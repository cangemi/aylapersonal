import { createRouter, createWebHistory } from 'vue-router'
import { auth, restore } from './lib/api'
import { createChunkRecovery, isChunkLoadError } from './lib/chunkRecovery'
import { toast } from './lib/ui'
const routes = [
  {
    path: '/compras/historico',
    component: () => import('./pages/ShoppingHistory.vue'),
    meta: { title: 'Compras e preços', subtitle: 'Seu histórico, sem perder os detalhes.' },
  },
  {
    path: '/agenda',
    component: () => import('./pages/Calendar.vue'),
    meta: { title: 'Agenda', subtitle: 'Um lembrete para cada plano.' },
  },
  {
    path: '/login',
    component: () => import('./pages/Login.vue'),
    meta: { public: true, title: 'Bem-vindo' },
  },
  {
    path: '/configuracao-inicial',
    component: () => import('./pages/Onboarding.vue'),
    meta: { onboarding: true, title: 'Configuração inicial' },
  },
  {
    path: '/',
    component: () => import('./pages/Dashboard.vue'),
    meta: { title: 'Visão geral', subtitle: 'Um pouco de tudo, do seu jeito.' },
  },
  {
    path: '/chat',
    component: () => import('./pages/Chat.vue'),
    meta: { title: 'Converse com a Ayla', subtitle: 'Sua companhia para organizar o dia.' },
  },
  {
    path: '/investimentos',
    component: () => import('./pages/Investments.vue'),
    meta: { title: 'Investimentos', subtitle: 'Sua caixinha rendendo com o CDI.' },
  },
  {
    path: '/financeiro',
    component: () => import('./pages/Finance.vue'),
    meta: { title: 'Financeiro', subtitle: 'Clareza para cuidar do seu dinheiro.' },
  },
  {
    path: '/despensa',
    component: () => import('./pages/Household.vue'),
    meta: { title: 'Despensa', subtitle: 'Tudo o que você tem em casa.', kind: 'pantry' },
  },
  {
    path: '/compras',
    component: () => import('./pages/Household.vue'),
    meta: { title: 'Lista de compras', subtitle: 'Para não esquecer de nada.', kind: 'shopping' },
  },
  {
    path: '/memoria',
    component: () => import('./pages/Memory.vue'),
    meta: { title: 'Memórias', subtitle: 'As pequenas coisas que a Ayla lembra sobre você.' },
  },
  {
    path: '/comandos',
    component: () => import('./pages/Commands.vue'),
    meta: { title: 'Comandos', subtitle: 'Como pedir as coisas para a Ayla.' },
  },
  {
    path: '/whatsapp',
    redirect: (to) => ({
      path: '/conexoes',
      query: { ...to.query, aba: 'whatsapp' },
      hash: to.hash,
    }),
  },
  {
    path: '/conexoes',
    component: () => import('./pages/Connections.vue'),
    meta: {
      title: 'Conexões',
      subtitle: 'WhatsApp e Hermes, conectados ao seu espaço.',
    },
  },
  {
    path: '/configuracoes',
    component: () => import('./pages/Settings.vue'),
    meta: { title: 'Configurações', subtitle: 'Deixe este espaço com a sua cara.' },
  },
  {
    path: '/monitoramento',
    component: () => import('./pages/Monitoring.vue'),
    meta: { title: 'Monitoramento', owner: true },
  },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]
const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})
router.beforeEach(async (to) => {
  if (!auth.checked) await restore()
  if (!to.meta.public && !auth.session) return { path: '/login', query: { redirect: to.fullPath } }
  if (to.path === '/login' && auth.session) return '/'
  const pending = auth.session?.user?.onboardingRequired
  if (pending && !to.meta.onboarding && !to.meta.public) return '/configuracao-inicial'
  if (!pending && to.meta.onboarding) return '/'
  if (
    to.path === '/' &&
    window.matchMedia('(max-width: 900px)').matches &&
    to.query.visao !== 'geral'
  )
    return '/chat'
  if (to.meta.owner && auth.session?.user?.role !== 'OWNER') return '/'
  document.title = `${to.meta.title || 'Seu espaço'} · Ayla`
})
const recoverChunk = createChunkRecovery({
  storage: {
    getItem: (key) => sessionStorage.getItem(key),
    setItem: (key, value) => sessionStorage.setItem(key, value),
  },
  navigate: (path) => window.location.replace(path),
})
router.onError((error, to) => {
  if (!isChunkLoadError(error)) {
    console.error('Falha ao abrir a tela.', error)
    return
  }
  if (recoverChunk(error, to?.fullPath || window.location.pathname)) return
  const message = 'Não foi possível carregar esta tela. Atualize a página e verifique sua conexão.'
  error.message = message
  toast(message, 'error')
})
export default router
