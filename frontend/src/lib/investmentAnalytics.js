export const assetLabels = {
  CAIXINHA: 'Caixinha / reserva', CDB: 'CDB', RDB: 'RDB', LCI: 'LCI', LCA: 'LCA',
  TESOURO: 'Tesouro Direto', POUPANCA: 'Poupança', ACAO: 'Ações', FII: 'Fundos imobiliários',
  ETF: 'ETF', FUNDO: 'Fundo de investimento', PREVIDENCIA: 'Previdência', CRIPTO: 'Criptoativos', OUTRO: 'Outro investimento',
}

export function sumPeriods(values) {
  const periods = values.filter(Boolean)
  const sum = (key) => periods.reduce((total, p) => total + (p[key] || 0), 0)
  const complete = periods.length > 0 && periods.every((p) => p.complete && p.gainCents != null)
  const gain = complete ? sum('gainCents') : null
  const base = sum('baseCents')
  return {
    count: periods.length, knownCount: periods.filter((p) => p.complete && p.gainCents != null).length,
    complete, partial: periods.some((p) => p.partial), estimated: periods.some((p) => p.estimated),
    gainCents: gain, observedGainCents: sum('observedGainCents'), baseCents: base,
    gainPercent: gain != null && base > 0 ? gain * 100 / base : null,
    balanceCents: sum('balanceCents'), netInvestedCents: sum('netInvestedCents'),
    accumulatedGainCents: sum('accumulatedGainCents'), depositsCents: sum('depositsCents'),
    withdrawalsCents: sum('withdrawalsCents'), incomeCents: sum('incomeCents'),
  }
}

export function combineSeries(holdings, field) {
  const grouped = new Map()
  for (const holding of holdings) for (const period of holding.performance[field] || []) {
    if (!grouped.has(period.key)) grouped.set(period.key, [])
    grouped.get(period.key).push(period)
  }
  return [...grouped].sort(([a], [b]) => a.localeCompare(b)).map(([key, periods]) => ({ key, ...sumPeriods(periods) }))
}

export function groupHoldings(holdings, field) {
  const groups = new Map()
  for (const holding of holdings) {
    const key = holding[field]
    if (!groups.has(key)) groups.set(key, [])
    groups.get(key).push(holding)
  }
  return [...groups].map(([key, entries]) => ({
    key, name: field === 'assetType' ? assetLabels[key] || key : key, entries,
    balanceCents: entries.reduce((sum, item) => sum + item.balanceCents, 0),
    day: sumPeriods(entries.map((item) => item.performance.day)),
    month: sumPeriods(entries.map((item) => item.performance.month)),
    year: sumPeriods(entries.map((item) => item.performance.year)),
  })).sort((a, b) => b.balanceCents - a.balanceCents)
}

export function periodStatus(period) {
  if (!period?.count) return 'Sem histórico'
  if (!period.complete) return `Dados incompletos · ${period.knownCount}/${period.count} aplicações com base suficiente`
  return [period.estimated ? 'Estimado' : 'Informado', period.partial ? 'Histórico desde o cadastro' : 'Período completo'].join(' · ')
}
