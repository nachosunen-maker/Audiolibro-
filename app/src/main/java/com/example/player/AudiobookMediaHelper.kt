package com.example.player

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
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
  const val EXTRA_CHUNK_START_OFFSET_MS = "extra_chunk_start_offset_ms"
  const val EXTRA_IS_PROGRESSIVE_CHUNK = "extra_is_progressive_chunk"

  const val DEFAULT_AHEAD_WINDOW_DURATION_MS = 30_000L
  const val CONSTRAINED_AHEAD_WINDOW_DURATION_MS = 20_000L

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

  fun computeChunkStartOffsetsMs(contiguousChunks: List<ChapterChunk>): Map<Int, Long> {
    val offsets = HashMap<Int, Long>(contiguousChunks.size)
    var runningMs = 0L
    for (chunk in contiguousChunks) {
      offsets[chunk.chunkIndex] = runningMs
      runningMs += chunk.durationMs.coerceAtLeast(0L)
    }
    return offsets
  }

  fun selectSlidingWindowChunks(
    contiguousChunks: List<ChapterChunk>,
    startChunkIndex: Int,
    aheadWindowDurationMs: Long = DEFAULT_AHEAD_WINDOW_DURATION_MS,
  ): List<ChapterChunk> {
    val startPos = contiguousChunks.indexOfFirst { it.chunkIndex >= startChunkIndex }
    if (startPos < 0) return emptyList()
    val window = ArrayList<ChapterChunk>(8)
    var accumulatedAheadMs = 0L
    for (i in startPos until contiguousChunks.size) {
      val chunk = contiguousChunks[i]
      window += chunk
      if (i > startPos) {
        accumulatedAheadMs += chunk.durationMs.coerceAtLeast(0L)
        if (window.size >= 2 && accumulatedAheadMs >= aheadWindowDurationMs) {
          break
        }
      }
    }
    return window
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
    chunks: List<ChapterChunk>,
    startOffsetsByChunkIndex: Map<Int, Long> = computeChunkStartOffsetsMs(chunks),
  ): List<MediaItem> {
    val artworkUri =
      book.coverImagePath?.let { File(it) }?.takeIf { it.exists() }?.let { Uri.fromFile(it) }

    return chunks.map { chunk ->
      val startOffsetMs = startOffsetsByChunkIndex[chunk.chunkIndex] ?: 0L
      val extras =
        Bundle().apply {
          putString(EXTRA_BOOK_ID, book.id)
          putInt(EXTRA_CHAPTER_INDEX, chapter.chapterIndex)
          putInt(EXTRA_CHUNK_INDEX, chunk.chunkIndex)
          putLong(EXTRA_CHUNK_DURATION_MS, chunk.durationMs.coerceAtLeast(0L))
          putLong(EXTRA_CHUNK_START_OFFSET_MS, startOffsetMs.coerceAtLeast(0L))
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

  fun resolveProgressiveChapterPositionMs(player: Player): Long {
    val currentItem = player.currentMediaItem ?: return player.currentPosition.coerceAtLeast(0L)
    val extras = currentItem.mediaMetadata.extras ?: return player.currentPosition.coerceAtLeast(0L)
    val chunkDur = extras.getLong(EXTRA_CHUNK_DURATION_MS, 0L)
    val localPos =
      if (chunkDur > 0L) player.currentPosition.coerceIn(0L, chunkDur)
      else player.currentPosition.coerceAtLeast(0L)
    val startOffset = extras.getLong(EXTRA_CHUNK_START_OFFSET_MS, -1L)
    if (startOffset >= 0L) {
      return startOffset + localPos
    }
    val currentIdx = player.currentMediaItemIndex.coerceAtLeast(0)
    var prefixMs = 0L
    for (i in 0 until currentIdx.coerceAtMost(player.mediaItemCount)) {
      val itemExtras = player.getMediaItemAt(i).mediaMetadata.extras
      val dur = itemExtras?.getLong(EXTRA_CHUNK_DURATION_MS, 0L) ?: 0L
      prefixMs += dur.coerceAtLeast(0L)
    }
    return prefixMs + localPos
  }

  fun findWindowSeekTarget(player: Player, targetChapterPosMs: Long): Pair<Int, Long>? {
    val count = player.mediaItemCount
    if (count == 0) return null
    val firstExtras = player.getMediaItemAt(0).mediaMetadata.extras ?: return null
    val firstStart = firstExtras.getLong(EXTRA_CHUNK_START_OFFSET_MS, -1L)
    if (firstStart < 0L || targetChapterPosMs < firstStart) return null

    for (idx in 0 until count) {
      val extras = player.getMediaItemAt(idx).mediaMetadata.extras ?: return null
      val start = extras.getLong(EXTRA_CHUNK_START_OFFSET_MS, -1L)
      val dur = extras.getLong(EXTRA_CHUNK_DURATION_MS, 0L).coerceAtLeast(0L)
      if (start < 0L) return null
      val end = start + dur
      if (targetChapterPosMs < end) {
        return idx to (targetChapterPosMs - start).coerceIn(0L, dur)
      }
    }
    return null
  }

  fun computeWindowAheadDurationMs(player: Player): Long {
    val count = player.mediaItemCount
    if (count == 0) return 0L
    val currentIdx = player.currentMediaItemIndex.coerceIn(0, count - 1)
    val currentDur =
      player.getMediaItemAt(currentIdx).mediaMetadata.extras?.getLong(EXTRA_CHUNK_DURATION_MS, 0L)
        ?.coerceAtLeast(0L) ?: 0L
    var aheadMs = (currentDur - player.currentPosition.coerceAtLeast(0L)).coerceAtLeast(0L)
    for (i in (currentIdx + 1) until count) {
      val dur =
        player.getMediaItemAt(i).mediaMetadata.extras?.getLong(EXTRA_CHUNK_DURATION_MS, 0L)
          ?.coerceAtLeast(0L) ?: 0L
      aheadMs += dur
    }
    return aheadMs
  }

  fun maxChunkIndexInPlayer(player: Player): Int {
    var maxIdx = -1
    for (i in 0 until player.mediaItemCount) {
      val extras = player.getMediaItemAt(i).mediaMetadata.extras ?: continue
      if (extras.getBoolean(EXTRA_IS_PROGRESSIVE_CHUNK, false)) {
        val chunkIdx = extras.getInt(EXTRA_CHUNK_INDEX, -1)
        if (chunkIdx > maxIdx) maxIdx = chunkIdx
      }
    }
    return maxIdx
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
