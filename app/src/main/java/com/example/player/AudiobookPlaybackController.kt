package com.example.player

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.data.repository.AudiobookRepository
import com.example.domain.model.AudioGenerationStatus
import com.example.domain.model.Book
import com.example.domain.model.Chapter
import com.example.domain.model.ChapterChunk
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlaybackUiState(
  val isConnected: Boolean = false,
  val activeBookId: String? = null,
  val activeBookTitle: String = "",
  val activeAuthor: String = "",
  val activeCoverPath: String? = null,
  val currentChapterIndex: Int? = null,
  val currentChapterTitle: String = "",
  val playlistChapters: List<Chapter> = emptyList(),
  val isPlaying: Boolean = false,
  val positionMs: Long = 0L,
  val durationMs: Long = 0L,
  val isProgressiveActive: Boolean = false,
  val bufferedDurationMs: Long = 0L,
  val aheadBufferedDurationMs: Long = 0L,
  val isWaitingForBuffer: Boolean = false,
  val playbackSpeed: Float = 1.0f,
  val lastPlayStartLatencyMs: Long = 0L,
  val errorMessage: String? = null,
) {
  val hasActiveAudio: Boolean
    get() = activeBookId != null && playlistChapters.isNotEmpty()
}

class AudiobookPlaybackController(
  private val context: Context,
  private val repository: AudiobookRepository,
  private val injectedPlayer: Player? = null,
) {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var controllerFuture: ListenableFuture<MediaController>? = null
  private var activePlayer: Player? = injectedPlayer
  private var tickerJob: Job? = null
  private var progressiveObserverJob: Job? = null
  private var progressivePlayJob: Job? = null
  private var pendingAction: ((Player) -> Unit)? = null
  private var lastSavedAtMs = 0L
  private var hydratingBookId: String? = null
  private var restoreAttempted = false
  private var progressiveChunks: List<ChapterChunk> = emptyList()
  private var lastQueuedChunkIndex: Int = -1
  private var playStartRequestedNs: Long = 0L
  private val persistenceQueue = Channel<PersistenceRequest>(Channel.UNLIMITED)

  private sealed interface PersistenceRequest {
    data class Save(val bookId: String, val chapterIndex: Int, val positionMs: Long) :
      PersistenceRequest

    data class Flush(val ack: kotlinx.coroutines.CompletableDeferred<Unit>) : PersistenceRequest
  }

  private val _uiState = MutableStateFlow(PlaybackUiState(isConnected = injectedPlayer != null))
  val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

  private val playerListener =
    object : Player.Listener {
      override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying && playStartRequestedNs > 0L) {
          val latency = ((System.nanoTime() - playStartRequestedNs) / 1_000_000L).coerceAtLeast(0L)
          playStartRequestedNs = 0L
          _uiState.update { it.copy(lastPlayStartLatencyMs = latency) }
        }
        syncFromPlayer()
        if (isPlaying) {
          startPositionTicker()
        } else {
          stopPositionTicker()
          persistCurrentPositionNow()
        }
      }

      override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        syncFromPlayer()
        persistCurrentPositionNow()
      }

      override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_READY && playStartRequestedNs > 0L) {
          val latency = ((System.nanoTime() - playStartRequestedNs) / 1_000_000L).coerceAtLeast(0L)
          playStartRequestedNs = 0L
          _uiState.update { it.copy(lastPlayStartLatencyMs = latency) }
        }
        if (playbackState == Player.STATE_ENDED && _uiState.value.isProgressiveActive) {
          val curChap = _uiState.value.playlistChapters.firstOrNull()
          val totalChunks = curChap?.totalChunks ?: 0
          if (totalChunks > 0 && progressiveChunks.size < totalChunks) {
            _uiState.update { it.copy(isWaitingForBuffer = true) }
          }
        }
        syncFromPlayer()
      }

      override fun onPlayerError(error: PlaybackException) {
        _uiState.update {
          it.copy(
            isPlaying = false,
            errorMessage =
              error.localizedMessage ?: "Error al reproducir el archivo de audio del capítulo.",
          )
        }
      }
    }

  init {
    scope.launch(Dispatchers.IO) {
      for (request in persistenceQueue) {
        when (request) {
          is PersistenceRequest.Save ->
            runCatching {
              repository.updatePlaybackPosition(
                request.bookId,
                request.chapterIndex,
                request.positionMs,
              )
            }
          is PersistenceRequest.Flush -> request.ack.complete(Unit)
        }
      }
    }
    injectedPlayer?.addListener(playerListener)
    if (injectedPlayer != null && injectedPlayer.mediaItemCount > 0) {
      syncFromPlayer()
      if (injectedPlayer.isPlaying) {
        startPositionTicker()
      }
    }
  }

  fun hasActiveMediaInPlayer(): Boolean = (activePlayer?.mediaItemCount ?: 0) > 0

  fun ensureConnected() {
    if (activePlayer != null) {
      syncFromPlayer()
      return
    }
    if (controllerFuture != null) return
    val appContext = context.applicationContext
    val token =
      SessionToken(appContext, ComponentName(appContext, AudiobookPlaybackService::class.java))
    val future = MediaController.Builder(appContext, token).buildAsync()
    controllerFuture = future
    future.addListener(
      {
        runCatching { future.get() }
          .onSuccess { controller ->
            activePlayer = controller
            controller.addListener(playerListener)
            _uiState.update { it.copy(isConnected = true, errorMessage = null) }
            syncFromPlayer()
            if (controller.isPlaying) {
              startPositionTicker()
            }
            val action = pendingAction
            pendingAction = null
            if (action != null) {
              action.invoke(controller)
            } else if (controller.mediaItemCount == 0 && !restoreAttempted) {
              restoreLastPlaybackFromRepository()
            }
          }
          .onFailure { err ->
            controllerFuture = null
            _uiState.update {
              it.copy(
                isConnected = false,
                errorMessage =
                  err.localizedMessage ?: "No se pudo conectar con el servicio de audio.",
              )
            }
          }
      },
      ContextCompat.getMainExecutor(appContext),
    )
  }

  fun restoreLastPlaybackIfIdle() {
    ensureConnected()
    val player = activePlayer
    if (player != null && player.mediaItemCount == 0 && !restoreAttempted) {
      restoreLastPlaybackFromRepository()
    }
  }

  fun playBook(
    book: Book,
    chapters: List<Chapter>,
    startChapterIndex: Int? = null,
    startPositionMs: Long? = null,
  ) {
    val playableChapters = AudiobookMediaHelper.buildPlayableChapters(chapters)
    val requestedIndex = startChapterIndex ?: book.lastPlayedChapterIndex
    val requestedChapter = chapters.firstOrNull { it.chapterIndex == requestedIndex }
    val requestedIsNotReady =
      requestedChapter != null &&
        (requestedChapter.audioStatus != AudioGenerationStatus.READY ||
          requestedChapter.audioFilePath.isNullOrBlank())

    if (playableChapters.isEmpty() || requestedIsNotReady) {
      if (requestedChapter != null && requestedChapter.generatedChunks > 0) {
        playProgressiveChapter(book.id, requestedChapter.chapterIndex, startPositionMs)
        return
      }
      if (playableChapters.isEmpty()) {
        val fallbackProgressive = chapters.firstOrNull { it.generatedChunks > 0 }
        if (fallbackProgressive != null) {
          playProgressiveChapter(book.id, fallbackProgressive.chapterIndex, startPositionMs)
          return
        }
        _uiState.update {
          it.copy(errorMessage = "Todavía no hay capítulos de audio generados y verificados.")
        }
        return
      }
    }

    stopProgressiveObserver()
    persistCurrentPositionNow()

    val targetChapterIndex =
      startChapterIndex ?: book.lastPlayedChapterIndex ?: playableChapters.first().chapterIndex
    val startListIndex =
      playableChapters.indexOfFirst { it.chapterIndex == targetChapterIndex }.let { idx ->
        if (idx >= 0) idx else 0
      }
    val initialSeekMs =
      startPositionMs
        ?: if (book.lastPlayedChapterIndex == playableChapters[startListIndex].chapterIndex) {
          book.lastPlayedPositionMs.coerceAtLeast(0L)
        } else {
          0L
        }

    val mediaItems = AudiobookMediaHelper.buildMediaItems(book, playableChapters)
    playStartRequestedNs = System.nanoTime()

    _uiState.update {
      it.copy(
        activeBookId = book.id,
        activeBookTitle = book.title,
        activeAuthor = book.author,
        activeCoverPath = book.coverImagePath,
        playlistChapters = playableChapters,
        currentChapterIndex = playableChapters[startListIndex].chapterIndex,
        currentChapterTitle = playableChapters[startListIndex].title,
        positionMs = initialSeekMs,
        durationMs = playableChapters[startListIndex].audioDurationMs,
        isProgressiveActive = false,
        bufferedDurationMs = playableChapters[startListIndex].audioDurationMs,
        aheadBufferedDurationMs =
          (playableChapters[startListIndex].audioDurationMs - initialSeekMs).coerceAtLeast(0L),
        isWaitingForBuffer = false,
        errorMessage = null,
      )
    }

    val execute: (Player) -> Unit = { player ->
      player.setMediaItems(mediaItems, startListIndex, initialSeekMs)
      if (player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
        player.setPlaybackSpeed(_uiState.value.playbackSpeed)
      }
      player.prepare()
      player.play()
      syncFromPlayer()
    }

    val current = activePlayer
    if (current != null) {
      execute(current)
    } else {
      pendingAction = execute
      ensureConnected()
    }
  }

  fun playProgressiveChapter(
    bookId: String,
    chapterIndex: Int,
    startPositionMs: Long? = null,
  ) {
    progressivePlayJob?.cancel()
    stopProgressiveObserver()
    persistCurrentPositionNow()
    playStartRequestedNs = System.nanoTime()

    progressivePlayJob =
      scope.launch {
        val loaded = repository.getBookWithChaptersOnce(bookId)
        val chapterWithChunks = repository.getChapterWithChunksOnce(bookId, chapterIndex)
        if (loaded == null || chapterWithChunks == null) {
          _uiState.update {
            it.copy(errorMessage = "No se encontró el capítulo solicitado.")
          }
          return@launch
        }

        val book = loaded.book
        val chapter = chapterWithChunks.first
        val contiguousChunks =
          AudiobookMediaHelper.extractContiguousValidChunks(chapterWithChunks.second)

        if (contiguousChunks.isEmpty()) {
          // Si ya está listo como capítulo completo, usar playBook normal.
          if (chapter.audioStatus == AudioGenerationStatus.READY && !chapter.audioFilePath.isNullOrBlank()) {
            playBook(book, loaded.chapters, startChapterIndex = chapterIndex, startPositionMs = startPositionMs)
            return@launch
          }
          _uiState.update {
            it.copy(errorMessage = "Aún no hay audio preparado para iniciar este capítulo.")
          }
          return@launch
        }

        progressiveChunks = contiguousChunks
        lastQueuedChunkIndex = contiguousChunks.last().chunkIndex
        val bufferedMs = contiguousChunks.sumOf { it.durationMs.coerceAtLeast(0L) }
        val targetChapterPosMs =
          (startPositionMs
            ?: if (book.lastPlayedChapterIndex == chapterIndex) book.lastPlayedPositionMs else 0L)
            .coerceIn(0L, bufferedMs.coerceAtLeast(0L))

        val (startChunkListIdx, offsetInChunkMs) =
          AudiobookMediaHelper.resolveChunkPositionOffset(contiguousChunks, targetChapterPosMs)
        val initialMediaItems =
          AudiobookMediaHelper.buildProgressiveMediaItems(book, chapter, contiguousChunks)

        _uiState.update {
          it.copy(
            activeBookId = book.id,
            activeBookTitle = book.title,
            activeAuthor = book.author,
            activeCoverPath = book.coverImagePath,
            playlistChapters = listOf(chapter),
            currentChapterIndex = chapter.chapterIndex,
            currentChapterTitle = chapter.title,
            positionMs = targetChapterPosMs,
            durationMs = bufferedMs.coerceAtLeast(chapter.audioDurationMs),
            isProgressiveActive = true,
            bufferedDurationMs = bufferedMs,
            aheadBufferedDurationMs = (bufferedMs - targetChapterPosMs).coerceAtLeast(0L),
            isWaitingForBuffer = false,
            errorMessage = null,
          )
        }

        val execute: (Player) -> Unit = { player ->
          player.setMediaItems(initialMediaItems, startChunkListIdx, offsetInChunkMs)
          if (player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
            player.setPlaybackSpeed(_uiState.value.playbackSpeed)
          }
          player.prepare()
          player.play()
          syncFromPlayer()
          observeLiveProgressiveChunks(book, chapter)
        }

        val current = activePlayer
        if (current != null) {
          execute(current)
        } else {
          pendingAction = execute
          ensureConnected()
        }
      }
  }

  private fun observeLiveProgressiveChunks(book: Book, chapter: Chapter) {
    progressiveObserverJob?.cancel()
    progressiveObserverJob =
      scope.launch {
        repository.observeChunksForChapter(chapter.id).collect { latestChunks ->
          val player = activePlayer ?: return@collect
          val state = _uiState.value
          if (!state.isProgressiveActive ||
            state.activeBookId != book.id ||
            state.currentChapterIndex != chapter.chapterIndex
          ) {
            return@collect
          }

          val validContiguous = AudiobookMediaHelper.extractContiguousValidChunks(latestChunks)
          val newlyReady = validContiguous.filter { it.chunkIndex > lastQueuedChunkIndex }
          if (newlyReady.isNotEmpty()) {
            val previousCount = player.mediaItemCount
            val newMediaItems =
              AudiobookMediaHelper.buildProgressiveMediaItems(book, chapter, newlyReady)
            // Añadir al final en tiempo real sin reiniciar ni interrumpir la reproducción activa.
            player.addMediaItems(newMediaItems)
            progressiveChunks = validContiguous
            lastQueuedChunkIndex = validContiguous.last().chunkIndex

            val newBufferedMs = validContiguous.sumOf { it.durationMs.coerceAtLeast(0L) }
            val wasWaiting = _uiState.value.isWaitingForBuffer
            _uiState.update {
              it.copy(
                bufferedDurationMs = newBufferedMs,
                durationMs = newBufferedMs.coerceAtLeast(it.durationMs),
                aheadBufferedDurationMs = (newBufferedMs - it.positionMs).coerceAtLeast(0L),
                isWaitingForBuffer = false,
              )
            }
            if (wasWaiting || player.playbackState == Player.STATE_ENDED) {
              if (previousCount < player.mediaItemCount) {
                player.seekTo(previousCount, 0L)
                player.prepare()
                player.play()
              }
            }
          }
        }
      }
  }

  fun togglePlayPause() {
    val player = activePlayer
    if (player == null) {
      ensureConnected()
      return
    }
    if (player.mediaItemCount == 0 && _uiState.value.hasActiveAudio) {
      val state = _uiState.value
      val bookId = state.activeBookId ?: return
      if (state.isProgressiveActive && state.currentChapterIndex != null) {
        playProgressiveChapter(bookId, state.currentChapterIndex, state.positionMs)
        return
      }
      scope.launch {
        val loaded = repository.getBookWithChaptersOnce(bookId) ?: return@launch
        playBook(
          book = loaded.book,
          chapters = loaded.chapters,
          startChapterIndex = state.currentChapterIndex,
          startPositionMs = state.positionMs,
        )
      }
      return
    }
    if (player.isPlaying) {
      player.pause()
    } else {
      playStartRequestedNs = System.nanoTime()
      if (player.playbackState == Player.STATE_IDLE) {
        player.prepare()
      }
      player.play()
    }
    syncFromPlayer()
  }

  fun setPlaybackSpeed(speed: Float) {
    val clamped = speed.coerceIn(0.5f, 2.5f)
    activePlayer?.let { player ->
      if (player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
        player.setPlaybackSpeed(clamped)
      }
    }
    _uiState.update { it.copy(playbackSpeed = clamped) }
    syncFromPlayer()
  }

  fun seekTo(positionMs: Long) {
    val player = activePlayer ?: return
    val state = _uiState.value
    if (state.isProgressiveActive && progressiveChunks.isNotEmpty()) {
      val maxBuffered = progressiveChunks.sumOf { it.durationMs.coerceAtLeast(0L) }
      val clamped = positionMs.coerceIn(0L, maxBuffered.coerceAtLeast(0L))
      val (chunkIdx, localOffset) =
        AudiobookMediaHelper.resolveChunkPositionOffset(progressiveChunks, clamped)
      player.seekTo(chunkIdx.coerceIn(0, (player.mediaItemCount - 1).coerceAtLeast(0)), localOffset)
    } else {
      val maxDuration =
        player.duration.takeIf { it > 0L } ?: state.durationMs.coerceAtLeast(0L)
      val clamped = positionMs.coerceIn(0L, maxDuration)
      player.seekTo(clamped)
    }
    syncFromPlayer()
    persistCurrentPositionNow()
  }

  fun seekForward(deltaMs: Long = 15_000L) {
    val player = activePlayer ?: return
    val state = _uiState.value
    if (state.isProgressiveActive && progressiveChunks.isNotEmpty()) {
      val maxDuration = progressiveChunks.sumOf { it.durationMs.coerceAtLeast(0L) }
      val currentCumulative =
        AudiobookMediaHelper.cumulativeChapterPositionMs(
          progressiveChunks,
          player.currentMediaItemIndex,
          player.currentPosition,
        )
      seekTo((currentCumulative + deltaMs).coerceAtMost(maxDuration))
      return
    }
    val maxDuration =
      player.duration.takeIf { it > 0L } ?: state.durationMs.coerceAtLeast(0L)
    val target = (player.currentPosition + deltaMs).coerceAtMost(maxDuration)
    player.seekTo(target)
    syncFromPlayer()
    persistCurrentPositionNow()
  }

  fun seekBack(deltaMs: Long = 15_000L) {
    val player = activePlayer ?: return
    if (_uiState.value.isProgressiveActive && progressiveChunks.isNotEmpty()) {
      val currentCumulative =
        AudiobookMediaHelper.cumulativeChapterPositionMs(
          progressiveChunks,
          player.currentMediaItemIndex,
          player.currentPosition,
        )
      seekTo((currentCumulative - deltaMs).coerceAtLeast(0L))
      return
    }
    val target = (player.currentPosition - deltaMs).coerceAtLeast(0L)
    player.seekTo(target)
    syncFromPlayer()
    persistCurrentPositionNow()
  }

  fun nextChapter() {
    val player = activePlayer ?: return
    if (_uiState.value.isProgressiveActive) return
    if (player.hasNextMediaItem()) {
      player.seekToNextMediaItem()
      player.play()
      syncFromPlayer()
      persistCurrentPositionNow()
    }
  }

  fun previousChapter() {
    val player = activePlayer ?: return
    if (_uiState.value.isProgressiveActive) {
      seekTo(0L)
      return
    }
    if (player.currentPosition > 5_000L || !player.hasPreviousMediaItem()) {
      player.seekTo(0L)
    } else {
      player.seekToPreviousMediaItem()
    }
    syncFromPlayer()
    persistCurrentPositionNow()
  }

  fun jumpToChapter(chapterIndex: Int) {
    val player = activePlayer ?: return
    val list = _uiState.value.playlistChapters
    val idx = list.indexOfFirst { it.chapterIndex == chapterIndex }
    if (idx >= 0) {
      if (_uiState.value.isProgressiveActive) {
        val currentChap = _uiState.value.currentChapterIndex
        if (currentChap == chapterIndex) {
          seekTo(0L)
          return
        }
      }
      if (player.mediaItemCount == 0 && _uiState.value.activeBookId != null) {
        val bookId = _uiState.value.activeBookId!!
        scope.launch {
          val loaded = repository.getBookWithChaptersOnce(bookId) ?: return@launch
          playBook(loaded.book, loaded.chapters, startChapterIndex = chapterIndex, startPositionMs = 0L)
        }
        return
      }
      player.seekTo(idx, 0L)
      player.play()
      syncFromPlayer()
      persistCurrentPositionNow()
    }
  }

  fun stopIfBookCleared(bookId: String) {
    if (_uiState.value.activeBookId == bookId) {
      stopProgressiveObserver()
      activePlayer?.stop()
      activePlayer?.clearMediaItems()
      stopPositionTicker()
      _uiState.value = PlaybackUiState(isConnected = activePlayer != null)
    }
  }

  fun clearError() {
    _uiState.update { it.copy(errorMessage = null) }
  }

  suspend fun awaitPersistenceForTesting() {
    val ack = kotlinx.coroutines.CompletableDeferred<Unit>()
    persistenceQueue.trySend(PersistenceRequest.Flush(ack))
    withContext(Dispatchers.Default) { ack.await() }
  }

  fun releaseForTesting() {
    stopPositionTicker()
    stopProgressiveObserver()
    progressivePlayJob?.cancel()
    activePlayer?.removeListener(playerListener)
    controllerFuture?.let { MediaController.releaseFuture(it) }
    controllerFuture = null
    activePlayer = null
    persistenceQueue.close()
    scope.cancel()
    if (INSTANCE === this) {
      INSTANCE = null
    }
  }

  private fun stopProgressiveObserver() {
    progressiveObserverJob?.cancel()
    progressiveObserverJob = null
    progressiveChunks = emptyList()
    lastQueuedChunkIndex = -1
  }

  private fun syncFromPlayer() {
    val player = activePlayer ?: return
    val currentItem = player.currentMediaItem
    val extras = currentItem?.mediaMetadata?.extras
    val playerBookId = extras?.getString(AudiobookMediaHelper.EXTRA_BOOK_ID)?.takeIf { it.isNotBlank() }
    val itemChapterIndex =
      extras?.getInt(AudiobookMediaHelper.EXTRA_CHAPTER_INDEX, -1)?.takeIf { it >= 0 }
    val itemIsProgressive =
      extras?.getBoolean(AudiobookMediaHelper.EXTRA_IS_PROGRESSIVE_CHUNK, false) ?: false

    val state = _uiState.value

    if (
      playerBookId != null &&
        !itemIsProgressive &&
        (state.activeBookId != playerBookId || state.playlistChapters.isEmpty()) &&
        hydratingBookId != playerBookId
    ) {
      hydratingBookId = playerBookId
      scope.launch {
        val loaded = repository.getBookWithChaptersOnce(playerBookId)
        hydratingBookId = null
        if (loaded != null) {
          val playable = AudiobookMediaHelper.buildPlayableChapters(loaded.chapters)
          val currentChapIdx =
            activePlayer?.currentMediaItem?.mediaMetadata?.extras?.getInt(
              AudiobookMediaHelper.EXTRA_CHAPTER_INDEX,
              -1,
            )?.takeIf { it >= 0 } ?: itemChapterIndex
          val matched =
            playable.firstOrNull { it.chapterIndex == currentChapIdx }
              ?: playable.getOrNull(activePlayer?.currentMediaItemIndex ?: 0)
          _uiState.update {
            it.copy(
              activeBookId = loaded.book.id,
              activeBookTitle = loaded.book.title,
              activeAuthor = loaded.book.author,
              activeCoverPath = loaded.book.coverImagePath,
              playlistChapters = playable,
              currentChapterIndex = matched?.chapterIndex ?: it.currentChapterIndex,
              currentChapterTitle = matched?.title ?: it.currentChapterTitle,
              durationMs =
                (activePlayer?.duration?.takeIf { d -> d > 0L } ?: matched?.audioDurationMs ?: it.durationMs),
            )
          }
        }
      }
    }

    if (player.mediaItemCount == 0 && state.hasActiveAudio) {
      _uiState.update { it.copy(isPlaying = false) }
      return
    }

    if ((state.isProgressiveActive || itemIsProgressive) && progressiveChunks.isNotEmpty()) {
      val cumulativePos =
        AudiobookMediaHelper.cumulativeChapterPositionMs(
          chunks = progressiveChunks,
          currentItemIndex = player.currentMediaItemIndex,
          currentLocalPosMs = player.currentPosition.coerceAtLeast(0L),
        )
      val bufferedMs = progressiveChunks.sumOf { it.durationMs.coerceAtLeast(0L) }
      val aheadMs = (bufferedMs - cumulativePos).coerceAtLeast(0L)
      val resolvedChapIdx = itemChapterIndex ?: state.currentChapterIndex
      val resolvedBookId = playerBookId ?: state.activeBookId

      _uiState.update {
        it.copy(
          activeBookId = resolvedBookId,
          currentChapterIndex = resolvedChapIdx,
          currentChapterTitle =
            state.playlistChapters.firstOrNull()?.title
              ?: currentItem?.mediaMetadata?.title?.toString()
              ?: it.currentChapterTitle,
          isPlaying = player.isPlaying,
          positionMs = cumulativePos,
          durationMs = bufferedMs.coerceAtLeast(it.durationMs),
          isProgressiveActive = true,
          bufferedDurationMs = bufferedMs,
          aheadBufferedDurationMs = aheadMs,
        )
      }

      if (resolvedBookId != null && resolvedChapIdx != null) {
        repository.notifyProgressivePlaybackState(
          bookId = resolvedBookId,
          chapterIndex = resolvedChapIdx,
          chapterPositionMs = cumulativePos,
          isPlaying = player.isPlaying,
          playbackSpeed = state.playbackSpeed,
        )
      }
      return
    }

    val matchedChapter =
      state.playlistChapters.firstOrNull { it.chapterIndex == itemChapterIndex }
        ?: state.playlistChapters.getOrNull(player.currentMediaItemIndex)

    val resolvedDuration =
      player.duration.takeIf { it > 0L } ?: matchedChapter?.audioDurationMs ?: state.durationMs
    val resolvedPos = player.currentPosition.coerceAtLeast(0L)

    _uiState.update {
      it.copy(
        activeBookId = playerBookId ?: it.activeBookId,
        currentChapterIndex = itemChapterIndex ?: matchedChapter?.chapterIndex ?: it.currentChapterIndex,
        currentChapterTitle =
          matchedChapter?.title
            ?: currentItem?.mediaMetadata?.title?.toString()
            ?: it.currentChapterTitle,
        isPlaying = player.isPlaying,
        positionMs = resolvedPos,
        durationMs = resolvedDuration,
        bufferedDurationMs = resolvedDuration,
        aheadBufferedDurationMs = (resolvedDuration - resolvedPos).coerceAtLeast(0L),
      )
    }
  }

  private fun restoreLastPlaybackFromRepository() {
    if (restoreAttempted) return
    restoreAttempted = true
    scope.launch {
      val currentPlayer = activePlayer
      if (currentPlayer != null && currentPlayer.mediaItemCount > 0) return@launch
      if (_uiState.value.hasActiveAudio) return@launch

      val last = repository.getLastPlayedBookWithChaptersOnce() ?: return@launch
      val playable = AudiobookMediaHelper.buildPlayableChapters(last.chapters)
      val targetIndex =
        last.book.lastPlayedChapterIndex ?: playable.firstOrNull()?.chapterIndex ?: return@launch
      val readyMatched = playable.firstOrNull { it.chapterIndex == targetIndex }

      if (readyMatched != null) {
        val savedPos =
          if (last.book.lastPlayedChapterIndex == readyMatched.chapterIndex) {
            last.book.lastPlayedPositionMs.coerceAtLeast(0L)
          } else {
            0L
          }
        _uiState.update {
          if (it.hasActiveAudio || (activePlayer?.mediaItemCount ?: 0) > 0) {
            it
          } else {
            it.copy(
              activeBookId = last.book.id,
              activeBookTitle = last.book.title,
              activeAuthor = last.book.author,
              activeCoverPath = last.book.coverImagePath,
              playlistChapters = playable,
              currentChapterIndex = readyMatched.chapterIndex,
              currentChapterTitle = readyMatched.title,
              isPlaying = false,
              positionMs = savedPos,
              durationMs = readyMatched.audioDurationMs,
              isProgressiveActive = false,
              bufferedDurationMs = readyMatched.audioDurationMs,
              aheadBufferedDurationMs = (readyMatched.audioDurationMs - savedPos).coerceAtLeast(0L),
            )
          }
        }
        return@launch
      }

      // Intentar restaurar capítulo en progreso con fragmentos WAV válidos guardados en disco.
      val targetChapter =
        last.chapters.firstOrNull { it.chapterIndex == targetIndex }
          ?: playable.firstOrNull()
          ?: return@launch
      val savedChunks = repository.getCompletedValidChunksOnce(targetChapter.id)
      if (savedChunks.isEmpty()) return@launch

      val bufferedMs = savedChunks.sumOf { it.durationMs.coerceAtLeast(0L) }
      val savedPos = last.book.lastPlayedPositionMs.coerceIn(0L, bufferedMs.coerceAtLeast(0L))
      progressiveChunks = savedChunks
      _uiState.update {
        if (it.hasActiveAudio || (activePlayer?.mediaItemCount ?: 0) > 0) {
          it
        } else {
          it.copy(
            activeBookId = last.book.id,
            activeBookTitle = last.book.title,
            activeAuthor = last.book.author,
            activeCoverPath = last.book.coverImagePath,
            playlistChapters = listOf(targetChapter),
            currentChapterIndex = targetChapter.chapterIndex,
            currentChapterTitle = targetChapter.title,
            isPlaying = false,
            positionMs = savedPos,
            durationMs = bufferedMs.coerceAtLeast(targetChapter.audioDurationMs),
            isProgressiveActive = true,
            bufferedDurationMs = bufferedMs,
            aheadBufferedDurationMs = (bufferedMs - savedPos).coerceAtLeast(0L),
          )
        }
      }
    }
  }

  private fun startPositionTicker() {
    if (tickerJob?.isActive == true) return
    tickerJob =
      scope.launch {
        while (isActive) {
          syncFromPlayer()
          val now = System.currentTimeMillis()
          if (now - lastSavedAtMs >= 3_000L) {
            persistCurrentPositionNow()
            lastSavedAtMs = now
          }
          delay(500L)
        }
      }
  }

  private fun stopPositionTicker() {
    tickerJob?.cancel()
    tickerJob = null
  }

  private fun persistCurrentPositionNow() {
    val current = _uiState.value
    val bookId = current.activeBookId ?: return
    val chapterIndex = current.currentChapterIndex ?: return
    val pos =
      if (current.isProgressiveActive && progressiveChunks.isNotEmpty() && activePlayer != null) {
        AudiobookMediaHelper.cumulativeChapterPositionMs(
          chunks = progressiveChunks,
          currentItemIndex = activePlayer?.currentMediaItemIndex ?: 0,
          currentLocalPosMs = activePlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L,
        )
      } else {
        activePlayer?.currentPosition?.coerceAtLeast(0L) ?: current.positionMs
      }
    persistenceQueue.trySend(PersistenceRequest.Save(bookId, chapterIndex, pos))
  }

  companion object {
    @Volatile private var INSTANCE: AudiobookPlaybackController? = null

    fun getInstance(context: Context): AudiobookPlaybackController {
      return INSTANCE
        ?: synchronized(this) {
          val appContext = context.applicationContext
          val repo = AudiobookRepository.getInstance(appContext)
          AudiobookPlaybackController(appContext, repo).also { INSTANCE = it }
        }
    }
  }
}
