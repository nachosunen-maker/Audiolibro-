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
import com.example.domain.audio.AudiobookGenerationService
import com.example.domain.audio.BookGenerationProgress
import com.example.domain.audio.GenerationPhase
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
  private val queueLock = Any()
  private val pendingBookIds = ArrayDeque<String>()
  @Volatile private var activeBookId: String? = null
  @Volatile private var activeJob: Job? = null

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

  /**
   * Punto de entrada desde la interfaz gráfica: inicia el servicio en primer plano
   * (`AudiobookGenerationService`, tipo `dataSync`) para mantener la síntesis activa en segundo plano
   * o con la pantalla apagada. Si el sistema restringe FGS, continúa en `enqueueAudiobookGeneration`.
   */
  fun requestAudiobookGeneration(bookId: String) {
    Log.i(TAG_GEN, "requestAudiobookGeneration(bookId=$bookId) -> iniciando servicio en primer plano")
    clearGenerationError(bookId)
    val startedService =
      runCatching {
          AudiobookGenerationService.startBookGeneration(context.applicationContext, bookId)
          true
        }
        .onFailure { err ->
          Log.w(
            TAG_GEN,
            "No se pudo iniciar AudiobookGenerationService en primer plano; ejecutando cola local directa",
            err,
          )
        }
        .getOrDefault(false)

    if (!startedService) {
      enqueueAudiobookGeneration(bookId)
    }
  }

  fun startAudiobookGeneration(bookId: String) {
    enqueueAudiobookGeneration(bookId)
  }

  fun enqueueAudiobookGeneration(bookId: String) {
    val shouldLaunch =
      synchronized(queueLock) {
        if (activeBookId == bookId && activeJob?.isActive == true) {
          return
        }
        if (!pendingBookIds.contains(bookId)) {
          pendingBookIds.addLast(bookId)
        }
        _generationProgress.update { map ->
          val prev = map[bookId]
          map +
            (bookId to
              BookGenerationProgress(
                bookId = bookId,
                bookTitle = prev?.bookTitle.orEmpty(),
                isRunning = true,
                phase = GenerationPhase.PREPARING,
                totalChapters = prev?.totalChapters ?: 0,
                completedChapters = prev?.completedChapters ?: 0,
                completedBookChunks = prev?.completedBookChunks ?: 0,
                totalBookChunks = prev?.totalBookChunks ?: 0,
                statusMessage = "Preparando los capítulos del libro…",
              ))
        }
        activeJob?.isActive != true
      }

    if (shouldLaunch) {
      launchNextQueuedJob()
    }
  }

  fun hasActiveOrQueuedGenerations(): Boolean =
    synchronized(queueLock) { activeJob?.isActive == true || pendingBookIds.isNotEmpty() }

  private fun launchNextQueuedJob() {
    val nextBookId =
      synchronized(queueLock) {
        if (activeJob?.isActive == true) return
        val id = pendingBookIds.removeFirstOrNull()
        activeBookId = id
        id
      } ?: return

    val job =
      generationScope.launch {
        try {
          val result =
            orchestrator.generateBookAudiobook(nextBookId) { progress ->
              _generationProgress.update { map -> map + (nextBookId to progress) }
            }
          result.onFailure { err ->
            Log.e(
              TAG_GEN,
              "Fallo en generación de audiolibro (bookId=$nextBookId): ${err.javaClass.simpleName} - ${err.message}",
              err,
            )
            _generationProgress.update { map ->
              val current =
                map[nextBookId]
                  ?: BookGenerationProgress(
                    bookId = nextBookId,
                    isRunning = false,
                    phase = GenerationPhase.ERROR,
                  )
              map +
                (nextBookId to
                  current.copy(
                    isRunning = false,
                    phase = GenerationPhase.ERROR,
                    errorMessage = err.localizedMessage ?: "No se pudo completar la generación.",
                  ))
            }
          }
        } catch (e: CancellationException) {
          Log.i(TAG_GEN, "Generación interrumpida/pausada para bookId=$nextBookId")
          _generationProgress.update { map ->
            val current = map[nextBookId]
            if (current != null) {
              map +
                (nextBookId to
                  current.copy(
                    isRunning = false,
                    phase = GenerationPhase.INTERRUPTED,
                    statusMessage = "Generación interrumpida. El progreso guardado se conserva.",
                  ))
            } else {
              map
            }
          }
          throw e
        } catch (t: Throwable) {
          Log.e(
            TAG_GEN,
            "Excepción no controlada en generación de audiolibro (bookId=$nextBookId): ${t.javaClass.simpleName} - ${t.message}",
            t,
          )
          _generationProgress.update { map ->
            val current =
              map[nextBookId]
                ?: BookGenerationProgress(
                  bookId = nextBookId,
                  isRunning = false,
                  phase = GenerationPhase.ERROR,
                )
            map +
              (nextBookId to
                current.copy(
                  isRunning = false,
                  phase = GenerationPhase.ERROR,
                  errorMessage =
                    t.localizedMessage ?: "Ocurrió un error inesperado durante la generación.",
                ))
          }
        } finally {
          val hasMore =
            synchronized(queueLock) {
              if (activeBookId == nextBookId) {
                activeBookId = null
                activeJob = null
              }
              pendingBookIds.isNotEmpty()
            }
          if (hasMore) {
            launchNextQueuedJob()
          }
        }
      }

    synchronized(queueLock) {
      activeJob = job
    }
  }

  fun cancelAudiobookGeneration(bookId: String) {
    val jobToCancel =
      synchronized(queueLock) {
        pendingBookIds.remove(bookId)
        if (activeBookId == bookId) activeJob else null
      }
    jobToCancel?.cancel()
    _generationProgress.update { map ->
      val current = map[bookId]
      if (current != null) {
        map +
          (bookId to
            current.copy(
              isRunning = false,
              phase = GenerationPhase.INTERRUPTED,
              statusMessage = "Generación pausada. El progreso guardado se conserva.",
            ))
      } else {
        map
      }
    }
  }

  fun cancelAllGenerations() {
    val active =
      synchronized(queueLock) {
        val ids = pendingBookIds.toList()
        pendingBookIds.clear()
        val runningId = activeBookId
        Pair(ids + listOfNotNull(runningId), activeJob)
      }
    active.second?.cancel()
    _generationProgress.update { map ->
      var updated = map
      for (id in active.first) {
        val cur = updated[id]
        if (cur != null) {
          updated =
            updated +
              (id to
                cur.copy(
                  isRunning = false,
                  phase = GenerationPhase.INTERRUPTED,
                  statusMessage = "Generación pausada. El progreso guardado se conserva.",
                ))
        }
      }
      updated
    }
  }

  fun clearGenerationError(bookId: String) {
    _generationProgress.update { map ->
      val current = map[bookId] ?: return@update map
      map +
        (bookId to
          current.copy(
            phase =
              if (current.isRunning) current.phase
              else if (current.completedBookChunks > 0) GenerationPhase.INTERRUPTED
              else GenerationPhase.IDLE,
            errorMessage = null,
          ))
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
