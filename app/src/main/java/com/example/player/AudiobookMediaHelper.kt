package com.example.player

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.example.domain.audio.WavAudioCodec
import com.example.domain.model.AudioGenerationStatus
import com.example.domain.model.Book
import com.example.domain.model.Chapter
import com.example.domain.model.ChapterChunk
import com.example.domain.model.ChapterChunkStatus
import java.io.File

object AudiobookMediaHelper {
  const val EXTRA_BOOK_ID = "extra_book_id"
  const val EXTRA_CHAPTER_INDEX = "extra_chapter_index"
  const val EXTRA_CHUNK_INDEX = "extra_chunk_index"
  const val EXTRA_CHUNK_DURATION_MS = "extra_chunk_duration_ms"
  const val EXTRA_IS_PROGRESSIVE_CHUNK = "extra_is_progressive_chunk"

  fun buildPlayableChapters(chapters: List<Chapter>): List<Chapter> =
    chapters
      .filter { ch ->
        ch.audioStatus == AudioGenerationStatus.READY &&
          !ch.audioFilePath.isNullOrBlank() &&
          WavAudioCodec.isValidWavFile(File(ch.audioFilePath))
      }
      .sortedBy { it.chapterIndex }

  fun extractContiguousValidChunks(chunks: List<ChapterChunk>): List<ChapterChunk> {
    val sorted = chunks.sortedBy { it.chunkIndex }
    val contiguous = ArrayList<ChapterChunk>(sorted.size)
    var expectedIdx = 0
    for (chunk in sorted) {
      if (chunk.chunkIndex != expectedIdx) break
      if (chunk.status != ChapterChunkStatus.COMPLETED || chunk.wavFilePath.isNullOrBlank()) break
      val file = File(chunk.wavFilePath)
      if (!WavAudioCodec.isValidWavFile(file)) break
      contiguous += chunk
      expectedIdx += 1
    }
    return contiguous
  }

  fun buildMediaItems(book: Book, playableChapters: List<Chapter>): List<MediaItem> {
    val artworkUri =
      book.coverImagePath?.let { File(it) }?.takeIf { it.exists() }?.let { Uri.fromFile(it) }

    return playableChapters.map { ch ->
      val extras =
        Bundle().apply {
          putString(EXTRA_BOOK_ID, book.id)
          putInt(EXTRA_CHAPTER_INDEX, ch.chapterIndex)
          putBoolean(EXTRA_IS_PROGRESSIVE_CHUNK, false)
        }
      val metadata =
        MediaMetadata.Builder()
          .setTitle(ch.title)
          .setArtist("${book.title} · ${book.author}")
          .setSubtitle(book.title)
          .setAlbumTitle(book.title)
          .setAlbumArtist(book.author)
          .setTrackNumber(ch.chapterIndex)
          .setTotalTrackCount(book.totalChapters.coerceAtLeast(playableChapters.size))
          .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
          .setIsPlayable(true)
          .setIsBrowsable(false)
          .setArtworkUri(artworkUri)
          .setExtras(extras)
          .build()

      MediaItem.Builder()
        .setMediaId("${book.id}:${ch.chapterIndex}")
        .setUri(Uri.fromFile(File(ch.audioFilePath!!)))
        .setMediaMetadata(metadata)
        .build()
    }
  }

  fun buildProgressiveMediaItems(
    book: Book,
    chapter: Chapter,
    contiguousChunks: List<ChapterChunk>,
  ): List<MediaItem> {
    val artworkUri =
      book.coverImagePath?.let { File(it) }?.takeIf { it.exists() }?.let { Uri.fromFile(it) }

    return contiguousChunks.map { chunk ->
      val extras =
        Bundle().apply {
          putString(EXTRA_BOOK_ID, book.id)
          putInt(EXTRA_CHAPTER_INDEX, chapter.chapterIndex)
          putInt(EXTRA_CHUNK_INDEX, chunk.chunkIndex)
          putLong(EXTRA_CHUNK_DURATION_MS, chunk.durationMs.coerceAtLeast(0L))
          putBoolean(EXTRA_IS_PROGRESSIVE_CHUNK, true)
        }
      val metadata =
        MediaMetadata.Builder()
          .setTitle(chapter.title)
          .setArtist("${book.title} · ${book.author}")
          .setSubtitle(book.title)
          .setAlbumTitle(book.title)
          .setAlbumArtist(book.author)
          .setTrackNumber(chapter.chapterIndex)
          .setTotalTrackCount(book.totalChapters.coerceAtLeast(1))
          .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
          .setIsPlayable(true)
          .setIsBrowsable(false)
          .setArtworkUri(artworkUri)
          .setExtras(extras)
          .build()

      MediaItem.Builder()
        .setMediaId("${book.id}:${chapter.chapterIndex}:chunk:${chunk.chunkIndex}")
        .setUri(Uri.fromFile(File(chunk.wavFilePath!!)))
        .setMediaMetadata(metadata)
        .build()
    }
  }

  fun resolveChunkPositionOffset(
    chunks: List<ChapterChunk>,
    chapterPositionMs: Long,
  ): Pair<Int, Long> {
    if (chunks.isEmpty()) return 0 to 0L
    var remaining = chapterPositionMs.coerceAtLeast(0L)
    for ((idx, chunk) in chunks.withIndex()) {
      val dur = chunk.durationMs.coerceAtLeast(0L)
      if (remaining < dur || idx == chunks.lastIndex) {
        return idx to remaining.coerceIn(0L, dur.coerceAtLeast(remaining))
      }
      remaining -= dur
    }
    return 0 to 0L
  }

  fun cumulativeChapterPositionMs(
    chunks: List<ChapterChunk>,
    currentItemIndex: Int,
    currentLocalPosMs: Long,
  ): Long {
    if (chunks.isEmpty()) return currentLocalPosMs.coerceAtLeast(0L)
    val clampedIdx = currentItemIndex.coerceIn(0, chunks.lastIndex)
    var prefixMs = 0L
    for (i in 0 until clampedIdx) {
      prefixMs += chunks[i].durationMs.coerceAtLeast(0L)
    }
    val localMax = chunks[clampedIdx].durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
    return prefixMs + currentLocalPosMs.coerceIn(0L, localMax)
  }
}
