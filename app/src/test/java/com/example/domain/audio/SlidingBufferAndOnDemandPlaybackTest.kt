package com.example.domain.audio

import android.content.Context
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.BookEntity
import com.example.data.local.ChapterChunkEntity
import com.example.data.local.ChapterEntity
import com.example.data.repository.AudiobookRepository
import com.example.data.storage.AudiobookStorageManager
import com.example.domain.model.AudioGenerationStatus
import com.example.domain.model.ChapterChunkStatus
import com.example.domain.model.NarratorVoice
import com.example.player.AudiobookMediaHelper
import com.example.player.AudiobookPlaybackController
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SlidingBufferAndOnDemandPlaybackTest {

  private lateinit var context: Context
  private lateinit var db: AppDatabase
  private lateinit var storageManager: AudiobookStorageManager
  private val trackedRepos = mutableListOf<AudiobookRepository>()
  private val trackedControllers = mutableListOf<AudiobookPlaybackController>()

  private fun trackRepo(repo: AudiobookRepository): AudiobookRepository =
    repo.also { trackedRepos += it }

  private fun trackController(controller: AudiobookPlaybackController): AudiobookPlaybackController =
    controller.also { trackedControllers += it }

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    storageManager = AudiobookStorageManager(context)
    storageManager.deleteBookAudioFiles("slide-book-1")
    storageManager.deleteBookAudioFiles("seq-book-1")
  }

  @After
  fun tearDown() {
    runBlocking {
      trackedControllers.forEach { it.awaitAndReleaseForTesting() }
      trackedControllers.clear()
      trackedRepos.forEach { it.awaitAndCloseForTesting() }
      trackedRepos.clear()
    }
    storageManager.deleteBookAudioFiles("slide-book-1")
    storageManager.deleteBookAudioFiles("seq-book-1")
    db.close()
  }

  @Test
  fun `sliding window bounds playlist RAM prunes finished chunks immediately and recovers out-of-window seeks from disk`() =
    runBlocking {
      val dao = db.audiobookDao()
      val bookId = "slide-book-1"
      val chapterId = "${bookId}_ch_1"
      val chunkCount = 16
      val pcmPerChunk = ByteArray(22_050 * 2 * 4) { 0x11 } // 4000ms por fragmento (total 64s)

      dao.insertBookWithChapters(
        BookEntity(
          id = bookId,
          title = "Libro Ventana Deslizante",
          author = "Autora Test",
          language = "es",
          publisher = "",
          description = "",
          coverImagePath = null,
          sourceFileName = "slide.epub",
          selectedVoice = NarratorVoice.FEMALE.name,
          totalChapters = 1,
          totalWords = 640,
          totalCharacters = chunkCount * 400,
          estimatedMinutes = 2,
          importedAt = 100L,
        ),
        listOf(
          ChapterEntity(
            id = chapterId,
            bookId = bookId,
            chapterIndex = 1,
            title = "Capítulo Largo",
            contentText = "Texto largo dividido en dieciséis fragmentos.",
            wordCount = 640,
            characterCount = chunkCount * 400,
            estimatedMinutes = 2,
            audioStatus = AudioGenerationStatus.GENERATING.name,
            audioFilePath = null,
            generatedChunks = chunkCount,
            totalChunks = chunkCount,
          )
        ),
      )

      val chunkEntities =
        (0 until chunkCount).map { idx ->
          val wav = storageManager.chunkWavFile(bookId, 1, idx)
          val info = WavAudioCodec.writePcmAsWav(pcmPerChunk, wav)
          ChapterChunkEntity(
            id = "${chapterId}_part_$idx",
            bookId = bookId,
            chapterId = chapterId,
            chapterIndex = 1,
            chunkIndex = idx,
            textContent = "F$idx " + "x".repeat(390),
            voiceName = NarratorVoice.FEMALE.name,
            status = ChapterChunkStatus.COMPLETED.name,
            wavFilePath = wav.absolutePath,
            byteSize = wav.length(),
            durationMs = info.durationMs,
          )
        }
      dao.upsertChunks(chunkEntities)

      var synthCalls = 0
      val zeroSynthProvider =
        object : AudioSynthesisProvider {
          override val isConfigured = true
          override val providerName = "Test"
          override val modelId = "es_ES-sharvard-medium"

          override suspend fun synthesizeFragment(
            text: String,
            voice: NarratorVoice,
            bookTitle: String,
            chapterTitle: String,
          ): Result<SynthesizedAudioPayload> {
            synthCalls += 1
            return Result.failure(IllegalStateException("No debe resintetizar WAVs ya en disco"))
          }
        }

      val repo = trackRepo(AudiobookRepository(context, dao, storageManager = storageManager, audioSynthesisProvider = zeroSynthProvider))
      val testPlayer = SlidingTestPlayer()
      val controller = trackController(AudiobookPlaybackController(context, repo, injectedPlayer = testPlayer))

      try {
        controller.playProgressiveChapter(bookId, chapterIndex = 1, startPositionMs = 0L)
        awaitCondition { controller.uiState.value.isPlaying && testPlayer.mediaItemCount > 0 }

        // 1. La ventana inicial NO carga los 16 fragmentos en RAM (sólo un subconjunto acotado).
        val initialWindowCount = testPlayer.mediaItemCount
        assertTrue(
          "La ventana inicial ($initialWindowCount) debe ser menor al total de fragmentos ($chunkCount)",
          initialWindowCount in 2 until chunkCount,
        )
        assertEquals("$bookId:1:chunk:0", testPlayer.currentMediaItem?.mediaId)

        // 2. Avanzar secuencialmente 6 fragmentos de forma natural:
        //    los fragmentos terminados deben liberarse inmediatamente y avanzar en los siguientes desde disco.
        repeat(6) {
          testPlayer.advanceAutoToNextChunk()
          Shadows.shadowOf(Looper.getMainLooper()).idle()
          delay(40L)
        }
        awaitCondition {
          controller.uiState.value.releasedChunksCount >= 5 &&
            controller.uiState.value.positionMs >= 20_000L
        }
        assertTrue(
          "El número de elementos en cola debe permanecer acotado (${testPlayer.mediaItemCount})",
          testPlayer.mediaItemCount in 2 until chunkCount,
        )
        assertTrue(controller.uiState.value.releasedChunksCount >= 5)
        val firstWindowItemChunkIdx =
          testPlayer.getMediaItemAt(0).mediaMetadata.extras?.getInt(AudiobookMediaHelper.EXTRA_CHUNK_INDEX) ?: -1
        assertTrue(
          "Los primeros fragmentos completados ya no están en cola (primer índice actual = $firstWindowItemChunkIdx)",
          firstWindowItemChunkIdx >= 5,
        )
        assertEquals(6 * 4_000L, controller.uiState.value.positionMs)

        // 3. Retroceder (SeekBar / -15s) a un fragmento ya liberado (pos = 5_000ms -> fragmento 1, offset 1_000ms):
        //    debe reconstruir la ventana desde los WAV en disco SIN resintetizar.
        controller.seekTo(5_000L)
        awaitCondition { controller.uiState.value.positionMs in 4_800L..5_200L }
        assertEquals("$bookId:1:chunk:1", testPlayer.currentMediaItem?.mediaId)
        assertEquals(0, synthCalls)
        assertTrue(testPlayer.mediaItemCount in 2 until chunkCount)

        // 4. Adelantar más allá de la ventana cargada (pos = 52_000ms -> fragmento 13):
        //    debe reubicar la ventana desde los WAV en disco SIN resintetizar.
        controller.seekTo(52_000L)
        awaitCondition { controller.uiState.value.positionMs in 51_800L..52_200L }
        assertEquals("$bookId:1:chunk:13", testPlayer.currentMediaItem?.mediaId)
        assertEquals(0, synthCalls)

        // 5. Detener / limpiar sesión libera el búfer temporal sin borrar WAVs ni progreso en disco.
        controller.stopIfBookCleared(bookId)
        assertEquals(0, testPlayer.mediaItemCount)
        assertEquals(0, controller.uiState.value.activeWindowSize)
        assertTrue(WavAudioCodec.isValidWavFile(storageManager.chunkWavFile(bookId, 1, 0)))
        assertTrue(WavAudioCodec.isValidWavFile(storageManager.chunkWavFile(bookId, 1, 15)))
      } finally {
        controller.awaitAndReleaseForTesting()
        testPlayer.release()
      }
    }

  @Test
  fun `sequential multi-chapter playback auto-advances from chapter 1 to ungenerated chapter 2 on demand without preconverting chapter 3`() =
    runBlocking {
      val dao = db.audiobookDao()
      val bookId = "seq-book-1"
      val ch1Wav = storageManager.chapterWavFile(bookId, 1)
      val ch1Info = WavAudioCodec.writePcmAsWav(ByteArray(22_050 * 2 * 2) { 3 }, ch1Wav)

      val ch2LongText =
        (1..18).joinToString(" ") { idx ->
          "Párrafo del capítulo dos número $idx para verificar conversión bajo demanda secuencial."
        }
      val ch3LongText = "Texto corto del capítulo tres que no debe preconvertirse antes de tiempo."

      dao.insertBookWithChapters(
        BookEntity(
          id = bookId,
          title = "Trilogía Secuencial",
          author = "Autora",
          language = "es",
          publisher = "",
          description = "",
          coverImagePath = null,
          sourceFileName = "seq.epub",
          selectedVoice = NarratorVoice.FEMALE.name,
          totalChapters = 3,
          totalWords = 300,
          totalCharacters = ch2LongText.length + ch3LongText.length + 30,
          estimatedMinutes = 3,
          importedAt = 100L,
        ),
        listOf(
          ChapterEntity(
            id = "${bookId}_ch_1",
            bookId = bookId,
            chapterIndex = 1,
            title = "Capítulo 1",
            contentText = "Capítulo uno listo en disco.",
            wordCount = 5,
            characterCount = 29,
            estimatedMinutes = 1,
            audioStatus = AudioGenerationStatus.READY.name,
            audioFilePath = ch1Wav.absolutePath,
            audioDurationMs = ch1Info.durationMs,
            audioSizeBytes = ch1Wav.length(),
            generatedChunks = 1,
            totalChunks = 1,
          ),
          ChapterEntity(
            id = "${bookId}_ch_2",
            bookId = bookId,
            chapterIndex = 2,
            title = "Capítulo 2",
            contentText = ch2LongText,
            wordCount = 220,
            characterCount = ch2LongText.length,
            estimatedMinutes = 2,
            audioStatus = AudioGenerationStatus.NOT_GENERATED.name,
            audioFilePath = null,
          ),
          ChapterEntity(
            id = "${bookId}_ch_3",
            bookId = bookId,
            chapterIndex = 3,
            title = "Capítulo 3",
            contentText = ch3LongText,
            wordCount = 12,
            characterCount = ch3LongText.length,
            estimatedMinutes = 1,
            audioStatus = AudioGenerationStatus.NOT_GENERATED.name,
            audioFilePath = null,
          ),
        ),
      )

      val synthChapters = mutableListOf<String>()
      val onDemandProvider =
        object : AudioSynthesisProvider {
          override val isConfigured = true
          override val providerName = "Piper Local"
          override val modelId = "es_ES-sharvard-medium"

          override suspend fun synthesizeFragment(
            text: String,
            voice: NarratorVoice,
            bookTitle: String,
            chapterTitle: String,
          ): Result<SynthesizedAudioPayload> {
            synchronized(synthChapters) {
              synthChapters += chapterTitle
            }
            delay(20L)
            val bytes = ByteArray(22_050 * 2 * 5) { 7 } // 5000ms por fragmento -> listo con 2 fragmentos
            return Result.success(
              SynthesizedAudioPayload(
                bytes = bytes,
                mimeType = "audio/L16;rate=22050;channels=1",
                sampleRate = 22_050,
                telemetry = SynthesisTelemetry(inferenceMs = 400L, audioDurationMs = 5_000L),
              )
            )
          }
        }

      val repo =
        trackRepo(
          AudiobookRepository(
            context = context,
            dao = dao,
            storageManager = storageManager,
            audioSynthesisProvider = onDemandProvider,
          )
        )
      val testPlayer = SlidingTestPlayer()
      val controller = trackController(AudiobookPlaybackController(context, repo, injectedPlayer = testPlayer))

      try {
        val loaded = repo.getBookWithChaptersOnce(bookId)!!
        controller.playBook(loaded.book, loaded.chapters, startChapterIndex = 1, startPositionMs = 0L)
        awaitCondition {
          controller.uiState.value.currentChapterIndex == 1 && controller.uiState.value.isPlaying
        }

        // Mientras suena el capítulo 1, el capítulo 2 y el capítulo 3 permanecen sin preconvertirse.
        assertTrue(synthChapters.isEmpty())
        assertEquals(0, dao.getChunksForChapterOnce("${bookId}_ch_2").size)
        assertEquals(0, dao.getChunksForChapterOnce("${bookId}_ch_3").size)

        // Simular que termina el capítulo 1 -> debe saltar automáticamente al capítulo 2 y convertirlo bajo demanda.
        testPlayer.simulatePlaybackEnded()
        awaitCondition(timeoutMs = 8_000L) {
          controller.uiState.value.currentChapterIndex == 2 &&
            controller.uiState.value.isPlaying &&
            testPlayer.mediaItemCount >= 1
        }

        assertTrue(synthChapters.contains("Capítulo 2"))
        assertFalse("Capítulo 3 NO debe preconvertirse mientras suena Capítulo 2", synthChapters.contains("Capítulo 3"))
        assertEquals(0, dao.getChunksForChapterOnce("${bookId}_ch_3").size)

        // Verificar progreso guardado en capítulo 2
        awaitCondition { dao.getBookOnce(bookId)?.lastPlayedChapterIndex == 2 }
      } finally {
        controller.awaitAndReleaseForTesting()
        testPlayer.release()
      }
    }

  private suspend fun awaitCondition(timeoutMs: Long = 5_000L, predicate: suspend () -> Boolean) {
    withTimeout(timeoutMs) {
      while (!predicate()) {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        delay(15L)
      }
    }
  }

  private class SlidingTestPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    private var items: MutableList<MediaItemData> = mutableListOf()
    private var currentIndex: Int = 0
    private var positionMs: Long = 0L
    private var playWhenReadyFlag: Boolean = false
    private var playbackStateInt: Int = Player.STATE_IDLE
    private var playbackParametersState: PlaybackParameters = PlaybackParameters.DEFAULT

    fun advanceAutoToNextChunk() {
      if (currentIndex + 1 < items.size) {
        currentIndex += 1
        positionMs = 0L
        invalidateState()
      }
    }

    fun simulatePlaybackEnded() {
      playbackStateInt = Player.STATE_ENDED
      invalidateState()
    }

    override fun getState(): State {
      return State.Builder()
        .setAvailableCommands(
          Player.Commands.Builder()
            .addAll(
              Player.COMMAND_PLAY_PAUSE,
              Player.COMMAND_PREPARE,
              Player.COMMAND_STOP,
              Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
              Player.COMMAND_SEEK_TO_MEDIA_ITEM,
              Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
              Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
              Player.COMMAND_SET_MEDIA_ITEM,
              Player.COMMAND_CHANGE_MEDIA_ITEMS,
              Player.COMMAND_SET_SPEED_AND_PITCH,
              Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
              Player.COMMAND_GET_TIMELINE,
              Player.COMMAND_GET_METADATA,
            )
            .build()
        )
        .setPlayWhenReady(playWhenReadyFlag, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        .setPlaybackState(playbackStateInt)
        .setPlaybackParameters(playbackParametersState)
        .setPlaylist(items.toList())
        .setCurrentMediaItemIndex(if (items.isEmpty()) C.INDEX_UNSET else currentIndex)
        .setContentPositionMs(positionMs)
        .build()
    }

    override fun handleSetMediaItems(
      mediaItems: MutableList<MediaItem>,
      startIndex: Int,
      startPositionMs: Long,
    ): ListenableFuture<*> {
      items =
        mediaItems
          .map { item ->
            val durUs =
              (item.mediaMetadata.extras?.getLong(AudiobookMediaHelper.EXTRA_CHUNK_DURATION_MS, 4_000L)
                ?: 4_000L) * 1_000L
            MediaItemData.Builder(item.mediaId).setMediaItem(item).setDurationUs(durUs).build()
          }
          .toMutableList()
      currentIndex = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
      positionMs = startPositionMs.coerceAtLeast(0L)
      invalidateState()
      return Futures.immediateVoidFuture()
    }

    override fun handleAddMediaItems(
      index: Int,
      mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<*> {
      val added =
        mediaItems.map { item ->
          val durUs =
            (item.mediaMetadata.extras?.getLong(AudiobookMediaHelper.EXTRA_CHUNK_DURATION_MS, 4_000L)
              ?: 4_000L) * 1_000L
          MediaItemData.Builder(item.mediaId).setMediaItem(item).setDurationUs(durUs).build()
        }
      items.addAll(index.coerceIn(0, items.size), added)
      invalidateState()
      return Futures.immediateVoidFuture()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
      val safeFrom = fromIndex.coerceIn(0, items.size)
      val safeTo = toIndex.coerceIn(safeFrom, items.size)
      if (safeFrom < safeTo) {
        items.subList(safeFrom, safeTo).clear()
        val removed = safeTo - safeFrom
        if (currentIndex >= safeTo) {
          currentIndex -= removed
        } else if (currentIndex >= safeFrom) {
          currentIndex = safeFrom.coerceAtMost((items.size - 1).coerceAtLeast(0))
          positionMs = 0L
        }
      }
      invalidateState()
      return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
      playbackStateInt = Player.STATE_READY
      invalidateState()
      return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
      playWhenReadyFlag = playWhenReady
      invalidateState()
      return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
      playbackParametersState = playbackParameters
      invalidateState()
      return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
      mediaItemIndex: Int,
      positionMs: Long,
      seekCommand: Int,
    ): ListenableFuture<*> {
      if (items.isNotEmpty() && mediaItemIndex in items.indices) {
        currentIndex = mediaItemIndex
      }
      this.positionMs = if (positionMs == C.TIME_UNSET) 0L else positionMs.coerceAtLeast(0L)
      invalidateState()
      return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
      playWhenReadyFlag = false
      playbackStateInt = Player.STATE_IDLE
      invalidateState()
      return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
  }
}
