package com.example.player

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.example.domain.audio.WavAudioCodec
import com.example.domain.model.AudioGenerationStatus
import com.example.domain.model.Book
import com.example.domain.model.Chapter
import java.io.File

object AudiobookMediaHelper {
  const val EXTRA_BOOK_ID = "extra_book_id"
  const val EXTRA_CHAPTER_INDEX = "extra_chapter_index"

  fun buildPlayableChapters(chapters: List<Chapter>): List<Chapter> =
    chapters
      .filter { ch ->
        ch.audioStatus == AudioGenerationStatus.READY &&
          !ch.audioFilePath.isNullOrBlank() &&
          WavAudioCodec.isValidWavFile(File(ch.audioFilePath))
      }
      .sortedBy { it.chapterIndex }

  fun buildMediaItems(book: Book, playableChapters: List<Chapter>): List<MediaItem> {
    val artworkUri =
      book.coverImagePath?.let { File(it) }?.takeIf { it.exists() }?.let { Uri.fromFile(it) }

    return playableChapters.map { ch ->
      val extras =
        Bundle().apply {
          putString(EXTRA_BOOK_ID, book.id)
          putInt(EXTRA_CHAPTER_INDEX, ch.chapterIndex)
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
}
