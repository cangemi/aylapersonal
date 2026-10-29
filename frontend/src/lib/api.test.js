import { it, expect, vi, afterEach } from 'vitest'
import { api, setSession, auth } from './api'
const storage = new Map()
vi.stubGlobal('sessionStorage', {
  getItem: (key) => storage.get(key) || null,
  setItem: (key, value) => storage.set(key, value),
  removeItem: (key) => storage.delete(key),
})
afterEach(() => {
  setSession(null)
  vi.unstubAllGlobals()
})
it('renova uma única vez em chamadas simultâneas e nunca repete escrita com erro 502', async () => {
  setSession({ accessToken: 'old', refreshToken: 'refresh', user: { id: 'owner' } })
  let renewals = 0,
    writes = 0
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url, options) => {
      if (url.endsWith('/auth/refresh')) {
        renewals++
        await new Promise((resolve) => setTimeout(resolve, 10))
        return Response.json({ accessToken: 'new', refreshToken: 'rotated', user: { id: 'owner' } })
      }
      if (url.endsWith('/finance/entries') && options.method === 'POST') {
        writes++
        return Response.json({ message: 'Entrega incerta' }, { status: 502 })
      }
      return options.headers.Authorization === 'Bearer new'
        ? Response.json({ ok: true })
        : Response.json({}, { status: 401 })
    }),
  )
  expect(await Promise.all([api('/auth/me'), api('/conversations')])).toEqual([
    { ok: true },
    { ok: true },
  ])
  expect(renewals).toBe(1)
  expect(auth.session.refreshToken).toBe('rotated')
  await expect(api('/finance/entries', { method: 'POST', body: {} })).rejects.toMatchObject({
    status: 502,
  })
  expect(writes).toBe(1)
})
