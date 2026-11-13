package com.example.domain.audio

import java.io.ByteArrayOutputStream
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
  fun `writePcmAsWav creates valid RIFF WAVE file and mergeWavFiles combines PCM payloads accurately`() {
    val pcm1 = ByteArray(24_000 * 2) { (it % 120).toByte() } // 1.0s @ 24kHz 16-bit mono
    val pcm2 = ByteArray(12_000 * 2) { ((it * 3) % 120).toByte() } // 0.5s

    val file1 = tempFolder.newFile("part1.wav")
    val file2 = tempFolder.newFile("part2.wav")
    val mergedFile = tempFolder.newFile("merged.wav")

    val info1 = WavAudioCodec.writePcmAsWav(pcm1, file1)
    val info2 = WavAudioCodec.writePcmAsWav(pcm2, file2)

    assertEquals(1000L, info1.durationMs)
    assertEquals(500L, info2.durationMs)
    assertTrue(WavAudioCodec.isValidWavFile(file1))
    assertTrue(WavAudioCodec.isValidWavFile(file2))

    val mergedInfo = WavAudioCodec.mergeWavFiles(listOf(file1, file2), mergedFile)
    assertEquals((pcm1.size + pcm2.size).toLong(), mergedInfo.dataSizeBytes)
    assertEquals(1500L, mergedInfo.durationMs)
    assertEquals(44L + pcm1.size + pcm2.size, mergedFile.length())

    val streamOut = ByteArrayOutputStream()
    val streamedBytes = WavAudioCodec.mergeWavFilesToStream(listOf(file1, file2), streamOut)
    assertEquals(mergedFile.length(), streamedBytes)
    assertEquals(mergedFile.length().toInt(), streamOut.size())
  }

  @Test
  fun `readWavInfo rejects truncated or non-wav file`() {
    val badFile = tempFolder.newFile("bad.wav")
    badFile.writeBytes("RIFF1234WAVEfmt ".toByteArray())

    assertNull(WavAudioCodec.readWavInfo(badFile))
    assertFalse(WavAudioCodec.isValidWavFile(badFile))
  }
}
