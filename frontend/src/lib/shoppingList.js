import { cents } from './format'
export function shoppingOrder(storage, userId) {
  try {
    return storage.getItem(`ayla.shopping.order.${userId}`) === 'CART_LAST' ? 'CART_LAST' : 'NORMAL'
  } catch {
    return 'NORMAL'
  }
}
export function saveShoppingOrder(storage, userId, order) {
  if (!['NORMAL', 'CART_LAST'].includes(order)) return
  try {
    storage.setItem(`ayla.shopping.order.${userId}`, order)
  } catch {
    /* Preference remains usable in memory. */
  }
}
export function shoppingRowPayload(item, draft) {
  const body = { version: item.version }
  if (draft.quantity === '' && draft.unit !== 'UN' && draft.price !== '')
    throw new Error('Informe a quantidade antes de salvar o preço nesta unidade.')
  if (draft.quantity !== '' && draft.quantity != null) {
    body.quantity = String(draft.quantity)
    body.unit = draft.unit
  }
  if (draft.price !== '') {
    body[draft.priceMode === 'UNIT' ? 'unitPriceCents' : 'totalPriceCents'] = cents(draft.price, {
      allowZero: true,
    })
    const value = body[draft.priceMode === 'UNIT' ? 'unitPriceCents' : 'totalPriceCents']
    const previous =
      draft.priceMode === 'UNIT'
        ? item.unitPriceCents
        : item.unitPriceCents == null
          ? item.totalPriceCents
          : null
    body.inCart = value !== previous ? true : item.status === 'IN_CART'
  } else if (item.totalPriceCents != null || item.unitPriceCents != null) {
    body.clearPrice = true
    body.inCart = false
  }
  return body
}
