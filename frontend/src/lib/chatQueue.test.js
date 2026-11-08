import { test, expect, vi } from 'vitest'
vi.mock('./api', () => ({ api: vi.fn(), query: () => '' }))
import { api } from './api'
import { sendQueuedChat, waitForChatJob, retryChatJob } from './chatQueue'

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
test('limite interrompe a espera e conserva estado, aviso e opção segura de retry', async () => {
  const availability = { status: 'RATE_LIMITED', availableAt: null }
  const result = await waitForChatJob({
    id: 'limited',
    status: 'NEEDS_REVIEW',
    message: 'Aguardando provedor',
    availability,
    retryable: false,
  })
  expect(result).toMatchObject({ jobStatus: 'NEEDS_REVIEW', availability, retryable: false })
  expect(result).not.toHaveProperty('success', true)
})
test('retry explícito usa o mesmo job e nunca reenvia POST /chat', async () => {
  api.mockResolvedValueOnce({
    id: 'same-job',
    status: 'COMPLETED',
    result: { response: 'Concluído' },
  })
  try {
    expect(await retryChatJob('same-job')).toMatchObject({
      jobId: 'same-job',
      response: 'Concluído',
    })
    expect(api).toHaveBeenCalledExactlyOnceWith('/chat/jobs/same-job/retry', {
      method: 'POST',
      signal: undefined,
    })
  } finally {
    api.mockReset()
  }
})
