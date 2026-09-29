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

// ---------------------------------------------------------------- push
//
// Added for reminder notifications (Phase 13). The payload is treated as untrusted throughout: it is
// parsed defensively, and a click never navigates to a URL the payload supplies. safePath below is
// what makes an open redirect impossible here.

/**
 * The in-app destination for a tapped notification.
 *
 * FitTrack has no URL-based SPA routing and no reminder detail route: navigation is a React
 * `useState<Tab>` and the app never reads the URL path. A payload-supplied path such as
 * /reminders/{id} therefore cannot deep-link anywhere - it would simply open the default tab - so
 * producing one would imply a capability that does not exist. The app root is returned instead.
 *
 * Reminder deep-linking is a possible future feature, out of scope for Phase 14. Until it exists, a
 * tap opens or focuses FitTrack, which is the behaviour the product can actually honour. The id is
 * still validated so a malformed or hostile payload cannot influence this function's result at all.
 */
function safePath(reminderId) {
  if (typeof reminderId !== "string" || !/^[0-9a-fA-F-]{1,64}$/.test(reminderId)) return "/";
  return "/";
}

function readPushPayload(event) {
  if (!event.data) return { title: "FitTrack", body: "You have a reminder", path: "/" };
  let parsed = null;
  try {
    parsed = event.data.json();
  } catch {
    parsed = null;
  }
  if (!parsed || typeof parsed !== "object") {
    return {
      title: "FitTrack",
      body: event.data.text ? String(event.data.text) : "You have a reminder",
      path: "/",
    };
  }
  return {
    title: typeof parsed.title === "string" && parsed.title ? parsed.title : "FitTrack",
    body: typeof parsed.body === "string" ? parsed.body : "You have a reminder",
    path: safePath(parsed.reminderId),
  };
}

self.addEventListener("push", (event) => {
  const payload = readPushPayload(event);
  event.waitUntil(
    self.registration.showNotification(payload.title, {
      body: payload.body,
      icon: "/icon-192.webp",
      badge: "/icon-192.webp",
      tag: "fittrack-reminder",
      data: { path: payload.path },
    })
  );
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const target = (event.notification.data && event.notification.data.path) || "/";

  event.waitUntil(
    self.clients.matchAll({ type: "window", includeUncontrolled: true }).then((clientList) => {
      for (const client of clientList) {
        if (client.url && new URL(client.url).origin === self.location.origin) {
          if ("focus" in client) return client.focus();
          if ("navigate" in client) return client.navigate(target);
        }
      }
      return self.clients.openWindow(target);
    })
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
