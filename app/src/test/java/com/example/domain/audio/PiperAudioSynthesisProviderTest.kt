package com.example.domain.audio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.domain.model.NarratorVoice
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PiperAudioSynthesisProviderTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private lateinit var context: Context

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    File(context.filesDir, SherpaOnnxLocalTtsEngine.ASSET_MODEL_DIR).deleteRecursively()
  }

  @Test
  fun `bundled sharvard-medium assets config tokens and espeak data are packaged and verifiable offline`() {
    assertTrue(SherpaOnnxLocalTtsEngine.hasBundledModelAssets(context.assets))

    val jsonText =
      context.assets
        .open(
          "${SherpaOnnxLocalTtsEngine.ASSET_MODEL_DIR}/${SherpaOnnxLocalTtsEngine.CONFIG_FILENAME}"
        )
        .bufferedReader()
        .use { it.readText() }
    val root = JSONObject(jsonText)
    assertEquals(22_050, root.getJSONObject("audio").getInt("sample_rate"))
    assertEquals(2, root.getInt("num_speakers"))
    assertEquals("espeak", root.getString("phoneme_type"))
    assertEquals("es", root.getJSONObject("espeak").getString("voice"))

    val speakerMap = root.getJSONObject("speaker_id_map")
    assertEquals(0, speakerMap.getInt("M"))
    assertEquals(1, speakerMap.getInt("F"))
    assertEquals(speakerMap.getInt("M"), NarratorVoice.MALE.speakerId)
    assertEquals(speakerMap.getInt("F"), NarratorVoice.FEMALE.speakerId)

    val tokensText =
      context.assets
        .open(
          "${SherpaOnnxLocalTtsEngine.ASSET_MODEL_DIR}/${SherpaOnnxLocalTtsEngine.TOKENS_FILENAME}"
        )
        .bufferedReader()
        .use { it.readText() }
    assertTrue(tokensText.lineSequence().count { it.isNotBlank() } >= 100)

    val extractedDir = SherpaOnnxLocalTtsEngine.ensureEspeakDataCopied(context)
    assertTrue(File(extractedDir, "es_dict").exists())
    assertTrue(File(extractedDir, "phontab").exists())
    assertTrue(File(extractedDir, "phonindex").exists())
    assertTrue(File(extractedDir, "phondata").exists())

    // Second invocation reuses cached extraction cleanly
    val secondDir = SherpaOnnxLocalTtsEngine.ensureEspeakDataCopied(context)
    assertEquals(extractedDir.absolutePath, secondDir.absolutePath)
  }

  @Test
  fun `floatSamplesToPcm16Le clamps extreme and NaN samples into valid 16-bit little endian PCM`() {
    val samples = floatArrayOf(-1.5f, -1.0f, 0.0f, Float.NaN, 1.0f, 1.5f)
    val bytes = PiperAudioPcmEncoder.floatSamplesToPcm16Le(samples)
    assertEquals(12, bytes.size)

    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    assertEquals((-32767).toShort(), buf.short)
    assertEquals((-32767).toShort(), buf.short)
    assertEquals(0.toShort(), buf.short)
    assertEquals(0.toShort(), buf.short)
    assertEquals(32767.toShort(), buf.short)
    assertEquals(32767.toShort(), buf.short)
  }

  @Test
  fun `prepareSpanishNarrationText preserves Spanish punctuation and cleans invisible EPUB artifacts`() {
    val raw =
      "  Capítulo\u00AD primero\n\n—¿Quién llama a esta hora? —preguntó\u200B Inés.\n¡Qué sorpresa tan grande! España, año 1898…\n  "
    val prepared = PiperTextPreprocessor.prepareSpanishNarrationText(raw)
    assertFalse(prepared.contains('\u00AD'))
    assertFalse(prepared.contains('\u200B'))
    assertTrue(prepared.contains("¿Quién llama a esta hora?"))
    assertTrue(prepared.contains("¡Qué sorpresa tan grande!"))
    assertTrue(prepared.contains("Capítulo primero."))
    assertTrue(prepared.contains("1898…"))
  }

  @Test
  fun `provider initializes once synthesizes both speaker IDs 0 and 1 cleanly produces distinct WAVs and releases`() =
    runTest {
      val fakeEngine = RecordingFakeLocalTtsEngine()
      val provider = PiperAudioSynthesisProvider(context, fakeEngine)

      assertTrue(provider.isConfigured)
      assertEquals("es_ES-sharvard-medium", provider.modelId)

      val longSpanishText =
        "¿Sabes qué ocurrió aquella tarde de otoño en Madrid? ¡Nadie lo esperaba! " +
          "Las campanas sonaron tres veces; después, un silencio profundo envolvió la plaza mayor… " +
          "Capítulo tras capítulo, la historia avanzaba con paso firme y ritmo sereno."

      val maleResult =
        provider.synthesizeFragment(
          text = longSpanishText,
          voice = NarratorVoice.MALE,
          bookTitle = "Prueba",
          chapterTitle = "Capítulo 1",
        )
      val femaleResult =
        provider.synthesizeFragment(
          text = longSpanishText,
          voice = NarratorVoice.FEMALE,
          bookTitle = "Prueba",
          chapterTitle = "Capítulo 1",
        )

      assertTrue(maleResult.isSuccess)
      assertTrue(femaleResult.isSuccess)

      val malePayload = maleResult.getOrThrow()
      val femalePayload = femaleResult.getOrThrow()
      assertEquals(22_050, malePayload.sampleRate)
      assertEquals(22_050, femalePayload.sampleRate)
      assertEquals(1, malePayload.channels)
      assertEquals(16, malePayload.bitsPerSample)
      assertTrue(malePayload.bytes.isNotEmpty())
      assertTrue(femalePayload.bytes.isNotEmpty())
      assertFalse(malePayload.bytes.contentEquals(femalePayload.bytes))

      val maleWav = tempFolder.newFile("male_spk0.wav")
      val femaleWav = tempFolder.newFile("female_spk1.wav")
      val maleInfo =
        WavAudioCodec.writePcmAsWav(
          pcmBytes = malePayload.bytes,
          outputFile = maleWav,
          sampleRate = malePayload.sampleRate,
          channels = malePayload.channels,
          bitsPerSample = malePayload.bitsPerSample,
        )
      val femaleInfo =
        WavAudioCodec.writePcmAsWav(
          pcmBytes = femalePayload.bytes,
          outputFile = femaleWav,
          sampleRate = femalePayload.sampleRate,
          channels = femalePayload.channels,
          bitsPerSample = femalePayload.bitsPerSample,
        )

      assertEquals(22_050, maleInfo.sampleRate)
      assertEquals(22_050, femaleInfo.sampleRate)
      assertTrue(maleInfo.durationMs > 0L)
      assertTrue(femaleInfo.durationMs > 0L)
      assertNotEquals(maleWav.readBytes().toList(), femaleWav.readBytes().toList())

      // Parallel calls share single initialization cleanly
      val concurrent =
        (1..4).map { idx ->
          async {
            provider.synthesizeFragment(
              text = "Oración concurrente número $idx.",
              voice = if (idx % 2 == 0) NarratorVoice.MALE else NarratorVoice.FEMALE,
              bookTitle = "Prueba",
              chapterTitle = "Capítulo 2",
            )
          }
        }
      assertTrue(concurrent.awaitAll().all { it.isSuccess })
      assertEquals(1, fakeEngine.initCalls)
      assertEquals(listOf(0, 1), fakeEngine.observedSpeakerIds.take(2))

      provider.release()
      assertEquals(1, fakeEngine.releaseCalls)
    }

  @Test
  fun `provider surfaces empty output or engine failure safely without crashing`() = runTest {
    val failingEngine =
      object : LocalTtsEngine {
        override fun isModelReady(): Boolean = true
        override fun initialize() = error("Fallo simulado al abrir modelo")
        override fun sampleRate(): Int = 22_050
        override fun numSpeakers(): Int = 2
        override fun generate(text: String, speakerId: Int, speed: Float): LocalTtsAudio =
          LocalTtsAudio(FloatArray(0), 22_050)
        override fun release() {}
      }

    val provider = PiperAudioSynthesisProvider(context, failingEngine)
    val res =
      provider.synthesizeFragment(
        text = "Texto de prueba.",
        voice = NarratorVoice.FEMALE,
        bookTitle = "Libro",
        chapterTitle = "Cap 1",
      )
    assertTrue(res.isFailure)
    assertTrue(res.exceptionOrNull()?.message?.contains("Fallo simulado") == true)
  }

  private class RecordingFakeLocalTtsEngine : LocalTtsEngine {
    var initCalls = 0
    var releaseCalls = 0
    val observedSpeakerIds = mutableListOf<Int>()

    override fun isModelReady(): Boolean = true

    override fun initialize() {
      initCalls++
    }

    override fun sampleRate(): Int = 22_050

    override fun numSpeakers(): Int = 2

    override fun generate(text: String, speakerId: Int, speed: Float): LocalTtsAudio {
      observedSpeakerIds += speakerId
      val freq = if (speakerId == 0) 140.0 else 235.0
      val count = 22_050 / 2 // 0.5s sample wave differentiated per speakerId
      val samples =
        FloatArray(count) { i ->
          (0.6 * sin(2.0 * Math.PI * freq * i / 22_050.0)).toFloat()
        }
      return LocalTtsAudio(samples = samples, sampleRate = 22_050)
    }

    override fun release() {
      releaseCalls++
    }
  }
}
