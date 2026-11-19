import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import * as ort from 'onnxruntime-web';
import { describe, expect, it } from 'vitest';
import {
  encodePcm16ToWav,
  floatSamplesToPcm16Le,
  getWavDurationMs,
  hasAudibleSignal,
  isValidWavBytes,
} from '../src/domain/textPreprocessor';

const WEB_ROOT = resolve(__dirname, '..');
const MODEL_ONNX_PATH = resolve(WEB_ROOT, 'public/assets/models/es_ES-sharvard-medium.onnx');
const MODEL_JSON_PATH = resolve(WEB_ROOT, 'public/assets/models/es_ES-sharvard-medium.onnx.json');
const EXPECTED_ONNX_SHA256 =
  'b1281e1c9d9ddd2c4c509d85b97f75ac70d28dfc0fc784c6699af7ec21866417';

describe('Firebase-only hosting, Service Worker & real Piper ONNX model verification', () => {
  it('verifies local Piper ONNX model SHA-256, metadata, and speaker_id_map (M=0, F=1)', () => {
    const onnxBytes = readFileSync(MODEL_ONNX_PATH);
    const sha256 = createHash('sha256').update(onnxBytes).digest('hex');
    expect(onnxBytes.byteLength).toBe(76_682_531);
    expect(sha256).toBe(EXPECTED_ONNX_SHA256);

    const cfg = JSON.parse(readFileSync(MODEL_JSON_PATH, 'utf8')) as {
      audio: { sample_rate: number };
      num_speakers: number;
      speaker_id_map: Record<string, number>;
    };
    expect(cfg.audio.sample_rate).toBe(22050);
    expect(cfg.num_speakers).toBe(2);
    expect(cfg.speaker_id_map.M).toBe(0);
    expect(cfg.speaker_id_map.F).toBe(1);
  });

  it('verifies Firebase Hosting config (.firebaserc, firebase.json) and zero external runtime URLs', () => {
    const firebaserc = JSON.parse(readFileSync(resolve(WEB_ROOT, '.firebaserc'), 'utf8')) as {
      projects: { default: string };
    };
    expect(firebaserc.projects.default).toBe('audiolibreame');

    const firebaseJson = JSON.parse(readFileSync(resolve(WEB_ROOT, 'firebase.json'), 'utf8')) as {
      hosting: {
        public: string;
        headers: Array<{ source: string; headers: Array<{ key: string; value: string }> }>;
        rewrites: Array<{ source: string; destination: string }>;
      };
    };
    expect(firebaseJson.hosting.public).toBe('dist');
    expect(firebaseJson.hosting.rewrites).toContainEqual({
      source: '**',
      destination: '/index.html',
    });

    const sources = firebaseJson.hosting.headers.map((h) => h.source);
    expect(sources).toContain('/sw.js');
    expect(sources).toContain('**/*.wasm');
    expect(sources).toContain('**/*.onnx');
    expect(sources).toContain('**/*.data');

    const workerCode = readFileSync(resolve(WEB_ROOT, 'src/tts/piperWorker.ts'), 'utf8');
    const swCode = readFileSync(resolve(WEB_ROOT, 'public/sw.js'), 'utf8');
    const combined = `${workerCode}\n${swCode}`;

    expect(combined).not.toMatch(/huggingface\.co/i);
    expect(combined).not.toMatch(/vercel/i);
    expect(combined).not.toMatch(/netlify/i);
    expect(combined).not.toMatch(/cloudflare/i);
    expect(workerCode).toContain("'/assets/models/es_ES-sharvard-medium.onnx'");
    expect(swCode).toContain("audiolibreame-piper-models-v1");
    expect(swCode).toContain("url.origin !== self.location.origin");
  });

  it(
    'runs real ONNX Runtime Web inference with es_ES-sharvard-medium.onnx for BOTH speakers (M=0 and F=1)',
    async () => {
      const cfg = JSON.parse(readFileSync(MODEL_JSON_PATH, 'utf8')) as {
        audio: { sample_rate: number };
        inference: { noise_scale: number; length_scale: number; noise_w: number };
        phoneme_id_map: Record<string, number[]>;
      };

      const onnxBytes = readFileSync(MODEL_ONNX_PATH);
      ort.env.wasm.numThreads = 1;
      const session = await ort.InferenceSession.create(new Uint8Array(onnxBytes), {
        executionProviders: ['wasm'],
      });

      // Secuencia fonémica española válida para "hola" (^ _ o _ l _ a _ $)
      const bos = cfg.phoneme_id_map['^'][0];
      const pad = cfg.phoneme_id_map['_'][0];
      const eos = cfg.phoneme_id_map['$'][0];
      const oId = cfg.phoneme_id_map['o'][0];
      const lId = cfg.phoneme_id_map['l'][0];
      const aId = cfg.phoneme_id_map['a'][0];
      const phonemeIds = [bos, pad, oId, pad, lId, pad, aId, pad, eos];

      const runForSpeaker = async (speakerId: number) => {
        const feeds: Record<string, ort.Tensor> = {
          input: new ort.Tensor(
            'int64',
            BigInt64Array.from(phonemeIds.map((n) => BigInt(n))),
            [1, phonemeIds.length],
          ),
          input_lengths: new ort.Tensor(
            'int64',
            BigInt64Array.from([BigInt(phonemeIds.length)]),
            [1],
          ),
          scales: new ort.Tensor(
            'float32',
            Float32Array.from([
              cfg.inference.noise_scale,
              cfg.inference.length_scale,
              cfg.inference.noise_w,
            ]),
            [3],
          ),
          sid: new ort.Tensor('int64', BigInt64Array.from([BigInt(speakerId)]), [1]),
        };

        const out = await session.run(feeds);
        const tensor = out.output || Object.values(out)[0];
        const floats = tensor.data as Float32Array;
        const wav = encodePcm16ToWav(
          floatSamplesToPcm16Le(floats),
          cfg.audio.sample_rate,
          1,
        );
        return { floats, wav, durationMs: getWavDurationMs(wav) };
      };

      const male = await runForSpeaker(0);
      const female = await runForSpeaker(1);

      expect(male.floats.length).toBeGreaterThan(2000);
      expect(female.floats.length).toBeGreaterThan(2000);
      expect(hasAudibleSignal(male.floats)).toBe(true);
      expect(hasAudibleSignal(female.floats)).toBe(true);
      expect(isValidWavBytes(male.wav)).toBe(true);
      expect(isValidWavBytes(female.wav)).toBe(true);
      expect(male.durationMs).toBeGreaterThan(150);
      expect(female.durationMs).toBeGreaterThan(150);

      // Confirmar que M=0 y F=1 producen ondas distintas
      const sameLength = male.floats.length === female.floats.length;
      let diffSamples = 0;
      const compareLen = Math.min(male.floats.length, female.floats.length, 4000);
      for (let i = 0; i < compareLen; i++) {
        if (Math.abs(male.floats[i] - female.floats[i]) > 0.001) {
          diffSamples++;
        }
      }
      expect(!sameLength || diffSamples > 500).toBe(true);

      await session.release();
    },
    30_000,
  );
});
