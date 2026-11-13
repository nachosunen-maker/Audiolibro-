package com.example.firebase

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

private const val TAG = "AppCheckInit"
private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"

object FirebaseConfigHelper {
  fun hasGoogleServicesConfig(context: Context): Boolean {
    val resId = context.resources.getIdentifier("google_app_id", "string", context.packageName)
    if (resId == 0) return false
    return runCatching { context.getString(resId).isNotBlank() }.getOrDefault(false)
  }
}

fun configureAppCheck(context: Context, intent: Intent?) {
  if (!FirebaseConfigHelper.hasGoogleServicesConfig(context)) {
    Log.w(
      TAG,
      "google-services.json no está configurado todavía (google_app_id ausente). " +
        "App Check y Firebase AI Logic permanecerán inactivos hasta completar la configuración de Firebase.",
    )
    return
  }

  try {
    if (FirebaseApp.getApps(context).isEmpty()) {
      FirebaseApp.initializeApp(context)
    }

    if (BuildConfig.DEBUG) {
      val debugToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)
      if (!debugToken.isNullOrEmpty()) {
        val persistenceKey = FirebaseApp.getInstance().persistenceKey
        val prefs =
          context.getSharedPreferences("$DEBUG_STORE_PREFIX$persistenceKey", Context.MODE_PRIVATE)
        prefs.edit().putString(DEBUG_SECRET_KEY, debugToken).commit()
        Log.d(TAG, "App Check debug secret configurado correctamente.")
      } else {
        Log.w(TAG, "No se recibió FIREBASE_APPCHECK_DEBUG_TOKEN en el Intent.")
      }

      FirebaseAppCheck.getInstance()
        .installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
      Log.d(TAG, "DebugAppCheckProviderFactory instalado.")
    } else {
      FirebaseAppCheck.getInstance()
        .installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
      Log.d(TAG, "PlayIntegrityAppCheckProviderFactory instalado.")
    }
  } catch (e: Exception) {
    Log.e(TAG, "Error al inicializar Firebase App Check", e)
  }
}
