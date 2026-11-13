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
import com.example.domain.model.Book
import com.example.domain.model.Chapter
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
  private var pendingAction: ((Player) -> Unit)? = null
  private var lastSavedAtMs = 0L
  private var hydratingBookId: String? = null
  private var restoreAttempted = false

  private val _uiState = MutableStateFlow(PlaybackUiState(isConnected = injectedPlayer != null))
  val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

  private val playerListener =
    object : Player.Listener {
      override fun onIsPlayingChanged(isPlaying: Boolean) {
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

    if (playableChapters.isEmpty()) {
      _uiState.update {
        it.copy(errorMessage = "Todavía no hay capítulos de audio generados y verificados.")
      }
      return
    }

    // Persist any existing playback progress before switching books/chapters to avoid losing state
    // and ensure only one active audiobook plays at a time.
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
        errorMessage = null,
      )
    }

    val execute: (Player) -> Unit = { player ->
      player.setMediaItems(mediaItems, startListIndex, initialSeekMs)
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

  fun togglePlayPause() {
    val player = activePlayer
    if (player == null) {
      ensureConnected()
      return
    }
    if (player.mediaItemCount == 0 && _uiState.value.hasActiveAudio) {
      val state = _uiState.value
      val bookId = state.activeBookId ?: return
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
      if (player.playbackState == Player.STATE_IDLE) {
        player.prepare()
      }
      player.play()
    }
    syncFromPlayer()
  }

  fun seekTo(positionMs: Long) {
    val player = activePlayer ?: return
    val maxDuration =
      player.duration.takeIf { it > 0L } ?: _uiState.value.durationMs.coerceAtLeast(0L)
    val clamped = positionMs.coerceIn(0L, maxDuration)
    player.seekTo(clamped)
    syncFromPlayer()
    persistCurrentPositionNow()
  }

  fun seekForward(deltaMs: Long = 15_000L) {
    val player = activePlayer ?: return
    val maxDuration =
      player.duration.takeIf { it > 0L } ?: _uiState.value.durationMs.coerceAtLeast(0L)
    val target = (player.currentPosition + deltaMs).coerceAtMost(maxDuration)
    player.seekTo(target)
    syncFromPlayer()
    persistCurrentPositionNow()
  }

  fun seekBack(deltaMs: Long = 15_000L) {
    val player = activePlayer ?: return
    val target = (player.currentPosition - deltaMs).coerceAtLeast(0L)
    player.seekTo(target)
    syncFromPlayer()
    persistCurrentPositionNow()
  }

  fun nextChapter() {
    val player = activePlayer ?: return
    if (player.hasNextMediaItem()) {
      player.seekToNextMediaItem()
      player.play()
      syncFromPlayer()
      persistCurrentPositionNow()
    }
  }

  fun previousChapter() {
    val player = activePlayer ?: return
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
      activePlayer?.stop()
      activePlayer?.clearMediaItems()
      stopPositionTicker()
      _uiState.value = PlaybackUiState(isConnected = activePlayer != null)
    }
  }

  fun clearError() {
    _uiState.update { it.copy(errorMessage = null) }
  }

  fun releaseForTesting() {
    stopPositionTicker()
    activePlayer?.removeListener(playerListener)
    controllerFuture?.let { MediaController.releaseFuture(it) }
    controllerFuture = null
    activePlayer = null
    if (INSTANCE === this) {
      INSTANCE = null
    }
  }

  private fun syncFromPlayer() {
    val player = activePlayer ?: return
    val currentItem = player.currentMediaItem
    val extras = currentItem?.mediaMetadata?.extras
    val playerBookId = extras?.getString(AudiobookMediaHelper.EXTRA_BOOK_ID)?.takeIf { it.isNotBlank() }
    val itemChapterIndex =
      extras?.getInt(AudiobookMediaHelper.EXTRA_CHAPTER_INDEX, -1)?.takeIf { it >= 0 }

    val state = _uiState.value

    // If reconnecting to an already running AudiobookPlaybackService after Activity/process restart,
    // hydrate book and chapter metadata from Room so MiniPlayer & PlayerScreen stay synchronized.
    if (
      playerBookId != null &&
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
      // Preserve restored preview state when player is still idle with no queued items.
      _uiState.update { it.copy(isPlaying = false) }
      return
    }

    val matchedChapter =
      state.playlistChapters.firstOrNull { it.chapterIndex == itemChapterIndex }
        ?: state.playlistChapters.getOrNull(player.currentMediaItemIndex)

    val resolvedDuration =
      player.duration.takeIf { it > 0L } ?: matchedChapter?.audioDurationMs ?: state.durationMs

    _uiState.update {
      it.copy(
        activeBookId = playerBookId ?: it.activeBookId,
        currentChapterIndex = itemChapterIndex ?: matchedChapter?.chapterIndex ?: it.currentChapterIndex,
        currentChapterTitle =
          matchedChapter?.title
            ?: currentItem?.mediaMetadata?.title?.toString()
            ?: it.currentChapterTitle,
        isPlaying = player.isPlaying,
        positionMs = player.currentPosition.coerceAtLeast(0L),
        durationMs = resolvedDuration,
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
      if (playable.isEmpty()) return@launch
      val targetIndex = last.book.lastPlayedChapterIndex ?: playable.first().chapterIndex
      val matched = playable.firstOrNull { it.chapterIndex == targetIndex } ?: playable.first()
      val savedPos =
        if (last.book.lastPlayedChapterIndex == matched.chapterIndex) {
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
            currentChapterIndex = matched.chapterIndex,
            currentChapterTitle = matched.title,
            isPlaying = false,
            positionMs = savedPos,
            durationMs = matched.audioDurationMs,
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
    val pos = activePlayer?.currentPosition?.coerceAtLeast(0L) ?: current.positionMs
    scope.launch { repository.updatePlaybackPosition(bookId, chapterIndex, pos) }
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
