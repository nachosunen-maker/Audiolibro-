package com.example.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.work.ListenableWorker
import com.example.data.local.AppDatabase
import com.example.data.local.AudiobookDao
import com.example.data.local.BookEntity
import com.example.data.local.ChapterEntity
import com.example.data.local.GenerationTaskEntity
import com.example.data.storage.AudiobookStorageManager
import com.example.domain.audio.AudioSynthesisProvider
import com.example.domain.audio.AudiobookGenerationOrchestrator
import com.example.domain.audio.AudiobookGenerationService
import com.example.domain.audio.AudiobookRecoveryScheduler
import com.example.domain.audio.BookGenerationProgress
import com.example.domain.audio.GenerationPhase
import com.example.domain.audio.PiperAudioSynthesisProvider
import com.example.domain.model.AudioGenerationStatus
import com.example.domain.model.Book
import com.example.domain.model.BookWithChapters
import com.example.domain.model.Chapter
import com.example.domain.model.GenerationTaskStatus
import com.example.domain.model.NarratorVoice
import com.example.epub.EpubParseException
import com.example.epub.EpubParser
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
  private val taskPersistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val taskDbMutex = Mutex()
  private val queueLock = Any()
  private val pendingBookIds = ArrayDeque<String>()
  @Volatile private var activeBookId: String? = null
  @Volatile private var activeJob: Job? = null
  private val osTimeoutInProgress = AtomicBoolean(false)

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

  fun observeGenerationTask(bookId: String): Flow<GenerationTaskEntity?> =
    dao.observeGenerationTask(bookId)

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
   * Punto de entrada desde la interfaz gráfica: persiste la tarea en Room, inicia el servicio
   * en primer plano (`mediaProcessing` en Android 15+, `specialUse` en Android 14) y programa el
   * respaldo persistente en WorkManager para mantener la síntesis activa ante cualquier corte del SO.
   */
  fun requestAudiobookGeneration(bookId: String) {
    Log.i(TAG_GEN, "requestAudiobookGeneration(bookId=$bookId) -> iniciando servicio en primer plano")
    osTimeoutInProgress.set(false)
    clearGenerationError(bookId)

    taskPersistenceScope.launch {
      persistQueuedTaskLocked(bookId, forceQueuedStatus = true)
    }

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
      AudiobookRecoveryScheduler.scheduleRecovery(
        context = context.applicationContext,
        initialDelaySeconds = 15L,
        replaceExisting = false,
      )
    }
  }

  fun startAudiobookGeneration(bookId: String) {
    enqueueAudiobookGeneration(bookId)
  }

  fun enqueueAudiobookGeneration(bookId: String) {
    osTimeoutInProgress.set(false)
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

    taskPersistenceScope.launch {
      persistQueuedTaskLocked(bookId, forceQueuedStatus = false)
    }

    if (shouldLaunch) {
      launchNextQueuedJob()
    }
  }

  fun hasActiveOrQueuedGenerations(): Boolean =
    synchronized(queueLock) { activeJob?.isActive == true || pendingBookIds.isNotEmpty() }

  /**
   * Inspecciona Room al arranque del proceso, regreso al primer plano o reinicio de `START_STICKY`
   * y reanuda automáticamente cualquier tarea en estado `QUEUED`, `RUNNING` o `INTERRUPTED_BY_OS`
   * sin requerir intervención del usuario y sin tocar tareas `PAUSED_BY_USER`, `COMPLETED` ni `ERROR`.
   */
  suspend fun restoreRecoverableGenerations(startServiceIfNeeded: Boolean = true): Boolean =
    withContext(Dispatchers.IO) {
      val recoverable =
        taskDbMutex.withLock {
          dao.getRecoverableGenerationTasksOnce()
        }
      if (recoverable.isEmpty()) {
        return@withContext false
      }

      if (hasActiveOrQueuedGenerations()) {
        return@withContext true
      }

      val now = System.currentTimeMillis()
      for (task in recoverable) {
        if (task.parsedStatus() == GenerationTaskStatus.RUNNING || task.parsedStatus() == GenerationTaskStatus.INTERRUPTED_BY_OS) {
          taskDbMutex.withLock {
            dao.updateGenerationTaskStatus(
              bookId = task.bookId,
              status = GenerationTaskStatus.QUEUED.name,
              updatedAtMs = now,
              errorMessage = null,
            )
          }
        }
      }

      val firstBookId = recoverable.first().bookId
      synchronized(queueLock) {
        for (task in recoverable) {
          if (!pendingBookIds.contains(task.bookId)) {
            pendingBookIds.addLast(task.bookId)
          }
          _generationProgress.update { map ->
            map +
              (task.bookId to
                BookGenerationProgress(
                  bookId = task.bookId,
                  isRunning = true,
                  phase = GenerationPhase.RESUMING,
                  completedBookChunks = task.completedChunks,
                  totalBookChunks = task.totalChunks,
                  statusMessage =
                    "Reanudando generación automáticamente (${task.completedChunks}/${task.totalChunks} fragmentos)…",
                ))
          }
        }
      }

      if (startServiceIfNeeded) {
        val started =
          runCatching {
              AudiobookGenerationService.startBookGeneration(context.applicationContext, firstBookId)
              true
            }
            .onFailure { err ->
              Log.w(
                TAG_GEN,
                "FGS no permitido al restaurar cola en segundo plano; programando WorkManager",
                err,
              )
            }
            .getOrDefault(false)

        if (!started) {
          AudiobookRecoveryScheduler.scheduleRecovery(
            context = context.applicationContext,
            initialDelaySeconds = 2L,
            replaceExisting = true,
          )
        }
      } else {
        launchNextQueuedJob()
      }
      true
    }

  internal fun tryStartForegroundServiceForRecovery(): Boolean {
    val firstBookId =
      runBlocking {
        taskDbMutex.withLock { dao.getRecoverableGenerationTasksOnce().firstOrNull()?.bookId }
      } ?: return false
    return runCatching {
        AudiobookGenerationService.startBookGeneration(context.applicationContext, firstBookId)
        true
      }
      .onFailure { err ->
        Log.w(TAG_GEN, "Sistema restringió FGS desde WorkManager para bookId=$firstBookId", err)
      }
      .getOrDefault(false)
  }

  /**
   * Gestiona `Service.onTimeout(startId, fgsType)` de Android 15+ sin marcar la tarea como
   * cancelación del usuario: conserva todos los fragmentos WAV completados, marca el libro activo
   * como `INTERRUPTED_BY_OS`, libera la cola en memoria y programa reanudación automática en WorkManager.
   */
  fun handleOsServiceTimeout() {
    osTimeoutInProgress.set(true)
    val affected =
      synchronized(queueLock) {
        val ids = mutableListOf<String>()
        activeBookId?.let { ids += it }
        ids += pendingBookIds
        pendingBookIds.clear()
        Pair(ids.distinct(), activeJob)
      }

    val now = System.currentTimeMillis()
    runBlocking(Dispatchers.IO) {
      taskDbMutex.withLock {
        dao.markRunningTasksInterruptedByOs(now)
      }
    }

    affected.second?.cancel(CancellationException("OS_FGS_TIMEOUT"))

    _generationProgress.update { map ->
      var updated = map
      for (id in affected.first) {
        val cur = updated[id]
        if (cur != null) {
          updated =
            updated +
              (id to
                cur.copy(
                  isRunning = false,
                  phase = GenerationPhase.INTERRUPTED,
                  statusMessage =
                    "Límite temporal del sistema alcanzado. La generación se reanudará automáticamente.",
                ))
        }
      }
      updated
    }

    AudiobookRecoveryScheduler.scheduleRecovery(
      context = context.applicationContext,
      initialDelaySeconds = 5L,
      replaceExisting = true,
    )
  }

  internal suspend fun runRecoverableTasksInWorker(): ListenableWorker.Result {
    val tasks = taskDbMutex.withLock { dao.getRecoverableGenerationTasksOnce() }
    if (tasks.isEmpty()) return ListenableWorker.Result.success()
    if (hasActiveOrQueuedGenerations()) return ListenableWorker.Result.success()

    for (task in tasks) {
      val result =
        try {
          orchestrator.generateBookAudiobook(task.bookId) { progress ->
            _generationProgress.update { map -> map + (task.bookId to progress) }
          }
        } catch (e: CancellationException) {
          // Worker detenido temporalmente por el SO: marcar INTERRUPTED_BY_OS y solicitar reintento.
          val now = System.currentTimeMillis()
          taskDbMutex.withLock {
            val cur = dao.getGenerationTaskOnce(task.bookId)
            if (cur != null && cur.parsedStatus() != GenerationTaskStatus.PAUSED_BY_USER) {
              dao.updateGenerationTaskStatus(
                bookId = task.bookId,
                status = GenerationTaskStatus.INTERRUPTED_BY_OS.name,
                updatedAtMs = now,
                errorMessage = null,
              )
            }
          }
          return ListenableWorker.Result.retry()
        }

      if (result.isFailure) {
        val err = result.exceptionOrNull()
        Log.e(TAG_GEN, "Worker de recuperación falló en bookId=${task.bookId}", err)
      }
    }

    val remaining = taskDbMutex.withLock { dao.getRecoverableGenerationTasksOnce() }
    return if (remaining.isEmpty()) {
      ListenableWorker.Result.success()
    } else {
      ListenableWorker.Result.retry()
    }
  }

  private fun launchNextQueuedJob() {
    val nextBookId =
      synchronized(queueLock) {
        if (activeJob?.isActive == true) return
        val id = pendingBookIds.removeFirstOrNull()
        activeBookId = id
        id
      }

    if (nextBookId == null) {
      // Comprobar también en Room por si hay tareas QUEUED persistidas pendientes tras una recuperación.
      val persistedJob =
        generationScope.launch {
          val dbNext =
            taskDbMutex.withLock { dao.getRecoverableGenerationTasksOnce().firstOrNull()?.bookId }
          if (dbNext != null) {
            enqueueAudiobookGeneration(dbNext)
          }
        }
      synchronized(queueLock) {
        if (activeJob == null) {
          activeJob = persistedJob
        }
      }
      return
    }

    val job =
      generationScope.launch(start = CoroutineStart.LAZY) {
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
          val pausedByOsTimeout = osTimeoutInProgress.get()
          _generationProgress.update { map ->
            val current = map[nextBookId]
            if (current != null) {
              map +
                (nextBookId to
                  current.copy(
                    isRunning = false,
                    phase = GenerationPhase.INTERRUPTED,
                    statusMessage =
                      if (pausedByOsTimeout) {
                        "Límite temporal del sistema alcanzado. Se reanudará automáticamente."
                      } else {
                        "Generación pausada. El progreso guardado se conserva."
                      },
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
          if (hasMore && !osTimeoutInProgress.get()) {
            launchNextQueuedJob()
          } else if (!hasMore && !osTimeoutInProgress.get()) {
            // Si aún quedan tareas QUEUED en Room que entraron mientras corría este libro, continuarlas.
            val dbRemaining =
              runCatching {
                  taskDbMutex.withLock { dao.getRecoverableGenerationTasksOnce() }
                }
                .getOrDefault(emptyList())
                .filter { it.bookId != nextBookId }
            if (dbRemaining.isNotEmpty()) {
              synchronized(queueLock) {
                dbRemaining.forEach { t ->
                  if (!pendingBookIds.contains(t.bookId)) pendingBookIds.addLast(t.bookId)
                }
              }
              launchNextQueuedJob()
            } else {
              AudiobookRecoveryScheduler.cancelRecovery(context.applicationContext)
            }
          }
        }
      }

    synchronized(queueLock) {
      activeJob = job
    }
    job.start()
  }

  fun cancelAudiobookGeneration(bookId: String) {
    val jobToCancel =
      synchronized(queueLock) {
        pendingBookIds.remove(bookId)
        if (activeBookId == bookId) activeJob else null
      }

    // Persistir PAUSED_BY_USER antes de cancelar la corrutina para que la cola persistente
    // distinga la pausa explícita del usuario frente a una muerte del proceso por el SO.
    taskPersistenceScope.launch {
      taskDbMutex.withLock {
        val existing = dao.getGenerationTaskOnce(bookId)
        if (existing != null) {
          dao.updateGenerationTaskStatus(
            bookId = bookId,
            status = GenerationTaskStatus.PAUSED_BY_USER.name,
            updatedAtMs = System.currentTimeMillis(),
            errorMessage = null,
          )
        }
      }
      val anyOtherRecoverable =
        taskDbMutex.withLock { dao.getRecoverableGenerationTasksOnce().isNotEmpty() }
      if (!anyOtherRecoverable) {
        AudiobookRecoveryScheduler.cancelRecovery(context.applicationContext)
      }
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
              statusMessage = "Generación pausada por el usuario. El progreso guardado se conserva.",
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
        Pair((ids + listOfNotNull(runningId)).distinct(), activeJob)
      }

    taskPersistenceScope.launch {
      val now = System.currentTimeMillis()
      taskDbMutex.withLock {
        for (id in active.first) {
          val existing = dao.getGenerationTaskOnce(id)
          if (existing != null) {
            dao.updateGenerationTaskStatus(
              bookId = id,
              status = GenerationTaskStatus.PAUSED_BY_USER.name,
              updatedAtMs = now,
              errorMessage = null,
            )
          }
        }
      }
      AudiobookRecoveryScheduler.cancelRecovery(context.applicationContext)
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
                  statusMessage = "Generación pausada por el usuario. El progreso guardado se conserva.",
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

  private suspend fun persistQueuedTaskLocked(bookId: String, forceQueuedStatus: Boolean) {
    taskDbMutex.withLock {
      val book = dao.getBookOnce(bookId) ?: return@withLock
      val existing = dao.getGenerationTaskOnce(bookId)
      val now = System.currentTimeMillis()
      if (existing == null) {
        dao.upsertGenerationTask(
          GenerationTaskEntity(
            bookId = bookId,
            queueOrder = now,
            voiceName = book.selectedVoice,
            status = GenerationTaskStatus.QUEUED.name,
            currentChapterIndex = 0,
            currentChunkIndex = 0,
            completedChunks = 0,
            totalChunks = 0,
            startedAtMs = now,
            updatedAtMs = now,
            lastValidProgressAtMs = 0L,
            errorMessage = null,
          )
        )
      } else if (forceQueuedStatus || existing.parsedStatus() != GenerationTaskStatus.RUNNING) {
        dao.upsertGenerationTask(
          existing.copy(
            voiceName = book.selectedVoice,
            status = GenerationTaskStatus.QUEUED.name,
            updatedAtMs = now,
            errorMessage = null,
          )
        )
      }
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
