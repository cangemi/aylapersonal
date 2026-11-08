export const providerLabels = {
  AVAILABLE: 'Disponível',
  RATE_LIMITED: 'Modelo temporariamente limitado',
  WAITING_PROVIDER: 'Aguardando disponibilidade do provedor',
  PENDING_CONFIRMATION: 'Prazo encerrado — aguardando confirmação do provedor',
  ERROR: 'Erro na integração',
  UNKNOWN: 'Disponibilidade ainda não confirmada',
}
// performance.now is monotonic: local timezone and device wall-clock changes do not affect the timer.
export function serverClock(
  serverNow,
  sent = performance.now(),
  received = performance.now(),
  processingMillis = 0,
) {
  const server = Date.parse(serverNow)
  const processing =
    Number.isFinite(processingMillis) && processingMillis > 0 ? processingMillis : 0
  const baseline = Number.isFinite(server)
    ? server + Math.max(0, received - sent - processing) / 2
    : null
  return (tick = performance.now()) =>
    baseline === null ? null : baseline + Math.max(0, tick - received)
}
export function remainingSeconds(availableAt, now) {
  const end = Date.parse(availableAt)
  return now === null || !Number.isFinite(end) ? null : Math.max(0, Math.ceil((end - now) / 1000))
}
export function effectiveStatus(state, remaining) {
  return ['RATE_LIMITED', 'WAITING_PROVIDER'].includes(state) && remaining === 0
    ? 'PENDING_CONFIRMATION'
    : state || 'UNKNOWN'
}
export function duration(seconds) {
  if (seconds === null) return 'Prazo não informado'
  return [Math.floor(seconds / 3600), Math.floor(seconds / 60) % 60, seconds % 60]
    .map((n) => String(n).padStart(2, '0'))
    .join(':')
}
export function localTime(value) {
  return value && Number.isFinite(Date.parse(value))
    ? new Intl.DateTimeFormat('pt-BR', {
        dateStyle: 'short',
        timeStyle: 'medium',
        timeZoneName: undefined,
      }).format(new Date(value))
    : 'Não informado'
}
export function realPercentage(value) {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 100
    ? value
    : null
}
