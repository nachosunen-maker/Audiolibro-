/// <reference lib="webworker" />

import * as ort from 'onnxruntime-web';
import {
  encodePcm16ToWav,
  floatSamplesToPcm16Le,
  getWavDurationMs,
  hasAudibleSignal,
  PIPER_SAMPLE_RATE,
  prepareSpanishNarrationText,
} from '../domain/textPreprocessor';

const MODEL_CACHE_NAME = 'audiolibreame-piper-models-v1';
const MODEL_CONFIG_URL = '/assets/models/es_ES-sharvard-medium.onnx.json';
const MODEL_ONNX_URL = '/assets/models/es_ES-sharvard-medium.onnx';

interface PiperConfig {
  audio: { sample_rate: number };
  espeak: { voice: string };
  inference: { noise_scale: number; length_scale: number; noise_w: number };
  num_speakers: number;
  phoneme_id_map: Record<string, number[]>;
  speaker_id_map?: Record<string, number>;
}

interface PiperPhonemizeModule {
  callMain: (args: string[]) => void;
  FS: {
    writeFile: (path: string, data: string | Uint8Array) => void;
    readFile: (path: string, opts: { encoding: 'utf8' }) => string;
    unlink: (path: string) => void;
  };
}

type WorkerRequest =
  | { type: 'WARMUP' }
  | {
      type: 'SYNTHESIZE';
      requestId: string;
      text: string;
      speakerId: number;
    }
  | { type: 'CHECK_CACHE' };

type WorkerResponse =
  | { type: 'DOWNLOAD_PROGRESS'; loaded: number; total: number; fraction: number; message: string }
  | { type: 'READY'; sampleRate: number; modelCached: boolean }
  | { type: 'CACHE_STATUS'; cached: boolean }
  | {
      type: 'SYNTHESIS_RESULT';
      requestId: string;
      wavBuffer: ArrayBuffer;
      durationMs: number;
      synthesisMs: number;
    }
  | { type: 'ERROR'; requestId?: string; message: string };

let configCache: PiperConfig | null = null;
let ortSession: ort.InferenceSession | null = null;
let phonemizerFactory: ((opts: Record<string, unknown>) => Promise<PiperPhonemizeModule>) | null =
  null;
let initPromise: Promise<void> | null = null;

function post(msg: WorkerResponse, transfer?: Transferable[]): void {
  if (transfer && transfer.length > 0) {
    self.postMessage(msg, transfer);
  } else {
    self.postMessage(msg);
  }
}

async function isModelCached(): Promise<boolean> {
  if (typeof caches === 'undefined') return false;
  try {
    const cache = await caches.open(MODEL_CACHE_NAME);
    const match = await cache.match(MODEL_ONNX_URL);
    return Boolean(match);
  } catch {
    return false;
  }
}

async function loadPiperConfig(): Promise<PiperConfig> {
  if (configCache) return configCache;
  const resp = await fetch(MODEL_CONFIG_URL);
  if (!resp.ok) {
    throw new Error(`No se pudo cargar la configuración del modelo Piper (${resp.status}).`);
  }
  configCache = (await resp.json()) as PiperConfig;
  return configCache;
}

async function loadModelOnnxBytes(): Promise<Uint8Array> {
  if (typeof caches !== 'undefined') {
    const cache = await caches.open(MODEL_CACHE_NAME);
    const cachedResp = await cache.match(MODEL_ONNX_URL);
    if (cachedResp) {
      post({
        type: 'DOWNLOAD_PROGRESS',
        loaded: 1,
        total: 1,
        fraction: 1,
        message: 'Modelo de voz local cargado desde caché offline.',
      });
      const buf = await cachedResp.arrayBuffer();
      return new Uint8Array(buf);
    }
  }

  post({
    type: 'DOWNLOAD_PROGRESS',
    loaded: 0,
    total: 0,
    fraction: 0,
    message: 'Descargando voz neural Piper es_ES-sharvard-medium (una sola vez, ~73 MB)…',
  });

  const response = await fetch(MODEL_ONNX_URL);
  if (!response.ok) {
    throw new Error(
      `No se pudo descargar el modelo Piper ONNX (HTTP ${response.status}). Comprueba tu conexión.`,
    );
  }

  const contentLengthHeader = response.headers.get('content-length');
  const total = contentLengthHeader ? parseInt(contentLengthHeader, 10) : 0;

  if (!response.body) {
    const buf = await response.arrayBuffer();
    const bytes = new Uint8Array(buf);
    await storeModelInCache(bytes);
    return bytes;
  }

  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let loaded = 0;

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    if (value) {
      chunks.push(value);
      loaded += value.byteLength;
      const fraction = total > 0 ? Math.min(0.99, loaded / total) : 0;
      const loadedMb = (loaded / (1024 * 1024)).toFixed(1);
      const totalMb = total > 0 ? `${(total / (1024 * 1024)).toFixed(1)} MB` : '…';
      post({
        type: 'DOWNLOAD_PROGRESS',
        loaded,
        total,
        fraction,
        message: `Descargando voz Piper es_ES: ${loadedMb} MB / ${totalMb}`,
      });
    }
  }

  const full = new Uint8Array(loaded);
  let offset = 0;
  for (const c of chunks) {
    full.set(c, offset);
    offset += c.byteLength;
  }

  await storeModelInCache(full);
  post({
    type: 'DOWNLOAD_PROGRESS',
    loaded: full.byteLength,
    total: full.byteLength,
    fraction: 1,
    message: 'Voz neural guardada en caché local para uso sin conexión.',
  });
  return full;
}

async function storeModelInCache(bytes: Uint8Array): Promise<void> {
  if (typeof caches === 'undefined') return;
  try {
    const cache = await caches.open(MODEL_CACHE_NAME);
    const copy = new Uint8Array(bytes.byteLength);
    copy.set(bytes);
    await cache.put(
      MODEL_ONNX_URL,
      new Response(copy.buffer, {
        headers: {
          'Content-Type': 'application/octet-stream',
          'Content-Length': String(copy.byteLength),
        },
      }),
    );
  } catch {
    // Si el navegador limita Cache API, el modelo sigue funcionando en memoria para la sesión.
  }
}

async function loadPhonemizerFactory(): Promise<
  (opts: Record<string, unknown>) => Promise<PiperPhonemizeModule>
> {
  if (phonemizerFactory) return phonemizerFactory;
  const resp = await fetch('/wasm/piper_phonemize.js');
  if (!resp.ok) {
    throw new Error(`No se pudo cargar piper_phonemize.js (${resp.status}).`);
  }
  const scriptText = await resp.text();
  const factory = new Function(`${scriptText}\nreturn createPiperPhonemize;`)() as (
    opts: Record<string, unknown>,
  ) => Promise<PiperPhonemizeModule>;

  if (typeof factory !== 'function') {
    throw new Error('No se pudo inicializar createPiperPhonemize en el Web Worker.');
  }
  phonemizerFactory = factory;
  return factory;
}

async function ensureInitialized(): Promise<void> {
  if (ortSession && configCache && phonemizerFactory) return;
  if (initPromise) return initPromise;

  initPromise = (async () => {
    const config = await loadPiperConfig();
    await loadPhonemizerFactory();

    ort.env.wasm.wasmPaths = '/wasm/';
    // En Safari/iOS sin crossOriginIsolated, usar 1 hilo WASM evita fallos de SharedArrayBuffer.
    ort.env.wasm.numThreads =
      typeof self.crossOriginIsolated === 'boolean' && self.crossOriginIsolated
        ? Math.min(4, navigator.hardwareConcurrency || 2)
        : 1;

    const modelBytes = await loadModelOnnxBytes();
    ortSession = await ort.InferenceSession.create(modelBytes, {
      executionProviders: ['wasm'],
      graphOptimizationLevel: 'all',
    });

    post({
      type: 'READY',
      sampleRate: config.audio?.sample_rate || PIPER_SAMPLE_RATE,
      modelCached: await isModelCached(),
    });
  })().catch((err) => {
    initPromise = null;
    throw err;
  });

  return initPromise;
}

async function phonemizeSpanishToIds(text: string, config: PiperConfig): Promise<number[][]> {
  const factory = await loadPhonemizerFactory();
  const inputJson = JSON.stringify({ text });
  const inputPath = '/input.json';
  const outputPath = '/output.json';

  const mod = await factory({
    locateFile: (path: string) => `/wasm/${path}`,
    print: () => undefined,
    printErr: () => undefined,
  });

  mod.FS.writeFile(inputPath, inputJson);
  mod.callMain([
    '-l',
    config.espeak?.voice || 'es',
    '--input',
    inputPath,
    '--output',
    outputPath,
  ]);

  const rawOutput = mod.FS.readFile(outputPath, { encoding: 'utf8' });
  try {
    mod.FS.unlink(inputPath);
    mod.FS.unlink(outputPath);
  } catch {
    // Limpieza best-effort en Emscripten MEMFS
  }

  const lines = rawOutput
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter((l) => l.length > 0);

  const sentencePhonemeIds: number[][] = [];
  const bosId = config.phoneme_id_map['^']?.[0] ?? 1;
  const eosId = config.phoneme_id_map['$']?.[0] ?? 2;
  const padId = config.phoneme_id_map['_']?.[0] ?? 0;

  for (const line of lines) {
    try {
      const parsed = JSON.parse(line) as { phoneme_ids?: number[]; phonemes?: string[] };
      if (Array.isArray(parsed.phoneme_ids) && parsed.phoneme_ids.length > 0) {
        sentencePhonemeIds.push(parsed.phoneme_ids);
      } else if (Array.isArray(parsed.phonemes) && parsed.phonemes.length > 0) {
        const ids: number[] = [bosId, padId];
        for (const ph of parsed.phonemes) {
          const mapped = config.phoneme_id_map[ph];
          if (mapped && mapped.length > 0) {
            ids.push(...mapped, padId);
          }
        }
        ids.push(eosId);
        sentencePhonemeIds.push(ids);
      }
    } catch {
      // Ignorar líneas no JSON
    }
  }

  return sentencePhonemeIds;
}

async function synthesizeText(
  rawText: string,
  speakerId: number,
): Promise<{ wavBytes: Uint8Array; durationMs: number; synthesisMs: number }> {
  const startedAt = performance.now();
  await ensureInitialized();

  if (!ortSession || !configCache) {
    throw new Error('El motor Piper VITS no está inicializado.');
  }

  const prepared = prepareSpanishNarrationText(rawText);
  if (!prepared) {
    throw new Error('El fragmento no contiene texto narrable.');
  }

  const sentences = await phonemizeSpanishToIds(prepared, configCache);
  if (sentences.length === 0) {
    throw new Error('eSpeak NG no pudo generar fonemas para este fragmento.');
  }

  const sampleRate = configCache.audio?.sample_rate || PIPER_SAMPLE_RATE;
  const noiseScale = configCache.inference?.noise_scale ?? 0.667;
  const lengthScale = configCache.inference?.length_scale ?? 1.0;
  const noiseW = configCache.inference?.noise_w ?? 0.8;
  const safeSpeakerId = speakerId === 1 ? 1 : 0;

  const pcmSegments: Int16Array[] = [];
  const interSentenceSilence = new Int16Array(Math.round(sampleRate * 0.12));

  for (let sIdx = 0; sIdx < sentences.length; sIdx++) {
    const ids = sentences[sIdx];
    if (ids.length === 0) continue;

    const inputBigInts = BigInt64Array.from(ids.map((id) => BigInt(id)));
    const feeds: Record<string, ort.Tensor> = {
      input: new ort.Tensor('int64', inputBigInts, [1, ids.length]),
      input_lengths: new ort.Tensor('int64', BigInt64Array.from([BigInt(ids.length)]), [1]),
      scales: new ort.Tensor('float32', Float32Array.from([noiseScale, lengthScale, noiseW]), [3]),
    };

    if (configCache.num_speakers > 1) {
      feeds.sid = new ort.Tensor('int64', BigInt64Array.from([BigInt(safeSpeakerId)]), [1]);
    }

    const outputs = await ortSession.run(feeds);
    const outputTensor = outputs.output || Object.values(outputs)[0];
    if (!outputTensor) {
      throw new Error('Piper ONNX no devolvió tensor de audio.');
    }

    const floatSamples = outputTensor.data as Float32Array;
    if (floatSamples.length > 0 && hasAudibleSignal(floatSamples, 0.0005)) {
      pcmSegments.push(floatSamplesToPcm16Le(floatSamples));
      if (sIdx < sentences.length - 1) {
        pcmSegments.push(interSentenceSilence);
      }
    }
  }

  const totalSamples = pcmSegments.reduce((acc, seg) => acc + seg.length, 0);
  if (totalSamples === 0) {
    throw new Error('La síntesis local produjo audio vacío.');
  }

  const mergedPcm = new Int16Array(totalSamples);
  let offset = 0;
  for (const seg of pcmSegments) {
    mergedPcm.set(seg, offset);
    offset += seg.length;
  }

  const wavBytes = encodePcm16ToWav(mergedPcm, sampleRate, 1);
  const durationMs = getWavDurationMs(wavBytes);
  const synthesisMs = Math.round(performance.now() - startedAt);

  return { wavBytes, durationMs, synthesisMs };
}

self.onmessage = async (event: MessageEvent<WorkerRequest>) => {
  const msg = event.data;
  try {
    if (msg.type === 'CHECK_CACHE') {
      post({ type: 'CACHE_STATUS', cached: await isModelCached() });
      return;
    }

    if (msg.type === 'WARMUP') {
      await ensureInitialized();
      return;
    }

    if (msg.type === 'SYNTHESIZE') {
      const result = await synthesizeText(msg.text, msg.speakerId);
      const copy = new Uint8Array(result.wavBytes.byteLength);
      copy.set(result.wavBytes);
      post(
        {
          type: 'SYNTHESIS_RESULT',
          requestId: msg.requestId,
          wavBuffer: copy.buffer,
          durationMs: result.durationMs,
          synthesisMs: result.synthesisMs,
        },
        [copy.buffer],
      );
    }
  } catch (error) {
    const message =
      error instanceof Error ? error.message : 'Error inesperado en el motor de voz local Piper.';
    post({
      type: 'ERROR',
      requestId: msg.type === 'SYNTHESIZE' ? msg.requestId : undefined,
      message,
    });
  }
};
