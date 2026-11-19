# AudioLibreaMe — Versión Web Instalable (PWA)

Versión web multiplataforma (PWA) de **AudioLibreaMe**, diseñada como réplica funcional y visual fiel de la aplicación Android existente sin modificar ningún archivo Android.

## Infraestructura exclusiva: Firebase Hosting

- **Único alojamiento**: Firebase Hosting en el proyecto `audiolibreame` (`https://audiolibreame.web.app`).
- **Sin recursos externos en ejecución**: el modelo neuronal `es_ES-sharvard-medium.onnx`, su configuración `.onnx.json`, los binarios WASM (`piper_phonemize.*` y `onnxruntime-web`), las fuentes `Lora` y `Plus Jakarta Sans` y los iconos se sirven exclusivamente desde el propio dominio de Firebase Hosting.
- **Privacidad total en el dispositivo**: NO se utiliza Firestore ni Firebase Storage. Todos los EPUB, portadas, capítulos, audio sintetizado y progreso permanecen en **IndexedDB** dentro del navegador del usuario.

## Comandos locales

```bash
cd web
npm install
npm run dev      # Servidor de desarrollo Vite
npm test         # Suite automatizada Vitest (incluye inferencia real con Piper ONNX)
npm run build    # Sincronización de recursos + TypeScript + compilación en dist/
```

## Despliegue automático desde GitHub Actions a Firebase Hosting

El flujo `.github/workflows/deploy-web-firebase.yml` compila, ejecuta TypeScript y las pruebas Vitest y solo despliega en producción (`audiolibreame`, canal `live`) si todas las comprobaciones pasan.

### Configuración del secreto de autenticación en GitHub (una sola vez)

1. Abre [Firebase Console](https://console.firebase.google.com/) y selecciona el proyecto **`audiolibreame`**.
2. Ve a **Configuración del proyecto → Cuentas de servicio** (o en Google Cloud IAM → Cuentas de servicio) y crea/descarga una clave JSON con el rol **Firebase Hosting Admin** (`roles/firebasehosting.admin`).
3. En tu repositorio de GitHub (`nachosunen-maker/Audiolibro-`), abre **Settings → Secrets and variables → Actions → New repository secret**.
4. Crea el secreto con el nombre exacto:
   - **Name**: `FIREBASE_SERVICE_ACCOUNT_AUDIOLIBREAME`
   - **Value**: el contenido completo del archivo JSON de la cuenta de servicio.
5. Nunca subas ese archivo JSON al repositorio. Al hacer push a `main` con cambios en `web/` (o al ejecutar manualmente *Actions → Deploy AudioLibreaMe Web to Firebase Hosting → Run workflow*), GitHub desplegará automáticamente en `https://audiolibreame.web.app`.

### Despliegue manual local (alternativa desde tu equipo)

```bash
cd web
npm install
npm test
npm run build
npx firebase-tools login
npx firebase-tools deploy --only hosting --project audiolibreame
```
