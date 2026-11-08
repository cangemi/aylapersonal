import { describe, it, expect } from 'vitest'
import {
  serverClock,
  remainingSeconds,
  effectiveStatus,
  duration,
  realPercentage,
  localTime,
} from './providerUsage'
describe('provider availability', () => {
  it('uses server UTC and monotonic elapsed time despite a wrong device clock', () => {
    const clock = serverClock('2026-11-04T22:00:00Z', 1000, 1200)
    expect(remainingSeconds('2026-11-04T22:27:25Z', clock(1200))).toBe(1645)
    expect(remainingSeconds('2026-11-04T22:27:25Z', clock(2200))).toBe(1644)
    expect(duration(1645)).toBe('00:27:25')
    expect(duration(3661)).toBe('01:01:01')
    const slowServer = serverClock('2026-11-04T22:00:00Z', 1000, 6200, 5000)
    expect(slowServer(6200)).toBe(Date.parse('2026-11-04T22:00:00Z') + 100)
  })
  it('expires to pending confirmation, never success', () => {
    const end = '2026-11-04T22:00:00Z'
    expect(remainingSeconds(end, Date.parse(end) + 5000)).toBe(0)
    expect(effectiveStatus('RATE_LIMITED', 0)).toBe('PENDING_CONFIRMATION')
    expect(effectiveStatus('AVAILABLE', 0)).toBe('AVAILABLE')
    expect(effectiveStatus('RATE_LIMITED', null)).toBe('RATE_LIMITED')
  })
  it('does not invent remaining seconds or percentage', () => {
    expect(remainingSeconds(null, Date.now())).toBeNull()
    expect(remainingSeconds('2026-11-04T22:00:00Z', null)).toBeNull()
    expect(realPercentage(null)).toBeNull()
    expect(realPercentage('60')).toBeNull()
    expect(realPercentage(101)).toBeNull()
    expect(realPercentage(0)).toBe(0)
    expect(localTime('2026-11-04T22:00:00Z')).not.toBe('Não informado')
  })
})
