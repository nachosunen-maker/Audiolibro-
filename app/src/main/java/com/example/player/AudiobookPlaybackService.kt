package com.example.player

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.MainActivity
import com.example.R
import com.example.data.repository.AudiobookRepository
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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

  private val servicePlayerListener =
    object : Player.Listener {
      override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
          startPersistenceTicker()
        } else {
          stopPersistenceTicker()
          persistPlayerPositionSnapshot()
        }
      }

      override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        persistPlayerPositionSnapshot()
      }

      override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_READY) {
          persistPlayerPositionSnapshot()
        }
      }

      override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
      ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
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
            if (playableChapters.isEmpty()) {
              future.setException(
                IllegalStateException("El último audiolibro no tiene capítulos listos.")
              )
              return@launch
            }
            val targetChapterIndex =
              lastBook.book.lastPlayedChapterIndex ?: playableChapters.first().chapterIndex
            val startIndex =
              playableChapters.indexOfFirst { it.chapterIndex == targetChapterIndex }.coerceAtLeast(0)
            val startPositionMs = lastBook.book.lastPlayedPositionMs.coerceAtLeast(0L)
            val mediaItems = AudiobookMediaHelper.buildMediaItems(lastBook.book, playableChapters)
            future.set(
              MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
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

    val player =
      ExoPlayer.Builder(this)
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
    if (
      player == null ||
        !player.playWhenReady ||
        player.mediaItemCount == 0 ||
        player.playbackState == Player.STATE_ENDED
    ) {
      persistPlayerPositionSnapshot()
      stopSelf()
    }
  }

  override fun onDestroy() {
    stopPersistenceTicker()
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

  private fun startPersistenceTicker() {
    if (tickerJob?.isActive == true) return
    tickerJob =
      serviceScope.launch {
        while (isActive) {
          persistPlayerPositionSnapshot()
          delay(POSITION_SAVE_INTERVAL_MS)
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
    val positionMs = player.currentPosition.coerceAtLeast(0L)
    lastPersistenceJob =
      persistenceScope.launch {
        runCatching { repository.updatePlaybackPosition(bookId, chapterIndex, positionMs) }
      }
  }

  companion object {
    const val SESSION_ID = "audiolibro_media_session"
    const val CHANNEL_ID_PLAYBACK = "audiolibro_playback_channel"
    private const val POSITION_SAVE_INTERVAL_MS = 3_000L
  }
}
