import { describe, it, expect } from 'vitest'
import { shoppingRowPayload, shoppingOrder, saveShoppingOrder } from './shoppingList'
describe('edição direta da lista de compras', () => {
  it('guarda dez centavos sem arredondar para reais e marca no carrinho', () => {
    expect(
      shoppingRowPayload(
        { version: 2, status: 'PENDING' },
        { quantity: '1', unit: 'UN', price: '0,10', priceMode: 'TOTAL' },
      ),
    ).toEqual({ version: 2, quantity: '1', unit: 'UN', totalPriceCents: 10, inCart: true })
  })
  it('alterar quantidade preserva item fora do carrinho e remover preço desmarca', () => {
    const item = { version: 3, status: 'PENDING', totalPriceCents: 2000 }
    expect(
      shoppingRowPayload(item, { quantity: '2', unit: 'UN', price: '20,00', priceMode: 'TOTAL' })
        .inCart,
    ).toBe(false)
    expect(
      shoppingRowPayload(item, { quantity: '2', unit: 'UN', price: '', priceMode: 'TOTAL' }),
    ).toMatchObject({ clearPrice: true, inCart: false })
  })
  it('lembra ordenação sem misturar preferências de usuários', () => {
    const values = new Map(),
      storage = {
        getItem: (key) => values.get(key),
        setItem: (key, value) => values.set(key, value),
      }
    saveShoppingOrder(storage, 'a', 'CART_LAST')
    expect(shoppingOrder(storage, 'a')).toBe('CART_LAST')
    expect(shoppingOrder(storage, 'b')).toBe('NORMAL')
  })
})
