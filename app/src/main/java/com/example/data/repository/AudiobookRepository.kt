package com.example.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.example.data.local.AppDatabase
import com.example.data.local.AudiobookDao
import com.example.data.local.BookEntity
import com.example.data.local.ChapterEntity
import com.example.data.local.GenerationTaskEntity
import com.example.data.storage.AudiobookStorageManager
import com.example.domain.audio.AdaptiveBufferPolicy
import com.example.domain.audio.AudioSynthesisProvider
import com.example.domain.audio.AudiobookGenerationOrchestrator
import com.example.domain.audio.BufferEvaluationInput
import com.example.domain.audio.DeviceCapabilityInspector
import com.example.domain.audio.PiperAudioSynthesisProvider
import com.example.domain.audio.ProgressiveChapterProgressCalculator
import com.example.domain.audio.ProgressiveChapterState
import com.example.domain.audio.ProgressivePlaybackSnapshot
import com.example.domain.audio.RtfEstimator
import com.example.domain.audio.WavAudioCodec
import com.example.domain.model.AudioGenerationStatus
import com.example.domain.model.Book
import com.example.domain.model.BookWithChapters
import com.example.domain.model.Chapter
import com.example.domain.model.ChapterChunk
import com.example.domain.model.ChapterChunkStatus
import com.example.domain.model.GenerationTaskStatus
import com.example.domain.model.NarratorVoice
import com.example.domain.model.ProgressiveChapterPhase
import com.example.epub.EpubParseException
import com.example.epub.EpubParser
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
  private val generationParentJob = SupervisorJob()
  private val generationScope =
    CoroutineScope(generationParentJob + Dispatchers.IO + generationExceptionHandler)
  private val taskPersistenceParentJob = SupervisorJob()
  private val taskPersistenceScope = CoroutineScope(taskPersistenceParentJob + Dispatchers.IO)
  private val taskDbMutex = Mutex()
  private val taskPersistenceChannel = Channel<TaskPersistenceOp>(Channel.UNLIMITED)
  private val pausedBookIds = ConcurrentHashMap.newKeySet<String>()
  private val cancelledBookIds = ConcurrentHashMap.newKeySet<String>()

  private sealed interface TaskPersistenceOp {
    data class Run(val block: suspend () -> Unit) : TaskPersistenceOp

    data class Flush(val ack: CompletableDeferred<Unit>) : TaskPersistenceOp
  }

  private val progressiveLock = Any()
  @Volatile private var progressiveJob: Job? = null
  @Volatile private var progressiveTargetKey: Pair<String, Int>? = null
  @Volatile private var progressiveUiActive: Boolean = false
  @Volatile private var progressiveAwaitingPlaybackKey: Pair<String, Int>? = null
  @Volatile private var progressivePausedByUser: Boolean = false
  @Volatile private var progressivePlaybackSnapshot: ProgressivePlaybackSnapshot = ProgressivePlaybackSnapshot()
  @Volatile private var progressiveSignalChannel: Channel<Unit> = Channel(Channel.CONFLATED)
  @Volatile private var progressiveRtfEstimator: RtfEstimator = RtfEstimator()

  private val _progressiveChapterState = MutableStateFlow<ProgressiveChapterState?>(null)
  val progressiveChapterState: StateFlow<ProgressiveChapterState?> =
    _progressiveChapterState.asStateFlow()

  init {
    taskPersistenceScope.launch {
      for (op in taskPersistenceChannel) {
        when (op) {
          is TaskPersistenceOp.Run ->
            taskDbMutex.withLock {
              runCatching { op.block() }
                .onFailure { err ->
                  Log.e(TAG_GEN, "Error en operación secuencial de persistencia", err)
                }
            }
          is TaskPersistenceOp.Flush -> op.ack.complete(Unit)
        }
      }
    }
  }

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

  fun observeChunksForChapter(chapterId: String): Flow<List<ChapterChunk>> =
    dao.observeChunksForChapter(chapterId).map { list -> list.map { it.toDomain() } }

  fun observeGenerationTask(bookId: String): Flow<GenerationTaskEntity?> =
    dao.observeGenerationTask(bookId)

  suspend fun getChapterWithChunksOnce(
    bookId: String,
    chapterIndex: Int,
  ): Pair<Chapter, List<ChapterChunk>>? =
    withContext(Dispatchers.IO) {
      val ch = dao.getChapterOnce(bookId, chapterIndex) ?: return@withContext null
      val chunks = dao.getChunksForChapterOnce(ch.id).sortedBy { it.chunkIndex }.map { it.toDomain() }
      ch.toDomain() to chunks
    }

  suspend fun getCompletedValidChunksOnce(chapterId: String): List<ChapterChunk> =
    withContext(Dispatchers.IO) {
      dao.getChunksForChapterOnce(chapterId)
        .sortedBy { it.chunkIndex }
        .takeWhile { entity ->
          entity.status == ChapterChunkStatus.COMPLETED.name &&
            !entity.wavFilePath.isNullOrBlank() &&
            WavAudioCodec.isValidWavFile(File(entity.wavFilePath))
        }
        .map { it.toDomain() }
    }

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

  suspend fun getAdjacentChapter(
    bookId: String,
    currentChapterIndex: Int,
    delta: Int,
  ): Chapter? =
    withContext(Dispatchers.IO) {
      val chapters = dao.getChaptersOnce(bookId).sortedBy { it.chapterIndex }
      val pos = chapters.indexOfFirst { it.chapterIndex == currentChapterIndex }
      if (pos < 0) return@withContext null
      chapters.getOrNull(pos + delta)?.toDomain()
    }

  fun setAwaitingPlaybackStart(bookId: String, chapterIndex: Int, awaiting: Boolean) {
    synchronized(progressiveLock) {
      progressiveAwaitingPlaybackKey = if (awaiting) (bookId to chapterIndex) else null
    }
    progressiveSignalChannel.trySend(Unit)
  }

  fun startOnDemandChapterLoading(bookId: String, chapterIndex: Int) {
    setAwaitingPlaybackStart(bookId, chapterIndex, awaiting = true)
    startProgressiveChapterLoading(bookId, chapterIndex, resetUserPause = true)
  }

  fun startProgressiveChapterLoading(
    bookId: String,
    chapterIndex: Int,
    resetUserPause: Boolean = true,
  ) {
    pausedBookIds.remove(bookId)
    cancelledBookIds.remove(bookId)

    val key = bookId to chapterIndex
    synchronized(progressiveLock) {
      progressiveUiActive = true
      if (resetUserPause) {
        progressivePausedByUser = false
      }
      if (progressiveTargetKey == key && progressiveJob?.isActive == true) {
        progressiveSignalChannel.trySend(Unit)
        return
      }
      progressiveJob?.cancel()
      progressiveTargetKey = key
      progressiveSignalChannel = Channel(Channel.CONFLATED)
      val previousRtf =
        _progressiveChapterState.value?.takeIf { it.bookId == bookId }?.measuredRtf ?: 0f
      if (!progressiveRtfEstimator.hasMeasurement() && previousRtf > 0f) {
        progressiveRtfEstimator = RtfEstimator(previousRtf)
      }
    }

    val job =
      generationScope.launch(start = CoroutineStart.LAZY) {
        val deviceProfile = DeviceCapabilityInspector.inspect(context)
        if (!progressiveRtfEstimator.hasMeasurement()) {
          val savedRtf = dao.getGenerationTaskOnce(bookId)?.measuredRtf ?: 0f
          if (savedRtf > 0f) {
            progressiveRtfEstimator = RtfEstimator(savedRtf)
          }
        }
        try {
          orchestrator.prepareAndGenerateProgressiveChapter(
            bookId = bookId,
            chapterIndex = chapterIndex,
            rtfEstimator = progressiveRtfEstimator,
            deviceProfile = deviceProfile,
            playbackStateProvider = { progressivePlaybackSnapshot },
            isPausedProvider = { progressivePausedByUser },
            sessionActiveProvider = { progressiveUiActive || progressiveAwaitingPlaybackKey == key },
            cancellationStatusProvider = ::resolveCancellationTaskStatus,
            awaitSignal = { progressiveSignalChannel.receive() },
            onProgressiveState = { state -> _progressiveChapterState.value = state },
          )
        } finally {
          synchronized(progressiveLock) {
            if (progressiveTargetKey == key) {
              progressiveJob = null
            }
            if (progressiveAwaitingPlaybackKey == key) {
              progressiveAwaitingPlaybackKey = null
            }
          }
        }
      }

    synchronized(progressiveLock) {
      progressiveJob = job
    }
    job.start()
  }

  fun pauseProgressiveGeneration(userPaused: Boolean = true) {
    progressivePausedByUser = userPaused
    progressiveSignalChannel.trySend(Unit)
    _progressiveChapterState.update { cur ->
      cur?.takeIf { !it.isCompleteChapterReady }?.copy(
        phase = ProgressiveChapterPhase.PAUSED,
        isSynthesizing = false,
        statusMessage = "Generación pausada",
      ) ?: cur
    }
    val cur = _progressiveChapterState.value
    if (cur != null && !cur.isCompleteChapterReady) {
      enqueuePersistenceOp {
        val task = dao.getGenerationTaskOnce(cur.bookId)
        if (task != null && task.parsedStatus() != GenerationTaskStatus.COMPLETED) {
          dao.updateGenerationTaskStatus(
            bookId = cur.bookId,
            status = GenerationTaskStatus.PAUSED_BY_USER.name,
            updatedAtMs = System.currentTimeMillis(),
            errorMessage = null,
          )
        }
      }
    }
  }

  fun resumeProgressiveGeneration(bookId: String, chapterIndex: Int) {
    progressivePausedByUser = false
    val runningSame =
      synchronized(progressiveLock) {
        progressiveTargetKey == (bookId to chapterIndex) && progressiveJob?.isActive == true
      }
    if (runningSame) {
      progressiveSignalChannel.trySend(Unit)
    } else {
      startProgressiveChapterLoading(bookId, chapterIndex, resetUserPause = true)
    }
  }

  fun onProgressiveUiExited() {
    progressiveUiActive = false
    progressiveSignalChannel.trySend(Unit)
  }

  fun notifyProgressivePlaybackState(
    bookId: String,
    chapterIndex: Int,
    chapterPositionMs: Long,
    isPlaying: Boolean,
    playbackSpeed: Float = 1.0f,
  ) {
    progressivePlaybackSnapshot =
      ProgressivePlaybackSnapshot(
        bookId = bookId,
        chapterIndex = chapterIndex,
        isPlaying = isPlaying,
        playedPositionMs = chapterPositionMs.coerceAtLeast(0L),
        playbackSpeed = playbackSpeed.coerceIn(0.5f, 3.0f),
      )

    if (isPlaying && progressiveAwaitingPlaybackKey == (bookId to chapterIndex)) {
      progressiveAwaitingPlaybackKey = null
    }

    val currentState = _progressiveChapterState.value
    if (currentState != null && currentState.bookId == bookId && currentState.chapterIndex == chapterIndex) {
      val ahead = (currentState.bufferedDurationMs - chapterPositionMs.coerceAtLeast(0L)).coerceAtLeast(0L)
      val remainingChunks = (currentState.totalChunks - currentState.chunksCompleted).coerceAtLeast(0)
      val decision =
        AdaptiveBufferPolicy.evaluate(
          BufferEvaluationInput(
            deviceProfile = currentState.deviceProfile,
            measuredRtf = progressiveRtfEstimator.currentRtf().takeIf { it > 0f } ?: currentState.measuredRtf,
            hasRtfMeasurement = progressiveRtfEstimator.hasMeasurement() || currentState.measuredRtf > 0f,
            playbackSpeed = playbackSpeed,
            chunksCompleted = currentState.chunksCompleted,
            totalChunksInChapter = currentState.totalChunks,
            bufferedDurationMs = currentState.bufferedDurationMs,
            aheadDurationMs = ahead,
            remainingChunks = remainingChunks,
            isPlaying = isPlaying,
            isPausedByUser = progressivePausedByUser,
            isSessionActive = progressiveUiActive || progressiveAwaitingPlaybackKey == (bookId to chapterIndex),
          )
        )

      val updatedPhase =
        when {
          currentState.isCompleteChapterReady -> ProgressiveChapterPhase.COMPLETED
          currentState.phase == ProgressiveChapterPhase.RECOVERABLE_ERROR -> ProgressiveChapterPhase.RECOVERABLE_ERROR
          progressivePausedByUser -> ProgressiveChapterPhase.PAUSED
          isPlaying -> ProgressiveChapterPhase.PLAYING_LOOKAHEAD
          decision.canStartPlayback -> ProgressiveChapterPhase.READY_TO_PLAY
          else -> currentState.phase
        }

      _progressiveChapterState.value =
        currentState.copy(
          phase = updatedPhase,
          playedDurationMs = chapterPositionMs.coerceAtLeast(0L),
          aheadDurationMs = ahead,
          aheadLookaheadTargetMs = decision.aheadLookaheadTargetMs,
          playbackSpeed = playbackSpeed,
          effectiveRtf = decision.effectiveRtf,
          canStartPlayback = decision.canStartPlayback,
        )

      val jobRunning =
        synchronized(progressiveLock) {
          progressiveTargetKey == (bookId to chapterIndex) && progressiveJob?.isActive == true
        }
      if (isPlaying && !progressivePausedByUser && !currentState.isCompleteChapterReady) {
        if (jobRunning) {
          if (decision.shouldGenerateMore) {
            progressiveSignalChannel.trySend(Unit)
          }
        } else if (decision.shouldGenerateMore) {
          startProgressiveChapterLoading(bookId, chapterIndex, resetUserPause = false)
        }
      } else {
        progressiveSignalChannel.trySend(Unit)
      }
    }
  }

  suspend fun hydrateProgressiveChapterPreview(bookId: String, chapterIndex: Int) {
    withContext(Dispatchers.IO) {
      val book = dao.getBookOnce(bookId) ?: return@withContext
      val chapters = dao.getChaptersOnce(bookId).sortedBy { it.chapterIndex }
      val pos = chapters.indexOfFirst { it.chapterIndex == chapterIndex }
      val chapter = chapters.getOrNull(pos) ?: return@withContext
      val voice = NarratorVoice.fromNameOrDefault(book.selectedVoice)
      val task = dao.getGenerationTaskOnce(bookId)
      if (!progressiveRtfEstimator.hasMeasurement() && (task?.measuredRtf ?: 0f) > 0f) {
        progressiveRtfEstimator = RtfEstimator(task!!.measuredRtf)
      }
      val profile = DeviceCapabilityInspector.inspect(context)
      val existingChunks = dao.getChunksForChapterOnce(chapter.id).sortedBy { it.chunkIndex }
      val matchingChunks = existingChunks.filter { it.voiceName == voice.name }
      val voiceMatches = existingChunks.isEmpty() || matchingChunks.size == existingChunks.size

      val chapterFile = chapter.audioFilePath?.let(::File)
      val chapterWavInfo = chapterFile?.let { WavAudioCodec.readWavInfo(it) }
      val healthyChapterWav = chapterWavInfo != null && chapterWavInfo.durationMs > 0L
      val isReady =
        chapter.audioStatus == AudioGenerationStatus.READY.name && healthyChapterWav && voiceMatches

      val contiguousReady =
        if (voiceMatches) {
          matchingChunks.takeWhile {
            it.status == ChapterChunkStatus.COMPLETED.name &&
              !it.wavFilePath.isNullOrBlank() &&
              (WavAudioCodec.readWavInfo(File(it.wavFilePath))?.durationMs ?: 0L) > 0L
          }
        } else {
          emptyList()
        }

      if (chapter.audioStatus == AudioGenerationStatus.READY.name && !isReady) {
        val validCount =
          if (voiceMatches) {
            matchingChunks.count {
              it.status == ChapterChunkStatus.COMPLETED.name &&
                !it.wavFilePath.isNullOrBlank() &&
                (WavAudioCodec.readWavInfo(File(it.wavFilePath))?.durationMs ?: 0L) > 0L
            }
          } else {
            0
          }
        dao.updateChapterAudioState(
          chapterId = chapter.id,
          audioStatus = AudioGenerationStatus.NOT_GENERATED.name,
          audioFilePath = null,
          audioDurationMs = 0L,
          audioSizeBytes = 0L,
          generatedChunks = validCount,
          totalChunks = if (voiceMatches) existingChunks.size else 0,
          generationError = null,
        )
      }

      val totalChars =
        if (voiceMatches && matchingChunks.isNotEmpty()) {
          ProgressiveChapterProgressCalculator.calculateTotalChars(matchingChunks)
        } else {
          chapter.characterCount.coerceAtLeast(chapter.contentText.length)
        }
      val synthesizedChars =
        if (isReady) {
          totalChars
        } else if (voiceMatches) {
          ProgressiveChapterProgressCalculator.calculateSynthesizedChars(matchingChunks)
        } else {
          0
        }
      val bufferedMs = contiguousReady.sumOf { it.durationMs.coerceAtLeast(0L) }
      val totalChunks =
        if (voiceMatches) chapter.totalChunks.coerceAtLeast(matchingChunks.size) else 0
      val snap = progressivePlaybackSnapshot
      val playedMs =
        if (snap.bookId == bookId && snap.chapterIndex == chapterIndex) snap.playedPositionMs
        else book.lastPlayedPositionMs
      val aheadMs =
        if (isReady) ((chapterWavInfo?.durationMs ?: chapter.audioDurationMs).coerceAtLeast(bufferedMs) - playedMs).coerceAtLeast(0L)
        else (bufferedMs - playedMs).coerceAtLeast(0L)
      val remaining = (totalChunks - contiguousReady.size).coerceAtLeast(0)
      val decision =
        AdaptiveBufferPolicy.evaluate(
          BufferEvaluationInput(
            deviceProfile = profile,
            measuredRtf = progressiveRtfEstimator.currentRtf(),
            hasRtfMeasurement = progressiveRtfEstimator.hasMeasurement(),
            playbackSpeed = snap.playbackSpeed,
            chunksCompleted = contiguousReady.size,
            totalChunksInChapter = totalChunks,
            bufferedDurationMs = bufferedMs,
            aheadDurationMs = aheadMs,
            remainingChunks = remaining,
            isPlaying = snap.bookId == bookId && snap.chapterIndex == chapterIndex && snap.isPlaying,
            isPausedByUser = task?.parsedStatus() == GenerationTaskStatus.PAUSED_BY_USER,
            isSessionActive = progressiveUiActive,
          )
        )
      val phase =
        when {
          isReady -> ProgressiveChapterPhase.COMPLETED
          chapter.audioStatus == AudioGenerationStatus.ERROR.name -> ProgressiveChapterPhase.RECOVERABLE_ERROR
          snap.bookId == bookId && snap.chapterIndex == chapterIndex && snap.isPlaying ->
            ProgressiveChapterPhase.PLAYING_LOOKAHEAD
          decision.canStartPlayback -> ProgressiveChapterPhase.READY_TO_PLAY
          contiguousReady.isNotEmpty() -> ProgressiveChapterPhase.PAUSED
          else -> ProgressiveChapterPhase.IDLE
        }
      _progressiveChapterState.value =
        ProgressiveChapterState(
          bookId = bookId,
          bookTitle = book.title,
          chapterIndex = chapterIndex,
          chapterTitle = chapter.title,
          chapterNumber = (pos + 1).coerceAtLeast(1),
          totalChapters = chapters.size.coerceAtLeast(1),
          phase = phase,
          chunksCompleted = contiguousReady.size,
          totalChunks = totalChunks,
          initialReadyTargetMs = decision.initialReadyTargetMs,
          aheadLookaheadTargetMs = decision.aheadLookaheadTargetMs,
          bufferedDurationMs =
            if (isReady) (chapterWavInfo?.durationMs ?: chapter.audioDurationMs).coerceAtLeast(bufferedMs)
            else bufferedMs,
          playedDurationMs = playedMs,
          aheadDurationMs = aheadMs,
          initialReadinessFraction = if (isReady) 1f else decision.initialReadinessFraction,
          measuredRtf = decision.measuredRtf,
          effectiveRtf = decision.effectiveRtf,
          playbackSpeed = snap.playbackSpeed,
          deviceProfile = profile,
          canStartPlayback = isReady || decision.canStartPlayback,
          isSynthesizing = false,
          isCompleteChapterReady = isReady,
          statusMessage =
            when (phase) {
              ProgressiveChapterPhase.COMPLETED -> "Audio preparado"
              ProgressiveChapterPhase.READY_TO_PLAY -> "Audio preparado"
              ProgressiveChapterPhase.RECOVERABLE_ERROR -> chapter.generationError ?: "Error recuperable"
              ProgressiveChapterPhase.PAUSED -> "Generación pausada"
              else -> "Listo para preparar"
            },
          errorMessage = if (phase == ProgressiveChapterPhase.RECOVERABLE_ERROR) chapter.generationError else null,
          totalChapterChars = totalChars,
          synthesizedChapterChars = synthesizedChars,
        )
    }
  }

  internal fun stopProgressiveGenerationForBook(bookId: String?, reasonPauseOnly: Boolean) {
    val toCancel =
      synchronized(progressiveLock) {
        val currentKey = progressiveTargetKey
        if (bookId == null || currentKey?.first == bookId) {
          if (reasonPauseOnly) {
            bookId?.let { pausedBookIds.add(it) }
            progressivePausedByUser = true
          } else {
            bookId?.let { cancelledBookIds.add(it) }
          }
          progressiveUiActive = false
          progressiveAwaitingPlaybackKey = null
          progressiveSignalChannel.trySend(Unit)
          progressiveJob?.also {
            progressiveJob = null
            progressiveTargetKey = null
          }
        } else {
          null
        }
      }
    toCancel?.cancel()
    if (bookId == null || _progressiveChapterState.value?.bookId == bookId) {
      _progressiveChapterState.value = null
    }
  }

  private fun enqueuePersistenceOp(block: suspend () -> Unit) {
    taskPersistenceChannel.trySend(TaskPersistenceOp.Run(block))
  }

  internal fun resolveCancellationTaskStatus(bookId: String): GenerationTaskStatus? =
    when {
      cancelledBookIds.contains(bookId) -> GenerationTaskStatus.CANCELLED
      pausedBookIds.contains(bookId) -> GenerationTaskStatus.PAUSED_BY_USER
      else -> null
    }

  private suspend fun awaitPersistenceJobs() {
    val ack = CompletableDeferred<Unit>()
    if (taskPersistenceChannel.trySend(TaskPersistenceOp.Flush(ack)).isSuccess) {
      withContext(Dispatchers.Default) { ack.await() }
    }
  }

  internal suspend fun awaitPersistenceForTesting() {
    awaitPersistenceJobs()
  }

  internal suspend fun awaitAndCloseForTesting() {
    stopProgressiveGenerationForBook(bookId = null, reasonPauseOnly = false)
    while (true) {
      val genChildren = generationParentJob.children.toList()
      if (genChildren.isEmpty()) break
      genChildren.forEach { it.cancel() }
      genChildren.forEach { it.join() }
    }
    awaitPersistenceJobs()
    taskPersistenceChannel.close()
    while (true) {
      val persistenceChildren = taskPersistenceParentJob.children.toList()
      if (persistenceChildren.isEmpty()) break
      persistenceChildren.forEach { it.join() }
    }
    generationParentJob.cancel()
    taskPersistenceParentJob.cancel()
    release()
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
    stopProgressiveGenerationForBook(bookId = bookId, reasonPauseOnly = false)
    withContext(Dispatchers.IO) {
      storageManager.deleteBookAudioFiles(bookId)
      dao.clearBookGeneratedAudio(bookId)
      if (playbackPrefs.getString(KEY_LAST_PLAYED_BOOK_ID, null) == bookId) {
        playbackPrefs.edit().remove(KEY_LAST_PLAYED_BOOK_ID).apply()
      }
    }
  }

  suspend fun deleteBook(bookId: String) {
    stopProgressiveGenerationForBook(bookId = bookId, reasonPauseOnly = false)
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
