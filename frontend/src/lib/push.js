import { api } from './api'

const urlBase64ToUint8Array = (value) => {
  const padding = '='.repeat((4 - (value.length % 4)) % 4)
  const base64 = (value + padding).replace(/-/g, '+').replace(/_/g, '/')
  const raw = atob(base64)
  const output = new Uint8Array(raw.length)
  for (let i = 0; i < raw.length; i++) output[i] = raw.charCodeAt(i)
  return output
}

export async function getPushPublicKey() {
  const result = await api('/push/public-key')
  return result.publicKey
}

export async function subscribePush() {
  if (!('serviceWorker' in navigator) || !('PushManager' in window)) {
    throw new Error('Este navegador não suporta notificações push.')
  }
  const registration = await navigator.serviceWorker.ready
  const publicKey = await getPushPublicKey()
  let subscription = await registration.pushManager.getSubscription()
  if (!subscription) {
    subscription = await registration.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: urlBase64ToUint8Array(publicKey),
    })
  }
  const json = subscription.toJSON()
  await api('/push/subscriptions', {
    method: 'POST',
    body: {
      endpoint: json.endpoint,
      keys: json.keys,
      userAgent: navigator.userAgent,
    },
  })
  return subscription
}

export async function unsubscribePush() {
  if (!('serviceWorker' in navigator) || !('PushManager' in window)) return
  const registration = await navigator.serviceWorker.ready
  const subscription = await registration.pushManager.getSubscription()
  if (subscription) {
    const json = subscription.toJSON()
    await api('/push/subscriptions', {
      method: 'DELETE',
      body: { endpoint: json.endpoint, keys: json.keys },
    }).catch(() => {})
    await subscription.unsubscribe().catch(() => {})
  } else {
    // Still inform backend? not possible without endpoint.
  }
}

export async function currentPushSubscription() {
  if (!('serviceWorker' in navigator) || !('PushManager' in window)) return null
  const registration = await navigator.serviceWorker.ready
  return registration.pushManager.getSubscription()
}