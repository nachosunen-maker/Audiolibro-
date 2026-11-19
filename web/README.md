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

## Despliegue automático con Workload Identity Federation (OIDC)

El flujo `.github/workflows/deploy-web-firebase.yml` compila, ejecuta TypeScript y las pruebas Vitest y autentica contra Google Cloud mediante **OIDC** (`google-github-actions/auth@v2`), sin claves privadas JSON permanentes.

### Requisitos en Google Cloud (`audiolibreame`) y GitHub Actions

1. **Workload Identity Pool**: `github-actions-pool` (en `IAM y administración → Workload Identity Federation`).
2. **Proveedor OIDC**: `github-actions-provider` con emisor `https://token.actions.githubusercontent.com`, mapeo `google.subject=assertion.sub`, `attribute.repository=assertion.repository`, `attribute.ref=assertion.ref` y condición de atributo:
   ```
   assertion.repository == 'nachosunen-maker/Audiolibro-' && assertion.ref == 'refs/heads/main'
   ```
3. **Cuenta de servicio**: `firebase-adminsdk-fbsvc@audiolibreame.iam.gserviceaccount.com` con los roles mínimos:
   - `roles/firebasehosting.admin` (**Administrador de Firebase Hosting**)
   - `roles/serviceusage.serviceUsageConsumer` (**Consumidor de Service Usage**)
   - Vinculación de `roles/iam.workloadIdentityUser` para el principal del repositorio `nachosunen-maker/Audiolibro-`.
4. **Variable de repositorio en GitHub** (*Settings → Secrets and variables → Actions → Variables*):
   - Nombre: `GCP_WORKLOAD_IDENTITY_PROVIDER`
   - Valor: `projects/<PROJECT_NUMBER>/locations/global/workloadIdentityPools/github-actions-pool/providers/github-actions-provider`
