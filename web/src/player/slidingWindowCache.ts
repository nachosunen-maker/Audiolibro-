import { resolveAdaptiveWindowConfig, type AdaptiveWindowConfig } from '../domain/progressiveProgress';
import type { ActiveWindowTelemetry } from '../domain/models';

export interface WindowChunkEntry {
  chunkIndex: number;
  objectUrl: string;
  durationMs: number;
  byteSize: number;
}

export interface ObjectUrlAdapter {
  createObjectUrl(blob: Blob): string;
  revokeObjectUrl(url: string): void;
}

const browserUrlAdapter: ObjectUrlAdapter = {
  createObjectUrl: (blob: Blob) => URL.createObjectURL(blob),
  revokeObjectUrl: (url: string) => URL.revokeObjectURL(url),
};

/**
 * Caché de ventana deslizante en RAM para fragmentos de audio WAV.
 * Réplica fiel de la gestión inteligente de memoria de Android:
 * - Libera inmediatamente cualquier fragmento anterior al índice actual (revoca ObjectURL).
 * - Limita los fragmentos precargados hacia adelante a la ventana configurada (20s / 30s).
 * - Si el usuario retrocede a un fragmento ya liberado, se vuelve a cargar desde IndexedDB sin resintetizar.
 */
export class SlidingWindowAudioCache {
  private readonly entries = new Map<number, WindowChunkEntry>();
  private readonly config: AdaptiveWindowConfig;
  private readonly urlAdapter: ObjectUrlAdapter;
  private releasedCount = 0;

  constructor(
    config: AdaptiveWindowConfig = resolveAdaptiveWindowConfig(),
    urlAdapter: ObjectUrlAdapter = browserUrlAdapter,
  ) {
    this.config = config;
    this.urlAdapter = urlAdapter;
  }

  getConfig(): AdaptiveWindowConfig {
    return this.config;
  }

  has(chunkIndex: number): boolean {
    return this.entries.has(chunkIndex);
  }

  get(chunkIndex: number): WindowChunkEntry | undefined {
    return this.entries.get(chunkIndex);
  }

  getActiveCount(): number {
    return this.entries.size;
  }

  getReleasedCount(): number {
    return this.releasedCount;
  }

  /**
   * Carga un fragmento en RAM (creando un ObjectURL) y aplica inmediatamente
   * la política de ventana deslizante alrededor de `currentChunkIndex`.
   */
  putChunk(
    chunkIndex: number,
    wavBytes: Uint8Array,
    durationMs: number,
    currentChunkIndex: number,
  ): WindowChunkEntry {
    const existing = this.entries.get(chunkIndex);
    if (existing) {
      this.evictOutsideWindow(currentChunkIndex);
      return existing;
    }

    const copy = new Uint8Array(wavBytes.byteLength);
    copy.set(wavBytes);
    const blob = new Blob([copy.buffer], { type: 'audio/wav' });
    const objectUrl = this.urlAdapter.createObjectUrl(blob);
    const entry: WindowChunkEntry = {
      chunkIndex,
      objectUrl,
      durationMs: Math.max(0, durationMs),
      byteSize: wavBytes.byteLength,
    };
    this.entries.set(chunkIndex, entry);
    this.evictOutsideWindow(currentChunkIndex);
    return entry;
  }

  /**
   * Libera inmediatamente todos los fragmentos ya reproducidos (< currentChunkIndex)
   * y recorta los fragmentos futuros que excedan la ventana de tiempo o el límite de URLs activas.
   */
  evictOutsideWindow(currentChunkIndex: number): number[] {
    const evicted: number[] = [];

    // 1. Liberación inmediata de todo lo anterior al fragmento actual.
    for (const [idx, entry] of this.entries) {
      if (idx < currentChunkIndex) {
        this.urlAdapter.revokeObjectUrl(entry.objectUrl);
        this.entries.delete(idx);
        this.releasedCount++;
        evicted.push(idx);
      }
    }

    // 2. Mantener orden ascendente desde currentChunkIndex dentro de la ventana forward.
    const sorted = Array.from(this.entries.values()).sort(
      (a, b) => a.chunkIndex - b.chunkIndex,
    );

    let forwardBufferedMs = 0;
    let keptCount = 0;

    for (const entry of sorted) {
      const isCurrent = entry.chunkIndex === currentChunkIndex;
      if (isCurrent) {
        keptCount++;
        continue;
      }

      const withinUrlLimit = keptCount < this.config.maxActiveObjectUrls;
      const withinTimeWindow = forwardBufferedMs < this.config.targetForwardWindowMs;

      if (withinUrlLimit && withinTimeWindow) {
        forwardBufferedMs += entry.durationMs;
        keptCount++;
      } else {
        this.urlAdapter.revokeObjectUrl(entry.objectUrl);
        this.entries.delete(entry.chunkIndex);
        this.releasedCount++;
        evicted.push(entry.chunkIndex);
      }
    }

    return evicted;
  }

  /**
   * Indica si todavía hay espacio en la ventana forward para precargar el siguiente fragmento.
   */
  canPreloadNext(currentChunkIndex: number): boolean {
    if (this.entries.size >= this.config.maxActiveObjectUrls) return false;
    let forwardMs = 0;
    for (const entry of this.entries.values()) {
      if (entry.chunkIndex > currentChunkIndex) {
        forwardMs += entry.durationMs;
      }
    }
    return forwardMs < this.config.targetForwardWindowMs;
  }

  getTelemetry(currentChunkIndex: number): ActiveWindowTelemetry {
    const indices = Array.from(this.entries.keys()).sort((a, b) => a - b);
    let forwardMs = 0;
    for (const entry of this.entries.values()) {
      if (entry.chunkIndex > currentChunkIndex) {
        forwardMs += entry.durationMs;
      }
    }

    return {
      activeWindowStartChunk: indices.length > 0 ? indices[0] : currentChunkIndex,
      activeWindowEndChunk: indices.length > 0 ? indices[indices.length - 1] : currentChunkIndex,
      bufferedAheadMs: forwardMs,
      activeObjectUrlCount: this.entries.size,
      targetWindowSeconds: Math.round(this.config.targetForwardWindowMs / 1000),
      memoryMode: this.config.mode,
      releasedChunksCount: this.releasedCount,
    };
  }

  clearAll(): void {
    for (const entry of this.entries.values()) {
      this.urlAdapter.revokeObjectUrl(entry.objectUrl);
      this.releasedCount++;
    }
    this.entries.clear();
  }
}
