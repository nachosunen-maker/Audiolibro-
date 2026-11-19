/**
 * Preprocesado de texto español para narración y utilidades WAV PCM16.
 * Réplica fiel de PiperAudioSynthesisProvider.kt y WavAudioUtils de Android.
 */

export const PIPER_SAMPLE_RATE = 22050;
export const WAV_HEADER_SIZE = 44;

/**
 * Normaliza el texto en español antes de enviarlo a eSpeak NG / Piper VITS:
 * - Limpia caracteres de control invisibles y espacios redundantes.
 * - Normaliza comillas tipográficas y guiones largos de diálogo.
 * - Asegura cierre de puntuación para evitar cortes abruptos al final del fragmento.
 */
export function prepareSpanishNarrationText(raw: string): string {
  let text = raw
    .replace(/\u00A0/g, ' ')
    .replace(/[\u200B-\u200D\uFEFF]/g, '')
    .replace(/[“”«»]/g, '"')
    .replace(/[‘’]/g, "'")
    .replace(/—/g, ' — ')
    .replace(/…/g, '...')
    .replace(/\s*\n+\s*/g, '. ')
    .replace(/\s{2,}/g, ' ')
    .replace(/\.\s*\./g, '.')
    .trim();

  if (!text) return '';

  // Garantizar que termina con puntuación para que Piper genere cadencia final natural.
  if (!/[.!?…]$/.test(text)) {
    text = `${text}.`;
  }
  return text;
}

/**
 * Convierte muestras Float32 [-1.0, 1.0] de Piper ONNX a PCM 16-bit Little-Endian.
 */
export function floatSamplesToPcm16Le(samples: Float32Array): Int16Array {
  const pcm = new Int16Array(samples.length);
  for (let i = 0; i < samples.length; i++) {
    const clamped = Math.max(-1, Math.min(1, samples[i]));
    pcm[i] = clamped < 0 ? Math.round(clamped * 32768) : Math.round(clamped * 32767);
  }
  return pcm;
}

/**
 * Comprueba que el audio sintetizado contiene señal audible real (no silencio absoluto).
 */
export function hasAudibleSignal(samples: Float32Array, rmsThreshold = 0.001): boolean {
  if (samples.length === 0) return false;
  let sumSq = 0;
  for (let i = 0; i < samples.length; i++) {
    const s = samples[i];
    sumSq += s * s;
  }
  const rms = Math.sqrt(sumSq / samples.length);
  return rms >= rmsThreshold;
}

/**
 * Empaqueta PCM 16-bit mono en un archivo WAV RIFF estándar de 44 bytes de cabecera.
 */
export function encodePcm16ToWav(
  pcm16: Int16Array,
  sampleRate: number = PIPER_SAMPLE_RATE,
  numChannels: number = 1,
): Uint8Array {
  const bytesPerSample = 2;
  const dataSize = pcm16.length * bytesPerSample;
  const buffer = new ArrayBuffer(WAV_HEADER_SIZE + dataSize);
  const view = new DataView(buffer);

  writeAscii(view, 0, 'RIFF');
  view.setUint32(4, 36 + dataSize, true);
  writeAscii(view, 8, 'WAVE');
  writeAscii(view, 12, 'fmt ');
  view.setUint32(16, 16, true); // Subchunk1Size (PCM)
  view.setUint16(20, 1, true); // AudioFormat = 1 (PCM)
  view.setUint16(22, numChannels, true);
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * numChannels * bytesPerSample, true); // ByteRate
  view.setUint16(32, numChannels * bytesPerSample, true); // BlockAlign
  view.setUint16(34, 16, true); // BitsPerSample
  writeAscii(view, 36, 'data');
  view.setUint32(40, dataSize, true);

  let offset = WAV_HEADER_SIZE;
  for (let i = 0; i < pcm16.length; i++) {
    view.setInt16(offset, pcm16[i], true);
    offset += 2;
  }

  return new Uint8Array(buffer);
}

/**
 * Valida la integridad mínima de un WAV PCM generado.
 */
export function isValidWavBytes(bytes: Uint8Array): boolean {
  if (bytes.byteLength <= WAV_HEADER_SIZE) return false;
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const riff = readAscii(view, 0, 4);
  const wave = readAscii(view, 8, 4);
  const fmt = readAscii(view, 12, 4);
  const data = readAscii(view, 36, 4);
  const dataSize = view.getUint32(40, true);
  return riff === 'RIFF' && wave === 'WAVE' && fmt === 'fmt ' && data === 'data' && dataSize > 0;
}

/**
 * Calcula la duración exacta en milisegundos de un WAV PCM válido.
 */
export function getWavDurationMs(bytes: Uint8Array): number {
  if (!isValidWavBytes(bytes)) return 0;
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const byteRate = view.getUint32(28, true);
  const dataSize = view.getUint32(40, true);
  if (byteRate <= 0) return 0;
  return Math.round((dataSize / byteRate) * 1000);
}

/**
 * Concatena múltiples WAV PCM mono (mismo sample rate) en un único WAV exportable
 * añadiendo una breve pausa natural entre fragmentos (por defecto 180 ms).
 */
export function mergeWavBuffers(
  wavParts: Uint8Array[],
  pauseBetweenChunksMs = 180,
  sampleRate = PIPER_SAMPLE_RATE,
): Uint8Array {
  const validParts = wavParts.filter((p) => isValidWavBytes(p));
  if (validParts.length === 0) {
    return encodePcm16ToWav(new Int16Array(0), sampleRate, 1);
  }

  const pauseSamples = Math.max(0, Math.round((pauseBetweenChunksMs / 1000) * sampleRate));
  const pauseBytes = pauseSamples * 2;

  let totalDataBytes = 0;
  for (let i = 0; i < validParts.length; i++) {
    totalDataBytes += validParts[i].byteLength - WAV_HEADER_SIZE;
    if (i < validParts.length - 1) {
      totalDataBytes += pauseBytes;
    }
  }

  const output = new Uint8Array(WAV_HEADER_SIZE + totalDataBytes);
  const view = new DataView(output.buffer);

  writeAscii(view, 0, 'RIFF');
  view.setUint32(4, 36 + totalDataBytes, true);
  writeAscii(view, 8, 'WAVE');
  writeAscii(view, 12, 'fmt ');
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);
  view.setUint16(22, 1, true);
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * 2, true);
  view.setUint16(32, 2, true);
  view.setUint16(34, 16, true);
  writeAscii(view, 36, 'data');
  view.setUint32(40, totalDataBytes, true);

  let offset = WAV_HEADER_SIZE;
  for (let i = 0; i < validParts.length; i++) {
    const pcmData = validParts[i].subarray(WAV_HEADER_SIZE);
    output.set(pcmData, offset);
    offset += pcmData.byteLength;
    if (i < validParts.length - 1 && pauseBytes > 0) {
      // Silencio (ceros ya inicializados en Uint8Array)
      offset += pauseBytes;
    }
  }

  return output;
}

function writeAscii(view: DataView, offset: number, str: string): void {
  for (let i = 0; i < str.length; i++) {
    view.setUint8(offset + i, str.charCodeAt(i));
  }
}

function readAscii(view: DataView, offset: number, length: number): string {
  let out = '';
  for (let i = 0; i < length; i++) {
    out += String.fromCharCode(view.getUint8(offset + i));
  }
  return out;
}
