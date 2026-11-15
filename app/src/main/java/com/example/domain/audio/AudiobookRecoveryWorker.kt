package com.example.domain.audio

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.local.AppDatabase
import com.example.data.repository.AudiobookRepository
import java.util.concurrent.TimeUnit

/**
 * Programador de recuperación persistente basado en WorkManager para reanudar
 * tareas en cola o interrumpidas por el sistema (`INTERRUPTED_BY_OS`, `QUEUED`, `RUNNING`)
 * tras muerte del proceso o límite temporal `mediaProcessing` de Android 15+.
 */
object AudiobookRecoveryScheduler {
  private const val TAG = "AudiobookRecoverySched"
  const val UNIQUE_RECOVERY_WORK_NAME = "audiolibro_persistent_generation_recovery"

  fun scheduleRecovery(
    context: Context,
    initialDelaySeconds: Long = 5L,
    replaceExisting: Boolean = true,
  ) {
    runCatching {
        val workManager = WorkManager.getInstance(context.applicationContext)
        val request =
          OneTimeWorkRequestBuilder<AudiobookRecoveryWorker>()
            .setInitialDelay(initialDelaySeconds.coerceAtLeast(0L), TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
            .build()
        val policy =
          if (replaceExisting) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        workManager.enqueueUniqueWork(UNIQUE_RECOVERY_WORK_NAME, policy, request)
        Log.i(
          TAG,
          "Recuperación automática programada en WorkManager (delay=${initialDelaySeconds}s, policy=$policy)",
        )
      }
      .onFailure { err ->
        Log.w(TAG, "No se pudo programar WorkManager de recuperación en este entorno", err)
      }
  }

  fun cancelRecovery(context: Context) {
    runCatching {
      WorkManager.getInstance(context.applicationContext)
        .cancelUniqueWork(UNIQUE_RECOVERY_WORK_NAME)
    }
  }
}

class AudiobookRecoveryWorker(
  appContext: Context,
  workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

  internal var repositoryOverride: AudiobookRepository? = null

  override suspend fun doWork(): Result {
    val repository = repositoryOverride ?: AudiobookRepository.getInstance(applicationContext)
    val dao = AppDatabase.getInstance(applicationContext).audiobookDao()

    val recoverable = dao.getRecoverableGenerationTasksOnce()
    if (recoverable.isEmpty()) {
      Log.i(TAG, "Sin tareas recuperables pendientes en base de datos; worker finalizado.")
      return Result.success()
    }

    Log.i(
      TAG,
      "Iniciando recuperación automática desde Room (${recoverable.size} tarea(s): " +
        recoverable.joinToString { "${it.bookId}:${it.status}" } + ")",
    )

    // Intentar promover al servicio en primer plano si el sistema lo permite en este contexto.
    val startedForegroundService = repository.tryStartForegroundServiceForRecovery()
    if (startedForegroundService) {
      Log.i(TAG, "Servicio en primer plano reanudado correctamente desde WorkManager.")
      return Result.success()
    }

    // Si Android restringe iniciar FGS desde segundo plano (por ejemplo tras agotarse la ventana de 6 h
    // hasta que la app vuelva al primer plano), avanzamos fragmentos válidos directamente en segundo plano
    // conservando cada WAV completado y reintentando si aún quedan fragmentos pendientes.
    return repository.runRecoverableTasksInWorker()
  }

  companion object {
    private const val TAG = "AudiobookRecoveryWorker"
  }
}
