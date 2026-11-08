<template>
  <div class="notifications-center" ref="root">
    <button
      class="icon-button notification-bell"
      type="button"
      aria-label="Notificações e alertas"
      title="Notificações e alertas"
      @click="toggle"
    >
      <Icon name="bell" :size="19" />
      <span v-if="count" class="notification-count">{{ count > 9 ? '9+' : count }}</span>
    </button>
    <div v-if="open" class="notification-dropdown">
      <div class="notification-dropdown-head">
        <strong>Notificações</strong>
        <RouterLink to="/alarmes" class="text-link" @click="open = false">Alarmes</RouterLink>
      </div>
      <div v-if="loading" class="notification-loading"><Icon name="loading" />Carregando…</div>
      <div v-else-if="!items.length" class="notification-empty">Nenhum alerta por enquanto.</div>
      <ul v-else class="notification-items">
        <li v-for="item in items" :key="item.kind + item.id" class="notification-item">
          <Icon :name="item.kind === 'alarm' ? 'bell' : 'calendar'" :size="16" />
          <div class="notification-copy">
            <strong>{{ item.title }}</strong>
            <span class="muted">{{ item.when }}</span>
          </div>
          <button
            v-if="item.readId"
            class="text-link"
            type="button"
            @click="read(item)"
          >Ler</button>
          <RouterLink :to="item.href" class="text-link" @click="openItem(item)">Abrir</RouterLink>
        </li>
      </ul>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { auth, api } from '../lib/api'
import Icon from './Icon.vue'
import { date } from '../lib/format'

const root = ref(null),
  open = ref(false),
  loading = ref(false),
  reminders = ref([]),
  alarms = ref([])



function formatNoticeWhen(value) {
  if (!value) return ''
  try {
    return new Intl.DateTimeFormat('pt-BR', {
      dateStyle: 'short',
      timeStyle: 'short',
    }).format(new Date(value))
  } catch {
    return value
  }
}

const items = computed(() => {
  const result = []
  for (const item of alarms.value) {
    result.push({
      id: item.id,
      kind: 'alarm',
      title: item.title,
      when: formatNoticeWhen(item.dueAt),
      href: '/alarmes',
      readId: item.id,
      readPath: `/alarms/notifications/${item.id}/read`,
    })
  }
  for (const item of reminders.value) {
    result.push({
      id: item.id,
      kind: 'calendar',
      title: item.title,
      when: formatNoticeWhen(item.dueAt || item.startsAt),
      href: '/agenda',
      readId: item.id,
      readPath: `/calendar/reminders/${item.id}/read`,
    })
  }
  return result
})

const count = computed(() => items.value.length)

async function load() {
  const user = auth.session?.user?.id
  if (!user) return
  loading.value = true
  try {
    const [calendarItems, alarmItems] = await Promise.all([
      api('/calendar/reminders').catch(() => []),
      api('/alarms/notifications?unread=false').catch(() => []),
    ])
    reminders.value = Array.isArray(calendarItems) ? calendarItems : []
    alarms.value = Array.isArray(alarmItems) ? alarmItems : []
  } catch {
    /* Keep previous counters when offline. */
  } finally {
    loading.value = false
  }
}

function toggle() {
  open.value = !open.value
  if (open.value) load()
}

async function read(item) {
  try {
    await api(item.readPath, { method: 'POST' })
    await load()
  } catch {
    /* Ignore read failures; the item remains visible. */
  }
}
async function openItem(item) {
  open.value = false
  if (item.readPath) {
    try {
      await api(item.readPath, { method: 'POST' })
    } catch {
      /* Ignore read failures. */
    }
  }
  if (item.id) {
    alarms.value = alarms.value.filter((alarm) => alarm.id !== item.id)
    reminders.value = reminders.value.filter((reminder) => reminder.id !== item.id)
  }
}

function clickOutside(event) {
  if (open.value && root.value && !root.value.contains(event.target)) open.value = false
}

watch(() => auth.session?.user?.id, () => {
  reminders.value = []
  alarms.value = []
  load()
}, { immediate: true })

let timer
onMounted(() => {
  if (typeof Notification !== 'undefined' && Notification.permission === 'default')
    Notification.requestPermission().catch(() => {})
  load()
  timer = setInterval(() => {
    if (!document.hidden) load()
  }, 15000)
  document.addEventListener('click', clickOutside)
})
onBeforeUnmount(() => {
  clearInterval(timer)
  document.removeEventListener('click', clickOutside)
})
</script>

<style scoped>
.notifications-center { position: relative; }
.notification-bell { position: relative; }
.notification-count {
  position: absolute;
  top: -6px;
  right: -6px;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 999px;
  background: #ef4444;
  color: white;
  font-size: 11px;
  display: grid;
  place-items: center;
}
.notification-dropdown {
  position: absolute;
  right: 0;
  top: calc(100% + .5rem);
  width: min(360px, calc(100vw - 1rem));
  max-width: calc(100vw - 1rem);
  max-height: 60vh;
  overflow: auto;
  z-index: 50;
  padding: .75rem;
  border-radius: 16px;
  background: var(--surface, rgba(20, 20, 24, .96));
  border: 1px solid var(--border, rgba(255, 255, 255, .1));
  box-shadow: 0 18px 48px rgba(0, 0, 0, .35);
}
@media (max-width: 900px) {
  .notification-dropdown {
    position: fixed;
    top: calc(62px + .5rem);
    right: .5rem;
    left: .5rem;
    width: auto;
    max-width: none;
  }
}
.notification-dropdown-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: .5rem;
}
.notification-items {
  list-style: none;
  padding: 0;
  margin: 0;
  display: grid;
  gap: .5rem;
}
.notification-item {
  display: flex;
  gap: .6rem;
  align-items: center;
  flex-wrap: wrap;
  padding: .5rem;
  border-radius: 10px;
  background: var(--surface-2, rgba(255, 255, 255, .04));
}
.notification-copy {
  flex: 1;
  min-width: 120px;
  display: grid;
}
.notification-loading,
.notification-empty { padding: .75rem 0; text-align: center; }
</style>
