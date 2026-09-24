/*
 * FitTrack AI service worker.
 *
 * Strategy, deliberately conservative:
 * - App shell (HTML, JS, CSS, fonts, icons): stale-while-revalidate, so the app
 *   opens instantly and still works offline after the first visit.
 * - API requests (including `/api/` and cross-origin API hosts): never cached.
 *   Private health data must always come directly from the server.
 * - Navigation requests fall back to the cached shell when offline.
 *
 * Workout logging offline (an IndexedDB queue) is not implemented yet; see the
 * README known limitations. This worker only guarantees the app opens offline.
 */

const VERSION = "fittrack-v1";
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
          if (response && response.status === 200 && response.type === "basic") {
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
