package com.example.domain.audio

import android.content.Context
import android.util.Log
import com.example.domain.model.NarratorVoice
import com.example.firebase.FirebaseConfigHelper
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

data class SynthesizedAudioPayload(
  val bytes: ByteArray,
  val mimeType: String,
  val sampleRate: Int = WavAudioCodec.DEFAULT_SAMPLE_RATE,
  val channels: Int = WavAudioCodec.DEFAULT_CHANNELS,
  val bitsPerSample: Int = WavAudioCodec.DEFAULT_BITS_PER_SAMPLE,
)

class FirebaseSetupRequiredException(message: String) : IllegalStateException(message)

/**
 * Contrato de síntesis de voz mediante Gemini TTS / Firebase AI Logic.
 */
interface AudioSynthesisProvider {
  val isConfigured: Boolean
  val providerName: String
  val modelId: String

  suspend fun synthesizeFragment(
    text: String,
    voice: NarratorVoice,
    bookTitle: String,
    chapterTitle: String,
  ): Result<SynthesizedAudioPayload>
}

class FirebaseAiAudioSynthesisProvider(private val context: Context) : AudioSynthesisProvider {

  companion object {
    private const val TAG = "FirebaseAiTts"
    const val MODEL_GEMINI_TTS = "gemini-3.8-flash-tts"
  }

  override val isConfigured: Boolean
    get() = FirebaseConfigHelper.hasGoogleServicesConfig(context)

  override val providerName: String = "Firebase AI Logic"
  override val modelId: String = MODEL_GEMINI_TTS

  @OptIn(PublicPreviewAPI::class)
  override suspend fun synthesizeFragment(
    text: String,
    voice: NarratorVoice,
    bookTitle: String,
    chapterTitle: String,
  ): Result<SynthesizedAudioPayload> {
    if (!isConfigured) {
      return Result.failure(
        FirebaseSetupRequiredException(
          "Falta activar Firebase AI Logic y Firebase App Check en el proyecto (no existe google-services.json). " +
            "Por seguridad no se introducen claves API en la app ni se simulan audios."
        )
      )
    }

    val cleanText = text.trim()
    if (cleanText.isEmpty()) {
      return Result.failure(IllegalArgumentException("El fragmento de texto está vacío."))
    }

    return try {
      val model =
        Firebase.ai(backend = GenerativeBackend.googleAI())
          .generativeModel(
            modelName = MODEL_GEMINI_TTS,
            generationConfig =
              generationConfig {
                responseModalities = listOf(ResponseModality.AUDIO)
                speechConfig = SpeechConfig(voice = Voice(voice.geminiVoiceName))
              },
          )

      val response = withTimeout(60_000L) { model.generateContent(cleanText) }
      val firstCandidate = response.candidates.firstOrNull()
      val audioPart =
        firstCandidate
          ?.content
          ?.parts
          ?.filterIsInstance<InlineDataPart>()
          ?.firstOrNull { it.mimeType.startsWith("audio/", ignoreCase = true) }
          ?: run {
            val finishReason = firstCandidate?.finishReason?.name
            val detail =
              if (finishReason != null) {
                "Gemini no devolvió un bloque de audio válido (motivo: $finishReason)."
              } else {
                "Gemini no devolvió un bloque de audio válido."
              }
            return Result.failure(IllegalStateException(detail))
          }

      if (audioPart.inlineData.isEmpty()) {
        return Result.failure(IllegalStateException("Gemini devolvió un bloque de audio vacío."))
      }

      if (!isSupportedAudioMimeType(audioPart.mimeType)) {
        return Result.failure(
          IllegalStateException(
            "Formato de audio no compatible (${audioPart.mimeType}). Se esperaba PCM o WAV."
          )
        )
      }

      val parsedRate = parseRateFromMimeType(audioPart.mimeType) ?: WavAudioCodec.DEFAULT_SAMPLE_RATE
      val parsedChannels =
        parseChannelsFromMimeType(audioPart.mimeType) ?: WavAudioCodec.DEFAULT_CHANNELS
      Result.success(
        SynthesizedAudioPayload(
          bytes = audioPart.inlineData,
          mimeType = audioPart.mimeType,
          sampleRate = parsedRate,
          channels = parsedChannels,
        )
      )
    } catch (e: TimeoutCancellationException) {
      Log.e(TAG, "Tiempo de espera agotado al sintetizar fragmento", e)
      Result.failure(
        IllegalStateException(
          "La generación del fragmento tardó más de 60 segundos. Puedes reanudar desde el punto guardado."
        )
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Error en Firebase AI Logic TTS", e)
      Result.failure(
        IllegalStateException(
          e.localizedMessage
            ?: "No se pudo generar el audio con Firebase AI Logic. Verifica App Check y la conexión."
        )
      )
    }
  }

  private fun isSupportedAudioMimeType(mimeType: String): Boolean {
    val base = mimeType.substringBefore(';').trim().lowercase()
    return base == "audio/l16" ||
      base == "audio/pcm" ||
      base == "audio/raw" ||
      base == "audio/wav" ||
      base == "audio/x-wav" ||
      base == "audio/wave"
  }

  private fun parseRateFromMimeType(mimeType: String): Int? {
    val match = Regex("rate=(\\d+)", RegexOption.IGNORE_CASE).find(mimeType)
    return match?.groupValues?.getOrNull(1)?.toIntOrNull()
  }

  private fun parseChannelsFromMimeType(mimeType: String): Int? {
    val match = Regex("channels=(\\d+)", RegexOption.IGNORE_CASE).find(mimeType)
    return match?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..2 }
  }
}
