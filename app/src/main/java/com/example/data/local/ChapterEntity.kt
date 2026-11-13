package com.example.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.domain.model.AudioGenerationStatus
import com.example.domain.model.Chapter

@Entity(
  tableName = "chapters",
  foreignKeys =
    [
      ForeignKey(
        entity = BookEntity::class,
        parentColumns = ["id"],
        childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE,
      )
    ],
  indices = [Index(value = ["bookId"])],
)
data class ChapterEntity(
  @PrimaryKey val id: String,
  val bookId: String,
  val chapterIndex: Int,
  val title: String,
  val contentText: String,
  val wordCount: Int,
  val characterCount: Int,
  val estimatedMinutes: Int,
  val audioStatus: String,
  val audioFilePath: String?,
  val audioDurationMs: Long = 0L,
  val audioSizeBytes: Long = 0L,
  val generatedChunks: Int = 0,
  val totalChunks: Int = 0,
  val generationError: String? = null,
) {
  fun toDomain(): Chapter =
    Chapter(
      id = id,
      bookId = bookId,
      chapterIndex = chapterIndex,
      title = title,
      contentText = contentText,
      wordCount = wordCount,
      characterCount = characterCount,
      estimatedMinutes = estimatedMinutes,
      audioStatus = AudioGenerationStatus.fromNameOrDefault(audioStatus),
      audioFilePath = audioFilePath,
      audioDurationMs = audioDurationMs,
      audioSizeBytes = audioSizeBytes,
      generatedChunks = generatedChunks,
      totalChunks = totalChunks,
      generationError = generationError,
    )
}
