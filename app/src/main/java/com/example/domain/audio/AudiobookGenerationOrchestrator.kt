package com.example.domain.audio

import com.example.data.local.AudiobookDao
import com.example.data.local.ChapterChunkEntity
import com.example.data.storage.AudiobookStorageManager
import com.example.domain.model.AudioGenerationStatus
import com.example.domain.model.ChapterChunkStatus
import com.example.domain.model.NarratorVoice
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class BookGenerationProgress(
  val bookId: String,
  val isRunning: Boolean,
  val currentChapterIndex: Int = 0,
  val totalChapters: Int = 0,
  val currentChunkIndex: Int = 0,
  val totalChunksInChapter: Int = 0,
  val completedChapters: Int = 0,
  val statusMessage: String = "",
  val errorMessage: String? = null,
)

class AudiobookGenerationOrchestrator(
  private val dao: AudiobookDao,
  private val storage: AudiobookStorageManager,
  private val synthesisProvider: AudioSynthesisProvider,
) {

  suspend fun generateBookAudiobook(
    bookId: String,
    onProgress: (BookGenerationProgress) -> Unit = {},
  ): Result<Unit> {
    val book =
      dao.getBookOnce(bookId)
        ?: return Result.failure(IllegalStateException("No se encontró el libro en la biblioteca."))
    val voice = NarratorVoice.fromNameOrDefault(book.selectedVoice)
    val chapters = dao.getChaptersOnce(bookId).sortedBy { it.chapterIndex }

    if (chapters.isEmpty()) {
      return Result.failure(IllegalStateException("Este libro no contiene capítulos para narrar."))
    }

    var completedChapters =
      chapters.count { ch ->
        ch.audioStatus == AudioGenerationStatus.READY.name &&
          !ch.audioFilePath.isNullOrBlank() &&
          WavAudioCodec.isValidWavFile(File(ch.audioFilePath))
      }

    for (chapter in chapters) {
      currentCoroutineContext().ensureActive()

      val existingChunks = dao.getChunksForChapterOnce(chapter.id).sortedBy { it.chunkIndex }
      val chapterFile = chapter.audioFilePath?.let { File(it) }
      val chapterAlreadyValid =
        chapter.audioStatus == AudioGenerationStatus.READY.name &&
          chapterFile != null &&
          WavAudioCodec.isValidWavFile(chapterFile) &&
          (existingChunks.isEmpty() || existingChunks.all { it.voiceName == voice.name })

      if (chapterAlreadyValid) {
        continue
      }

      val chunks = prepareChunksForChapter(bookId, chapter.id, chapter.chapterIndex, chapter.contentText, voice, existingChunks)
      val totalChunks = chunks.size
      if (totalChunks == 0) continue

      var doneCount =
        chunks.count { ch ->
          ch.status == ChapterChunkStatus.COMPLETED.name &&
            !ch.wavFilePath.isNullOrBlank() &&
            WavAudioCodec.isValidWavFile(File(ch.wavFilePath))
        }

      dao.updateChapterAudioState(
        chapterId = chapter.id,
        audioStatus = AudioGenerationStatus.GENERATING.name,
        audioFilePath = null,
        audioDurationMs = 0L,
        audioSizeBytes = 0L,
        generatedChunks = doneCount,
        totalChunks = totalChunks,
        generationError = null,
      )

      val chunkFiles = mutableListOf<File>()

      try {
        for (chunk in chunks) {
          currentCoroutineContext().ensureActive()

          val targetFile = storage.chunkWavFile(bookId, chapter.chapterIndex, chunk.chunkIndex)
          val alreadySaved =
            chunk.status == ChapterChunkStatus.COMPLETED.name &&
              chunk.voiceName == voice.name &&
              chunk.wavFilePath != null &&
              WavAudioCodec.isValidWavFile(File(chunk.wavFilePath))

          if (alreadySaved) {
            chunkFiles += File(chunk.wavFilePath!!)
            continue
          }

          onProgress(
            BookGenerationProgress(
              bookId = bookId,
              isRunning = true,
              currentChapterIndex = chapter.chapterIndex,
              totalChapters = chapters.size,
              currentChunkIndex = chunk.chunkIndex + 1,
              totalChunksInChapter = totalChunks,
              completedChapters = completedChapters,
              statusMessage =
                "Generando capítulo ${chapter.chapterIndex} de ${chapters.size} · fragmento ${chunk.chunkIndex + 1}/$totalChunks",
            )
          )

          val synthResult =
            synthesisProvider.synthesizeFragment(
              text = chunk.textContent,
              voice = voice,
              bookTitle = book.title,
              chapterTitle = chapter.title,
            )

          val payload =
            synthResult.getOrElse { error ->
              val msg =
                error.localizedMessage ?: "Error al sintetizar el fragmento ${chunk.chunkIndex + 1}."
              dao.upsertChunk(chunk.copy(status = ChapterChunkStatus.ERROR.name))
              dao.updateChapterAudioState(
                chapterId = chapter.id,
                audioStatus = AudioGenerationStatus.ERROR.name,
                audioFilePath = null,
                audioDurationMs = 0L,
                audioSizeBytes = 0L,
                generatedChunks = doneCount,
                totalChunks = totalChunks,
                generationError = msg,
              )
              onProgress(
                BookGenerationProgress(
                  bookId = bookId,
                  isRunning = false,
                  currentChapterIndex = chapter.chapterIndex,
                  totalChapters = chapters.size,
                  currentChunkIndex = chunk.chunkIndex + 1,
                  totalChunksInChapter = totalChunks,
                  completedChapters = completedChapters,
                  errorMessage = msg,
                )
              )
              return Result.failure(error)
            }

          val wavInfo =
            if (isAlreadyWavPayload(payload)) {
              WavAudioCodec.writeVerifiedWavBytes(payload.bytes, targetFile)
            } else {
              WavAudioCodec.writePcmAsWav(
                pcmBytes = payload.bytes,
                outputFile = targetFile,
                sampleRate = payload.sampleRate,
                channels = payload.channels,
                bitsPerSample = payload.bitsPerSample,
              )
            }

          dao.upsertChunk(
            chunk.copy(
              status = ChapterChunkStatus.COMPLETED.name,
              wavFilePath = targetFile.absolutePath,
              byteSize = targetFile.length(),
              durationMs = wavInfo.durationMs,
            )
          )
          doneCount += 1
          dao.updateChapterAudioState(
            chapterId = chapter.id,
            audioStatus = AudioGenerationStatus.GENERATING.name,
            audioFilePath = null,
            audioDurationMs = 0L,
            audioSizeBytes = 0L,
            generatedChunks = doneCount,
            totalChunks = totalChunks,
            generationError = null,
          )
          chunkFiles += targetFile
        }

        val finalChapterFile = storage.chapterWavFile(bookId, chapter.chapterIndex)
        val mergedInfo = WavAudioCodec.mergeWavFiles(chunkFiles, finalChapterFile)
        dao.updateChapterAudioState(
          chapterId = chapter.id,
          audioStatus = AudioGenerationStatus.READY.name,
          audioFilePath = finalChapterFile.absolutePath,
          audioDurationMs = mergedInfo.durationMs,
          audioSizeBytes = finalChapterFile.length(),
          generatedChunks = totalChunks,
          totalChunks = totalChunks,
          generationError = null,
        )
        completedChapters += 1
      } catch (e: CancellationException) {
        dao.updateChapterAudioState(
          chapterId = chapter.id,
          audioStatus = AudioGenerationStatus.NOT_GENERATED.name,
          audioFilePath = null,
          audioDurationMs = 0L,
          audioSizeBytes = 0L,
          generatedChunks = doneCount,
          totalChunks = totalChunks,
          generationError = null,
        )
        throw e
      } catch (e: Exception) {
        val msg = e.localizedMessage ?: "Error al guardar el archivo WAV del capítulo."
        dao.updateChapterAudioState(
          chapterId = chapter.id,
          audioStatus = AudioGenerationStatus.ERROR.name,
          audioFilePath = null,
          audioDurationMs = 0L,
          audioSizeBytes = 0L,
          generatedChunks = doneCount,
          totalChunks = totalChunks,
          generationError = msg,
        )
        return Result.failure(e)
      }
    }

    onProgress(
      BookGenerationProgress(
        bookId = bookId,
        isRunning = false,
        currentChapterIndex = chapters.size,
        totalChapters = chapters.size,
        completedChapters = completedChapters,
        statusMessage = "Audiolibro generado y verificado",
      )
    )
    return Result.success(Unit)
  }

  private suspend fun prepareChunksForChapter(
    bookId: String,
    chapterId: String,
    chapterIndex: Int,
    contentText: String,
    voice: NarratorVoice,
    existingChunks: List<ChapterChunkEntity>,
  ): List<ChapterChunkEntity> {
    val splitFragments = ChapterTextChunker.splitChapter(contentText)
    val sameVoiceAndCount =
      existingChunks.isNotEmpty() &&
        existingChunks.size == splitFragments.size &&
        existingChunks.all { it.voiceName == voice.name }

    if (sameVoiceAndCount) {
      return existingChunks.map { chunk ->
        val fileOk =
          chunk.status == ChapterChunkStatus.COMPLETED.name &&
            !chunk.wavFilePath.isNullOrBlank() &&
            WavAudioCodec.isValidWavFile(File(chunk.wavFilePath))
        if (fileOk) chunk else chunk.copy(status = ChapterChunkStatus.PENDING.name, wavFilePath = null)
      }
    }

    // Voice changed or first time chunking: clear old chunk files for this chapter
    for (old in existingChunks) {
      old.wavFilePath?.let { path -> runCatching { File(path).delete() } }
    }
    dao.deleteChunksForChapter(chapterId)

    val freshChunks =
      splitFragments.mapIndexed { idx, fragmentText ->
        ChapterChunkEntity(
          id = "${chapterId}_part_${idx}",
          bookId = bookId,
          chapterId = chapterId,
          chapterIndex = chapterIndex,
          chunkIndex = idx,
          textContent = fragmentText,
          voiceName = voice.name,
          status = ChapterChunkStatus.PENDING.name,
          wavFilePath = null,
          byteSize = 0L,
          durationMs = 0L,
        )
      }
    dao.upsertChunks(freshChunks)
    return freshChunks
  }

  private fun isAlreadyWavPayload(payload: SynthesizedAudioPayload): Boolean {
    if (payload.mimeType.contains("wav", ignoreCase = true) && payload.bytes.size >= 44) {
      return payload.bytes[0] == 'R'.code.toByte() &&
        payload.bytes[1] == 'I'.code.toByte() &&
        payload.bytes[2] == 'F'.code.toByte() &&
        payload.bytes[3] == 'F'.code.toByte()
    }
    return false
  }
}
