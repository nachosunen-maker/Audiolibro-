import { splitChapterIntoChunks, DEFAULT_MAX_CHARS_PER_CHUNK } from '../domain/chunker';
import {
  type Book,
  type Chapter,
  type ChapterChunk,
  type NarratorVoiceId,
  type ProgressiveChapterState,
} from '../domain/models';
import { computeChapterProgress } from '../domain/progressiveProgress';
import { getWavDurationMs, isValidWavBytes, mergeWavBuffers } from '../domain/textPreprocessor';
import {
  getAudioBlob,
  getChapterChunks,
  makeChapterAudioKey,
  makeChunkAudioKey,
  markChapterSynthesized,
  saveAudioBlob,
  saveChapterChunks,
} from '../storage/db';
import { piperClient, type SynthesisChunkResult } from './piperClient';

export interface ChunkSynthesizer {
  synthesizeChunk(text: string, voiceId: NarratorVoiceId): Promise<SynthesisChunkResult>;
}

export const INITIAL_PROGRESSIVE_STATE: ProgressiveChapterState = {
  bookId: null,
  bookTitle: '',
  chapterId: null,
  chapterIndex: 0,
  chapterTitle: '',
  voiceId: 'MALE',
  phase: 'IDLE',
  totalChunks: 0,
  completedChunks: 0,
  totalChapterChars: 0,
  synthesizedChapterChars: 0,
  currentChunkIndex: 0,
  canStartPlayback: false,
  modelDownloadProgress: 0,
  modelDownloadMessage: null,
  statusMessage: '',
  errorMessage: null,
  realtimeFactor: null,
};

/**
 * Orquestador de síntesis progresiva bajo demanda (exclusivamente por capítulo).
 * NO realiza conversión masiva de libros completos (misma regla que Android).
 */
export class ProgressiveChapterOrchestrator {
  private state: ProgressiveChapterState = { ...INITIAL_PROGRESSIVE_STATE };
  private listeners = new Set<(state: ProgressiveChapterState) => void>();
  private chunkReadyListeners = new Set<(chapterId: string, chunkIndex: number) => void>();
  private abortController: AbortController | null = null;
  private synthesizer: ChunkSynthesizer;

  constructor(synthesizer: ChunkSynthesizer = piperClient) {
    this.synthesizer = synthesizer;

    if (synthesizer === piperClient) {
      piperClient.onDownloadProgress((evt) => {
        if (this.state.phase === 'IDLE' || this.state.phase === 'COMPLETED') return;
        this.emit({
          ...this.state,
          phase: evt.fraction < 1 ? 'DOWNLOADING_MODEL' : 'SYNTHESIZING',
          modelDownloadProgress: evt.fraction,
          modelDownloadMessage: evt.message,
          statusMessage: evt.message,
        });
      });
    }
  }

  getState(): ProgressiveChapterState {
    return this.state;
  }

  subscribe(listener: (state: ProgressiveChapterState) => void): () => void {
    this.listeners.add(listener);
    listener(this.state);
    return () => this.listeners.delete(listener);
  }

  onChunkReady(listener: (chapterId: string, chunkIndex: number) => void): () => void {
    this.chunkReadyListeners.add(listener);
    return () => this.chunkReadyListeners.delete(listener);
  }

  private emit(next: ProgressiveChapterState): void {
    this.state = next;
    for (const listener of this.listeners) {
      listener(next);
    }
  }

  cancel(): void {
    if (this.abortController) {
      this.abortController.abort();
      this.abortController = null;
    }
    if (
      this.state.phase === 'DOWNLOADING_MODEL' ||
      this.state.phase === 'PREPARING' ||
      this.state.phase === 'SYNTHESIZING' ||
      this.state.phase === 'READY_TO_PLAY'
    ) {
      this.emit({
        ...this.state,
        phase: 'CANCELLED',
        statusMessage: 'Generación del capítulo pausada. Los fragmentos ya creados se conservan.',
      });
    }
  }

  /**
   * Inicia o reanuda la síntesis progresiva de un único capítulo bajo demanda.
   */
  async startChapterSynthesis(
    book: Book,
    chapter: Chapter,
    voiceId: NarratorVoiceId,
  ): Promise<ChapterChunk[]> {
    // Cancelar cualquier síntesis previa en curso
    if (this.abortController) {
      this.abortController.abort();
    }
    const controller = new AbortController();
    this.abortController = controller;

    const slices = splitChapterIntoChunks(chapter.textContent, DEFAULT_MAX_CHARS_PER_CHUNK);
    if (slices.length === 0) {
      this.emit({
        ...INITIAL_PROGRESSIVE_STATE,
        bookId: book.id,
        bookTitle: book.title,
        chapterId: chapter.id,
        chapterIndex: chapter.chapterIndex,
        chapterTitle: chapter.title,
        voiceId,
        phase: 'ERROR',
        errorMessage: 'Este capítulo no contiene texto narrable.',
      });
      return [];
    }

    const totalChars = slices.reduce((acc, s) => acc + s.charCount, 0);

    // Cargar o inicializar estado persistido de fragmentos para reanudación exacta
    const existingChunks = await getChapterChunks(chapter.id, voiceId);
    const existingByIndex = new Map(existingChunks.map((c) => [c.chunkIndex, c]));

    const chunks: ChapterChunk[] = [];
    for (const slice of slices) {
      const audioKey = makeChunkAudioKey(chapter.id, voiceId, slice.index);
      const prev = existingByIndex.get(slice.index);
      let isAlreadyReady = false;
      let existingDuration = 0;

      if (prev && prev.status === 'READY') {
        const blob = await getAudioBlob(audioKey);
        if (blob && isValidWavBytes(blob.bytes)) {
          isAlreadyReady = true;
          existingDuration = blob.durationMs || getWavDurationMs(blob.bytes);
        }
      }

      chunks.push({
        id: `${chapter.id}_${voiceId}_c${String(slice.index).padStart(4, '0')}`,
        bookId: book.id,
        chapterId: chapter.id,
        chapterIndex: chapter.chapterIndex,
        chunkIndex: slice.index,
        voiceId,
        text: slice.text,
        charCount: slice.charCount,
        status: isAlreadyReady ? 'READY' : 'PENDING',
        durationMs: existingDuration,
        audioKey,
        updatedAt: Date.now(),
      });
    }

    await saveChapterChunks(chunks);

    let completedChunks = chunks.filter((c) => c.status === 'READY').length;
    let synthesizedChars = chunks
      .filter((c) => c.status === 'READY')
      .reduce((acc, c) => acc + c.charCount, 0);

    this.emit({
      bookId: book.id,
      bookTitle: book.title,
      chapterId: chapter.id,
      chapterIndex: chapter.chapterIndex,
      chapterTitle: chapter.title,
      voiceId,
      phase: completedChunks === chunks.length ? 'COMPLETED' : 'PREPARING',
      totalChunks: chunks.length,
      completedChunks,
      totalChapterChars: totalChars,
      synthesizedChapterChars: synthesizedChars,
      currentChunkIndex: Math.min(completedChunks, chunks.length - 1),
      canStartPlayback: completedChunks > 0,
      modelDownloadProgress: 0,
      modelDownloadMessage: null,
      statusMessage:
        completedChunks === chunks.length
          ? 'Capítulo completamente sintetizado y disponible sin conexión.'
          : 'Preparando síntesis neural local Piper VITS…',
      errorMessage: null,
      realtimeFactor: null,
    });

    if (completedChunks === chunks.length) {
      await this.ensureMergedChapterWav(book.id, chapter.id, voiceId, chunks);
      return chunks;
    }

    // Sintetizar fragmentos pendientes secuencialmente
    for (let i = 0; i < chunks.length; i++) {
      if (controller.signal.aborted) {
        return chunks;
      }

      const chunk = chunks[i];
      if (chunk.status === 'READY') continue;

      const progress = computeChapterProgress(
        completedChunks,
        chunks.length,
        synthesizedChars,
        totalChars,
      );

      this.emit({
        ...this.state,
        phase: completedChunks > 0 ? 'READY_TO_PLAY' : 'SYNTHESIZING',
        currentChunkIndex: i,
        completedChunks,
        synthesizedChapterChars: synthesizedChars,
        canStartPlayback: completedChunks > 0,
        statusMessage: `Sintetizando fragmento ${i + 1} de ${chunks.length} (${progress.percent}%)…`,
        errorMessage: null,
      });

      try {
        const result = await this.synthesizer.synthesizeChunk(chunk.text, voiceId);
        if (controller.signal.aborted) {
          return chunks;
        }

        if (!isValidWavBytes(result.wavBytes)) {
          throw new Error('El fragmento generado no contiene un archivo WAV PCM válido.');
        }

        const durationMs = result.durationMs || getWavDurationMs(result.wavBytes);
        await saveAudioBlob({
          key: chunk.audioKey,
          bookId: book.id,
          chapterId: chapter.id,
          voiceId,
          kind: 'chunk',
          chunkIndex: chunk.chunkIndex,
          mimeType: 'audio/wav',
          bytes: result.wavBytes,
          durationMs,
          updatedAt: Date.now(),
        });

        chunk.status = 'READY';
        chunk.durationMs = durationMs;
        chunk.updatedAt = Date.now();
        await saveChapterChunks([chunk]);

        completedChunks++;
        synthesizedChars += chunk.charCount;
        const rtf = durationMs > 0 ? result.synthesisMs / durationMs : null;

        for (const listener of this.chunkReadyListeners) {
          listener(chapter.id, chunk.chunkIndex);
        }

        const updatedProgress = computeChapterProgress(
          completedChunks,
          chunks.length,
          synthesizedChars,
          totalChars,
        );

        this.emit({
          ...this.state,
          phase: completedChunks === chunks.length ? 'COMPLETED' : 'READY_TO_PLAY',
          completedChunks,
          synthesizedChapterChars: synthesizedChars,
          currentChunkIndex: i,
          canStartPlayback: true,
          realtimeFactor: rtf,
          statusMessage:
            completedChunks === chunks.length
              ? 'Capítulo listo y guardado en caché local.'
              : `Reproducción disponible · ${updatedProgress.percent}% sintetizado (${completedChunks}/${chunks.length})`,
        });
      } catch (err) {
        if (controller.signal.aborted) return chunks;
        const message =
          err instanceof Error ? err.message : 'Error durante la síntesis local del fragmento.';
        chunk.status = 'FAILED';
        chunk.updatedAt = Date.now();
        await saveChapterChunks([chunk]);
        this.emit({
          ...this.state,
          phase: 'ERROR',
          errorMessage: message,
          statusMessage: 'Error en la síntesis local.',
        });
        return chunks;
      }
    }

    if (!controller.signal.aborted && completedChunks === chunks.length) {
      await this.ensureMergedChapterWav(book.id, chapter.id, voiceId, chunks);
    }

    return chunks;
  }

  private async ensureMergedChapterWav(
    bookId: string,
    chapterId: string,
    voiceId: NarratorVoiceId,
    chunks: ChapterChunk[],
  ): Promise<void> {
    const chapterKey = makeChapterAudioKey(chapterId, voiceId);
    const existing = await getAudioBlob(chapterKey);
    if (existing && isValidWavBytes(existing.bytes)) {
      await markChapterSynthesized(
        chapterId,
        voiceId,
        existing.durationMs,
        existing.bytes.byteLength,
      );
      return;
    }

    const wavParts: Uint8Array[] = [];
    for (const chunk of chunks) {
      const blob = await getAudioBlob(chunk.audioKey);
      if (blob && isValidWavBytes(blob.bytes)) {
        wavParts.push(blob.bytes);
      }
    }

    if (wavParts.length === 0) return;

    const merged = mergeWavBuffers(wavParts, 180);
    const durationMs = getWavDurationMs(merged);

    await saveAudioBlob({
      key: chapterKey,
      bookId,
      chapterId,
      voiceId,
      kind: 'chapter',
      chunkIndex: -1,
      mimeType: 'audio/wav',
      bytes: merged,
      durationMs,
      updatedAt: Date.now(),
    });

    await markChapterSynthesized(chapterId, voiceId, durationMs, merged.byteLength);
  }
}

export const chapterOrchestrator = new ProgressiveChapterOrchestrator();
