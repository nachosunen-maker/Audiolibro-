package com.example.domain.audio

import com.example.domain.model.NarratorVoice

data class SynthesizedAudioPayload(
  val bytes: ByteArray,
  val mimeType: String,
  val sampleRate: Int = WavAudioCodec.DEFAULT_SAMPLE_RATE,
  val channels: Int = WavAudioCodec.DEFAULT_CHANNELS,
  val bitsPerSample: Int = WavAudioCodec.DEFAULT_BITS_PER_SAMPLE,
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
