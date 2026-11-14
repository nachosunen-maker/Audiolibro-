package com.example.domain.audio

import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WavAudioCodecTest {

  @get:Rule val tempFolder = TemporaryFolder()

  @Test
  fun `writePcmAsWav defaults to 22050 Hz for Piper and mergeWavFiles combines PCM payloads accurately`() {
    assertEquals(22_050, WavAudioCodec.DEFAULT_SAMPLE_RATE)

    val pcm1 = ByteArray(22_050 * 2) { (it % 120).toByte() } // 1.0s @ 22.05kHz 16-bit mono
    val pcm2 = ByteArray(11_025 * 2) { ((it * 3) % 120).toByte() } // 0.5s

    val file1 = tempFolder.newFile("part1.wav")
    val file2 = tempFolder.newFile("part2.wav")
    val mergedFile = tempFolder.newFile("merged.wav")

    val info1 = WavAudioCodec.writePcmAsWav(pcm1, file1)
    val info2 = WavAudioCodec.writePcmAsWav(pcm2, file2)

    assertEquals(22_050, info1.sampleRate)
    assertEquals(1000L, info1.durationMs)
    assertEquals(500L, info2.durationMs)
    assertTrue(WavAudioCodec.isValidWavFile(file1))
    assertTrue(WavAudioCodec.isValidWavFile(file2))

    val mergedInfo = WavAudioCodec.mergeWavFiles(listOf(file1, file2), mergedFile)
    assertEquals(22_050, mergedInfo.sampleRate)
    assertEquals((pcm1.size + pcm2.size).toLong(), mergedInfo.dataSizeBytes)
    assertEquals(1500L, mergedInfo.durationMs)
    assertEquals(44L + pcm1.size + pcm2.size, mergedFile.length())

    val streamOut = ByteArrayOutputStream()
    val streamedBytes = WavAudioCodec.mergeWavFilesToStream(listOf(file1, file2), streamOut)
    assertEquals(mergedFile.length(), streamedBytes)
    assertEquals(mergedFile.length().toInt(), streamOut.size())
  }

  @Test
  fun `mergeWavFiles safely resamples mixed 24kHz legacy and 22050Hz Piper WAVs without failing`() {
    val legacy24k = tempFolder.newFile("legacy_24k.wav")
    val piper22k = tempFolder.newFile("piper_22050.wav")
    val mergedMixed = tempFolder.newFile("merged_mixed.wav")

    val pcm24k = generateSinePcm16(sampleRate = 24_000, durationSeconds = 1.0, freqHz = 440.0)
    val pcm22k = generateSinePcm16(sampleRate = 22_050, durationSeconds = 1.0, freqHz = 440.0)

    WavAudioCodec.writePcmAsWav(pcm24k, legacy24k, sampleRate = 24_000)
    WavAudioCodec.writePcmAsWav(pcm22k, piper22k, sampleRate = 22_050)

    val mergedInfo = WavAudioCodec.mergeWavFiles(listOf(legacy24k, piper22k), mergedMixed)
    assertEquals(22_050, mergedInfo.sampleRate)
    assertEquals(1, mergedInfo.channels)
    assertEquals(16, mergedInfo.bitsPerSample)
    assertTrue(abs(mergedInfo.durationMs - 2000L) <= 10L)

    val streamOut = ByteArrayOutputStream()
    val streamedLen = WavAudioCodec.mergeWavFilesToStream(listOf(legacy24k, piper22k), streamOut)
    assertEquals(mergedMixed.length(), streamedLen)
  }

  @Test
  fun `readWavInfo rejects truncated or non-wav file`() {
    val badFile = tempFolder.newFile("bad.wav")
    badFile.writeBytes("RIFF1234WAVEfmt ".toByteArray())

    assertNull(WavAudioCodec.readWavInfo(badFile))
    assertFalse(WavAudioCodec.isValidWavFile(badFile))
  }

  private fun generateSinePcm16(sampleRate: Int, durationSeconds: Double, freqHz: Double): ByteArray {
    val count = (sampleRate * durationSeconds).toInt()
    val out = ByteArray(count * 2)
    for (i in 0 until count) {
      val sample = (sin(2.0 * Math.PI * freqHz * i / sampleRate) * 16000.0).toInt()
      out[i * 2] = (sample and 0xFF).toByte()
      out[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
    }
    return out
  }
}
