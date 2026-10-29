<script setup>
import { ref, onMounted } from 'vue'
import { api, query } from '../lib/api'
import { date } from '../lib/format'
import { confirmAction, toast } from '../lib/ui'
import Icon from '../components/Icon.vue'
import EmptyState from '../components/EmptyState.vue'
const memories = ref([]),
  q = ref(''),
  error = ref(''),
  loading = ref(false)
const kinds = {
  PROFILE: 'Sobre você',
  PREFERENCE: 'Preferências',
  PET: 'Seus animais',
  POSSESSION: 'Suas coisas',
  EXPERIENCE: 'Experiências',
}
async function load() {
  loading.value = true
  error.value = ''
  try {
    memories.value = await api('/memories?' + query({ q: q.value || undefined }))
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}
async function remove(memory) {
  if (!(await confirmAction('Apagar esta lembrança?', memory.content))) return
  try {
    await api('/memories/' + memory.id, { method: 'DELETE' })
    toast('Lembrança apagada.')
    await load()
  } catch (e) {
    toast(e.message, 'error')
  }
}
onMounted(load)
</script>
<template>
  <div class="page-heading">
    <div>
      <span class="eyebrow">CADA DETALHE FAZ DIFERENÇA</span>
      <h1>Um pouco sobre você.</h1>
      <p>O que a Ayla guarda para tornar as conversas mais suas.</p>
    </div>
    <span class="stat-icon lavender"><Icon name="memory" :size="24" /></span>
  </div>
  <div class="tip-strip">
    <Icon name="shield" /><span
      >Você tem controle. Apague qualquer lembrança que não quiser manter. Novas memórias são
      criadas naturalmente nas conversas.</span
    >
  </div>
  <form class="search-box memory-search" @submit.prevent="load">
    <Icon name="search" :size="18" /><input
      v-model="q"
      placeholder="Buscar uma lembrança…"
      aria-label="Buscar lembranças"
    /><button class="text-link" :disabled="loading">Buscar</button>
  </form>
  <div v-if="error" class="banner error" role="alert">{{ error }}</div>
  <div v-if="loading" class="loading-line"><Icon name="loading" />Buscando lembranças…</div>
  <div v-else-if="memories.length" class="memory-grid">
    <article v-for="memory in memories" :key="memory.id" class="memory-card">
      <div class="item-top">
        <span class="tag lavender">{{ kinds[memory.kind] || memory.kind }}</span
        ><button class="icon-button" aria-label="Apagar lembrança" @click="remove(memory)">
          <Icon name="delete" :size="16" />
        </button>
      </div>
      <h3>{{ memory.subject }}</h3>
      <p>{{ memory.content }}</p>
      <div class="memory-meta">
        <span>{{ memory.source === 'IMAGE' ? 'A partir de uma imagem' : 'Da nossa conversa' }}</span
        ><span>{{ date(memory.updatedAt) }}</span>
      </div>
    </article>
  </div>
  <EmptyState
    v-else
    icon="memory"
    :title="q ? 'Nenhuma lembrança encontrada' : 'A gente ainda tem muito para conversar.'"
    description="Preferências, histórias e detalhes importantes vão aparecer por aqui."
    ><RouterLink class="button primary" to="/chat"
      >Conversar com a Ayla<Icon name="arrow" :size="16" /></RouterLink
  ></EmptyState>
</template>
