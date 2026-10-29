export function money(value) {
  const integer = BigInt(value || 0),
    negative = integer < 0n,
    absolute = negative ? -integer : integer,
    whole = absolute / 100n
  return new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' })
    .formatToParts(negative ? (whole === 0n ? -0 : -whole) : whole)
    .map((part) =>
      part.type === 'fraction' ? String(absolute % 100n).padStart(2, '0') : part.value,
    )
    .join('')
}
export function cents(value, { allowZero = false } = {}) {
  const normalized = String(value).trim()
  if (!/^\d+(?:,\d{1,2})?$/.test(normalized))
    throw new Error('Use um valor em reais, como 15,90, sem separador de milhar.')
  const [whole, fraction = ''] = normalized.split(',')
  const exact = BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0'))
  if (exact < 0n || (!allowZero && exact === 0n) || exact > BigInt(Number.MAX_SAFE_INTEGER))
    throw new Error('Informe um valor positivo dentro do limite permitido.')
  return Number(exact)
}
export function moneyInput(value) {
  const integer = BigInt(value)
  return `${integer / 100n},${String(integer % 100n).padStart(2, '0')}`
}
export const date = (value) =>
  value
    ? new Intl.DateTimeFormat('pt-BR', {
        day: '2-digit',
        month: 'short',
        year: 'numeric',
        timeZone: 'America/Sao_Paulo',
      }).format(new Date(value.length === 10 ? value + 'T12:00:00-03:00' : value))
    : '—'
export const timestamp = (value) =>
  value
    ? new Intl.DateTimeFormat('pt-BR', {
        dateStyle: 'short',
        timeStyle: 'short',
        timeZone: 'America/Sao_Paulo',
      }).format(new Date(value))
    : '—'
export function today() {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'America/Sao_Paulo',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date())
}
export function monthRange() {
  const now = today()
  return { from: now.slice(0, 7) + '-01', to: now }
}
export function localDateTime(value) {
  return new Date(new Date(value).getTime() - new Date(value).getTimezoneOffset() * 60000)
    .toISOString()
    .slice(0, 16)
}
export const units = ['UN', 'KG', 'G', 'L', 'ML', 'PACOTE']
