package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.domain.model.Book
import com.example.domain.model.NarratorVoice

@Entity(tableName = "books")
data class BookEntity(
  @PrimaryKey val id: String,
  val title: String,
  val author: String,
  val language: String,
  val publisher: String,
  val description: String,
  val coverImagePath: String?,
  val sourceFileName: String,
  val selectedVoice: String,
  val totalChapters: Int,
  val totalWords: Int,
  val totalCharacters: Int,
  val estimatedMinutes: Int,
  val importedAt: Long,
  val lastPlayedChapterIndex: Int? = null,
  val lastPlayedPositionMs: Long = 0L,
) {
  fun toDomain(): Book =
    Book(
      id = id,
      title = title,
      author = author,
      language = language,
      publisher = publisher,
      description = description,
      coverImagePath = coverImagePath,
      sourceFileName = sourceFileName,
      selectedVoice = NarratorVoice.fromNameOrDefault(selectedVoice),
      totalChapters = totalChapters,
      totalWords = totalWords,
      totalCharacters = totalCharacters,
      estimatedMinutes = estimatedMinutes,
      importedAt = importedAt,
      lastPlayedChapterIndex = lastPlayedChapterIndex,
      lastPlayedPositionMs = lastPlayedPositionMs,
    )
}
