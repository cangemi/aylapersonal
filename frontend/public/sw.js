const CACHE = 'ayla-public-offline-v2'
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
self.addEventListener('push', (event) => {
  let data = {}
  try {
    data = event.data ? event.data.json() : {}
  } catch {
    data = { body: event.data ? event.data.text() : '' }
  }
  const title = data.title || 'Ayla'
  const target = data.url || '/chat'
  const options = {
    body: data.body || 'Nova resposta disponível.',
    icon: '/ayla-192.png',
    badge: '/favicon-32.png',
    tag: data.tag || data.eventId || 'ayla-response',
    renotify: false,
    data: { url: target },
  }
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clients) => {
      const focused = clients.some((client) => client.focused && client.url.includes(target))
      if (focused) return
      return self.registration.showNotification(title, options)
    }),
  )
})
self.addEventListener('notificationclick', (event) => {
  event.notification.close()
  let target = (event.notification.data && event.notification.data.url) ? String(event.notification.data.url) : '/chat'
  let url
  try {
    url = new URL(target, self.location.origin)
  } catch {
    url = new URL('/chat', self.location.origin)
  }
  if (url.origin !== self.location.origin || !url.pathname.startsWith('/chat')) {
    url = new URL('/chat', self.location.origin)
  }
  if (event.notification.data && event.notification.data.conversationId) {
    url.searchParams.set('conversationId', String(event.notification.data.conversationId))
  }
  target = url.pathname + url.search + url.hash
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clients) => {
      for (const client of clients) {
        try {
          const clientUrl = new URL(client.url)
          if (clientUrl.origin === self.location.origin && 'focus' in client) {
            if ('navigate' in client) return client.navigate(target).then(() => client.focus())
            return client.focus()
          }
        } catch {
          // Ignore malformed client URLs.
        }
      }
      if (self.clients.openWindow) return self.clients.openWindow(target)
    }),
  )
})
self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url)
  if (url.origin !== self.location.origin || event.request.method !== 'GET') return
  if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/internal/')) return
  if (event.request.mode === 'navigate') {
    event.respondWith(fetch(event.request).catch(() => caches.match('/offline.html')))
  } else if (url.pathname === '/app-icon.svg') {
    event.respondWith(fetch(event.request).catch(() => caches.match('/app-icon.svg')))
  }
})
