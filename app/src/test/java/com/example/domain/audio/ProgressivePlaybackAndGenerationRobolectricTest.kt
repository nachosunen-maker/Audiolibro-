package com.example.domain.audio

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.BookEntity
import com.example.data.local.ChapterChunkEntity
import com.example.data.local.ChapterEntity
import com.example.data.repository.AudiobookRepository
import com.example.data.storage.AudiobookStorageManager
import com.example.domain.model.AudioGenerationStatus
import com.example.domain.model.ChapterChunkStatus
import com.example.domain.model.GenerationMode
import com.example.domain.model.GenerationTaskStatus
import com.example.domain.model.NarratorVoice
import com.example.domain.model.ProgressiveChapterPhase
import com.example.player.AudiobookMediaHelper
import com.example.player.AudiobookPlaybackController
import com.example.ui.screens.BookDetailScreen
import com.example.ui.screens.ProgressiveChapterContent
import com.example.ui.theme.AudiolibroTheme
import com.example.ui.viewmodel.BookDetailViewModel
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProgressivePlaybackAndGenerationRobolectricTest {

  @get:Rule val composeRule = createComposeRule()

  private lateinit var context: Context
  private lateinit var db: AppDatabase
  private lateinit var storageManager: AudiobookStorageManager
  private val trackedRepos = mutableListOf<AudiobookRepository>()

  private fun trackRepo(repo: AudiobookRepository): AudiobookRepository =
    repo.also { trackedRepos += it }

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    storageManager = AudiobookStorageManager(context)
    storageManager.deleteBookAudioFiles("prog-book-1")
    storageManager.deleteBookAudioFiles("prog-book-err")
  }

  @After
  fun tearDown() {
    runBlocking {
      trackedRepos.forEach { it.awaitAndCloseForTesting() }
      trackedRepos.clear()
    }
    storageManager.deleteBookAudioFiles("prog-book-1")
    storageManager.deleteBookAudioFiles("prog-book-err")
    db.close()
  }

  @Test
  fun `progressive loading of advanced chapter 4 starts playback early appends ordered live chunks stops on pause and finishes WAV`() =
    runBlocking {
      val dao = db.audiobookDao()
      val longText =
        (1..28).joinToString(" ") { idx ->
          "Pasaje narrativo extenso número $idx del capítulo cuatro para probar fragmentos continuos sin cortes."
        }
      val expectedChunks = ChapterTextChunker.splitChapter(longText)
      assertTrue("Se esperaban >= 4 fragmentos, hubo ${expectedChunks.size}", expectedChunks.size >= 4)

      val chapters =
        (1..5).map { idx ->
          ChapterEntity(
            id = "prog-book-1_ch_$idx",
            bookId = "prog-book-1",
            chapterIndex = idx,
            title = if (idx == 4) "El Desenlace Inesperado" else "Capítulo $idx",
            contentText = if (idx == 4) longText else "Texto corto del capítulo $idx.",
            wordCount = if (idx == 4) 300 else 10,
            characterCount = if (idx == 4) longText.length else 30,
            estimatedMinutes = 2,
            audioStatus = AudioGenerationStatus.NOT_GENERATED.name,
            audioFilePath = null,
          )
        }
      dao.insertBookWithChapters(
        BookEntity(
          id = "prog-book-1",
          title = "La Brújula del Sur",
          author = "Autora",
          language = "es",
          publisher = "",
          description = "",
          coverImagePath = null,
          sourceFileName = "brujula.epub",
          selectedVoice = NarratorVoice.FEMALE.name,
          totalChapters = 5,
          totalWords = 340,
          totalCharacters = longText.length + 120,
          estimatedMinutes = 4,
          importedAt = 100L,
        ),
        chapters,
      )

      // Cada fragmento sintetizado equivale a 4.5 s (198_450 bytes a 22.050 Hz PCM-16 mono)
      // con síntesis rápida => AdaptiveBufferPolicy autorizará Play tras 2 fragmentos (~9 s >= 8 s).
      val chunkBytesSize = 22_050 * 2 * 45 / 10 // 4500 ms
      val synthesizedOrder = mutableListOf<String>()
      val provider =
        object : AudioSynthesisProvider {
          override val isConfigured = true
          override val providerName = "Piper VITS + sherpa-onnx (Local)"
          override val modelId = "es_ES-sharvard-medium"

          override suspend fun synthesizeFragment(
            text: String,
            voice: NarratorVoice,
            bookTitle: String,
            chapterTitle: String,
          ): Result<SynthesizedAudioPayload> {
            synthesizedOrder += text
            delay(25L)
            return Result.success(
              SynthesizedAudioPayload(
                bytes = ByteArray(chunkBytesSize) { (synthesizedOrder.size % 64).toByte() },
                mimeType = "audio/L16;rate=22050;channels=1",
                sampleRate = 22_050,
                telemetry =
                  SynthesisTelemetry(
                    phonemizationMs = 10L,
                    inferenceMs = 450L,
                    pcmEncodingMs = 4L,
                    audioDurationMs = 4_500L,
                    sampleCount = chunkBytesSize / 2,
                    textChars = text.length,
                  ),
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
            audioSynthesisProvider = provider,
          )
        )
      val testPlayer = InMemoryPlaylistPlayer()
      val controller = AudiobookPlaybackController(context, repo, injectedPlayer = testPlayer)

      try {
        repo.startProgressiveChapterLoading("prog-book-1", chapterIndex = 4)

        // 1. Esperar a que esté habilitado para reproducir sin haber generado todavía todo el capítulo.
        awaitCondition {
          val st = repo.progressiveChapterState.value
          st != null && st.chapterIndex == 4 && st.canStartPlayback && !st.isCompleteChapterReady
        }

        val readyState = repo.progressiveChapterState.value!!
        assertTrue(readyState.chunksCompleted in 2 until expectedChunks.size)
        assertTrue(readyState.bufferedDurationMs >= 8_000L)
        assertEquals(1, dao.getChunksForChapterOnce("prog-book-1_ch_4").count { it.chunkIndex == 0 })
        // Capítulos 1, 2, 3 y 5 siguen intactos sin sintetizarse.
        assertEquals(0, dao.getChunksForChapterOnce("prog-book-1_ch_1").size)
        assertEquals(0, dao.getChunksForChapterOnce("prog-book-1_ch_2").size)

        // 2. Iniciar reproducción progresiva antes de terminar el capítulo y verificar que no se reinicia.
        val initialReadyCount = readyState.chunksCompleted
        controller.playProgressiveChapter("prog-book-1", chapterIndex = 4)
        awaitCondition {
          controller.uiState.value.isProgressiveActive &&
            controller.uiState.value.isPlaying &&
            testPlayer.mediaItemCount >= initialReadyCount
        }

        // Notificar reproducción activa en el fragmento 0 -> lookahead continúa en segundo plano
        // agregando el resto de fragmentos por orden sin reiniciar `setMediaItems`.
        val setCallsAfterStart = testPlayer.setMediaItemsCallCount
        awaitCondition(timeoutMs = 8_000L) {
          repo.progressiveChapterState.value?.isCompleteChapterReady == true &&
            testPlayer.mediaItemCount == expectedChunks.size
        }

        assertEquals(
          "No debió reiniciarse setMediaItems al agregar fragmentos en vivo",
          setCallsAfterStart,
          testPlayer.setMediaItemsCallCount,
        )
        assertTrue(testPlayer.addMediaItemsCallCount >= 1)
        val queuedIds = testPlayer.currentMediaIds()
        assertEquals(
          (0 until expectedChunks.size).map { idx -> "prog-book-1:4:chunk:$idx" },
          queuedIds,
        )
        assertEquals(expectedChunks, synthesizedOrder)

        val ch4 = dao.getChapterOnce("prog-book-1", 4)!!
        assertEquals(AudioGenerationStatus.READY.name, ch4.audioStatus)
        assertTrue(WavAudioCodec.isValidWavFile(File(ch4.audioFilePath!!)))

        // Verificar que una tarea PROGRESSIVE_CHAPTER no entra en recuperación automática infinita de FGS/WorkManager.
        assertTrue(dao.getRecoverableGenerationTasksOnce().isEmpty())

        // Buscar a una posición avanzada del capítulo y validar persistencia exacta.
        controller.seekTo(9_500L)
        controller.togglePlayPause()
        controller.awaitPersistenceForTesting()
        val persistedBook = dao.getBookOnce("prog-book-1")!!
        assertEquals(4, persistedBook.lastPlayedChapterIndex)
        assertTrue(persistedBook.lastPlayedPositionMs >= 9_000L)
      } finally {
        controller.releaseForTesting()
        testPlayer.release()
      }
    }

  @Test
  fun `pausing generation halts lookahead and exiting progressive screen stops background loop cleanly`() =
    runBlocking {
      val dao = db.audiobookDao()
      val longText =
        (1..26).joinToString(" ") { idx ->
          "Línea literaria número $idx para verificar pausa y salida limpia en carga progresiva."
        }
      dao.insertBookWithChapters(
        BookEntity(
          id = "prog-book-1",
          title = "Libro Pausa",
          author = "Autora",
          language = "es",
          publisher = "",
          description = "",
          coverImagePath = null,
          sourceFileName = "pausa.epub",
          selectedVoice = NarratorVoice.MALE.name,
          totalChapters = 1,
          totalWords = 260,
          totalCharacters = longText.length,
          estimatedMinutes = 2,
          importedAt = 100L,
        ),
        listOf(
          ChapterEntity(
            id = "prog-book-1_ch_1",
            bookId = "prog-book-1",
            chapterIndex = 1,
            title = "Capítulo Único",
            contentText = longText,
            wordCount = 260,
            characterCount = longText.length,
            estimatedMinutes = 2,
            audioStatus = AudioGenerationStatus.NOT_GENERATED.name,
            audioFilePath = null,
          )
        ),
      )

      val gate = AtomicBoolean(false)
      var synthCount = 0
      val slowProvider =
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
            synthCount++
            if (synthCount == 2) {
              while (!gate.get()) delay(20L)
            }
            val bytes = ByteArray(22_050 * 2 * 4) { 3 } // 4 s por fragmento
            return Result.success(
              SynthesizedAudioPayload(
                bytes = bytes,
                mimeType = "audio/L16;rate=22050;channels=1",
                sampleRate = 22_050,
                telemetry = SynthesisTelemetry(inferenceMs = 300L, audioDurationMs = 4_000L),
              )
            )
          }
        }

      val repo = trackRepo(AudiobookRepository(context, dao, storageManager = storageManager, audioSynthesisProvider = slowProvider))
      repo.startProgressiveChapterLoading("prog-book-1", chapterIndex = 1)
      awaitCondition { synthCount >= 1 }
      repo.pauseProgressiveGeneration(userPaused = true)
      gate.set(true)

      awaitCondition {
        repo.progressiveChapterState.value?.phase == ProgressiveChapterPhase.PAUSED
      }
      val countAfterPause = synthCount
      delay(150L)
      assertEquals("No debe generar más fragmentos mientras está en pausa", countAfterPause, synthCount)

      // Al salir sin reproducción activa tampoco queda generación infinita en marcha.
      repo.onProgressiveUiExited()
      delay(100L)
      assertEquals(countAfterPause, synthCount)
    }

  @Test
  fun `recoverable error retains partial chunks and retries without duplicating already generated audio`() =
    runBlocking {
      val dao = db.audiobookDao()
      val longText =
        (1..24).joinToString(" ") { idx ->
          "Párrafo técnico número $idx con oraciones largas para validar recuperación de error sin duplicar."
        }
      val chunks = ChapterTextChunker.splitChapter(longText)
      assertTrue(chunks.size >= 3)

      dao.insertBookWithChapters(
        BookEntity(
          id = "prog-book-err",
          title = "Libro Recuperación",
          author = "Autor",
          language = "es",
          publisher = "",
          description = "",
          coverImagePath = null,
          sourceFileName = "rec.epub",
          selectedVoice = NarratorVoice.FEMALE.name,
          totalChapters = 1,
          totalWords = 240,
          totalCharacters = longText.length,
          estimatedMinutes = 2,
          importedAt = 100L,
        ),
        listOf(
          ChapterEntity(
            id = "prog-book-err_ch_1",
            bookId = "prog-book-err",
            chapterIndex = 1,
            title = "Capítulo Resiliente",
            contentText = longText,
            wordCount = 240,
            characterCount = longText.length,
            estimatedMinutes = 2,
            audioStatus = AudioGenerationStatus.NOT_GENERATED.name,
            audioFilePath = null,
          )
        ),
      )

      var failSecond = true
      val synthCalls = mutableListOf<String>()
      val provider =
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
            if (failSecond && text == chunks[1]) {
              failSecond = false
              return Result.failure(IllegalStateException("Error transitorio en síntesis"))
            }
            synthCalls += text
            val bytes = ByteArray(22_050 * 2 * 5) { 4 } // 5 s
            return Result.success(
              SynthesizedAudioPayload(
                bytes = bytes,
                mimeType = "audio/L16;rate=22050;channels=1",
                sampleRate = 22_050,
                telemetry = SynthesisTelemetry(inferenceMs = 500L, audioDurationMs = 5_000L),
              )
            )
          }
        }

      val repo = trackRepo(AudiobookRepository(context, dao, storageManager = storageManager, audioSynthesisProvider = provider))
      repo.startProgressiveChapterLoading("prog-book-err", chapterIndex = 1)
      awaitCondition {
        repo.progressiveChapterState.value?.phase == ProgressiveChapterPhase.RECOVERABLE_ERROR
      }

      val errState = repo.progressiveChapterState.value!!
      assertEquals(1, errState.chunksCompleted)
      assertNotNull(errState.errorMessage)
      assertEquals(listOf(chunks[0]), synthCalls)

      // Reintentar retoma desde el fragmento 1 (el segundo) sin repetir el 0
      repo.resumeProgressiveGeneration("prog-book-err", chapterIndex = 1)
      awaitCondition {
        repo.progressiveChapterState.value?.canStartPlayback == true &&
          (repo.progressiveChapterState.value?.chunksCompleted ?: 0) >= 2
      }
      assertFalse(synthCalls.count { it == chunks[0] } > 1)
    }

  @Test
  fun `Room MIGRATION_3_4 preserves existing rows and adds progressive task metadata columns`() {
    val dbFile = File(context.cacheDir, "migration_3_4_test.db").apply { delete() }
    val factory = FrameworkSQLiteOpenHelperFactory()
    val helper: SupportSQLiteOpenHelper =
      factory.create(
        SupportSQLiteOpenHelper.Configuration.builder(context)
          .name(dbFile.absolutePath)
          .callback(
            object : SupportSQLiteOpenHelper.Callback(3) {
              override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                  """
                  CREATE TABLE IF NOT EXISTS `generation_tasks` (
                    `bookId` TEXT NOT NULL PRIMARY KEY,
                    `queueOrder` INTEGER NOT NULL,
                    `voiceName` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `currentChapterIndex` INTEGER NOT NULL,
                    `currentChunkIndex` INTEGER NOT NULL,
                    `completedChunks` INTEGER NOT NULL,
                    `totalChunks` INTEGER NOT NULL,
                    `startedAtMs` INTEGER NOT NULL,
                    `updatedAtMs` INTEGER NOT NULL,
                    `lastValidProgressAtMs` INTEGER NOT NULL,
                    `errorMessage` TEXT
                  )
                  """.trimIndent()
                )
                db.execSQL(
                  """
                  INSERT INTO generation_tasks VALUES (
                    'legacy-book', 1, 'FEMALE', 'RUNNING', 2, 1, 3, 10, 100, 200, 200, NULL
                  )
                  """.trimIndent()
                )
              }

              override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            }
          )
          .build()
      )

    val v3Db = helper.writableDatabase
    AppDatabase.MIGRATION_3_4.migrate(v3Db)
    v3Db.query("SELECT mode, targetChapterIndex, measuredRtf FROM generation_tasks WHERE bookId = 'legacy-book'").use { cursor ->
      assertTrue(cursor.moveToFirst())
      assertEquals(GenerationMode.FULL_BOOK.name, cursor.getString(0))
      assertTrue(cursor.isNull(1))
      assertEquals(0f, cursor.getFloat(2), 0.001f)
    }
    v3Db.close()
    dbFile.delete()
  }

  @Test
  fun `ProgressiveChapterContent and BookDetailScreen UI expose Cargar capitulo real progress 5 states and reduced-motion tiers`() =
    runBlocking {
      val dao = db.audiobookDao()
      dao.insertBookWithChapters(
        BookEntity(
          id = "prog-book-1",
          title = "Novela UI",
          author = "Autora",
          language = "es",
          publisher = "",
          description = "",
          coverImagePath = null,
          sourceFileName = "ui.epub",
          selectedVoice = NarratorVoice.FEMALE.name,
          totalChapters = 3,
          totalWords = 300,
          totalCharacters = 1500,
          estimatedMinutes = 3,
          importedAt = 100L,
        ),
        (1..3).map { idx ->
          ChapterEntity(
            id = "prog-book-1_ch_$idx",
            bookId = "prog-book-1",
            chapterIndex = idx,
            title = "Capítulo $idx",
            contentText = "Texto de muestra del capítulo $idx.",
            wordCount = 100,
            characterCount = 500,
            estimatedMinutes = 1,
            audioStatus = AudioGenerationStatus.NOT_GENERATED.name,
            audioFilePath = null,
          )
        },
      )

      val repo = trackRepo(AudiobookRepository(context, dao, storageManager = storageManager))
      val testPlayer = InMemoryPlaylistPlayer()
      val controller = AudiobookPlaybackController(context, repo, injectedPlayer = testPlayer)
      val detailVm = BookDetailViewModel("prog-book-1", repo, controller)

      composeRule.setContent {
        AudiolibroTheme {
          BookDetailScreen(
            viewModel = detailVm,
            onNavigateBack = {},
            onOpenChapterText = { _, _ -> },
            onLoadChapter = { _, _ -> },
            onOpenPlayer = { _, _ -> },
          )
        }
      }

      composeRule.waitUntil(timeoutMillis = 5_000L) {
        composeRule.onAllNodesWithTag("voice_male_card").fetchSemanticsNodes().isNotEmpty()
      }
      composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("load_chapter_3"))
      composeRule.onNodeWithTag("load_chapter_3").assertIsDisplayed()
      controller.releaseForTesting()
      testPlayer.release()
    }

  @Test
  fun `ProgressiveChapterContent toggles Play readiness shows 5 visual states and adapts to constrained or reduced motion devices`() {
    val preparingState =
      ProgressiveChapterState(
        bookId = "b1",
        bookTitle = "Libro Demo",
        chapterIndex = 4,
        chapterTitle = "Capítulo Cuatro",
        chapterNumber = 4,
        totalChapters = 8,
        phase = ProgressiveChapterPhase.GENERATING_BUFFER,
        chunksCompleted = 1,
        totalChunks = 10,
        initialReadyTargetMs = 10_000L,
        bufferedDurationMs = 4_000L,
        initialReadinessFraction = 0.4f,
        measuredRtf = 0.52f,
        effectiveRtf = 0.52f,
        deviceProfile = DeviceResourceProfile(reducedMotionPreferred = true),
        canStartPlayback = false,
        isSynthesizing = true,
        statusMessage = "Generando audio",
        totalChapterChars = 1000,
        synthesizedChapterChars = 100,
      )

    val stateHolder = androidx.compose.runtime.mutableStateOf(preparingState)

    composeRule.setContent {
      AudiolibroTheme {
        ProgressiveChapterContent(
          state = stateHolder.value,
          isActivelyPlayingThisChapter = false,
          onPlay = {},
          onPause = {},
          onResumeOrRetry = {},
        )
      }
    }

    composeRule.onNodeWithTag("book_to_sound_canvas").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithTag("progressive_segmented_bar").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithText("Modo sin movimiento (accesibilidad)").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithTag("progressive_play_button").performScrollTo().assertIsNotEnabled()
    composeRule.onNodeWithTag("state_chip_prepared").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithTag("state_chip_playing").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithTag("state_chip_lookahead").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithTag("state_chip_paused").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithTag("state_chip_error").performScrollTo().assertIsDisplayed()

    val readyState =
      preparingState.copy(
        phase = ProgressiveChapterPhase.READY_TO_PLAY,
        chunksCompleted = 3,
        bufferedDurationMs = 12_500L,
        initialReadinessFraction = 1f,
        deviceProfile = DeviceResourceProfile(isLowRamDevice = true, isPowerSaveMode = true),
        canStartPlayback = true,
        isSynthesizing = false,
        statusMessage = "Audio preparado",
        synthesizedChapterChars = 300,
      )

    composeRule.runOnUiThread {
      stateHolder.value = readyState
    }
    composeRule.waitForIdle()

    composeRule.onNodeWithTag("progressive_play_button").performScrollTo().assertIsEnabled()
    composeRule.onNodeWithTag("progressive_segmented_bar").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithTag("playback_availability_bar").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithText("30 %").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithText("Efectos reducidos por ahorro o RAM").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun `hydrateProgressiveChapterPreview rejects corrupt or missing READY WAV and restores true chunk progress`() =
    runBlocking {
      val dao = db.audiobookDao()
      val bookId = "corrupt-ready-book"
      val chapterId = "${bookId}_ch_1"
      val chunk0Wav = storageManager.chunkWavFile(bookId, 1, 0)
      WavAudioCodec.writePcmAsWav(ByteArray(22_050 * 2) { 5 }, chunk0Wav)
      val fakeCorruptChapterWav = storageManager.chapterWavFile(bookId, 1)
      fakeCorruptChapterWav.writeBytes(byteArrayOf(0x00, 0x01, 0x02)) // corrupt WAV header

      dao.insertBookWithChapters(
        BookEntity(
          id = bookId,
          title = "Libro WAV Corrupto",
          author = "Autor",
          language = "es",
          publisher = "",
          description = "",
          coverImagePath = null,
          sourceFileName = "corrupt.epub",
          selectedVoice = NarratorVoice.FEMALE.name,
          totalChapters = 1,
          totalWords = 200,
          totalCharacters = 1000,
          estimatedMinutes = 2,
          importedAt = 100L,
        ),
        listOf(
          ChapterEntity(
            id = chapterId,
            bookId = bookId,
            chapterIndex = 1,
            title = "Cap 1",
            contentText = "Texto de prueba largo.",
            wordCount = 200,
            characterCount = 1000,
            estimatedMinutes = 2,
            audioStatus = AudioGenerationStatus.READY.name,
            audioFilePath = fakeCorruptChapterWav.absolutePath,
            audioDurationMs = 5_000L,
            audioSizeBytes = 3L,
            generatedChunks = 2,
            totalChunks = 2,
          )
        ),
      )
      dao.upsertChunks(
        listOf(
          ChapterChunkEntity(
            id = "${chapterId}_part_0",
            bookId = bookId,
            chapterId = chapterId,
            chapterIndex = 1,
            chunkIndex = 0,
            textContent = "A".repeat(400),
            voiceName = NarratorVoice.FEMALE.name,
            status = ChapterChunkStatus.COMPLETED.name,
            wavFilePath = chunk0Wav.absolutePath,
            byteSize = chunk0Wav.length(),
            durationMs = 1_000L,
          ),
          ChapterChunkEntity(
            id = "${chapterId}_part_1",
            bookId = bookId,
            chapterId = chapterId,
            chapterIndex = 1,
            chunkIndex = 1,
            textContent = "B".repeat(600),
            voiceName = NarratorVoice.FEMALE.name,
            status = ChapterChunkStatus.PENDING.name,
            wavFilePath = null,
            byteSize = 0L,
            durationMs = 0L,
          ),
        )
      )

      val provider =
        object : AudioSynthesisProvider {
          override val isConfigured = true
          override val providerName = "Test"
          override val modelId = "es_ES-sharvard-medium"
          override suspend fun synthesizeFragment(
            text: String,
            voice: NarratorVoice,
            bookTitle: String,
            chapterTitle: String,
          ) = Result.failure<SynthesizedAudioPayload>(IllegalStateException("unused"))
        }

      val repo = trackRepo(AudiobookRepository(context, dao, storageManager = storageManager, audioSynthesisProvider = provider))
      repo.hydrateProgressiveChapterPreview(bookId, chapterIndex = 1)

      val state = repo.progressiveChapterState.value!!
      assertFalse("Corrupt final WAV must not report complete chapter ready", state.isCompleteChapterReady)
      assertEquals(40, state.chapterPercent)
      assertEquals(400, state.synthesizedChapterChars)
      assertEquals(1000, state.totalChapterChars)

      val repairedChapter = dao.getChapterOnce(bookId, 1)!!
      assertEquals(AudioGenerationStatus.NOT_GENERATED.name, repairedChapter.audioStatus)
    }

  @Test
  fun `buffer depletion on STATE_ENDED waits and auto-resumes at newly appended chunk without restarting chapter`() =
    runBlocking {
      val dao = db.audiobookDao()
      val bookId = "depletion-book"
      val chapterId = "${bookId}_ch_1"
      val wav0 = storageManager.chunkWavFile(bookId, 1, 0)
      val wav1 = storageManager.chunkWavFile(bookId, 1, 1)
      val info0 = WavAudioCodec.writePcmAsWav(ByteArray(22_050 * 2 * 3) { 2 }, wav0)
      val info1 = WavAudioCodec.writePcmAsWav(ByteArray(22_050 * 2 * 3) { 3 }, wav1)

      dao.insertBookWithChapters(
        BookEntity(
          id = bookId,
          title = "Libro Depletion",
          author = "Autor",
          language = "es",
          publisher = "",
          description = "",
          coverImagePath = null,
          sourceFileName = "dep.epub",
          selectedVoice = NarratorVoice.FEMALE.name,
          totalChapters = 1,
          totalWords = 100,
          totalCharacters = 600,
          estimatedMinutes = 2,
          importedAt = 100L,
        ),
        listOf(
          ChapterEntity(
            id = chapterId,
            bookId = bookId,
            chapterIndex = 1,
            title = "Cap 1",
            contentText = "Texto para probar agotamiento de búfer.",
            wordCount = 100,
            characterCount = 600,
            estimatedMinutes = 2,
            audioStatus = AudioGenerationStatus.GENERATING.name,
            audioFilePath = null,
            generatedChunks = 1,
            totalChunks = 2,
          )
        ),
      )
      dao.upsertChunks(
        listOf(
          ChapterChunkEntity(
            id = "${chapterId}_part_0",
            bookId = bookId,
            chapterId = chapterId,
            chapterIndex = 1,
            chunkIndex = 0,
            textContent = "A".repeat(300),
            voiceName = NarratorVoice.FEMALE.name,
            status = ChapterChunkStatus.COMPLETED.name,
            wavFilePath = wav0.absolutePath,
            byteSize = wav0.length(),
            durationMs = info0.durationMs,
          ),
          ChapterChunkEntity(
            id = "${chapterId}_part_1",
            bookId = bookId,
            chapterId = chapterId,
            chapterIndex = 1,
            chunkIndex = 1,
            textContent = "B".repeat(300),
            voiceName = NarratorVoice.FEMALE.name,
            status = ChapterChunkStatus.PENDING.name,
            wavFilePath = null,
            byteSize = 0L,
            durationMs = 0L,
          ),
        )
      )

      val unusedProvider =
        object : AudioSynthesisProvider {
          override val isConfigured = true
          override val providerName = "Test"
          override val modelId = "es_ES-sharvard-medium"
          override suspend fun synthesizeFragment(
            text: String,
            voice: NarratorVoice,
            bookTitle: String,
            chapterTitle: String,
          ) = Result.failure<SynthesizedAudioPayload>(IllegalStateException("unused"))
        }

      val repo = trackRepo(AudiobookRepository(context, dao, storageManager = storageManager, audioSynthesisProvider = unusedProvider))
      val testPlayer = InMemoryPlaylistPlayer()
      val controller = AudiobookPlaybackController(context, repo, injectedPlayer = testPlayer)

      try {
        controller.playProgressiveChapter(bookId, chapterIndex = 1, startPositionMs = 0L)
        awaitCondition { testPlayer.currentMediaIds().size == 1 && testPlayer.playWhenReady }

        // Simular que el fragmento 0 terminó antes de llegar el fragmento 1 (buffer depletion)
        testPlayer.simulatePlaybackEnded()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        awaitCondition { controller.uiState.value.isWaitingForBuffer }

        // Llega el fragmento 1 sintetizado en Room
        dao.upsertChunk(
          ChapterChunkEntity(
            id = "${chapterId}_part_1",
            bookId = bookId,
            chapterId = chapterId,
            chapterIndex = 1,
            chunkIndex = 1,
            textContent = "B".repeat(300),
            voiceName = NarratorVoice.FEMALE.name,
            status = ChapterChunkStatus.COMPLETED.name,
            wavFilePath = wav1.absolutePath,
            byteSize = wav1.length(),
            durationMs = info1.durationMs,
          )
        )

        awaitCondition { testPlayer.currentMediaIds().size == 2 }
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, testPlayer.setMediaItemsCallCount)
        assertEquals(1, testPlayer.currentMediaItemIndex)
        assertEquals(Player.STATE_READY, testPlayer.playbackState)
        assertFalse(controller.uiState.value.isWaitingForBuffer)
      } finally {
        controller.releaseForTesting()
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

  private class InMemoryPlaylistPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    private var items: List<MediaItemData> = emptyList()
    private var currentIndex: Int = 0
    private var positionMs: Long = 0L
    private var playWhenReadyFlag: Boolean = false
    private var playbackStateInt: Int = Player.STATE_IDLE
    private var playbackParametersState: PlaybackParameters = PlaybackParameters.DEFAULT
    var setMediaItemsCallCount: Int = 0
      private set
    var addMediaItemsCallCount: Int = 0
      private set

    fun currentMediaIds(): List<String> = items.map { it.mediaItem.mediaId }

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
      setMediaItemsCallCount += 1
      items =
        mediaItems.map { item ->
          val durUs =
            (item.mediaMetadata.extras?.getLong(AudiobookMediaHelper.EXTRA_CHUNK_DURATION_MS, 4_500L)
              ?: 4_500L) * 1_000L
          MediaItemData.Builder(item.mediaId).setMediaItem(item).setDurationUs(durUs).build()
        }
      currentIndex = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
      positionMs = startPositionMs.coerceAtLeast(0L)
      invalidateState()
      return Futures.immediateVoidFuture()
    }

    override fun handleAddMediaItems(
      index: Int,
      mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<*> {
      addMediaItemsCallCount += 1
      val added =
        mediaItems.map { item ->
          val durUs =
            (item.mediaMetadata.extras?.getLong(AudiobookMediaHelper.EXTRA_CHUNK_DURATION_MS, 4_500L)
              ?: 4_500L) * 1_000L
          MediaItemData.Builder(item.mediaId).setMediaItem(item).setDurationUs(durUs).build()
        }
      val mutable = items.toMutableList()
      mutable.addAll(index.coerceIn(0, mutable.size), added)
      items = mutable
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
