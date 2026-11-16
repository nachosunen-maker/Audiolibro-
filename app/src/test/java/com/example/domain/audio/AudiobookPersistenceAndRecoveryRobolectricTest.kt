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
  private val trackedRepos = mutableListOf<AudiobookRepository>()

  private fun trackRepo(repo: AudiobookRepository): AudiobookRepository =
    repo.also { trackedRepos += it }

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
    runBlocking {
      trackedRepos.forEach { it.awaitAndCloseForTesting() }
      trackedRepos.clear()
    }
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
  fun `C_progressive_chapter_recovery_resumes_from_valid_disk_chunks_without_duplicating_or_preconverting_next_chapter`() =
    runTest {
      val dbFile = File(context.cacheDir, "recovery_restart_test.db")
      dbFile.delete()

      val longText =
        (1..22).joinToString(" ") { i ->
          "Párrafo narrativo número $i en castellano completo para dividir en varios fragmentos."
        }
      val expectedChunks = ChapterTextChunker.splitChapter(longText)
      assertTrue(expectedChunks.size >= 2)

      val firstDb =
        Room.databaseBuilder(context, AppDatabase::class.java, dbFile.absolutePath)
          .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
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
      firstDb.close()

      val restartedDb =
        Room.databaseBuilder(context, AppDatabase::class.java, dbFile.absolutePath)
          .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
          .allowMainThreadQueries()
          .build()
      val restartedDao = restartedDb.audiobookDao()

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

      restartedRepo.startProgressiveChapterLoading("rec-book-1", chapterIndex = 1)
      val finishedState =
        restartedRepo.progressiveChapterState.first { state ->
          state != null && state.bookId == "rec-book-1" && state.isCompleteChapterReady
        }
      assertNotNull(finishedState)

      // No duplicó el chunk 0 ya válido en disco y no preconvirtió el capítulo 2.
      assertFalse(synthesizedChunkTexts.contains(expectedChunks.first()))
      assertEquals(expectedChunks.drop(1), synthesizedChunkTexts)

      val chsBook1 = restartedDao.getChaptersOnce("rec-book-1").sortedBy { it.chapterIndex }
      assertEquals(AudioGenerationStatus.READY.name, chsBook1[0].audioStatus)
      assertEquals(AudioGenerationStatus.NOT_GENERATED.name, chsBook1[1].audioStatus)

      restartedRepo.awaitAndCloseForTesting()
      restartedDb.close()
      dbFile.delete()
    }
}
