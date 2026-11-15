package com.example.domain.audio

import com.example.domain.model.NarratorVoice

data class SynthesisTelemetry(
  val initializationMs: Long = 0L,
  val phonemizationMs: Long = 0L,
  val inferenceMs: Long = 0L,
  val pcmEncodingMs: Long = 0L,
  val wavWriteMs: Long = 0L,
  val audioDurationMs: Long = 0L,
  val sampleCount: Int = 0,
  val textChars: Int = 0,
) {
  val synthesisTotalMs: Long
    get() = (phonemizationMs + inferenceMs + pcmEncodingMs).coerceAtLeast(0L)

  val realTimeFactor: Float
    get() =
      if (audioDurationMs > 0L) synthesisTotalMs.toFloat() / audioDurationMs.toFloat() else 0f
}

data class SynthesizedAudioPayload(
  val bytes: ByteArray,
  val mimeType: String,
  val sampleRate: Int = WavAudioCodec.DEFAULT_SAMPLE_RATE,
  val channels: Int = WavAudioCodec.DEFAULT_CHANNELS,
  val bitsPerSample: Int = WavAudioCodec.DEFAULT_BITS_PER_SAMPLE,
  val telemetry: SynthesisTelemetry? = null,
)

/**
 * Contrato de síntesis de voz local sin conexión mediante Piper VITS + sherpa-onnx.
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

  fun release() {}
}
