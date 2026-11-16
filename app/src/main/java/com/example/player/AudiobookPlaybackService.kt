package com.example.player

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.MainActivity
import com.example.R
import com.example.data.repository.AudiobookRepository
import com.example.domain.audio.DeviceCapabilityInspector
import com.example.domain.audio.WavAudioCodec
import com.example.domain.model.AudioGenerationStatus
import com.example.domain.model.Book
import com.example.domain.model.Chapter
import com.example.domain.model.ChapterChunk
import com.example.domain.model.ProgressiveChapterPhase
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AudiobookPlaybackService : MediaSessionService() {

  internal var repositoryOverride: AudiobookRepository? = null
  private val repository: AudiobookRepository by lazy {
    repositoryOverride ?: AudiobookRepository.getInstance(applicationContext)
  }

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private var mediaSession: MediaSession? = null
  private var tickerJob: Job? = null
  private var lastPersistenceJob: Job? = null
  private var progressiveObserverJob: Job? = null
  private var autoAdvanceJob: Job? = null
  private var activeProgressiveKey: Pair<String, Int>? = null
  private var activeProgressiveTotalChunks: Int = 0
  private var highestQueuedChunkIndex: Int = -1
  private var activeWindowAheadMs: Long = AudiobookMediaHelper.DEFAULT_AHEAD_WINDOW_DURATION_MS
  private var shouldResumeAfterBufferDepletion: Boolean = false
  private var isAutoAdvancing: Boolean = false
  private var lastDbPersistAtMs: Long = 0L
  private val windowRefillTrigger = MutableStateFlow(0L)

  private val servicePlayerListener =
    object : Player.Listener {
      override fun onIsPlayingChanged(isPlaying: Boolean) {
        val player = mediaSession?.player
        if (!isPlaying && player != null && !player.playWhenReady) {
          shouldResumeAfterBufferDepletion = false
        }
        syncProgressiveObserverFromPlayer()
        notifyProgressiveEngineFromPlayer()
        if (isPlaying) {
          windowRefillTrigger.value += 1L
          startPersistenceTicker()
        } else {
          stopPersistenceTicker()
          persistPlayerPositionSnapshot()
        }
      }

      override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (!playWhenReady) {
          shouldResumeAfterBufferDepletion = false
        }
        notifyProgressiveEngineFromPlayer()
      }

      override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        val player = mediaSession?.player ?: return
        if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
          val maxInPlayer = AudiobookMediaHelper.maxChunkIndexInPlayer(player)
          if (maxInPlayer >= 0) {
            highestQueuedChunkIndex = maxInPlayer
          } else if (player.mediaItemCount == 0) {
            highestQueuedChunkIndex = -1
          }
          windowRefillTrigger.value += 1L
        }
      }

      override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val player = mediaSession?.player
        if (player != null) {
          prunePlayedMediaItemsIfNeeded(player, reason)
          val maxInPlayer = AudiobookMediaHelper.maxChunkIndexInPlayer(player)
          if (maxInPlayer >= 0) {
            highestQueuedChunkIndex = maxInPlayer
          }
        }
        syncProgressiveObserverFromPlayer()
        windowRefillTrigger.value += 1L
        notifyProgressiveEngineFromPlayer()
        persistPlayerPositionSnapshot()
      }

      override fun onPlaybackStateChanged(playbackState: Int) {
        val player = mediaSession?.player
        if (playbackState == Player.STATE_ENDED && player != null) {
          handlePlayerEnded(player)
        }
        if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_READY) {
          syncProgressiveObserverFromPlayer()
          windowRefillTrigger.value += 1L
          notifyProgressiveEngineFromPlayer()
          persistPlayerPositionSnapshot()
        }
      }

      override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
      ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
          windowRefillTrigger.value += 1L
          notifyProgressiveEngineFromPlayer()
          persistPlayerPositionSnapshot()
        }
      }
    }

  private val sessionCallback =
    object : MediaSession.Callback {
      override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
      ): MediaSession.ConnectionResult {
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
      }

      override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
      ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        serviceScope.launch {
          try {
            val lastBook = repository.getLastPlayedBookWithChaptersOnce()
            if (lastBook == null) {
              future.setException(
                IllegalStateException("No hay ningún audiolibro reproducido recientemente.")
              )
              return@launch
            }
            val playableChapters = AudiobookMediaHelper.buildPlayableChapters(lastBook.chapters)
            val targetChapterIndex =
              lastBook.book.lastPlayedChapterIndex
                ?: playableChapters.firstOrNull()?.chapterIndex
                ?: lastBook.chapters.firstOrNull { it.generatedChunks > 0 }?.chapterIndex
            val readyMatch =
              playableChapters.firstOrNull { it.chapterIndex == targetChapterIndex }

            if (readyMatch != null) {
              val startIndex =
                playableChapters.indexOfFirst { it.chapterIndex == readyMatch.chapterIndex }.coerceAtLeast(0)
              val startPositionMs = lastBook.book.lastPlayedPositionMs.coerceAtLeast(0L)
              val mediaItems = AudiobookMediaHelper.buildMediaItems(lastBook.book, playableChapters)
              future.set(
                MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
              )
              return@launch
            }

            val progressiveTarget =
              lastBook.chapters.firstOrNull { it.chapterIndex == targetChapterIndex }
                ?: lastBook.chapters.firstOrNull { it.generatedChunks > 0 }
            if (progressiveTarget != null) {
              val savedChunks = repository.getCompletedValidChunksOnce(progressiveTarget.id)
              if (savedChunks.isNotEmpty()) {
                val targetPos = lastBook.book.lastPlayedPositionMs.coerceAtLeast(0L)
                val offsets = AudiobookMediaHelper.computeChunkStartOffsetsMs(savedChunks)
                val (chunkListIdx, offsetMs) =
                  AudiobookMediaHelper.resolveChunkPositionOffset(savedChunks, targetPos)
                val startChunkIdx = savedChunks[chunkListIdx].chunkIndex
                val windowChunks =
                  AudiobookMediaHelper.selectSlidingWindowChunks(
                    savedChunks,
                    startChunkIdx,
                    activeWindowAheadMs,
                  )
                val items =
                  AudiobookMediaHelper.buildProgressiveMediaItems(
                    book = lastBook.book,
                    chapter = progressiveTarget,
                    chunks = windowChunks,
                    startOffsetsByChunkIndex = offsets,
                  )
                val startIndexInWindow =
                  windowChunks.indexOfFirst { it.chunkIndex == startChunkIdx }.coerceAtLeast(0)
                future.set(
                  MediaSession.MediaItemsWithStartPosition(items, startIndexInWindow, offsetMs)
                )
                return@launch
              }
            }

            future.setException(
              IllegalStateException("El último audiolibro no tiene capítulos listos.")
            )
          } catch (t: Throwable) {
            future.setException(t)
          }
        }
        return future
      }
    }

  @OptIn(UnstableApi::class)
  override fun onCreate() {
    super.onCreate()

    activeWindowAheadMs =
      if (DeviceCapabilityInspector.inspect(applicationContext).isConstrainedDevice) {
        AudiobookMediaHelper.CONSTRAINED_AHEAD_WINDOW_DURATION_MS
      } else {
        AudiobookMediaHelper.DEFAULT_AHEAD_WINDOW_DURATION_MS
      }

    setMediaNotificationProvider(
      DefaultMediaNotificationProvider.Builder(this)
        .setChannelId(CHANNEL_ID_PLAYBACK)
        .setChannelName(R.string.notification_channel_playback)
        .build()
    )

    val audioAttributes =
      AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
        .build()

    val loadControl =
      DefaultLoadControl.Builder()
        .setBufferDurationsMs(
          /* minBufferMs = */ 12_000,
          /* maxBufferMs = */ 30_000,
          /* bufferForPlaybackMs = */ 500,
          /* bufferForPlaybackAfterRebufferMs = */ 1_000,
        )
        .setBackBuffer(/* backBufferDurationMs = */ 0, /* retainBackBufferFromKeyframe = */ false)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

    val player =
      ExoPlayer.Builder(this)
        .setLoadControl(loadControl)
        .setAudioAttributes(audioAttributes, true)
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .setSeekBackIncrementMs(15_000L)
        .setSeekForwardIncrementMs(15_000L)
        .build()

    player.addListener(servicePlayerListener)

    val openAppIntent =
      Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
      }
    val sessionActivityPendingIntent =
      PendingIntent.getActivity(
        this,
        0,
        openAppIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    mediaSession =
      MediaSession.Builder(this, player)
        .setId(SESSION_ID)
        .setSessionActivity(sessionActivityPendingIntent)
        .setCallback(sessionCallback)
        .build()
  }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
    return mediaSession
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    val player = mediaSession?.player
    val awaitingMoreChunks =
      (shouldResumeAfterBufferDepletion && activeProgressiveKey != null) || isAutoAdvancing
    if (
      player == null ||
        (!awaitingMoreChunks &&
          (!player.playWhenReady ||
            player.mediaItemCount == 0 ||
            player.playbackState == Player.STATE_ENDED))
    ) {
      persistPlayerPositionSnapshot()
      stopSelf()
    }
  }

  override fun onDestroy() {
    stopPersistenceTicker()
    autoAdvanceJob?.cancel()
    autoAdvanceJob = null
    progressiveObserverJob?.cancel()
    progressiveObserverJob = null
    activeProgressiveKey = null
    highestQueuedChunkIndex = -1
    persistPlayerPositionSnapshot()
    mediaSession?.run {
      player.removeListener(servicePlayerListener)
      player.release()
      release()
      mediaSession = null
    }
    serviceScope.cancel()
    super.onDestroy()
  }

  internal fun currentSessionForTesting(): MediaSession? = mediaSession

  internal fun triggerPlaybackResumptionForTesting(
    controller: MediaSession.ControllerInfo
  ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
    val session = requireNotNull(mediaSession)
    return sessionCallback.onPlaybackResumption(session, controller)
  }

  internal suspend fun awaitPersistenceForTesting() {
    lastPersistenceJob?.join()
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
      runCatching { player.removeMediaItems(0, pruneCount) }
    }
  }

  private fun handlePlayerEnded(player: Player) {
    val extras = player.currentMediaItem?.mediaMetadata?.extras ?: return
    val bookId = extras.getString(AudiobookMediaHelper.EXTRA_BOOK_ID)?.takeIf { it.isNotBlank() } ?: return
    val chapterIndex = extras.getInt(AudiobookMediaHelper.EXTRA_CHAPTER_INDEX, -1)
    if (chapterIndex < 0) return

    val isProgressive = extras.getBoolean(AudiobookMediaHelper.EXTRA_IS_PROGRESSIVE_CHUNK, false)
    val maxQueued =
      maxOf(highestQueuedChunkIndex, AudiobookMediaHelper.maxChunkIndexInPlayer(player))

    if (isProgressive) {
      val hasMoreChunksInChapter =
        activeProgressiveTotalChunks <= 0 || maxQueued < activeProgressiveTotalChunks - 1
      if (hasMoreChunksInChapter) {
        if (player.playWhenReady) {
          shouldResumeAfterBufferDepletion = true
          windowRefillTrigger.value += 1L
        }
        return
      }
    } else if (player.hasNextMediaItem()) {
      return
    }

    if (player.playWhenReady) {
      autoAdvanceToNextChapter(bookId, chapterIndex)
    }
  }

  private fun autoAdvanceToNextChapter(bookId: String, currentChapterIndex: Int) {
    if (isAutoAdvancing) return
    autoAdvanceJob?.cancel()
    autoAdvanceJob =
      serviceScope.launch {
        isAutoAdvancing = true
        var nextChapterIndexForCleanup = -1
        var startedPlayback = false
        try {
          val nextChapter =
            repository.getAdjacentChapter(bookId, currentChapterIndex, delta = 1) ?: return@launch
          val loaded = repository.getBookWithChaptersOnce(bookId) ?: return@launch
          val book = loaded.book
          nextChapterIndexForCleanup = nextChapter.chapterIndex

          repository.updatePlaybackPosition(bookId, nextChapter.chapterIndex, 0L)

          val nextReady =
            nextChapter.audioStatus == AudioGenerationStatus.READY &&
              !nextChapter.audioFilePath.isNullOrBlank() &&
              WavAudioCodec.isValidWavFile(File(nextChapter.audioFilePath))

          val player = mediaSession?.player ?: return@launch
          if (nextReady) {
            val items = AudiobookMediaHelper.buildMediaItems(book, listOf(nextChapter))
            player.setMediaItems(items, 0, 0L)
            player.prepare()
            player.play()
            startedPlayback = true
            return@launch
          }

          val existingValid =
            repository.getCompletedValidChunksOnce(nextChapter.id).filter {
              it.voiceName == book.selectedVoice.name
            }
          if (existingValid.size >= 2) {
            startProgressiveWindowOnPlayer(player, book, nextChapter, existingValid)
            startedPlayback = true
            repository.startOnDemandChapterLoading(bookId, nextChapter.chapterIndex)
            return@launch
          }

          repository.startOnDemandChapterLoading(bookId, nextChapter.chapterIndex)
          val readyState =
            repository.progressiveChapterState.first { st ->
              st != null &&
                st.bookId == bookId &&
                st.chapterIndex == nextChapter.chapterIndex &&
                (st.phase == ProgressiveChapterPhase.RECOVERABLE_ERROR ||
                  st.phase == ProgressiveChapterPhase.PAUSED ||
                  st.canStartPlayback ||
                  st.isCompleteChapterReady)
            }
          if (readyState?.phase == ProgressiveChapterPhase.RECOVERABLE_ERROR ||
            readyState?.phase == ProgressiveChapterPhase.PAUSED
          ) {
            return@launch
          }

          val activePlayer = mediaSession?.player ?: return@launch
          val refreshed = repository.getChapterWithChunksOnce(bookId, nextChapter.chapterIndex) ?: return@launch
          val freshChapter = refreshed.first
          val freshChunks =
            AudiobookMediaHelper.extractContiguousValidChunks(
              refreshed.second.filter { it.voiceName == book.selectedVoice.name }
            )
          if (freshChunks.isNotEmpty()) {
            startProgressiveWindowOnPlayer(activePlayer, book, freshChapter, freshChunks)
            startedPlayback = true
          } else if (
            freshChapter.audioStatus == AudioGenerationStatus.READY &&
              !freshChapter.audioFilePath.isNullOrBlank() &&
              WavAudioCodec.isValidWavFile(File(freshChapter.audioFilePath))
          ) {
            val items = AudiobookMediaHelper.buildMediaItems(book, listOf(freshChapter))
            activePlayer.setMediaItems(items, 0, 0L)
            activePlayer.prepare()
            activePlayer.play()
            startedPlayback = true
          }
        } finally {
          isAutoAdvancing = false
          if (!startedPlayback && nextChapterIndexForCleanup >= 0) {
            repository.setAwaitingPlaybackStart(bookId, nextChapterIndexForCleanup, awaiting = false)
          }
        }
      }
  }

  private fun startProgressiveWindowOnPlayer(
    player: Player,
    book: Book,
    chapter: Chapter,
    contiguousChunks: List<ChapterChunk>,
  ) {
    val offsets = AudiobookMediaHelper.computeChunkStartOffsetsMs(contiguousChunks)
    val startChunkIndex = contiguousChunks.first().chunkIndex
    val windowChunks =
      AudiobookMediaHelper.selectSlidingWindowChunks(
        contiguousChunks = contiguousChunks,
        startChunkIndex = startChunkIndex,
        aheadWindowDurationMs = activeWindowAheadMs,
      )
    highestQueuedChunkIndex = windowChunks.lastOrNull()?.chunkIndex ?: -1
    val items =
      AudiobookMediaHelper.buildProgressiveMediaItems(
        book = book,
        chapter = chapter,
        chunks = windowChunks,
        startOffsetsByChunkIndex = offsets,
      )
    player.setMediaItems(items, 0, 0L)
    player.prepare()
    player.play()
  }

  private fun syncProgressiveObserverFromPlayer() {
    val player = mediaSession?.player ?: return
    val extras = player.currentMediaItem?.mediaMetadata?.extras
    val isProgressive =
      extras?.getBoolean(AudiobookMediaHelper.EXTRA_IS_PROGRESSIVE_CHUNK, false) == true
    val bookId = extras?.getString(AudiobookMediaHelper.EXTRA_BOOK_ID)?.takeIf { it.isNotBlank() }
    val chapterIndex = extras?.getInt(AudiobookMediaHelper.EXTRA_CHAPTER_INDEX, -1) ?: -1

    if (!isProgressive || bookId == null || chapterIndex < 0) {
      if (activeProgressiveKey != null) {
        progressiveObserverJob?.cancel()
        progressiveObserverJob = null
        activeProgressiveKey = null
        activeProgressiveTotalChunks = 0
        highestQueuedChunkIndex = -1
        shouldResumeAfterBufferDepletion = false
      }
      return
    }

    val key = bookId to chapterIndex
    val playerMax = AudiobookMediaHelper.maxChunkIndexInPlayer(player)
    if (playerMax >= 0) {
      highestQueuedChunkIndex = playerMax
    }
    if (activeProgressiveKey == key && progressiveObserverJob?.isActive == true) {
      return
    }

    progressiveObserverJob?.cancel()
    activeProgressiveKey = key
    progressiveObserverJob =
      serviceScope.launch {
        val loaded = repository.getBookWithChaptersOnce(bookId) ?: return@launch
        val chapterWithChunks =
          repository.getChapterWithChunksOnce(bookId, chapterIndex) ?: return@launch
        val book = loaded.book
        val chapter = chapterWithChunks.first
        activeProgressiveTotalChunks = chapter.totalChunks

        combine(
          repository.observeChunksForChapter(chapter.id),
          windowRefillTrigger,
        ) { latestEntities, _ -> latestEntities }
          .collect { latestEntities ->
            val activePlayer = mediaSession?.player ?: return@collect
            val currentExtras = activePlayer.currentMediaItem?.mediaMetadata?.extras
            if (
              currentExtras?.getBoolean(AudiobookMediaHelper.EXTRA_IS_PROGRESSIVE_CHUNK, false) != true ||
                currentExtras.getString(AudiobookMediaHelper.EXTRA_BOOK_ID) != bookId ||
                currentExtras.getInt(AudiobookMediaHelper.EXTRA_CHAPTER_INDEX, -1) != chapterIndex
            ) {
              return@collect
            }

            val validContiguous =
              AudiobookMediaHelper.extractContiguousValidChunks(
                latestEntities.filter { it.voiceName == book.selectedVoice.name }
              )
            if (validContiguous.isEmpty()) return@collect

            val refreshedChapter =
              repository.getChapterWithChunksOnce(bookId, chapterIndex)?.first ?: chapter
            if (refreshedChapter.totalChunks > activeProgressiveTotalChunks) {
              activeProgressiveTotalChunks = refreshedChapter.totalChunks
            }

            val offsetsByChunk = AudiobookMediaHelper.computeChunkStartOffsetsMs(validContiguous)
            val tailInPlayer = AudiobookMediaHelper.maxChunkIndexInPlayer(activePlayer)
            val maxQueued =
              if (activePlayer.mediaItemCount > 0 && tailInPlayer >= 0) tailInPlayer
              else highestQueuedChunkIndex

            val candidates = validContiguous.filter { it.chunkIndex > maxQueued }
            if (candidates.isEmpty()) return@collect

            var aheadMs = AudiobookMediaHelper.computeWindowAheadDurationMs(activePlayer)
            val chunksToAppend = ArrayList<ChapterChunk>(4)
            for (chunk in candidates) {
              val projectedCount = activePlayer.mediaItemCount + chunksToAppend.size
              if (projectedCount >= 2 && aheadMs >= activeWindowAheadMs) {
                break
              }
              chunksToAppend += chunk
              aheadMs += chunk.durationMs.coerceAtLeast(0L)
            }

            if (chunksToAppend.isNotEmpty()) {
              val previousCount = activePlayer.mediaItemCount
              val itemsToAdd =
                AudiobookMediaHelper.buildProgressiveMediaItems(
                  book = book,
                  chapter = refreshedChapter,
                  chunks = chunksToAppend,
                  startOffsetsByChunkIndex = offsetsByChunk,
                )
              activePlayer.addMediaItems(itemsToAdd)
              highestQueuedChunkIndex = chunksToAppend.last().chunkIndex

              val hitEndWhilePlaying =
                shouldResumeAfterBufferDepletion ||
                  (activePlayer.playbackState == Player.STATE_ENDED && activePlayer.playWhenReady)
              if (hitEndWhilePlaying && previousCount < activePlayer.mediaItemCount) {
                shouldResumeAfterBufferDepletion = false
                activePlayer.seekTo(previousCount, 0L)
                activePlayer.prepare()
                activePlayer.play()
              }
            }
          }
      }
  }

  private fun notifyProgressiveEngineFromPlayer() {
    val player = mediaSession?.player ?: return
    val extras = player.currentMediaItem?.mediaMetadata?.extras ?: return
    if (!extras.getBoolean(AudiobookMediaHelper.EXTRA_IS_PROGRESSIVE_CHUNK, false)) return
    val bookId = extras.getString(AudiobookMediaHelper.EXTRA_BOOK_ID)?.takeIf { it.isNotBlank() } ?: return
    val chapterIndex = extras.getInt(AudiobookMediaHelper.EXTRA_CHAPTER_INDEX, -1)
    if (chapterIndex < 0) return

    val cumulativePos = AudiobookMediaHelper.resolveProgressiveChapterPositionMs(player)
    val speed = runCatching { player.playbackParameters.speed }.getOrDefault(1.0f)
    repository.notifyProgressivePlaybackState(
      bookId = bookId,
      chapterIndex = chapterIndex,
      chapterPositionMs = cumulativePos,
      isPlaying = player.isPlaying,
      playbackSpeed = speed,
    )
  }

  private fun startPersistenceTicker() {
    if (tickerJob?.isActive == true) return
    lastDbPersistAtMs = 0L
    tickerJob =
      serviceScope.launch {
        while (isActive) {
          notifyProgressiveEngineFromPlayer()
          val now = System.currentTimeMillis()
          if (now - lastDbPersistAtMs >= POSITION_SAVE_INTERVAL_MS) {
            persistPlayerPositionSnapshot()
            lastDbPersistAtMs = now
          }
          delay(PROGRESSIVE_HEARTBEAT_INTERVAL_MS)
        }
      }
  }

  private fun stopPersistenceTicker() {
    tickerJob?.cancel()
    tickerJob = null
  }

  private fun persistPlayerPositionSnapshot() {
    val player = mediaSession?.player ?: return
    val item = player.currentMediaItem ?: return
    val extras = item.mediaMetadata.extras ?: return
    val bookId = extras.getString(AudiobookMediaHelper.EXTRA_BOOK_ID)?.takeIf { it.isNotBlank() }
      ?: return
    val chapterIndex = extras.getInt(AudiobookMediaHelper.EXTRA_CHAPTER_INDEX, -1)
    if (chapterIndex < 0) return
    val isProgressive = extras.getBoolean(AudiobookMediaHelper.EXTRA_IS_PROGRESSIVE_CHUNK, false)
    val positionMs =
      if (isProgressive) {
        AudiobookMediaHelper.resolveProgressiveChapterPositionMs(player)
      } else {
        player.currentPosition.coerceAtLeast(0L)
      }
    lastPersistenceJob =
      persistenceScope.launch {
        runCatching { repository.updatePlaybackPosition(bookId, chapterIndex, positionMs) }
      }
  }

  companion object {
    const val SESSION_ID = "audiolibro_media_session"
    const val CHANNEL_ID_PLAYBACK = "audiolibro_playback_channel"
    private const val POSITION_SAVE_INTERVAL_MS = 3_000L
    private const val PROGRESSIVE_HEARTBEAT_INTERVAL_MS = 1_000L
  }
}
