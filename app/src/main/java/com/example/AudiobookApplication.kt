package com.example

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import com.example.data.repository.AudiobookRepository
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Inicializador de ciclo de vida de aplicación para restaurar automáticamente las colas de
 * generación persistidas en Room al arrancar el proceso o cuando la app vuelve a primer plano.
 */
class AudiobookApplication : Application() {

  private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val startedActivities = AtomicInteger(0)

  override fun onCreate() {
    super.onCreate()
    registerActivityLifecycleCallbacks(
      object : ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
          if (startedActivities.incrementAndGet() == 1) {
            triggerAutoRecovery(fromForeground = true)
          }
        }

        override fun onActivityStopped(activity: Activity) {
          startedActivities.updateAndGet { cur -> (cur - 1).coerceAtLeast(0) }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
      }
    )

    triggerAutoRecovery(fromForeground = false)
  }

  private fun triggerAutoRecovery(fromForeground: Boolean) {
    appScope.launch {
      runCatching {
          val repo = AudiobookRepository.getInstance(applicationContext)
          val resumed = repo.restoreRecoverableGenerations(startServiceIfNeeded = true)
          if (resumed) {
            Log.i(
              TAG,
              "Cola persistente reanudada automáticamente (fromForeground=$fromForeground).",
            )
          }
        }
        .onFailure { err ->
          Log.w(TAG, "Error comprobando cola persistente de generación", err)
        }
    }
  }

  companion object {
    private const val TAG = "AudiobookApplication"
  }
}
