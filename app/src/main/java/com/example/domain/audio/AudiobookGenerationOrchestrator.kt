package com.example.domain.audio

import android.util.Log
import com.example.data.local.AudiobookDao
import com.example.data.local.ChapterChunkEntity
import com.example.data.local.ChapterEntity
import com.example.data.storage.AudiobookStorageManager
import com.example.domain.model.AudioGenerationStatus
import com.example.domain.model.ChapterChunkStatus
import com.example.domain.model.GenerationTaskStatus
import com.example.domain.model.NarratorVoice
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class GenerationPhase {
  IDLE,
  PREPARING,
  RESUMING,
  GENERATING,
  INTERRUPTED,
  COMPLETED,
  ERROR,
}

data class BookGenerationProgress(
  val bookId: String,
  val bookTitle: String = "",
  val isRunning: Boolean,
  val phase: GenerationPhase = if (isRunning) GenerationPhase.GENERATING else GenerationPhase.IDLE,
  val currentChapterIndex: Int = 0,
  val currentChapterNumber: Int = 0,
  val totalChapters: Int = 0,
  val currentChunkIndex: Int = 0,
  val totalChunksInChapter: Int = 0,
  val completedChapters: Int = 0,
  val completedBookChunks: Int = 0,
  val totalBookChunks: Int = 0,
  val statusMessage: String = "",
  val errorMessage: String? = null,
) {
  val globalProgressFraction: Float
    get() =
      when {
        phase == GenerationPhase.COMPLETED -> 1f
        totalBookChunks > 0 ->
          (completedBookChunks.toFloat() / totalBookChunks.toFloat()).coerceIn(0f, 1f)
        totalChapters > 0 ->
          (completedChapters.toFloat() / totalChapters.toFloat()).coerceIn(0f, 1f)
        else -> 0f
      }
}

class AudiobookGenerationOrchestrator(
  private val dao: AudiobookDao,
  private val storage: AudiobookStorageManager,
  private val synthesisProvider: AudioSynthesisProvider,
) {

  suspend fun generateBookAudiobook(
    bookId: String,
    onProgress: (BookGenerationProgress) -> Unit = {},
  ): Result<Unit> {
    var activeVoiceName = NarratorVoice.FEMALE.name
    var currentChapterIdx = 0
    var currentChunkIdx = 0
    var totalBookChunks = 0
    var completedBookChunks = 0

    return try {
      Log.i(TAG, "[Inicio] Solicitando generación continua para bookId=$bookId")
      val book =
        dao.getBookOnce(bookId)
          ?: return Result.failure(IllegalStateException("No se encontró el libro en la biblioteca."))
      val voice = NarratorVoice.fromNameOrDefault(book.selectedVoice)
      activeVoiceName = voice.name
      val chapters = dao.getChaptersOnce(bookId).sortedBy { it.chapterIndex }

      if (chapters.isEmpty()) {
        dao.updateGenerationTaskStatus(
          bookId = bookId,
          status = GenerationTaskStatus.ERROR.name,
          updatedAtMs = System.currentTimeMillis(),
          errorMessage = "Este libro no contiene capítulos para narrar.",
        )
        return Result.failure(IllegalStateException("Este libro no contiene capítulos para narrar."))
      }

      val nowStart = System.currentTimeMillis()
      val existingTask = dao.getGenerationTaskOnce(bookId)
      dao.upsertGenerationTask(
        com.example.data.local.GenerationTaskEntity(
          bookId = bookId,
          queueOrder = existingTask?.queueOrder ?: nowStart,
          voiceName = voice.name,
          status = GenerationTaskStatus.RUNNING.name,
          currentChapterIndex = existingTask?.currentChapterIndex ?: chapters.first().chapterIndex,
          currentChunkIndex = existingTask?.currentChunkIndex ?: 0,
          completedChunks = existingTask?.completedChunks ?: 0,
          totalChunks = existingTask?.totalChunks ?: 0,
          startedAtMs = existingTask?.startedAtMs ?: nowStart,
          updatedAtMs = nowStart,
          lastValidProgressAtMs = existingTask?.lastValidProgressAtMs ?: 0L,
          errorMessage = null,
        )
      )

      onProgress(
        BookGenerationProgress(
          bookId = bookId,
          bookTitle = book.title,
          isRunning = true,
          phase = GenerationPhase.PREPARING,
          totalChapters = chapters.size,
          statusMessage = "Preparando los ${chapters.size} capítulos del libro…",
        )
      )

      // Preparar y persistir los fragmentos de TODOS los capítulos del libro en orden antes de sintetizar
      // para conocer el total real de fragmentos y permitir reanudación exacta ante cierres del sistema.
      val preparedChunksByChapterId = LinkedHashMap<String, List<ChapterChunkEntity>>(chapters.size)
      val chapterValidStatus = HashMap<String, Boolean>(chapters.size)
      var completedChapters = 0
      var hadStaleOrPartialWork = false

      for (chapter in chapters) {
        currentCoroutineContext().ensureActive()
        val existingChunks = dao.getChunksForChapterOnce(chapter.id).sortedBy { it.chunkIndex }
        val chapterFile = chapter.audioFilePath?.let { File(it) }
        val chapterAlreadyValid =
          chapter.audioStatus == AudioGenerationStatus.READY.name &&
            chapterFile != null &&
            WavAudioCodec.isValidWavFile(chapterFile) &&
            (existingChunks.isEmpty() || existingChunks.all { it.voiceName == voice.name })

        chapterValidStatus[chapter.id] = chapterAlreadyValid
        if (chapterAlreadyValid) {
          val chunkCount = maxOf(existingChunks.size, chapter.totalChunks, 1)
          totalBookChunks += chunkCount
          completedBookChunks += chunkCount
          completedChapters += 1
          hadStaleOrPartialWork = true
        } else {
          if (chapter.audioStatus == AudioGenerationStatus.GENERATING.name) {
            hadStaleOrPartialWork = true
          }
          val preparedChunks =
            prepareChunksForChapter(
              bookId = bookId,
              chapter = chapter,
              voice = voice,
              existingChunks = existingChunks,
            )
          preparedChunksByChapterId[chapter.id] = preparedChunks
          val validDoneInChapter =
            preparedChunks.count { ch ->
              ch.status == ChapterChunkStatus.COMPLETED.name &&
                !ch.wavFilePath.isNullOrBlank() &&
                WavAudioCodec.isValidWavFile(File(ch.wavFilePath))
            }
          if (validDoneInChapter > 0) {
            hadStaleOrPartialWork = true
          }
          totalBookChunks += preparedChunks.size
          completedBookChunks += validDoneInChapter

          dao.updateChapterAudioState(
            chapterId = chapter.id,
            audioStatus = AudioGenerationStatus.NOT_GENERATED.name,
            audioFilePath = null,
            audioDurationMs = 0L,
            audioSizeBytes = 0L,
            generatedChunks = validDoneInChapter,
            totalChunks = preparedChunks.size,
            generationError = null,
          )
        }
      }

      val prepTime = System.currentTimeMillis()
      dao.updateGenerationTaskProgress(
        bookId = bookId,
        status = GenerationTaskStatus.RUNNING.name,
        currentChapterIndex = chapters.first().chapterIndex,
        currentChunkIndex = 0,
        completedChunks = completedBookChunks,
        totalChunks = totalBookChunks,
        updatedAtMs = prepTime,
        lastValidProgressAtMs = existingTask?.lastValidProgressAtMs ?: prepTime,
      )

      Log.i(
        TAG,
        "Preparación completa para '${book.title}': ${chapters.size} capítulos, " +
          "$totalBookChunks fragmentos totales ($completedBookChunks ya completados), voz=${voice.name} (speakerId=${voice.speakerId})",
      )

      if (hadStaleOrPartialWork && completedChapters < chapters.size) {
        onProgress(
          BookGenerationProgress(
            bookId = bookId,
            bookTitle = book.title,
            isRunning = true,
            phase = GenerationPhase.RESUMING,
            totalChapters = chapters.size,
            completedChapters = completedChapters,
            completedBookChunks = completedBookChunks,
            totalBookChunks = totalBookChunks,
            statusMessage =
              "Reanudando generación ($completedBookChunks/$totalBookChunks fragmentos completados)…",
          )
        )
      }

      for ((positionIndex, chapter) in chapters.withIndex()) {
        currentCoroutineContext().ensureActive()
        if (chapterValidStatus[chapter.id] == true) {
          continue
        }

        currentChapterIdx = chapter.chapterIndex
        val chapterNumber = positionIndex + 1
        val chunks = preparedChunksByChapterId[chapter.id].orEmpty()
        val totalChunks = chunks.size
        if (totalChunks == 0) {
          completedChapters += 1
          continue
        }

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
            currentChunkIdx = chunk.chunkIndex

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

            if (!storage.hasSufficientStorageSpace()) {
              val msg =
                "Espacio de almacenamiento insuficiente en el dispositivo para guardar el siguiente fragmento de audio."
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
              dao.updateGenerationTaskStatus(
                bookId = bookId,
                status = GenerationTaskStatus.ERROR.name,
                updatedAtMs = System.currentTimeMillis(),
                errorMessage = msg,
              )
              onProgress(
                BookGenerationProgress(
                  bookId = bookId,
                  bookTitle = book.title,
                  isRunning = false,
                  phase = GenerationPhase.ERROR,
                  currentChapterIndex = chapter.chapterIndex,
                  currentChapterNumber = chapterNumber,
                  totalChapters = chapters.size,
                  currentChunkIndex = chunk.chunkIndex + 1,
                  totalChunksInChapter = totalChunks,
                  completedChapters = completedChapters,
                  completedBookChunks = completedBookChunks,
                  totalBookChunks = totalBookChunks,
                  errorMessage = msg,
                )
              )
              return Result.failure(java.io.IOException(msg))
            }

            onProgress(
              BookGenerationProgress(
                bookId = bookId,
                bookTitle = book.title,
                isRunning = true,
                phase = GenerationPhase.GENERATING,
                currentChapterIndex = chapter.chapterIndex,
                currentChapterNumber = chapterNumber,
                totalChapters = chapters.size,
                currentChunkIndex = chunk.chunkIndex + 1,
                totalChunksInChapter = totalChunks,
                completedChapters = completedChapters,
                completedBookChunks = completedBookChunks,
                totalBookChunks = totalBookChunks,
                statusMessage =
                  "Capítulo $chapterNumber de ${chapters.size} · fragmento ${chunk.chunkIndex + 1}/$totalChunks",
              )
            )

            Log.i(
              TAG,
              "Sintetizando cap. $chapterNumber/${chapters.size} (idx=${chapter.chapterIndex}), " +
                "fragmento ${chunk.chunkIndex + 1}/$totalChunks (${chunk.textContent.length} chars, voz=${voice.name}, spk=${voice.speakerId})",
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
                Log.e(
                  TAG,
                  "Fallo al sintetizar cap. ${chapter.chapterIndex} fragmento ${chunk.chunkIndex + 1}: $msg",
                  error,
                )
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
                dao.updateGenerationTaskStatus(
                  bookId = bookId,
                  status = GenerationTaskStatus.ERROR.name,
                  updatedAtMs = System.currentTimeMillis(),
                  errorMessage = msg,
                )
                onProgress(
                  BookGenerationProgress(
                    bookId = bookId,
                    bookTitle = book.title,
                    isRunning = false,
                    phase = GenerationPhase.ERROR,
                    currentChapterIndex = chapter.chapterIndex,
                    currentChapterNumber = chapterNumber,
                    totalChapters = chapters.size,
                    currentChunkIndex = chunk.chunkIndex + 1,
                    totalChunksInChapter = totalChunks,
                    completedChapters = completedChapters,
                    completedBookChunks = completedBookChunks,
                    totalBookChunks = totalBookChunks,
                    errorMessage = msg,
                  )
                )
                return Result.failure(error)
              }

            Log.i(
              TAG,
              "Fragmento sintetizado (${payload.bytes.size} bytes PCM, ${payload.sampleRate} Hz). Escribiendo WAV ${targetFile.name}...",
            )

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

            Log.i(
              TAG,
              "WAV de fragmento guardado y verificado: ${targetFile.name} (${wavInfo.durationMs} ms, ${targetFile.length()} bytes)",
            )

            dao.upsertChunk(
              chunk.copy(
                status = ChapterChunkStatus.COMPLETED.name,
                wavFilePath = targetFile.absolutePath,
                byteSize = targetFile.length(),
                durationMs = wavInfo.durationMs,
              )
            )
            doneCount += 1
            completedBookChunks += 1

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

            val chunkNow = System.currentTimeMillis()
            dao.updateGenerationTaskProgress(
              bookId = bookId,
              status = GenerationTaskStatus.RUNNING.name,
              currentChapterIndex = chapter.chapterIndex,
              currentChunkIndex = chunk.chunkIndex + 1,
              completedChunks = completedBookChunks,
              totalChunks = totalBookChunks,
              updatedAtMs = chunkNow,
              lastValidProgressAtMs = chunkNow,
            )

            onProgress(
              BookGenerationProgress(
                bookId = bookId,
                bookTitle = book.title,
                isRunning = true,
                phase = GenerationPhase.GENERATING,
                currentChapterIndex = chapter.chapterIndex,
                currentChapterNumber = chapterNumber,
                totalChapters = chapters.size,
                currentChunkIndex = chunk.chunkIndex + 1,
                totalChunksInChapter = totalChunks,
                completedChapters = completedChapters,
                completedBookChunks = completedBookChunks,
                totalBookChunks = totalBookChunks,
                statusMessage =
                  "Capítulo $chapterNumber de ${chapters.size} · fragmento ${chunk.chunkIndex + 1}/$totalChunks",
              )
            )
            chunkFiles += targetFile
          }

          val finalChapterFile = storage.chapterWavFile(bookId, chapter.chapterIndex)
          Log.i(
            TAG,
            "Capítulo $chapterNumber/${chapters.size}: concatenando por streaming ${chunkFiles.size} fragmentos en ${finalChapterFile.name}...",
          )
          val mergedInfo = WavAudioCodec.mergeWavFiles(chunkFiles, finalChapterFile)
          Log.i(
            TAG,
            "Capítulo $chapterNumber completado: ${finalChapterFile.name} (${mergedInfo.durationMs} ms, ${finalChapterFile.length()} bytes)",
          )
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
          val latestTask = dao.getGenerationTaskOnce(bookId)
          if (latestTask != null && latestTask.parsedStatus() != GenerationTaskStatus.PAUSED_BY_USER) {
            dao.updateGenerationTaskProgress(
              bookId = bookId,
              status = GenerationTaskStatus.INTERRUPTED_BY_OS.name,
              currentChapterIndex = currentChapterIdx,
              currentChunkIndex = currentChunkIdx,
              completedChunks = completedBookChunks,
              totalChunks = totalBookChunks,
              updatedAtMs = System.currentTimeMillis(),
              lastValidProgressAtMs = latestTask.lastValidProgressAtMs,
            )
          }
          throw e
        } catch (e: Exception) {
          val msg = e.localizedMessage ?: "Error al guardar el archivo WAV del capítulo."
          Log.e(TAG, "Error guardando capítulo ${chapter.chapterIndex}: $msg", e)
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
          dao.updateGenerationTaskStatus(
            bookId = bookId,
            status = GenerationTaskStatus.ERROR.name,
            updatedAtMs = System.currentTimeMillis(),
            errorMessage = msg,
          )
          onProgress(
            BookGenerationProgress(
              bookId = bookId,
              bookTitle = book.title,
              isRunning = false,
              phase = GenerationPhase.ERROR,
              currentChapterIndex = chapter.chapterIndex,
              currentChapterNumber = chapterNumber,
              totalChapters = chapters.size,
              completedChapters = completedChapters,
              completedBookChunks = completedBookChunks,
              totalBookChunks = totalBookChunks,
              errorMessage = msg,
            )
          )
          return Result.failure(e)
        }
      }

      val doneNow = System.currentTimeMillis()
      dao.updateGenerationTaskProgress(
        bookId = bookId,
        status = GenerationTaskStatus.COMPLETED.name,
        currentChapterIndex = chapters.last().chapterIndex,
        currentChunkIndex = 0,
        completedChunks = totalBookChunks,
        totalChunks = totalBookChunks,
        updatedAtMs = doneNow,
        lastValidProgressAtMs = doneNow,
      )

      Log.i(TAG, "Audiolibro '${book.title}' completado al 100 % ($completedChapters/${chapters.size} capítulos)")
      onProgress(
        BookGenerationProgress(
          bookId = bookId,
          bookTitle = book.title,
          isRunning = false,
          phase = GenerationPhase.COMPLETED,
          currentChapterIndex = chapters.last().chapterIndex,
          currentChapterNumber = chapters.size,
          totalChapters = chapters.size,
          completedChapters = completedChapters,
          completedBookChunks = totalBookChunks,
          totalBookChunks = totalBookChunks,
          statusMessage = "Audiolibro generado y verificado",
        )
      )
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (t: Throwable) {
      Log.e(
        TAG,
        "Error general en orquestador para bookId=$bookId: ${t.javaClass.simpleName} - ${t.message}",
        t,
      )
      runCatching {
        dao.updateGenerationTaskStatus(
          bookId = bookId,
          status = GenerationTaskStatus.ERROR.name,
          updatedAtMs = System.currentTimeMillis(),
          errorMessage = t.localizedMessage ?: "Error inesperado durante la síntesis de audio.",
        )
      }
      Result.failure(t as? Exception ?: RuntimeException(t))
    }
  }

  private suspend fun prepareChunksForChapter(
    bookId: String,
    chapter: ChapterEntity,
    voice: NarratorVoice,
    existingChunks: List<ChapterChunkEntity>,
  ): List<ChapterChunkEntity> {
    val splitFragments = ChapterTextChunker.splitChapter(chapter.contentText)
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

    for (old in existingChunks) {
      old.wavFilePath?.let { path -> runCatching { File(path).delete() } }
    }
    dao.deleteChunksForChapter(chapter.id)

    val freshChunks =
      splitFragments.mapIndexed { idx, fragmentText ->
        ChapterChunkEntity(
          id = "${chapter.id}_part_${idx}",
          bookId = bookId,
          chapterId = chapter.id,
          chapterIndex = chapter.chapterIndex,
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

  companion object {
    private const val TAG = "AudioGenOrch"
  }
}
