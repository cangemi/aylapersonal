
import { api } from './api'

const arrayBufferToUrlBase64 = (buffer) => {
  const bytes = new Uint8Array(buffer)
  let binary = ''
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i])
  }
  return btoa(binary)
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/g, '')
}

const urlBase64ToUint8Array = (value) => {
  const padding = '='.repeat((4 - (value.length % 4)) % 4)
  const base64 = (value + padding)
    .replace(/-/g, '+')
    .replace(/_/g, '/')

  const raw = atob(base64)
  const output = new Uint8Array(raw.length)

  for (let i = 0; i < raw.length; i++) {
    output[i] = raw.charCodeAt(i)
  }

  return output
}

export async function getPushPublicKey() {
  const result = await api('/push/public-key')
  return result.publicKey
}

export async function subscribePush() {
  if (
    !('serviceWorker' in navigator) ||
    !('PushManager' in window)
  ) {
    throw new Error(
      'Este navegador não suporta notificações push.'
    )
  }

  const registration = await navigator.serviceWorker.ready
  const publicKey = await getPushPublicKey()

  if (!publicKey) {
    throw new Error('Chave pública VAPID indisponível.')
  }

  let subscription = await registration.pushManager.getSubscription()

  if (subscription) {
    const applicationServerKey =
      subscription.options?.applicationServerKey

    const currentKey = applicationServerKey
      ? arrayBufferToUrlBase64(applicationServerKey)
      : null

    // Compara a chave real da inscrição, não o localStorage.
    if (!currentKey || currentKey !== publicKey) {
      const json = subscription.toJSON()

      await api('/push/subscriptions', {
        method: 'DELETE',
        body: {
          endpoint: json.endpoint,
          keys: json.keys,
        },
      }).catch(() => {})

      const removed = await subscription.unsubscribe()

      if (!removed) {
        throw new Error(
          'Não foi possível remover a inscrição Push antiga.'
        )
      }

      subscription = null
    }
  }

  if (!subscription) {
    subscription = await registration.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: urlBase64ToUint8Array(publicKey),
    })
  }

  const json = subscription.toJSON()

  // Associa a inscrição ao usuário autenticado no backend.
  await api('/push/subscriptions', {
    method: 'POST',
    body: {
      endpoint: json.endpoint,
      keys: json.keys,
      userAgent: navigator.userAgent,
    },
  })

  localStorage.setItem('ayla.vapid.publicKey', publicKey)

  return subscription
}

export async function unsubscribePush() {
  if (
    !('serviceWorker' in navigator) ||
    !('PushManager' in window)
  ) {
    return
  }

  const registration = await navigator.serviceWorker.ready
  const subscription =
    await registration.pushManager.getSubscription()

  if (!subscription) {
    localStorage.removeItem('ayla.vapid.publicKey')
    return
  }

  const json = subscription.toJSON()

  await api('/push/subscriptions', {
    method: 'DELETE',
    body: {
      endpoint: json.endpoint,
      keys: json.keys,
    },
  }).catch(() => {})

  const removed = await subscription.unsubscribe()

  if (!removed) {
    throw new Error(
      'Não foi possível cancelar a inscrição Push.'
    )
  }

  localStorage.removeItem('ayla.vapid.publicKey')
}

export async function currentPushSubscription() {
  if (
    !('serviceWorker' in navigator) ||
    !('PushManager' in window)
  ) {
    return null
  }

  const registration = await navigator.serviceWorker.ready
  return registration.pushManager.getSubscription()
}
