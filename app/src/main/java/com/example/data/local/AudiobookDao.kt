package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.domain.model.AudioGenerationStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface AudiobookDao {

  @Query("SELECT * FROM books ORDER BY importedAt DESC")
  fun observeBooks(): Flow<List<BookEntity>>

  @Query("SELECT * FROM books WHERE id = :bookId LIMIT 1")
  fun observeBook(bookId: String): Flow<BookEntity?>

  @Query("SELECT * FROM books WHERE id = :bookId LIMIT 1")
  suspend fun getBookOnce(bookId: String): BookEntity?

  @Query(
    "SELECT * FROM books WHERE lastPlayedChapterIndex IS NOT NULL ORDER BY importedAt DESC LIMIT 1"
  )
  suspend fun getLastPlayedBookOnce(): BookEntity?

  @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY chapterIndex ASC")
  fun observeChapters(bookId: String): Flow<List<ChapterEntity>>

  @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY chapterIndex ASC")
  suspend fun getChaptersOnce(bookId: String): List<ChapterEntity>

  @Query(
    "SELECT * FROM chapters WHERE bookId = :bookId AND chapterIndex = :chapterIndex LIMIT 1"
  )
  fun observeChapter(bookId: String, chapterIndex: Int): Flow<ChapterEntity?>

  @Query(
    "SELECT * FROM chapter_chunks WHERE chapterId = :chapterId ORDER BY chunkIndex ASC"
  )
  suspend fun getChunksForChapterOnce(chapterId: String): List<ChapterChunkEntity>

  @Query("SELECT * FROM chapter_chunks WHERE bookId = :bookId ORDER BY chapterIndex ASC, chunkIndex ASC")
  fun observeChunksForBook(bookId: String): Flow<List<ChapterChunkEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertBook(book: BookEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertChapters(chapters: List<ChapterEntity>)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertChunks(chunks: List<ChapterChunkEntity>)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertChunk(chunk: ChapterChunkEntity)

  @Transaction
  suspend fun insertBookWithChapters(book: BookEntity, chapters: List<ChapterEntity>) {
    insertBook(book)
    insertChapters(chapters)
  }

  @Query("UPDATE books SET selectedVoice = :voiceName WHERE id = :bookId")
  suspend fun updateSelectedVoice(bookId: String, voiceName: String)

  @Query(
    "UPDATE books SET lastPlayedChapterIndex = :chapterIndex, lastPlayedPositionMs = :positionMs WHERE id = :bookId"
  )
  suspend fun updateBookPlaybackPosition(bookId: String, chapterIndex: Int?, positionMs: Long)

  @Query(
    """
    UPDATE chapters
    SET audioStatus = :audioStatus,
        audioFilePath = :audioFilePath,
        audioDurationMs = :audioDurationMs,
        audioSizeBytes = :audioSizeBytes,
        generatedChunks = :generatedChunks,
        totalChunks = :totalChunks,
        generationError = :generationError
    WHERE id = :chapterId
    """
  )
  suspend fun updateChapterAudioState(
    chapterId: String,
    audioStatus: String,
    audioFilePath: String?,
    audioDurationMs: Long,
    audioSizeBytes: Long,
    generatedChunks: Int,
    totalChunks: Int,
    generationError: String?,
  )

  @Query("DELETE FROM chapter_chunks WHERE chapterId = :chapterId")
  suspend fun deleteChunksForChapter(chapterId: String)

  @Query("DELETE FROM chapter_chunks WHERE bookId = :bookId")
  suspend fun deleteChunksForBook(bookId: String)

  @Query(
    """
    UPDATE chapters
    SET audioStatus = :resetStatus,
        audioFilePath = NULL,
        audioDurationMs = 0,
        audioSizeBytes = 0,
        generatedChunks = 0,
        totalChunks = 0,
        generationError = NULL
    WHERE bookId = :bookId
    """
  )
  suspend fun resetChaptersAudioState(
    bookId: String,
    resetStatus: String = AudioGenerationStatus.NOT_GENERATED.name,
  )

  @Transaction
  suspend fun clearBookGeneratedAudio(bookId: String) {
    deleteChunksForBook(bookId)
    resetChaptersAudioState(bookId)
    updateBookPlaybackPosition(bookId, null, 0L)
  }

  @Query("DELETE FROM books WHERE id = :bookId")
  suspend fun deleteBookById(bookId: String)
}
