package com.example.domain.audio

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import kotlin.math.roundToLong

enum class VisualEffectTier {
  FULL,
  REDUCED,
  STATIC_ACCESSIBLE,
}

data class DeviceResourceProfile(
  val isLowRamDevice: Boolean = false,
  val availableMemoryMb: Long = 1024L,
  val availableStorageMb: Long = 2048L,
  val cpuCores: Int = 4,
  val isPowerSaveMode: Boolean = false,
  val reducedMotionPreferred: Boolean = false,
) {
  val isConstrainedDevice: Boolean
    get() = isLowRamDevice || availableMemoryMb < 350L || cpuCores <= 2 || isPowerSaveMode

  val recommendedEffectTier: VisualEffectTier
    get() =
      when {
        reducedMotionPreferred -> VisualEffectTier.STATIC_ACCESSIBLE
        isConstrainedDevice -> VisualEffectTier.REDUCED
        else -> VisualEffectTier.FULL
      }
}

object DeviceCapabilityInspector {
  fun inspect(context: Context): DeviceResourceProfile {
    val appContext = context.applicationContext
    val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val memInfo = ActivityManager.MemoryInfo()
    val hasMemInfo = runCatching { am?.getMemoryInfo(memInfo) }.isSuccess
    val availMemMb =
      if (hasMemInfo && memInfo.availMem > 0L) memInfo.availMem / (1024L * 1024L) else 1024L
    val lowRam =
      runCatching { am?.isLowRamDevice == true || (hasMemInfo && memInfo.lowMemory) }
        .getOrDefault(false)

    val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val powerSave = runCatching { pm?.isPowerSaveMode == true }.getOrDefault(false)

    val animScale =
      runCatching {
          Settings.Global.getFloat(
            appContext.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1.0f,
          )
        }
        .getOrDefault(1.0f)

    val freeStorageMb =
      runCatching { appContext.filesDir.usableSpace / (1024L * 1024L) }
        .getOrDefault(1024L)
        .let { if (it <= 0L) 1024L else it }

    return DeviceResourceProfile(
      isLowRamDevice = lowRam,
      availableMemoryMb = availMemMb,
      availableStorageMb = freeStorageMb,
      cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
      isPowerSaveMode = powerSave,
      reducedMotionPreferred = animScale <= 0.05f,
    )
  }
}

/**
 * Rastreador incremental del factor de tiempo real (RTF = tiempo síntesis / duración de audio).
 * Excluye la carga inicial única del modelo ONNX para medir la velocidad real sostenida del dispositivo.
 */
class RtfEstimator(initialRtf: Float = 0f) {
  private var totalSynthesisMs: Long = 0L
  private var totalAudioMs: Long = 0L
  private var ewmaRtf: Float = initialRtf.coerceIn(0f, 8f)
  private var samplesRecorded: Int = if (initialRtf > 0f) 1 else 0

  fun recordFragment(synthesisMs: Long, audioDurationMs: Long) {
    if (audioDurationMs <= 0L) return
    val safeSynthMs = synthesisMs.coerceAtLeast(1L)
    totalSynthesisMs += safeSynthMs
    totalAudioMs += audioDurationMs
    val instantRtf = (safeSynthMs.toFloat() / audioDurationMs.toFloat()).coerceIn(0.02f, 8.0f)
    ewmaRtf =
      if (samplesRecorded == 0) {
        instantRtf
      } else {
        (0.55f * ewmaRtf) + (0.45f * instantRtf)
      }
    samplesRecorded += 1
  }

  fun currentRtf(): Float {
    if (samplesRecorded == 0) return 0f
    val cumulative =
      if (totalAudioMs > 0L) {
        (totalSynthesisMs.toFloat() / totalAudioMs.toFloat()).coerceIn(0.02f, 8.0f)
      } else {
        ewmaRtf
      }
    return ((ewmaRtf * 0.6f) + (cumulative * 0.4f)).coerceIn(0.02f, 8.0f)
  }

  fun hasMeasurement(): Boolean = samplesRecorded > 0
}

data class BufferEvaluationInput(
  val deviceProfile: DeviceResourceProfile,
  val measuredRtf: Float,
  val hasRtfMeasurement: Boolean,
  val playbackSpeed: Float = 1.0f,
  val chunksCompleted: Int,
  val totalChunksInChapter: Int,
  val bufferedDurationMs: Long,
  val aheadDurationMs: Long,
  val remainingChunks: Int,
  val isPlaying: Boolean,
  val isPausedByUser: Boolean,
  val isSessionActive: Boolean,
)

data class BufferDecision(
  val initialReadyTargetMs: Long,
  val aheadLookaheadTargetMs: Long,
  val measuredRtf: Float,
  val effectiveRtf: Float,
  val canStartPlayback: Boolean,
  val shouldGenerateMore: Boolean,
  val initialReadinessFraction: Float,
)

/**
 * Política adaptativa sin tamaños fijos universales: ajusta la preparación inicial y el búfer
 * por adelantado según RTF medido, velocidad de reproducción, RAM/almacenamiento y estado de reproducción.
 */
object AdaptiveBufferPolicy {

  private const val MIN_INITIAL_TARGET_MS = 6_000L
  private const val MAX_INITIAL_TARGET_STANDARD_MS = 75_000L
  private const val MAX_INITIAL_TARGET_CONSTRAINED_MS = 50_000L

  private const val FAST_RTF_THRESHOLD = 0.65f
  private const val REALTIME_RTF_THRESHOLD = 0.95f

  fun evaluate(input: BufferEvaluationInput): BufferDecision {
    val speed = input.playbackSpeed.coerceIn(0.5f, 3.0f)
    val rtf =
      if (input.hasRtfMeasurement && input.measuredRtf > 0f) {
        input.measuredRtf.coerceIn(0.02f, 8.0f)
      } else {
        if (input.deviceProfile.isConstrainedDevice) 0.95f else 0.75f
      }
    val effectiveRtf = (rtf * speed).coerceIn(0.02f, 12.0f)

    val rawInitialTargetMs =
      when {
        effectiveRtf < FAST_RTF_THRESHOLD -> {
          // Dispositivo rápido: prepara lo mínimo necesario (~8 s) para comenzar rápido.
          8_000L
        }
        effectiveRtf < REALTIME_RTF_THRESHOLD -> {
          val ratio =
            (effectiveRtf - FAST_RTF_THRESHOLD) / (REALTIME_RTF_THRESHOLD - FAST_RTF_THRESHOLD)
          (12_000f + (16_000f * ratio)).roundToLong()
        }
        else -> {
          // Dispositivo más lento que el tiempo real: acumula margen proporcional evitando cortes
          // dentro de límites razonables para este terminal.
          val surplus = (effectiveRtf - 0.85f).coerceIn(0.15f, 2.2f)
          (22_000f + (24_000f * surplus)).roundToLong()
        }
      }

    val deviceInitialCapMs =
      when {
        input.deviceProfile.availableStorageMb in 1L..96L -> 30_000L
        input.deviceProfile.isConstrainedDevice -> MAX_INITIAL_TARGET_CONSTRAINED_MS
        else -> MAX_INITIAL_TARGET_STANDARD_MS
      }
    val initialTargetMs =
      rawInitialTargetMs.coerceIn(MIN_INITIAL_TARGET_MS, deviceInitialCapMs)

    val canStartPlayback =
      input.chunksCompleted > 0 &&
        input.bufferedDurationMs > 0L &&
        (input.remainingChunks <= 0 || input.bufferedDurationMs >= initialTargetMs)

    val rawLookaheadTargetMs =
      when {
        effectiveRtf < FAST_RTF_THRESHOLD -> 20_000L
        effectiveRtf < REALTIME_RTF_THRESHOLD -> {
          val ratio =
            (effectiveRtf - FAST_RTF_THRESHOLD) / (REALTIME_RTF_THRESHOLD - FAST_RTF_THRESHOLD)
          (24_000f + (20_000f * ratio)).roundToLong()
        }
        else -> {
          val surplus = (effectiveRtf - 0.85f).coerceIn(0.15f, 2.5f)
          (45_000f + (22_000f * surplus)).roundToLong()
        }
      }

    val lookaheadCapMs =
      when {
        input.deviceProfile.availableStorageMb in 1L..96L -> 30_000L
        input.deviceProfile.isConstrainedDevice -> 55_000L
        else -> 90_000L
      }

    val activeLookaheadTargetMs =
      if (input.isPlaying) {
        rawLookaheadTargetMs.coerceIn(15_000L, lookaheadCapMs)
      } else {
        0L
      }

    val shouldGenerateMore =
      when {
        input.remainingChunks <= 0 -> false
        input.isPausedByUser -> false
        !input.isPlaying && !input.isSessionActive -> false
        // Mientras se prepara el búfer inicial en la pantalla de carga: seguir hasta hacerlo reproducible.
        !canStartPlayback && input.isSessionActive -> true
        // Si el usuario está escuchando activamente: rellenar hasta el objetivo adaptable por adelantado.
        input.isPlaying -> input.aheadDurationMs < activeLookaheadTargetMs
        // Si ya es reproducible pero el usuario todavía no pulsó Reproducir ni Pausar en pantalla:
        // detener especulación innecesaria y esperar a que inicie la escucha.
        else -> false
      }

    val readinessFraction =
      when {
        canStartPlayback -> 1f
        initialTargetMs <= 0L -> 0f
        else -> (input.bufferedDurationMs.toFloat() / initialTargetMs.toFloat()).coerceIn(0f, 0.99f)
      }

    return BufferDecision(
      initialReadyTargetMs = initialTargetMs,
      aheadLookaheadTargetMs = activeLookaheadTargetMs,
      measuredRtf = if (input.hasRtfMeasurement) rtf else 0f,
      effectiveRtf = effectiveRtf,
      canStartPlayback = canStartPlayback,
      shouldGenerateMore = shouldGenerateMore,
      initialReadinessFraction = readinessFraction,
    )
  }
}
