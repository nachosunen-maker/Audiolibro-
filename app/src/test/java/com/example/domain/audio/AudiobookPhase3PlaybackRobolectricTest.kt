package com.example.domain.audio

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Looper
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.lifecycle.SavedStateHandle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaButtonReceiver
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.MainActivity
import com.example.data.local.AppDatabase
import com.example.data.local.AudiobookDao
import com.example.data.local.BookEntity
import com.example.data.local.ChapterEntity
import com.example.data.repository.AudiobookRepository
import com.example.data.storage.AudiobookStorageManager
import com.example.domain.model.AudioGenerationStatus
import com.example.domain.model.NarratorVoice
import com.example.player.AudiobookMediaHelper
import com.example.player.AudiobookPlaybackController
import com.example.player.AudiobookPlaybackService
import com.example.ui.viewmodel.PlayerViewModel
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AudiobookPhase3PlaybackRobolectricTest {

  private lateinit var context: Context
  private lateinit var db: AppDatabase
  private lateinit var storageManager: AudiobookStorageManager
  private lateinit var repository: AudiobookRepository

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    storageManager = AudiobookStorageManager(context)
    storageManager.deleteBookAudioFiles("book-p3-1")
    storageManager.deleteBookAudioFiles("book-p3-2")
    repository = AudiobookRepository(context, db.audiobookDao(), storageManager = storageManager)
  }

  @After
  fun tearDown() {
    storageManager.deleteBookAudioFiles("book-p3-1")
    storageManager.deleteBookAudioFiles("book-p3-2")
    File(context.filesDir, "covers").deleteRecursively()
    db.close()
  }

  @OptIn(UnstableApi::class)
  @Test
  fun `service initializes ExoPlayer with speech AudioAttributes WakeLock and releases cleanly on destroy`() {
    val serviceController = Robolectric.buildService(AudiobookPlaybackService::class.java)
    val service = serviceController.create().get()
    val controllerInfo = createTestControllerInfo()
    try {
      service.repositoryOverride = repository

      val session = service.currentSessionForTesting()
      assertNotNull(session)
      assertEquals(AudiobookPlaybackService.SESSION_ID, session!!.id)
      val exoPlayer = session.player as ExoPlayer
      assertEquals(C.USAGE_MEDIA, exoPlayer.audioAttributes.usage)
      assertEquals(C.AUDIO_CONTENT_TYPE_SPEECH, exoPlayer.audioAttributes.contentType)
      assertEquals(15_000L, exoPlayer.seekBackIncrement)
      assertEquals(15_000L, exoPlayer.seekForwardIncrement)

      val activeSession = service.onGetSession(controllerInfo)
      assertNotNull(activeSession)
      assertNotNull(activeSession!!.sessionActivity)
    } finally {
      serviceController.destroy()
      Shadows.shadowOf(Looper.getMainLooper()).idle()
    }
    assertNull(service.currentSessionForTesting())
    assertNull(service.onGetSession(controllerInfo))
  }

  @Test
  fun `manifest declares minimal playback permissions foregroundServiceType and MediaButtonReceiver`() {
    val flags =
      PackageManager.GET_PERMISSIONS or
        PackageManager.GET_SERVICES or
        PackageManager.GET_RECEIVERS
    val pkgInfo =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(
          context.packageName,
          PackageManager.PackageInfoFlags.of(flags.toLong()),
        )
      } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, flags)
      }

    val permissions = pkgInfo.requestedPermissions?.toSet().orEmpty()
    assertTrue(permissions.contains(Manifest.permission.WAKE_LOCK))
    assertTrue(permissions.contains(Manifest.permission.FOREGROUND_SERVICE))
    assertTrue(permissions.contains(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK))
    assertTrue(permissions.contains(Manifest.permission.POST_NOTIFICATIONS))
    assertFalse(permissions.contains(Manifest.permission.INTERNET))
    assertFalse(permissions.contains("android.permission.READ_PHONE_STATE"))
    assertFalse(permissions.contains("android.permission.BLUETOOTH_CONNECT"))
    assertFalse(permissions.contains("android.permission.READ_EXTERNAL_STORAGE"))

    val serviceInfo =
      pkgInfo.services?.firstOrNull { it.name == AudiobookPlaybackService::class.java.name }
    assertNotNull(serviceInfo)
    assertTrue(
      (serviceInfo!!.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) != 0
    )

    val receiverInfo =
      pkgInfo.receivers?.firstOrNull { it.name == MediaButtonReceiver::class.java.name }
    assertNotNull(receiverInfo)

    val mediaButtonIntent =
      Intent(Intent.ACTION_MEDIA_BUTTON).apply {
        setPackage(context.packageName)
        putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
      }
    val resolvedReceivers = context.packageManager.queryBroadcastReceivers(mediaButtonIntent, 0)
    assertTrue(
      resolvedReceivers.any { it.activityInfo.name == MediaButtonReceiver::class.java.name }
    )
  }

  @Test
  fun `media metadata updates across chapters and includes book title author and cover artwork`() =
    runBlocking {
      val dao = db.audiobookDao()
      val coverFile = createDummyCoverFile("book-p3-1")
      val (book, chapters) = insertBookWithReadyWavChapters(dao, "book-p3-1", coverFile.absolutePath)

      val items = AudiobookMediaHelper.buildMediaItems(book, chapters)
      assertEquals(2, items.size)

      val firstMeta = items[0].mediaMetadata
      assertEquals("Capítulo 1", firstMeta.title.toString())
      assertTrue(firstMeta.artist.toString().contains("Novela de Prueba"))
      assertTrue(firstMeta.artist.toString().contains("Autora Demo"))
      assertEquals("Novela de Prueba", firstMeta.albumTitle.toString())
      assertEquals("Novela de Prueba", firstMeta.subtitle.toString())
      assertEquals(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK, firstMeta.mediaType)
      assertNotNull(firstMeta.artworkUri)
      assertEquals("book-p3-1", firstMeta.extras?.getString(AudiobookMediaHelper.EXTRA_BOOK_ID))
      assertEquals(1, firstMeta.extras?.getInt(AudiobookMediaHelper.EXTRA_CHAPTER_INDEX))

      val noCoverBook = book.copy(coverImagePath = null)
      val noCoverItems = AudiobookMediaHelper.buildMediaItems(noCoverBook, chapters)
      assertNull(noCoverItems[0].mediaMetadata.artworkUri)

      val serviceController = Robolectric.buildService(AudiobookPlaybackService::class.java)
      val service = serviceController.create().get()
      try {
        service.repositoryOverride = repository
        val player = service.currentSessionForTesting()!!.player
        player.setMediaItems(items, 0, 0L)
        player.prepare()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Capítulo 1", player.currentMediaItem?.mediaMetadata?.title?.toString())

        player.seekToNextMediaItem()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Capítulo 2", player.currentMediaItem?.mediaMetadata?.title?.toString())
        assertEquals(
          2,
          player.currentMediaItem?.mediaMetadata?.extras?.getInt(AudiobookMediaHelper.EXTRA_CHAPTER_INDEX),
        )
      } finally {
        serviceController.destroy()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
      }
    }

  @Test
  fun `controller enforces single playback pauses on becoming noisy and handles controls`() =
    runBlocking {
      val dao = db.audiobookDao()
      val (book1, chapters1) = insertBookWithReadyWavChapters(dao, "book-p3-1", null)
      val (book2, chapters2) =
        insertBookWithReadyWavChapters(dao, "book-p3-2", null, title = "Segundo Libro")

      val testPlayer = InMemoryPlaylistPlayer()
      val controller = AudiobookPlaybackController(context, repository, injectedPlayer = testPlayer)
      try {
        controller.playBook(book1, chapters1, startChapterIndex = 1, startPositionMs = 450L)
        awaitCondition {
          controller.uiState.value.activeBookId == "book-p3-1" && controller.uiState.value.isPlaying
        }
        assertEquals(1, controller.uiState.value.currentChapterIndex)
        assertEquals(450L, controller.uiState.value.positionMs)

        controller.seekForward(300L)
        assertEquals(750L, controller.uiState.value.positionMs)
        controller.nextChapter()
        assertEquals(2, controller.uiState.value.currentChapterIndex)
        controller.previousChapter()
        assertEquals(1, controller.uiState.value.currentChapterIndex)
        controller.togglePlayPause()
        assertFalse(controller.uiState.value.isPlaying)
        controller.togglePlayPause()
        assertTrue(controller.uiState.value.isPlaying)

        // Switching to Book 2 replaces the single player's playlist without two simultaneous
        // playbacks and saves Book 1's position first.
        controller.playBook(book2, chapters2, startChapterIndex = 1, startPositionMs = 100L)
        awaitCondition { controller.uiState.value.activeBookId == "book-p3-2" }
        assertEquals("Segundo Libro", controller.uiState.value.activeBookTitle)
        assertEquals(2, testPlayer.mediaItemCount)
        assertEquals("book-p3-2:1", testPlayer.currentMediaItem?.mediaId)

        awaitCondition { dao.getBookOnce("book-p3-1")?.lastPlayedChapterIndex == 1 }
      } finally {
        controller.releaseForTesting()
        testPlayer.release()
      }

      // Verify ExoPlayer inside AudiobookPlaybackService pauses when headphones disconnect
      // (ACTION_AUDIO_BECOMING_NOISY).
      val serviceController = Robolectric.buildService(AudiobookPlaybackService::class.java)
      val service = serviceController.create().get()
      try {
        service.repositoryOverride = repository
        val exoPlayer = service.currentSessionForTesting()!!.player
        val items = AudiobookMediaHelper.buildMediaItems(book1, chapters1)
        exoPlayer.setMediaItems(items, 0, 0L)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(exoPlayer.playWhenReady)

        context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse(exoPlayer.playWhenReady)
      } finally {
        serviceController.destroy()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
      }
    }

  @Test
  fun `service persists position independently of UI survives task removal while playing and resumes via MediaSession`() =
    runBlocking {
      val dao = db.audiobookDao()
      val (book, chapters) = insertBookWithReadyWavChapters(dao, "book-p3-1", null)
      val items = AudiobookMediaHelper.buildMediaItems(book, chapters)

      val serviceController = Robolectric.buildService(AudiobookPlaybackService::class.java)
      val service = serviceController.create().get()
      try {
        service.repositoryOverride = repository
        val exoPlayer = service.currentSessionForTesting()!!.player

        // Simulate user controlling playback only from lock screen / service without MainActivity.
        exoPlayer.setMediaItems(items, 1, 820L)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Removing task while actively playing must NOT stop the service.
        service.onTaskRemoved(Intent())
        assertFalse(Shadows.shadowOf(service).isStoppedBySelf)

        // Pausing triggers service-side position persistence to Room.
        exoPlayer.pause()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        service.awaitPersistenceForTesting()

        val savedBook = dao.getBookOnce("book-p3-1")
        assertEquals(2, savedBook?.lastPlayedChapterIndex)
        assertTrue((savedBook?.lastPlayedPositionMs ?: -1L) >= 820L)

        // MediaSession playback resumption restores the exact last played book, chapter, and position.
        val resumptionFuture = service.triggerPlaybackResumptionForTesting(createTestControllerInfo())
        val resumption = awaitFuture(resumptionFuture)
        assertEquals(2, resumption.mediaItems.size)
        assertEquals(1, resumption.startIndex)
        assertTrue(resumption.startPositionMs >= 820L)

        // Removing task while paused/not playWhenReady cleanly stops the service.
        service.onTaskRemoved(Intent())
        assertTrue(Shadows.shadowOf(service).isStoppedBySelf)
      } finally {
        serviceController.destroy()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
      }
    }

  @Test
  fun `controller reconnect and PlayerViewModel recreation recover position without rewinding`() =
    runBlocking {
      val dao = db.audiobookDao()
      val (book, chapters) = insertBookWithReadyWavChapters(dao, "book-p3-1", null)
      val items = AudiobookMediaHelper.buildMediaItems(book, chapters)

      // 1) Simulate a brand new controller connecting to an already-active player on Chapter 2.
      val existingPlayer = InMemoryPlaylistPlayer()
      existingPlayer.setMediaItems(items, 1, 900L)
      existingPlayer.prepare()
      existingPlayer.play()

      val reconnectedController =
        AudiobookPlaybackController(context, repository, injectedPlayer = existingPlayer)
      try {
        awaitCondition {
          val st = reconnectedController.uiState.value
          st.activeBookId == "book-p3-1" && st.playlistChapters.size == 2
        }
        val hydrated = reconnectedController.uiState.value
        assertEquals(2, hydrated.currentChapterIndex)
        assertEquals("Capítulo 2", hydrated.currentChapterTitle)
        assertEquals(900L, hydrated.positionMs)
        assertTrue(hydrated.isPlaying)

        // 2) Simulate recreating PlayerViewModel after Activity configuration change with SavedStateHandle:
        // even though startChapterIndex arg was 1, active playback on Chapter 2 must NOT rewind to Chapter 1.
        val savedStateHandle = SavedStateHandle(mapOf("player_initial_auto_started" to true))
        val recreatedVm =
          PlayerViewModel(
            bookId = "book-p3-1",
            startChapterIndex = 1,
            repository = repository,
            playbackController = reconnectedController,
            savedStateHandle = savedStateHandle,
          )
        delay(50L)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, recreatedVm.playbackState.value.currentChapterIndex)
        assertEquals(900L, recreatedVm.playbackState.value.positionMs)
      } finally {
        reconnectedController.releaseForTesting()
        existingPlayer.release()
      }

      // 3) Simulate cold app launch restoring last played book/chapter/position into MiniPlayer state.
      repository.updatePlaybackPosition("book-p3-1", 2, 1250L)
      val idlePlayer = InMemoryPlaylistPlayer()
      val coldController =
        AudiobookPlaybackController(context, repository, injectedPlayer = idlePlayer)
      try {
        coldController.restoreLastPlaybackIfIdle()
        awaitCondition {
          val st = coldController.uiState.value
          st.hasActiveAudio && st.activeBookId == "book-p3-1"
        }
        val restored = coldController.uiState.value
        assertEquals(2, restored.currentChapterIndex)
        assertEquals(1250L, restored.positionMs)
        assertFalse(restored.isPlaying)
      } finally {
        coldController.releaseForTesting()
        idlePlayer.release()
      }
    }

  @Test
  fun `MainActivity recreates cleanly connected to AudiobookPlaybackService`() {
    val serviceController = Robolectric.buildService(AudiobookPlaybackService::class.java)
    val service = serviceController.create().get()
    try {
      service.repositoryOverride = repository
      val binder = service.onBind(Intent(MediaSessionService.SERVICE_INTERFACE))
      Shadows.shadowOf(context as Application)
        .setComponentNameAndServiceForBindService(
          ComponentName(context, AudiobookPlaybackService::class.java),
          binder,
        )

      val activityController = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      assertNotNull(activityController.get())

      activityController.recreate()
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      assertNotNull(activityController.get())

      activityController.pause().stop().destroy()
      Shadows.shadowOf(Looper.getMainLooper()).idle()
    } finally {
      AudiobookPlaybackController.getInstance(context).releaseForTesting()
      serviceController.destroy()
      Shadows.shadowOf(Looper.getMainLooper()).idle()
    }
  }

  private suspend fun awaitCondition(timeoutMs: Long = 5_000L, predicate: suspend () -> Boolean) {
    withTimeout(timeoutMs) {
      while (!predicate()) {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        delay(15L)
      }
    }
  }

  private suspend fun <T> awaitFuture(
    future: ListenableFuture<T>,
    timeoutMs: Long = 5_000L,
  ): T {
    awaitCondition(timeoutMs) { future.isDone }
    return future.get()
  }

  private fun createTestControllerInfo(): MediaSession.ControllerInfo =
    MediaSession.ControllerInfo.createTestOnlyControllerInfo(
      context.packageName,
      android.os.Process.myPid(),
      android.os.Process.myUid(),
      MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION,
      MediaSession.ControllerInfo.LEGACY_CONTROLLER_INTERFACE_VERSION,
      true,
      android.os.Bundle.EMPTY,
    )

  private fun createDummyCoverFile(bookId: String): File {
    val dir = File(context.filesDir, "covers").apply { mkdirs() }
    val file = File(dir, "$bookId.img")
    file.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
    return file
  }

  private suspend fun insertBookWithReadyWavChapters(
    dao: AudiobookDao,
    bookId: String,
    coverPath: String?,
    title: String = "Novela de Prueba",
  ): Pair<com.example.domain.model.Book, List<com.example.domain.model.Chapter>> {
    val bookEntity =
      BookEntity(
        id = bookId,
        title = title,
        author = "Autora Demo",
        language = "es",
        publisher = "Editorial",
        description = "Descripción",
        coverImagePath = coverPath,
        sourceFileName = "$bookId.epub",
        selectedVoice = NarratorVoice.FEMALE.name,
        totalChapters = 2,
        totalWords = 200,
        totalCharacters = 1200,
        estimatedMinutes = 2,
        importedAt = System.currentTimeMillis(),
      )
    val wav1 = storageManager.chapterWavFile(bookId, 1)
    val wav2 = storageManager.chapterWavFile(bookId, 2)
    val pcm = ByteArray(48_000) { 0x10 } // ~1000ms at 24kHz mono PCM-16
    val info1 = WavAudioCodec.writePcmAsWav(pcm, wav1)
    val info2 = WavAudioCodec.writePcmAsWav(pcm, wav2)

    val ch1 =
      ChapterEntity(
        id = "${bookId}_ch_1",
        bookId = bookId,
        chapterIndex = 1,
        title = "Capítulo 1",
        contentText = "Texto del primer capítulo.",
        wordCount = 100,
        characterCount = 600,
        estimatedMinutes = 1,
        audioStatus = AudioGenerationStatus.READY.name,
        audioFilePath = wav1.absolutePath,
        audioDurationMs = info1.durationMs,
        audioSizeBytes = wav1.length(),
        generatedChunks = 1,
        totalChunks = 1,
      )
    val ch2 =
      ChapterEntity(
        id = "${bookId}_ch_2",
        bookId = bookId,
        chapterIndex = 2,
        title = "Capítulo 2",
        contentText = "Texto del segundo capítulo.",
        wordCount = 100,
        characterCount = 600,
        estimatedMinutes = 1,
        audioStatus = AudioGenerationStatus.READY.name,
        audioFilePath = wav2.absolutePath,
        audioDurationMs = info2.durationMs,
        audioSizeBytes = wav2.length(),
        generatedChunks = 1,
        totalChunks = 1,
      )
    dao.insertBookWithChapters(bookEntity, listOf(ch1, ch2))
    return bookEntity.toDomain() to listOf(ch1.toDomain(), ch2.toDomain())
  }

  private class InMemoryPlaylistPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    private var items: List<MediaItemData> = emptyList()
    private var currentIndex: Int = 0
    private var positionMs: Long = 0L
    private var playWhenReadyFlag: Boolean = false
    private var playbackStateInt: Int = Player.STATE_IDLE

    override fun getState(): State {
      return State.Builder()
        .setAvailableCommands(
          Player.Commands.Builder()
            .addAll(
              Player.COMMAND_PLAY_PAUSE,
              Player.COMMAND_PREPARE,
              Player.COMMAND_STOP,
              Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
              Player.COMMAND_SEEK_TO_MEDIA_ITEM,
              Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
              Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
              Player.COMMAND_SET_MEDIA_ITEM,
              Player.COMMAND_CHANGE_MEDIA_ITEMS,
              Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
              Player.COMMAND_GET_TIMELINE,
              Player.COMMAND_GET_METADATA,
            )
            .build()
        )
        .setPlayWhenReady(playWhenReadyFlag, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        .setPlaybackState(playbackStateInt)
        .setPlaylist(items)
        .setCurrentMediaItemIndex(if (items.isEmpty()) C.INDEX_UNSET else currentIndex)
        .setContentPositionMs(positionMs)
        .build()
    }

    override fun handleSetMediaItems(
      mediaItems: MutableList<MediaItem>,
      startIndex: Int,
      startPositionMs: Long,
    ): ListenableFuture<*> {
      items =
        mediaItems.map { item ->
          MediaItemData.Builder(item.mediaId)
            .setMediaItem(item)
            .setDurationUs(2_000_000L)
            .build()
        }
      currentIndex = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
      positionMs = startPositionMs.coerceAtLeast(0L)
      invalidateState()
      return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
      playbackStateInt = Player.STATE_READY
      invalidateState()
      return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
      playWhenReadyFlag = playWhenReady
      invalidateState()
      return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
      mediaItemIndex: Int,
      positionMs: Long,
      seekCommand: Int,
    ): ListenableFuture<*> {
      if (items.isNotEmpty() && mediaItemIndex in items.indices) {
        currentIndex = mediaItemIndex
      }
      this.positionMs = if (positionMs == C.TIME_UNSET) 0L else positionMs.coerceAtLeast(0L)
      invalidateState()
      return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
      playWhenReadyFlag = false
      playbackStateInt = Player.STATE_IDLE
      invalidateState()
      return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
      return Futures.immediateVoidFuture()
    }
  }
}
