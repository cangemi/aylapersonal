<script setup>
import { ref, watch, onBeforeUnmount } from 'vue'
import { auth, api } from '../lib/api'
import Icon from './Icon.vue'
const count = ref(0)
let timer,
  revision = 0,
  alive = true
async function load() {
  const user = auth.session?.user?.id,
    current = ++revision
  if (!user) return
  try {
    const result = await api('/calendar/reminders')
    if (alive && current === revision && user === auth.session?.user?.id)
      count.value = result.length
  } catch {
    /* Keep the counter when offline. */
  }
}
watch(
  () => auth.session?.user?.id,
  () => {
    count.value = 0
    clearInterval(timer)
    load()
    timer = setInterval(() => {
      if (!document.hidden) load()
    }, 30000)
  },
  { immediate: true },
)
onBeforeUnmount(() => {
  alive = false
  revision++
  clearInterval(timer)
})
</script>
<template>
  <RouterLink
    to="/agenda"
    class="icon-button calendar-alert-button"
    :aria-label="count ? `${count} lembretes na agenda` : 'Abrir agenda e lembretes'"
    title="Agenda e lembretes"
    ><Icon name="bell" :size="19" /><span v-if="count" class="calendar-alert-count">{{
      count > 9 ? '9+' : count
    }}</span></RouterLink
  >
</template>
