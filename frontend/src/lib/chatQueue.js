import { api, query } from './api'

export async function waitForChatJob(initial, { onStatus = () => {}, signal } = {}) {
  let job = initial
  while (true) {
    if (signal?.aborted)
      throw new DOMException('Consulta interrompida; pedido continua salvo.', 'AbortError')
    onStatus(job)
    if (job.status === 'COMPLETED') return { ...job.result, jobId: job.id }
    if (['FAILED', 'NEEDS_REVIEW', 'CANCELLED'].includes(job.status))
      return {
        response: job.message || 'Não consegui concluir esse pedido.',
        conversationId: job.conversationId,
        jobId: job.id,
      }
    await new Promise((resolve) => setTimeout(resolve, 1000))
    try {
      job = await api('/chat/jobs/' + job.id, { signal })
    } catch (error) {
      if (![0, 502, 503, 504].includes(error.status)) throw error
      onStatus({
        ...job,
        message: 'A conexão está indisponível. Seu pedido continua salvo; vou aguardar.',
      })
    }
  }
}
export async function sendQueuedChat(body, options = {}) {
  const accepted = await api('/chat', {
    method: 'POST',
    body,
    signal: options.signal,
    headers: { Prefer: 'respond-async', 'Idempotency-Key': crypto.randomUUID() },
  })
  return accepted.response ? accepted : waitForChatJob(accepted, options)
}
export const pendingChatJobs = (conversationId) => api('/chat/jobs?' + query({ conversationId }))
export const acknowledgeChatJob = (id) =>
  id ? api('/chat/jobs/' + id + '/ack', { method: 'POST' }).catch(() => {}) : Promise.resolve()
