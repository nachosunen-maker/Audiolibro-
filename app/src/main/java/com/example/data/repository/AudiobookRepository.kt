package com.example.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import android.provider.OpenableColumns
import com.example.data.local.AppDatabase
import com.example.data.local.AudiobookDao
import com.example.data.local.BookEntity
import com.example.data.local.ChapterEntity
import com.example.data.storage.AudiobookStorageManager
import com.example.domain.audio.AudioSynthesisProvider
import com.example.domain.audio.AudiobookGenerationOrchestrator
import com.example.domain.audio.BookGenerationProgress
import com.example.domain.audio.PiperAudioSynthesisProvider
import com.example.domain.model.AudioGenerationStatus
import com.example.domain.model.Book
import com.example.domain.model.BookWithChapters
import com.example.domain.model.Chapter
import com.example.domain.model.NarratorVoice
import com.example.epub.EpubParseException
import com.example.epub.EpubParser
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AudiobookRepository(
  private val context: Context,
  private val dao: AudiobookDao,
  private val epubParser: EpubParser = EpubParser(),
  val storageManager: AudiobookStorageManager = AudiobookStorageManager(context),
  val audioSynthesisProvider: AudioSynthesisProvider = PiperAudioSynthesisProvider(context),
) {

  private val orchestrator =
    AudiobookGenerationOrchestrator(
      dao = dao,
      storage = storageManager,
      synthesisProvider = audioSynthesisProvider,
    )

  private val generationExceptionHandler =
    CoroutineExceptionHandler { _, throwable ->
      if (throwable !is CancellationException) {
        Log.e(
          TAG_GEN,
          "Excepción no controlada capturada en generationScope: ${throwable.javaClass.simpleName} - ${throwable.message}",
          throwable,
        )
      }
    }
  private val generationScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO + generationExceptionHandler)
  private val activeJobs = mutableMapOf<String, Job>()

  private val _generationProgress =
    MutableStateFlow<Map<String, BookGenerationProgress>>(emptyMap())
  val generationProgress: StateFlow<Map<String, BookGenerationProgress>> =
    _generationProgress.asStateFlow()

  fun observeBooks(): Flow<List<Book>> = dao.observeBooks().map { list -> list.map { it.toDomain() } }

  fun observeBookWithChapters(bookId: String): Flow<BookWithChapters?> =
    combine(dao.observeBook(bookId), dao.observeChapters(bookId)) { bookEntity, chapterEntities ->
      bookEntity?.let { book ->
        BookWithChapters(
          book = book.toDomain(),
          chapters = chapterEntities.map { it.toDomain() },
        )
      }
    }

  fun observeChapter(bookId: String, chapterIndex: Int): Flow<Chapter?> =
    dao.observeChapter(bookId, chapterIndex).map { it?.toDomain() }

  suspend fun importEpub(uri: Uri): Result<Book> =
    withContext(Dispatchers.IO) {
      try {
        val fileName = resolveDisplayName(uri) ?: "libro.epub"
        val inputStream =
          context.contentResolver.openInputStream(uri)
            ?: return@withContext Result.failure(
              EpubParseException("No se pudo abrir el archivo seleccionado.")
            )

        val parsedBook =
          inputStream.use { stream ->
            epubParser.parseStream(
              inputStream = stream,
              fallbackTitle = fileName,
              tempCacheDir = context.cacheDir,
            )
          }

        val bookId = UUID.randomUUID().toString()
        val coverPath = saveCoverIfPresent(bookId, parsedBook.coverBytes)
        val now = System.currentTimeMillis()
        val defaultVoice = NarratorVoice.FEMALE

        val bookEntity =
          BookEntity(
            id = bookId,
            title = parsedBook.title,
            author = parsedBook.author,
            language = parsedBook.language,
            publisher = parsedBook.publisher,
            description = parsedBook.description,
            coverImagePath = coverPath,
            sourceFileName = fileName,
            selectedVoice = defaultVoice.name,
            totalChapters = parsedBook.chapters.size,
            totalWords = parsedBook.totalWords,
            totalCharacters = parsedBook.totalCharacters,
            estimatedMinutes = parsedBook.estimatedMinutes,
            importedAt = now,
          )

        val chapterEntities =
          parsedBook.chapters.map { ch ->
            ChapterEntity(
              id = "${bookId}_ch_${ch.chapterIndex}",
              bookId = bookId,
              chapterIndex = ch.chapterIndex,
              title = ch.title,
              contentText = ch.contentText,
              wordCount = ch.wordCount,
              characterCount = ch.characterCount,
              estimatedMinutes = ch.estimatedMinutes,
              audioStatus = AudioGenerationStatus.NOT_GENERATED.name,
              audioFilePath = null,
            )
          }

        dao.insertBookWithChapters(bookEntity, chapterEntities)
        Result.success(bookEntity.toDomain())
      } catch (e: EpubParseException) {
        Result.failure(e)
      } catch (e: Exception) {
        Result.failure(
          EpubParseException(
            e.localizedMessage ?: "Ocurrió un error al procesar el archivo EPUB.",
            e,
          )
        )
      }
    }

  suspend fun updateSelectedVoice(bookId: String, voice: NarratorVoice) {
    withContext(Dispatchers.IO) { dao.updateSelectedVoice(bookId, voice.name) }
  }

  suspend fun updatePlaybackPosition(bookId: String, chapterIndex: Int, positionMs: Long) {
    withContext(Dispatchers.IO) {
      dao.updateBookPlaybackPosition(bookId, chapterIndex, positionMs.coerceAtLeast(0L))
      playbackPrefs.edit().putString(KEY_LAST_PLAYED_BOOK_ID, bookId).apply()
    }
  }

  suspend fun getBookWithChaptersOnce(bookId: String): BookWithChapters? =
    withContext(Dispatchers.IO) {
      val book = dao.getBookOnce(bookId) ?: return@withContext null
      val chapters = dao.getChaptersOnce(bookId).map { it.toDomain() }
      BookWithChapters(book = book.toDomain(), chapters = chapters)
    }

  suspend fun getLastPlayedBookWithChaptersOnce(): BookWithChapters? =
    withContext(Dispatchers.IO) {
      val preferredBookId = playbackPrefs.getString(KEY_LAST_PLAYED_BOOK_ID, null)
      val preferred = preferredBookId?.let { id -> getBookWithChaptersOnce(id) }
      if (preferred != null && preferred.book.lastPlayedChapterIndex != null) {
        return@withContext preferred
      }
      val fallbackBook = dao.getLastPlayedBookOnce() ?: return@withContext null
      val chapters = dao.getChaptersOnce(fallbackBook.id).map { it.toDomain() }
      BookWithChapters(book = fallbackBook.toDomain(), chapters = chapters)
    }

  fun startAudiobookGeneration(bookId: String) {
    if (activeJobs[bookId]?.isActive == true) return
    _generationProgress.update { map ->
      map +
        (bookId to
          BookGenerationProgress(
            bookId = bookId,
            isRunning = true,
            statusMessage = "Preparando fragmentos del libro…",
          ))
    }

    val job =
      generationScope.launch {
        try {
          val result =
            orchestrator.generateBookAudiobook(bookId) { progress ->
              _generationProgress.update { map -> map + (bookId to progress) }
            }
          result.onFailure { err ->
            Log.e(TAG_GEN, "Fallo en generación de audiolibro (bookId=$bookId): ${err.javaClass.simpleName} - ${err.message}", err)
            _generationProgress.update { map ->
              val current = map[bookId] ?: BookGenerationProgress(bookId = bookId, isRunning = false)
              map +
                (bookId to
                  current.copy(
                    isRunning = false,
                    errorMessage = err.localizedMessage ?: "No se pudo completar la generación.",
                  ))
            }
          }
        } catch (e: CancellationException) {
          Log.i(TAG_GEN, "Generación cancelada para bookId=$bookId")
          throw e
        } catch (t: Throwable) {
          Log.e(TAG_GEN, "Excepción no controlada en generación de audiolibro (bookId=$bookId): ${t.javaClass.simpleName} - ${t.message}", t)
          _generationProgress.update { map ->
            val current = map[bookId] ?: BookGenerationProgress(bookId = bookId, isRunning = false)
            map +
              (bookId to
                current.copy(
                  isRunning = false,
                  errorMessage = t.localizedMessage ?: "Ocurrió un error inesperado durante la generación.",
                ))
          }
        } finally {
          activeJobs.remove(bookId)
        }
      }
    activeJobs[bookId] = job
  }

  fun cancelAudiobookGeneration(bookId: String) {
    activeJobs[bookId]?.cancel()
    activeJobs.remove(bookId)
    _generationProgress.update { map ->
      val current = map[bookId]
      if (current != null) {
        map +
          (bookId to
            current.copy(
              isRunning = false,
              statusMessage = "Generación pausada. El progreso guardado se conserva.",
            ))
      } else {
        map
      }
    }
  }

  fun clearGenerationError(bookId: String) {
    _generationProgress.update { map ->
      val current = map[bookId] ?: return@update map
      map + (bookId to current.copy(errorMessage = null))
    }
  }

  fun release() {
    audioSynthesisProvider.release()
  }

  suspend fun getBookAudioStorageBytes(bookId: String): Long =
    withContext(Dispatchers.IO) { storageManager.calculateBookAudioBytes(bookId) }

  suspend fun exportChapterWav(chapter: Chapter, destinationUri: Uri): Result<Long> =
    storageManager.exportChapterWav(chapter, destinationUri)

  suspend fun exportFullAudiobookWav(
    chapters: List<Chapter>,
    destinationUri: Uri,
  ): Result<Long> = storageManager.exportFullAudiobookWav(chapters, destinationUri)

  suspend fun deleteGeneratedAudioOnly(bookId: String) {
    cancelAudiobookGeneration(bookId)
    withContext(Dispatchers.IO) {
      storageManager.deleteBookAudioFiles(bookId)
      dao.clearBookGeneratedAudio(bookId)
      if (playbackPrefs.getString(KEY_LAST_PLAYED_BOOK_ID, null) == bookId) {
        playbackPrefs.edit().remove(KEY_LAST_PLAYED_BOOK_ID).apply()
      }
    }
  }

  suspend fun deleteBook(bookId: String) {
    cancelAudiobookGeneration(bookId)
    withContext(Dispatchers.IO) {
      val existing = dao.getBookOnce(bookId)
      existing?.coverImagePath?.let { path -> runCatching { File(path).delete() } }
      storageManager.deleteBookAudioFiles(bookId)
      dao.deleteBookById(bookId)
      if (playbackPrefs.getString(KEY_LAST_PLAYED_BOOK_ID, null) == bookId) {
        playbackPrefs.edit().remove(KEY_LAST_PLAYED_BOOK_ID).apply()
      }
    }
  }

  private fun saveCoverIfPresent(bookId: String, bytes: ByteArray?): String? {
    if (bytes == null || bytes.isEmpty()) return null
    val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
    val outFile = File(coversDir, "$bookId.img")
    return runCatching {
        outFile.writeBytes(bytes)
        outFile.absolutePath
      }
      .getOrNull()
  }

  private fun resolveDisplayName(uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
          ?.use { cursor ->
            if (cursor.moveToFirst()) {
              val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
              if (index >= 0) cursor.getString(index) else null
            } else {
              null
            }
          }
      }
      .getOrNull()
      ?: uri.lastPathSegment?.substringAfterLast('/')
  }

  private val playbackPrefs by lazy {
    context.applicationContext.getSharedPreferences(PREFS_PLAYBACK, Context.MODE_PRIVATE)
  }

  companion object {
    private const val TAG_GEN = "AudiobookGeneration"
    private const val PREFS_PLAYBACK = "audiolibro_playback_prefs"
    private const val KEY_LAST_PLAYED_BOOK_ID = "last_played_book_id"

    @Volatile private var INSTANCE: AudiobookRepository? = null

    fun getInstance(context: Context): AudiobookRepository {
      return INSTANCE
        ?: synchronized(this) {
          val appContext = context.applicationContext
          val db = AppDatabase.getInstance(appContext)
          AudiobookRepository(appContext, db.audiobookDao()).also { INSTANCE = it }
        }
    }
  }
}
