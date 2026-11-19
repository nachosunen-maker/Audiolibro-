import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  copyFileSync,
  createReadStream,
  createWriteStream,
  existsSync,
  mkdirSync,
  mkdtempSync,
  rmSync,
  statSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const webRoot = resolve(__dirname, '..');

const wasmDest = resolve(webRoot, 'public/wasm');
const modelsDest = resolve(webRoot, 'public/assets/models');

const MODEL_ARCHIVE_URL =
  'https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-es_ES-sharvard-medium.tar.bz2';
const EXPECTED_ARCHIVE_SHA256 =
  'b30a7a83df0518f0ee1c7039506648cade99f1f9b498fc49ed2ced2e2536bb5a';
const EXPECTED_ONNX_SHA256 =
  'b1281e1c9d9ddd2c4c509d85b97f75ac70d28dfc0fc784c6699af7ec21866417';
const EXPECTED_ONNX_SIZE_BYTES = 76_682_531;

const ONNX_FILENAME = 'es_ES-sharvard-medium.onnx';
const CONFIG_FILENAME = 'es_ES-sharvard-medium.onnx.json';

async function sha256File(filePath) {
  const hash = createHash('sha256');
  await new Promise((resolvePromise, rejectPromise) => {
    const stream = createReadStream(filePath);
    stream.on('data', (chunk) => hash.update(chunk));
    stream.on('end', resolvePromise);
    stream.on('error', rejectPromise);
  });
  return hash.digest('hex');
}

async function isValidLocalOnnx(filePath) {
  if (!existsSync(filePath)) return false;
  const stats = statSync(filePath);
  if (stats.size !== EXPECTED_ONNX_SIZE_BYTES) return false;
  const actualSha = await sha256File(filePath);
  return actualSha === EXPECTED_ONNX_SHA256;
}

function syncWasmAssets() {
  mkdirSync(wasmDest, { recursive: true });

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
}

async function ensureOfficialPiperModel() {
  mkdirSync(modelsDest, { recursive: true });

  const onnxDest = resolve(modelsDest, ONNX_FILENAME);
  const configDest = resolve(modelsDest, CONFIG_FILENAME);

  if ((await isValidLocalOnnx(onnxDest)) && existsSync(configDest)) {
    console.log(
      `[sync-runtime-assets] Modelo Piper ONNX ya verificado (${EXPECTED_ONNX_SIZE_BYTES} bytes, SHA-256 OK). Se omite la descarga.`,
    );
    return;
  }

  const workDir = mkdtempSync(join(tmpdir(), 'audiolibreame-piper-'));
  const archivePath = join(workDir, 'vits-piper-es_ES-sharvard-medium.tar.bz2');

  try {
    console.log(`[sync-runtime-assets] Descargando archivo oficial de Piper: ${MODEL_ARCHIVE_URL}`);
    const response = await fetch(MODEL_ARCHIVE_URL, {
      redirect: 'follow',
      headers: { 'User-Agent': 'AudioLibreaMe-Web-Build' },
    });

    if (!response.ok || !response.body) {
      throw new Error(
        `Error al descargar el archivo oficial de Piper (HTTP ${response.status} ${response.statusText}).`,
      );
    }

    await pipeline(Readable.fromWeb(response.body), createWriteStream(archivePath));

    const archiveSha256 = await sha256File(archivePath);
    if (archiveSha256 !== EXPECTED_ARCHIVE_SHA256) {
      throw new Error(
        `Integridad SHA-256 inválida para el archivo comprimido. Esperado: ${EXPECTED_ARCHIVE_SHA256}, obtenido: ${archiveSha256}`,
      );
    }
    console.log(`[sync-runtime-assets] SHA-256 del archivo comprimido verificado (${archiveSha256}).`);

    // Extraer únicamente el modelo ONNX y su JSON usando tarfile (r:bz2) de Python 3 (disponible en Ubuntu runner y contenedores mínimos sin binario bzip2)
    const archivePrefix = 'vits-piper-es_ES-sharvard-medium';
    const pyExtract = [
      'import sys, tarfile',
      'archive, dest, prefix, onnx_name, cfg_name = sys.argv[1:6]',
      'targets = {f"{prefix}/{onnx_name}", f"{prefix}/{cfg_name}"}',
      'with tarfile.open(archive, "r:bz2") as tf:',
      '    members = [m for m in tf.getmembers() if m.name in targets]',
      '    if len(members) != len(targets):',
      '        raise RuntimeError(f"Faltan archivos esperados en el tar.bz2: {sorted(targets)}")',
      '    tf.extractall(dest, members=members)',
    ].join('\n');

    execFileSync(
      'python3',
      ['-c', pyExtract, archivePath, workDir, archivePrefix, ONNX_FILENAME, CONFIG_FILENAME],
      { stdio: 'pipe' },
    );

    const extractedOnnx = join(workDir, archivePrefix, ONNX_FILENAME);
    const extractedConfig = join(workDir, archivePrefix, CONFIG_FILENAME);

    if (!existsSync(extractedOnnx) || !existsSync(extractedConfig)) {
      throw new Error(
        'La extracción del archivo oficial no generó es_ES-sharvard-medium.onnx y es_ES-sharvard-medium.onnx.json.',
      );
    }

    const onnxSize = statSync(extractedOnnx).size;
    if (onnxSize !== EXPECTED_ONNX_SIZE_BYTES) {
      throw new Error(
        `Tamaño inválido para ${ONNX_FILENAME}. Esperado: ${EXPECTED_ONNX_SIZE_BYTES} bytes, obtenido: ${onnxSize} bytes.`,
      );
    }

    const onnxSha256 = await sha256File(extractedOnnx);
    if (onnxSha256 !== EXPECTED_ONNX_SHA256) {
      throw new Error(
        `Integridad SHA-256 inválida para ${ONNX_FILENAME}. Esperado: ${EXPECTED_ONNX_SHA256}, obtenido: ${onnxSha256}`,
      );
    }

    copyFileSync(extractedOnnx, onnxDest);
    copyFileSync(extractedConfig, configDest);

    console.log(
      `[sync-runtime-assets] Modelo Piper ONNX y JSON verificados y copiados (${onnxSize} bytes, SHA-256 ${onnxSha256}).`,
    );
  } finally {
    rmSync(workDir, { recursive: true, force: true });
  }
}

async function main() {
  syncWasmAssets();
  await ensureOfficialPiperModel();
  console.log('[sync-runtime-assets] Recursos WASM y modelo Piper ONNX listos en web/public/.');
}

main().catch((err) => {
  console.error('[sync-runtime-assets] ERROR:', err instanceof Error ? err.message : err);
  process.exit(1);
});
