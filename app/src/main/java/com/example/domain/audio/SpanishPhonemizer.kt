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
  fun initialize(dataRootDir: String)

  fun phonemizeToCodepoints(text: String, voice: String = DEFAULT_VOICE): List<IntArray>

  companion object {
    const val DEFAULT_VOICE = "es"
    const val MAX_CODEPOINTS_PER_SENTENCE = 400
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
      var splitAt = start + maxCodepoints
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
 */
class PiperNativePhonemizer : SpanishPhonemizer {

  @Volatile private var initializedRootDir: String? = null

  override fun initialize(dataRootDir: String) {
    val rootDir = File(dataRootDir)
    val espeakDir = File(rootDir, "espeak-ng-data")
    val esDict = File(espeakDir, "es_dict")
    val phontab = File(espeakDir, "phontab")
    check(espeakDir.isDirectory && esDict.isFile && phontab.isFile) {
      "Directorio eSpeak NG inválido o incompleto en ${espeakDir.absolutePath} " +
        "(esDict=${esDict.exists()}, phontab=${phontab.exists()})."
    }

    if (initializedRootDir == rootDir.absolutePath) return

    val version =
      try {
        PiperPhonemize.nativeGetVersionStr()
      } catch (t: Throwable) {
        Log.e(TAG, "Error cargando libpiper-phonemize-jni.so (verificando versión nativa)", t)
        throw IllegalStateException(
          "No se pudo cargar la librería nativa de fonemización (libpiper-phonemize-jni.so): " +
            "${t.javaClass.simpleName}: ${t.message}",
          t,
        )
      }

    // espeak_Initialize dentro de std::call_once devuelve la frecuencia (22050 > 0) la 1.ª vez,
    // 0 en invocaciones posteriores ya inicializadas, y -1 en caso de error fatal.
    val rc =
      try {
        piperPhonemizeInitialize(rootDir.absolutePath)
      } catch (t: Throwable) {
        Log.e(TAG, "Fallo en piperPhonemizeInitialize(${rootDir.absolutePath})", t)
        throw IllegalStateException(
          "Fallo nativo inicializando eSpeak NG (piper-phonemize $version): ${t.message}",
          t,
        )
      }

    Log.i(
      TAG,
      "piperPhonemizeInitialize(dataRoot=${rootDir.absolutePath}, version=$version) -> rc=$rc",
    )

    if (rc < 0) {
      throw IllegalStateException(
        "eSpeak NG / piper-phonemize devolvió error ($rc) al cargar datos desde ${espeakDir.absolutePath}."
      )
    }
    initializedRootDir = rootDir.absolutePath
  }

  override fun phonemizeToCodepoints(text: String, voice: String): List<IntArray> {
    val clean = text.trim()
    if (clean.isEmpty()) {
      throw IllegalArgumentException("No se puede fonemizar un fragmento vacío.")
    }
    val effectiveVoice = voice.ifBlank { SpanishPhonemizer.DEFAULT_VOICE }
    check(initializedRootDir != null) {
      "PiperNativePhonemizer no está inicializado antes de phonemizeToCodepoints()."
    }

    val result =
      try {
        piperPhonemizeText(text = clean, voice = effectiveVoice)
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

    try {
      val sentenceCount = result.numSentences
      if (sentenceCount <= 0) {
        throw IllegalStateException(
          "piper-phonemize devolvió 0 oraciones fonéticas para un texto de ${clean.length} caracteres."
        )
      }

      val sentences = ArrayList<IntArray>(sentenceCount)
      var totalPhonemes = 0
      var firstSentencePreview = ""
      for (i in 0 until sentenceCount) {
        val cps = result.getPhonemes(i)
        if (cps.isNotEmpty()) {
          if (sentences.isEmpty()) {
            firstSentencePreview = runCatching { result.getPhonemesAsString(i).take(120) }.getOrDefault("")
          }
          totalPhonemes += cps.size
          sentences += cps
        }
      }

      val sanitized = PhonemeSentenceBatcher.sanitizeCodepoints(sentences)
      if (sanitized.isEmpty() || totalPhonemes == 0) {
        throw IllegalStateException(
          "piper-phonemize no produjo codepoints IPA válidos (oraciones=$sentenceCount, total=$totalPhonemes)."
        )
      }

      Log.d(
        TAG,
        "eSpeak NG (voz=$effectiveVoice): ${clean.length} chars -> $sentenceCount oraciones " +
          "(${sanitized.size} lotes), $totalPhonemes fonemas. Muestra IPA[0]: \"$firstSentencePreview\"",
      )
      return sanitized
    } finally {
      runCatching { result.close() }.onFailure { err ->
        Log.w(TAG, "Error liberando PiperPhonemizeResult nativo", err)
      }
    }
  }

  companion object {
    private const val TAG = "PiperNativePhonemizer"
  }
}
