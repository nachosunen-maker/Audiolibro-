package com.example.domain.audio

import com.example.domain.model.ProgressiveChapterPhase
import kotlin.math.roundToInt

data class ProgressivePlaybackSnapshot(
  val bookId: String = "",
  val chapterIndex: Int = -1,
  val isPlaying: Boolean = false,
  val playedPositionMs: Long = 0L,
  val playbackSpeed: Float = 1.0f,
)

data class ProgressiveChapterState(
  val bookId: String,
  val bookTitle: String = "",
  val chapterIndex: Int,
  val chapterTitle: String = "",
  val chapterNumber: Int = 1,
  val totalChapters: Int = 1,
  val phase: ProgressiveChapterPhase = ProgressiveChapterPhase.IDLE,
  val chunksCompleted: Int = 0,
  val totalChunks: Int = 0,
  val initialReadyTargetMs: Long = 0L,
  val aheadLookaheadTargetMs: Long = 0L,
  val bufferedDurationMs: Long = 0L,
  val playedDurationMs: Long = 0L,
  val aheadDurationMs: Long = 0L,
  val initialReadinessFraction: Float = 0f,
  val measuredRtf: Float = 0f,
  val effectiveRtf: Float = 0f,
  val playbackSpeed: Float = 1.0f,
  val deviceProfile: DeviceResourceProfile = DeviceResourceProfile(),
  val lastTelemetry: SynthesisTelemetry? = null,
  val canStartPlayback: Boolean = false,
  val isSynthesizing: Boolean = false,
  val isCompleteChapterReady: Boolean = false,
  val statusMessage: String = "",
  val errorMessage: String? = null,
  val totalChapterChars: Int = 0,
  val synthesizedChapterChars: Int = 0,
) {
  val overallChapterProgressFraction: Float
    get() =
      when {
        isCompleteChapterReady || phase == ProgressiveChapterPhase.COMPLETED -> 1f
        totalChapterChars > 0 -> (synthesizedChapterChars.toFloat() / totalChapterChars.toFloat()).coerceIn(0f, 1f)
        totalChunks > 0 -> (chunksCompleted.toFloat() / totalChunks.toFloat()).coerceIn(0f, 1f)
        else -> 0f
      }

  val chapterPercent: Int
    get() = (overallChapterProgressFraction * 100f).roundToInt().coerceIn(0, 100)

  val initialReadinessPercent: Int
    get() =
      if (canStartPlayback || isCompleteChapterReady) {
        100
      } else {
        (initialReadinessFraction * 100f).roundToInt().coerceIn(0, 99)
      }
}
