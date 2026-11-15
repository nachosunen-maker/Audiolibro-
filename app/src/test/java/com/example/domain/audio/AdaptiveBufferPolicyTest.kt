package com.example.domain.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveBufferPolicyTest {

  private val standardDevice =
    DeviceResourceProfile(
      isLowRamDevice = false,
      availableMemoryMb = 2048L,
      availableStorageMb = 4096L,
      cpuCores = 8,
      isPowerSaveMode = false,
      reducedMotionPreferred = false,
    )

  private val constrainedDevice =
    DeviceResourceProfile(
      isLowRamDevice = true,
      availableMemoryMb = 280L,
      availableStorageMb = 80L,
      cpuCores = 2,
      isPowerSaveMode = true,
      reducedMotionPreferred = false,
    )

  @Test
  fun `fast device requires smaller initial buffer than slow device and never uses huge fixed targets`() {
    val fastDecision =
      AdaptiveBufferPolicy.evaluate(
        BufferEvaluationInput(
          deviceProfile = standardDevice,
          measuredRtf = 0.30f,
          hasRtfMeasurement = true,
          playbackSpeed = 1.0f,
          chunksCompleted = 2,
          totalChunksInChapter = 10,
          bufferedDurationMs = 9_000L,
          aheadDurationMs = 9_000L,
          remainingChunks = 8,
          isPlaying = false,
          isPausedByUser = false,
          isSessionActive = true,
        )
      )

    val slowDecision =
      AdaptiveBufferPolicy.evaluate(
        BufferEvaluationInput(
          deviceProfile = standardDevice,
          measuredRtf = 1.45f,
          hasRtfMeasurement = true,
          playbackSpeed = 1.0f,
          chunksCompleted = 2,
          totalChunksInChapter = 10,
          bufferedDurationMs = 9_000L,
          aheadDurationMs = 9_000L,
          remainingChunks = 8,
          isPlaying = false,
          isPausedByUser = false,
          isSessionActive = true,
        )
      )

    assertTrue(fastDecision.initialReadyTargetMs <= 12_000L)
    assertTrue(fastDecision.canStartPlayback)
    assertTrue(slowDecision.initialReadyTargetMs > fastDecision.initialReadyTargetMs)
    assertTrue(slowDecision.initialReadyTargetMs <= 75_000L)
    assertFalse(slowDecision.canStartPlayback)
    assertTrue(slowDecision.shouldGenerateMore)
  }

  @Test
  fun `higher playback speed scales effective RTF and increases lookahead target while playing`() {
    val normalSpeed =
      AdaptiveBufferPolicy.evaluate(
        BufferEvaluationInput(
          deviceProfile = standardDevice,
          measuredRtf = 0.72f,
          hasRtfMeasurement = true,
          playbackSpeed = 1.0f,
          chunksCompleted = 4,
          totalChunksInChapter = 12,
          bufferedDurationMs = 30_000L,
          aheadDurationMs = 20_000L,
          remainingChunks = 8,
          isPlaying = true,
          isPausedByUser = false,
          isSessionActive = true,
        )
      )

    val fastPlaybackSpeed =
      AdaptiveBufferPolicy.evaluate(
        BufferEvaluationInput(
          deviceProfile = standardDevice,
          measuredRtf = 0.72f,
          hasRtfMeasurement = true,
          playbackSpeed = 1.5f,
          chunksCompleted = 4,
          totalChunksInChapter = 12,
          bufferedDurationMs = 30_000L,
          aheadDurationMs = 20_000L,
          remainingChunks = 8,
          isPlaying = true,
          isPausedByUser = false,
          isSessionActive = true,
        )
      )

    assertTrue(fastPlaybackSpeed.effectiveRtf > normalSpeed.effectiveRtf)
    assertTrue(fastPlaybackSpeed.aheadLookaheadTargetMs > normalSpeed.aheadLookaheadTargetMs)
    assertTrue(fastPlaybackSpeed.shouldGenerateMore)
  }

  @Test
  fun `constrained low-storage and low-ram device caps buffer conservatively`() {
    val decision =
      AdaptiveBufferPolicy.evaluate(
        BufferEvaluationInput(
          deviceProfile = constrainedDevice,
          measuredRtf = 2.1f,
          hasRtfMeasurement = true,
          playbackSpeed = 1.0f,
          chunksCompleted = 3,
          totalChunksInChapter = 10,
          bufferedDurationMs = 15_000L,
          aheadDurationMs = 15_000L,
          remainingChunks = 7,
          isPlaying = true,
          isPausedByUser = false,
          isSessionActive = true,
        )
      )

    assertTrue(decision.initialReadyTargetMs <= 30_000L)
    assertTrue(decision.aheadLookaheadTargetMs <= 30_000L)
    assertEquals(VisualEffectTier.REDUCED, constrainedDevice.recommendedEffectTier)
    assertEquals(
      VisualEffectTier.STATIC_ACCESSIBLE,
      constrainedDevice.copy(reducedMotionPreferred = true).recommendedEffectTier,
    )
  }

  @Test
  fun `short chapter with all chunks completed is immediately playable and pause stops generation`() {
    val completeShort =
      AdaptiveBufferPolicy.evaluate(
        BufferEvaluationInput(
          deviceProfile = standardDevice,
          measuredRtf = 1.2f,
          hasRtfMeasurement = true,
          playbackSpeed = 1.0f,
          chunksCompleted = 1,
          totalChunksInChapter = 1,
          bufferedDurationMs = 3_200L,
          aheadDurationMs = 3_200L,
          remainingChunks = 0,
          isPlaying = false,
          isPausedByUser = false,
          isSessionActive = true,
        )
      )

    assertTrue(completeShort.canStartPlayback)
    assertFalse(completeShort.shouldGenerateMore)

    val paused =
      AdaptiveBufferPolicy.evaluate(
        BufferEvaluationInput(
          deviceProfile = standardDevice,
          measuredRtf = 0.8f,
          hasRtfMeasurement = true,
          playbackSpeed = 1.0f,
          chunksCompleted = 1,
          totalChunksInChapter = 8,
          bufferedDurationMs = 4_000L,
          aheadDurationMs = 4_000L,
          remainingChunks = 7,
          isPlaying = false,
          isPausedByUser = true,
          isSessionActive = true,
        )
      )
    assertFalse(paused.shouldGenerateMore)
  }

  @Test
  fun `rtf estimator blends cumulative and ewma speed accurately`() {
    val estimator = RtfEstimator()
    assertFalse(estimator.hasMeasurement())
    estimator.recordFragment(synthesisMs = 1_500L, audioDurationMs = 5_000L) // 0.30
    estimator.recordFragment(synthesisMs = 2_000L, audioDurationMs = 5_000L) // 0.40
    assertTrue(estimator.hasMeasurement())
    val measured = estimator.currentRtf()
    assertTrue("Expected measured RTF in (0.30..0.40), got $measured", measured in 0.30f..0.41f)
  }
}
