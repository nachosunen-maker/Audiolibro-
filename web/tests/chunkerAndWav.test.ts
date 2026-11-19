import { describe, expect, it } from 'vitest';
import { splitChapterIntoChunks } from '../src/domain/chunker';
import { computeChapterProgress } from '../src/domain/progressiveProgress';
import {
  encodePcm16ToWav,
  floatSamplesToPcm16Le,
  getWavDurationMs,
  hasAudibleSignal,
  isValidWavBytes,
  mergeWavBuffers,
  PIPER_SAMPLE_RATE,
  prepareSpanishNarrationText,
} from '../src/domain/textPreprocessor';

function createSineWaveWav(durationSec: number, freqHz = 440): Uint8Array {
  const numSamples = Math.round(PIPER_SAMPLE_RATE * durationSec);
  const floats = new Float32Array(numSamples);
  for (let i = 0; i < numSamples; i++) {
    floats[i] = 0.25 * Math.sin((2 * Math.PI * freqHz * i) / PIPER_SAMPLE_RATE);
  }
  const pcm = floatSamplesToPcm16Le(floats);
  return encodePcm16ToWav(pcm, PIPER_SAMPLE_RATE, 1);
}

describe('ChapterTextChunker & WAV utilities', () => {
  it('splits long Spanish chapters respecting paragraphs and max length', () => {
    const p1 = 'En un lugar de la Mancha, de cuyo nombre no quiero acordarme. '.repeat(12);
    const p2 = 'No ha mucho tiempo que vivía un hidalgo de los de lanza en astillero. '.repeat(12);
    const chunks = splitChapterIntoChunks(`${p1}\n\n${p2}`, 900);

    expect(chunks.length).toBeGreaterThanOrEqual(2);
    for (const chunk of chunks) {
      expect(chunk.text.length).toBeLessThanOrEqual(900);
      expect(chunk.charCount).toBe(chunk.text.length);
    }
  });

  it('normalizes Spanish typography and guarantees closing punctuation', () => {
    const cleaned = prepareSpanishNarrationText('  «Hola   mundo»\n—dijo ella  ');
    expect(cleaned).toBe('"Hola mundo". — dijo ella.');
  });

  it('encodes, validates, measures and merges PCM16 WAV buffers accurately', () => {
    const wavA = createSineWaveWav(0.5, 440);
    const wavB = createSineWaveWav(0.5, 520);

    expect(isValidWavBytes(wavA)).toBe(true);
    expect(getWavDurationMs(wavA)).toBeGreaterThanOrEqual(495);
    expect(getWavDurationMs(wavA)).toBeLessThanOrEqual(505);

    const merged = mergeWavBuffers([wavA, wavB], 200);
    expect(isValidWavBytes(merged)).toBe(true);
    // 500ms + 200ms pause + 500ms = ~1200ms
    expect(getWavDurationMs(merged)).toBeGreaterThanOrEqual(1190);
    expect(getWavDurationMs(merged)).toBeLessThanOrEqual(1210);
  });

  it('detects silence vs audible signal', () => {
    const silence = new Float32Array(2205);
    expect(hasAudibleSignal(silence)).toBe(false);

    const tone = new Float32Array(2205);
    for (let i = 0; i < tone.length; i++) tone[i] = 0.2 * Math.sin(i * 0.1);
    expect(hasAudibleSignal(tone)).toBe(true);
  });

  it('computes character-weighted chapter progress accurately', () => {
    const metrics = computeChapterProgress(1, 3, 250, 1000);
    expect(metrics.percent).toBe(25);
    expect(metrics.fraction).toBeCloseTo(0.25);
  });
});
