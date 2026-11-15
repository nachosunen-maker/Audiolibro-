package com.example.domain.audio

import android.util.Log
import com.github.csukuangfj.piper.phonemize.PiperPhonemize
import com.github.csukuangfj.piper.phonemize.piperPhonemizeInitialize
import com.github.csukuangfj.piper.phonemize.piperPhonemizeText
import java.io.File

/**
 * Interfaz de fonemización texto-a-IPA para Piper TTS + sherpa-onnx 2.0.1.
 * En producción utiliza la biblioteca nativa `piper-phonemize` (eSpeak NG compilado para ARM64)
 * y devuelve listas de codepoints Unicode IPA sin delimitadores artificiales (`^`/`$`),
 * ya que `sherpa-onnx` inserta BOS, padding `_` y EOS internamente en C++.
 */
interface SpanishPhonemizer {
  fun initialize(dataDirOrRoot: String)

  fun phonemizeToCodepoints(text: String, voice: String = DEFAULT_VOICE): List<IntArray>

  companion object {
    const val DEFAULT_VOICE = "es"
    const val MAX_CODEPOINTS_PER_SENTENCE = 400
  }
}

data class NativePhonemizedSentence(
  val codepoints: IntArray,
  val previewIpa: String = "",
)

/**
 * Puente mínimo sobre `piper-phonemize` v1.5.9 que encapsula la carga de `libpiper-phonemize-jni.so`,
 * inicialización de `espeak-ng-data` y liberación determinista (`close()`) de `PiperPhonemizeResult`.
 */
interface NativePhonemizeBridge {
  fun getVersion(): String

  fun initialize(espeakDataDir: String): Int

  fun phonemize(text: String, voice: String): List<NativePhonemizedSentence>?
}

object DefaultNativePhonemizeBridge : NativePhonemizeBridge {
  override fun getVersion(): String = PiperPhonemize.nativeGetVersionStr()

  override fun initialize(espeakDataDir: String): Int =
    piperPhonemizeInitialize(dataDir = espeakDataDir)

  override fun phonemize(text: String, voice: String): List<NativePhonemizedSentence>? {
    val result = piperPhonemizeText(text = text, voice = voice) ?: return null
    return try {
      val sentenceCount = result.numSentences
      val sentences = ArrayList<NativePhonemizedSentence>(sentenceCount.coerceAtLeast(0))
      for (i in 0 until sentenceCount) {
        val cps = result.getPhonemes(i)
        val preview =
          if (i == 0) runCatching { result.getPhonemesAsString(i).take(120) }.getOrDefault("")
          else ""
        sentences += NativePhonemizedSentence(codepoints = cps, previewIpa = preview)
      }
      sentences
    } finally {
      runCatching { result.close() }.onFailure { err ->
        Log.w("PiperNativePhonemizer", "Error liberando PiperPhonemizeResult nativo", err)
      }
    }
  }
}

/**
 * Utilidad pura Kotlin para validar y dividir oraciones con demasiados codepoints
 * (por ejemplo, párrafos EPUB extensos sin signos de puntuación), manteniendo
 * las secuencias dentro de límites seguros de memoria para ONNX Runtime VITS.
 */
object PhonemeSentenceBatcher {
  private const val SPACE_CODEPOINT = ' '.code

  fun splitOversizedSentence(
    codepoints: IntArray,
    maxCodepoints: Int = SpanishPhonemizer.MAX_CODEPOINTS_PER_SENTENCE,
  ): List<IntArray> {
    if (codepoints.isEmpty()) return emptyList()
    if (codepoints.size <= maxCodepoints) return listOf(codepoints)

    val batches = mutableListOf<IntArray>()
    var start = 0
    while (start < codepoints.size) {
      val remaining = codepoints.size - start
      if (remaining <= maxCodepoints) {
        val tail = codepoints.copyOfRange(start, codepoints.size)
        if (tail.isNotEmpty()) batches += tail
        break
      }
      val splitAt = start + maxCodepoints
      var spaceIdx = -1
      for (idx in splitAt downTo (start + maxCodepoints / 2)) {
        if (codepoints[idx] == SPACE_CODEPOINT) {
          spaceIdx = idx
          break
        }
      }
      if (spaceIdx > start) {
        val slice = codepoints.copyOfRange(start, spaceIdx)
        if (slice.isNotEmpty()) batches += slice
        start = spaceIdx + 1
        while (start < codepoints.size && codepoints[start] == SPACE_CODEPOINT) {
          start++
        }
      } else {
        val slice = codepoints.copyOfRange(start, splitAt)
        if (slice.isNotEmpty()) batches += slice
        start = splitAt
      }
    }
    return batches
  }

  fun sanitizeCodepoints(sentences: List<IntArray>): List<IntArray> {
    val out = mutableListOf<IntArray>()
    for (sentence in sentences) {
      if (sentence.isEmpty()) continue
      out += splitOversizedSentence(sentence)
    }
    return out
  }
}

/**
 * Implementación nativa real basada en `com.github.csukuangfj.piper.phonemize` (v1.5.9)
 * y los datos binarios reales de eSpeak NG (`espeak-ng-data`).
 *
 * Contrato C++ verificado en `libpiper-phonemize-jni.so` y `PiperPhonemizeKt`:
 * `piperPhonemizeInitialize(dataDir)` pasa `dataDir` directamente a `espeak_Initialize()`,
 * que busca `<dataDir>/phontab`, `<dataDir>/phonindex`, `<dataDir>/phondata`, `<dataDir>/intonations`
 * y `<dataDir>/es_dict`. Por tanto, `dataDir` debe apuntar al directorio `.../espeak-ng-data`,
 * nunca a su carpeta padre.
 */
class PiperNativePhonemizer(
  private val bridge: NativePhonemizeBridge = DefaultNativePhonemizeBridge,
) : SpanishPhonemizer {

  private val initLock = Any()
  @Volatile private var initializedEspeakDir: String? = null

  internal fun currentInitializedPath(): String? = initializedEspeakDir

  override fun initialize(dataDirOrRoot: String) {
    val espeakDir = resolveAndValidateEspeakDataDir(dataDirOrRoot)
    val resolvedPath = espeakDir.absolutePath

    if (initializedEspeakDir == resolvedPath) return

    synchronized(initLock) {
      if (initializedEspeakDir == resolvedPath) return

      val version =
        try {
          bridge.getVersion()
        } catch (t: Throwable) {
          Log.e(TAG, "Error cargando libpiper-phonemize-jni.so (verificando versión nativa)", t)
          throw IllegalStateException(
            "No se pudo cargar la librería nativa de fonemización (libpiper-phonemize-jni.so): " +
              "${t.javaClass.simpleName}: ${t.message}",
            t,
          )
        }

      // espeak_Initialize dentro de std::call_once en C++ devuelve la frecuencia (22050 > 0) la 1.ª vez,
      // 0 si ya se inicializó en este proceso, y -1 en caso de error fatal al abrir phontab/es_dict.
      val rc =
        try {
          bridge.initialize(resolvedPath)
        } catch (t: Throwable) {
          Log.e(TAG, "Fallo en piperPhonemizeInitialize(espeakDir=$resolvedPath)", t)
          throw IllegalStateException(
            "Fallo nativo inicializando eSpeak NG (piper-phonemize $version) en $resolvedPath: ${t.message}",
            t,
          )
        }

      Log.i(
        TAG,
        "piperPhonemizeInitialize(espeakDataDir=$resolvedPath, version=$version) -> rc=$rc",
      )

      if (rc < 0) {
        throw IllegalStateException(
          "eSpeak NG / piper-phonemize devolvió error ($rc) al cargar datos desde $resolvedPath."
        )
      }
      initializedEspeakDir = resolvedPath
    }
  }

  override fun phonemizeToCodepoints(text: String, voice: String): List<IntArray> {
    val clean = text.trim()
    if (clean.isEmpty()) {
      throw IllegalArgumentException("No se puede fonemizar un fragmento vacío.")
    }
    val effectiveVoice = voice.ifBlank { SpanishPhonemizer.DEFAULT_VOICE }
    check(initializedEspeakDir != null) {
      "PiperNativePhonemizer no está inicializado antes de phonemizeToCodepoints()."
    }

    val rawSentences =
      try {
        bridge.phonemize(text = clean, voice = effectiveVoice)
      } catch (t: Throwable) {
        Log.e(TAG, "Fallo nativo en piperPhonemizeText(voice=$effectiveVoice, len=${clean.length})", t)
        throw IllegalStateException(
          "Error en conversión fonética eSpeak NG (voz=$effectiveVoice): ${t.message}",
          t,
        )
      }
        ?: throw IllegalStateException(
          "piperPhonemizeText devolvió null para el texto proporcionado (voz=$effectiveVoice, len=${clean.length})."
        )

    if (rawSentences.isEmpty()) {
      throw IllegalStateException(
        "piper-phonemize devolvió 0 oraciones fonéticas para un texto de ${clean.length} caracteres."
      )
    }

    val sentences = ArrayList<IntArray>(rawSentences.size)
    var totalPhonemes = 0
    var firstSentencePreview = ""
    for (entry in rawSentences) {
      if (entry.codepoints.isNotEmpty()) {
        if (sentences.isEmpty()) {
          firstSentencePreview = entry.previewIpa
        }
        totalPhonemes += entry.codepoints.size
        sentences += entry.codepoints
      }
    }

    val sanitized = PhonemeSentenceBatcher.sanitizeCodepoints(sentences)
    if (sanitized.isEmpty() || totalPhonemes == 0) {
      throw IllegalStateException(
        "piper-phonemize no produjo codepoints IPA válidos (oraciones=${rawSentences.size}, total=$totalPhonemes)."
      )
    }

    Log.d(
      TAG,
      "eSpeak NG (voz=$effectiveVoice): ${clean.length} chars -> ${rawSentences.size} oraciones " +
        "(${sanitized.size} lotes), $totalPhonemes fonemas. Muestra IPA[0]: \"$firstSentencePreview\"",
    )
    return sanitized
  }

  companion object {
    private const val TAG = "PiperNativePhonemizer"
    val REQUIRED_ESPEAK_FILES =
      listOf("es_dict", "phontab", "phonindex", "phondata", "intonations")

    fun resolveAndValidateEspeakDataDir(dataDirOrRoot: String): File {
      val candidate = File(dataDirOrRoot)
      val directHasTables =
        File(candidate, "phontab").isFile && File(candidate, "es_dict").isFile
      val nested = File(candidate, "espeak-ng-data")
      val espeakDir =
        when {
          directHasTables -> candidate
          nested.isDirectory -> nested
          else -> candidate
        }

      val missing =
        REQUIRED_ESPEAK_FILES.filter { name ->
          val f = File(espeakDir, name)
          !f.isFile || f.length() <= 0L
        }

      check(espeakDir.isDirectory && missing.isEmpty()) {
        "Directorio eSpeak NG inválido o incompleto en ${espeakDir.absolutePath} " +
          "(archivos faltantes o vacíos: ${missing.joinToString()})."
      }
      return espeakDir
    }
  }
}
