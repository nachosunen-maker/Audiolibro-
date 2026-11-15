package com.example.domain.audio

import com.example.data.local.ChapterChunkEntity
import com.example.domain.model.ChapterChunkStatus
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProgressiveChapterProgressTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private fun createValidWavFile(name: String, durationMs: Long = 1000L): File {
    val file = tempFolder.newFile(name)
    val pcmSamples = (22_050 * (durationMs / 1000.0)).toInt() * 2
    val pcm = ByteArray(pcmSamples.coerceAtLeast(200)) { 0 }
    WavAudioCodec.writePcmAsWav(pcm, file, sampleRate = 22_050)
    return file
  }

  private fun createChunk(
    chunkIndex: Int,
    text: String,
    status: ChapterChunkStatus = ChapterChunkStatus.PENDING,
    wavFile: File? = null,
    customWavPath: String? = null,
  ): ChapterChunkEntity {
    val path = customWavPath ?: wavFile?.absolutePath
    return ChapterChunkEntity(
      id = "chunk_$chunkIndex",
      bookId = "book_1",
      chapterId = "chapter_1",
      chapterIndex = 0,
      chunkIndex = chunkIndex,
      textContent = text,
      voiceName = "FEMALE",
      status = status.name,
      wavFilePath = path,
      byteSize = wavFile?.length() ?: 0L,
      durationMs = 1000L,
    )
  }

  @Test
  fun `test 1 fragments 900 900 100 characters first completed yields 47 percent`() {
    val wav0 = createValidWavFile("chunk_0.wav")
    val chunks =
      listOf(
        createChunk(0, "A".repeat(900), ChapterChunkStatus.COMPLETED, wav0),
        createChunk(1, "B".repeat(900), ChapterChunkStatus.PENDING),
        createChunk(2, "C".repeat(100), ChapterChunkStatus.PENDING),
      )

    val totalChars = ProgressiveChapterProgressCalculator.calculateTotalChars(chunks)
    val synthesizedChars = ProgressiveChapterProgressCalculator.calculateSynthesizedChars(chunks)
    val percent = ProgressiveChapterProgressCalculator.calculateChapterPercent(
      totalChars = totalChars,
      synthesizedChars = synthesizedChars,
      isCompleteChapterReady = false,
    )

    assertEquals(1900, totalChars)
    assertEquals(900, synthesizedChars)
    assertEquals(47, percent) // 900 / 1900 = 47.368% -> 47%

    val state = ProgressiveChapterState(
      bookId = "b1",
      chapterIndex = 0,
      totalChapterChars = totalChars,
      synthesizedChapterChars = synthesizedChars,
    )
    assertEquals(47, state.chapterPercent)
  }

  @Test
  fun `test 2 fragments 800 800 100 characters first completed yields 47 percent`() {
    val wav0 = createValidWavFile("chunk_0.wav")
    val chunks =
      listOf(
        createChunk(0, "A".repeat(800), ChapterChunkStatus.COMPLETED, wav0),
        createChunk(1, "B".repeat(800), ChapterChunkStatus.PENDING),
        createChunk(2, "C".repeat(100), ChapterChunkStatus.PENDING),
      )

    val totalChars = ProgressiveChapterProgressCalculator.calculateTotalChars(chunks)
    val synthesizedChars = ProgressiveChapterProgressCalculator.calculateSynthesizedChars(chunks)
    val percent = ProgressiveChapterProgressCalculator.calculateChapterPercent(
      totalChars = totalChars,
      synthesizedChars = synthesizedChars,
      isCompleteChapterReady = false,
    )

    assertEquals(1700, totalChars)
    assertEquals(800, synthesizedChars)
    assertEquals(47, percent) // 800 / 1700 = 47.058% -> 47% (differs from 33% chunk count!)
  }

  @Test
  fun `test 3 zero chunks completed yields 0 percent`() {
    val chunks =
      listOf(
        createChunk(0, "A".repeat(500), ChapterChunkStatus.PENDING),
        createChunk(1, "B".repeat(500), ChapterChunkStatus.PENDING),
      )

    val totalChars = ProgressiveChapterProgressCalculator.calculateTotalChars(chunks)
    val synthesizedChars = ProgressiveChapterProgressCalculator.calculateSynthesizedChars(chunks)
    val fraction = ProgressiveChapterProgressCalculator.calculateProgressFraction(
      totalChars = totalChars,
      synthesizedChars = synthesizedChars,
      isCompleteChapterReady = false,
    )
    val percent = ProgressiveChapterProgressCalculator.calculateChapterPercent(
      totalChars = totalChars,
      synthesizedChars = synthesizedChars,
      isCompleteChapterReady = false,
    )

    assertEquals(1000, totalChars)
    assertEquals(0, synthesizedChars)
    assertEquals(0f, fraction, 0.001f)
    assertEquals(0, percent)
  }

  @Test
  fun `test 4 all chunks completed and final consolidated wav validated yields 100 percent`() {
    val wav0 = createValidWavFile("c0.wav")
    val wav1 = createValidWavFile("c1.wav")
    val consolidatedWav = tempFolder.newFile("chapter_0.wav")
    WavAudioCodec.mergeWavFiles(listOf(wav0, wav1), consolidatedWav)

    assertTrue(WavAudioCodec.isValidWavFile(consolidatedWav))

    val chunks =
      listOf(
        createChunk(0, "Hello world", ChapterChunkStatus.COMPLETED, wav0),
        createChunk(1, "Second fragment", ChapterChunkStatus.COMPLETED, wav1),
      )

    val totalChars = ProgressiveChapterProgressCalculator.calculateTotalChars(chunks)
    val synthesizedChars = ProgressiveChapterProgressCalculator.calculateSynthesizedChars(chunks)

    // Before consolidation is marked ready, percentage is capped at 99
    val percentBeforeConsolidation = ProgressiveChapterProgressCalculator.calculateChapterPercent(
      totalChars = totalChars,
      synthesizedChars = synthesizedChars,
      isCompleteChapterReady = false,
    )
    assertEquals(99, percentBeforeConsolidation)

    // When consolidated and validated, it reaches exactly 100%
    val percentAfterConsolidation = ProgressiveChapterProgressCalculator.calculateChapterPercent(
      totalChars = totalChars,
      synthesizedChars = synthesizedChars,
      isCompleteChapterReady = true,
    )
    val fractionAfterConsolidation = ProgressiveChapterProgressCalculator.calculateProgressFraction(
      totalChars = totalChars,
      synthesizedChars = synthesizedChars,
      isCompleteChapterReady = true,
    )
    assertEquals(100, percentAfterConsolidation)
    assertEquals(1f, fractionAfterConsolidation, 0.001f)
  }

  @Test
  fun `test 5 chunk marked COMPLETED without wav file does not count towards progress`() {
    val chunkWithoutFile = createChunk(0, "A".repeat(500), ChapterChunkStatus.COMPLETED, wavFile = null)
    val chunkWithMissingPath = createChunk(
      1,
      "B".repeat(500),
      ChapterChunkStatus.COMPLETED,
      customWavPath = "/tmp/does_not_exist_${System.currentTimeMillis()}.wav",
    )

    assertFalse(ProgressiveChapterProgressCalculator.isChunkValidAndCompleted(chunkWithoutFile.status, chunkWithoutFile.wavFilePath))
    assertFalse(ProgressiveChapterProgressCalculator.isChunkValidAndCompleted(chunkWithMissingPath.status, chunkWithMissingPath.wavFilePath))

    val synthesizedChars = ProgressiveChapterProgressCalculator.calculateSynthesizedChars(
      listOf(chunkWithoutFile, chunkWithMissingPath)
    )
    assertEquals(0, synthesizedChars)
  }

  @Test
  fun `test 6 chunk with corrupt or under 44 bytes wav does not count towards progress`() {
    val corruptFile = tempFolder.newFile("corrupt.wav")
    corruptFile.writeBytes(ByteArray(20) { 0x55 }) // 20 bytes is less than WAV header minimum (44 bytes)

    assertFalse(WavAudioCodec.isValidWavFile(corruptFile))

    val chunk = createChunk(
      0,
      "Some meaningful text here",
      ChapterChunkStatus.COMPLETED,
      wavFile = corruptFile,
    )

    assertFalse(ProgressiveChapterProgressCalculator.isChunkValidAndCompleted(chunk.status, chunk.wavFilePath))
    val synthesizedChars = ProgressiveChapterProgressCalculator.calculateSynthesizedChars(listOf(chunk))
    assertEquals(0, synthesizedChars)
  }

  @Test
  fun `test 7 differing chunk lengths are character weighted not chunk count based`() {
    val wav0 = createValidWavFile("large.wav")
    val chunks =
      listOf(
        createChunk(0, "A".repeat(1500), ChapterChunkStatus.COMPLETED, wav0), // 75% of text
        createChunk(1, "B".repeat(500), ChapterChunkStatus.PENDING), // 25% of text
      )

    val totalChars = ProgressiveChapterProgressCalculator.calculateTotalChars(chunks)
    val synthesizedChars = ProgressiveChapterProgressCalculator.calculateSynthesizedChars(chunks)
    val percent = ProgressiveChapterProgressCalculator.calculateChapterPercent(
      totalChars = totalChars,
      synthesizedChars = synthesizedChars,
      isCompleteChapterReady = false,
    )

    // By chunk count this would be 1/2 = 50%, but character weighting proves it is 75%
    assertEquals(2000, totalChars)
    assertEquals(1500, synthesizedChars)
    assertEquals(75, percent)
  }

  @Test
  fun `test 8 empty chapter does not divide by zero and returns 0`() {
    val emptyChunks = emptyList<ChapterChunkEntity>()
    val totalChars = ProgressiveChapterProgressCalculator.calculateTotalChars(emptyChunks)
    val synthesizedChars = ProgressiveChapterProgressCalculator.calculateSynthesizedChars(emptyChunks)

    assertEquals(0, totalChars)
    assertEquals(0, synthesizedChars)

    val fraction = ProgressiveChapterProgressCalculator.calculateProgressFraction(
      totalChars = 0,
      synthesizedChars = 0,
      isCompleteChapterReady = false,
    )
    val percent = ProgressiveChapterProgressCalculator.calculateChapterPercent(
      totalChars = 0,
      synthesizedChars = 0,
      isCompleteChapterReady = false,
    )

    assertEquals(0f, fraction, 0.0001f)
    assertEquals(0, percent)

    val state = ProgressiveChapterState(
      bookId = "b1",
      chapterIndex = 0,
      totalChapterChars = 0,
      synthesizedChapterChars = 0,
    )
    assertEquals(0, state.chapterPercent)
    assertEquals(0f, state.overallChapterProgressFraction, 0.0001f)
  }

  @Test
  fun `test 9 recovery after process exit reconstructs exact percentage from chunks`() {
    val wav0 = createValidWavFile("persisted_0.wav")
    val wav1 = createValidWavFile("persisted_1.wav")

    // Database entities loaded upon cold app startup
    val loadedEntities =
      listOf(
        createChunk(0, "X".repeat(600), ChapterChunkStatus.COMPLETED, wav0),
        createChunk(1, "Y".repeat(300), ChapterChunkStatus.COMPLETED, wav1),
        createChunk(2, "Z".repeat(100), ChapterChunkStatus.PENDING),
      )

    val totalChars = ProgressiveChapterProgressCalculator.calculateTotalChars(loadedEntities)
    val synthesizedChars = ProgressiveChapterProgressCalculator.calculateSynthesizedChars(loadedEntities)
    val percent = ProgressiveChapterProgressCalculator.calculateChapterPercent(
      totalChars = totalChars,
      synthesizedChars = synthesizedChars,
      isCompleteChapterReady = false,
    )

    assertEquals(1000, totalChars)
    assertEquals(900, synthesizedChars)
    assertEquals(90, percent)
  }

  @Test
  fun `test 10 resume after pause does not duplicate chunks or characters`() {
    val wav0 = createValidWavFile("resume_0.wav")
    val existingChunks =
      listOf(
        createChunk(0, "A".repeat(500), ChapterChunkStatus.COMPLETED, wav0),
        createChunk(1, "B".repeat(500), ChapterChunkStatus.PENDING),
        createChunk(2, "C".repeat(500), ChapterChunkStatus.PENDING),
      )

    val initialTotal = ProgressiveChapterProgressCalculator.calculateTotalChars(existingChunks)
    val initialSynth = ProgressiveChapterProgressCalculator.calculateSynthesizedChars(existingChunks)
    assertEquals(1500, initialTotal)
    assertEquals(500, initialSynth)
    assertEquals(33, ProgressiveChapterProgressCalculator.calculateChapterPercent(initialTotal, initialSynth, false))

    // Synthesis completes second chunk
    val wav1 = createValidWavFile("resume_1.wav")
    val updatedChunks = existingChunks.toMutableList()
    updatedChunks[1] = existingChunks[1].copy(
      status = ChapterChunkStatus.COMPLETED.name,
      wavFilePath = wav1.absolutePath,
    )

    val updatedTotal = ProgressiveChapterProgressCalculator.calculateTotalChars(updatedChunks)
    val updatedSynth = ProgressiveChapterProgressCalculator.calculateSynthesizedChars(updatedChunks)
    assertEquals(1500, updatedTotal) // total remained strictly invariant
    assertEquals(1000, updatedSynth)
    assertEquals(67, ProgressiveChapterProgressCalculator.calculateChapterPercent(updatedTotal, updatedSynth, false))
  }

  @Test
  fun `test 11 early playback start progresses independently of playback position and initial readiness`() {
    val state = ProgressiveChapterState(
      bookId = "b1",
      chapterIndex = 0,
      phase = com.example.domain.model.ProgressiveChapterPhase.PLAYING_LOOKAHEAD,
      canStartPlayback = true,
      initialReadinessFraction = 1f,
      bufferedDurationMs = 20_000L,
      playedDurationMs = 12_000L, // user has listened 12 seconds
      aheadDurationMs = 8_000L,
      totalChapterChars = 2000,
      synthesizedChapterChars = 400, // 400 / 2000 = 20%
    )

    assertEquals(100, state.initialReadinessPercent)
    assertEquals(20, state.chapterPercent)
    assertEquals(0.20f, state.overallChapterProgressFraction, 0.001f)
    assertEquals(12_000L, state.playedDurationMs)
    assertEquals(8_000L, state.aheadDurationMs)
  }

  @Test
  fun `test 12 progressive addition of chunks is monotonically increasing without resetting playback`() {
    val totalChars = 1000
    var synthesizedChars = 0

    val p0 = ProgressiveChapterProgressCalculator.calculateChapterPercent(totalChars, synthesizedChars, false)
    assertEquals(0, p0)

    synthesizedChars += 400
    val p1 = ProgressiveChapterProgressCalculator.calculateChapterPercent(totalChars, synthesizedChars, false)
    assertEquals(40, p1)

    synthesizedChars += 400
    val p2 = ProgressiveChapterProgressCalculator.calculateChapterPercent(totalChars, synthesizedChars, false)
    assertEquals(80, p2)

    synthesizedChars += 200
    val p3 = ProgressiveChapterProgressCalculator.calculateChapterPercent(totalChars, synthesizedChars, false)
    assertEquals(99, p3) // capped at 99 before consolidation

    val pFinal = ProgressiveChapterProgressCalculator.calculateChapterPercent(totalChars, synthesizedChars, isCompleteChapterReady = true)
    assertEquals(100, pFinal)

    assertTrue(p0 <= p1 && p1 <= p2 && p2 <= p3 && p3 <= pFinal)
  }
}
