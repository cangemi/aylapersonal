import { test, expect } from '@playwright/test'
const session = {
  accessToken: 'browser-test-access',
  refreshToken: 'browser-test-refresh',
  user: { id: 'test-owner', username: 'pedro@example.test', displayName: 'Pedro', role: 'OWNER' },
}
const entry = {
  id: 'entry-1',
  description: 'Café da manhã',
  category: 'Alimentação',
  amountCents: 1590,
  type: 'EXPENSE',
  occurredAt: '2026-10-24T10:00:00-03:00',
  version: 0,
}
async function fixture(page) {
  let profile = { ...session.user }
  const entries = [{ ...entry }],
    shopping = [
      {
        id: 'shopping-1',
        name: 'Café',
        quantity: 1,
        unit: 'PACOTE',
        status: 'PENDING',
        version: 0,
      },
    ],
    pantry = [
      {
        id: 'pantry-1',
        name: 'Arroz',
        quantity: 1,
        minimumQuantity: 2,
        unit: 'KG',
        lowStock: true,
        version: 0,
      },
    ],
    requests = []
  requests.historyPages = {}
  requests.conversationList = [
    { conversationId: 'conversation-1', title: 'Nosso dia a dia', current: true },
  ]
  page.on('pageerror', (error) => {
    throw error
  })
  await page.route('**/api/v1/**', async (route) => {
    const req = route.request(),
      url = new URL(req.url()),
      path = url.pathname.replace('/api/v1', ''),
      method = req.method(),
      body = req.postDataJSON()
    requests.push({ path, method, body })
    let response = {}
    if (path === '/auth/login') response = { ...session, user: profile }
    else if (path === '/auth/me') response = profile
    else if (path === '/auth/profile' && method === 'PUT') {
      profile = { ...profile, displayName: body.displayName }
      response = profile
    } else if (path === '/auth/forgot-password')
      response = {
        message:
          'Se houver uma conta com WhatsApp cadastrado e disponível, a nova senha será enviada para esse número.',
      }
    else if (path === '/finance/summary')
      response = {
        balanceCents: 485910,
        period: { incomeCents: 500000, expenseCents: 14090 },
        series: [
          { label: '2026-10-01', incomeCents: 500000, expenseCents: 0 },
          { label: '2026-10-12', incomeCents: 0, expenseCents: 12500 },
          { label: '2026-10-24', incomeCents: 0, expenseCents: 1590 },
        ],
      }
    else if (path === '/finance/insights')
      response = {
        timezone: 'America/Sao_Paulo',
        today: '2026-10-24',
        months: ['2026-08', '2026-09', '2026-10'].map((label, i) => ({
          label,
          incomeCents: 500000,
          expenseCents: 200000 + i * 20000,
          investedCents: 50000,
          netCents: 250000,
        })),
        current: {
          month: '2026-10',
          daysElapsed: 24,
          daysInMonth: 31,
          expenseCents: 240000,
          incomeCents: 500000,
          investedCents: 50000,
          dailyAverageCents: 10000,
          projectedExpenseCents: 310000,
          previousMonthExpenseCents: 220000,
          previousSamePeriodExpenseCents: 200000,
          changePercent: 20,
          savingsRatePercent: 52,
          largestExpenseCents: 80000,
          largestExpenseDescription: 'Aluguel',
        },
        categories: [{ category: 'mercado', currentCents: 90000, previousCents: 60000 }],
        weekdayExpenseCents: [1000, 2000, 3000, 4000, 5000, 6000, 7000],
        cumulative: { currentCents: [100, 200, 300], previousCents: [100, 150, 400, 500] },
        trend: { direction: 'UP', slopeCentsPerMonth: 20000, lineCents: [200000, 220000, 240000] },
      }
    else if (path === '/investments/overview')
      response = {
        name: 'Caixinha Nubank',
        cdiPercent: 100,
        hasData: true,
        asOf: '2026-10-24',
        cdi: {
          dailyPercent: 0.050788,
          annualPercent: 13.65,
          date: '2026-10-23',
          stale: false,
          source: 'Banco Central',
          history: [
            { date: '2026-10-22', dailyPercent: 0.050788, annualPercent: 13.65 },
            { date: '2026-10-23', dailyPercent: 0.050788, annualPercent: 13.65 },
          ],
        },
        productAnnualPercent: 13.65,
        balanceCents: 1000500,
        depositedCents: 1000000,
        withdrawnCents: 0,
        netInvestedCents: 1000000,
        gainCents: 500,
        gainPercent: 0.05,
        lastDayYieldCents: 508,
        lastYieldDate: '2026-10-23',
        monthYieldCents: 500,
        last30DaysYieldCents: 500,
        nextDayYieldCents: 508,
        nextYieldDate: '2026-10-26',
        nextMonthYieldCents: 10900,
        nextMonth: '2026-11',
        projections: [
          { label: 'Fim do mês', until: '2026-10-30', balanceCents: 1001000, yieldCents: 1000 },
        ],
        history: [
          { date: '2026-10-22', balanceCents: 1000000, investedCents: 1000000, yieldCents: 0 },
          { date: '2026-10-23', balanceCents: 1000500, investedCents: 1000000, yieldCents: 500 },
        ],
        months: [
          { month: '2026-10', yieldCents: 500, netDepositCents: 1000000, endBalanceCents: 1000500 },
        ],
        movements: [
          {
            entryId: 'm1',
            date: '2026-10-22',
            type: 'DEPOSIT',
            amountCents: 1000000,
            description: 'Aporte na caixinha',
          },
        ],
      }
    else if (path === '/investments/simulate')
      response = {
        cdiAnnualPercent: 13.65,
        percentCdi: 100,
        productAnnualPercent: 13.65,
        initialCents: 1000500,
        monthlyCents: 10000,
        months: 2,
        finalBalanceCents: 1030000,
        investedCents: 1020000,
        gainCents: 10000,
        points: [
          {
            month: 0,
            date: '2026-10-24',
            balanceCents: 1000500,
            investedCents: 1000500,
            gainCents: 0,
          },
          {
            month: 1,
            date: '2026-11-24',
            balanceCents: 1020000,
            investedCents: 1010500,
            gainCents: 9500,
          },
        ],
      }
    else if (path === '/finance/entries' && method === 'POST') {
      const item = { ...body, id: 'created-entry', version: 0 }
      entries.unshift(item)
      response = item
    } else if (path === '/finance/entries') response = { items: entries, total: entries.length }
    else if (path.startsWith('/pantry/items')) response = { items: pantry, total: 1 }
    else if (path === '/shopping-list/items' && method === 'POST') {
      const item = { ...body, id: 'created-shopping', status: 'PENDING', version: 0 }
      shopping.push(item)
      response = item
    } else if (path === '/shopping-list/items')
      response = { items: shopping, total: shopping.length }
    else if (/^\/conversations\/[^/]+\/messages$/.test(path))
      response = requests.historyPages[
        path.split('/')[2] + ':' + (url.searchParams.get('offset') || '0')
      ] || { items: [], hasMore: false, nextOffset: 0 }
    else if (/^\/conversations\/[^/]+\/current$/.test(path)) {
      const selected = path.split('/')[2]
      requests.conversationList.forEach((conversation) => {
        conversation.current = conversation.conversationId === selected
      })
      response = requests.conversationList.find((conversation) => conversation.current) || {}
    } else if (path === '/conversations') response = requests.conversationList
    else if (path === '/chat')
      response = { response: 'Claro! **Tudo organizado.**', conversationId: 'conversation-1' }
    else if (path === '/memories')
      response = [
        {
          id: 'memory-1',
          subject: 'Meu cachorro',
          content: 'O nome dele é Bento.',
          kind: 'PET',
          source: 'USER',
          updatedAt: '2026-10-24T10:00:00Z',
        },
      ]
    else if (path === '/whatsapp/status')
      response = { connected: false, state: 'disconnected', sessionSaved: false }
    else if (path === '/whatsapp/chats')
      response = [
        {
          id: 'chat-1',
          name: 'Minha conversa',
          phone: '5511999999999',
          enabled: true,
          mentionOnly: true,
        },
      ]
    else if (['/hermes/voice/voices', '/me/agent/voice/voices'].includes(path))
      response = [
        { id: 'pt-BR-FranciscaNeural', name: 'Francisca' },
        { id: 'pt-BR-AntonioNeural', name: 'Antônio' },
      ]
    else if (['/hermes/voice/preview', '/me/agent/voice/preview'].includes(path))
      response = { mimeType: 'audio/mpeg', data: 'SUQzAA==' }
    else if (['/hermes/voice', '/me/agent/voice'].includes(path))
      response = { voice: 'pt-BR-FranciscaNeural', speed: 1.02, pitchHz: 2 }
    else if (path === '/hermes/models') response = [{ model: 'modelo-gratuito', free: true }]
    else if (path === '/hermes/configuration') response = { model: null, instructions: null }
    else if (path === '/hermes/status')
      response = {
        ready: true,
        model: 'modelo-gratuito',
        message: 'Hermes pronto com modelo gratuito.',
      }
    await route.fulfill({ status: path === '/auth/forgot-password' ? 202 : 200, json: response })
  })
  return requests
}
async function login(page) {
  await page.goto('/')
  await expect(page).toHaveURL(/login/)
  await page.locator('.login-layout').evaluate(async (element) => {
    const source = getComputedStyle(element)
      .getPropertyValue('--login-background')
      .trim()
      .slice(4, -1)
      .replace(/^['"]|['"]$/g, '')
    const image = new Image()
    image.src = source
    await image.decode()
  })
  await expect(page.locator('.login-layout img')).toHaveCount(0)
  await expect(page.locator('.login-logo')).toHaveAccessibleName('Ayla')
  await expect(page.locator('.login-logo-name')).toHaveText('A✦')
  await expect
    .poll(() =>
      page
        .locator('img')
        .evaluateAll((images) => images.every((image) => image.complete && image.naturalWidth > 0)),
    )
    .toBeTruthy()
  await page.screenshot({
    path: `test-results/login-${page.viewportSize().width > 900 ? 'desktop' : 'mobile'}.png`,
    fullPage: true,
  })
  await page.getByLabel('Seu login').fill('pedro')
  await page.getByLabel('Senha', { exact: true }).fill('test-password-only')
  await page.getByRole('button', { name: 'Entrar no meu espaço' }).click()
  await expect(page.getByRole('heading', { name: 'Oi, Pedro' })).toBeVisible()
}
test('fila recupera a resposta depois de recarregar a página sem reenviar o pedido', async ({
  page,
}) => {
  await fixture(page)
  let posts = 0,
    accepted = false,
    complete = false,
    acknowledged = false
  const job = () => ({
    id: '00000000-0000-4000-8000-000000000099',
    status: complete ? 'COMPLETED' : 'QUEUED',
    conversationId: 'conversation-1',
    userMessage: 'Oi, aguarde minha vez',
    message: 'Seu pedido está aguardando na fila.',
    ...(complete
      ? { result: { response: 'Resposta recuperada da fila', conversationId: 'conversation-1' } }
      : {}),
  })
  await page.route('**/api/v1/chat**', async (route) => {
    const url = new URL(route.request().url())
    if (route.request().method() === 'POST' && url.pathname.endsWith('/chat')) {
      posts++
      accepted = true
      return route.fulfill({ status: 202, json: job() })
    }
    if (url.pathname.endsWith('/ack')) {
      acknowledged = true
      return route.fulfill({ json: {} })
    }
    if (url.pathname.endsWith('/jobs'))
      return route.fulfill({ json: accepted && !acknowledged ? [job()] : [] })
    return route.fulfill({ json: job() })
  })
  await login(page)
  await page.getByRole('link', { name: 'Conversar com Ayla' }).click()
  await page
    .getByRole('textbox', { name: 'Sua mensagem para a Ayla' })
    .fill('Oi, aguarde minha vez')
  await page.getByRole('button', { name: 'Enviar mensagem' }).click()
  await expect(page.getByText('Seu pedido está aguardando na fila.')).toBeVisible()
  complete = true
  await page.reload()
  await expect(page.getByText('Resposta recuperada da fila', { exact: true })).toBeVisible()
  await expect.poll(() => acknowledged).toBe(true)
  expect(posts).toBe(1)
})

test('máscaras formatam dinheiro, quantidade e telefone sem mudar os dados enviados', async ({
  page,
}) => {
  const requests = await fixture(page)
  await login(page)
  await page.getByRole('link', { name: 'Financeiro', exact: true }).click()
  await page.getByRole('button', { name: 'Novo lançamento' }).click()
  const amount = page.getByLabel('Valor em reais')
  await amount.pressSequentially('1234,56')
  await expect(amount).toHaveValue('R$ 1.234,56')
  await amount.fill('0,12')
  await page.getByLabel('Descrição', { exact: true }).fill('Teste de máscara')
  await page.getByRole('dialog').getByLabel('Categoria', { exact: true }).fill('Outros')
  await page.getByRole('button', { name: 'Salvar lançamento' }).click()
  expect(
    requests.find((r) => r.path === '/finance/entries' && r.method === 'POST').body.amountCents,
  ).toBe(12)
  await page.getByRole('link', { name: 'Investimentos', exact: true }).click()
  await page.getByLabel('Aporte mensal (R$)', { exact: true }).fill('2000,50')
  await page.getByLabel('CDI anual (%)', { exact: true }).fill('13,65')
  await page.getByLabel('Valor inicial (R$)', { exact: true }).fill('0')
  await page.getByRole('button', { name: 'Simular', exact: true }).click()
  const simulation = requests.find((r) => r.path === '/investments/simulate').body
  expect(simulation.monthlyCents).toBe(200050)
  expect(simulation.initialCents).toBe(0)
  expect(simulation.cdiAnnualPercent).toBe(13.65)
  await page.getByRole('link', { name: 'Despensa', exact: true }).click()
  await page.getByRole('button', { name: 'Adicionar produto', exact: true }).click()
  await page.getByLabel('Produto', { exact: true }).fill('Arroz teste')
  await page.getByLabel('Quantidade', { exact: true }).fill('0,125')
  await page.getByRole('dialog').getByRole('combobox').selectOption('KG')
  await page.getByRole('button', { name: 'Salvar produto', exact: true }).click()
  expect(
    requests.find((r) => r.path === '/pantry/items' && r.method === 'POST').body.quantity,
  ).toBe('0.125')
  await page.getByRole('link', { name: 'Conexões', exact: true }).click()
  await page.getByRole('button', { name: 'Adicionar conversa', exact: true }).click()
  const destination = page.getByLabel('Telefone ou identificador do grupo')
  await destination.fill('5511999999999')
  await expect(destination).toHaveValue('+55 (11) 99999-9999')
  await destination.fill('120363000000000000@g.us')
  await expect(destination).toHaveValue('120363000000000000@g.us')
})

test('arquivo de tela indisponível após atualização recupera o login sem repetir autenticação', async ({
  page,
}) => {
  const requests = await fixture(page)
  let loads = 0
  await page.route('**/src/pages/Dashboard.vue*', (route) => {
    loads++
    return loads === 1 ? route.abort('failed') : route.continue()
  })
  await login(page)
  await expect(page.getByRole('heading', { name: 'Oi, Pedro' })).toBeVisible()
  expect(loads).toBeGreaterThanOrEqual(2)
  expect(requests.filter((r) => r.path === '/auth/login' && r.method === 'POST')).toHaveLength(1)
  expect(
    await page.evaluate(() => JSON.parse(sessionStorage.getItem('ayla.session')).accessToken),
  ).toBe(session.accessToken)
})

test('superadmin lista e cadastra usuários e redefine a senha pelo celular', async ({ page }) => {
  await fixture(page)
  const accounts = [
    { ...session.user, enabled: true, createdAt: '2026-10-25T12:00:00Z' },
    {
      id: 'test-member',
      username: 'user@example.test',
      displayName: 'Usuário teste',
      role: 'USER',
      enabled: true,
      createdAt: '2026-10-25T13:00:00Z',
    },
  ]
  let resets = 0
  let stateChanges = 0
  await page.route('**/api/v1/auth/users**', (route) => {
    const req = route.request(),
      url = new URL(req.url())
    if (req.method() === 'PATCH' && url.pathname.endsWith('/status')) {
      expect(url.pathname).toBe('/api/v1/auth/users/test-member/status')
      accounts[1].enabled = req.postDataJSON().enabled
      stateChanges++
      return route.fulfill({ status: 204 })
    }
    if (url.pathname.endsWith('/reset-password')) {
      expect(url.pathname).toBe('/api/v1/auth/users/test-member/reset-password')
      expect(req.postDataJSON()).toEqual({ newPassword: 'synthetic-reset-password' })
      resets++
      return route.fulfill({ status: 204 })
    }
    if (req.method() === 'POST') {
      const account = {
        id: 'created-user',
        username: req.postDataJSON().username,
        role: 'USER',
        enabled: true,
        createdAt: '2026-10-25T14:00:00Z',
      }
      accounts.push(account)
      return route.fulfill({ json: account })
    }
    const q = (url.searchParams.get('q') || '').toLowerCase(),
      items = accounts.filter((a) => (a.username + (a.displayName || '')).toLowerCase().includes(q))
    return route.fulfill({ json: { items, total: items.length, page: 0, size: 20 } })
  })
  await login(page)
  await page.getByRole('link', { name: 'Configurações', exact: true }).click()
  await page.getByRole('button', { name: 'Usuários', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Usuários cadastrados' })).toBeVisible()
  await expect(page.getByText('Usuário teste', { exact: true })).toBeVisible()
  await page.getByLabel('Login do usuário', { exact: true }).fill('created@example.test')
  await page.getByLabel('Senha inicial').fill('synthetic-created-password')
  await page.getByRole('button', { name: 'Criar usuário', exact: true }).click()
  await expect(page.getByText('created@example.test', { exact: true })).toBeVisible()
  await expect(page.getByLabel('Senha inicial')).toHaveValue('')
  await page.screenshot({ path: 'test-results/admin-users-desktop.png', fullPage: true })
  await page.getByLabel('Buscar usuário').fill('user@example.test')
  await page.getByRole('button', { name: 'Buscar', exact: true }).click()
  await page.setViewportSize({ width: 390, height: 844 })
  await page
    .getByRole('button', { name: 'Redefinir senha de user@example.test', exact: true })
    .click()
  await page.getByLabel('Nova senha do usuário').fill('synthetic-reset-password')
  await page.getByLabel('Confirme a senha do usuário').fill('synthetic-different-password')
  await page.getByRole('button', { name: 'Salvar nova senha do usuário' }).click()
  await expect(page.getByRole('alert')).toContainText('As senhas não coincidem.')
  expect(resets).toBe(0)
  await page.getByLabel('Confirme a senha do usuário').fill('synthetic-reset-password')
  await page.screenshot({ path: 'test-results/admin-reset-mobile.png', fullPage: true })
  await page.getByRole('button', { name: 'Salvar nova senha do usuário' }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  expect(resets).toBe(1)
  await page
    .getByRole('button', { name: 'Redefinir senha de user@example.test', exact: true })
    .click()
  await expect(page.getByLabel('Nova senha do usuário')).toHaveValue('')
  await page.keyboard.press('Escape')
  await page.getByRole('button', { name: 'Inativar user@example.test', exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Confirmar', exact: true }).click()
  await expect(
    page.getByRole('button', { name: 'Reativar user@example.test', exact: true }),
  ).toBeVisible()
  expect(accounts[1].enabled).toBe(false)
  await page.getByRole('button', { name: 'Reativar user@example.test', exact: true }).click()
  await expect(
    page.getByRole('button', { name: 'Inativar user@example.test', exact: true }),
  ).toBeVisible()
  expect(stateChanges).toBe(2)
  await page.route('**/api/v1/auth/me', (route) =>
    route.fulfill({ json: { ...session.user, role: 'USER' } }),
  )
  await page.goto('/configuracoes')
  await expect(page.getByRole('button', { name: 'Usuários', exact: true })).toHaveCount(0)
})

test('monitoramento mostra falhas no celular e bloqueia usuários comuns', async ({ page }) => {
  await page.clock.install()
  await page.addInitScript(() => {
    const counts = new WeakMap(),
      original = HTMLCanvasElement.prototype.getContext
    window.chartAllocations = (canvas) => counts.get(canvas) || 0
    HTMLCanvasElement.prototype.getContext = function (...args) {
      if (args[0] === '2d') counts.set(this, (counts.get(this) || 0) + 1)
      return original.apply(this, args)
    }
  })
  await fixture(page)
  const event = {
    id: '00000000-0000-4000-8000-000000000001',
    occurred_at: '2026-10-25T19:00:00Z',
    user_id: 'another-user',
    user_name: 'Outro usuário',
    username: 'another@example.test',
    source: 'WEB',
    method: 'POST',
    route: '/api/v1/finance/entries',
    action: 'Registrar lançamento',
    status: 503,
    duration_ms: 1200,
    error_type: 'FinanceException',
    error_reason: 'Serviço indisponível.',
    stack_summary: 'ayla.finance.FinanceService.save(FinanceService.java:42)',
  }
  let monitorCalls = 0,
    filtered = false
  const usageQueries = []
  await page.route('**/api/v1/monitoring**', (route) => {
    monitorCalls++
    const url = new URL(route.request().url())
    let response
    if (url.pathname.endsWith('/users'))
      response = [{ id: 'another-user', name: 'Outro usuário', username: 'another@example.test' }]
    else if (url.pathname.endsWith('/ai-usage/calls')) {
      const separate = url.searchParams.get('userId') === 'another-user'
      response = {
        items: [
          {
            id: 'ai-call-1',
            occurred_at: event.occurred_at,
            user_name: 'Outro usuário',
            model: 'nous/welcome',
            source: 'WEB',
            purpose: 'CHAT',
            status: 200,
            input_tokens: separate ? 300 : 1000,
            output_tokens: separate ? 150 : 350,
            total_tokens: separate ? 450 : 1350,
          },
        ],
        total: 1,
      }
    } else if (url.pathname.endsWith('/ai-usage')) {
      usageQueries.push(url)
      const separate = url.searchParams.get('userId') === 'another-user'
      const totals = {
        calls: 2,
        failures: 1,
        input_tokens: separate ? 300 : 1000,
        output_tokens: separate ? 150 : 350,
        total_tokens: separate ? 450 : 1350,
        unreported_calls: 1,
        incomplete_calls: 1,
      }
      response = {
        totals,
        byUser: [{ user_id: 'another-user', user_name: 'Outro usuário', ...totals }],
        byModel: [{ provider: 'nous', model: 'nous/welcome', ...totals }],
        daily: [{ day: '2026-10-26', ...totals }],
      }
    } else if (url.pathname.endsWith('/events/' + event.id)) response = event
    else if (url.pathname.endsWith('/events')) {
      filtered ||= url.searchParams.get('outcome') === 'ERROR'
      response = { items: [event], total: 1 }
    } else
      response = {
        totals: {
          total: 10,
          errors: 1,
          server_errors: 1,
          active_users: 2,
          average_ms: 350,
          p95_ms: 1200,
        },
        failures: [{ action: event.action, method: event.method, route: event.route, failures: 1 }],
      }
    return route.fulfill({ json: response })
  })
  await login(page)
  await page.getByRole('link', { name: 'Monitoramento', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Monitoramento da aplicação.' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Uso da IA', exact: true })).toBeVisible()
  await expect(page.getByText('1.350', { exact: true }).first()).toBeVisible()
  await expect(page.getByRole('cell', { name: 'nous/welcome' }).first()).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Tokens por chamada' })).toBeVisible()
  const graph = page.getByRole('img', { name: 'Tokens por dia, no horário de Brasília' })
  await expect(graph).toBeVisible()
  const allocations = await graph.evaluate((canvas) => window.chartAllocations(canvas))
  const sameChart = async () =>
    expect(await graph.evaluate((canvas) => window.chartAllocations(canvas))).toBe(allocations)
  await page.getByRole('combobox', { name: 'Usuário da IA' }).selectOption('another-user')
  await expect(page.getByText('450', { exact: true }).first()).toBeVisible()
  await page.getByRole('combobox', { name: 'Período da IA' }).selectOption('2m')
  await expect.poll(() => usageQueries.at(-1)?.searchParams.get('userId')).toBe('another-user')
  expect(
    new Date(usageQueries.at(-1).searchParams.get('to')) -
      new Date(usageQueries.at(-1).searchParams.get('from')),
  ).toBeGreaterThan(50 * 86400000)
  await sameChart()
  const beforeRefresh = usageQueries.length
  await page.clock.fastForward(16000)
  await expect.poll(() => usageQueries.length).toBeGreaterThan(beforeRefresh)
  await expect(page.getByRole('button', { name: 'Atualizar uso', exact: true })).toBeEnabled()
  await sameChart()
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight))
  await page.evaluate(() => window.scrollTo(0, 0))
  await sameChart()
  await page.getByRole('checkbox', { name: 'Atualizar a cada 15 segundos' }).uncheck()
  await page.getByRole('combobox', { name: /^Resultado/ }).selectOption('ERROR')
  await page.getByRole('button', { name: 'Aplicar filtros' }).click()
  await expect.poll(() => filtered).toBe(true)
  await page.screenshot({ path: 'test-results/monitoring-desktop.png', fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.getByRole('button', { name: 'Detalhes', exact: true }).click()
  await expect(
    page.getByRole('dialog').getByText('FinanceException', { exact: true }),
  ).toBeVisible()
  await expect(page.getByRole('dialog').locator('pre')).toContainText('FinanceService.java:42')
  await page.screenshot({ path: 'test-results/monitoring-mobile.png', fullPage: true })
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toHaveCount(0)
  const before = monitorCalls
  const member = { ...session, user: { ...session.user, role: 'USER' } }
  await page.route('**/api/v1/auth/me', (route) => route.fulfill({ json: member.user }))
  await page.goto('/monitoramento')
  await expect(page).toHaveURL(/\/$/)
  await expect(page.getByRole('link', { name: 'Monitoramento', exact: true })).toHaveCount(0)
  expect(monitorCalls).toBe(before)
})

test('grupo pessoal Ayla exige conexão e pode ser preparado pelo painel', async ({ page }) => {
  await fixture(page)
  let connected = false,
    prepared = false,
    calls = 0
  await page.route('**/api/v1/whatsapp/status', (route) =>
    route.fulfill({
      json: {
        connected,
        state: connected ? 'ready' : 'disconnected',
        personalChatId: prepared ? '120363000000000000@g.us' : null,
      },
    }),
  )
  await page.route('**/api/v1/whatsapp/personal-chat', (route) => {
    calls++
    prepared = true
    return route.fulfill({ json: { created: true, pictureUpdated: true, chat: { name: 'Ayla' } } })
  })
  await login(page)
  await page.getByRole('link', { name: 'Conexões', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Começar conversa com Ayla' })).toBeDisabled()
  connected = true
  await page.reload()
  await page.getByRole('button', { name: 'Começar conversa com Ayla' }).click()
  await expect(page.getByRole('button', { name: 'Continuar conversa com Ayla' })).toBeEnabled()
  await expect(
    page.getByText('Pronto! Abra seu WhatsApp e procure o grupo Ayla. Você já pode falar por lá.'),
  ).toBeVisible()
  expect(calls).toBe(1)
  await page.getByRole('link', { name: /^Conversar com Ayla/ }).click()
  await page.setViewportSize({ width: 390, height: 844 })
  await page.getByRole('button', { name: 'Começar uma conversa com Ayla no WhatsApp' }).click()
  await expect(page.getByRole('dialog').getByRole('img', { name: 'Avatar da Ayla' })).toBeVisible()
  await page.getByRole('button', { name: 'Continuar conversa com Ayla' }).click()
  await expect(page.getByRole('status').filter({ hasText: 'Tudo pronto!' })).toBeVisible()
  expect(calls).toBe(2)
})

test('nome do perfil substitui o email e pode ser alterado sem mudar o login', async ({ page }) => {
  const requests = await fixture(page)
  await login(page)
  await page.getByRole('link', { name: 'Configurações', exact: true }).click()
  await page.getByLabel('Seu nome', { exact: true }).fill('Nome escolhido')
  await page.getByRole('button', { name: 'Salvar nome', exact: true }).click()
  await expect(page.getByText('Nome atualizado!', { exact: true })).toBeVisible()
  await page.getByRole('link', { name: 'Ayla', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Oi, Nome escolhido' })).toBeVisible()
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Oi, Nome escolhido' })).toBeVisible()
  expect(requests.find((r) => r.path === '/auth/profile').body).toEqual({
    displayName: 'Nome escolhido',
  })
})

test('painel, CRUD em centavos, lista, chat e configurações usam os endpoints reais', async ({
  page,
}) => {
  const requests = await fixture(page)
  await login(page)
  await expect(page.getByText('R$ 4.859,10')).toBeVisible()
  await expect
    .poll(() =>
      page.locator('.art-image').evaluate((image) => image.complete && image.naturalWidth > 0),
    )
    .toBeTruthy()
  await page.screenshot({ path: 'test-results/dashboard-dark.png', fullPage: true })
  await page.getByRole('button', { name: 'Alternar tema' }).click()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light')
  await page.screenshot({ path: 'test-results/dashboard-light.png', fullPage: true })
  await page.getByRole('button', { name: 'Alternar tema' }).click()
  await page.getByRole('link', { name: 'Financeiro', exact: true }).click()
  await page.getByRole('button', { name: 'Novo lançamento' }).click()
  await page.getByLabel('Valor em reais').fill('0,12')
  await page.getByLabel('Descrição', { exact: true }).fill('Doze centavos de teste')
  await page.getByRole('dialog').getByLabel('Categoria', { exact: true }).fill('Outros')
  await page.getByRole('button', { name: 'Salvar lançamento' }).click()
  await expect(page.getByText('Doze centavos de teste')).toBeVisible()
  expect(
    requests.find((r) => r.path === '/finance/entries' && r.method === 'POST').body.amountCents,
  ).toBe(12)
  await page.getByRole('link', { name: 'Lista de compras', exact: true }).click()
  await page.getByRole('button', { name: 'Adicionar à lista' }).click()
  await page.getByLabel('Produto', { exact: true }).fill('Pão')
  await page.getByRole('button', { name: 'Salvar produto' }).click()
  await expect(page.getByRole('heading', { name: 'Pão', exact: true })).toBeVisible()
  expect(
    requests.find((r) => r.path === '/shopping-list/items' && r.method === 'POST').body,
  ).not.toHaveProperty('quantity')
  await page.getByRole('link', { name: 'Despensa', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Arroz', exact: true })).toBeVisible()
  await page.getByRole('link', { name: 'Memórias', exact: true }).click()
  await expect(page.getByText('O nome dele é Bento.')).toBeVisible()
  await page.getByRole('link', { name: 'Conexões', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Conversas configuradas' })).toBeVisible()
  await page.getByRole('link', { name: 'Configurações', exact: true }).click()
  await expect(page.getByRole('status').filter({ hasText: 'Modelo em uso' })).toContainText(
    'modelo-gratuito',
  )
  await page.getByRole('link', { name: 'Comandos', exact: true }).click()
  await expect(page.getByText('configurar Ayla0101', { exact: true })).toBeVisible()
  await expect(page.getByText('Ayla0101 <pedido>', { exact: true })).toBeVisible()
  await page.getByRole('link', { name: 'Investimentos', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Caixinha Nubank' })).toBeVisible()
  await expect(page.getByText('R$ 10.005,00').first()).toBeVisible()
  await page.getByRole('button', { name: 'Simular' }).click()
  await expect(page.getByText('R$ 10.300,00')).toBeVisible()
  expect(requests.find((r) => r.path === '/investments/simulate').body.months).toBe(12)
  await page.getByRole('link', { name: 'Financeiro', exact: true }).click()
  await page.getByRole('tab', { name: 'Categorias' }).click()
  await expect(page.getByRole('heading', { name: 'Gastos por categoria' })).toBeVisible()
  await page.getByRole('tab', { name: 'Tendências' }).click()
  await expect(page.getByRole('heading', { name: 'Receitas, despesas e tendência' })).toBeVisible()
  await page.getByRole('link', { name: 'Configurações', exact: true }).click()
  await page.getByRole('button', { name: 'Voz da Ayla' }).click()
  await expect(page.getByRole('combobox', { name: 'Voz', exact: true })).toHaveValue(
    'pt-BR-FranciscaNeural',
  )
  await page
    .getByRole('slider')
    .first()
    .evaluate((input) => {
      input.value = '1.10'
      input.dispatchEvent(new Event('input', { bubbles: true }))
    })
  await page.getByRole('button', { name: 'Ouvir sem salvar' }).click()
  await expect(page.locator('audio.voice-preview')).toBeVisible()
  const firstPreview = await page.locator('audio.voice-preview').getAttribute('src')
  await page.getByRole('combobox', { name: 'Voz', exact: true }).selectOption('pt-BR-AntonioNeural')
  await expect(page.locator('audio.voice-preview')).toHaveCount(0)
  await page
    .getByRole('slider')
    .first()
    .evaluate((input) => {
      input.value = '1.25'
      input.dispatchEvent(new Event('input', { bubbles: true }))
    })
  await page
    .getByRole('slider')
    .nth(1)
    .evaluate((input) => {
      input.value = '12'
      input.dispatchEvent(new Event('input', { bubbles: true }))
    })
  await page.getByRole('button', { name: 'Ouvir sem salvar' }).click()
  await expect(page.locator('audio.voice-preview')).toBeVisible()
  expect(await page.locator('audio.voice-preview').getAttribute('src')).not.toBe(firstPreview)
  expect(requests.filter((r) => r.path === '/me/agent/voice/preview').at(-1).body).toEqual({
    voice: 'pt-BR-AntonioNeural',
    speed: 1.25,
    pitchHz: 12,
  })
  await expect(page.getByText('Prévia: Antônio · 1.25× · +12 Hz')).toBeVisible()
  expect(requests.find((r) => r.path === '/me/agent/voice/preview').body.speed).toBe(1.1)
  expect(requests.filter((r) => r.path === '/me/agent/voice' && r.method === 'PUT')).toHaveLength(0)
  await page.getByRole('link', { name: /^Conversar com Ayla/ }).click()
  await expect
    .poll(() =>
      page
        .locator('.chat-welcome-star')
        .evaluate((image) => image.complete && image.naturalWidth > 0),
    )
    .toBeTruthy()
  await page.screenshot({ path: 'test-results/chat-welcome-desktop.png', fullPage: true })
  await page.getByLabel('Sua mensagem para a Ayla').fill('Olá!')
  await page.getByRole('button', { name: 'Enviar mensagem' }).click()
  await expect(page.getByText('Tudo organizado.')).toBeVisible()
  expect(requests.find((r) => r.path === '/chat').body.conversationId).toBe('conversation-1')
  await page.screenshot({ path: 'test-results/chat-dark.png', fullPage: true })
})
test('histórico salvo reaparece após recarregar e fica separado por conversa', async ({ page }) => {
  const requests = await fixture(page)
  requests.conversationList.push({
    conversationId: 'conversation-2',
    title: 'Trabalho',
    current: false,
  })
  requests.historyPages['conversation-1:0'] = {
    items: [
      { id: 'm1', role: 'user', text: 'Uma pergunta guardada no servidor', files: [] },
      { id: 'm2', role: 'assistant', text: 'Uma resposta guardada no servidor', files: [] },
    ],
    hasMore: true,
    nextOffset: 100,
  }
  requests.historyPages['conversation-1:100'] = {
    items: [
      { id: 'old', role: 'user', text: 'Uma mensagem anterior', files: [] },
      { id: 'm1', role: 'user', text: 'Uma pergunta guardada no servidor', files: [] },
    ],
    hasMore: false,
    nextOffset: 103,
  }
  requests.historyPages['conversation-2:0'] = {
    items: [{ id: 'work', role: 'user', text: 'Assunto exclusivo de trabalho', files: [] }],
    hasMore: false,
    nextOffset: 1,
  }
  await login(page)
  await page.getByRole('link', { name: /^Conversar com Ayla/ }).click()
  await expect(page.getByText('Uma resposta guardada no servidor')).toBeVisible()
  await page.reload()
  await expect(page.getByText('Uma resposta guardada no servidor')).toBeVisible()
  await page.getByRole('button', { name: 'Carregar mensagens anteriores' }).click()
  await expect(page.getByText('Uma mensagem anterior')).toBeVisible()
  await expect(page.getByText('Uma pergunta guardada no servidor')).toHaveCount(1)
  await page.getByRole('button', { name: 'Trabalho', exact: true }).click()
  await expect(page.getByText('Assunto exclusivo de trabalho')).toBeVisible()
  await expect(page.getByText('Uma resposta guardada no servidor')).toHaveCount(0)
  await page.reload()
  await expect(page.getByText('Assunto exclusivo de trabalho')).toBeVisible()
  expect(
    requests.some(
      (request) =>
        request.path === '/conversations/conversation-1/messages' && request.method === 'GET',
    ),
  ).toBeTruthy()
})

test('celular, recuperação de senha e navegação sem transbordamento', async ({ page }) => {
  test.setTimeout(90000)
  await page.setViewportSize({ width: 390, height: 844 })
  const requests = await fixture(page)
  await page.goto('/login')
  await page.getByRole('button', { name: 'Esqueci minha senha' }).click()
  await page.getByLabel('Seu login').fill('pedro')
  await page.getByRole('button', { name: 'Enviar nova senha' }).click()
  await expect(page.getByRole('status')).toContainText('Se houver uma conta')
  expect(requests.find((r) => r.path === '/auth/forgot-password').body).toEqual({
    username: 'pedro',
  })
  await page.getByRole('button', { name: 'Voltar para o login' }).click()
  await login(page)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy()
  await page.screenshot({ path: 'test-results/dashboard-mobile.png', fullPage: true })
  await page.getByRole('button', { name: 'Abrir menu' }).click()
  await page.getByRole('link', { name: /^Conversar com Ayla/ }).click()
  await expect(page.getByLabel('Sua mensagem para a Ayla')).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy()
  async function navigate(name) {
    const link = page.getByRole('link', { name, exact: typeof name === 'string' })
    const menuButton = page.getByRole('button', { name: 'Abrir menu' })
    if ((await menuButton.getAttribute('aria-expanded')) !== 'true') await menuButton.click()
    await link.click()
    await expect(menuButton).toHaveAttribute('aria-expanded', 'false')
  }
  for (const viewport of [
    { width: 320, height: 640 },
    { width: 768, height: 1024 },
    { width: 844, height: 390 },
  ]) {
    await page.setViewportSize(viewport)
    for (const name of [
      'Visão geral',
      'Financeiro',
      'Investimentos',
      'Despensa',
      'Lista de compras',
      'Memórias',
      'Comandos',
      'WhatsApp',
      'Configurações',
    ]) {
      await navigate(name)
      expect(
        await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
        `${name} em ${viewport.width}px`,
      ).toBeTruthy()
      if (name === 'Financeiro') {
        await expect(page.locator('.responsive-table td[data-label="Valor"]').first()).toBeVisible()
        expect(
          await page
            .getByRole('button', { name: 'Editar lançamento' })
            .first()
            .evaluate((el) => el.getBoundingClientRect().height),
        ).toBeGreaterThanOrEqual(44)
        await page.getByRole('button', { name: 'Novo lançamento' }).click()
        const dialog = page.getByRole('dialog')
        await expect(dialog).toBeVisible()
        expect(await dialog.evaluate((el) => el.scrollWidth <= el.clientWidth + 1)).toBeTruthy()
        await page.getByRole('button', { name: 'Cancelar', exact: true }).click()
        if (viewport.width === 320)
          await page.screenshot({ path: 'test-results/finance-mobile.png', fullPage: true })
      }
      if (name === 'Configurações') {
        await page.getByRole('button', { name: 'Voz da Ayla' }).click()
        await expect(page.getByRole('button', { name: 'Ouvir sem salvar' })).toBeVisible()
        expect(
          await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
        ).toBeTruthy()
        if (viewport.width === 320)
          await page.screenshot({ path: 'test-results/voice-mobile.png', fullPage: true })
      }
    }
    await navigate(/^Conversar com Ayla/)
    await expect(page.getByLabel('Sua mensagem para a Ayla')).toBeVisible()
    const sendBounds = await page.getByRole('button', { name: 'Enviar mensagem' }).boundingBox()
    expect(sendBounds.y + sendBounds.height).toBeLessThanOrEqual(viewport.height)
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
    ).toBeTruthy()
  }
  // A reduced viewport exercises the same resize path as a mobile keyboard.
  await page.setViewportSize({ width: 390, height: 420 })
  await page.getByLabel('Sua mensagem para a Ayla').focus()
  const keyboardBounds = await page.getByRole('button', { name: 'Enviar mensagem' }).boundingBox()
  expect(keyboardBounds.y + keyboardBounds.height).toBeLessThanOrEqual(420)
  await page.screenshot({ path: 'test-results/chat-mobile-keyboard.png', fullPage: true })
  await page.getByRole('button', { name: 'Abrir menu' }).click()
  await expect(page.getByRole('button', { name: 'Fechar menu' })).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('button', { name: 'Abrir menu' })).toBeFocused()
  await page.setViewportSize({ width: 1280, height: 360 })
  const sidebar = page.locator('.sidebar')
  const sidebarScroll = await sidebar.evaluate((el) => {
    el.scrollTop = el.scrollHeight
    const bounds = el.getBoundingClientRect()
    const profile = el.querySelector('.profile').getBoundingClientRect()
    return {
      overflows: el.scrollHeight > el.clientHeight,
      scrolled: el.scrollTop > 0,
      profileVisible: profile.bottom <= bounds.bottom,
    }
  })
  expect(sidebarScroll).toEqual({ overflows: true, scrolled: true, profileVisible: true })
})
