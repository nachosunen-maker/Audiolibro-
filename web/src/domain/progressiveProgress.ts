/**
 * Cálculo de progreso ponderado por caracteres y política adaptativa de ventana RAM.
 * Réplica fiel de ProgressiveChapterProgress y AdaptiveBufferPolicy de Android.
 */

export interface ChapterProgressMetrics {
  completedChunks: number;
  totalChunks: number;
  synthesizedChars: number;
  totalChars: number;
  fraction: number; // 0..1
  percent: number; // 0..100
}

export function computeChapterProgress(
  completedChunks: number,
  totalChunks: number,
  synthesizedChars: number,
  totalChars: number,
): ChapterProgressMetrics {
  const safeTotalChunks = Math.max(0, totalChunks);
  const safeCompleted = Math.min(Math.max(0, completedChunks), safeTotalChunks);
  const safeTotalChars = Math.max(0, totalChars);
  const safeSynthChars = Math.min(Math.max(0, synthesizedChars), safeTotalChars);

  let fraction = 0;
  if (safeTotalChars > 0) {
    fraction = safeSynthChars / safeTotalChars;
  } else if (safeTotalChunks > 0) {
    fraction = safeCompleted / safeTotalChunks;
  }
  fraction = Math.max(0, Math.min(1, fraction));

  return {
    completedChunks: safeCompleted,
    totalChunks: safeTotalChunks,
    synthesizedChars: safeSynthChars,
    totalChars: safeTotalChars,
    fraction,
    percent: Math.round(fraction * 100),
  };
}

export interface AdaptiveWindowConfig {
  mode: 'CONSTRAINED' | 'STANDARD';
  targetForwardWindowMs: number;
  maxActiveObjectUrls: number;
  minChunksBeforePlayback: number;
}

/**
 * Política de memoria adaptativa para dispositivos móviles (iPhone/Android) y escritorio:
 * - Dispositivos con <= 4 GB de RAM reportada o móviles táctiles: ventana forward de 20 s (máx. 2 URLs activas).
 * - Equipos con mayor memoria: ventana forward de 30 s (máx. 3 URLs activas).
 * En todos los casos los fragmentos ya reproducidos se liberan de RAM inmediatamente.
 */
export function resolveAdaptiveWindowConfig(): AdaptiveWindowConfig {
  const nav = typeof navigator !== 'undefined' ? (navigator as Navigator & { deviceMemory?: number }) : undefined;
  const deviceMemoryGb = nav?.deviceMemory;
  const isTouchMobile =
    typeof navigator !== 'undefined' &&
    /iPhone|iPad|iPod|Android/i.test(navigator.userAgent || '');

  const isConstrained =
    (typeof deviceMemoryGb === 'number' && deviceMemoryGb <= 4) || isTouchMobile;

  if (isConstrained) {
    return {
      mode: 'CONSTRAINED',
      targetForwardWindowMs: 20_000,
      maxActiveObjectUrls: 2,
      minChunksBeforePlayback: 1,
    };
  }

  return {
    mode: 'STANDARD',
    targetForwardWindowMs: 30_000,
    maxActiveObjectUrls: 3,
    minChunksBeforePlayback: 1,
  };
}
