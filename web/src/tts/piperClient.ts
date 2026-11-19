import { NARRATOR_VOICES, type NarratorVoiceId } from '../domain/models';

export interface ModelDownloadEvent {
  loaded: number;
  total: number;
  fraction: number;
  message: string;
}

export interface SynthesisChunkResult {
  wavBytes: Uint8Array;
  durationMs: number;
  synthesisMs: number;
}

interface PendingJob {
  resolve: (result: SynthesisChunkResult) => void;
  reject: (err: Error) => void;
}

/**
 * Cliente principal del motor neuronal local Piper VITS (Web Worker).
 * Mantiene una única instancia del worker para reutilizar la sesión ONNX en RAM.
 */
export class PiperLocalClient {
  private worker: Worker | null = null;
  private pending = new Map<string, PendingJob>();
  private downloadListeners = new Set<(event: ModelDownloadEvent) => void>();
  private readyListeners = new Set<(cached: boolean) => void>();
  private isReady = false;
  private modelCached = false;

  private ensureWorker(): Worker {
    if (this.worker) return this.worker;
    const worker = new Worker(new URL('./piperWorker.ts', import.meta.url), {
      type: 'module',
    });

    worker.onmessage = (event: MessageEvent) => {
      const data = event.data as
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

      if (data.type === 'DOWNLOAD_PROGRESS') {
        for (const listener of this.downloadListeners) {
          listener({
            loaded: data.loaded,
            total: data.total,
            fraction: data.fraction,
            message: data.message,
          });
        }
        return;
      }

      if (data.type === 'READY') {
        this.isReady = true;
        this.modelCached = data.modelCached;
        for (const listener of this.readyListeners) {
          listener(data.modelCached);
        }
        return;
      }

      if (data.type === 'CACHE_STATUS') {
        this.modelCached = data.cached;
        return;
      }

      if (data.type === 'SYNTHESIS_RESULT') {
        const job = this.pending.get(data.requestId);
        if (job) {
          this.pending.delete(data.requestId);
          job.resolve({
            wavBytes: new Uint8Array(data.wavBuffer),
            durationMs: data.durationMs,
            synthesisMs: data.synthesisMs,
          });
        }
        return;
      }

      if (data.type === 'ERROR') {
        const err = new Error(data.message);
        if (data.requestId) {
          const job = this.pending.get(data.requestId);
          if (job) {
            this.pending.delete(data.requestId);
            job.reject(err);
          }
        } else {
          for (const [, job] of this.pending) {
            job.reject(err);
          }
          this.pending.clear();
        }
      }
    };

    worker.onerror = (err) => {
      const error = new Error(err.message || 'Fallo crítico en el worker de voz Piper.');
      for (const [, job] of this.pending) {
        job.reject(error);
      }
      this.pending.clear();
    };

    this.worker = worker;
    return worker;
  }

  onDownloadProgress(listener: (event: ModelDownloadEvent) => void): () => void {
    this.downloadListeners.add(listener);
    return () => this.downloadListeners.delete(listener);
  }

  onReady(listener: (cached: boolean) => void): () => void {
    this.readyListeners.add(listener);
    if (this.isReady) listener(this.modelCached);
    return () => this.readyListeners.delete(listener);
  }

  getModelCached(): boolean {
    return this.modelCached;
  }

  async checkCache(): Promise<void> {
    if (typeof Worker === 'undefined') return;
    this.ensureWorker().postMessage({ type: 'CHECK_CACHE' });
  }

  async warmup(): Promise<void> {
    if (typeof Worker === 'undefined') {
      throw new Error('Este navegador no soporta Web Workers para síntesis local.');
    }
    this.ensureWorker().postMessage({ type: 'WARMUP' });
  }

  synthesizeChunk(text: string, voiceId: NarratorVoiceId): Promise<SynthesisChunkResult> {
    if (typeof Worker === 'undefined') {
      return Promise.reject(
        new Error('Este navegador no soporta Web Workers para síntesis neuronal local.'),
      );
    }

    const worker = this.ensureWorker();
    const requestId = `req_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
    const speakerId = NARRATOR_VOICES[voiceId].speakerId;

    return new Promise<SynthesisChunkResult>((resolve, reject) => {
      this.pending.set(requestId, { resolve, reject });
      worker.postMessage({
        type: 'SYNTHESIZE',
        requestId,
        text,
        speakerId,
      });
    });
  }

  terminate(): void {
    if (this.worker) {
      this.worker.terminate();
      this.worker = null;
      this.isReady = false;
    }
    for (const [, job] of this.pending) {
      job.reject(new Error('Síntesis cancelada por el usuario.'));
    }
    this.pending.clear();
  }
}

export const piperClient = new PiperLocalClient();
