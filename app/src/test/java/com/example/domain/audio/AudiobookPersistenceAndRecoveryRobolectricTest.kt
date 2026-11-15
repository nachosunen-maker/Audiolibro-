package com.example.domain.audio

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.data.local.AppDatabase
import com.example.data.local.BookEntity
import com.example.data.local.ChapterChunkEntity
import com.example.data.local.ChapterEntity
import com.example.data.local.GenerationTaskEntity
import com.example.data.repository.AudiobookRepository
import com.example.data.storage.AudiobookStorageManager
import com.example.domain.model.AudioGenerationStatus
import com.example.domain.model.ChapterChunkStatus
import com.example.domain.model.GenerationTaskStatus
import com.example.domain.model.NarratorVoice
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AudiobookPersistenceAndRecoveryRobolectricTest {

  private lateinit var context: Context
  private lateinit var db: AppDatabase
  private lateinit var storageManager: AudiobookStorageManager

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    WorkManagerTestInitHelper.initializeTestWorkManager(context)
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    storageManager = AudiobookStorageManager(context)
    storageManager.deleteBookAudioFiles("rec-book-1")
    storageManager.deleteBookAudioFiles("rec-book-2")
    storageManager.deleteBookAudioFiles("svc-book-1")
  }

  @After
  fun tearDown() {
    storageManager.deleteBookAudioFiles("rec-book-1")
    storageManager.deleteBookAudioFiles("rec-book-2")
    storageManager.deleteBookAudioFiles("svc-book-1")
    db.close()
  }

  @Test
  fun `A_phonemizer_contract_passes_espeak_ng_data_dir_directly_guards_single_init_and_surfaces_errors`() {
    val modelDir = SherpaOnnxLocalTtsEngine.ensureModelAssetsCopied(context)
    val expectedEspeakDir = File(modelDir, SherpaOnnxLocalTtsEngine.ESPEAK_DIR_NAME)

    val observedPaths = mutableListOf<String>()
    val recordingBridge =
      object : NativePhonemizeBridge {
        var nextReturnCode = 22_050

        override fun getVersion(): String = "1.5.9"

        override fun initialize(espeakDataDir: String): Int {
          observedPaths += espeakDataDir
          return nextReturnCode
        }

        override fun phonemize(text: String, voice: String): List<NativePhonemizedSentence> {
          assertEquals("es", voice)
          return listOf(
            NativePhonemizedSentence(
              codepoints = "ˈola".map { it.code }.toIntArray(),
              previewIpa = "ˈola",
            )
          )
        }
      }

    // Caso 1: Pasar carpeta padre (modelDir) debe resolver al subdirectorio .../espeak-ng-data
    val phonemizerFromParent = PiperNativePhonemizer(recordingBridge)
    phonemizerFromParent.initialize(modelDir.absolutePath)
    // Llamada repetida sobre la misma ruta no vuelve a invocar bridge.initialize()
    phonemizerFromParent.initialize(expectedEspeakDir.absolutePath)

    assertEquals(1, observedPaths.size)
    assertEquals(expectedEspeakDir.absolutePath, observedPaths.single())
    assertTrue(observedPaths.single().endsWith("/espeak-ng-data"))
    assertEquals(expectedEspeakDir.absolutePath, phonemizerFromParent.currentInitializedPath())

    val codepoints = phonemizerFromParent.phonemizeToCodepoints("Hola mundo.", "es")
    assertEquals(1, codepoints.size)
    assertTrue(codepoints.single().isNotEmpty())

    // Caso 2: Error nativo (rc < 0) NO se oculta y NO marca estado inicializado
    val failingBridge =
      object : NativePhonemizeBridge {
        override fun getVersion(): String = "1.5.9"
        override fun initialize(espeakDataDir: String): Int = -1
        override fun phonemize(text: String, voice: String): List<NativePhonemizedSentence>? = null
      }
    val failingPhonemizer = PiperNativePhonemizer(failingBridge)
    val initErr =
      runCatching { failingPhonemizer.initialize(expectedEspeakDir.absolutePath) }.exceptionOrNull()
    assertNotNull(initErr)
    assertNull(failingPhonemizer.currentInitializedPath())
  }

  @Test
  fun `B_room_migration_2_to_3_preserves_existing_books_chapters_chunks_and_creates_generation_tasks`() {
    val dbFile = File(context.cacheDir, "migration_2_3_test.db")
    dbFile.delete()

    // Crear BD en versión 2 con datos reales de usuario
    val v2Config =
      SupportSQLiteOpenHelper.Configuration.builder(context)
        .name(dbFile.absolutePath)
        .callback(
          object : SupportSQLiteOpenHelper.Callback(2) {
            override fun onCreate(db: SupportSQLiteDatabase) {
              db.execSQL(
                """
                CREATE TABLE `books` (
                  `id` TEXT NOT NULL PRIMARY KEY, `title` TEXT NOT NULL, `author` TEXT NOT NULL,
                  `language` TEXT NOT NULL, `publisher` TEXT NOT NULL, `description` TEXT NOT NULL,
                  `coverImagePath` TEXT, `sourceFileName` TEXT NOT NULL, `selectedVoice` TEXT NOT NULL,
                  `totalChapters` INTEGER NOT NULL, `totalWords` INTEGER NOT NULL,
                  `totalCharacters` INTEGER NOT NULL, `estimatedMinutes` INTEGER NOT NULL,
                  `importedAt` INTEGER NOT NULL, `lastPlayedChapterIndex` INTEGER,
                  `lastPlayedPositionMs` INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
              )
              db.execSQL(
                """
                CREATE TABLE `chapters` (
                  `id` TEXT NOT NULL PRIMARY KEY, `bookId` TEXT NOT NULL, `chapterIndex` INTEGER NOT NULL,
                  `title` TEXT NOT NULL, `contentText` TEXT NOT NULL, `wordCount` INTEGER NOT NULL,
                  `characterCount` INTEGER NOT NULL, `estimatedMinutes` INTEGER NOT NULL,
                  `audioStatus` TEXT NOT NULL, `audioFilePath` TEXT,
                  `audioDurationMs` INTEGER NOT NULL DEFAULT 0, `audioSizeBytes` INTEGER NOT NULL DEFAULT 0,
                  `generatedChunks` INTEGER NOT NULL DEFAULT 0, `totalChunks` INTEGER NOT NULL DEFAULT 0,
                  `generationError` TEXT,
                  FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
              )
              db.execSQL("CREATE INDEX `index_chapters_bookId` ON `chapters` (`bookId`)")
              db.execSQL(
                """
                CREATE TABLE `chapter_chunks` (
                  `id` TEXT NOT NULL PRIMARY KEY, `bookId` TEXT NOT NULL, `chapterId` TEXT NOT NULL,
                  `chapterIndex` INTEGER NOT NULL, `chunkIndex` INTEGER NOT NULL, `textContent` TEXT NOT NULL,
                  `voiceName` TEXT NOT NULL, `status` TEXT NOT NULL, `wavFilePath` TEXT,
                  `byteSize` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL,
                  FOREIGN KEY(`chapterId`) REFERENCES `chapters`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
              )
              db.execSQL("CREATE INDEX `index_chapter_chunks_chapterId` ON `chapter_chunks` (`chapterId`)")
              db.execSQL("CREATE INDEX `index_chapter_chunks_bookId` ON `chapter_chunks` (`bookId`)")

              db.execSQL(
                """
                INSERT INTO books VALUES (
                  'legacy-book', 'Don Quijote', 'Cervantes', 'es', 'Imprenta', 'Sinopsis', NULL,
                  'quijote.epub', 'MALE', 1, 10, 50, 1, 999, 1, 1234
                )
                """.trimIndent()
              )
              db.execSQL(
                """
                INSERT INTO chapters VALUES (
                  'legacy-book_ch_1', 'legacy-book', 1, 'Capítulo I', 'En un lugar de la Mancha.',
                  6, 26, 1, 'READY', '/tmp/ch1.wav', 2500, 110294, 1, 1, NULL
                )
                """.trimIndent()
              )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
          }
        )
        .build()

    FrameworkSQLiteOpenHelperFactory().create(v2Config).writableDatabase.close()

    val upgradedDb =
      Room.databaseBuilder(context, AppDatabase::class.java, dbFile.absolutePath)
        .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
        .allowMainThreadQueries()
        .build()

    runBlocking {
      val dao = upgradedDb.audiobookDao()
      val book = dao.getBookOnce("legacy-book")
      assertNotNull(book)
      assertEquals("Don Quijote", book!!.title)
      assertEquals("MALE", book.selectedVoice)
      assertEquals(1234L, book.lastPlayedPositionMs)

      val chapters = dao.getChaptersOnce("legacy-book")
      assertEquals(1, chapters.size)
      assertEquals(AudioGenerationStatus.READY.name, chapters.first().audioStatus)

      // Verificar que generation_tasks existe y soporta persistencia real
      dao.upsertGenerationTask(
        GenerationTaskEntity(
          bookId = "legacy-book",
          queueOrder = 1L,
          voiceName = NarratorVoice.MALE.name,
          status = GenerationTaskStatus.COMPLETED.name,
          startedAtMs = 1L,
          updatedAtMs = 2L,
        )
      )
      assertEquals("COMPLETED", dao.getGenerationTaskOnce("legacy-book")?.status)
    }

    upgradedDb.close()
    dbFile.delete()
  }

  @Test
  fun `G_persistent_db_queue_recovers_after_process_restart_without_duplicating_chunks_or_resuming_user_paused_books`() =
    runTest {
      val dbFile = File(context.cacheDir, "recovery_restart_test.db")
      dbFile.delete()

      val longText =
        (1..22).joinToString(" ") { i ->
          "Párrafo narrativo número $i en castellano completo para dividir en varios fragmentos."
        }
      val expectedChunks = ChapterTextChunker.splitChapter(longText)
      assertTrue(expectedChunks.size >= 2)

      // 1. Primera instancia (antes del kill del proceso): Libro 1 con fragmento 0 ya guardado válido en disco
      // y tarea marcada RUNNING; Libro 2 marcado PAUSED_BY_USER.
      val firstDb =
        Room.databaseBuilder(context, AppDatabase::class.java, dbFile.absolutePath)
          .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
          .allowMainThreadQueries()
          .build()
      val firstDao = firstDb.audiobookDao()

      firstDao.insertBookWithChapters(
        BookEntity(
          id = "rec-book-1",
          title = "Libro Recuperable",
          author = "Autora",
          language = "es",
          publisher = "",
          description = "",
          coverImagePath = null,
          sourceFileName = "rec1.epub",
          selectedVoice = NarratorVoice.MALE.name,
          totalChapters = 2,
          totalWords = 200,
          totalCharacters = longText.length + 30,
          estimatedMinutes = 2,
          importedAt = 100L,
        ),
        listOf(
          ChapterEntity(
            id = "rec-book-1_ch_1",
            bookId = "rec-book-1",
            chapterIndex = 1,
            title = "Cap 1",
            contentText = longText,
            wordCount = 190,
            characterCount = longText.length,
            estimatedMinutes = 2,
            audioStatus = AudioGenerationStatus.GENERATING.name,
            audioFilePath = null,
            generatedChunks = 1,
            totalChunks = expectedChunks.size,
          ),
          ChapterEntity(
            id = "rec-book-1_ch_2",
            bookId = "rec-book-1",
            chapterIndex = 2,
            title = "Cap 2",
            contentText = "Capítulo final completo.",
            wordCount = 3,
            characterCount = 24,
            estimatedMinutes = 1,
            audioStatus = AudioGenerationStatus.NOT_GENERATED.name,
            audioFilePath = null,
          ),
        ),
      )

      val chunk0Wav = storageManager.chunkWavFile("rec-book-1", 1, 0)
      val chunk0Info = WavAudioCodec.writePcmAsWav(ByteArray(4410) { 7 }, chunk0Wav)
      firstDao.upsertChunks(
        expectedChunks.mapIndexed { idx, txt ->
          ChapterChunkEntity(
            id = "rec-book-1_ch_1_part_$idx",
            bookId = "rec-book-1",
            chapterId = "rec-book-1_ch_1",
            chapterIndex = 1,
            chunkIndex = idx,
            textContent = txt,
            voiceName = NarratorVoice.MALE.name,
            status =
              if (idx == 0) ChapterChunkStatus.COMPLETED.name else ChapterChunkStatus.PENDING.name,
            wavFilePath = if (idx == 0) chunk0Wav.absolutePath else null,
            byteSize = if (idx == 0) chunk0Wav.length() else 0L,
            durationMs = if (idx == 0) chunk0Info.durationMs else 0L,
          )
        }
      )
      firstDao.upsertGenerationTask(
        GenerationTaskEntity(
          bookId = "rec-book-1",
          queueOrder = 10L,
          voiceName = NarratorVoice.MALE.name,
          status = GenerationTaskStatus.RUNNING.name,
          currentChapterIndex = 1,
          currentChunkIndex = 1,
          completedChunks = 1,
          totalChunks = expectedChunks.size + 1,
          startedAtMs = 100L,
          updatedAtMs = 200L,
          lastValidProgressAtMs = 200L,
        )
      )

      // Libro 2: pausado explícitamente por el usuario -> NO debe reanudarse solo
      firstDao.insertBookWithChapters(
        BookEntity(
          id = "rec-book-2",
          title = "Libro Pausado Usuario",
          author = "Autor",
          language = "es",
          publisher = "",
          description = "",
          coverImagePath = null,
          sourceFileName = "rec2.epub",
          selectedVoice = NarratorVoice.FEMALE.name,
          totalChapters = 1,
          totalWords = 5,
          totalCharacters = 30,
          estimatedMinutes = 1,
          importedAt = 101L,
        ),
        listOf(
          ChapterEntity(
            id = "rec-book-2_ch_1",
            bookId = "rec-book-2",
            chapterIndex = 1,
            title = "Cap 1",
            contentText = "Este libro fue pausado manualmente.",
            wordCount = 5,
            characterCount = 34,
            estimatedMinutes = 1,
            audioStatus = AudioGenerationStatus.NOT_GENERATED.name,
            audioFilePath = null,
          )
        ),
      )
      firstDao.upsertGenerationTask(
        GenerationTaskEntity(
          bookId = "rec-book-2",
          queueOrder = 20L,
          voiceName = NarratorVoice.FEMALE.name,
          status = GenerationTaskStatus.PAUSED_BY_USER.name,
          startedAtMs = 150L,
          updatedAtMs = 250L,
        )
      )
      firstDb.close()

      // 2. Nuevo proceso simulado: nueva instancia de AppDatabase + nuevo AudiobookRepository
      val restartedDb =
        Room.databaseBuilder(context, AppDatabase::class.java, dbFile.absolutePath)
          .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
          .allowMainThreadQueries()
          .build()
      val restartedDao = restartedDb.audiobookDao()

      val synthesizedBookIds = mutableListOf<String>()
      val synthesizedChunkTexts = mutableListOf<String>()
      val trackingProvider =
        object : AudioSynthesisProvider {
          override val isConfigured = true
          override val providerName = "TestOfflinePiper"
          override val modelId = "es_ES-sharvard-medium"

          override suspend fun synthesizeFragment(
            text: String,
            voice: NarratorVoice,
            bookTitle: String,
            chapterTitle: String,
          ): Result<SynthesizedAudioPayload> {
            synchronized(synthesizedChunkTexts) {
              synthesizedBookIds += bookTitle
              synthesizedChunkTexts += text
            }
            return Result.success(
              SynthesizedAudioPayload(
                bytes = ByteArray(4410) { 9 },
                mimeType = "audio/L16;rate=22050;channels=1",
                sampleRate = 22_050,
              )
            )
          }
        }

      val restartedRepo =
        AudiobookRepository(
          context = context,
          dao = restartedDao,
          storageManager = storageManager,
          audioSynthesisProvider = trackingProvider,
        )

      val didRestore = restartedRepo.restoreRecoverableGenerations(startServiceIfNeeded = false)
      assertTrue(didRestore)

      val finishedProgress =
        restartedRepo.generationProgress
          .first { map -> map["rec-book-1"]?.phase == GenerationPhase.COMPLETED }["rec-book-1"]
      assertNotNull(finishedProgress)

      // Verificar que NO duplicó el chunk 0 ya válido en disco y SÍ generó el resto + cap 2
      assertFalse(synthesizedChunkTexts.contains(expectedChunks.first()))
      assertEquals(expectedChunks.drop(1) + "Capítulo final completo.", synthesizedChunkTexts)
      assertTrue(synthesizedBookIds.all { it == "Libro Recuperable" })

      val chsBook1 = restartedDao.getChaptersOnce("rec-book-1")
      assertTrue(chsBook1.all { it.audioStatus == AudioGenerationStatus.READY.name })
      assertEquals(GenerationTaskStatus.COMPLETED.name, restartedDao.getGenerationTaskOnce("rec-book-1")?.status)

      // Libro 2 debe permanecer PAUSED_BY_USER sin haber sido tocado
      assertEquals(
        GenerationTaskStatus.PAUSED_BY_USER.name,
        restartedDao.getGenerationTaskOnce("rec-book-2")?.status,
      )
      assertEquals(
        AudioGenerationStatus.NOT_GENERATED.name,
        restartedDao.getChaptersOnce("rec-book-2").single().audioStatus,
      )

      restartedDb.close()
      dbFile.delete()
    }

  @Test
  fun `F_service_onTimeout_saves_INTERRUPTED_BY_OS_releases_WakeLock_stops_cleanly_and_schedules_WorkManager`() =
    runTest {
      val dao = db.audiobookDao()
      dao.insertBookWithChapters(
        BookEntity(
          id = "svc-book-1",
          title = "Libro Largo Servicio",
          author = "Autor",
          language = "es",
          publisher = "",
          description = "",
          coverImagePath = null,
          sourceFileName = "svc.epub",
          selectedVoice = NarratorVoice.FEMALE.name,
          totalChapters = 1,
          totalWords = 10,
          totalCharacters = 60,
          estimatedMinutes = 1,
          importedAt = 100L,
        ),
        listOf(
          ChapterEntity(
            id = "svc-book-1_ch_1",
            bookId = "svc-book-1",
            chapterIndex = 1,
            title = "Cap 1",
            contentText = "Primera oración larga.\n\nSegunda oración larga.",
            wordCount = 6,
            characterCount = 46,
            estimatedMinutes = 1,
            audioStatus = AudioGenerationStatus.NOT_GENERATED.name,
            audioFilePath = null,
          )
        ),
      )

      val startedFirstChunk = CountDownLatch(1)
      val blockingProvider =
        object : AudioSynthesisProvider {
          override val isConfigured = true
          override val providerName = "BlockingPiperTest"
          override val modelId = "es_ES-sharvard-medium"

          override suspend fun synthesizeFragment(
            text: String,
            voice: NarratorVoice,
            bookTitle: String,
            chapterTitle: String,
          ): Result<SynthesizedAudioPayload> {
            startedFirstChunk.countDown()
            delay(60_000L)
            return Result.success(
              SynthesizedAudioPayload(
                bytes = ByteArray(4410) { 1 },
                mimeType = "audio/L16;rate=22050;channels=1",
                sampleRate = 22_050,
              )
            )
          }
        }

      val repo =
        AudiobookRepository(
          context = context,
          dao = dao,
          storageManager = storageManager,
          audioSynthesisProvider = blockingProvider,
        )

      val serviceController = Robolectric.buildService(AudiobookGenerationService::class.java)
      val service = serviceController.create().get()
      service.repositoryOverride = repo

      val startIntent =
        Intent(context, AudiobookGenerationService::class.java).apply {
          action = AudiobookGenerationService.ACTION_START_GENERATION
          putExtra(AudiobookGenerationService.EXTRA_BOOK_ID, "svc-book-1")
        }
      val stickyMode = service.onStartCommand(startIntent, 0, 1)
      assertEquals(android.app.Service.START_STICKY, stickyMode)
      assertTrue(service.isWakeLockHeldForTesting())

      assertTrue("Synth coroutine should start", startedFirstChunk.await(5, TimeUnit.SECONDS))
      // Asegurar que la fila RUNNING ya se escribió en Room antes del timeout
      var runningTask: GenerationTaskEntity? = null
      for (attempt in 1..40) {
        runningTask = dao.getGenerationTaskOnce("svc-book-1")
        if (runningTask?.status == GenerationTaskStatus.RUNNING.name) break
        delay(25L)
      }
      assertEquals(GenerationTaskStatus.RUNNING.name, runningTask?.status)

      // Simular callback de Android 15+: Service.onTimeout(startId, FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
      service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)

      // 1. WakeLock liberado inmediatamente sin fuga
      assertFalse(service.isWakeLockHeldForTesting())

      // 2. Tarea queda en INTERRUPTED_BY_OS (NO PAUSED_BY_USER), lista para reanudarse sola
      val postTimeoutTask = dao.getGenerationTaskOnce("svc-book-1")
      assertNotNull(postTimeoutTask)
      assertEquals(GenerationTaskStatus.INTERRUPTED_BY_OS.name, postTimeoutTask!!.status)

      // 3. WorkManager recibió el trabajo único de recuperación persistente
      val workInfos =
        WorkManager.getInstance(context)
          .getWorkInfosForUniqueWork(AudiobookRecoveryScheduler.UNIQUE_RECOVERY_WORK_NAME)
          .get()
      assertTrue(workInfos.isNotEmpty())
      assertTrue(
        workInfos.any {
          it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
        }
      )

      serviceController.destroy()
    }

  @Test
  fun `H_recovery_worker_resumes_interrupted_db_tasks_and_exits_cleanly_when_empty`() = runTest {
    val dao = db.audiobookDao()
    dao.insertBookWithChapters(
      BookEntity(
        id = "rec-book-1",
        title = "Worker Book",
        author = "Autor",
        language = "es",
        publisher = "",
        description = "",
        coverImagePath = null,
        sourceFileName = "w.epub",
        selectedVoice = NarratorVoice.MALE.name,
        totalChapters = 1,
        totalWords = 5,
        totalCharacters = 30,
        estimatedMinutes = 1,
        importedAt = 10L,
      ),
      listOf(
        ChapterEntity(
          id = "rec-book-1_ch_1",
          bookId = "rec-book-1",
          chapterIndex = 1,
          title = "Cap 1",
          contentText = "Texto corto del trabajador.",
          wordCount = 4,
          characterCount = 27,
          estimatedMinutes = 1,
          audioStatus = AudioGenerationStatus.NOT_GENERATED.name,
          audioFilePath = null,
        )
      ),
    )
    dao.upsertGenerationTask(
      GenerationTaskEntity(
        bookId = "rec-book-1",
        queueOrder = 1L,
        voiceName = NarratorVoice.MALE.name,
        status = GenerationTaskStatus.INTERRUPTED_BY_OS.name,
        startedAtMs = 1L,
        updatedAtMs = 1L,
      )
    )

    val okProvider =
      object : AudioSynthesisProvider {
        override val isConfigured = true
        override val providerName = "WorkerPiper"
        override val modelId = "es_ES-sharvard-medium"

        override suspend fun synthesizeFragment(
          text: String,
          voice: NarratorVoice,
          bookTitle: String,
          chapterTitle: String,
        ): Result<SynthesizedAudioPayload> =
          Result.success(
            SynthesizedAudioPayload(
              bytes = ByteArray(4410) { 3 },
              mimeType = "audio/L16;rate=22050;channels=1",
              sampleRate = 22_050,
            )
          )
      }

    val repo =
      AudiobookRepository(
        context = context,
        dao = dao,
        storageManager = storageManager,
        audioSynthesisProvider = okProvider,
      )

    // Ejecutar directamente la lógica del Worker con la BD real de prueba
    val workerResult = repo.runRecoverableTasksInWorker()
    assertEquals(ListenableWorker.Result.success(), workerResult)
    assertEquals(GenerationTaskStatus.COMPLETED.name, dao.getGenerationTaskOnce("rec-book-1")?.status)
    assertEquals(
      AudioGenerationStatus.READY.name,
      dao.getChaptersOnce("rec-book-1").single().audioStatus,
    )

    // Comprobar además TestListenableWorkerBuilder cuando ya no quedan tareas pendientes en AppDatabase vacío
    val worker = TestListenableWorkerBuilder<AudiobookRecoveryWorker>(context).build()
    worker.repositoryOverride = repo
    val idleWorkerResult = worker.doWork()
    assertEquals(ListenableWorker.Result.success(), idleWorkerResult)
  }

  @Test
  fun `I_service_START_STICKY_null_intent_restores_db_queue_and_explicit_cancel_releases_WakeLock`() =
    runTest {
      val dao = db.audiobookDao()
      dao.insertBookWithChapters(
        BookEntity(
          id = "svc-book-1",
          title = "Libro Sticky",
          author = "Autor",
          language = "es",
          publisher = "",
          description = "",
          coverImagePath = null,
          sourceFileName = "sticky.epub",
          selectedVoice = NarratorVoice.MALE.name,
          totalChapters = 1,
          totalWords = 5,
          totalCharacters = 28,
          estimatedMinutes = 1,
          importedAt = 10L,
        ),
        listOf(
          ChapterEntity(
            id = "svc-book-1_ch_1",
            bookId = "svc-book-1",
            chapterIndex = 1,
            title = "Cap 1",
            contentText = "Texto reanudado por START_STICKY.",
            wordCount = 5,
            characterCount = 34,
            estimatedMinutes = 1,
            audioStatus = AudioGenerationStatus.NOT_GENERATED.name,
            audioFilePath = null,
          )
        ),
      )
      dao.upsertGenerationTask(
        GenerationTaskEntity(
          bookId = "svc-book-1",
          queueOrder = 1L,
          voiceName = NarratorVoice.MALE.name,
          status = GenerationTaskStatus.INTERRUPTED_BY_OS.name,
          startedAtMs = 1L,
          updatedAtMs = 1L,
        )
      )

      val blockingLatch = CountDownLatch(1)
      val slowProvider =
        object : AudioSynthesisProvider {
          override val isConfigured = true
          override val providerName = "StickyTest"
          override val modelId = "es_ES-sharvard-medium"

          override suspend fun synthesizeFragment(
            text: String,
            voice: NarratorVoice,
            bookTitle: String,
            chapterTitle: String,
          ): Result<SynthesizedAudioPayload> {
            blockingLatch.countDown()
            delay(30_000L)
            return Result.success(
              SynthesizedAudioPayload(
                bytes = ByteArray(4410) { 2 },
                mimeType = "audio/L16;rate=22050;channels=1",
                sampleRate = 22_050,
              )
            )
          }
        }

      val repo =
        AudiobookRepository(
          context = context,
          dao = dao,
          storageManager = storageManager,
          audioSynthesisProvider = slowProvider,
        )

      val serviceController = Robolectric.buildService(AudiobookGenerationService::class.java)
      val service = serviceController.create().get()
      service.repositoryOverride = repo

      // Reinicio por el SO sin Intent (null)
      val stickyResult = service.onStartCommand(null, 0, 1)
      assertEquals(android.app.Service.START_STICKY, stickyResult)
      assertTrue(service.isWakeLockHeldForTesting())

      // Avanzar Looper principal para permitir que la corrutina de onStartCommand restaure desde Room
      var reachedSynth = false
      for (i in 1..40) {
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        if (blockingLatch.await(25, TimeUnit.MILLISECONDS)) {
          reachedSynth = true
          break
        }
      }
      assertTrue("START_STICKY null intent should restore persisted Room task", reachedSynth)

      // Cancelación explícita del usuario libera WakeLock inmediatamente y persiste PAUSED_BY_USER
      val cancelIntent =
        Intent(context, AudiobookGenerationService::class.java).apply {
          action = AudiobookGenerationService.ACTION_CANCEL_GENERATION
          putExtra(AudiobookGenerationService.EXTRA_BOOK_ID, "svc-book-1")
        }
      service.onStartCommand(cancelIntent, 0, 2)
      org.robolectric.shadows.ShadowLooper.idleMainLooper()
      assertFalse(service.isWakeLockHeldForTesting())

      var pausedStatus: String? = null
      for (i in 1..40) {
        pausedStatus = dao.getGenerationTaskOnce("svc-book-1")?.status
        if (pausedStatus == GenerationTaskStatus.PAUSED_BY_USER.name) break
        delay(25L)
      }
      assertEquals(GenerationTaskStatus.PAUSED_BY_USER.name, pausedStatus)

      serviceController.destroy()
    }
}
