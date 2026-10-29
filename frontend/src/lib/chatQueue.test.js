import { test, expect, vi } from 'vitest'
vi.mock('./api', () => ({ api: vi.fn(), query: () => '' }))
import { api } from './api'
import { sendQueuedChat } from './chatQueue'

test('aguarda a fila e recupera a resposta sem reenviar o pedido após falha de consulta', async () => {
  vi.useFakeTimers()
  api
    .mockResolvedValueOnce({ id: 'job', status: 'QUEUED' })
    .mockRejectedValueOnce({ status: 503 })
    .mockResolvedValueOnce({ id: 'job', status: 'COMPLETED', result: { response: 'Concluído' } })
  try {
    const result = sendQueuedChat({ message: 'Oi' })
    await vi.advanceTimersByTimeAsync(4100)
    expect(await result).toMatchObject({ response: 'Concluído', jobId: 'job' })
    expect(api.mock.calls.filter(([, options]) => options?.method === 'POST')).toHaveLength(1)
  } finally {
    vi.useRealTimers()
    api.mockReset()
  }
})
