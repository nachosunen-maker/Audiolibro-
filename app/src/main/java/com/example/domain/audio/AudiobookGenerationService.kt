package com.example.domain.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.repository.AudiobookRepository
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Servicio en primer plano (`foregroundServiceType="dataSync"`) para sintetizar audiolibros completos
 * en segundo plano, con pantalla apagada o mientras la persona usuaria navega por otras pantallas.
 */
class AudiobookGenerationService : Service() {

  internal var repositoryOverride: AudiobookRepository? = null
  private val repository: AudiobookRepository by lazy {
    repositoryOverride ?: AudiobookRepository.getInstance(applicationContext)
  }

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var observerJob: Job? = null
  private var wakeLock: PowerManager.WakeLock? = null
  private var lastNotifiedSignature: String? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    ensureNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val action = intent?.action
    val bookId = intent?.getStringExtra(EXTRA_BOOK_ID)
    Log.i(TAG, "onStartCommand(action=$action, bookId=$bookId)")

    if (action == ACTION_CANCEL_GENERATION) {
      if (!bookId.isNullOrBlank()) {
        repository.cancelAudiobookGeneration(bookId)
      } else {
        repository.cancelAllGenerations()
      }
      if (!repository.hasActiveOrQueuedGenerations()) {
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
      }
      return START_NOT_STICKY
    }

    // Llamar a startForeground de inmediato para cumplir con el contrato estricto de Android 12–16
    // (evita ForegroundServiceDidNotStartInTimeException incluso antes de lanzar la corrutina).
    val initialProgress =
      if (!bookId.isNullOrBlank()) repository.generationProgress.value[bookId] else null
    startForegroundCompat(buildGenerationNotification(initialProgress, bookId))
    acquireWakeLock()
    startObservingProgress()

    if (!bookId.isNullOrBlank()) {
      repository.enqueueAudiobookGeneration(bookId)
    } else if (!repository.hasActiveOrQueuedGenerations()) {
      releaseWakeLock()
      ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
      stopSelf()
    }

    return START_NOT_STICKY
  }

  override fun onDestroy() {
    observerJob?.cancel()
    releaseWakeLock()
    serviceScope.cancel()
    super.onDestroy()
  }

  private fun startObservingProgress() {
    if (observerJob?.isActive == true) return
    observerJob =
      serviceScope.launch {
        repository.generationProgress.collectLatest { progressMap ->
          val active = progressMap.values.firstOrNull { it.isRunning }
          if (active != null) {
            val sig =
              "${active.bookId}|${active.phase}|${active.currentChapterNumber}|" +
                "${active.currentChunkIndex}|${active.completedBookChunks}|${active.totalBookChunks}"
            if (sig != lastNotifiedSignature) {
              lastNotifiedSignature = sig
              val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
              notificationManager?.notify(
                NOTIFICATION_ID,
                buildGenerationNotification(active, active.bookId),
              )
            }
          } else if (!repository.hasActiveOrQueuedGenerations()) {
            Log.i(TAG, "Cola de generación vacía; finalizando servicio en primer plano.")
            releaseWakeLock()
            ServiceCompat.stopForeground(
              this@AudiobookGenerationService,
              ServiceCompat.STOP_FOREGROUND_REMOVE,
            )
            stopSelf()
          }
        }
      }
  }

  private fun startForegroundCompat(notification: Notification) {
    val type =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
      } else {
        0
      }
    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
  }

  private fun acquireWakeLock() {
    if (wakeLock?.isHeld == true) return
    runCatching {
      val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
      wakeLock =
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
          setReferenceCounted(false)
          acquire(WAKELOCK_TIMEOUT_MS)
        }
    }.onFailure { err ->
      Log.w(TAG, "No se pudo adquirir WakeLock parcial", err)
    }
  }

  private fun releaseWakeLock() {
    runCatching {
      if (wakeLock?.isHeld == true) {
        wakeLock?.release()
      }
      wakeLock = null
    }.onFailure { err ->
      Log.w(TAG, "Error liberando WakeLock parcial", err)
    }
  }

  private fun ensureNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
      if (manager.getNotificationChannel(CHANNEL_ID_GENERATION) == null) {
        val channel =
          NotificationChannel(
            CHANNEL_ID_GENERATION,
            getString(R.string.notification_channel_generation),
            NotificationManager.IMPORTANCE_LOW,
          ).apply {
            description = "Progreso continuo de síntesis neuronal local Piper"
            setShowBadge(false)
          }
        manager.createNotificationChannel(channel)
      }
    }
  }

  private fun buildGenerationNotification(
    progress: BookGenerationProgress?,
    fallbackBookId: String?,
  ): Notification {
    val openIntent =
      Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
      }
    val openPendingIntent =
      PendingIntent.getActivity(
        this,
        101,
        openIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val targetBookId = progress?.bookId ?: fallbackBookId
    val cancelIntent =
      Intent(this, AudiobookGenerationService::class.java).apply {
        action = ACTION_CANCEL_GENERATION
        if (!targetBookId.isNullOrBlank()) {
          putExtra(EXTRA_BOOK_ID, targetBookId)
        }
      }
    val cancelPendingIntent =
      PendingIntent.getService(
        this,
        102,
        cancelIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val title =
      progress?.bookTitle?.takeIf { it.isNotBlank() }?.let { "Generando: $it" }
        ?: "Generando audiolibro local"

    val text =
      progress?.statusMessage?.takeIf { it.isNotBlank() }
        ?: "Preparando síntesis local con Piper…"

    val builder =
      NotificationCompat.Builder(this, CHANNEL_ID_GENERATION)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(title)
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setContentIntent(openPendingIntent)
        .addAction(android.R.drawable.ic_media_pause, "Pausar", cancelPendingIntent)

    if (progress != null && progress.totalBookChunks > 0) {
      val percent = (progress.globalProgressFraction * 100f).roundToInt().coerceIn(0, 100)
      builder.setProgress(100, percent, false)
    } else {
      builder.setProgress(0, 0, true)
    }

    return builder.build()
  }

  companion object {
    private const val TAG = "AudiobookGenService"
    const val CHANNEL_ID_GENERATION = "audiolibro_generation_channel"
    const val NOTIFICATION_ID = 4102
    const val ACTION_START_GENERATION = "com.example.action.START_GENERATION"
    const val ACTION_CANCEL_GENERATION = "com.example.action.CANCEL_GENERATION"
    const val EXTRA_BOOK_ID = "extra_book_id"
    private const val WAKELOCK_TAG = "Audiolibro:GenerationWakeLock"
    private const val WAKELOCK_TIMEOUT_MS = 2L * 60L * 60L * 1000L

    fun startBookGeneration(context: Context, bookId: String) {
      val intent =
        Intent(context, AudiobookGenerationService::class.java).apply {
          action = ACTION_START_GENERATION
          putExtra(EXTRA_BOOK_ID, bookId)
        }
      ContextCompat.startForegroundService(context, intent)
    }

    fun stopBookGeneration(context: Context, bookId: String? = null) {
      val intent =
        Intent(context, AudiobookGenerationService::class.java).apply {
          action = ACTION_CANCEL_GENERATION
          if (!bookId.isNullOrBlank()) {
            putExtra(EXTRA_BOOK_ID, bookId)
          }
        }
      context.startService(intent)
    }
  }
}
