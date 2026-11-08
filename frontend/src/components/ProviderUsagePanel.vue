<script setup>
import { ref, computed, onMounted, onBeforeUnmount, onActivated, onDeactivated } from 'vue'
import { api } from '../lib/api'
import ProviderLimitNotice from './ProviderLimitNotice.vue'
import ExternalLink from './ExternalLink.vue'
import {
  serverClock,
  remainingSeconds,
  effectiveStatus,
  providerLabels,
  localTime,
  realPercentage,
} from '../lib/providerUsage'
const data = ref(null),
  error = ref(''),
  loading = ref(false),
  tick = ref(0)
let clock = () => null,
  timer,
  poll,
  active = false,
  disposed = false
const state = computed(() => {
  void tick.value
  return effectiveStatus(
    data.value?.status,
    remainingSeconds(data.value?.availability?.availableAt, clock()),
  )
})
const quotas = computed(() =>
  data.value?.quotaStatus === 'KNOWN'
    ? (data.value.quotas || []).filter((q) => realPercentage(q.usagePercentage) !== null)
    : [],
)
const quotaMessage = computed(
  () =>
    ({
      ERROR: 'Não foi possível consultar o consumo na Nous.',
      LOGIN_REQUIRED:
        'Conecte sua conta Nous para consultar o percentual utilizado e o que ainda está disponível.',
      STALE: 'A informação de consumo está desatualizada. Aguardando nova consulta oficial.',
      UNAVAILABLE:
        'Consumo indisponível na integração atual. O provedor não informou uma cota verificável.',
    })[data.value?.quotaStatus],
)
async function refresh() {
  if (loading.value || !active) return
  loading.value = true
  const sent = performance.now()
  try {
    const result = await api('/me/hermes/usage')
    if (disposed || !active) return
    clock = serverClock(result.serverNow, sent, performance.now(), result.serverProcessingMillis)
    data.value = result
    error.value = ''
  } catch {
    if (!disposed)
      error.value =
        'Falha ao atualizar a disponibilidade. A informação anterior pode estar desatualizada.'
  } finally {
    loading.value = false
  }
}
function start() {
  if (active || disposed) return
  active = true
  refresh()
  timer = setInterval(() => tick.value++, 1000)
  poll = setInterval(refresh, 30000)
}
function stop() {
  active = false
  clearInterval(timer)
  clearInterval(poll)
}
onMounted(start)
onActivated(start)
onDeactivated(stop)
onBeforeUnmount(() => {
  disposed = true
  stop()
})
</script>
<template>
  <section
    class="provider-usage"
    :data-state="state"
    aria-label="Consumo e disponibilidade do Hermes"
  >
    <div class="usage-title">
      <h3>Consumo e disponibilidade</h3>
      <button type="button" :disabled="loading" @click="refresh">
        {{ loading ? 'Atualizando…' : 'Atualizar' }}
      </button>
    </div>
    <p v-if="error" role="alert">{{ error }}</p>
    <template v-if="data">
      <strong role="status"
        >{{ state === 'AVAILABLE' ? '✓' : state === 'ERROR' ? '⚠' : '◷' }}
        {{ providerLabels[state] }}</strong
      >
      <p>
        Provedor: Nous · Modelo: <b>{{ data.model || 'Automático — ainda não observado' }}</b>
      </p>
      <p>Hermes: {{ data.connected ? 'Conectado' : 'Indisponível' }}. {{ data.message }}</p>
      <ProviderLimitNotice
        v-if="
          data.availability &&
          ['RATE_LIMITED', 'WAITING_PROVIDER', 'PENDING_CONFIRMATION'].includes(state)
        "
        :availability="data.availability"
        :conversation="false"
      />
      <p v-if="data.sharedCredentials">
        Esta conta Nous é compartilhada entre perfis. Sua cota e os limites do mesmo modelo também
        são compartilhados.
      </p>
      <p v-if="quotaMessage">{{ quotaMessage }}</p>
      <p v-if="data.quotaQueryMessage" role="alert">{{ data.quotaQueryMessage }}</p>
      <h4 v-if="quotas.length">Cotas oficiais da conta Nous conectada</h4>
      <p v-if="quotas.length" class="muted">
        Cada barra mostra o uso e o restante de uma cota do provedor. Cotas de períodos ou modelos
        diferentes não são somadas.
      </p>
      <div v-for="(quota, index) in quotas" :key="index" class="quota">
        <div>
          <b>{{ quota.label }}</b
          ><span class="quota-percent"
            >{{ quota.usagePercentage.toLocaleString('pt-BR', { maximumFractionDigits: 2 }) }}%
            utilizado</span
          >
        </div>
        <progress
          :value="quota.usagePercentage"
          max="100"
          :aria-label="quota.label + ': percentual utilizado'"
        />
        <span
          >{{
            (100 - quota.usagePercentage).toLocaleString('pt-BR', { maximumFractionDigits: 2 })
          }}% restante ·
          {{
            quota.scope === 'ACCOUNT'
              ? 'Conta Nous'
              : quota.scope === 'MODEL_FAMILY'
                ? 'Família de modelos'
                : quota.model || 'Modelo'
          }}</span
        >
        <span v-if="quota.used != null && quota.limit != null"
          >{{ quota.used }} / {{ quota.limit }} · Restante:
          {{ quota.remaining ?? Math.max(0, quota.limit - quota.used) }}</span
        >
        <span>Renovação: {{ localTime(quota.resetsAt) }}</span>
      </div>
      <ExternalLink class="button secondary" href="https://portal.nousresearch.com/billing"
        >Ver consumo na conta Nous</ExternalLink
      >
      <p class="muted">
        Última consulta de consumo: {{ localTime(data.quotaUpdatedAt) }}. Fonte:
        {{ data.quotaSource }}.
      </p>
      <details v-if="data.models?.length">
        <summary>Disponibilidade por modelo</summary>
        <div v-for="model in data.models" :key="model.model" class="model-row">
          <b>{{ model.model }}</b
          ><span>{{
            providerLabels[
              effectiveStatus(model.status, remainingSeconds(model.availableAt, clock()))
            ]
          }}</span
          ><span
            >Limite detectado: {{ localTime(model.detectedAt) }} · Último sucesso:
            {{ localTime(model.confirmedAt) }}</span
          ><span v-if="model.alternates?.length"
            >Alternativas informadas: {{ model.alternates.join(', ') }}. A elegibilidade gratuita
            precisa ser confirmada na seleção de modelo.</span
          >
        </div>
      </details>
      <p class="muted">
          No modo Automático, novas mensagens podem usar outro modelo gratuito quando o anterior
          estiver limitado. Um pedido já enviado não é repetido automaticamente. O fim do prazo
          permite uma nova tentativa; a disponibilidade depende da confirmação do provedor.
      </p>
      <details>
        <summary>Uso registrado pela Ayla nas últimas 24 horas</summary>
        <p>
          Chamadas: {{ data.recordedUsage?.calls ?? '—' }} · Tokens informados:
          {{ data.recordedUsage?.total_tokens ?? 'Não informado' }} · Chamadas sem tokens
          informados: {{ data.recordedUsage?.unreported_calls ?? '—' }}.
        </p>
        <p>{{ data.recordedUsagePeriod }}</p>
      </details>
    </template>
    <p v-else-if="loading">Consultando monitoramento…</p>
  </section>
</template>
<style scoped>
.provider-usage {
  display: grid;
  gap: 12px;
  min-width: 0;
  border: 1px solid var(--border);
  border-radius: 16px;
  padding: 20px;
  overflow-wrap: anywhere;
}
.quota-percent {
  font-size: 1.3rem;
  font-weight: 700;
}
.provider-usage p,
.provider-usage h3 {
  margin: 0;
}
.usage-title,
.quota > div {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  align-items: center;
}
.quota,
.model-row {
  display: grid;
  gap: 6px;
  padding: 12px 0;
  border-bottom: 1px solid var(--border);
}
progress {
  width: 100%;
  height: 14px;
  accent-color: var(--accent);
}
.quota > span,
.model-row > span {
  font-size: 0.9rem;
}
summary {
  cursor: pointer;
  font-weight: 600;
}
@media (max-width: 600px) {
  .provider-usage {
    padding: 14px;
  }
}
</style>
