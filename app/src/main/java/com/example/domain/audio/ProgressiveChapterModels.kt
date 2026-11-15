package com.example.domain.audio

import com.example.data.local.ChapterChunkEntity
import com.example.domain.model.ChapterChunkStatus
import com.example.domain.model.ProgressiveChapterPhase
import java.io.File
import kotlin.math.roundToInt

data class ProgressivePlaybackSnapshot(
  val bookId: String = "",
  val chapterIndex: Int = -1,
  val isPlaying: Boolean = false,
  val playedPositionMs: Long = 0L,
  val playbackSpeed: Float = 1.0f,
)

object ProgressiveChapterProgressCalculator {

  fun isChunkValidAndCompleted(status: String, wavFilePath: String?): Boolean {
    if (status != ChapterChunkStatus.COMPLETED.name || wavFilePath.isNullOrBlank()) return false
    val file = File(wavFilePath)
    return file.exists() && file.length() >= 44L && WavAudioCodec.isValidWavFile(file)
  }

  fun calculateSynthesizedChars(chunks: List<ChapterChunkEntity>): Int {
    return chunks
      .filter { isChunkValidAndCompleted(it.status, it.wavFilePath) }
      .sumOf { it.textContent.length }
  }

  fun calculateTotalChars(chunks: List<ChapterChunkEntity>): Int {
    return chunks.sumOf { it.textContent.length }
  }

  fun calculateProgressFraction(
    totalChars: Int,
    synthesizedChars: Int,
    isCompleteChapterReady: Boolean,
  ): Float {
    if (isCompleteChapterReady) return 1f
    if (totalChars <= 0 || synthesizedChars <= 0) return 0f
    return (synthesizedChars.toFloat() / totalChars.toFloat()).coerceIn(0f, 1f)
  }

  fun calculateChapterPercent(
    totalChars: Int,
    synthesizedChars: Int,
    isCompleteChapterReady: Boolean,
  ): Int {
    if (isCompleteChapterReady) return 100
    if (totalChars <= 0 || synthesizedChars <= 0) return 0
    val rawPercent = (synthesizedChars.toDouble() / totalChars.toDouble() * 100.0).roundToInt()
    return rawPercent.coerceIn(0, 99)
  }
}

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
    get() = ProgressiveChapterProgressCalculator.calculateProgressFraction(
      totalChars = totalChapterChars,
      synthesizedChars = synthesizedChapterChars,
      isCompleteChapterReady = isCompleteChapterReady || phase == ProgressiveChapterPhase.COMPLETED,
    )

  val chapterPercent: Int
    get() = ProgressiveChapterProgressCalculator.calculateChapterPercent(
      totalChars = totalChapterChars,
      synthesizedChars = synthesizedChapterChars,
      isCompleteChapterReady = isCompleteChapterReady || phase == ProgressiveChapterPhase.COMPLETED,
    )

  val initialReadinessPercent: Int
    get() =
      if (canStartPlayback || isCompleteChapterReady) {
        100
      } else {
        (initialReadinessFraction * 100f).roundToInt().coerceIn(0, 99)
      }
}
