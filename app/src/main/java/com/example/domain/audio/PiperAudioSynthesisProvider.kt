package com.example.domain.audio

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.example.domain.model.NarratorVoice
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class LocalTtsAudio(
  val samples: FloatArray,
  val sampleRate: Int,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is LocalTtsAudio) return false
    return sampleRate == other.sampleRate && samples.contentEquals(other.samples)
  }

  override fun hashCode(): Int = 31 * samples.contentHashCode() + sampleRate
}

interface LocalTtsEngine {
  fun isModelReady(): Boolean

  fun initialize()

  fun sampleRate(): Int

  fun numSpeakers(): Int

  fun generate(text: String, speakerId: Int, speed: Float = 1.0f): LocalTtsAudio

  fun release()
}

object PiperTextPreprocessor {
  private val INVISIBLE_EPUB_CHARS = Regex("[\u00AD\u200B\u200C\u200D\uFEFF]")
  private val CONTROL_CHARS = Regex("[\\p{Cntrl}&&[^\n\t ]]")
  private val MULTI_NEWLINES = Regex("\\s*\\n+\\s*")
  private val MULTI_SPACES = Regex("[ \\t]{2,}")

  fun prepareSpanishNarrationText(rawText: String): String {
    val cleaned =
      rawText
        .replace(INVISIBLE_EPUB_CHARS, "")
        .replace(CONTROL_CHARS, " ")
        .trim()
    if (cleaned.isEmpty()) return ""

    val paragraphs = cleaned.split(MULTI_NEWLINES).map { it.trim() }.filter { it.isNotEmpty() }
    if (paragraphs.isEmpty()) return ""

    val joined = StringBuilder()
    for ((idx, paragraph) in paragraphs.withIndex()) {
      if (idx > 0) {
        val lastChar = joined.lastOrNull()
        if (lastChar != null && lastChar !in ".!?…:;—") {
          joined.append('.')
        }
        joined.append(' ')
      }
      joined.append(paragraph)
    }
    return joined.toString().replace(MULTI_SPACES, " ").trim()
  }
}

object PiperAudioPcmEncoder {
  fun floatSamplesToPcm16Le(samples: FloatArray): ByteArray {
    val pcm = ByteArray(samples.size * 2)
    var offset = 0
    for (sample in samples) {
      val finite = if (sample.isFinite()) sample else 0f
      val clamped = finite.coerceIn(-1.0f, 1.0f)
      val intSample =
        (clamped * 32767.0f).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
      pcm[offset++] = (intSample and 0xFF).toByte()
      pcm[offset++] = ((intSample shr 8) and 0xFF).toByte()
    }
    return pcm
  }
}

class SherpaOnnxLocalTtsEngine(private val context: Context) : LocalTtsEngine {

  @Volatile private var offlineTts: OfflineTts? = null

  override fun isModelReady(): Boolean = hasBundledModelAssets(context.assets)

  override fun initialize() {
    if (offlineTts != null) return
    check(isModelReady()) {
      "Faltan los archivos locales del modelo $MODEL_ID en assets/$ASSET_MODEL_DIR."
    }

    val espeakDataDir = ensureEspeakDataCopied(context)
    val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    val vitsConfig =
      OfflineTtsVitsModelConfig(
        model = "$ASSET_MODEL_DIR/$MODEL_FILENAME",
        tokens = "$ASSET_MODEL_DIR/$TOKENS_FILENAME",
        lexicon = "",
        dataDir = espeakDataDir.absolutePath,
        noiseScale = 0.667f,
        noiseScaleW = 0.8f,
        lengthScale = 1.0f,
      )

    val ttsConfig =
      OfflineTtsConfig(
        model =
          OfflineTtsModelConfig(
            vits = vitsConfig,
            numThreads = threads,
            debug = false,
            provider = "cpu",
          ),
        maxNumSentences = 1,
        silenceScale = 0.25f,
      )

    val engine = OfflineTts(assetManager = context.assets, config = ttsConfig)
    val rate = engine.sampleRate()
    val speakers = engine.numSpeakers()
    if (rate <= 0 || speakers < 2) {
      engine.release()
      error(
        "El modelo local $MODEL_ID se inicializó con parámetros inválidos (sampleRate=$rate, speakers=$speakers)."
      )
    }
    offlineTts = engine
    Log.i(TAG, "Piper VITS inicializado localmente: model=$MODEL_ID, rate=$rate Hz, speakers=$speakers, threads=$threads")
  }

  override fun sampleRate(): Int = offlineTts?.sampleRate() ?: DEFAULT_SAMPLE_RATE

  override fun numSpeakers(): Int = offlineTts?.numSpeakers() ?: 2

  override fun generate(text: String, speakerId: Int, speed: Float): LocalTtsAudio {
    val engine =
      offlineTts
        ?: throw IllegalStateException("El motor local sherpa-onnx no está inicializado.")
    val generated = engine.generate(text = text, sid = speakerId, speed = speed)
    return LocalTtsAudio(samples = generated.samples, sampleRate = generated.sampleRate)
  }

  override fun release() {
    val current = offlineTts
    offlineTts = null
    if (current != null) {
      runCatching { current.release() }.onFailure { err ->
        Log.w(TAG, "Error liberando recursos nativos de OfflineTts", err)
      }
    }
  }

  companion object {
    private const val TAG = "SherpaPiperTts"
    const val MODEL_ID = "es_ES-sharvard-medium"
    const val DEFAULT_SAMPLE_RATE = 22_050
    const val ASSET_MODEL_DIR = "tts/vits-piper-es_ES-sharvard-medium"
    const val MODEL_FILENAME = "es_ES-sharvard-medium.onnx"
    const val CONFIG_FILENAME = "es_ES-sharvard-medium.onnx.json"
    const val TOKENS_FILENAME = "tokens.txt"
    const val ESPEAK_DIR_NAME = "espeak-ng-data"
    private const val EXTRACTION_MARKER_FILE = ".espeak_ng_ready_v1"

    fun hasBundledModelAssets(assets: AssetManager): Boolean {
      return assetExists(assets, "$ASSET_MODEL_DIR/$MODEL_FILENAME") &&
        assetExists(assets, "$ASSET_MODEL_DIR/$CONFIG_FILENAME") &&
        assetExists(assets, "$ASSET_MODEL_DIR/$TOKENS_FILENAME") &&
        assetExists(assets, "$ASSET_MODEL_DIR/$ESPEAK_DIR_NAME/es_dict") &&
        assetExists(assets, "$ASSET_MODEL_DIR/$ESPEAK_DIR_NAME/phontab")
    }

    private fun assetExists(assets: AssetManager, assetPath: String): Boolean {
      return runCatching {
          assets.open(assetPath).use {}
          true
        }
        .getOrDefault(false)
    }

    internal fun ensureEspeakDataCopied(context: Context): File {
      val baseTargetDir = File(context.filesDir, "$ASSET_MODEL_DIR/$ESPEAK_DIR_NAME")
      val markerFile = File(baseTargetDir, EXTRACTION_MARKER_FILE)
      val requiredEsDict = File(baseTargetDir, "es_dict")
      val requiredPhontab = File(baseTargetDir, "phontab")
      if (markerFile.exists() && requiredEsDict.exists() && requiredPhontab.exists()) {
        return baseTargetDir
      }

      if (baseTargetDir.exists()) {
        baseTargetDir.deleteRecursively()
      }
      baseTargetDir.mkdirs()

      copyAssetTree(
        assets = context.assets,
        assetPath = "$ASSET_MODEL_DIR/$ESPEAK_DIR_NAME",
        targetDir = baseTargetDir,
      )

      check(requiredEsDict.exists() && requiredPhontab.exists()) {
        "No se pudieron extraer los datos fonéticos de eSpeak NG para $MODEL_ID."
      }
      markerFile.writeText("ok\n")
      return baseTargetDir
    }

    private fun copyAssetTree(assets: AssetManager, assetPath: String, targetDir: File) {
      val children = assets.list(assetPath).orEmpty()
      if (children.isEmpty()) {
        // Leaf file (or empty directory)
        val inputStream =
          try {
            assets.open(assetPath)
          } catch (_: java.io.FileNotFoundException) {
            targetDir.mkdirs()
            return
          }
        targetDir.parentFile?.mkdirs()
        val tmp = File(targetDir.parentFile, "${targetDir.name}.tmp")
        inputStream.use { input ->
          FileOutputStream(tmp).use { output -> input.copyTo(output) }
        }
        if (targetDir.exists()) targetDir.delete()
        if (!tmp.renameTo(targetDir)) {
          tmp.copyTo(targetDir, overwrite = true)
          tmp.delete()
        }
        return
      }

      targetDir.mkdirs()
      for (child in children) {
        copyAssetTree(assets, "$assetPath/$child", File(targetDir, child))
      }
    }
  }
}

class PiperAudioSynthesisProvider(
  private val context: Context,
  private val engine: LocalTtsEngine = SherpaOnnxLocalTtsEngine(context),
) : AudioSynthesisProvider {

  private val mutex = Mutex()
  @Volatile private var initialized = false

  override val isConfigured: Boolean
    get() = engine.isModelReady()

  override val providerName: String = "Piper VITS + sherpa-onnx (Local)"
  override val modelId: String = SherpaOnnxLocalTtsEngine.MODEL_ID

  override suspend fun synthesizeFragment(
    text: String,
    voice: NarratorVoice,
    bookTitle: String,
    chapterTitle: String,
  ): Result<SynthesizedAudioPayload> {
    if (!isConfigured) {
      return Result.failure(
        IllegalStateException(
          "Los archivos locales del modelo de voz ($modelId) no están disponibles en la aplicación."
        )
      )
    }

    val preparedText = PiperTextPreprocessor.prepareSpanishNarrationText(text)
    if (preparedText.isEmpty()) {
      return Result.failure(IllegalArgumentException("El fragmento de texto está vacío."))
    }

    return withContext(Dispatchers.IO) {
      try {
        currentCoroutineContext().ensureActive()
        mutex.withLock {
          currentCoroutineContext().ensureActive()
          if (!initialized) {
            engine.initialize()
            initialized = true
          }

          val speakerCount = engine.numSpeakers()
          if (voice.speakerId !in 0 until speakerCount) {
            Result.failure(
              IllegalArgumentException(
                "El speakerId ${voice.speakerId} no es válido para $modelId (voces disponibles: $speakerCount)."
              )
            )
          } else {
            val generated =
              engine.generate(text = preparedText, speakerId = voice.speakerId, speed = 1.0f)
            currentCoroutineContext().ensureActive()

            if (generated.samples.isEmpty()) {
              Result.failure(
                IllegalStateException("El motor local devolvió un bloque de audio vacío.")
              )
            } else {
              val sampleRate =
                if (generated.sampleRate > 0) generated.sampleRate
                else engine.sampleRate().takeIf { it > 0 } ?: SherpaOnnxLocalTtsEngine.DEFAULT_SAMPLE_RATE

              val pcmBytes = PiperAudioPcmEncoder.floatSamplesToPcm16Le(generated.samples)
              Result.success(
                SynthesizedAudioPayload(
                  bytes = pcmBytes,
                  mimeType = "audio/L16;rate=$sampleRate;channels=1",
                  sampleRate = sampleRate,
                  channels = WavAudioCodec.DEFAULT_CHANNELS,
                  bitsPerSample = WavAudioCodec.DEFAULT_BITS_PER_SAMPLE,
                )
              )
            }
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (t: Throwable) {
        Log.e(TAG, "Error en síntesis local Piper/sherpa-onnx", t)
        Result.failure(
          IllegalStateException(
            t.localizedMessage ?: "No se pudo sintetizar el fragmento de audio en el dispositivo.",
            t,
          )
        )
      }
    }
  }

  override fun release() {
    initialized = false
    engine.release()
  }

  companion object {
    private const val TAG = "PiperLocalTts"
  }
}
