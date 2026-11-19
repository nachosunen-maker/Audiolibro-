import { existsSync, readFileSync, statSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const distDir = resolve(__dirname, '../dist');

const EXPECTED_ONNX_SIZE_BYTES = 76_682_531;

const requiredFiles = [
  'index.html',
  'manifest.webmanifest',
  'sw.js',
  'assets/icon-192.png',
  'assets/icon-512.png',
  'assets/apple-touch-icon.png',
  'assets/img_empty_library.jpg',
  'assets/fonts/lora.ttf',
  'assets/fonts/plus_jakarta_sans.ttf',
  'assets/models/es_ES-sharvard-medium.onnx',
  'assets/models/es_ES-sharvard-medium.onnx.json',
  'wasm/piper_phonemize.js',
  'wasm/piper_phonemize.wasm',
  'wasm/piper_phonemize.data',
  'wasm/ort-wasm-simd-threaded.mjs',
  'wasm/ort-wasm-simd-threaded.wasm',
];

for (const rel of requiredFiles) {
  const full = resolve(distDir, rel);
  if (!existsSync(full)) {
    console.error(`[verify-dist] ERROR: Falta archivo obligatorio en dist/: ${rel}`);
    process.exit(1);
  }
}

const onnxPath = resolve(distDir, 'assets/models/es_ES-sharvard-medium.onnx');
const onnxSize = statSync(onnxPath).size;
if (onnxSize !== EXPECTED_ONNX_SIZE_BYTES) {
  console.error(
    `[verify-dist] ERROR: Tamaño inesperado en dist/assets/models/es_ES-sharvard-medium.onnx (${onnxSize} != ${EXPECTED_ONNX_SIZE_BYTES}).`,
  );
  process.exit(1);
}

const indexHtml = readFileSync(resolve(distDir, 'index.html'), 'utf8');
if (!indexHtml.includes('AudioLibreaMe')) {
  console.error('[verify-dist] ERROR: dist/index.html no contiene la identidad AudioLibreaMe.');
  process.exit(1);
}

console.log(
  `[verify-dist] OK: ${requiredFiles.length} recursos verificados en web/dist/ (ONNX ${onnxSize} bytes).`,
);
