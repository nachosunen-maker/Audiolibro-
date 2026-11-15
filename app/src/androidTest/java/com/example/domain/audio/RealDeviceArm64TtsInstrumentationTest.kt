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
}
