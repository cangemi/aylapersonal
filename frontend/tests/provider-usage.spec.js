import { test, expect } from '@playwright/test'

async function fixture(page, { quotaStatus = 'KNOWN', remaining = 1645 } = {}) {
  const user = { id: 'usage-user', username: 'alice', role: 'USER', displayName: 'Alice' }
  await page.clock.install({ time: new Date('2030-01-01T12:00:00Z') }) // Deliberately wrong device clock.
  await page.addInitScript(
    (user) =>
      localStorage.setItem(
        'ayla.session',
        JSON.stringify({ accessToken: 'test', refreshToken: 'test', user }),
      ),
    user,
  )
  let reads = 0
  const now = '2026-11-04T22:00:00Z'
  const availability = {
    provider: 'nous',
    model: 'model-a:free',
    status: 'RATE_LIMITED',
    serverNow: now,
    detectedAt: now,
    availableAt:
      remaining === null ? null : new Date(Date.parse(now) + remaining * 1000).toISOString(),
    retryAttemptAt: null,
    alternates: ['model-b:free'],
  }
  await page.route('**/api/v1/**', async (route) => {
    const path = new URL(route.request().url()).pathname.replace('/api/v1', '')
    let response = []
    if (path === '/auth/me') response = user
    if (path === '/me/hermes/status')
      response = { provisioned: true, connected: true, loggedIn: true, ready: true }
    if (path === '/me/hermes/usage') {
      reads++
      response = {
        serverNow: now,
        provider: 'nous',
        model: 'model-a:free',
        status: 'RATE_LIMITED',
        connected: true,
        availability,
        quotaStatus,
        quotaUpdatedAt: now,
        quotaSource: 'API oficial Nous',
        sharedCredentials: true,
        quotas: [
          {
            label: 'Diária',
            usagePercentage: 80,
            scope: 'ACCOUNT',
            resetsAt: '2026-11-05T00:00:00Z',
          },
        ],
        models: [availability, { model: 'model-b:free', status: 'AVAILABLE', confirmedAt: now }],
        recordedUsage: { calls: 2, unreported_calls: 2, total_tokens: null },
        recordedUsagePeriod: 'Uso local; não representa a cota Nous.',
      }
    }
    await route.fulfill({ json: response })
  })
  await page.goto('/conexoes?aba=hermes')
  await expect(
    page.getByRole('region', { name: 'Consumo e disponibilidade do Hermes' }),
  ).toBeVisible()
  return { reads: () => reads }
}

for (const width of [1440, 390]) {
  test(`real quota and server countdown render at ${width}px without per-second HTTP`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height: 1050 })
    const count = await fixture(page)
    const panel = page.getByRole('region', { name: 'Consumo e disponibilidade do Hermes' })
    await expect(panel.locator('progress')).toHaveAttribute('value', '80')
    await expect(panel.getByText('20% restante', { exact: false })).toBeVisible()
    await expect(panel.getByText('00:27:25', { exact: true })).toBeVisible()
    await page.clock.runFor(3000)
    await expect(panel.getByText('00:27:22', { exact: true })).toBeVisible()
    expect(count.reads()).toBe(1)
    await panel.getByText('Disponibilidade por modelo', { exact: true }).click()
    await expect(panel.getByText('Disponível', { exact: true })).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  })
}
test('expiry stays pending and an unknown quota has no percentage bar', async ({ page }) => {
  await fixture(page, { quotaStatus: 'UNAVAILABLE', remaining: 2 })
  const panel = page.getByRole('region', { name: 'Consumo e disponibilidade do Hermes' })
  await expect(panel.locator('progress')).toHaveCount(0)
  await expect(
    panel.getByText('Consumo indisponível na integração atual.', { exact: false }),
  ).toBeVisible()
  await page.clock.runFor(3000)
  await expect(panel.getByText('Prazo encerrado', { exact: false }).first()).toBeVisible()
  await expect(panel.getByText('00:00:00', { exact: true })).toBeVisible()
})
test('usage API failure is explicit and a missing delay is not guessed', async ({ page }) => {
  await fixture(page, { quotaStatus: 'ERROR', remaining: null })
  const panel = page.getByRole('region', { name: 'Consumo e disponibilidade do Hermes' })
  await expect(panel.locator('progress')).toHaveCount(0)
  await expect(panel.getByText('Não foi possível consultar o consumo na Nous.')).toBeVisible()
  await expect(panel.getByText('O provedor não informou o prazo para liberação.')).toBeVisible()
})
