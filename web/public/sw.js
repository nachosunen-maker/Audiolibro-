const APP_CACHE = 'audiolibreame-shell-v2';
const MODEL_CACHE = 'audiolibreame-piper-models-v1';

const PRECACHE_URLS = [
  '/',
  '/index.html',
  '/manifest.webmanifest',
  '/assets/img_empty_library.jpg',
  '/assets/icon-192.png',
  '/assets/icon-512.png',
  '/assets/apple-touch-icon.png',
  '/assets/fonts/lora.ttf',
  '/assets/fonts/plus_jakarta_sans.ttf',
  '/assets/models/es_ES-sharvard-medium.onnx.json',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(APP_CACHE).then((cache) => cache.addAll(PRECACHE_URLS))
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) =>
        Promise.all(
          keys
            .filter((key) => key !== APP_CACHE && key !== MODEL_CACHE)
            .map((key) => caches.delete(key))
        )
      )
      .then(() => self.clients.claim())
  );
});

self.addEventListener('message', (event) => {
  if (event.data && event.data.type === 'SKIP_WAITING') {
    self.skipWaiting();
  }
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;

  const url = new URL(req.url);
  // Solo gestionar recursos del mismo dominio (Firebase Hosting)
  if (url.origin !== self.location.origin) return;

  // Caché dedicada y persistente para el modelo ONNX Piper y binarios WASM/datos de ejecución
  if (
    url.pathname.endsWith('.onnx') ||
    url.pathname.endsWith('.onnx.json') ||
    url.pathname.endsWith('.wasm') ||
    url.pathname.endsWith('.data') ||
    url.pathname.startsWith('/wasm/')
  ) {
    event.respondWith(
      caches.open(MODEL_CACHE).then(async (cache) => {
        const cached = await cache.match(req);
        if (cached) return cached;
        const response = await fetch(req);
        if (response && response.ok) {
          cache.put(req, response.clone());
        }
        return response;
      })
    );
    return;
  }

  // Navegación SPA: network-first con respaldo offline en index.html para evitar versiones antiguas
  if (req.mode === 'navigate') {
    event.respondWith(
      fetch(req)
        .then((res) => {
          if (res && res.ok) {
            const copy = res.clone();
            caches.open(APP_CACHE).then((cache) => cache.put('/index.html', copy));
          }
          return res;
        })
        .catch(async () => {
          const cached = await caches.match('/index.html');
          return cached || Response.error();
        })
    );
    return;
  }

  // Resto de recursos propios (JS/CSS con hash, iconos, fuentes): stale-while-revalidate
  event.respondWith(
    caches.open(APP_CACHE).then(async (cache) => {
      const cached = await cache.match(req);
      const networkPromise = fetch(req)
        .then((res) => {
          if (res && res.ok) {
            cache.put(req, res.clone());
          }
          return res;
        })
        .catch(() => cached);
      return cached || networkPromise;
    })
  );
});
