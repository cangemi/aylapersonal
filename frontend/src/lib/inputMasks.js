// Formatting uses strings throughout. Financial conversion remains in cents()/BigInt.
export function numericMask(
  value,
  { kind = 'money', scale = kind === 'money' ? 2 : 3, pad = false } = {},
) {
  let raw = String(value ?? '').replace(/R\$|%|\s/g, '')
  if (!raw) return { model: '', display: '' }
  const negative = raw.startsWith('-')
  raw = raw.replace(/[^\d,]/g, '')
  if (!raw) return { model: negative ? '-' : '', display: negative ? '-' : '' }
  const separator = raw.indexOf(',')
  let whole = (separator < 0 ? raw : raw.slice(0, separator)).replace(/^0+(?=\d)/, '') || '0'
  let fraction =
    separator < 0
      ? ''
      : raw
          .slice(separator + 1)
          .replace(/,/g, '')
          .slice(0, scale)
  if (pad && kind === 'money') fraction = fraction.padEnd(2, '0')
  const hasFraction = scale > 0 && (fraction.length > 0 || (!pad && separator >= 0))
  const sign = negative ? '-' : ''
  const canonical = sign + whole + (hasFraction ? ',' + fraction : '')
  const grouped = kind === 'money' ? whole.replace(/\B(?=(\d{3})+(?!\d))/g, '.') : whole
  return {
    model: kind === 'money' ? canonical : canonical.replace(',', '.'),
    display: (kind === 'money' ? 'R$ ' : '') + sign + grouped + (hasFraction ? ',' + fraction : ''),
  }
}

export function destinationMask(value) {
  const raw = String(value ?? '').trim()
  if (raw.includes('@')) {
    const id = raw.replace(/[\s()+]/g, '')
    return { model: id, display: id }
  }
  const digits = raw.replace(/\D/g, '')
  if (!digits) return { model: '', display: '' }
  if (!digits.startsWith('55') || digits.length > 13)
    return { model: digits, display: '+' + digits }
  const area = digits.slice(2, 4),
    local = digits.slice(4),
    split = local.length > 8 ? 5 : 4
  return {
    model: digits,
    display:
      '+55' +
      (area ? ' (' + area + (area.length === 2 ? ')' : '') : '') +
      (local
        ? ' ' + local.slice(0, split) + (local.length > split ? '-' + local.slice(split) : '')
        : ''),
  }
}

export function pastedNumber(value, kind = 'money') {
  const raw = String(value).replace(/R\$|%|\s/g, '')
  // A comma is the Brazilian radix; dots are thousands. Also accept pasted 12.50.
  const decimal = kind === 'decimal' ? /^-?\d+\.\d+$/ : /^-?\d+\.\d{1,2}$/
  return !raw.includes(',') && decimal.test(raw) ? raw.replace('.', ',') : raw
}
