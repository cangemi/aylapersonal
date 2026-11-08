import { test, expect } from '@playwright/test'

test('registers an existing balance at another bank and reconciles it without another deposit', async ({ page }) => {
  const user = { id: 'portfolio-user', username: 'alice', role: 'USER', displayName: 'Alice' }
  const positions = [], requests = [], errors = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.addInitScript((user) => localStorage.setItem('ayla.session', JSON.stringify({ accessToken: 'test', refreshToken: 'test', user })), user)
  await page.route('**/api/v1/**', async (route) => {
    const path = new URL(route.request().url()).pathname.replace('/api/v1', '')
    const method = route.request().method(), body = route.request().postDataJSON()
    requests.push({ path, method, body })
    let response = []
    if (path === '/auth/me') response = user
    if (path === '/investments/analytics') response = { year: 2026, firstYear: 2026, asOf: '2026-11-04', holdings: [], warnings: [] }
    if (path === '/investments/portfolio') response = { asOf: '2026-11-04', balanceCents: positions.reduce((sum, p) => sum + p.balanceCents, 0),
      netInvestedCents: 123450, gainCents: 0, incomeCents: 0, monthGainCents: 0, positions, legacy: null, warnings: [] }
    if (path === '/investments/positions' && method === 'POST') {
      const rate = { type: body.rateType, percent: body.ratePercent, dayBasis: body.dayBasis }
      response = { position: { ...body, id: 'new-position', version: 0, rate }, currentRate: rate, balanceCents: body.openingBalanceCents,
        netInvestedCents: body.openingInvestedCents, gainCents: 0, incomeCents: 0, gainPercent: 0, estimated: true, warnings: [], history: [], events: [] }
      positions.push(response)
    }
    if (path.endsWith('/events') && method === 'POST') {
      positions[0].balanceCents = body.amountCents
      positions[0].position.version++
      response = positions[0]
    }
    await route.fulfill({ json: response })
  })
  await page.goto('/investimentos')
  await page.getByRole('button', { name: 'Adicionar investimento' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('Nome da aplicação').fill('Reserva Inter')
  await dialog.getByLabel('Banco ou corretora').fill('Inter')
  await dialog.getByLabel('Saldo que já tenho').fill('1234,50')
  await dialog.getByLabel('Percentual do CDI').fill('110')
  await dialog.getByRole('button', { name: 'Salvar investimento' }).click()
  await expect(dialog).not.toBeVisible()
  expect(requests.find((r) => r.path === '/investments/positions').body).toMatchObject({
    institution: 'Inter', openingBalanceCents: 123450, openingInvestedCents: 123450, rateType: 'CDI', ratePercent: 110,
  })
  await expect(page.getByRole('heading', { name: 'Reserva Inter' })).toBeVisible()
  await page.getByRole('button', { name: 'Atualizar saldo pelo extrato' }).click()
  await dialog.getByLabel('Saldo total no extrato').fill('1250,00')
  await dialog.getByRole('button', { name: 'Salvar', exact: true }).click()
  await expect(dialog).not.toBeVisible()
  expect(requests.find((r) => r.path.endsWith('/events')).body).toMatchObject({ type: 'VALUATION', amountCents: 125000, version: 0 })
  expect(requests.some((r) => r.path === '/finance/entries' && r.method === 'POST')).toBe(false)
  expect(errors).toEqual([])
})

test('analytics renders charts, filters investment types and loads older years without turning missing returns into zero', async ({ page }) => {
  await page.clock.setFixedTime(new Date('2026-11-04T15:00:00Z'))
  const user = { id: 'analytics-user', username: 'alice', role: 'USER', displayName: 'Alice' }
  const errors = [], queriedYears = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.addInitScript((user) => localStorage.setItem('ayla.session', JSON.stringify({ accessToken: 'test', refreshToken: 'test', user })), user)
  const period = (key, gain, complete = true) => ({ key, complete, gainCents: complete ? gain : null, observedGainCents: gain,
    balanceCents: 110000, netInvestedCents: 100000, accumulatedGainCents: 10000, baseCents: 100000,
    depositsCents: 0, withdrawalsCents: 0, incomeCents: 0, estimated: complete, partial: false })
  const holding = (id, name, assetType, institution, year, manual = false) => ({ id, name, assetType, institution,
    balanceCents: 110000, netInvestedCents: 100000, gainCents: 10000, performance: {
      trackingFrom: '2024-01-01', lastValuationDate: '2026-11-03', currentRate: { type: manual ? 'MANUAL' : 'CDI', percent: 100 },
      day: period('2026-11-04', manual ? 0 : 50, !manual), month: period('2026-11', manual ? 500 : 150, !manual),
      year: period(String(year), year === 2025 ? 8000 : 9000, !manual),
      days: [period(`${year}-11-03`, 40), period(`${year}-11-04`, manual ? 500 : 50, !manual)],
      months: [period(`${year}-10`, 1000), period(`${year}-11`, manual ? 500 : 150, !manual)],
      years: [period('2024', 6000), period('2025', 8000), period('2026', 9000, !manual)],
    } })
  await page.route('**/api/v1/**', async (route) => {
    const url = new URL(route.request().url()), path = url.pathname.replace('/api/v1', '')
    let response = []
    if (path === '/auth/me') response = user
    if (path === '/investments/portfolio') response = { asOf: '2026-11-04', balanceCents: 220000, netInvestedCents: 200000,
      gainCents: 20000, incomeCents: 0, positions: [], legacy: null, warnings: [] }
    if (path === '/investments/analytics') {
      const year = Number(url.searchParams.get('year')); queriedYears.push(year)
      response = { asOf: '2026-11-04', year, firstYear: 2024, warnings: [], holdings: [
        holding('cdb', 'CDB Inter', 'CDB', 'Inter', year), holding('stocks', 'Ações carteira', 'ACAO', 'Corretora', year, true),
      ] }
    }
    await route.fulfill({ json: response })
  })
  await page.goto('/investimentos')
  const dashboard = page.getByRole('region', { name: 'Análise completa dos investimentos' })
  await expect(dashboard.getByRole('img', { name: 'Rendimento de cada dia do mês escolhido' })).toBeVisible()
  await expect(dashboard.getByRole('img')).toHaveCount(7)
  await expect(dashboard.getByText('Dados incompletos', { exact: false }).first()).toBeVisible()
  await dashboard.getByRole('combobox', { name: 'Tipo de investimento', exact: true }).selectOption('CDB')
  const table = dashboard.getByLabel('Rendimento por aplicação', { exact: true })
  await expect(table.getByRole('row')).toHaveCount(2)
  await expect(table).toContainText('CDB Inter')
  await expect(table).not.toContainText('Ações carteira')
  await dashboard.getByLabel('Ano da análise').selectOption('2025')
  await expect(dashboard.getByRole('heading', { name: 'Evolução em 2025' })).toBeVisible()
  expect(queriedYears).toContain(2025)
  await dashboard.getByRole('combobox', { name: 'Tipo de investimento', exact: true }).selectOption('ACAO')
  await expect(table).toContainText('Ações carteira')
  await expect(table.getByRole('cell', { name: '—', exact: false }).first()).toBeVisible()
  const download = page.waitForEvent('download')
  await dashboard.getByRole('button', { name: 'Exportar CSV' }).click()
  expect((await download).suggestedFilename()).toBe('investimentos-2025.csv')
  await dashboard.getByRole('heading', { name: 'Rendimento diário', exact: true }).scrollIntoViewIfNeeded()
  await page.screenshot({ path: 'test-results/analytics-desktop.png' })
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(dashboard.getByLabel('Ano da análise')).toBeVisible()
  await dashboard.locator('.performance-controls').scrollIntoViewIfNeeded()
  await page.screenshot({ path: 'test-results/analytics-mobile.png' })
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  expect(errors).toEqual([])
})
