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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Servicio en primer plano para síntesis neuronal continua sin conexión con Piper + sherpa-onnx.
 *
 * - Android 15+ (API 35+): `mediaProcessing` (conversión prolongada de texto EPUB a WAV local)
 *   con manejo explícito de `Service.onTimeout(startId, fgsType)` y reprogramación persistente.
 * - Android 14 (API 34): `specialUse` declarado para compatibilidad previa a `mediaProcessing`.
 * - WakeLock parcial: sin expiración fija de 2 horas; renovado periódicamente mientras existe
 *   generación activa y liberado de inmediato al terminar, pausar, fallar, agotarse el timeout o destruirse.
 */
class AudiobookGenerationService : Service() {

  internal var repositoryOverride: AudiobookRepository? = null
  private val repository: AudiobookRepository by lazy {
    repositoryOverride ?: AudiobookRepository.getInstance(applicationContext)
  }

  private val serviceParentJob = SupervisorJob()
  private val serviceScope = CoroutineScope(serviceParentJob + Dispatchers.Main.immediate)
  private var observerJob: Job? = null
  private var stickyRestoreJob: Job? = null
  private var wakeLockRenewalJob: Job? = null
  private var wakeLock: PowerManager.WakeLock? = null
  private var lastNotifiedSignature: String? = null
  @Volatile private var activeStartId: Int = 0
  @Volatile private var isRestoringStickyQueue: Boolean = false

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    ensureNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    activeStartId = startId
    val action = intent?.action
    val bookId = intent?.getStringExtra(EXTRA_BOOK_ID)
    Log.i(TAG, "onStartCommand(startId=$startId, action=$action, bookId=$bookId)")

    if (action == ACTION_CANCEL_GENERATION) {
      isRestoringStickyQueue = false
      stickyRestoreJob?.cancel()
      stickyRestoreJob = null
      if (!bookId.isNullOrBlank()) {
        repository.cancelAudiobookGeneration(bookId)
      } else {
        repository.cancelAllGenerations()
      }
      if (!repository.hasActiveOrQueuedGenerations()) {
        stopServiceCleanly(startId)
      }
      return START_NOT_STICKY
    }

    // Si llega un reinicio sin EXTRA_BOOK_ID (START_STICKY tras cierre del SO), proteger la ventana
    // de consulta a Room antes de arrancar observerJob/WakeLock para evitar auto-detención prematura.
    val isStickyOrResumeAll = bookId.isNullOrBlank()
    isRestoringStickyQueue = isStickyOrResumeAll

    // Llamar a startForeground de inmediato para cumplir con el contrato estricto de Android 12–16
    // (evita ForegroundServiceDidNotStartInTimeException incluso antes de consultar Room).
    val initialProgress =
      if (!bookId.isNullOrBlank()) repository.generationProgress.value[bookId] else null
    startForegroundCompat(buildGenerationNotification(initialProgress, bookId))
    ensureWakeLockHeldAndRenewing()
    startObservingProgress()

    if (!bookId.isNullOrBlank()) {
      stickyRestoreJob?.cancel()
      stickyRestoreJob = null
      repository.enqueueAudiobookGeneration(bookId)
      return START_STICKY
    }

    // Reinicio del sistema vía START_STICKY (intent == null) o llamada sin EXTRA_BOOK_ID:
    // reconstruir la cola persistida desde Room antes de decidir si detener el servicio.
    stickyRestoreJob?.cancel()
    stickyRestoreJob =
      serviceScope.launch {
        val restored =
          try {
            runCatching { repository.restoreRecoverableGenerations(startServiceIfNeeded = false) }
              .onFailure { err -> Log.e(TAG, "Error restaurando cola persistente en START_STICKY", err) }
              .getOrDefault(false)
          } finally {
            isRestoringStickyQueue = false
          }
        if (!restored || !repository.hasActiveOrQueuedGenerations()) {
          if (!repository.hasActiveOrQueuedGenerations()) {
            Log.i(TAG, "Sin tareas recuperables en Room tras START_STICKY; cerrando servicio.")
            stopServiceCleanly(activeStartId)
          }
        } else {
          ensureWakeLockHeldAndRenewing()
        }
      }

    return START_STICKY
  }

  override fun onTimeout(startId: Int, fgsType: Int) {
    Log.w(
      TAG,
      "onTimeout recibido del sistema (startId=$startId, fgsType=$fgsType). " +
        "Guardando checkpoint e interrumpiendo limpiamente para reanudación automática.",
    )
    isRestoringStickyQueue = false
    stickyRestoreJob?.cancel()
    stickyRestoreJob = null
    runCatching { repository.handleOsServiceTimeout() }.onFailure { err ->
      Log.e(TAG, "Error guardando estado durante onTimeout", err)
    }
    stopServiceCleanly(startId)
  }

  override fun onDestroy() {
    isRestoringStickyQueue = false
    stickyRestoreJob?.cancel()
    stickyRestoreJob = null
    observerJob?.cancel()
    observerJob = null
    stopWakeLockAndRenewal()
    serviceScope.cancel()
    super.onDestroy()
  }

  internal fun isWakeLockHeldForTesting(): Boolean = wakeLock?.isHeld == true

  internal suspend fun awaitJobsForTesting() {
    while (true) {
      val children = serviceParentJob.children.toList()
      if (children.isEmpty()) break
      children.forEach { it.join() }
    }
  }

  private fun stopServiceCleanly(targetStartId: Int = activeStartId) {
    stopWakeLockAndRenewal()
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    if (targetStartId > 0) {
      stopSelf(targetStartId)
    } else {
      stopSelf()
    }
  }

  private fun startObservingProgress() {
    if (observerJob?.isActive == true) return
    observerJob =
      serviceScope.launch {
        repository.generationProgress.collectLatest { progressMap ->
          val active = progressMap.values.firstOrNull { it.isRunning }
          if (active != null) {
            acquireOrRenewWakeLock()
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
          } else if (!isRestoringStickyQueue && !repository.hasActiveOrQueuedGenerations()) {
            Log.i(TAG, "Cola de generación vacía; liberando WakeLock y cerrando servicio.")
            stopServiceCleanly(activeStartId)
          }
        }
      }
  }

  private fun startForegroundCompat(notification: Notification) {
    val type = foregroundServiceTypeForSdk(Build.VERSION.SDK_INT)
    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
  }

  private fun ensureWakeLockHeldAndRenewing() {
    acquireOrRenewWakeLock()
    if (wakeLockRenewalJob?.isActive == true) return
    wakeLockRenewalJob =
      serviceScope.launch {
        while (isActive) {
          delay(WAKE_LOCK_RENEW_INTERVAL_MS)
          if (isRestoringStickyQueue || repository.hasActiveOrQueuedGenerations()) {
            acquireOrRenewWakeLock()
          } else {
            stopWakeLockAndRenewal()
            break
          }
        }
      }
  }

  private fun acquireOrRenewWakeLock() {
    runCatching {
        val lock =
          wakeLock
            ?: (getSystemService(Context.POWER_SERVICE) as? PowerManager)
              ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
              ?.apply { setReferenceCounted(false) }
              ?.also { wakeLock = it }
            ?: return
        lock.acquire(WAKE_LOCK_LEASE_MS)
      }
      .onFailure { err -> Log.w(TAG, "No se pudo adquirir/renovar WakeLock parcial", err) }
  }

  private fun stopWakeLockAndRenewal() {
    wakeLockRenewalJob?.cancel()
    wakeLockRenewalJob = null
    runCatching {
        if (wakeLock?.isHeld == true) {
          wakeLock?.release()
        }
        wakeLock = null
      }
      .onFailure { err -> Log.w(TAG, "Error liberando WakeLock parcial", err) }
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

    // Ventana de concesión segura renovada automáticamente cada 5 min mientras dura la generación,
    // sin límite fijo de 2 horas que interrumpa audiolibros extensos.
    internal const val WAKE_LOCK_LEASE_MS = 15L * 60L * 1000L
    internal const val WAKE_LOCK_RENEW_INTERVAL_MS = 5L * 60L * 1000L

    internal fun foregroundServiceTypeForSdk(sdkInt: Int): Int =
      when {
        sdkInt >= Build.VERSION_CODES.VANILLA_ICE_CREAM ->
          ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
          ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        else -> 0
      }

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
