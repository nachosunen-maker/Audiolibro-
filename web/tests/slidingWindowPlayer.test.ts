import 'fake-indexeddb/auto';
import { describe, expect, it } from 'vitest';
import { encodePcm16ToWav, PIPER_SAMPLE_RATE } from '../src/domain/textPreprocessor';
import { SlidingWindowAudioCache, type ObjectUrlAdapter } from '../src/player/slidingWindowCache';
import { ProgressiveChapterOrchestrator } from '../src/tts/chapterOrchestrator';
import { getAudioBlob, getChapterChunks, makeChapterAudioKey } from '../src/storage/db';
import type { Book, Chapter } from '../src/domain/models';

function makeDummyWav(durationSec: number): Uint8Array {
  const samples = new Int16Array(Math.round(PIPER_SAMPLE_RATE * durationSec));
  for (let i = 0; i < samples.length; i++) {
    samples[i] = Math.round(8000 * Math.sin(i * 0.05));
  }
  return encodePcm16ToWav(samples, PIPER_SAMPLE_RATE, 1);
}

describe('SlidingWindowAudioCache RAM eviction & recovery', () => {
  it('immediately revokes played chunks and bounds active forward ObjectURLs', () => {
    const revoked: string[] = [];
    let counter = 0;
    const adapter: ObjectUrlAdapter = {
      createObjectUrl: () => `blob:mock-${++counter}`,
      revokeObjectUrl: (url) => {
        revoked.push(url);
      },
    };

    const cache = new SlidingWindowAudioCache(
      {
        mode: 'CONSTRAINED',
        targetForwardWindowMs: 20_000,
        maxActiveObjectUrls: 2,
        minChunksBeforePlayback: 1,
      },
      adapter,
    );

    const wav12s = makeDummyWav(12);
    cache.putChunk(0, wav12s, 12_000, 0);
    cache.putChunk(1, wav12s, 12_000, 0);
    expect(cache.getActiveCount()).toBe(2);
    expect(cache.canPreloadNext(0)).toBe(false);

    // Avanzar al chunk 1 -> chunk 0 debe liberarse inmediatamente
    const evicted = cache.evictOutsideWindow(1);
    expect(evicted).toContain(0);
    expect(cache.has(0)).toBe(false);
    expect(revoked).toContain('blob:mock-1');

    // Precargar chunk 2 dentro de ventana -> solo [1, 2] en RAM
    cache.putChunk(2, wav12s, 12_000, 1);
    expect(cache.getActiveCount()).toBe(2);
    expect(cache.has(1)).toBe(true);
    expect(cache.has(2)).toBe(true);

    // Saltar atrás al chunk 0 (simula recuperación desde IndexedDB) -> recorta ventana
    cache.putChunk(0, wav12s, 12_000, 0);
    expect(cache.has(0)).toBe(true);
    expect(cache.getActiveCount()).toBeLessThanOrEqual(2);
  });
});

describe('ProgressiveChapterOrchestrator on-demand synthesis', () => {
  it('synthesizes a single chapter progressively, merges final WAV, and resumes without re-synthesizing', async () => {
    let synthCalls = 0;
    const orchestrator = new ProgressiveChapterOrchestrator({
      async synthesizeChunk() {
        synthCalls++;
        const wavBytes = makeDummyWav(1.0);
        return { wavBytes, durationMs: 1000, synthesisMs: 120 };
      },
    });

    const book: Book = {
      id: 'book_orch_1',
      title: 'Libro Orquestador',
      author: 'Autor Test',
      language: 'es',
      description: '',
      sha256: 'abc123',
      fileName: 'test.epub',
      fileSizeBytes: 1024,
      chapterCount: 2,
      totalCharCount: 2200,
      estimatedDurationMs: 150000,
      hasCover: false,
      preferredVoice: 'MALE',
      lastChapterIndex: 0,
      lastPositionMs: 0,
      playbackSpeed: 1.0,
      status: 'READY',
      createdAt: Date.now(),
      updatedAt: Date.now(),
    };

    const chapter: Chapter = {
      id: 'book_orch_1_ch_0000',
      bookId: book.id,
      chapterIndex: 0,
      title: 'Capítulo 1',
      textContent: `${'Esta es una oración narrativa de prueba en español. '.repeat(22)}\n\n${'Segundo párrafo narrativo para generar múltiples fragmentos. '.repeat(20)}`,
      charCount: 2200,
      wordCount: 340,
      estimatedDurationMs: 150000,
      isSynthesized: false,
      synthesizedDurationMs: 0,
      audioSizeBytes: 0,
    };

    const chunks = await orchestrator.startChapterSynthesis(book, chapter, 'MALE');
    expect(chunks.length).toBeGreaterThanOrEqual(2);
    expect(synthCalls).toBe(chunks.length);
    expect(orchestrator.getState().phase).toBe('COMPLETED');

    const merged = await getAudioBlob(makeChapterAudioKey(chapter.id, 'MALE'));
    expect(merged).toBeDefined();
    expect(merged!.durationMs).toBeGreaterThan(1500);

    // Ejecutar de nuevo el mismo capítulo -> 0 nuevas llamadas de síntesis (100% reutilización caché)
    const callsBefore = synthCalls;
    await orchestrator.startChapterSynthesis(book, chapter, 'MALE');
    expect(synthCalls).toBe(callsBefore);

    const persistedChunks = await getChapterChunks(chapter.id, 'MALE');
    expect(persistedChunks.every((c) => c.status === 'READY')).toBe(true);
  });
});
