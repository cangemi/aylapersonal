import { test, expect } from '@playwright/test'
test('lista mobile: preço direto, carrinho, ordenação persistida e sem enquete', async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const user = {
    id: 'shopping-ui-user',
    username: 'ui@example.test',
    displayName: 'Pessoa',
    role: 'OWNER',
  }
  await page.addInitScript(
    (session) => localStorage.setItem('ayla.session', JSON.stringify(session)),
    { accessToken: 'ui-test', refreshToken: 'ui-refresh', user },
  )
  const items = [
    {
      id: 'a',
      name: 'Arroz',
      quantity: 1,
      unit: 'UN',
      status: 'PENDING',
      version: 0,
      totalPriceCents: null,
    },
    {
      id: 'b',
      name: 'Bala',
      quantity: 1,
      unit: 'UN',
      status: 'PENDING',
      version: 0,
      totalPriceCents: null,
    },
  ]
  const writes = []
  await page.route('**/api/v1/**', async (route) => {
    const req = route.request(),
      url = new URL(req.url()),
      path = url.pathname.replace('/api/v1', '')
    let data = {}
    if (path === '/auth/me') data = user
    else if (path === '/shopping-list/cart') {
      const cart = items.filter((i) => i.status === 'IN_CART')
      data = {
        cartCount: cart.length,
        pendingCount: items.length - cart.length,
        cartTotalCents: cart.reduce((sum, i) => sum + (i.totalPriceCents || 0), 0),
        missingPrices: cart.filter((i) => i.totalPriceCents == null).length,
        missingQuantities: 0,
        cartItems: cart,
      }
    } else if (req.method() === 'PATCH' && path.startsWith('/shopping-list/items/')) {
      const body = req.postDataJSON(),
        item = items.find((i) => i.id === path.split('/').at(-1))
      writes.push(body)
      Object.assign(item, {
        ...body,
        status: body.inCart === true ? 'IN_CART' : body.inCart === false ? 'PENDING' : item.status,
        version: item.version + 1,
      })
      data = item
    } else if (path === '/shopping-list/items') {
      const rows = [...items].sort(
        (a, b) =>
          (url.searchParams.get('order') === 'CART_LAST'
            ? Number(a.status === 'IN_CART') - Number(b.status === 'IN_CART')
            : 0) || a.name.localeCompare(b.name),
      )
      data = { items: rows, total: rows.length, page: 0, size: 50 }
    }
    await route.fulfill({ json: data })
  })
  await page.goto('/compras')
  await expect(page.getByRole('button', { name: 'Enviar enquete no WhatsApp' })).toHaveCount(0)
  await page.getByRole('textbox', { name: 'Preço: Arroz', exact: true }).fill('0,10')
  await page.getByRole('textbox', { name: 'Preço: Arroz', exact: true }).press('Tab')
  await expect(
    page.getByRole('checkbox', { name: 'No carrinho: Arroz', exact: true }),
  ).toBeChecked()
  expect(writes.some((body) => body.totalPriceCents === 10)).toBe(true)
  await page.getByLabel('Ordenar lista', { exact: true }).selectOption('CART_LAST')
  await expect(page.locator('.shopping-list-row').first()).toHaveAttribute('aria-label', 'Bala')
  await page.reload()
  await expect(page.getByLabel('Ordenar lista', { exact: true })).toHaveValue('CART_LAST')
  await expect(page.locator('.shopping-list-row').first()).toHaveAttribute('aria-label', 'Bala')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  )
})
