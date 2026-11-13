package com.example.domain.audio

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.BookEntity
import com.example.data.local.ChapterEntity
import com.example.data.repository.AudiobookRepository
import com.example.data.storage.AudiobookStorageManager
import com.example.domain.model.AudioGenerationStatus
import com.example.domain.model.NarratorVoice
import com.example.player.AudiobookPlaybackController
import com.example.player.AudiobookPlaybackService
import com.example.ui.screens.BookDetailScreen
import com.example.ui.theme.AudiolibroTheme
import com.example.ui.viewmodel.BookDetailViewModel
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AudiobookPhase2RobolectricTest {

  @get:Rule val composeRule = createComposeRule()

  private lateinit var context: Context
  private lateinit var db: AppDatabase
  private lateinit var storageManager: AudiobookStorageManager

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    storageManager = AudiobookStorageManager(context)
    storageManager.deleteBookAudioFiles("book-1")
    storageManager.deleteBookAudioFiles("book-2")
  }

  @After
  fun tearDown() {
    storageManager.deleteBookAudioFiles("book-1")
    storageManager.deleteBookAudioFiles("book-2")
    db.close()
  }

  @Test
  fun `configured Firebase provider uses gemini-3_8-flash-tts configures App Check and never fakes failed chapters`() =
    runTest {
      val dao = db.audiobookDao()
      insertSampleBook(dao, "book-1", NarratorVoice.FEMALE)

      val debugToken = "00000000-0000-0000-0000-000000000001"
      val launchIntent =
        android.content.Intent().putExtra("FIREBASE_APPCHECK_DEBUG_TOKEN", debugToken)
      com.example.firebase.configureAppCheck(context, launchIntent)

      val app = com.google.firebase.FirebaseApp.getInstance()
      val prefs =
        context.getSharedPreferences(
          "com.google.firebase.appcheck.debug.store.${app.persistenceKey}",
          Context.MODE_PRIVATE,
        )
      assertEquals(
        debugToken,
        prefs.getString("com.google.firebase.appcheck.debug.DEBUG_SECRET", null),
      )

      val provider = FirebaseAiAudioSynthesisProvider(context)
      assertEquals("gemini-3.8-flash-tts", provider.modelId)
      assertTrue(provider.isConfigured)

      val failingProvider =
        object : AudioSynthesisProvider {
          override val isConfigured = true
          override val providerName = "Firebase AI Logic"
          override val modelId = "gemini-3.8-flash-tts"

          override suspend fun synthesizeFragment(
            text: String,
            voice: NarratorVoice,
            bookTitle: String,
            chapterTitle: String,
          ): Result<SynthesizedAudioPayload> =
            Result.failure(IllegalStateException("Error de App Check o red al contactar Gemini"))
        }

      val orchestrator = AudiobookGenerationOrchestrator(dao, storageManager, failingProvider)
      val result = orchestrator.generateBookAudiobook("book-1")

      assertTrue(result.isFailure)
      val chapters = dao.getChaptersOnce("book-1")
      assertEquals(AudioGenerationStatus.ERROR.name, chapters[0].audioStatus)
      assertNull(chapters[0].audioFilePath)
      assertEquals(0L, storageManager.calculateBookAudioBytes("book-1"))
    }

  @Test
  fun `voice selection persists Charon for male and Kore for female and drives synthesis`() = runTest {
    val dao = db.audiobookDao()
    insertSampleBook(dao, "book-1", NarratorVoice.FEMALE)

    assertEquals("Kore", NarratorVoice.FEMALE.geminiVoiceName)
    assertEquals("Charon", NarratorVoice.MALE.geminiVoiceName)
    assertEquals(2, NarratorVoice.entries.size)

    dao.updateSelectedVoice("book-1", NarratorVoice.MALE.name)
    val updated = dao.getBookOnce("book-1")
    assertEquals(NarratorVoice.MALE.name, updated?.selectedVoice)

    val observedVoices = mutableListOf<NarratorVoice>()
    val testProvider =
      object : AudioSynthesisProvider {
        override val isConfigured = true
        override val providerName = "TestGemini"
        override val modelId = "gemini-3.8-flash-tts"

        override suspend fun synthesizeFragment(
          text: String,
          voice: NarratorVoice,
          bookTitle: String,
          chapterTitle: String,
        ): Result<SynthesizedAudioPayload> {
          observedVoices += voice
          return Result.success(
            SynthesizedAudioPayload(
              bytes = ByteArray(4800) { 1 },
              mimeType = "audio/L16;rate=24000",
              sampleRate = 24_000,
            )
          )
        }
      }

    val orchestrator = AudiobookGenerationOrchestrator(dao, storageManager, testProvider)
    val res = orchestrator.generateBookAudiobook("book-1")
    assertTrue(res.isSuccess)
    assertTrue(observedVoices.isNotEmpty())
    assertTrue(observedVoices.all { it == NarratorVoice.MALE })
  }

  @Test
  fun `resumable generation preserves fragment order skips saved chunks and exports full WAV`() =
    runTest {
      val dao = db.audiobookDao()
      val longParagraph =
        (1..25).joinToString(" ") { idx ->
          "Oración número $idx con contenido narrativo completo para forzar varios fragmentos en orden."
        }
      dao.insertBookWithChapters(
        BookEntity(
          id = "book-2",
          title = "Crónica del Sur",
          author = "Autor Prueba",
          language = "Español",
          publisher = "",
          description = "",
          coverImagePath = null,
          sourceFileName = "cronica.epub",
          selectedVoice = NarratorVoice.FEMALE.name,
          totalChapters = 2,
          totalWords = 300,
          totalCharacters = longParagraph.length + 50,
          estimatedMinutes = 2,
          importedAt = 100L,
        ),
        listOf(
          ChapterEntity(
            id = "book-2_ch_1",
            bookId = "book-2",
            chapterIndex = 1,
            title = "Capítulo 1",
            contentText = "Texto breve del primer capítulo.",
            wordCount = 5,
            characterCount = 32,
            estimatedMinutes = 1,
            audioStatus = AudioGenerationStatus.NOT_GENERATED.name,
            audioFilePath = null,
          ),
          ChapterEntity(
            id = "book-2_ch_2",
            bookId = "book-2",
            chapterIndex = 2,
            title = "Capítulo 2",
            contentText = longParagraph,
            wordCount = 295,
            characterCount = longParagraph.length,
            estimatedMinutes = 2,
            audioStatus = AudioGenerationStatus.NOT_GENERATED.name,
            audioFilePath = null,
          ),
        ),
      )

      val expectedCh2Fragments = ChapterTextChunker.splitChapter(longParagraph)
      assertTrue(expectedCh2Fragments.size >= 2)

      val synthesizedTexts = mutableListOf<String>()
      var failOnSecondChunkOfCh2 = true

      val flakyProvider =
        object : AudioSynthesisProvider {
          override val isConfigured = true
          override val providerName = "TestGemini"
          override val modelId = "gemini-3.8-flash-tts"

          override suspend fun synthesizeFragment(
            text: String,
            voice: NarratorVoice,
            bookTitle: String,
            chapterTitle: String,
          ): Result<SynthesizedAudioPayload> {
            if (failOnSecondChunkOfCh2 && text == expectedCh2Fragments[1]) {
              failOnSecondChunkOfCh2 = false
              return Result.failure(IllegalStateException("Corte temporal de red"))
            }
            synthesizedTexts += text
            return Result.success(
              SynthesizedAudioPayload(
                bytes = ByteArray(9600) { 2 },
                mimeType = "audio/L16;rate=24000",
                sampleRate = 24_000,
              )
            )
          }
        }

      val orchestrator = AudiobookGenerationOrchestrator(dao, storageManager, flakyProvider)

      val firstAttempt = orchestrator.generateBookAudiobook("book-2")
      assertTrue(firstAttempt.isFailure)

      val afterFailCh1 = dao.getChaptersOnce("book-2").first { it.chapterIndex == 1 }
      val afterFailCh2 = dao.getChaptersOnce("book-2").first { it.chapterIndex == 2 }
      assertEquals(AudioGenerationStatus.READY.name, afterFailCh1.audioStatus)
      assertEquals(AudioGenerationStatus.ERROR.name, afterFailCh2.audioStatus)
      assertEquals(1, afterFailCh2.generatedChunks)
      assertEquals(2, synthesizedTexts.size)

      val resumeAttempt = orchestrator.generateBookAudiobook("book-2")
      assertTrue(resumeAttempt.isSuccess)

      assertEquals(1 + expectedCh2Fragments.size, synthesizedTexts.size)
      assertEquals("Texto breve del primer capítulo.", synthesizedTexts[0])
      assertEquals(expectedCh2Fragments, synthesizedTexts.drop(1))

      val readyChapters = dao.getChaptersOnce("book-2").map { it.toDomain() }
      assertTrue(readyChapters.all { it.audioStatus == AudioGenerationStatus.READY })
      assertTrue(storageManager.calculateBookAudioBytes("book-2") > 0L)

      val exportedChapterFile = File(context.cacheDir, "export_ch1.wav")
      val exportChRes =
        storageManager.exportChapterWav(readyChapters[0], Uri.fromFile(exportedChapterFile))
      assertTrue(exportChRes.isSuccess)
      assertTrue(WavAudioCodec.isValidWavFile(exportedChapterFile))

      val exportedFullFile = File(context.cacheDir, "export_full.wav")
      val exportFullRes =
        storageManager.exportFullAudiobookWav(readyChapters, Uri.fromFile(exportedFullFile))
      assertTrue(exportFullRes.isSuccess)
      val fullWavInfo = WavAudioCodec.readWavInfo(exportedFullFile)
      assertNotNull(fullWavInfo)
      assertEquals(readyChapters.sumOf { it.audioDurationMs }, fullWavInfo!!.durationMs)

      dao.clearBookGeneratedAudio("book-2")
      storageManager.deleteBookAudioFiles("book-2")
      assertEquals(0L, storageManager.calculateBookAudioBytes("book-2"))
      assertNotNull(dao.getBookOnce("book-2"))
      val chaptersAfterClear = dao.getChaptersOnce("book-2")
      assertEquals(2, chaptersAfterClear.size)
      assertTrue(chaptersAfterClear.all { it.audioStatus == AudioGenerationStatus.NOT_GENERATED.name })

      exportedChapterFile.delete()
      exportedFullFile.delete()
    }

  @Test
  fun `playback controller plays navigates chapters seeks and persists resume position`() = runTest {
    val dao = db.audiobookDao()
    dao.insertBookWithChapters(
      BookEntity(
        id = "book-1",
        title = "Libro de Prueba",
        author = "Autora",
        language = "Español",
        publisher = "Editorial",
        description = "Descripción",
        coverImagePath = null,
        sourceFileName = "prueba.epub",
        selectedVoice = NarratorVoice.FEMALE.name,
        totalChapters = 2,
        totalWords = 24,
        totalCharacters = 140,
        estimatedMinutes = 1,
        importedAt = 100L,
      ),
      listOf(
        ChapterEntity(
          id = "book-1_ch_1",
          bookId = "book-1",
          chapterIndex = 1,
          title = "Capítulo 1",
          contentText = "Primer capítulo.",
          wordCount = 2,
          characterCount = 16,
          estimatedMinutes = 1,
          audioStatus = AudioGenerationStatus.NOT_GENERATED.name,
          audioFilePath = null,
        ),
        ChapterEntity(
          id = "book-1_ch_2",
          bookId = "book-1",
          chapterIndex = 2,
          title = "Capítulo 2",
          contentText = "Segundo capítulo.",
          wordCount = 2,
          characterCount = 17,
          estimatedMinutes = 1,
          audioStatus = AudioGenerationStatus.NOT_GENERATED.name,
          audioFilePath = null,
        ),
      ),
    )

    val wav1 = storageManager.chapterWavFile("book-1", 1)
    val wav2 = storageManager.chapterWavFile("book-1", 2)
    val info1 = WavAudioCodec.writePcmAsWav(ByteArray(48_000 * 2) { 3 }, wav1) // 2000ms
    val info2 = WavAudioCodec.writePcmAsWav(ByteArray(48_000 * 2) { 4 }, wav2) // 2000ms

    dao.updateChapterAudioState(
      "book-1_ch_1",
      AudioGenerationStatus.READY.name,
      wav1.absolutePath,
      info1.durationMs,
      wav1.length(),
      1,
      1,
      null,
    )
    dao.updateChapterAudioState(
      "book-1_ch_2",
      AudioGenerationStatus.READY.name,
      wav2.absolutePath,
      info2.durationMs,
      wav2.length(),
      1,
      1,
      null,
    )

    val testPlayer = InMemoryPlaylistPlayer()
    val repo = AudiobookRepository(context, dao, storageManager = storageManager)
    val controller = AudiobookPlaybackController(context, repo, injectedPlayer = testPlayer)
    val book = dao.getBookOnce("book-1")!!.toDomain()
    val chapters = dao.getChaptersOnce("book-1").map { it.toDomain() }

    controller.playBook(book, chapters, startChapterIndex = 1, startPositionMs = 500L)
    var state = controller.uiState.first { it.activeBookId == "book-1" }
    assertEquals(1, state.currentChapterIndex)
    assertTrue(state.isPlaying)
    assertEquals(500L, state.positionMs)

    controller.seekForward(400L)
    state = controller.uiState.value
    assertEquals(900L, state.positionMs)

    controller.nextChapter()
    state = controller.uiState.value
    assertEquals(2, state.currentChapterIndex)
    assertEquals("Capítulo 2", state.currentChapterTitle)

    controller.togglePlayPause()
    assertFalse(controller.uiState.value.isPlaying)

    controller.jumpToChapter(1)
    assertEquals(1, controller.uiState.value.currentChapterIndex)
    assertTrue(controller.uiState.value.isPlaying)

    repo.updatePlaybackPosition("book-1", 2, 1100L)
    val savedBook = dao.getBookOnce("book-1")
    assertEquals(2, savedBook?.lastPlayedChapterIndex)
    assertEquals(1100L, savedBook?.lastPlayedPositionMs)

    testPlayer.release()
  }

  @Test
  fun `AudiobookPlaybackService initializes MediaSession cleanly`() {
    val service = Robolectric.buildService(AudiobookPlaybackService::class.java).create().get()
    assertNotNull(service)
    service.onDestroy()
  }

  @Test
  fun `BookDetailScreen renders Spanish generation controls and switches voice cleanly`() = runTest {
    val dao = db.audiobookDao()
    insertSampleBook(dao, "book-1", NarratorVoice.FEMALE)
    val repo = AudiobookRepository(context, dao, storageManager = storageManager)
    val testPlayer = InMemoryPlaylistPlayer()
    val controller = AudiobookPlaybackController(context, repo, injectedPlayer = testPlayer)
    val vm = BookDetailViewModel("book-1", repo, controller)

    composeRule.setContent {
      AudiolibroTheme {
        BookDetailScreen(
          viewModel = vm,
          onNavigateBack = {},
          onOpenChapterText = { _, _ -> },
          onOpenPlayer = { _, _ -> },
        )
      }
    }

    composeRule.waitUntil(timeoutMillis = 5_000L) {
      composeRule.onAllNodesWithTag("voice_male_card").fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("voice_male_card"))
    composeRule.onNodeWithTag("voice_male_card").assertIsDisplayed().performClick()
    composeRule
      .onNode(hasScrollToNodeAction())
      .performScrollToNode(hasTestTag("generate_audiobook_button"))
    composeRule.onNodeWithTag("generate_audiobook_button").assertIsDisplayed()
    composeRule.waitUntil(timeoutMillis = 5_000L) {
      runCatching {
        kotlinx.coroutines.runBlocking { dao.getBookOnce("book-1")?.selectedVoice == NarratorVoice.MALE.name }
      }.getOrDefault(false)
    }
    assertEquals(NarratorVoice.MALE.name, dao.getBookOnce("book-1")?.selectedVoice)
    testPlayer.release()
  }

  @Test
  fun `imported EPUB voice WAV file and resume position persist across database close and reopen`() =
    runTest {
      val dbFile = File(context.cacheDir, "persist_reopen_test.db")
      dbFile.delete()
      val epubFile = File(context.cacheDir, "integracion_real.epub")
      java.util.zip.ZipOutputStream(epubFile.outputStream()).use { zos ->
        fun put(name: String, content: String) {
          zos.putNextEntry(java.util.zip.ZipEntry(name))
          zos.write(content.toByteArray())
          zos.closeEntry()
        }
        put("mimetype", "application/epub+zip")
        put(
          "META-INF/container.xml",
          """<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
        )
        put(
          "content.opf",
          """<package xmlns="http://www.idpf.org/2007/opf" version="2.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Prueba Real</dc:title><dc:creator>Autora</dc:creator><dc:language>es</dc:language></metadata><manifest><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c1"/></spine></package>""",
        )
        put(
          "c1.xhtml",
          """<html xmlns="http://www.w3.org/1999/xhtml"><body><h1>Capítulo Uno</h1><p>En un lugar tranquilo junto al mar.</p></body></html>""",
        )
      }

      var firstDb =
        Room.databaseBuilder(context, AppDatabase::class.java, dbFile.absolutePath)
          .allowMainThreadQueries()
          .build()
      val firstRepo = AudiobookRepository(context, firstDb.audiobookDao(), storageManager = storageManager)
      val importResult = firstRepo.importEpub(Uri.fromFile(epubFile))
      assertTrue(importResult.isSuccess)
      val importedBook = importResult.getOrThrow()

      firstRepo.updateSelectedVoice(importedBook.id, NarratorVoice.MALE)
      val chapter = firstDb.audiobookDao().getChaptersOnce(importedBook.id).first()
      val chapterWav = storageManager.chapterWavFile(importedBook.id, chapter.chapterIndex)
      val wavInfo = WavAudioCodec.writePcmAsWav(ByteArray(24_000) { 5 }, chapterWav)
      firstDb.audiobookDao().updateChapterAudioState(
        chapterId = chapter.id,
        audioStatus = AudioGenerationStatus.READY.name,
        audioFilePath = chapterWav.absolutePath,
        audioDurationMs = wavInfo.durationMs,
        audioSizeBytes = chapterWav.length(),
        generatedChunks = 1,
        totalChunks = 1,
        generationError = null,
      )
      firstRepo.updatePlaybackPosition(importedBook.id, chapter.chapterIndex, 320L)
      firstDb.close()

      // Simulate app restart with fresh AppDatabase + Repository
      val reopenedDb =
        Room.databaseBuilder(context, AppDatabase::class.java, dbFile.absolutePath)
          .allowMainThreadQueries()
          .build()
      val reopenedRepo =
        AudiobookRepository(context, reopenedDb.audiobookDao(), storageManager = storageManager)
      val reopenedBookWithChapters = reopenedRepo.observeBookWithChapters(importedBook.id).first()
      assertNotNull(reopenedBookWithChapters)
      assertEquals(NarratorVoice.MALE, reopenedBookWithChapters!!.book.selectedVoice)
      assertEquals(1, reopenedBookWithChapters.book.lastPlayedChapterIndex)
      assertEquals(320L, reopenedBookWithChapters.book.lastPlayedPositionMs)

      val reopenedChapter = reopenedBookWithChapters.chapters.first()
      assertEquals(AudioGenerationStatus.READY, reopenedChapter.audioStatus)
      assertTrue(WavAudioCodec.isValidWavFile(File(reopenedChapter.audioFilePath!!)))
      assertTrue(reopenedRepo.getBookAudioStorageBytes(importedBook.id) > 0L)

      reopenedDb.close()
      storageManager.deleteBookAudioFiles(importedBook.id)
      dbFile.delete()
      epubFile.delete()
    }

  private suspend fun insertSampleBook(
    dao: com.example.data.local.AudiobookDao,
    bookId: String,
    voice: NarratorVoice,
  ) {
    dao.insertBookWithChapters(
      BookEntity(
        id = bookId,
        title = "Libro de Prueba",
        author = "Autora",
        language = "Español",
        publisher = "Editorial",
        description = "Descripción",
        coverImagePath = null,
        sourceFileName = "prueba.epub",
        selectedVoice = voice.name,
        totalChapters = 1,
        totalWords = 12,
        totalCharacters = 70,
        estimatedMinutes = 1,
        importedAt = 100L,
      ),
      listOf(
        ChapterEntity(
          id = "${bookId}_ch_1",
          bookId = bookId,
          chapterIndex = 1,
          title = "Capítulo Inicial",
          contentText = "Este es un texto real extraído del libro EPUB para probar la narración.",
          wordCount = 12,
          characterCount = 70,
          estimatedMinutes = 1,
          audioStatus = AudioGenerationStatus.NOT_GENERATED.name,
          audioFilePath = null,
        )
      ),
    )
  }

  private class InMemoryPlaylistPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    private var items: List<MediaItemData> = emptyList()
    private var currentIndex: Int = 0
    private var positionMs: Long = 0L
    private var playWhenReadyFlag: Boolean = false
    private var playbackStateInt: Int = Player.STATE_IDLE

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
              Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
              Player.COMMAND_GET_TIMELINE,
              Player.COMMAND_GET_METADATA,
            )
            .build()
        )
        .setPlayWhenReady(playWhenReadyFlag, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        .setPlaybackState(playbackStateInt)
        .setPlaylist(items)
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
        mediaItems.map { item ->
          MediaItemData.Builder(item.mediaId)
            .setMediaItem(item)
            .setDurationUs(2_000_000L)
            .build()
        }
      currentIndex = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
      positionMs = startPositionMs.coerceAtLeast(0L)
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

    override fun handleRelease(): ListenableFuture<*> {
      return Futures.immediateVoidFuture()
    }
  }

  @Test
  fun `generation exception during initial prep sets isRunning false and stores error message`() = runTest {
    val dao = db.audiobookDao()
    val repo = AudiobookRepository(context, dao)
    repo.startAudiobookGeneration("non-existent-book-id")
    val progress = repo.generationProgress.map { it["non-existent-book-id"] }.first { it != null && !it.isRunning }
    assertNotNull(progress)
    assertFalse(progress!!.isRunning)
    assertNotNull(progress.errorMessage)
  }
}
