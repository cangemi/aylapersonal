import { describe, expect, it } from 'vitest'
import { combineSeries, groupHoldings, sumPeriods } from './investmentAnalytics'

describe('investment period aggregation', () => {
  it('weights the percentage by available capital instead of adding percentages', () => {
    const result = sumPeriods([
      { complete: true, gainCents: 100, observedGainCents: 100, baseCents: 1000 },
      { complete: true, gainCents: 100, observedGainCents: 100, baseCents: 9000 },
    ])
    expect(result.gainCents).toBe(200)
    expect(result.gainPercent).toBe(2)
  })
  it('does not convert a missing valuation into zero or a complete portfolio return', () => {
    const result = sumPeriods([{ complete: true, gainCents: 50, observedGainCents: 50 },
      { complete: false, gainCents: null, observedGainCents: 300 }])
    expect(result.gainCents).toBeNull()
    expect(result.observedGainCents).toBe(350)
    expect(result.knownCount).toBe(1)
    expect(sumPeriods([]).gainCents).toBeNull()
  })
  it('groups applications by type and combines only the dates actually tracked', () => {
    const p = (key, gain) => ({ key, complete: true, gainCents: gain, observedGainCents: gain, baseCents: 1000 })
    const holdings = [
      { assetType: 'CDB', institution: 'A', balanceCents: 1100, performance: { month: p('2026-11', 100), days: [p('2026-11-03', 40), p('2026-11-04', 60)] } },
      { assetType: 'CDB', institution: 'B', balanceCents: 1200, performance: { month: p('2026-11', 200), days: [p('2026-11-04', 200)] } },
    ]
    expect(groupHoldings(holdings, 'assetType')[0].month.gainCents).toBe(300)
    expect(groupHoldings(holdings, 'institution')).toHaveLength(2)
    const series = combineSeries(holdings, 'days')
    expect(series.map((item) => item.gainCents)).toEqual([40, 260])
    expect(series.map((item) => item.count)).toEqual([1, 2])
  })
})
