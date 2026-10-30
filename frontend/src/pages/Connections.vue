<script setup>
import { computed, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Icon from '../components/Icon.vue'
import Whatsapp from './Whatsapp.vue'
import HermesConnection from '../components/HermesConnection.vue'

const route = useRoute(),
  router = useRouter()
const tabs = [
  { id: 'whatsapp', label: 'WhatsApp', icon: 'phone' },
  { id: 'hermes', label: 'Hermes', icon: 'sparkles' },
]
const tab = computed(() => (route.query.aba === 'hermes' ? 'hermes' : 'whatsapp'))
async function selectTab(id) {
  await router.replace({ query: { ...route.query, aba: id } })
}
async function tabKeyboard(event, index) {
  const destination =
    event.key === 'ArrowRight'
      ? (index + 1) % tabs.length
      : event.key === 'ArrowLeft'
        ? (index + tabs.length - 1) % tabs.length
        : event.key === 'Home'
          ? 0
          : event.key === 'End'
            ? tabs.length - 1
            : null
  if (destination === null) return
  event.preventDefault()
  await selectTab(tabs[destination].id)
  await nextTick()
  document.getElementById('connection-tab-' + tabs[destination].id)?.focus()
}
</script>
<template>
  <div class="page-heading">
    <div>
      <span class="eyebrow">TUDO NO SEU LUGAR</span>
      <h1>Conexões</h1>
      <p>Conecte seu WhatsApp e gerencie a conta que dá vida à sua Ayla.</p>
    </div>
    <span class="stat-icon lavender"><Icon name="globe" :size="24" /></span>
  </div>
  <div class="tabs connection-tabs" role="tablist" aria-label="Conexões da Ayla">
    <button
      v-for="(item, index) in tabs"
      :id="'connection-tab-' + item.id"
      :key="item.id"
      type="button"
      role="tab"
      :class="{ active: tab === item.id }"
      :aria-selected="tab === item.id"
      :aria-controls="'connection-panel-' + item.id"
      :tabindex="tab === item.id ? 0 : -1"
      @click="selectTab(item.id)"
      @keydown="tabKeyboard($event, index)"
    >
      <Icon :name="item.icon" :size="18" />{{ item.label }}
    </button>
  </div>
  <div
    :id="'connection-panel-' + tab"
    role="tabpanel"
    :aria-labelledby="'connection-tab-' + tab"
    class="connection-content"
  >
    <KeepAlive>
      <Whatsapp v-if="tab === 'whatsapp'" embedded />
      <HermesConnection v-else management />
    </KeepAlive>
  </div>
</template>
<style scoped>
.connection-tabs button {
  display: inline-flex;
  align-items: center;
  gap: 9px;
}
.connection-tabs button:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 4px;
}
.connection-content {
  min-width: 0;
}
</style>
