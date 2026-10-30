<script setup>
import { ref, watch, nextTick, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { auth, api, setSession } from './lib/api'
import { notices, confirmation, settleConfirm, toast } from './lib/ui'
import Icon from './components/Icon.vue'
import AylaLogo from './components/AylaLogo.vue'
import Modal from './components/Modal.vue'
import InstallApp from './components/InstallApp.vue'
import CalendarAlerts from './components/CalendarAlerts.vue'
const route = useRoute(),
  router = useRouter(),
  menu = ref(false)
const mobileQuery = window.matchMedia('(max-width: 900px)')
const mobile = ref(mobileQuery.matches)
const navigation = ref(null)
const menuButton = ref(null)
const previousOverflow = document.body.style.overflow
function resizeMenu(event) {
  mobile.value = event.matches
  if (!event.matches) menu.value = false
}
function closeMenu() {
  menu.value = false
  nextTick(() => menuButton.value?.focus())
}
function menuKeyboard(event) {
  if (!menu.value || !mobile.value) return
  if (event.key === 'Escape') {
    event.preventDefault()
    closeMenu()
    return
  }
  if (event.key !== 'Tab') return
  const targets = [...navigation.value.querySelectorAll('a[href],button:not(:disabled)')].filter(
    (el) => el.getClientRects().length,
  )
  const first = targets[0],
    last = targets.at(-1)
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last?.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first?.focus()
  }
}
mobileQuery.addEventListener('change', resizeMenu)
window.addEventListener('keydown', menuKeyboard)
watch([menu, mobile], async ([open, small]) => {
  document.body.style.overflow = open && small ? 'hidden' : previousOverflow
  if (open && small) {
    await nextTick()
    navigation.value?.querySelector('.sidebar-close')?.focus()
  }
})
onBeforeUnmount(() => {
  mobileQuery.removeEventListener('change', resizeMenu)
  window.removeEventListener('keydown', menuKeyboard)
  document.body.style.overflow = previousOverflow
})
const theme = ref(localStorage.getItem('ayla.theme') || 'dark')
watch(
  theme,
  (value) => {
    document.documentElement.dataset.theme = value
    localStorage.setItem('ayla.theme', value)
  },
  { immediate: true },
)
watch(
  () => route.path,
  () => {
    menu.value = false
  },
)
watch(
  () => auth.session,
  (value) => {
    if (!value && !route.meta.public) router.replace('/login')
  },
)
const nav = [
  ['/', 'dashboard', 'Visão geral'],
  ['/chat', 'chat', 'Conversar com Ayla'],
  ['/agenda', 'calendar', 'Agenda'],
  ['/financeiro', 'wallet', 'Financeiro'],
  ['/investimentos', 'trend', 'Investimentos'],
  ['/despensa', 'pantry', 'Despensa'],
  ['/compras', 'shopping', 'Lista de compras'],
  ['/memoria', 'memory', 'Memórias'],
  ['/comandos', 'help', 'Comandos'],
]
async function logout() {
  try {
    await api('/auth/logout', { method: 'POST' })
  } catch {
    toast('A sessão local foi encerrada.', 'info')
  } finally {
    setSession(null)
    router.replace('/login')
  }
}
</script>
<template>
  <div v-if="route.meta.public || route.meta.onboarding" class="public-shell">
    <button
      class="theme-float icon-button"
      aria-label="Alternar tema"
      @click="theme = theme === 'dark' ? 'light' : 'dark'"
    >
      <Icon :name="theme === 'dark' ? 'sun' : 'moon'" /></button
    ><RouterView />
  </div>
  <div v-else class="app-shell">
    <div v-if="menu" class="sidebar-backdrop" aria-hidden="true" @click="closeMenu" />
    <aside
      id="main-navigation"
      ref="navigation"
      :inert="mobile && !menu"
      :class="['sidebar', { open: menu }]"
    >
      <button class="icon-button sidebar-close" aria-label="Fechar menu" @click="closeMenu">
        <Icon name="close" />
      </button>
      <div class="brand brand-animated">
        <AylaLogo compact />
        <small>seu espaço pessoal</small>
      </div>
      <p class="nav-label">SEU DIA A DIA</p>
      <nav aria-label="Navegação principal">
        <RouterLink
          v-for="[path, icon, label] in nav"
          :key="path"
          :to="path === '/' ? { path: '/', query: { visao: 'geral' } } : path"
          :class="['nav-link', { active: route.path === path }]"
          ><Icon :name="icon" /><span>{{ label }}</span
          ><span v-if="path === '/chat'" class="tiny-star">✦</span></RouterLink
        >
      </nav>
      <p class="nav-label nav-secondary">PREFERÊNCIAS</p>
      <nav aria-label="Configurações">
        <RouterLink
          v-if="auth.session?.user?.role === 'OWNER'"
          to="/monitoramento"
          class="nav-link"
          active-class="active"
          ><Icon name="monitor" />Monitoramento</RouterLink
        >
        <RouterLink to="/conexoes" class="nav-link" active-class="active"
          ><Icon name="globe" />Conexões</RouterLink
        ><RouterLink to="/configuracoes" class="nav-link" active-class="active"
          ><Icon name="settings" />Configurações</RouterLink
        >
      </nav>
      <div class="sidebar-bottom">
        <div class="ayla-note">
          <span>✦</span>
          <p>Um dia mais leve<br /><strong>começa por aqui.</strong></p>
        </div>
        <div class="profile">
          <span class="avatar">{{ auth.session?.user?.username?.slice(0, 1).toUpperCase() }}</span>
          <div>
            <strong>{{ auth.session?.user?.displayName || 'Minha conta' }}</strong
            ><small>{{
              auth.session?.user?.role === 'OWNER' ? 'Meu espaço' : 'Minha conta'
            }}</small>
          </div>
          <button class="icon-button" aria-label="Sair" title="Sair" @click="logout">
            <Icon name="logout" :size="18" />
          </button>
        </div>
      </div>
    </aside>
    <main class="main" :inert="mobile && menu">
      <header class="topbar">
        <button
          ref="menuButton"
          class="icon-button mobile-menu"
          aria-label="Abrir menu"
          aria-controls="main-navigation"
          :aria-expanded="menu"
          @click="menu = !menu"
        >
          <Icon name="menu" /></button
        ><span class="breadcrumb"
          >Meu espaço <Icon name="right" :size="14" /><strong>{{ route.meta.title }}</strong></span
        >
        <div class="topbar-right">
          <CalendarAlerts />
          <InstallApp v-if="mobile" />
          <span class="private-label"><Icon name="shield" :size="14" />Seu espaço, privado</span
          ><button
            class="icon-button"
            aria-label="Alternar tema"
            @click="theme = theme === 'dark' ? 'light' : 'dark'"
          >
            <Icon :name="theme === 'dark' ? 'sun' : 'moon'" :size="18" />
          </button>
        </div>
      </header>
      <div class="page" :class="{ 'chat-page': route.path === '/chat' }"><RouterView /></div>
      <footer class="page-footer">
        <span>Feito para o seu dia a dia.</span><span>Ayla <span class="accent">✦</span></span>
      </footer>
      <nav class="mobile-dock" aria-label="Atalhos do aplicativo" :inert="mobile && menu">
        <RouterLink
          to="/chat"
          :class="{ active: route.path === '/chat' }"
          :aria-current="route.path === '/chat' ? 'page' : undefined"
          ><Icon name="chat" /><span>Conversa</span></RouterLink
        >
        <RouterLink
          to="/agenda"
          :class="{ active: route.path === '/agenda' }"
          :aria-current="route.path === '/agenda' ? 'page' : undefined"
          ><Icon name="calendar" /><span>Agenda</span></RouterLink
        >
        <RouterLink
          to="/financeiro"
          :class="{ active: route.path === '/financeiro' }"
          :aria-current="route.path === '/financeiro' ? 'page' : undefined"
          ><Icon name="wallet" /><span>Finanças</span></RouterLink
        >
        <RouterLink
          to="/compras"
          :class="{ active: route.path === '/compras' }"
          :aria-current="route.path === '/compras' ? 'page' : undefined"
          ><Icon name="shopping" /><span>Compras</span></RouterLink
        >
        <button
          :class="{ active: menu }"
          aria-controls="main-navigation"
          :aria-expanded="menu"
          @click="menu = !menu"
        >
          <Icon name="menu" /><span>Mais</span>
        </button>
      </nav>
    </main>
  </div>
  <div class="toast-stack" aria-live="polite">
    <div v-for="notice in notices" :key="notice.id" :class="['toast', notice.type]">
      <Icon :name="notice.type === 'error' ? 'error' : 'check'" :size="18" /><span>{{
        notice.message
      }}</span
      ><button
        class="icon-button"
        aria-label="Dispensar aviso"
        @click="notices.splice(notices.indexOf(notice), 1)"
      >
        <Icon name="close" :size="15" />
      </button>
    </div>
  </div>
  <Modal v-if="confirmation" :title="confirmation.title" @close="settleConfirm(false)"
    ><p class="muted">{{ confirmation.description }}</p>
    <div class="form-actions">
      <button class="button secondary" @click="settleConfirm(false)">Cancelar</button
      ><button class="button danger" @click="settleConfirm(true)">Confirmar</button>
    </div></Modal
  >
</template>
