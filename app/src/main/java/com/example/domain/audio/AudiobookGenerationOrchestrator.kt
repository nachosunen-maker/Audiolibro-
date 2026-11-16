package com.example.domain.audio

import android.util.Log
import com.example.data.local.AudiobookDao
import com.example.data.local.ChapterChunkEntity
import com.example.data.local.ChapterEntity
import com.example.data.local.GenerationTaskEntity
import com.example.data.storage.AudiobookStorageManager
import com.example.domain.model.AudioGenerationStatus
import com.example.domain.model.ChapterChunkStatus
import com.example.domain.model.GenerationMode
import com.example.domain.model.GenerationTaskStatus
import com.example.domain.model.NarratorVoice
import com.example.domain.model.ProgressiveChapterPhase
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class AudiobookGenerationOrchestrator(
  private val dao: AudiobookDao,
  private val storage: AudiobookStorageManager,
  private val synthesisProvider: AudioSynthesisProvider,
) {

  suspend fun prepareAndGenerateProgressiveChapter(
    bookId: String,
    chapterIndex: Int,
    rtfEstimator: RtfEstimator,
    deviceProfile: DeviceResourceProfile,
    playbackStateProvider: () -> ProgressivePlaybackSnapshot,
    isPausedProvider: () -> Boolean,
    sessionActiveProvider: () -> Boolean,
    cancellationStatusProvider: (String) -> GenerationTaskStatus? = { null },
    awaitSignal: suspend () -> Unit,
    onProgressiveState: (ProgressiveChapterState) -> Unit,
  ): Result<Unit> {
    var activeChapterEntity: ChapterEntity? = null
    var activeChunksCount = 0
    var completedCount = 0
    var lastTelemetry: SynthesisTelemetry? = null

    return try {
      val book =
        dao.getBookOnce(bookId)
          ?: return Result.failure(IllegalStateException("No se encontró el libro en la biblioteca."))
      val chapters = dao.getChaptersOnce(bookId).sortedBy { it.chapterIndex }
      val chapterPos = chapters.indexOfFirst { it.chapterIndex == chapterIndex }
      val chapter =
        chapters.getOrNull(chapterPos)
          ?: return Result.failure(IllegalStateException("No se encontró el capítulo $chapterIndex."))
      activeChapterEntity = chapter
      val chapterNumber = (chapterPos + 1).coerceAtLeast(1)
      val voice = NarratorVoice.fromNameOrDefault(book.selectedVoice)
      storage.cleanOrphanedTempFiles(bookId)

      val existingFile = chapter.audioFilePath?.let { File(it) }
      val existingChunks = dao.getChunksForChapterOnce(chapter.id).sortedBy { it.chunkIndex }
      val matchingVoiceChunks = existingChunks.filter { it.voiceName == voice.name }
      val voiceMatches = existingChunks.isEmpty() || matchingVoiceChunks.size == existingChunks.size
      val validExistingWav = isHealthyWavFile(existingFile)
      val alreadyComplete =
        chapter.audioStatus == AudioGenerationStatus.READY.name && validExistingWav && voiceMatches

      if (alreadyComplete) {
        val dur = chapter.audioDurationMs.coerceAtLeast(WavAudioCodec.readWavInfo(existingFile!!)?.durationMs ?: 0L)
        val readyChunks = existingChunks.size.coerceAtLeast(chapter.totalChunks).coerceAtLeast(1)
        val readyTotalChars =
          if (existingChunks.isNotEmpty()) {
            ProgressiveChapterProgressCalculator.calculateTotalChars(existingChunks)
          } else {
            chapter.characterCount.coerceAtLeast(chapter.contentText.length)
          }
        onProgressiveState(
          ProgressiveChapterState(
            bookId = bookId,
            bookTitle = book.title,
            chapterIndex = chapter.chapterIndex,
            chapterTitle = chapter.title,
            chapterNumber = chapterNumber,
            totalChapters = chapters.size.coerceAtLeast(1),
            phase = ProgressiveChapterPhase.COMPLETED,
            chunksCompleted = readyChunks,
            totalChunks = readyChunks,
            initialReadyTargetMs = 0L,
            aheadLookaheadTargetMs = 0L,
            bufferedDurationMs = dur,
            playedDurationMs = playbackStateProvider().playedPositionMs,
            aheadDurationMs = (dur - playbackStateProvider().playedPositionMs).coerceAtLeast(0L),
            initialReadinessFraction = 1f,
            measuredRtf = rtfEstimator.currentRtf(),
            effectiveRtf = rtfEstimator.currentRtf(),
            playbackSpeed = playbackStateProvider().playbackSpeed,
            deviceProfile = deviceProfile,
            canStartPlayback = true,
            isSynthesizing = false,
            isCompleteChapterReady = true,
            statusMessage = "Audio preparado",
            totalChapterChars = readyTotalChars,
            synthesizedChapterChars = readyTotalChars,
          )
        )
        return Result.success(Unit)
      }

      if (chapter.audioStatus == AudioGenerationStatus.READY.name) {
        val repairedCount =
          if (voiceMatches) {
            matchingVoiceChunks.count { ch ->
              ch.status == ChapterChunkStatus.COMPLETED.name &&
                isHealthyWavFile(ch.wavFilePath?.let(::File))
            }
          } else {
            0
          }
        dao.updateChapterAudioState(
          chapterId = chapter.id,
          audioStatus = AudioGenerationStatus.NOT_GENERATED.name,
          audioFilePath = null,
          audioDurationMs = 0L,
          audioSizeBytes = 0L,
          generatedChunks = repairedCount,
          totalChunks = if (voiceMatches) existingChunks.size else 0,
          generationError = null,
        )
      }

      val initialTotalChars =
        if (matchingVoiceChunks.isNotEmpty()) {
          ProgressiveChapterProgressCalculator.calculateTotalChars(matchingVoiceChunks)
        } else {
          chapter.characterCount.coerceAtLeast(chapter.contentText.length)
        }
      val initialSynthesizedChars =
        ProgressiveChapterProgressCalculator.calculateSynthesizedChars(matchingVoiceChunks)

      onProgressiveState(
        ProgressiveChapterState(
          bookId = bookId,
          bookTitle = book.title,
          chapterIndex = chapter.chapterIndex,
          chapterTitle = chapter.title,
          chapterNumber = chapterNumber,
          totalChapters = chapters.size.coerceAtLeast(1),
          phase = ProgressiveChapterPhase.PREPARING_VOICE,
          deviceProfile = deviceProfile,
          isSynthesizing = true,
          statusMessage = "Preparando voz local",
          totalChapterChars = initialTotalChars,
          synthesizedChapterChars = initialSynthesizedChars,
        )
      )

      val chunks =
        prepareChunksForChapter(
          bookId = bookId,
          chapter = chapter,
          voice = voice,
          existingChunks = existingChunks,
        ).toMutableList()
      activeChunksCount = chunks.size

      if (chunks.isEmpty()) {
        val msg = "El capítulo no contiene texto narrable."
        onProgressiveState(
          ProgressiveChapterState(
            bookId = bookId,
            bookTitle = book.title,
            chapterIndex = chapter.chapterIndex,
            chapterTitle = chapter.title,
            chapterNumber = chapterNumber,
            totalChapters = chapters.size.coerceAtLeast(1),
            phase = ProgressiveChapterPhase.RECOVERABLE_ERROR,
            deviceProfile = deviceProfile,
            statusMessage = msg,
            errorMessage = msg,
            totalChapterChars = 0,
            synthesizedChapterChars = 0,
          )
        )
        return Result.failure(IllegalStateException(msg))
      }

      val nowStart = System.currentTimeMillis()
      val existingTask = dao.getGenerationTaskOnce(bookId)
      completedCount = chunks.count { ch ->
        ch.status == ChapterChunkStatus.COMPLETED.name &&
          !ch.wavFilePath.isNullOrBlank() &&
          WavAudioCodec.isValidWavFile(File(ch.wavFilePath))
      }
      dao.upsertGenerationTask(
        GenerationTaskEntity(
          bookId = bookId,
          queueOrder = existingTask?.queueOrder ?: nowStart,
          mode = GenerationMode.PROGRESSIVE_CHAPTER.name,
          targetChapterIndex = chapter.chapterIndex,
          voiceName = voice.name,
          status = GenerationTaskStatus.RUNNING.name,
          currentChapterIndex = chapter.chapterIndex,
          currentChunkIndex = completedCount,
          completedChunks = completedCount,
          totalChunks = chunks.size,
          measuredRtf = rtfEstimator.currentRtf(),
          startedAtMs = existingTask?.startedAtMs ?: nowStart,
          updatedAtMs = nowStart,
          lastValidProgressAtMs = nowStart,
          errorMessage = null,
        )
      )

      while (true) {
        currentCoroutineContext().ensureActive()

        val totalChars = ProgressiveChapterProgressCalculator.calculateTotalChars(chunks)
        val synthesizedChars = ProgressiveChapterProgressCalculator.calculateSynthesizedChars(chunks)

        val contiguousReady = mutableListOf<ChapterChunkEntity>()
        for (chunk in chunks) {
          val ok =
            chunk.status == ChapterChunkStatus.COMPLETED.name &&
              !chunk.wavFilePath.isNullOrBlank() &&
              WavAudioCodec.isValidWavFile(File(chunk.wavFilePath))
          if (ok) contiguousReady += chunk else break
        }
        val contiguousBufferedMs = contiguousReady.sumOf { it.durationMs.coerceAtLeast(0L) }
        val nextPendingIndex = chunks.indexOfFirst { chunk ->
          chunk.status != ChapterChunkStatus.COMPLETED.name ||
            chunk.wavFilePath.isNullOrBlank() ||
            !WavAudioCodec.isValidWavFile(File(chunk.wavFilePath))
        }
        completedCount =
          chunks.count { ch ->
            ch.status == ChapterChunkStatus.COMPLETED.name &&
              !ch.wavFilePath.isNullOrBlank() &&
              WavAudioCodec.isValidWavFile(File(ch.wavFilePath))
          }

        val snap = playbackStateProvider()
        val isPlaybackMatchingChapter =
          snap.bookId == bookId && snap.chapterIndex == chapter.chapterIndex
        val playedMs = if (isPlaybackMatchingChapter) snap.playedPositionMs.coerceAtLeast(0L) else 0L
        val isPlayingThisChapter = isPlaybackMatchingChapter && snap.isPlaying
        val aheadMs = (contiguousBufferedMs - playedMs).coerceAtLeast(0L)
        val remainingChunks = if (nextPendingIndex < 0) 0 else (chunks.size - completedCount)

        if (nextPendingIndex < 0) {
          val chunkFiles = chunks.mapNotNull { it.wavFilePath?.let(::File) }
          val finalChapterFile = storage.chapterWavFile(bookId, chapter.chapterIndex)
          val mergedInfo = WavAudioCodec.mergeWavFiles(chunkFiles, finalChapterFile)
          val isFinalWavValid = mergedInfo.durationMs > 0L && isHealthyWavFile(finalChapterFile)
          if (!isFinalWavValid) {
            throw java.io.IOException("El archivo WAV final del capítulo resultó vacío o dañado.")
          }
          val finalNow = System.currentTimeMillis()
          dao.updateChapterAudioState(
            chapterId = chapter.id,
            audioStatus = AudioGenerationStatus.READY.name,
            audioFilePath = finalChapterFile.absolutePath,
            audioDurationMs = mergedInfo.durationMs,
            audioSizeBytes = finalChapterFile.length(),
            generatedChunks = chunks.size,
            totalChunks = chunks.size,
            generationError = null,
          )
          dao.updateGenerationTaskProgress(
            bookId = bookId,
            status = GenerationTaskStatus.COMPLETED.name,
            currentChapterIndex = chapter.chapterIndex,
            currentChunkIndex = chunks.size,
            completedChunks = chunks.size,
            totalChunks = chunks.size,
            updatedAtMs = finalNow,
            lastValidProgressAtMs = finalNow,
            measuredRtf = rtfEstimator.currentRtf(),
          )
          onProgressiveState(
            ProgressiveChapterState(
              bookId = bookId,
              bookTitle = book.title,
              chapterIndex = chapter.chapterIndex,
              chapterTitle = chapter.title,
              chapterNumber = chapterNumber,
              totalChapters = chapters.size.coerceAtLeast(1),
              phase = ProgressiveChapterPhase.COMPLETED,
              chunksCompleted = chunks.size,
              totalChunks = chunks.size,
              initialReadyTargetMs = 0L,
              aheadLookaheadTargetMs = 0L,
              bufferedDurationMs = mergedInfo.durationMs,
              playedDurationMs = playedMs,
              aheadDurationMs = (mergedInfo.durationMs - playedMs).coerceAtLeast(0L),
              initialReadinessFraction = 1f,
              measuredRtf = rtfEstimator.currentRtf(),
              effectiveRtf = rtfEstimator.currentRtf() * snap.playbackSpeed,
              playbackSpeed = snap.playbackSpeed,
              deviceProfile = deviceProfile,
              lastTelemetry = lastTelemetry,
              canStartPlayback = true,
              isSynthesizing = false,
              isCompleteChapterReady = isFinalWavValid,
              statusMessage = "Audio preparado",
              totalChapterChars = totalChars,
              synthesizedChapterChars = if (isFinalWavValid) totalChars else synthesizedChars,
            )
          )
          return Result.success(Unit)
        }

        val isPausedByUser = isPausedProvider()
        val sessionActive = sessionActiveProvider()
        val decision =
          AdaptiveBufferPolicy.evaluate(
            BufferEvaluationInput(
              deviceProfile = deviceProfile,
              measuredRtf = rtfEstimator.currentRtf(),
              hasRtfMeasurement = rtfEstimator.hasMeasurement(),
              playbackSpeed = snap.playbackSpeed,
              chunksCompleted = contiguousReady.size,
              totalChunksInChapter = chunks.size,
              bufferedDurationMs = contiguousBufferedMs,
              aheadDurationMs = aheadMs,
              remainingChunks = remainingChunks,
              isPlaying = isPlayingThisChapter,
              isPausedByUser = isPausedByUser,
              isSessionActive = sessionActive,
            )
          )

        if (!decision.shouldGenerateMore) {
          val idlePhase =
            when {
              isPausedByUser -> ProgressiveChapterPhase.PAUSED
              isPlayingThisChapter -> ProgressiveChapterPhase.PLAYING_LOOKAHEAD
              decision.canStartPlayback -> ProgressiveChapterPhase.READY_TO_PLAY
              else -> ProgressiveChapterPhase.PAUSED
            }
          val idleMessage =
            when (idlePhase) {
              ProgressiveChapterPhase.PAUSED -> "Generación pausada"
              ProgressiveChapterPhase.PLAYING_LOOKAHEAD -> "Búfer por adelantado listo"
              ProgressiveChapterPhase.READY_TO_PLAY -> "Audio preparado"
              else -> "Generación en pausa"
            }

          dao.updateChapterAudioState(
            chapterId = chapter.id,
            audioStatus = AudioGenerationStatus.NOT_GENERATED.name,
            audioFilePath = null,
            audioDurationMs = contiguousBufferedMs,
            audioSizeBytes = contiguousReady.sumOf { it.byteSize },
            generatedChunks = completedCount,
            totalChunks = chunks.size,
            generationError = null,
          )
          dao.updateGenerationTaskProgress(
            bookId = bookId,
            status = GenerationTaskStatus.PAUSED_BY_USER.name,
            currentChapterIndex = chapter.chapterIndex,
            currentChunkIndex = completedCount,
            completedChunks = completedCount,
            totalChunks = chunks.size,
            updatedAtMs = System.currentTimeMillis(),
            lastValidProgressAtMs = System.currentTimeMillis(),
            measuredRtf = rtfEstimator.currentRtf(),
          )

          onProgressiveState(
            ProgressiveChapterState(
              bookId = bookId,
              bookTitle = book.title,
              chapterIndex = chapter.chapterIndex,
              chapterTitle = chapter.title,
              chapterNumber = chapterNumber,
              totalChapters = chapters.size.coerceAtLeast(1),
              phase = idlePhase,
              chunksCompleted = completedCount,
              totalChunks = chunks.size,
              initialReadyTargetMs = decision.initialReadyTargetMs,
              aheadLookaheadTargetMs = decision.aheadLookaheadTargetMs,
              bufferedDurationMs = contiguousBufferedMs,
              playedDurationMs = playedMs,
              aheadDurationMs = aheadMs,
              initialReadinessFraction = decision.initialReadinessFraction,
              measuredRtf = decision.measuredRtf,
              effectiveRtf = decision.effectiveRtf,
              playbackSpeed = snap.playbackSpeed,
              deviceProfile = deviceProfile,
              lastTelemetry = lastTelemetry,
              canStartPlayback = decision.canStartPlayback,
              isSynthesizing = false,
              isCompleteChapterReady = false,
              statusMessage = idleMessage,
              totalChapterChars = totalChars,
              synthesizedChapterChars = synthesizedChars,
            )
          )

          // Si ni el usuario está escuchando ni la pantalla sigue abierta, no mantener trabajo suspendido.
          if (!isPlayingThisChapter && !sessionActive) {
            return Result.success(Unit)
          }

          awaitSignal()
          continue
        }

        val chunk = chunks[nextPendingIndex]
        val targetFile = storage.chunkWavFile(bookId, chapter.chapterIndex, chunk.chunkIndex)

        if (!storage.hasSufficientStorageSpace()) {
          val msg = "Espacio insuficiente en el dispositivo para continuar generando este capítulo."
          dao.upsertChunk(chunk.copy(status = ChapterChunkStatus.ERROR.name))
          dao.updateChapterAudioState(
            chapterId = chapter.id,
            audioStatus = AudioGenerationStatus.ERROR.name,
            audioFilePath = null,
            audioDurationMs = contiguousBufferedMs,
            audioSizeBytes = contiguousReady.sumOf { it.byteSize },
            generatedChunks = completedCount,
            totalChunks = chunks.size,
            generationError = msg,
          )
          dao.updateGenerationTaskStatus(
            bookId = bookId,
            status = GenerationTaskStatus.ERROR.name,
            updatedAtMs = System.currentTimeMillis(),
            errorMessage = msg,
          )
          onProgressiveState(
            ProgressiveChapterState(
              bookId = bookId,
              bookTitle = book.title,
              chapterIndex = chapter.chapterIndex,
              chapterTitle = chapter.title,
              chapterNumber = chapterNumber,
              totalChapters = chapters.size.coerceAtLeast(1),
              phase = ProgressiveChapterPhase.RECOVERABLE_ERROR,
              chunksCompleted = completedCount,
              totalChunks = chunks.size,
              initialReadyTargetMs = decision.initialReadyTargetMs,
              aheadLookaheadTargetMs = decision.aheadLookaheadTargetMs,
              bufferedDurationMs = contiguousBufferedMs,
              playedDurationMs = playedMs,
              aheadDurationMs = aheadMs,
              initialReadinessFraction = decision.initialReadinessFraction,
              measuredRtf = decision.measuredRtf,
              effectiveRtf = decision.effectiveRtf,
              playbackSpeed = snap.playbackSpeed,
              deviceProfile = deviceProfile,
              lastTelemetry = lastTelemetry,
              canStartPlayback = decision.canStartPlayback,
              isSynthesizing = false,
              isCompleteChapterReady = false,
              statusMessage = "Error de almacenamiento recuperable",
              errorMessage = msg,
              totalChapterChars = totalChars,
              synthesizedChapterChars = synthesizedChars,
            )
          )
          return Result.failure(java.io.IOException(msg))
        }

        val activePhase =
          if (isPlayingThisChapter) ProgressiveChapterPhase.PLAYING_LOOKAHEAD
          else ProgressiveChapterPhase.GENERATING_BUFFER
        val activeStatusMsg =
          if (completedCount == 0 && !synthesisProvider.isConfigured) {
            "Preparando voz local"
          } else if (isPlayingThisChapter) {
            "Generando por adelantado (${completedCount + 1}/${chunks.size})"
          } else if (decision.canStartPlayback) {
            "Audio preparado · ampliando capítulo (${completedCount + 1}/${chunks.size})"
          } else {
            "Generando audio (${completedCount + 1}/${chunks.size})"
          }

        dao.updateChapterAudioState(
          chapterId = chapter.id,
          audioStatus = AudioGenerationStatus.GENERATING.name,
          audioFilePath = null,
          audioDurationMs = contiguousBufferedMs,
          audioSizeBytes = contiguousReady.sumOf { it.byteSize },
          generatedChunks = completedCount,
          totalChunks = chunks.size,
          generationError = null,
        )

        onProgressiveState(
          ProgressiveChapterState(
            bookId = bookId,
            bookTitle = book.title,
            chapterIndex = chapter.chapterIndex,
            chapterTitle = chapter.title,
            chapterNumber = chapterNumber,
            totalChapters = chapters.size.coerceAtLeast(1),
            phase = activePhase,
            chunksCompleted = completedCount,
            totalChunks = chunks.size,
            initialReadyTargetMs = decision.initialReadyTargetMs,
            aheadLookaheadTargetMs = decision.aheadLookaheadTargetMs,
            bufferedDurationMs = contiguousBufferedMs,
            playedDurationMs = playedMs,
            aheadDurationMs = aheadMs,
            initialReadinessFraction = decision.initialReadinessFraction,
            measuredRtf = decision.measuredRtf,
            effectiveRtf = decision.effectiveRtf,
            playbackSpeed = snap.playbackSpeed,
            deviceProfile = deviceProfile,
            lastTelemetry = lastTelemetry,
            canStartPlayback = decision.canStartPlayback,
            isSynthesizing = true,
            isCompleteChapterReady = false,
            statusMessage = activeStatusMsg,
            totalChapterChars = totalChars,
            synthesizedChapterChars = synthesizedChars,
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
            val msg = error.localizedMessage ?: "No se pudo sintetizar el fragmento ${chunk.chunkIndex + 1}."
            dao.upsertChunk(chunk.copy(status = ChapterChunkStatus.ERROR.name))
            dao.updateChapterAudioState(
              chapterId = chapter.id,
              audioStatus = AudioGenerationStatus.ERROR.name,
              audioFilePath = null,
              audioDurationMs = contiguousBufferedMs,
              audioSizeBytes = contiguousReady.sumOf { it.byteSize },
              generatedChunks = completedCount,
              totalChunks = chunks.size,
              generationError = msg,
            )
            dao.updateGenerationTaskStatus(
              bookId = bookId,
              status = GenerationTaskStatus.ERROR.name,
              updatedAtMs = System.currentTimeMillis(),
              errorMessage = msg,
            )
            onProgressiveState(
              ProgressiveChapterState(
                bookId = bookId,
                bookTitle = book.title,
                chapterIndex = chapter.chapterIndex,
                chapterTitle = chapter.title,
                chapterNumber = chapterNumber,
                totalChapters = chapters.size.coerceAtLeast(1),
                phase = ProgressiveChapterPhase.RECOVERABLE_ERROR,
                chunksCompleted = completedCount,
                totalChunks = chunks.size,
                initialReadyTargetMs = decision.initialReadyTargetMs,
                aheadLookaheadTargetMs = decision.aheadLookaheadTargetMs,
                bufferedDurationMs = contiguousBufferedMs,
                playedDurationMs = playedMs,
                aheadDurationMs = aheadMs,
                initialReadinessFraction = decision.initialReadinessFraction,
                measuredRtf = decision.measuredRtf,
                effectiveRtf = decision.effectiveRtf,
                playbackSpeed = snap.playbackSpeed,
                deviceProfile = deviceProfile,
                lastTelemetry = lastTelemetry,
                canStartPlayback = decision.canStartPlayback,
                isSynthesizing = false,
                isCompleteChapterReady = false,
                statusMessage = "Error recuperable · puedes reintentar sin perder lo generado",
                errorMessage = msg,
                totalChapterChars = totalChars,
                synthesizedChapterChars = synthesizedChars,
              )
            )
            return Result.failure(error)
          }

        val writeStartNs = System.nanoTime()
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
        val wavWriteMs = ((System.nanoTime() - writeStartNs) / 1_000_000L).coerceAtLeast(0L)

        val telemetryWithWrite =
          payload.telemetry?.let { t ->
            val chunkSynthMs = (t.phonemizationMs + t.inferenceMs + t.pcmEncodingMs + wavWriteMs).coerceAtLeast(1L)
            rtfEstimator.recordFragment(chunkSynthMs, wavInfo.durationMs)
            t.copy(
              wavWriteMs = wavWriteMs,
              audioDurationMs = wavInfo.durationMs,
            )
          } ?: SynthesisTelemetry(
            wavWriteMs = wavWriteMs,
            audioDurationMs = wavInfo.durationMs,
            textChars = chunk.textContent.length,
          )
        lastTelemetry = telemetryWithWrite

        val updatedChunk =
          chunk.copy(
            status = ChapterChunkStatus.COMPLETED.name,
            wavFilePath = targetFile.absolutePath,
            byteSize = targetFile.length(),
            durationMs = wavInfo.durationMs,
          )
        chunks[nextPendingIndex] = updatedChunk
        dao.upsertChunk(updatedChunk)

        val postBufferedMs =
          chunks
            .takeWhile {
              it.status == ChapterChunkStatus.COMPLETED.name && !it.wavFilePath.isNullOrBlank()
            }
            .sumOf { it.durationMs.coerceAtLeast(0L) }
        val postCompletedCount = chunks.count { it.status == ChapterChunkStatus.COMPLETED.name }
        val nowChunk = System.currentTimeMillis()

        dao.updateChapterAudioState(
          chapterId = chapter.id,
          audioStatus = AudioGenerationStatus.GENERATING.name,
          audioFilePath = null,
          audioDurationMs = postBufferedMs,
          audioSizeBytes = chunks.sumOf { it.byteSize },
          generatedChunks = postCompletedCount,
          totalChunks = chunks.size,
          generationError = null,
        )
        dao.updateGenerationTaskProgress(
          bookId = bookId,
          status = GenerationTaskStatus.RUNNING.name,
          currentChapterIndex = chapter.chapterIndex,
          currentChunkIndex = postCompletedCount,
          completedChunks = postCompletedCount,
          totalChunks = chunks.size,
          updatedAtMs = nowChunk,
          lastValidProgressAtMs = nowChunk,
          measuredRtf = rtfEstimator.currentRtf(),
        )
      }
      @Suppress("UNREACHABLE_CODE")
      Result.success(Unit)
    } catch (e: CancellationException) {
      withContext(NonCancellable) {
        storage.cleanOrphanedTempFiles(bookId)
        val ch = activeChapterEntity
        if (ch != null) {
          val currentChunks = runCatching { dao.getChunksForChapterOnce(ch.id) }.getOrDefault(emptyList())
          val validCount =
            currentChunks.count {
              it.status == ChapterChunkStatus.COMPLETED.name &&
                !it.wavFilePath.isNullOrBlank() &&
                WavAudioCodec.isValidWavFile(File(it.wavFilePath))
            }
          val latestChapter = runCatching { dao.getChapterOnce(bookId, ch.chapterIndex) }.getOrNull()
          if (latestChapter?.audioStatus != AudioGenerationStatus.READY.name) {
            dao.updateChapterAudioState(
              chapterId = ch.id,
              audioStatus = AudioGenerationStatus.NOT_GENERATED.name,
              audioFilePath = null,
              audioDurationMs = currentChunks.filter { it.status == ChapterChunkStatus.COMPLETED.name }.sumOf { it.durationMs },
              audioSizeBytes = currentChunks.sumOf { it.byteSize },
              generatedChunks = validCount,
              totalChunks = activeChunksCount.coerceAtLeast(currentChunks.size),
              generationError = null,
            )
          }
          val task = dao.getGenerationTaskOnce(bookId)
          if (task != null &&
            task.parsedStatus() != GenerationTaskStatus.COMPLETED &&
            task.parsedStatus() != GenerationTaskStatus.ERROR
          ) {
            val requestedStatus = cancellationStatusProvider(bookId)
            val resolvedStatus =
              when {
                requestedStatus == GenerationTaskStatus.CANCELLED ||
                  task.parsedStatus() == GenerationTaskStatus.CANCELLED ->
                  GenerationTaskStatus.CANCELLED
                requestedStatus == GenerationTaskStatus.PAUSED_BY_USER ||
                  task.parsedStatus() == GenerationTaskStatus.PAUSED_BY_USER ->
                  GenerationTaskStatus.PAUSED_BY_USER
                else -> GenerationTaskStatus.PAUSED_BY_USER
              }
            dao.updateGenerationTaskProgress(
              bookId = bookId,
              status = resolvedStatus.name,
              currentChapterIndex = ch.chapterIndex,
              currentChunkIndex = validCount,
              completedChunks = validCount,
              totalChunks = activeChunksCount.coerceAtLeast(currentChunks.size),
              updatedAtMs = System.currentTimeMillis(),
              lastValidProgressAtMs = task.lastValidProgressAtMs,
              measuredRtf = rtfEstimator.currentRtf(),
            )
          }
        }
      }
      throw e
    } catch (t: Throwable) {
      Log.e(TAG, "Error en generación progresiva cap. $chapterIndex de $bookId: ${t.message}", t)
      val msg = t.localizedMessage ?: "Error inesperado durante la carga progresiva del capítulo."
      activeChapterEntity?.let { ch ->
        runCatching {
          dao.updateChapterAudioState(
            chapterId = ch.id,
            audioStatus = AudioGenerationStatus.ERROR.name,
            audioFilePath = null,
            audioDurationMs = 0L,
            audioSizeBytes = 0L,
            generatedChunks = completedCount,
            totalChunks = activeChunksCount,
            generationError = msg,
          )
          dao.updateGenerationTaskStatus(
            bookId = bookId,
            status = GenerationTaskStatus.ERROR.name,
            updatedAtMs = System.currentTimeMillis(),
            errorMessage = msg,
          )
        }
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

  private fun isHealthyWavFile(file: File?): Boolean {
    if (file == null || !WavAudioCodec.isValidWavFile(file)) return false
    val info = WavAudioCodec.readWavInfo(file) ?: return false
    return info.durationMs > 0L
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
