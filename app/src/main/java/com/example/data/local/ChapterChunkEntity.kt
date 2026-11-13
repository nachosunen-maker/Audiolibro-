package com.example.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.domain.model.ChapterChunk
import com.example.domain.model.ChapterChunkStatus

@Entity(
  tableName = "chapter_chunks",
  foreignKeys =
    [
      ForeignKey(
        entity = ChapterEntity::class,
        parentColumns = ["id"],
        childColumns = ["chapterId"],
        onDelete = ForeignKey.CASCADE,
      )
    ],
  indices = [Index(value = ["chapterId"]), Index(value = ["bookId"])],
)
data class ChapterChunkEntity(
  @PrimaryKey val id: String,
  val bookId: String,
  val chapterId: String,
  val chapterIndex: Int,
  val chunkIndex: Int,
  val textContent: String,
  val voiceName: String,
  val status: String,
  val wavFilePath: String?,
  val byteSize: Long = 0L,
  val durationMs: Long = 0L,
) {
  fun toDomain(): ChapterChunk =
    ChapterChunk(
      id = id,
      bookId = bookId,
      chapterId = chapterId,
      chapterIndex = chapterIndex,
      chunkIndex = chunkIndex,
      textContent = textContent,
      voiceName = voiceName,
      status = ChapterChunkStatus.fromNameOrDefault(status),
      wavFilePath = wavFilePath,
      byteSize = byteSize,
      durationMs = durationMs,
    )
}
