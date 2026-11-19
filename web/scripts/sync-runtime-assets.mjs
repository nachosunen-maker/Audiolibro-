import { copyFileSync, existsSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const webRoot = resolve(__dirname, '..');
const repoRoot = resolve(webRoot, '..');

const wasmDest = resolve(webRoot, 'public/wasm');
const modelsDest = resolve(webRoot, 'public/assets/models');

mkdirSync(wasmDest, { recursive: true });
mkdirSync(modelsDest, { recursive: true });

const wasmCopies = [
  ['node_modules/@diffusionstudio/piper-wasm/build/piper_phonemize.js', 'piper_phonemize.js'],
  ['node_modules/@diffusionstudio/piper-wasm/build/piper_phonemize.wasm', 'piper_phonemize.wasm'],
  ['node_modules/@diffusionstudio/piper-wasm/build/piper_phonemize.data', 'piper_phonemize.data'],
  ['node_modules/onnxruntime-web/dist/ort-wasm-simd-threaded.mjs', 'ort-wasm-simd-threaded.mjs'],
  ['node_modules/onnxruntime-web/dist/ort-wasm-simd-threaded.wasm', 'ort-wasm-simd-threaded.wasm'],
  ['node_modules/onnxruntime-web/dist/ort-wasm-simd-threaded.jsep.mjs', 'ort-wasm-simd-threaded.jsep.mjs'],
  ['node_modules/onnxruntime-web/dist/ort-wasm-simd-threaded.jsep.wasm', 'ort-wasm-simd-threaded.jsep.wasm'],
];

for (const [relSrc, fileName] of wasmCopies) {
  const src = resolve(webRoot, relSrc);
  const dest = resolve(wasmDest, fileName);
  if (existsSync(src)) {
    copyFileSync(src, dest);
  } else if (!existsSync(dest)) {
    throw new Error(`Falta recurso WASM obligatorio: ${src}`);
  }
}

const modelFiles = [
  'es_ES-sharvard-medium.onnx',
  'es_ES-sharvard-medium.onnx.json',
];

for (const fileName of modelFiles) {
  const dest = resolve(modelsDest, fileName);
  if (existsSync(dest)) continue;
  const androidModelSrc = resolve(
    repoRoot,
    'app/src/main/assets/tts/vits-piper-es_ES-sharvard-medium',
    fileName,
  );
  if (existsSync(androidModelSrc)) {
    copyFileSync(androidModelSrc, dest);
  } else {
    throw new Error(
      `Falta el archivo oficial del modelo Piper (${fileName}) en ${dest} y en ${androidModelSrc}.`,
    );
  }
}

console.log('[sync-runtime-assets] Recursos WASM y modelo Piper ONNX listos en public/.');
