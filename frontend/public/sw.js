const CACHE = 'ayla-public-offline-v1'
self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE).then((cache) => cache.addAll(['/offline.html', '/app-icon.svg'])),
  )
  self.skipWaiting()
})
self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) =>
        Promise.all(
          keys
            .filter((key) => key.startsWith('ayla-public-offline-') && key !== CACHE)
            .map((key) => caches.delete(key)),
        ),
      )
      .then(() => self.clients.claim()),
  )
})
self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url)
  if (url.origin !== self.location.origin || event.request.method !== 'GET') return
  // API, tokens, messages and financial data are never stored in a service worker cache.
  if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/internal/')) return
  if (event.request.mode === 'navigate') {
    event.respondWith(fetch(event.request).catch(() => caches.match('/offline.html')))
  } else if (url.pathname === '/app-icon.svg') {
    event.respondWith(fetch(event.request).catch(() => caches.match('/app-icon.svg')))
  }
})
