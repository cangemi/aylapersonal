<script setup>
import { timestamp } from '../lib/format'

defineProps({ item: { type: Object, required: true } })
const emit = defineEmits(['details', 'journey'])
const sources = {
  WEB: 'Site',
  WHATSAPP: 'WhatsApp',
  ANONYMOUS: 'Não autenticado',
}
const number = (value) => Number(value || 0).toLocaleString('pt-BR')
const tokens = (value) => (value == null ? '—' : number(value))
const result = (status) =>
  status < 400 ? 'Sucesso' : status >= 500 ? 'Falha' : 'Não concluída'
</script>

<template>
  <li :class="['monitor-row', { 'monitor-row-error': item.status >= 400 }]">
    <div class="monitor-row-head">
      <span :class="['tag', item.kind === 'ia' ? 'lavender' : '']">{{
        item.kind === 'ia' ? 'IA' : 'API'
      }}</span>
      <span class="monitor-row-time">{{ timestamp(item.occurred_at) }}</span>
      <span class="monitor-row-source">{{ sources[item.source] || item.source }}</span>
    </div>
    <div class="monitor-row-body">
      <strong>{{ item.action }}</strong>
      <code v-if="item.kind === 'ia'">{{
        `${item.model || 'Modelo não informado'} · ${item.purpose === 'VISION' ? 'Imagem' : 'Conversa'}`
      }}</code>
      <code v-else class="monitor-route">{{ item.method }} {{ item.route }}</code>
      <small v-if="item.error_type" class="monitor-error-text">{{ item.error_type }}</small>
      <small v-else-if="item.error_reason" class="muted">{{ item.error_reason }}</small>
    </div>
    <div class="monitor-row-side">
      <span :class="['tag', item.status < 400 ? 'green' : 'red']"
        >{{ result(item.status) }} · {{ item.status }}</span
      >
      <span class="monitor-row-metric">
        <template v-if="item.kind === 'ia'"
          >{{ tokens(item.input_tokens) }} → {{ tokens(item.output_tokens) }} ({{
            tokens(item.total_tokens)
          }})</template
        >
        <template v-else>{{
          item.method === 'EVENT' ? 'Não medido' : number(item.duration_ms) + ' ms'
        }}</template>
      </span>
      <div class="monitor-row-actions">
        <button
          v-if="item.request_key && (item.kind === 'ia' || item.route === '/api/v1/chat')"
          class="button secondary"
          @click="emit('journey', item.request_key)"
        >
          Jornada
        </button>
        <button v-if="item.kind === 'api'" class="button secondary" @click="emit('details', item)">
          Detalhes
        </button>
      </div>
    </div>
  </li>
</template>
