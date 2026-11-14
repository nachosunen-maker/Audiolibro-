package com.example.data.storage

import android.content.Context
import android.net.Uri
import com.example.domain.audio.WavAudioCodec
import com.example.domain.model.AudioGenerationStatus
import com.example.domain.model.Chapter
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AudiobookStorageManager(private val context: Context) {

  private fun bookRootDir(bookId: String): File =
    File(context.filesDir, "audiobooks/$bookId")

  fun chunkWavFile(bookId: String, chapterIndex: Int, chunkIndex: Int): File {
    val dir = File(bookRootDir(bookId), "chunks").apply { mkdirs() }
    val paddedChapter = chapterIndex.toString().padStart(4, '0')
    val paddedChunk = chunkIndex.toString().padStart(4, '0')
    return File(dir, "ch_${paddedChapter}_part_${paddedChunk}.wav")
  }

  fun chapterWavFile(bookId: String, chapterIndex: Int): File {
    val dir = File(bookRootDir(bookId), "chapters").apply { mkdirs() }
    val paddedChapter = chapterIndex.toString().padStart(4, '0')
    return File(dir, "chapter_${paddedChapter}.wav")
  }

  fun calculateBookAudioBytes(bookId: String): Long {
    val root = bookRootDir(bookId)
    if (!root.exists()) return 0L
    return root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
  }

  fun deleteBookAudioFiles(bookId: String) {
    val root = bookRootDir(bookId)
    if (root.exists()) {
      root.deleteRecursively()
    }
  }

  fun availableStorageBytes(): Long = runCatching { context.filesDir.usableSpace }.getOrDefault(0L)

  fun hasSufficientStorageSpace(minBytes: Long = MIN_FREE_STORAGE_BYTES): Boolean {
    val free = availableStorageBytes()
    return free == 0L || free >= minBytes
  }

  companion object {
    const val MIN_FREE_STORAGE_BYTES: Long = 32L * 1024L * 1024L
  }

  suspend fun exportChapterWav(chapter: Chapter, destinationUri: Uri): Result<Long> =
    withContext(Dispatchers.IO) {
      try {
        val path =
          chapter.audioFilePath
            ?: return@withContext Result.failure(
              IllegalStateException("Este capítulo aún no tiene un archivo de audio generado.")
            )
        val sourceFile = File(path)
        if (!WavAudioCodec.isValidWavFile(sourceFile)) {
          return@withContext Result.failure(
            IOException("El archivo de audio del capítulo no existe o está dañado.")
          )
        }

        val outputStream =
          context.contentResolver.openOutputStream(destinationUri)
            ?: return@withContext Result.failure(
              IOException("No se pudo abrir la ubicación de destino seleccionada.")
            )

        val bytesWritten =
          outputStream.use { out ->
            sourceFile.inputStream().use { input -> input.copyTo(out) }
          }
        Result.success(bytesWritten)
      } catch (e: Exception) {
        Result.failure(
          IOException(e.localizedMessage ?: "Error al exportar el capítulo WAV.", e)
        )
      }
    }

  suspend fun exportFullAudiobookWav(
    chapters: List<Chapter>,
    destinationUri: Uri,
  ): Result<Long> =
    withContext(Dispatchers.IO) {
      try {
        if (chapters.isEmpty()) {
          return@withContext Result.failure(
            IllegalStateException("El libro no contiene capítulos para exportar.")
          )
        }
        val sorted = chapters.sortedBy { it.chapterIndex }
        val notReady =
          sorted.filter {
            it.audioStatus != AudioGenerationStatus.READY || it.audioFilePath.isNullOrBlank()
          }
        if (notReady.isNotEmpty()) {
          return@withContext Result.failure(
            IllegalStateException(
              "Faltan ${notReady.size} capítulos por generar antes de exportar el audiolibro completo."
            )
          )
        }

        val files =
          sorted.map { ch ->
            val file = File(ch.audioFilePath!!)
            if (!WavAudioCodec.isValidWavFile(file)) {
              return@withContext Result.failure(
                IOException("El archivo WAV del capítulo ${ch.chapterIndex} no es válido.")
              )
            }
            file
          }

        val outputStream =
          context.contentResolver.openOutputStream(destinationUri)
            ?: return@withContext Result.failure(
              IOException("No se pudo abrir la ubicación de destino seleccionada.")
            )

        val totalBytes =
          outputStream.use { out -> WavAudioCodec.mergeWavFilesToStream(files, out) }
        Result.success(totalBytes)
      } catch (e: Exception) {
        Result.failure(
          IOException(e.localizedMessage ?: "Error al exportar el audiolibro completo.", e)
        )
      }
    }

  fun sanitizeFileName(raw: String): String {
    val cleaned =
      raw
        .trim()
        .replace(Regex("[^A-Za-z0-9áéíóúÁÉÍÓÚñÑüÜ._ -]"), "_")
        .replace(Regex("\\s+"), "_")
    return cleaned.take(64).ifBlank { "audiolibro" }
  }
}
