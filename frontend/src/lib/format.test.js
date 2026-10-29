import { describe, it, expect } from 'vitest'
import { cents, moneyInput, money } from './format'
describe('valores financeiros sem multiplicação em ponto flutuante', () => {
  it('converte reais em centavos exatos e mantém o limite seguro', () => {
    expect(cents('0,12')).toBe(12)
    expect(cents('50')).toBe(5000)
    expect(cents('1,01')).toBe(101)
    expect(cents('90071992547409,91')).toBe(Number.MAX_SAFE_INTEGER)
    expect(moneyInput(101)).toBe('1,01')
    expect(money(12)).toContain('0,12')
    expect(money(Number.MAX_SAFE_INTEGER)).toContain('90.071.992.547.409,91')
    expect(money(-12)).toContain('-R$')
  })
  it('rejeita frações de centavo, números ambíguos, zero e overflow', () => {
    for (const value of ['0', '-1', '1.234', '1.25', '1,234', '1e3', 'NaN', '90071992547409,92'])
      expect(() => cents(value)).toThrow()
  })
})
