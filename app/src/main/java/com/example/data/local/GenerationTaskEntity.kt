package com.example.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.domain.model.GenerationMode
import com.example.domain.model.GenerationTaskStatus

@Entity(
  tableName = "generation_tasks",
  foreignKeys =
    [
      ForeignKey(
        entity = BookEntity::class,
        parentColumns = ["id"],
        childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE,
      )
    ],
  indices = [Index(value = ["status"]), Index(value = ["queueOrder"])],
)
data class GenerationTaskEntity(
  @PrimaryKey val bookId: String,
  val queueOrder: Long,
  val voiceName: String,
  val status: String,
  val currentChapterIndex: Int = 0,
  val currentChunkIndex: Int = 0,
  val completedChunks: Int = 0,
  val totalChunks: Int = 0,
  val startedAtMs: Long,
  val updatedAtMs: Long,
  val lastValidProgressAtMs: Long = 0L,
  val errorMessage: String? = null,
  val mode: String = GenerationMode.FULL_BOOK.name,
  val targetChapterIndex: Int? = null,
  val measuredRtf: Float = 0f,
) {
  fun parsedStatus(): GenerationTaskStatus = GenerationTaskStatus.fromNameOrDefault(status)

  fun parsedMode(): GenerationMode = GenerationMode.fromNameOrDefault(mode)
}
