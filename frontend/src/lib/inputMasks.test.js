import { describe, it, expect } from 'vitest'
import { numericMask, destinationMask, pastedNumber } from './inputMasks'
import { cents } from './format'

describe('máscaras sem alterar os dados enviados à API', () => {
  it('formata reais, preserva centavos exatos e aceita edição, vazio e colagem', () => {
    expect(numericMask('1234,56')).toEqual({ display: 'R$ 1.234,56', model: '1234,56' })
    expect(numericMask('12', { pad: true }).display).toBe('R$ 12,00')
    expect(numericMask('').model).toBe('')
    expect(numericMask('0,').display).toBe('R$ 0,')
    expect(numericMask(pastedNumber('R$ 90.071.992.547.409,91')).model).toBe('90071992547409,91')
    expect(cents(numericMask('R$ 0,12').model)).toBe(12)
    expect(cents(numericMask('90.071.992.547.409,91').model)).toBe(Number.MAX_SAFE_INTEGER)
    expect(cents('0,00', { allowZero: true })).toBe(0)
    expect(() => cents(numericMask('-15,00').model)).toThrow()
    expect(pastedNumber('12.50')).toBe('12,50')
  })
  it('usa vírgula para quantidades/percentuais e preserva telefones e IDs de grupo', () => {
    expect(numericMask('0,125', { kind: 'decimal', scale: 3 })).toEqual({
      display: '0,125',
      model: '0.125',
    })
    expect(pastedNumber('0.125', 'decimal')).toBe('0,125')
    expect(destinationMask('5511999999999')).toEqual({
      display: '+55 (11) 99999-9999',
      model: '5511999999999',
    })
    expect(destinationMask('+55 (11) 3333-4444').model).toBe('551133334444')
    expect(destinationMask('120363000000000000@g.us').display).toBe('120363000000000000@g.us')
  })
})
