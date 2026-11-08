<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import {
  serverClock,
  remainingSeconds,
  effectiveStatus,
  providerLabels,
  duration,
  localTime,
} from '../lib/providerUsage'
const props = defineProps({
  availability: { type: Object, required: true },
  retryable: Boolean,
  busy: Boolean,
  conversation: { type: Boolean, default: true },
})
defineEmits(['retry'])
const tick = ref(0)
let clock = () => null,
  timer
watch(
  () => props.availability,
  (value) => {
    clock = serverClock(value.serverNow)
    tick.value++
  },
  { immediate: true },
)
const remaining = computed(() => {
  void tick.value
  return remainingSeconds(props.availability.availableAt, clock())
})
const status = computed(() => effectiveStatus(props.availability.status, remaining.value))
onMounted(() => {
  timer = setInterval(() => tick.value++, 1000)
})
onBeforeUnmount(() => clearInterval(timer))
</script>
<template>
  <aside class="provider-notice" role="status">
    <strong>◷ {{ providerLabels[status] }}</strong>
    <span v-if="availability.model" class="model">{{ availability.model }}</span>
    <span>Liberação estimada: {{ localTime(availability.availableAt) }}</span>
    <span v-if="remaining !== null"
      >Tempo restante: <b>{{ duration(remaining) }}</b></span
    >
    <span v-else>O provedor não informou o prazo para liberação.</span>
    <span v-if="availability.retryAttemptAt">Próxima tentativa informada: {{ localTime(availability.retryAttemptAt) }}</span>
    <p v-if="conversation && !retryable">
      O pedido não teve sua conclusão confirmada. O Hermes pode continuar processando; confira o
      resultado antes de repetir alterações.
    </p>
    <p v-else-if="conversation">
      Este pedido foi bloqueado antes do envio à IA. Uma nova tentativa usa o mesmo pedido salvo.
    </p>
    <RouterLink to="/conexoes?aba=hermes">Ver em Conexões → Hermes</RouterLink>
    <button
      v-if="retryable"
      type="button"
      :disabled="busy || remaining > 0"
      @click="$emit('retry')"
    >
      Tentar novamente
    </button>
  </aside>
</template>
<style scoped>
.provider-notice {
  display: grid;
  gap: 8px;
  padding: 14px;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--surface);
  font-size: 0.9rem;
  line-height: 1.5;
  min-width: 0;
  overflow-wrap: anywhere;
}
.provider-notice p {
  margin: 0;
}
.model {
  font-family: monospace;
}
.provider-notice button {
  justify-self: start;
}
</style>
