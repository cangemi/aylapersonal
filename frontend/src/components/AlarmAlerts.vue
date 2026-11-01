<template>
  <RouterLink to="/alarmes" class="icon-button" :aria-label="count ? `${count} alarmes pendentes` : 'Abrir alarmes'"
    ><Icon name="bell" :size="19" /><span v-if="count" class="calendar-alert-count">{{ count > 9 ? '9+' : count }}</span></RouterLink>
</template>
<script setup>
import { ref, watch, onBeforeUnmount } from 'vue'
import { auth, api } from '../lib/api'
import Icon from './Icon.vue'
const count = ref(0)
let timer, revision = 0, alive = true, seen = new Set()
async function load() {
  const user = auth.session?.user?.id, current = ++revision
  if (!user) return
  try {
    const result = await api('/alarms/notifications')
    const items = Array.isArray(result) ? result : []
    if (alive && current === revision && user === auth.session?.user?.id) {
      count.value = items.length
      if (Notification.permission === 'granted') {
        for (const item of items) {
          if (!seen.has(item.id)) {
            seen.add(item.id)
            new Notification(item.title || 'Alarme', { body: item.dueAt })
          }
        }
      }
    }
  } catch {}
}
watch(() => auth.session?.user?.id, () => {
  count.value = 0; seen.clear(); clearInterval(timer); load()
  timer = setInterval(() => { if (!document.hidden) load() }, 15000)
}, { immediate: true })
onBeforeUnmount(() => { alive = false; revision++; clearInterval(timer) })
if (typeof Notification !== 'undefined' && Notification.permission === 'default') Notification.requestPermission().catch(() => {})
</script>
<style scoped>
.icon-button { position: relative; }
.calendar-alert-count { position: absolute; top: -6px; right: -6px; min-width: 18px; height: 18px; padding: 0 5px; border-radius: 999px; background: #ef4444; color: white; font-size: 11px; display: grid; place-items: center; }
</style>
