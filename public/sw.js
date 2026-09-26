/*
 * FitTrack AI service worker.
 *
 * Strategy, deliberately conservative:
 * - App shell (HTML, JS, CSS, fonts, icons): stale-while-revalidate, so the app
 *   opens instantly and still works offline after the first visit.
 * - API requests (including `/api/` and cross-origin API hosts): never cached.
 *   Private health data must always come directly from the server.
 * - Navigation requests fall back to the cached shell when offline.
 * - Offline writes are queued by the application in IndexedDB and replayed through
 *   the normal REST API; the worker never replays or caches them itself.
 *
 * Only the static shell is ever stored, so no Authorization-dependent response can
 * be shared between users. `PURGE_USER_DATA` exists so sign-out can defensively drop
 * any cache a future change might introduce.
 */

const VERSION = "fittrack-v2";
const SHELL_CACHE = `${VERSION}-shell`;

const SHELL_ASSETS = ["/", "/index.html", "/manifest.webmanifest"];

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches
      .open(SHELL_CACHE)
      .then((cache) => cache.addAll(SHELL_ASSETS))
      .then(() => self.skipWaiting())
      .catch(() => self.skipWaiting())
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => !k.startsWith(VERSION)).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

// Sign-out / user switch: drop everything this worker owns.
self.addEventListener("message", (event) => {
  if (event.data && event.data.type === "PURGE_USER_DATA") {
    event.waitUntil(caches.keys().then((keys) => Promise.all(keys.map((k) => caches.delete(k)))));
  }
});

function isPrivateRequest(url) {
  return (
    url.pathname.startsWith("/api/") ||
    url.pathname.startsWith("/api") ||
    url.pathname.includes("/api/v1/") ||
    url.port === "8080"
  );
}

self.addEventListener("fetch", (event) => {
  const { request } = event;
  // Never intercept a write: queued offline mutations replay through the API directly.
  if (request.method !== "GET") return;

  const url = new URL(request.url);

  // Private data is never cached.
  if (isPrivateRequest(url)) return;

  // Only handle same-origin assets and web fonts.
  const sameOrigin = url.origin === self.location.origin;
  const isFont = url.hostname.includes("fonts.googleapis.com") || url.hostname.includes("fonts.gstatic.com");
  if (!sameOrigin && !isFont) return;

  if (request.mode === "navigate") {
    event.respondWith(
      fetch(request).catch(() => caches.match("/index.html").then((r) => r || Response.error()))
    );
    return;
  }

  event.respondWith(
    caches.match(request).then((cached) => {
      const network = fetch(request)
        .then((response) => {
          // Only ever store same-origin shell assets; never a cross-origin or opaque body.
          if (response && response.status === 200 && response.type === "basic" && sameOrigin) {
            const copy = response.clone();
            caches.open(SHELL_CACHE).then((cache) => cache.put(request, copy)).catch(() => {});
          }
          return response;
        })
        .catch(() => cached || Response.error());
      return cached || network;
    })
  );
});
