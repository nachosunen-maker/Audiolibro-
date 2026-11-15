package com.example.domain.audio

import android.content.Context
import android.os.Build
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.domain.model.NarratorVoice
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Prueba instrumentada reproducible para dispositivo físico / emulador ARM64 real.
 *
 * Verifica de extremo a extremo en hardware `arm64-v8a`:
 * 1. Inicialización real de `PiperNativePhonemizer` (`piper-phonemize` v1.5.9 + `espeak-ng-data`).
 * 2. Carga real de `SherpaOnnxLocalTtsEngine` (`sherpa-onnx` 2.0.1 + `es_ES-sharvard-medium`).
 * 3. Síntesis neuronal española con `speakerId = 0` (voz masculina) y `speakerId = 1` (voz femenina).
 * 4. Escritura WAV PCM-16 mono a 22.050 Hz con señal de audio no nula.
 * 5. Preparación de reproducción real en Media3 (`ExoPlayer`).
 */
@RunWith(AndroidJUnit4::class)
class RealDeviceArm64TtsInstrumentationTest {

  @Test
  fun arm64_endToEnd_phonemizer_sherpaOnnx_maleAndFemaleWav_and_media3Playback() {
    assumeTrue(
      "Esta prueba requiere un dispositivo Android real arm64-v8a",
      Build.SUPPORTED_ABIS.any { it.equals("arm64-v8a", ignoreCase = true) },
    )

    val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    val phonemizer = PiperNativePhonemizer()
    val engine = SherpaOnnxLocalTtsEngine(context, phonemizer)

    try {
      assertTrue(engine.isModelReady())
      engine.initialize()
      assertEquals(22_050, engine.sampleRate())
      assertEquals(2, engine.numSpeakers())

      val sampleText =
        "En un lugar de la Mancha, de cuyo nombre no quiero acordarme, vivía un hidalgo."
      val phonemes = phonemizer.phonemizeToCodepoints(sampleText, voice = "es")
      assertTrue(phonemes.isNotEmpty())
      assertTrue(phonemes.sumOf { it.size } > 10)

      val maleAudio =
        engine.generate(sampleText, speakerId = NarratorVoice.MALE.speakerId, speed = 1.0f)
      val femaleAudio =
        engine.generate(sampleText, speakerId = NarratorVoice.FEMALE.speakerId, speed = 1.0f)

      assertEquals(22_050, maleAudio.sampleRate)
      assertEquals(22_050, femaleAudio.sampleRate)
      assertTrue(maleAudio.samples.size > 11_025)
      assertTrue(femaleAudio.samples.size > 11_025)

      val malePeak = maleAudio.samples.maxOf { abs(it) }
      val femalePeak = femaleAudio.samples.maxOf { abs(it) }
      assertTrue("Voz masculina sin señal útil (peak=$malePeak)", malePeak > 0.01f)
      assertTrue("Voz femenina sin señal útil (peak=$femalePeak)", femalePeak > 0.01f)

      val malePcm = PiperAudioPcmEncoder.floatSamplesToPcm16Le(maleAudio.samples)
      val femalePcm = PiperAudioPcmEncoder.floatSamplesToPcm16Le(femaleAudio.samples)
      assertFalse(malePcm.contentEquals(femalePcm))

      val outDir = File(context.cacheDir, "arm64_instr_test").apply { mkdirs() }
      val maleWav = File(outDir, "sample_male_spk0.wav")
      val femaleWav = File(outDir, "sample_female_spk1.wav")
      val mergedWav = File(outDir, "sample_merged.wav")

      val maleInfo = WavAudioCodec.writePcmAsWav(malePcm, maleWav, 22_050, 1, 16)
      val femaleInfo = WavAudioCodec.writePcmAsWav(femalePcm, femaleWav, 22_050, 1, 16)
      val mergedInfo = WavAudioCodec.mergeWavFiles(listOf(maleWav, femaleWav), mergedWav)

      assertEquals(22_050, maleInfo.sampleRate)
      assertEquals(22_050, femaleInfo.sampleRate)
      assertTrue(mergedInfo.durationMs >= maleInfo.durationMs + femaleInfo.durationMs - 10L)

      val firstFrameSample =
        ByteBuffer.wrap(malePcm.copyOfRange(0, minOf(1024, malePcm.size)))
          .order(ByteOrder.LITTLE_ENDIAN)
      assertNotNull(firstFrameSample)

      // Verificar carga en Media3 ExoPlayer en el hilo principal
      runBlocking {
        withContext(Dispatchers.Main) {
          val player = ExoPlayer.Builder(context).build()
          try {
            player.setMediaItem(MediaItem.fromUri(mergedWav.toURI().toString()))
            player.prepare()
            assertTrue(player.mediaItemCount == 1)
          } finally {
            player.release()
          }
        }
      }

      outDir.deleteRecursively()
    } finally {
      engine.release()
    }
  }

  @Test
  fun arm64_benchmark_progressiveChunkTelemetry_initPhonemizeInferenceWavAndPlayback() {
    assumeTrue(
      "Esta prueba requiere un dispositivo Android real arm64-v8a",
      Build.SUPPORTED_ABIS.any { it.equals("arm64-v8a", ignoreCase = true) },
    )

    val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    val provider = PiperAudioSynthesisProvider(context)
    val outDir = File(context.cacheDir, "arm64_benchmark_test").apply { mkdirs() }

    val fragments =
      listOf(
        "El viento del sur doblaba los cipreses al caer la tarde.",
        "Cuando el reloj dio las doce, nadie se atrevió a abrir la puerta de madera vieja ni a pronunciar palabra alguna en toda la estancia silenciosa.",
        "Durante años recorrió ciudades portuarias buscando aquel libro encuadernado en tela oscura cuyo título apenas recordaba, convencido de que entre sus páginas aguardaba la única explicación posible.",
      )

    try {
      val t0 = System.nanoTime()
      val rtfEstimator = RtfEstimator()
      val results = mutableListOf<SynthesisTelemetry>()

      runBlocking {
        for ((index, text) in fragments.withIndex()) {
          val synthResult =
            provider.synthesizeFragment(
              text = text,
              voice = NarratorVoice.FEMALE,
              bookTitle = "Benchmark",
              chapterTitle = "Cap 1",
            )
          assertTrue("Fallo en fragmento $index", synthResult.isSuccess)
          val payload = synthResult.getOrThrow()
          val telemetry = payload.telemetry
          assertNotNull(telemetry)

          val wavFile = File(outDir, "bench_part_$index.wav")
          val writeStartNs = System.nanoTime()
          val wavInfo =
            WavAudioCodec.writePcmAsWav(
              pcmBytes = payload.bytes,
              outputFile = wavFile,
              sampleRate = payload.sampleRate,
              channels = payload.channels,
              bitsPerSample = payload.bitsPerSample,
            )
          val wavWriteMs = ((System.nanoTime() - writeStartNs) / 1_000_000L).coerceAtLeast(0L)
          val updatedTelemetry = telemetry!!.copy(wavWriteMs = wavWriteMs, audioDurationMs = wavInfo.durationMs)
          results += updatedTelemetry
          val synthMs =
            (updatedTelemetry.phonemizationMs +
              updatedTelemetry.inferenceMs +
              updatedTelemetry.pcmEncodingMs +
              wavWriteMs)
              .coerceAtLeast(1L)
          rtfEstimator.recordFragment(synthMs, wavInfo.durationMs)
        }
      }

      val prepStartNs = System.nanoTime()
      runBlocking {
        withContext(Dispatchers.Main) {
          val player = ExoPlayer.Builder(context).build()
          try {
            val items =
              fragments.indices.map { idx ->
                MediaItem.fromUri(File(outDir, "bench_part_$idx.wav").toURI().toString())
              }
            player.setMediaItems(items)
            player.prepare()
            assertEquals(fragments.size, player.mediaItemCount)
          } finally {
            player.release()
          }
        }
      }
      val playbackPrepareMs = ((System.nanoTime() - prepStartNs) / 1_000_000L).coerceAtLeast(0L)
      val totalBenchmarkMs = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(0L)
      val measuredRtf = rtfEstimator.currentRtf()

      assertTrue(measuredRtf > 0f)
      assertTrue(results.all { it.audioDurationMs > 0L && it.inferenceMs >= 0L })

      android.util.Log.i(
        "RealDeviceArm64Bench",
        "initMs=${results.first().initializationMs}, " +
          "phonMs=${results.sumOf { it.phonemizationMs }}, " +
          "inferMs=${results.sumOf { it.inferenceMs }}, " +
          "wavWriteMs=${results.sumOf { it.wavWriteMs }}, " +
          "playbackPrepareMs=$playbackPrepareMs, " +
          "audioMs=${results.sumOf { it.audioDurationMs }}, " +
          "RTF=$measuredRtf, totalMs=$totalBenchmarkMs",
      )
    } finally {
      outDir.deleteRecursively()
      provider.release()
    }
  }
}
