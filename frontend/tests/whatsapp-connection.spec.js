import { test, expect } from '@playwright/test'

async function fixture(page, { onboarding = false, hermesReady = false } = {}) {
  page.on('pageerror', (error) => {
    throw error
  })
  const user = {
    id: 'wa-test-user',
    username: 'alice',
    role: 'USER',
    displayName: 'Alice',
    onboardingRequired: onboarding,
  }
  const state = { mode: 'own', connected: false, ready: false, state: 'disconnected', ayla: null }
  const requests = []
  await page.addInitScript(
    ({ user }) => {
      localStorage.setItem(
        'ayla.session',
        JSON.stringify({ accessToken: 'fake-access', refreshToken: 'fake-refresh', user }),
      )
    },
    { user },
  )
  await page.route('**/api/v1/**', async (route) => {
    const path = new URL(route.request().url()).pathname.replace('/api/v1', '')
    requests.push({ path, method: route.request().method(), body: route.request().postDataJSON() })
    let response = []
    if (path === '/auth/me') response = user
    else if (path === '/me/whatsapp/status') response = state
    else if (path === '/me/whatsapp/configuration') {
      state.mode = route.request().postDataJSON().mode
      state.ayla = state.mode === 'dedicated' ? { connected: false } : null
      state.ready = false
      response = state
    } else if (path.endsWith('/qr')) response = {}
    else if (path === '/me/hermes/status')
      response = {
        provisioned: true,
        ready: hermesReady,
        connected: true,
        loggedIn: true,
        message: 'Hermes preparado para concluir.',
      }
    else if (path === '/me/hermes/provision') response = { ready: true }
    else if (path === '/finance/summary')
      response = { balanceCents: 0, period: { incomeCents: 0, expenseCents: 0 }, series: [] }
    else if (['/finance/entries', '/shopping-list/items', '/pantry/items'].includes(path))
      response = { items: [], total: 0 }
    else if (path === '/auth/onboarding/complete') {
      user.onboardingRequired = false
      response = user
    }
    await route.fulfill({ json: response })
  })
  return { state, requests }
}

test('onboarding keeps the WhatsApp step optional, including unavailable and partial connections', async ({
  page,
}) => {
  const { requests } = await fixture(page, { onboarding: true })
  await page.goto('/configuracao-inicial')
  await expect(page.locator('.onboarding-steps li')).toHaveCount(4)
  await expect(page.locator('.onboarding-steps li.done')).toHaveCount(2)
  await expect(
    page.getByText('Converse com a Ayla pelo chat do site ou conecte-a ao seu WhatsApp', {
      exact: false,
    }),
  ).toBeVisible()
  await expect(page.getByRole('button', { name: 'Continuar', exact: true })).toBeDisabled()
  await page.getByRole('radio', { name: 'Usar um número exclusivo' }).check()
  await expect(page.getByRole('heading', { name: 'WhatsApp exclusivo da Ayla' })).toBeVisible()
  await page.getByRole('button', { name: 'Pular por enquanto' }).click()
  await expect(page.getByRole('heading', { name: 'Conecte sua conta ao Hermes' })).toBeVisible()
  await expect(page.locator('.onboarding-steps li.skipped')).toContainText('WhatsApp')
  await page.getByRole('button', { name: 'Voltar', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Leve a Ayla para o WhatsApp' })).toBeVisible()
  await expect(page.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '3')
  await page.getByRole('button', { name: 'Pular por enquanto' }).click()
  await expect(page.getByRole('heading', { name: 'Conecte sua conta ao Hermes' })).toBeVisible()
  expect(
    requests.filter((request) => /whatsapp\/(?:ayla\/)?connect$/.test(request.path)),
  ).toHaveLength(0)
  expect(
    requests.some(
      (request) =>
        request.path === '/me/whatsapp/configuration' && request.body.mode === 'dedicated',
    ),
  ).toBeTruthy()
  await page.route('**/api/v1/me/hermes/status', (route) =>
    route.fulfill({ json: { provisioned: true, ready: true, message: 'Hermes pronto.' } }),
  )
  await page.getByRole('button', { name: 'Verificar conexão' }).click()
  await page.getByRole('button', { name: 'Concluir', exact: true }).click()
  await expect(page).toHaveURL(/\/$/)
  expect(requests.some((request) => request.path === '/auth/onboarding/complete')).toBeTruthy()
})

test('already prepared Hermes remains on screen so a skipped WhatsApp step can be revisited', async ({
  page,
}) => {
  const { requests } = await fixture(page, { onboarding: true, hermesReady: true })
  await page.goto('/configuracao-inicial')
  await page.getByRole('button', { name: 'Pular por enquanto' }).click()
  await expect(page.getByRole('button', { name: 'Concluir', exact: true })).toBeEnabled()
  expect(requests.some((request) => request.path === '/auth/onboarding/complete')).toBeFalsy()
  await page
    .locator('.onboarding-steps')
    .getByRole('button', { name: /WhatsApp/ })
    .click()
  await expect(page.getByRole('heading', { name: 'Leve a Ayla para o WhatsApp' })).toBeVisible()
  await page
    .locator('.onboarding-steps')
    .getByRole('button', { name: /Nova senha/ })
    .click()
  await expect(page.getByRole('heading', { name: 'Sua senha está pronta' })).toBeVisible()
})

test('onboarding can skip WhatsApp even when the service is unavailable', async ({ page }) => {
  await fixture(page, { onboarding: true })
  await page.route('**/api/v1/me/whatsapp/status', (route) =>
    route.fulfill({ status: 503, json: { message: 'WhatsApp indisponível' } }),
  )
  await page.goto('/configuracao-inicial')
  await expect(page.getByRole('alert')).toContainText('WhatsApp indisponível')
  await page.getByRole('button', { name: 'Pular por enquanto' }).click()
  await expect(page.getByRole('heading', { name: 'Conecte sua conta ao Hermes' })).toBeVisible()
})

for (const path of ['/configuracao-inicial', '/whatsapp']) {
  test(`QR loading stays visible and independent until each requested image loads: ${path}`, async ({
    page,
  }) => {
    const { state } = await fixture(page, { onboarding: path === '/configuracao-inicial' })
    state.mode = 'dedicated'
    state.ayla = { connected: false, state: 'disconnected' }
    const reads = { user: 0, ayla: 0 }
    const image =
      'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/l9sAAAAASUVORK5CYII='
    await page.route('**/api/v1/me/whatsapp/**', async (route) => {
      const endpoint = new URL(route.request().url()).pathname
      const role = endpoint.includes('/ayla/') ? 'ayla' : 'user'
      if (endpoint.endsWith('/connect')) {
        const account = role === 'ayla' ? state.ayla : state
        account.state = 'connecting'
        return route.fulfill({ json: { state: 'connecting' } })
      }
      if (endpoint.endsWith('/qr')) {
        reads[role]++
        return route.fulfill({
          json: {
            state: 'awaiting_qr',
            qrImageDataUrl: reads[role] >= (role === 'user' ? 3 : 2) ? image : null,
          },
        })
      }
      return route.fallback()
    })
    await page.goto(path)
    const own = page.locator('.whatsapp-account[data-role="user"]')
    const ayla = page.locator('.whatsapp-account[data-role="ayla"]')
    await expect(ayla).toBeVisible()
    expect(reads).toEqual({ user: 0, ayla: 0 })
    await own.getByRole('button', { name: 'Conectar meu WhatsApp' }).click()
    await expect(own.locator('.qr-placeholder')).toHaveAttribute('aria-busy', 'true')
    await expect(ayla.locator('.qr-placeholder')).toHaveAttribute('aria-busy', 'false')
    await expect.poll(() => reads.user).toBeGreaterThanOrEqual(2)
    await expect(own.locator('.qr-loading')).toBeVisible()
    expect(reads.ayla).toBe(0)
    await expect(own.getByRole('img')).toBeVisible({ timeout: 10000 })
    await expect(own.locator('.qr-loading')).toHaveCount(0)
    await ayla.getByRole('button', { name: 'Conectar número da Ayla' }).click()
    await expect(ayla.locator('.qr-loading')).toBeVisible()
    await expect(own.getByRole('img')).toBeVisible()
    await expect(ayla.getByRole('img')).toBeVisible({ timeout: 10000 })
    await expect(ayla.locator('.qr-loading')).toHaveCount(0)
  })
}

test('failed QR generation stops only that account and allows retry without loading its companion', async ({
  page,
}) => {
  const { state } = await fixture(page)
  state.mode = 'dedicated'
  state.ayla = { connected: false }
  let attempts = 0
  await page.route('**/api/v1/me/whatsapp/connect', (route) => {
    attempts++
    return route.fulfill({ status: 503, json: { message: 'Falha ao preparar a conexão' } })
  })
  await page.goto('/whatsapp')
  const own = page.locator('.whatsapp-account[data-role="user"]')
  const ayla = page.locator('.whatsapp-account[data-role="ayla"]')
  await own.getByRole('button', { name: 'Conectar meu WhatsApp' }).click()
  await expect(own.getByRole('alert')).toContainText('Falha ao preparar a conexão')
  await expect(own.locator('.qr-placeholder')).toHaveAttribute('aria-busy', 'false')
  await expect(ayla.locator('.qr-placeholder')).toHaveAttribute('aria-busy', 'false')
  await own.getByRole('button', { name: 'Conectar meu WhatsApp' }).click()
  await expect.poll(() => attempts).toBe(2)
})

test('disconnected chat button explains the connection and provides cancel and configuration actions', async ({
  page,
}) => {
  const { requests } = await fixture(page)
  await page.goto('/chat')
  await page.getByRole('button', { name: 'Começar uma conversa com Ayla no WhatsApp' }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toContainText('Você pode continuar conversando pelo chat do site')
  await dialog.getByRole('button', { name: 'Cancelar' }).click()
  await expect(dialog).not.toBeVisible()
  await page.getByRole('button', { name: 'Começar uma conversa com Ayla no WhatsApp' }).click()
  await dialog.getByRole('link', { name: 'Configurar WhatsApp' }).click()
  await expect(page).toHaveURL(/\/conexoes\?aba=whatsapp$/)
  expect(requests.filter((request) => request.path.endsWith('/personal-chat'))).toHaveLength(0)
})

test('dedicated connection shows two sessions and keeps chatting disabled until both are ready', async ({
  page,
}) => {
  const { state } = await fixture(page)
  await page.goto('/whatsapp')
  await expect(page.getByRole('radio', { name: 'Usar meu próprio número' })).toBeChecked()
  await page.getByRole('radio', { name: 'Usar um número exclusivo' }).check()
  await expect(page.getByRole('button', { name: 'Conectar meu WhatsApp' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Conectar número da Ayla' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Continuar conversa com Ayla' })).toBeDisabled()
  state.connected = true
  state.number = '5511888888888'
  await page.reload()
  await expect(page.getByRole('button', { name: 'Continuar conversa com Ayla' })).toBeDisabled()
  state.ayla = { connected: true, number: '5511777777777' }
  state.ready = true
  await page.reload()
  await expect(page.getByRole('button', { name: 'Continuar conversa com Ayla' })).toBeEnabled()
  await page.screenshot({ path: 'test-results/whatsapp-dedicated-desktop.png', fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.getByRole('radio', { name: 'Usar um número exclusivo' })).toBeVisible()
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
  ).toBeTruthy()
  await page.screenshot({ path: 'test-results/whatsapp-dedicated-mobile.png', fullPage: true })
})

test('dedicated chat opens the Ayla number directly without creating a group', async ({ page }) => {
  const { state, requests } = await fixture(page)
  Object.assign(state, {
    mode: 'dedicated',
    connected: true,
    ready: true,
    number: '5511888888888',
    ayla: { connected: true, number: '5511777777777' },
  })
  await page.route('**/api/v1/me/whatsapp/personal-chat', (route) =>
    route.fulfill({ json: { mode: 'dedicated', url: 'https://wa.me/5511777777777' } }),
  )
  await page.route('https://wa.me/**', (route) =>
    route.fulfill({ contentType: 'text/html', body: 'Conversa com Ayla' }),
  )
  await page.goto('/chat')
  await page.getByRole('button', { name: 'Começar uma conversa com Ayla no WhatsApp' }).click()
  await expect(page).toHaveURL('https://wa.me/5511777777777')
  expect(
    requests.some((request) => request.path.endsWith('/chats') && request.method === 'POST'),
  ).toBeFalsy()
})
