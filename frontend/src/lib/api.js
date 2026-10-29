import { reactive } from 'vue'
const storageKey = 'ayla.session'
function stored() {
  try {
    const raw = localStorage.getItem(storageKey) || sessionStorage.getItem(storageKey)
    const session = raw ? JSON.parse(raw) : null
    if (!session?.accessToken || !session?.refreshToken || !session?.user?.id) return null
    // Preserve an existing login when upgrading from tab-only sessions.
    localStorage.setItem(storageKey, JSON.stringify(session))
    sessionStorage.removeItem(storageKey)
    return session
  } catch {
    return null
  }
}
export const auth = reactive({
  session: typeof window === 'undefined' ? null : stored(),
  checked: false,
})
let refreshing
let generation = 0
export function setSession(session) {
  generation++
  auth.session = session
  try {
    if (session) localStorage.setItem(storageKey, JSON.stringify(session))
    else localStorage.removeItem(storageKey)
    sessionStorage.removeItem(storageKey)
  } catch {
    // Storage restrictions must not lose an otherwise valid in-memory login.
  }
}
function syncSession() {
  try {
    const raw = localStorage.getItem(storageKey)
    const next = raw ? JSON.parse(raw) : null
    if (JSON.stringify(next) !== JSON.stringify(auth.session)) {
      generation++
      auth.session = next?.accessToken && next?.refreshToken && next?.user?.id ? next : null
    }
  } catch {
    /* Keep the current session when device storage cannot be read. */
  }
}
if (typeof window !== 'undefined') {
  window.addEventListener('storage', (event) => {
    if (event.storageArea === localStorage && (event.key === storageKey || event.key === null))
      syncSession()
  })
}
export class ApiError extends Error {
  constructor(message, status) {
    super(message)
    this.status = status
  }
}
const base = import.meta.env.VITE_API_BASE_URL || ''
async function decode(response) {
  if (response.status === 204) return null
  const text = await response.text()
  let data
  try {
    data = text ? JSON.parse(text) : null
  } catch {
    data = null
  }
  if (!response.ok)
    throw new ApiError(
      data?.message ||
        data?.detail ||
        data?.error ||
        `Não foi possível concluir (${response.status}).`,
      response.status,
    )
  return data
}
async function refresh(expectedToken) {
  if (!refreshing) {
    const currentUser = auth.session?.user?.id
    const renew = async () => {
      // Other tabs share the login. Read the token again after acquiring the lock:
      // refresh tokens rotate and must never be consumed twice by concurrent tabs.
      syncSession()
      if (!auth.session?.refreshToken || auth.session.user.id !== currentUser)
        throw new ApiError('Entre novamente para continuar.', 401)
      if (auth.session.accessToken !== expectedToken) return
      const before = generation
      try {
        const response = await fetch(base + '/api/v1/auth/refresh', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refreshToken: auth.session.refreshToken }),
        })
        const next = await decode(response)
        if (before !== generation) throw new ApiError('A sessão foi encerrada.', 401)
        setSession(next)
      } catch (error) {
        if (before === generation && [400, 401, 403].includes(error.status)) setSession(null)
        throw error
      }
    }
    refreshing = (
      navigator.locks?.request ? navigator.locks.request('ayla.auth.refresh', renew) : renew()
    ).finally(() => {
      refreshing = null
    })
  }
  return refreshing
}
export async function api(
  path,
  { method = 'GET', body, public: anonymous = false, headers = {}, signal, ...rest } = {},
) {
  const token = auth.session?.accessToken
  const userId = auth.session?.user?.id
  const send = () =>
    fetch(base + (path.startsWith('/actuator') ? path : '/api/v1' + path), {
      method,
      signal,
      ...rest,
      headers: {
        ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...(!anonymous && auth.session?.accessToken
          ? { Authorization: `Bearer ${auth.session.accessToken}` }
          : {}),
        ...headers,
      },
      ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
    })
  try {
    let response = await send()
    if (response.status === 401 && !anonymous && auth.session) {
      if (userId !== auth.session.user?.id)
        throw new ApiError('A conta ativa mudou. Tente novamente.', 401)
      if (token === auth.session.accessToken) await refresh(token)
      if (userId !== auth.session?.user?.id) throw new ApiError('A sessão foi encerrada.', 401)
      response = await send()
      if (response.status === 401 && userId === auth.session?.user?.id) setSession(null)
    }
    return await decode(response)
  } catch (error) {
    if (error instanceof ApiError || error.name === 'AbortError') throw error
    throw new ApiError('Não foi possível conectar à API. Confira se ela está em execução.', 0)
  }
}
export function query(values) {
  return new URLSearchParams(
    Object.entries(values).filter(
      ([, value]) => value !== '' && value !== null && value !== undefined,
    ),
  ).toString()
}
export const write = (path, method, body) =>
  api(path, { method, body, headers: { 'Idempotency-Key': crypto.randomUUID() } })
export async function restore() {
  const restoringUser = auth.session?.user?.id
  if (auth.session) {
    try {
      const user = await api('/auth/me')
      if (auth.session?.user?.id === user.id) setSession({ ...auth.session, user })
    } catch (error) {
      if (error.status === 401 && restoringUser === auth.session?.user?.id) setSession(null)
    }
  }
  auth.checked = true
}
