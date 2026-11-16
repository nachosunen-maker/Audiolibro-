package com.example.player

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.data.repository.AudiobookRepository
import com.example.domain.audio.DeviceCapabilityInspector
import com.example.domain.audio.WavAudioCodec
import com.example.domain.model.AudioGenerationStatus
import com.example.domain.model.Book
import com.example.domain.model.Chapter
import com.example.domain.model.ChapterChunk
import com.example.domain.model.ProgressiveChapterPhase
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

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
  val activeWindowSize: Int = 0,
  val releasedChunksCount: Int = 0,
  val errorMessage: String? = null,
) {
  val hasActiveAudio: Boolean
    get() = activeBookId != null && (playlistChapters.isNotEmpty() || currentChapterIndex != null)
}

class AudiobookPlaybackController(
  private val context: Context,
  private val repository: AudiobookRepository,
  private val injectedPlayer: Player? = null,
) {

  private val ownsPlayerDirectly: Boolean = injectedPlayer != null
  private val controllerJob = SupervisorJob()
  private val scope = CoroutineScope(controllerJob + Dispatchers.Main.immediate)
  private var controllerFuture: ListenableFuture<MediaController>? = null
  private var activePlayer: Player? = injectedPlayer
  private var tickerJob: Job? = null
  private var progressiveObserverJob: Job? = null
  private var progressivePlayJob: Job? = null
  private var autoAdvanceJob: Job? = null
  private var seekJob: Job? = null
  private var persistenceConsumerJob: Job? = null
  private var pendingAction: ((Player) -> Unit)? = null
  private var lastSavedAtMs = 0L
  private var hydratingBookId: String? = null
  private var hydratingProgressiveKey: Pair<String, Int>? = null
  private var restoreAttempted = false
  private var progressiveChapterDurationMs: Long = 0L
  private var progressiveTotalChunksCount: Int = 0
  private var highestQueuedChunkIndex: Int = -1
  private var activeWindowAheadMs: Long = AudiobookMediaHelper.DEFAULT_AHEAD_WINDOW_DURATION_MS
  private val releasedChunkIndices = LinkedHashSet<Int>()
  private val windowRefillTrigger = MutableStateFlow(0L)
  private var isAutoAdvancing: Boolean = false
  private var playStartRequestedNs: Long = 0L
  private val activeIoQueries = AtomicInteger(0)
  private val persistenceQueue = Channel<PersistenceRequest>(Channel.UNLIMITED)

  private sealed interface PersistenceRequest {
    data class Save(val bookId: String, val chapterIndex: Int, val positionMs: Long) :
      PersistenceRequest

    data class Flush(val ack: CompletableDeferred<Unit>) : PersistenceRequest
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
          windowRefillTrigger.value += 1L
          startPositionTicker()
        } else {
          stopPositionTicker()
          persistCurrentPositionNow()
        }
      }

      override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        val player = activePlayer ?: return
        if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
          val maxInPlayer = AudiobookMediaHelper.maxChunkIndexInPlayer(player)
          if (maxInPlayer >= 0) {
            highestQueuedChunkIndex = maxInPlayer
          } else if (player.mediaItemCount == 0) {
            highestQueuedChunkIndex = -1
          }
          windowRefillTrigger.value += 1L
          syncFromPlayer()
        }
      }

      override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val player = activePlayer
        if (player != null && ownsPlayerDirectly) {
          prunePlayedMediaItemsIfNeeded(player, reason)
          val maxInPlayer = AudiobookMediaHelper.maxChunkIndexInPlayer(player)
          if (maxInPlayer >= 0) {
            highestQueuedChunkIndex = maxInPlayer
          }
        }
        windowRefillTrigger.value += 1L
        syncFromPlayer()
        persistCurrentPositionNow()
      }

      override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (!playWhenReady) {
          _uiState.update { it.copy(isWaitingForBuffer = false) }
        }
        syncFromPlayer()
      }

      override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_READY && playStartRequestedNs > 0L) {
          val latency = ((System.nanoTime() - playStartRequestedNs) / 1_000_000L).coerceAtLeast(0L)
          playStartRequestedNs = 0L
          _uiState.update { it.copy(lastPlayStartLatencyMs = latency) }
        }
        if (playbackState == Player.STATE_READY) {
          _uiState.update { it.copy(isWaitingForBuffer = false) }
        }
        if (playbackState == Player.STATE_ENDED) {
          handlePlayerEnded()
        }
        syncFromPlayer()
      }

      override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
      ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
          windowRefillTrigger.value += 1L
          syncFromPlayer()
        }
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
    activeWindowAheadMs =
      if (DeviceCapabilityInspector.inspect(context.applicationContext).isConstrainedDevice) {
        AudiobookMediaHelper.CONSTRAINED_AHEAD_WINDOW_DURATION_MS
      } else {
        AudiobookMediaHelper.DEFAULT_AHEAD_WINDOW_DURATION_MS
      }
    persistenceConsumerJob =
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
    val orderedChapters = chapters.sortedBy { it.chapterIndex }
    val playableChapters = AudiobookMediaHelper.buildPlayableChapters(orderedChapters)
    val requestedIndex = startChapterIndex ?: book.lastPlayedChapterIndex
    val requestedChapter = orderedChapters.firstOrNull { it.chapterIndex == requestedIndex }
    val requestedIsNotReady =
      requestedChapter != null &&
        (requestedChapter.audioStatus != AudioGenerationStatus.READY ||
          requestedChapter.audioFilePath.isNullOrBlank())

    if (playableChapters.isEmpty() || requestedIsNotReady) {
      if (requestedChapter != null && requestedChapter.generatedChunks > 0) {
        playProgressiveChapter(book.id, requestedChapter.chapterIndex, startPositionMs)
        return
      }
      if (requestedChapter != null && startChapterIndex != null) {
        openOrPlayChapterOnDemand(book.id, requestedChapter.chapterIndex, startPositionMs ?: 0L)
        return
      }
      if (playableChapters.isEmpty()) {
        val fallbackProgressive = orderedChapters.firstOrNull { it.generatedChunks > 0 }
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

    autoAdvanceJob?.cancel()
    isAutoAdvancing = false
    progressivePlayJob?.cancel()
    seekJob?.cancel()
    stopProgressiveObserver()
    persistCurrentPositionNow()

    val targetChapterIndex =
      startChapterIndex ?: book.lastPlayedChapterIndex ?: playableChapters.first().chapterIndex
    val contiguousReady = extractContiguousReadyRun(orderedChapters, targetChapterIndex)
      .ifEmpty { playableChapters }
    val startListIndex =
      contiguousReady.indexOfFirst { it.chapterIndex == targetChapterIndex }.let { idx ->
        if (idx >= 0) idx else 0
      }
    val initialSeekMs =
      startPositionMs
        ?: if (book.lastPlayedChapterIndex == contiguousReady[startListIndex].chapterIndex) {
          book.lastPlayedPositionMs.coerceAtLeast(0L)
        } else {
          0L
        }

    val mediaItems = AudiobookMediaHelper.buildMediaItems(book, contiguousReady)
    playStartRequestedNs = System.nanoTime()

    _uiState.update {
      it.copy(
        activeBookId = book.id,
        activeBookTitle = book.title,
        activeAuthor = book.author,
        activeCoverPath = book.coverImagePath,
        playlistChapters = contiguousReady,
        currentChapterIndex = contiguousReady[startListIndex].chapterIndex,
        currentChapterTitle = contiguousReady[startListIndex].title,
        positionMs = initialSeekMs,
        durationMs = contiguousReady[startListIndex].audioDurationMs,
        isProgressiveActive = false,
        bufferedDurationMs = contiguousReady[startListIndex].audioDurationMs,
        aheadBufferedDurationMs =
          (contiguousReady[startListIndex].audioDurationMs - initialSeekMs).coerceAtLeast(0L),
        isWaitingForBuffer = false,
        activeWindowSize = mediaItems.size,
        releasedChunksCount = 0,
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
    autoAdvanceJob?.cancel()
    isAutoAdvancing = false
    progressivePlayJob?.cancel()
    seekJob?.cancel()
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
          AudiobookMediaHelper.extractContiguousValidChunks(
            chapterWithChunks.second.filter { it.voiceName == book.selectedVoice.name }
          )

        if (contiguousChunks.isEmpty()) {
          if (chapter.audioStatus == AudioGenerationStatus.READY && !chapter.audioFilePath.isNullOrBlank()) {
            playBook(book, loaded.chapters, startChapterIndex = chapterIndex, startPositionMs = startPositionMs)
            return@launch
          }
          _uiState.update {
            it.copy(errorMessage = "Aún no hay audio preparado para iniciar este capítulo.")
          }
          return@launch
        }

        val bufferedMs = contiguousChunks.sumOf { it.durationMs.coerceAtLeast(0L) }
        val targetChapterPosMs =
          (startPositionMs
            ?: if (book.lastPlayedChapterIndex == chapterIndex) book.lastPlayedPositionMs else 0L)
            .coerceIn(0L, bufferedMs.coerceAtLeast(0L))

        val offsetsByChunk = AudiobookMediaHelper.computeChunkStartOffsetsMs(contiguousChunks)
        val (targetListIdx, offsetInChunkMs) =
          AudiobookMediaHelper.resolveChunkPositionOffset(contiguousChunks, targetChapterPosMs)
        val targetChunkIndex = contiguousChunks[targetListIdx].chunkIndex
        val windowChunks =
          AudiobookMediaHelper.selectSlidingWindowChunks(
            contiguousChunks = contiguousChunks,
            startChunkIndex = targetChunkIndex,
            aheadWindowDurationMs = activeWindowAheadMs,
          )
        val initialMediaItems =
          AudiobookMediaHelper.buildProgressiveMediaItems(
            book = book,
            chapter = chapter,
            chunks = windowChunks,
            startOffsetsByChunkIndex = offsetsByChunk,
          )
        val startWindowIndex =
          windowChunks.indexOfFirst { it.chunkIndex == targetChunkIndex }.coerceAtLeast(0)

        progressiveChapterDurationMs = bufferedMs.coerceAtLeast(chapter.audioDurationMs)
        progressiveTotalChunksCount = chapter.totalChunks.coerceAtLeast(contiguousChunks.size)
        highestQueuedChunkIndex = windowChunks.last().chunkIndex
        releasedChunkIndices.clear()

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
            durationMs = progressiveChapterDurationMs,
            isProgressiveActive = true,
            bufferedDurationMs = bufferedMs,
            aheadBufferedDurationMs = (bufferedMs - targetChapterPosMs).coerceAtLeast(0L),
            isWaitingForBuffer = false,
            activeWindowSize = initialMediaItems.size,
            releasedChunksCount = 0,
            errorMessage = null,
          )
        }

        val execute: (Player) -> Unit = { player ->
          player.setMediaItems(initialMediaItems, startWindowIndex, offsetInChunkMs)
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
    val bookId = book.id
    val chapterId = chapter.id
    val chapterIndex = chapter.chapterIndex
    val voiceName = book.selectedVoice.name

    progressiveObserverJob?.cancel()
    progressiveObserverJob =
      scope.launch {
        var cachedChapter = chapter
        var lastObservedChunkCount = -1
        combine(
          repository.observeChunksForChapter(chapterId),
          windowRefillTrigger,
        ) { latestChunks, _ -> latestChunks }
          .collect { latestChunks ->
            if (!coroutineContext.isActive) return@collect
            val player = activePlayer ?: return@collect
            val state = _uiState.value
            if (!state.isProgressiveActive ||
              state.activeBookId != bookId ||
              state.currentChapterIndex != chapterIndex
            ) {
              return@collect
            }

            val validContiguous =
              AudiobookMediaHelper.extractContiguousValidChunks(
                latestChunks.filter { it.voiceName == voiceName }
              )
            if (validContiguous.isEmpty()) return@collect

            val refreshedChapter =
              if (latestChunks.size != lastObservedChunkCount) {
                lastObservedChunkCount = latestChunks.size
                withDbIo {
                  repository.getChapterWithChunksOnce(bookId, chapterIndex)?.first
                }?.also { cachedChapter = it } ?: cachedChapter
              } else {
                cachedChapter
              }
            progressiveTotalChunksCount =
              maxOf(progressiveTotalChunksCount, refreshedChapter.totalChunks, latestChunks.size)
            val newBufferedMs = validContiguous.sumOf { it.durationMs.coerceAtLeast(0L) }
            progressiveChapterDurationMs =
              maxOf(progressiveChapterDurationMs, newBufferedMs, refreshedChapter.audioDurationMs)

            if (ownsPlayerDirectly) {
              val offsetsByChunk = AudiobookMediaHelper.computeChunkStartOffsetsMs(validContiguous)
              val maxQueuedInPlayer = AudiobookMediaHelper.maxChunkIndexInPlayer(player)
              val tailChunkIdx =
                if (player.mediaItemCount > 0 && maxQueuedInPlayer >= 0) maxQueuedInPlayer
                else highestQueuedChunkIndex

              val candidates = validContiguous.filter { it.chunkIndex > tailChunkIdx }
              if (candidates.isNotEmpty()) {
                var currentAheadMs = AudiobookMediaHelper.computeWindowAheadDurationMs(player)
                val chunksToAppend = ArrayList<ChapterChunk>(4)
                for (chunk in candidates) {
                  val projectedCount = player.mediaItemCount + chunksToAppend.size
                  if (projectedCount >= 2 && currentAheadMs >= activeWindowAheadMs) {
                    break
                  }
                  chunksToAppend += chunk
                  currentAheadMs += chunk.durationMs.coerceAtLeast(0L)
                }

                if (chunksToAppend.isNotEmpty()) {
                  val previousCount = player.mediaItemCount
                  val newMediaItems =
                    AudiobookMediaHelper.buildProgressiveMediaItems(
                      book = book,
                      chapter = refreshedChapter,
                      chunks = chunksToAppend,
                      startOffsetsByChunkIndex = offsetsByChunk,
                    )
                  player.addMediaItems(newMediaItems)
                  highestQueuedChunkIndex = chunksToAppend.last().chunkIndex

                  val wasWaiting = _uiState.value.isWaitingForBuffer
                  if (wasWaiting || (player.playbackState == Player.STATE_ENDED && player.playWhenReady)) {
                    if (previousCount < player.mediaItemCount) {
                      player.seekTo(previousCount, 0L)
                      player.prepare()
                      player.play()
                    }
                  }
                }
              }
            }

            val currentPos = AudiobookMediaHelper.resolveProgressiveChapterPositionMs(player)
            _uiState.update {
              it.copy(
                playlistChapters = listOf(refreshedChapter),
                bufferedDurationMs = newBufferedMs,
                durationMs = maxOf(newBufferedMs, progressiveChapterDurationMs, it.durationMs),
                aheadBufferedDurationMs = (newBufferedMs - currentPos).coerceAtLeast(0L),
                isWaitingForBuffer =
                  it.isWaitingForBuffer &&
                    (player.playbackState == Player.STATE_ENDED &&
                      AudiobookMediaHelper.maxChunkIndexInPlayer(player) >= highestQueuedChunkIndex),
                activeWindowSize = player.mediaItemCount,
                releasedChunksCount = computeReleasedChunksCount(player),
              )
            }
          }
      }
  }

  private fun prunePlayedMediaItemsIfNeeded(player: Player, reason: Int) {
    val currentExtras = player.currentMediaItem?.mediaMetadata?.extras
    val isProgressive =
      currentExtras?.getBoolean(AudiobookMediaHelper.EXTRA_IS_PROGRESSIVE_CHUNK, false) == true
    if (!isProgressive) return
    if (!player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) return

    val currentIdx = player.currentMediaItemIndex
    val pruneCount =
      if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
        currentIdx
      } else {
        currentIdx - 1
      }
    if (pruneCount > 0 && pruneCount < player.mediaItemCount) {
      for (i in 0 until pruneCount) {
        val prunedChunkIdx =
          player.getMediaItemAt(i).mediaMetadata.extras?.getInt(
            AudiobookMediaHelper.EXTRA_CHUNK_INDEX,
            -1,
          ) ?: -1
        if (prunedChunkIdx >= 0) {
          releasedChunkIndices.add(prunedChunkIdx)
        }
      }
      runCatching { player.removeMediaItems(0, pruneCount) }
    }
  }

  private fun computeReleasedChunksCount(player: Player): Int {
    if (ownsPlayerDirectly) return releasedChunkIndices.size
    if (player.mediaItemCount == 0) return releasedChunkIndices.size
    val firstChunkInWindow =
      player.getMediaItemAt(0).mediaMetadata.extras?.getInt(
        AudiobookMediaHelper.EXTRA_CHUNK_INDEX,
        -1,
      ) ?: -1
    return maxOf(releasedChunkIndices.size, firstChunkInWindow.coerceAtLeast(0))
  }

  private fun handlePlayerEnded() {
    val player = activePlayer ?: return
    val state = _uiState.value
    val extras = player.currentMediaItem?.mediaMetadata?.extras
    val bookId =
      extras?.getString(AudiobookMediaHelper.EXTRA_BOOK_ID)?.takeIf { it.isNotBlank() }
        ?: state.activeBookId
        ?: return
    val chapterIndex =
      extras?.getInt(AudiobookMediaHelper.EXTRA_CHAPTER_INDEX, -1)?.takeIf { it >= 0 }
        ?: state.currentChapterIndex
        ?: return
    val isProgressive =
      extras?.getBoolean(AudiobookMediaHelper.EXTRA_IS_PROGRESSIVE_CHUNK, false)
        ?: state.isProgressiveActive

    if (isProgressive) {
      val maxQueued =
        maxOf(highestQueuedChunkIndex, AudiobookMediaHelper.maxChunkIndexInPlayer(player))
      val totalChunks =
        progressiveTotalChunksCount.takeIf { it > 0 }
          ?: state.playlistChapters.firstOrNull()?.totalChunks
          ?: 0
      val hasRemainingChunksInChapter = totalChunks <= 0 || maxQueued < totalChunks - 1
      if (hasRemainingChunksInChapter) {
        if (player.playWhenReady) {
          _uiState.update { it.copy(isWaitingForBuffer = true) }
          windowRefillTrigger.value += 1L
        }
        return
      }
    } else if (player.hasNextMediaItem()) {
      return
    }

    if (ownsPlayerDirectly && player.playWhenReady) {
      autoAdvanceToAdjacentChapter(bookId, chapterIndex, delta = 1)
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
    if (state.isProgressiveActive) {
      val maxBuffered =
        maxOf(state.bufferedDurationMs, progressiveChapterDurationMs, state.positionMs)
      val clamped = positionMs.coerceIn(0L, maxBuffered.coerceAtLeast(0L))
      val inWindowTarget = AudiobookMediaHelper.findWindowSeekTarget(player, clamped)
      if (inWindowTarget != null) {
        player.seekTo(inWindowTarget.first, inWindowTarget.second)
        syncFromPlayer()
        persistCurrentPositionNow()
        return
      }

      val bookId = state.activeBookId ?: return
      val chapIdx = state.currentChapterIndex ?: return
      val shouldResumePlayback = state.isPlaying || player.playWhenReady
      seekJob?.cancel()
      seekJob =
        scope.launch {
          val loaded = repository.getBookWithChaptersOnce(bookId) ?: return@launch
          val chapterWithChunks = repository.getChapterWithChunksOnce(bookId, chapIdx) ?: return@launch
          val valid =
            AudiobookMediaHelper.extractContiguousValidChunks(
              chapterWithChunks.second.filter { it.voiceName == loaded.book.selectedVoice.name }
            )
          if (valid.isEmpty()) return@launch

          val validTotalMs = valid.sumOf { it.durationMs.coerceAtLeast(0L) }
          val clampedToDisk = clamped.coerceIn(0L, validTotalMs.coerceAtLeast(0L))
          val offsets = AudiobookMediaHelper.computeChunkStartOffsetsMs(valid)
          val (targetListIdx, localOffset) =
            AudiobookMediaHelper.resolveChunkPositionOffset(valid, clampedToDisk)
          val targetChunkIdx = valid[targetListIdx].chunkIndex
          val windowChunks =
            AudiobookMediaHelper.selectSlidingWindowChunks(
              contiguousChunks = valid,
              startChunkIndex = targetChunkIdx,
              aheadWindowDurationMs = activeWindowAheadMs,
            )
          releasedChunkIndices.clear()
          highestQueuedChunkIndex = windowChunks.last().chunkIndex
          val mediaItems =
            AudiobookMediaHelper.buildProgressiveMediaItems(
              book = loaded.book,
              chapter = chapterWithChunks.first,
              chunks = windowChunks,
              startOffsetsByChunkIndex = offsets,
            )
          player.setMediaItems(mediaItems, 0, localOffset)
          player.prepare()
          if (shouldResumePlayback) {
            player.play()
          }
          syncFromPlayer()
          persistCurrentPositionNow()
        }
      return
    }

    val maxDuration =
      player.duration.takeIf { it > 0L } ?: state.durationMs.coerceAtLeast(0L)
    val clamped = positionMs.coerceIn(0L, maxDuration)
    player.seekTo(clamped)
    syncFromPlayer()
    persistCurrentPositionNow()
  }

  fun seekForward(deltaMs: Long = 15_000L) {
    val player = activePlayer ?: return
    val state = _uiState.value
    if (state.isProgressiveActive) {
      val maxDuration = maxOf(state.bufferedDurationMs, progressiveChapterDurationMs, state.durationMs)
      val currentCumulative = AudiobookMediaHelper.resolveProgressiveChapterPositionMs(player)
      seekTo((currentCumulative + deltaMs).coerceAtMost(maxDuration.coerceAtLeast(currentCumulative)))
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
    if (_uiState.value.isProgressiveActive) {
      val currentCumulative = AudiobookMediaHelper.resolveProgressiveChapterPositionMs(player)
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
    val state = _uiState.value
    if (!state.isProgressiveActive && player.hasNextMediaItem()) {
      player.seekToNextMediaItem()
      player.play()
      syncFromPlayer()
      persistCurrentPositionNow()
      return
    }
    val bookId = state.activeBookId ?: return
    val currentChapIdx = state.currentChapterIndex ?: return
    autoAdvanceToAdjacentChapter(bookId, currentChapIdx, delta = 1)
  }

  fun previousChapter() {
    val player = activePlayer ?: return
    val state = _uiState.value
    val currentPos =
      if (state.isProgressiveActive) {
        AudiobookMediaHelper.resolveProgressiveChapterPositionMs(player)
      } else {
        player.currentPosition.coerceAtLeast(0L)
      }
    if (currentPos > 5_000L) {
      seekTo(0L)
      return
    }
    if (!state.isProgressiveActive && player.hasPreviousMediaItem()) {
      player.seekToPreviousMediaItem()
      syncFromPlayer()
      persistCurrentPositionNow()
      return
    }
    val bookId = state.activeBookId ?: return
    val currentChapIdx = state.currentChapterIndex ?: return
    scope.launch {
      val prevChapter = repository.getAdjacentChapter(bookId, currentChapIdx, delta = -1)
      if (prevChapter != null) {
        openOrPlayChapterOnDemand(bookId, prevChapter.chapterIndex, startPositionMs = 0L)
      } else {
        seekTo(0L)
      }
    }
  }

  fun jumpToChapter(chapterIndex: Int) {
    val player = activePlayer ?: return
    val state = _uiState.value
    val list = state.playlistChapters
    val idx = list.indexOfFirst { it.chapterIndex == chapterIndex }
    if (!state.isProgressiveActive && idx >= 0 && player.mediaItemCount > idx) {
      player.seekTo(idx, 0L)
      player.play()
      syncFromPlayer()
      persistCurrentPositionNow()
      return
    }
    if (state.currentChapterIndex == chapterIndex && player.mediaItemCount > 0) {
      seekTo(0L)
      if (!player.isPlaying) {
        player.play()
      }
      return
    }
    val bookId = state.activeBookId ?: return
    openOrPlayChapterOnDemand(bookId, chapterIndex, startPositionMs = 0L)
  }

  private fun autoAdvanceToAdjacentChapter(
    bookId: String,
    currentChapterIndex: Int,
    delta: Int,
  ) {
    if (isAutoAdvancing) return
    autoAdvanceJob?.cancel()
    autoAdvanceJob =
      scope.launch {
        isAutoAdvancing = true
        try {
          val targetChapter =
            repository.getAdjacentChapter(bookId, currentChapterIndex, delta = delta)
              ?: return@launch
          openOrPlayChapterOnDemandInternal(bookId, targetChapter.chapterIndex, startPositionMs = 0L)
        } finally {
          isAutoAdvancing = false
        }
      }
  }

  fun openOrPlayChapterOnDemand(
    bookId: String,
    chapterIndex: Int,
    startPositionMs: Long = 0L,
  ) {
    autoAdvanceJob?.cancel()
    autoAdvanceJob =
      scope.launch {
        isAutoAdvancing = true
        try {
          openOrPlayChapterOnDemandInternal(bookId, chapterIndex, startPositionMs)
        } finally {
          isAutoAdvancing = false
        }
      }
  }

  private suspend fun openOrPlayChapterOnDemandInternal(
    bookId: String,
    chapterIndex: Int,
    startPositionMs: Long,
  ) {
    val loaded = repository.getBookWithChaptersOnce(bookId) ?: return
    val targetChapter =
      loaded.chapters.firstOrNull { it.chapterIndex == chapterIndex } ?: return
    val book = loaded.book

    stopProgressiveObserver()
    repository.updatePlaybackPosition(bookId, chapterIndex, startPositionMs.coerceAtLeast(0L))

    val isReadyWav =
      targetChapter.audioStatus == AudioGenerationStatus.READY &&
        !targetChapter.audioFilePath.isNullOrBlank() &&
        WavAudioCodec.isValidWavFile(File(targetChapter.audioFilePath))
    if (isReadyWav) {
      playBook(book, loaded.chapters, startChapterIndex = chapterIndex, startPositionMs = startPositionMs)
      return
    }

    val existingValid =
      repository.getCompletedValidChunksOnce(targetChapter.id).filter {
        it.voiceName == book.selectedVoice.name
      }
    if (existingValid.size >= 2) {
      playProgressiveChapter(bookId, chapterIndex, startPositionMs)
      repository.startOnDemandChapterLoading(bookId, chapterIndex)
      return
    }

    _uiState.update {
      it.copy(
        activeBookId = book.id,
        activeBookTitle = book.title,
        activeAuthor = book.author,
        activeCoverPath = book.coverImagePath,
        playlistChapters = listOf(targetChapter),
        currentChapterIndex = targetChapter.chapterIndex,
        currentChapterTitle = targetChapter.title,
        positionMs = startPositionMs,
        durationMs = targetChapter.audioDurationMs,
        isProgressiveActive = true,
        bufferedDurationMs = existingValid.sumOf { ch -> ch.durationMs.coerceAtLeast(0L) },
        aheadBufferedDurationMs = 0L,
        isWaitingForBuffer = true,
        activeWindowSize = 0,
        releasedChunksCount = 0,
        errorMessage = null,
      )
    }

    repository.startOnDemandChapterLoading(bookId, chapterIndex)
    var reachedReady = false
    try {
      val settled =
        repository.progressiveChapterState.first { st ->
          st != null &&
            st.bookId == bookId &&
            st.chapterIndex == chapterIndex &&
            (st.phase == ProgressiveChapterPhase.RECOVERABLE_ERROR ||
              st.phase == ProgressiveChapterPhase.PAUSED ||
              st.canStartPlayback ||
              st.isCompleteChapterReady)
        }
      if (settled != null &&
        settled.phase != ProgressiveChapterPhase.RECOVERABLE_ERROR &&
        settled.phase != ProgressiveChapterPhase.PAUSED
      ) {
        reachedReady = true
        playProgressiveChapter(bookId, chapterIndex, startPositionMs)
      } else {
        _uiState.update {
          it.copy(
            isWaitingForBuffer = false,
            errorMessage = settled?.errorMessage ?: it.errorMessage,
          )
        }
      }
    } finally {
      if (!reachedReady) {
        repository.setAwaitingPlaybackStart(bookId, chapterIndex, awaiting = false)
      }
    }
  }

  private fun extractContiguousReadyRun(
    orderedChapters: List<Chapter>,
    targetChapterIndex: Int,
  ): List<Chapter> {
    val targetPos = orderedChapters.indexOfFirst { it.chapterIndex == targetChapterIndex }
    if (targetPos < 0) return emptyList()
    var start = targetPos
    while (start > 0 && isReadyWithValidWav(orderedChapters[start - 1])) {
      start -= 1
    }
    var end = targetPos
    while (end < orderedChapters.lastIndex && isReadyWithValidWav(orderedChapters[end + 1])) {
      end += 1
    }
    val result = ArrayList<Chapter>(end - start + 1)
    for (i in start..end) {
      if (isReadyWithValidWav(orderedChapters[i])) {
        result += orderedChapters[i]
      }
    }
    return result
  }

  private fun isReadyWithValidWav(chapter: Chapter): Boolean =
    chapter.audioStatus == AudioGenerationStatus.READY &&
      !chapter.audioFilePath.isNullOrBlank() &&
      WavAudioCodec.isValidWavFile(File(chapter.audioFilePath))

  fun stopIfBookCleared(bookId: String) {
    if (_uiState.value.activeBookId == bookId) {
      autoAdvanceJob?.cancel()
      isAutoAdvancing = false
      progressivePlayJob?.cancel()
      seekJob?.cancel()
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
    val ack = CompletableDeferred<Unit>()
    persistenceQueue.trySend(PersistenceRequest.Flush(ack))
    withContext(Dispatchers.Default) { ack.await() }
  }

  fun releaseForTesting() {
    stopPositionTicker()
    autoAdvanceJob?.cancel()
    progressivePlayJob?.cancel()
    seekJob?.cancel()
    stopProgressiveObserver()
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

  private suspend fun <T> withDbIo(block: suspend () -> T): T {
    activeIoQueries.incrementAndGet()
    return withContext(Dispatchers.IO) {
      try {
        block()
      } finally {
        activeIoQueries.decrementAndGet()
      }
    }
  }

  suspend fun awaitAndReleaseForTesting() {
    stopPositionTicker()
    autoAdvanceJob?.cancel()
    progressivePlayJob?.cancel()
    seekJob?.cancel()
    stopProgressiveObserver()
    activePlayer?.removeListener(playerListener)
    controllerFuture?.let { MediaController.releaseFuture(it) }
    controllerFuture = null
    activePlayer = null
    persistenceQueue.close()
    controllerJob.cancel()
    withContext(Dispatchers.IO) {
      persistenceConsumerJob?.join()
      var waitedMs = 0L
      while (activeIoQueries.get() > 0 && waitedMs < 2_000L) {
        delay(5L)
        waitedMs += 5L
      }
    }
    if (INSTANCE === this) {
      INSTANCE = null
    }
  }

  private fun stopProgressiveObserver() {
    progressiveObserverJob?.cancel()
    progressiveObserverJob = null
    progressiveChapterDurationMs = 0L
    progressiveTotalChunksCount = 0
    highestQueuedChunkIndex = -1
    releasedChunkIndices.clear()
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
        (state.activeBookId != playerBookId ||
          state.isProgressiveActive ||
          state.playlistChapters.isEmpty()) &&
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
          val contiguousRun =
            currentChapIdx?.let { extractContiguousReadyRun(loaded.chapters.sortedBy { c -> c.chapterIndex }, it) }
              ?.takeIf { it.isNotEmpty() }
              ?: playable
          val matched =
            contiguousRun.firstOrNull { it.chapterIndex == currentChapIdx }
              ?: contiguousRun.getOrNull(activePlayer?.currentMediaItemIndex ?: 0)
          _uiState.update {
            it.copy(
              activeBookId = loaded.book.id,
              activeBookTitle = loaded.book.title,
              activeAuthor = loaded.book.author,
              activeCoverPath = loaded.book.coverImagePath,
              playlistChapters = contiguousRun,
              isProgressiveActive = false,
              currentChapterIndex = matched?.chapterIndex ?: it.currentChapterIndex,
              currentChapterTitle = matched?.title ?: it.currentChapterTitle,
              durationMs =
                (activePlayer?.duration?.takeIf { d -> d > 0L } ?: matched?.audioDurationMs ?: it.durationMs),
              activeWindowSize = activePlayer?.mediaItemCount ?: 0,
              releasedChunksCount = 0,
            )
          }
        }
      }
    }

    if (playerBookId != null &&
      itemIsProgressive &&
      itemChapterIndex != null &&
      (progressiveObserverJob?.isActive != true ||
        state.activeBookId != playerBookId ||
        state.currentChapterIndex != itemChapterIndex) &&
      hydratingProgressiveKey != (playerBookId to itemChapterIndex)
    ) {
      val key = playerBookId to itemChapterIndex
      hydratingProgressiveKey = key
      scope.launch {
        val loaded = repository.getBookWithChaptersOnce(playerBookId)
        val chWithChunks = repository.getChapterWithChunksOnce(playerBookId, itemChapterIndex)
        hydratingProgressiveKey = null
        if (loaded != null && chWithChunks != null) {
          val ch = chWithChunks.first
          val validChunks =
            AudiobookMediaHelper.extractContiguousValidChunks(
              chWithChunks.second.filter { it.voiceName == loaded.book.selectedVoice.name }
            )
          val bufferedMs = validChunks.sumOf { it.durationMs.coerceAtLeast(0L) }
          progressiveChapterDurationMs = maxOf(bufferedMs, ch.audioDurationMs)
          progressiveTotalChunksCount = maxOf(ch.totalChunks, chWithChunks.second.size)
          _uiState.update {
            it.copy(
              activeBookId = loaded.book.id,
              activeBookTitle = loaded.book.title,
              activeAuthor = loaded.book.author,
              activeCoverPath = loaded.book.coverImagePath,
              playlistChapters = listOf(ch),
              currentChapterIndex = ch.chapterIndex,
              currentChapterTitle = ch.title,
              isProgressiveActive = true,
              bufferedDurationMs = bufferedMs,
              durationMs = maxOf(bufferedMs, ch.audioDurationMs),
            )
          }
          if (progressiveObserverJob?.isActive != true ||
            state.activeBookId != playerBookId ||
            state.currentChapterIndex != itemChapterIndex
          ) {
            observeLiveProgressiveChunks(loaded.book, ch)
          }
        }
      }
    }

    if (player.mediaItemCount == 0 && state.hasActiveAudio) {
      _uiState.update { it.copy(isPlaying = false, activeWindowSize = 0) }
      return
    }

    if (state.isProgressiveActive || itemIsProgressive) {
      val cumulativePos = AudiobookMediaHelper.resolveProgressiveChapterPositionMs(player)
      val bufferedMs = state.bufferedDurationMs.coerceAtLeast(cumulativePos)
      val aheadMs = (bufferedMs - cumulativePos).coerceAtLeast(0L)
      val resolvedChapIdx = itemChapterIndex ?: state.currentChapterIndex
      val resolvedBookId = playerBookId ?: state.activeBookId

      _uiState.update {
        it.copy(
          activeBookId = resolvedBookId,
          currentChapterIndex = resolvedChapIdx,
          currentChapterTitle =
            state.playlistChapters.firstOrNull { ch -> ch.chapterIndex == resolvedChapIdx }?.title
              ?: currentItem?.mediaMetadata?.title?.toString()
              ?: it.currentChapterTitle,
          isPlaying = player.isPlaying,
          positionMs = cumulativePos,
          durationMs = maxOf(bufferedMs, progressiveChapterDurationMs, it.durationMs),
          isProgressiveActive = true,
          bufferedDurationMs = bufferedMs,
          aheadBufferedDurationMs = aheadMs,
          activeWindowSize = player.mediaItemCount,
          releasedChunksCount = computeReleasedChunksCount(player),
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
        isProgressiveActive = false,
        positionMs = resolvedPos,
        durationMs = resolvedDuration,
        bufferedDurationMs = resolvedDuration,
        aheadBufferedDurationMs = (resolvedDuration - resolvedPos).coerceAtLeast(0L),
        activeWindowSize = player.mediaItemCount,
        releasedChunksCount = 0,
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
        val contiguousRun =
          extractContiguousReadyRun(last.chapters.sortedBy { it.chapterIndex }, readyMatched.chapterIndex)
            .ifEmpty { playable }
        _uiState.update {
          if (it.hasActiveAudio || (activePlayer?.mediaItemCount ?: 0) > 0) {
            it
          } else {
            it.copy(
              activeBookId = last.book.id,
              activeBookTitle = last.book.title,
              activeAuthor = last.book.author,
              activeCoverPath = last.book.coverImagePath,
              playlistChapters = contiguousRun,
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

      val targetChapter =
        last.chapters.firstOrNull { it.chapterIndex == targetIndex }
          ?: playable.firstOrNull()
          ?: return@launch
      val savedChunks =
        repository.getCompletedValidChunksOnce(targetChapter.id).filter {
          it.voiceName == last.book.selectedVoice.name
        }
      if (savedChunks.isEmpty()) return@launch

      val bufferedMs = savedChunks.sumOf { it.durationMs.coerceAtLeast(0L) }
      val savedPos = last.book.lastPlayedPositionMs.coerceIn(0L, bufferedMs.coerceAtLeast(0L))
      progressiveChapterDurationMs = bufferedMs.coerceAtLeast(targetChapter.audioDurationMs)
      progressiveTotalChunksCount = targetChapter.totalChunks.coerceAtLeast(savedChunks.size)
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
            durationMs = progressiveChapterDurationMs,
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
    val player = activePlayer
    val pos =
      if (current.isProgressiveActive && player != null && player.mediaItemCount > 0) {
        AudiobookMediaHelper.resolveProgressiveChapterPositionMs(player)
      } else {
        player?.currentPosition?.coerceAtLeast(0L) ?: current.positionMs
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
