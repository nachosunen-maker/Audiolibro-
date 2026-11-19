# AudioLibreaMe — Versión Web Instalable (PWA)

Versión web multiplataforma (PWA) de **AudioLibreaMe**, diseñada como réplica funcional y visual fiel de la aplicación Android existente sin modificar ningún archivo Android.

## Características principales

- **Identidad visual fiel a Android**: temas *Papel y Tinta* (claro) y *Tinta Nocturna* (oscuro), tipografías `Lora` y `Plus Jakarta Sans`, y las 5 pantallas principales (`Biblioteca`, `Detalle del libro`, `Lector de capítulo`, `Síntesis progresiva` y `Reproductor`) más `MiniPlayerBar`.
- **100% local y privada**: los archivos EPUB, portadas, capítulos, audio generado y progreso se almacenan únicamente en **IndexedDB** en el dispositivo del usuario. Nunca se suben a servidores externos.
- **Voz neural local Piper VITS (`es_ES-sharvard-medium`)**: mismo modelo neuronal español que Android (voces masculina `sid=0` y femenina `sid=1` a `22.050 Hz`), ejecutado en un **Web Worker** con `piper-phonemize` (eSpeak NG WASM) + `onnxruntime-web`. El modelo `.onnx` se descarga bajo demanda la primera vez y queda guardado en Cache API para uso sin conexión.
- **Síntesis bajo demanda por capítulo + ventana deslizante en RAM**: sin conversión masiva del libro completo; los fragmentos reproducidos se liberan de RAM inmediatamente (`URL.revokeObjectURL`) manteniendo una ventana activa acotada (`20 s` en dispositivos con memoria limitada / móviles, `30 s` estándar) y recuperando desde IndexedDB al retroceder sin resintetizar.
- **PWA instalable y offline**: `manifest.webmanifest`, `sw.js` con actualizaciones controladas, iconos adaptados y soporte de **Media Session API**.

## Comandos locales

```bash
cd web
npm install
npm run dev      # Servidor de desarrollo Vite
npm test         # Suite automatizada Vitest
npm run build    # Comprobación TypeScript + compilación de producción (dist/)
```

## Despliegue estático en Firebase Hosting (sin Firestore ni Storage)

1. Copia `.firebaserc.example` a `.firebaserc` e indica tu ID de proyecto de Firebase Hosting.
2. Compila y despliega solo los archivos estáticos:

```bash
cd web
npm run build
firebase deploy --only hosting
```
