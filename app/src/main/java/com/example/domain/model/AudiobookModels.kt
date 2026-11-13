package com.example.domain.model

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

enum class NarratorVoice(
  val displayName: String,
  val geminiVoiceName: String,
  val description: String,
) {
  MALE(
    displayName = "Masculina",
    geminiVoiceName = "Charon",
    description =
      "Voz masculina serena, grave e informativa (perfil Charon), ideal para narración literaria.",
  ),
  FEMALE(
    displayName = "Femenina",
    geminiVoiceName = "Kore",
    description =
      "Voz femenina firme, clara y equilibrada (perfil Kore), ideal para lectura continua.",
  );

  companion object {
    fun fromNameOrDefault(raw: String?): NarratorVoice =
      entries.firstOrNull { it.name == raw } ?: FEMALE
  }
}

enum class AudioGenerationStatus(val label: String) {
  NOT_GENERATED("Pendiente"),
  GENERATING("Generando"),
  READY("Generado"),
  ERROR("Error");

  companion object {
    fun fromNameOrDefault(raw: String?): AudioGenerationStatus =
      when (raw) {
        "PENDING_PHASE_2",
        null -> NOT_GENERATED
        else -> entries.firstOrNull { it.name == raw } ?: NOT_GENERATED
      }
  }
}

enum class ChapterChunkStatus {
  PENDING,
  COMPLETED,
  ERROR;

  companion object {
    fun fromNameOrDefault(raw: String?): ChapterChunkStatus =
      entries.firstOrNull { it.name == raw } ?: PENDING
  }
}

data class Book(
  val id: String,
  val title: String,
  val author: String,
  val language: String,
  val publisher: String,
  val description: String,
  val coverImagePath: String?,
  val sourceFileName: String,
  val selectedVoice: NarratorVoice,
  val totalChapters: Int,
  val totalWords: Int,
  val totalCharacters: Int,
  val estimatedMinutes: Int,
  val importedAt: Long,
  val lastPlayedChapterIndex: Int? = null,
  val lastPlayedPositionMs: Long = 0L,
)

data class Chapter(
  val id: String,
  val bookId: String,
  val chapterIndex: Int,
  val title: String,
  val contentText: String,
  val wordCount: Int,
  val characterCount: Int,
  val estimatedMinutes: Int,
  val audioStatus: AudioGenerationStatus,
  val audioFilePath: String?,
  val audioDurationMs: Long = 0L,
  val audioSizeBytes: Long = 0L,
  val generatedChunks: Int = 0,
  val totalChunks: Int = 0,
  val generationError: String? = null,
) {
  val previewText: String
    get() =
      contentText
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .take(3)
        .joinToString(" ")
        .take(180)
}

data class ChapterChunk(
  val id: String,
  val bookId: String,
  val chapterId: String,
  val chapterIndex: Int,
  val chunkIndex: Int,
  val textContent: String,
  val voiceName: String,
  val status: ChapterChunkStatus,
  val wavFilePath: String?,
  val byteSize: Long,
  val durationMs: Long,
)

data class BookWithChapters(
  val book: Book,
  val chapters: List<Chapter>,
) {
  val readyChaptersCount: Int
    get() = chapters.count { it.audioStatus == AudioGenerationStatus.READY && !it.audioFilePath.isNullOrBlank() }

  val isCompleteAudiobookReady: Boolean
    get() = chapters.isNotEmpty() && readyChaptersCount == chapters.size
}

object NarrationEstimator {
  private const val WORDS_PER_MINUTE = 150.0

  fun estimateMinutes(wordCount: Int): Int {
    if (wordCount <= 0) return 0
    return max(1, ceil(wordCount / WORDS_PER_MINUTE).toInt())
  }

  fun formatDuration(minutes: Int): String {
    if (minutes <= 0) return "< 1 min"
    val hours = minutes / 60
    val remainingMinutes = minutes % 60
    return when {
      hours > 0 && remainingMinutes > 0 -> "~$hours h $remainingMinutes min"
      hours > 0 -> "~$hours h"
      else -> "~$minutes min"
    }
  }

  fun formatPlaybackTime(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0L) / 1000L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) {
      String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
      String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
  }

  fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) {
      return String.format(Locale("es", "ES"), "%.0f KB", kb)
    }
    val mb = kb / 1024.0
    if (mb < 1024.0) {
      return String.format(Locale("es", "ES"), "%.1f MB", mb)
    }
    val gb = mb / 1024.0
    return String.format(Locale("es", "ES"), "%.2f GB", gb)
  }
}
