<script setup>
import { ref, computed, onMounted, onBeforeUnmount, onActivated, onDeactivated } from 'vue'
import { api } from '../lib/api'
import ExternalLink from './ExternalLink.vue'
import {
  serverClock, remainingSeconds, effectiveStatus, providerLabels, localTime,
  realPercentage, duration,
} from '../lib/providerUsage'

const data = ref(null), error = ref(''), loading = ref(false), tick = ref(0)
const period = ref('24H')
const periods = [
  { value: 'DAY', label: 'Hoje' }, { value: '24H', label: 'Últimas 24 horas' },
  { value: '7D', label: 'Últimos 7 dias' }, { value: 'MONTH', label: 'Mês atual' },
]
let clock = () => null, timer, poll, active = false, disposed = false
const number = (value) => Number(value).toLocaleString('pt-BR')
const tokens = (value) => value == null ? 'Não informado' : number(value)
const state = computed(() => {
  void tick.value
  return effectiveStatus(data.value?.status, remainingSeconds(data.value?.availability?.availableAt, clock()))
})
const models = computed(() => {
  void tick.value
  return (data.value?.models || []).map((model) => {
    const remaining = remainingSeconds(model.availableAt, clock())
    return { ...model, remaining, currentStatus: effectiveStatus(model.status, remaining) }
  }).sort((a, b) => Number(b.currentStatus === 'RATE_LIMITED') - Number(a.currentStatus === 'RATE_LIMITED'))
})
const limited = computed(() => models.value.filter((model) => model.currentStatus === 'RATE_LIMITED'))
const quotas = computed(() => data.value?.quotaStatus === 'KNOWN'
  ? (data.value.quotas || []).filter((q) => realPercentage(q.usagePercentage) !== null) : [])
const missingCalls = computed(() => Number(data.value?.recordedUsage?.unreported_calls || 0))
const officialWarning = computed(() => quotas.value.some((q) => q.usagePercentage >= 100)
  ? 'Uma cota oficial atingiu 100%. Confira abaixo a qual período ou modelo ela se aplica.'
  : quotas.value.some((q) => q.usagePercentage >= 80)
    ? 'Atenção: uma cota oficial já está em 80% ou mais do limite.' : '')
const quotaMessage = computed(() => ({
  ERROR: 'Não foi possível consultar o consumo na Nous.',
  LOGIN_REQUIRED: 'Conecte sua conta Nous para consultar as informações oficiais de consumo.',
  STALE: 'A consulta oficial está desatualizada. Use Atualizar para consultar novamente.',
  UNAVAILABLE: 'A Nous não informou o teto de tokens desta conta na API. Não é possível calcular quanto falta ou uma porcentagem de uso.',
})[data.value?.quotaStatus])
async function refresh() {
  if (loading.value || !active) return
  loading.value = true
  const sent = performance.now()
  try {
    const result = await api('/me/hermes/usage?period=' + period.value)
    if (disposed || !active) return
    clock = serverClock(result.serverNow, sent, performance.now(), result.serverProcessingMillis)
    data.value = result
    error.value = ''
  } catch {
    if (!disposed) error.value = 'Falha ao atualizar. Os dados anteriores podem estar desatualizados.'
  } finally { loading.value = false }
}
function start() {
  if (active || disposed) return
  active = true
  refresh()
  timer = setInterval(() => tick.value++, 1000)
  poll = setInterval(refresh, 30000)
}
function stop() { active = false; clearInterval(timer); clearInterval(poll) }
onMounted(start)
onActivated(start)
onDeactivated(stop)
onBeforeUnmount(() => { disposed = true; stop() })
</script>
<template>
  <section class="provider-usage" :data-state="state" aria-label="Consumo e disponibilidade do Hermes">
    <div class="usage-title">
      <div><h3>Seu consumo e limites</h3><p class="muted">Nous · atualização automática a cada 30 segundos</p></div>
      <button type="button" class="button secondary" :disabled="loading" @click="refresh">
        {{ loading ? 'Atualizando…' : 'Atualizar' }}
      </button>
    </div>
    <p v-if="error" class="usage-alert" role="alert">{{ error }}</p>
    <template v-if="data">
      <div class="connection-summary">
        <strong role="status">{{ providerLabels[state] }}</strong>
        <span>Modelo atual: <b>{{ data.model || 'Automático — ainda não observado' }}</b></span>
        <small>{{ data.connected ? 'Hermes conectado' : 'Hermes indisponível' }} · estado baseado na última resposta observada</small>
      </div>
      <p v-if="limited.length" class="usage-alert warning" role="status">
        <strong>{{ limited.length === 1 ? '1 modelo atingiu um limite.' : limited.length + ' modelos atingiram um limite.' }}</strong>
        {{ state === 'AVAILABLE' ? 'O modelo atual respondeu normalmente. Confira os prazos de cada modelo abaixo.' : 'Confira abaixo o prazo informado para o modelo afetado.' }}
      </p>
      <p v-if="data.sharedCredentials" class="usage-alert warning">
        Esta conta Nous está conectada a mais de um perfil. Os limites dessa conta podem ser compartilhados.
      </p>

      <section class="usage-section" aria-label="Tokens registrados pela Ayla">
        <div class="usage-title">
          <h4>Tokens registrados pela Ayla</h4>
          <label class="period-selector">Período
            <select v-model="period" :disabled="loading" @change="refresh">
              <option v-for="option in periods" :key="option.value" :value="option.value">{{ option.label }}</option>
            </select>
          </label>
        </div>
        <div class="token-grid">
          <article><span>{{ missingCalls ? 'Total parcial' : 'Total informado' }}</span><strong>{{ tokens(data.recordedUsage?.total_tokens) }}</strong><small>tokens</small></article>
          <article><span>Entrada</span><strong>{{ tokens(data.recordedUsage?.input_tokens) }}</strong><small>instruções, histórico e pedido</small></article>
          <article><span>Saída</span><strong>{{ tokens(data.recordedUsage?.output_tokens) }}</strong><small>geração do modelo</small></article>
        </div>
        <p>{{ number(data.recordedUsage?.calls || 0) }} chamadas ao Hermes · {{ number(data.recordedUsage?.failures || 0) }} não concluídas</p>
        <p v-if="missingCalls" class="usage-alert warning" role="status">
          <strong>Contagem incompleta:</strong> {{ number(missingCalls) }} chamadas sem total de tokens informado.
          O total exibido inclui apenas os valores recebidos e pode ser menor que o consumo real.
        </p>
        <p class="muted">{{ data.recordedUsagePeriod }}</p>
        <small v-if="data.usageWindow">De {{ localTime(data.usageWindow.from) }} até {{ localTime(data.usageWindow.to) }}.</small>
        <div v-if="data.recordedUsageByModel?.length" class="model-consumption">
          <h4>Consumo por modelo</h4>
          <div v-for="row in data.recordedUsageByModel" :key="row.model || 'unknown'" class="consumption-row">
            <div><b>{{ row.model || 'Modelo não confirmado' }}</b><strong>{{ tokens(row.total_tokens) }} tokens</strong></div>
            <small>Entrada: {{ tokens(row.input_tokens) }} · Saída: {{ tokens(row.output_tokens) }} · {{ number(row.calls) }} chamadas</small>
            <small v-if="row.unreported_calls > 0" class="warning-text">Total parcial · {{ number(row.unreported_calls) }} chamadas sem contagem</small>
          </div>
        </div>
      </section>

      <section class="usage-section" aria-label="Cotas oficiais da Nous">
        <h4>Limites oficiais da conta</h4>
        <p v-if="quotaMessage">{{ quotaMessage }}</p>
        <p v-if="data.quotaQueryMessage" role="alert">{{ data.quotaQueryMessage }}</p>
        <p v-if="officialWarning" class="usage-alert warning" role="status">{{ officialWarning }}</p>
        <p v-if="quotas.length" class="muted">Cada cota tem seu próprio período e escopo. Elas não são somadas.</p>
        <div v-for="(quota, index) in quotas" :key="index" class="quota">
          <div><b>{{ quota.label }}</b><strong>{{ quota.usagePercentage.toLocaleString('pt-BR', { maximumFractionDigits: 2 }) }}% utilizado</strong></div>
          <progress :value="quota.usagePercentage" max="100" :aria-label="quota.label + ': percentual utilizado'" />
          <span>{{ (100 - quota.usagePercentage).toLocaleString('pt-BR', { maximumFractionDigits: 2 }) }}% restante · {{ quota.scope === 'ACCOUNT' ? 'Conta Nous' : quota.scope === 'MODEL_FAMILY' ? 'Família de modelos' : quota.model || 'Modelo' }}</span>
          <span v-if="quota.used != null && quota.limit != null">{{ number(quota.used) }} / {{ number(quota.limit) }} · Restante: {{ number(quota.remaining ?? Math.max(0, quota.limit - quota.used)) }}</span>
          <span>Renovação: {{ localTime(quota.resetsAt) }}</span>
        </div>
        <p v-if="!quotas.length" class="muted">Um limite de modelo pode ser dinâmico. Quando a Nous recusa uma chamada por limite, a Ayla registra o modelo e a previsão de liberação que ela informar.</p>
        <small>Última consulta oficial: {{ localTime(data.quotaUpdatedAt) }}. Os tokens registrados pela Ayla não determinam o saldo desta cota.</small>
        <ExternalLink class="button secondary" href="https://portal.nousresearch.com/billing">Ver consumo e plano na Nous</ExternalLink>
      </section>

      <section v-if="models.length" class="usage-section" aria-label="Limites por modelo">
        <h4>Limites por modelo</h4>
        <div v-for="model in models" :key="model.model" :class="['model-row', { limited: model.currentStatus === 'RATE_LIMITED' }]">
          <div class="usage-title"><b>{{ model.model }}</b><span :class="['tag', model.currentStatus === 'AVAILABLE' ? 'green' : '']">{{ providerLabels[model.currentStatus] }}</span></div>
          <template v-if="['RATE_LIMITED', 'WAITING_PROVIDER', 'PENDING_CONFIRMATION'].includes(model.currentStatus)">
            <span>Limite detectado: {{ localTime(model.detectedAt) }}</span>
            <strong v-if="model.remaining !== null">{{ duration(model.remaining) }}</strong>
            <span v-if="model.availableAt">Liberação estimada: {{ localTime(model.availableAt) }}</span>
            <span v-else>O provedor não informou o prazo para liberação.</span>
            <small>O prazo é uma previsão da Nous. Seu término permite tentar novamente; uma resposta bem-sucedida confirma a liberação.</small>
          </template>
          <span v-if="model.confirmedAt">Último sucesso: {{ localTime(model.confirmedAt) }}</span>
          <span v-else-if="model.currentStatus === 'AVAILABLE'">Sem horário de sucesso informado.</span>
        </div>
        <p class="muted">No modo Automático, novas mensagens priorizam um modelo gratuito que já respondeu bem. Pedidos que já começaram não são repetidos automaticamente.</p>
      </section>
    </template>
    <p v-else-if="loading">Consultando consumo e limites…</p>
  </section>
</template>
<style scoped>
.provider-usage { display: grid; gap: 20px; min-width: 0; border: 1px solid var(--border); border-radius: 16px; padding: 20px; overflow-wrap: anywhere; }
.provider-usage p, .provider-usage h3, .provider-usage h4 { margin: 0; }
.usage-title, .quota > div, .consumption-row > div { display: flex; justify-content: space-between; gap: 10px; flex-wrap: wrap; align-items: center; }
.usage-title > div { display: grid; gap: 5px; }
.connection-summary { display: grid; gap: 7px; padding: 14px; border-radius: 12px; background: var(--surface); border: 1px solid var(--border); }
.usage-section { display: grid; gap: 12px; min-width: 0; padding-top: 18px; border-top: 1px solid var(--border); }
.period-selector { display: flex; gap: 8px; align-items: center; font-size: .9rem; }
.period-selector select { max-width: 100%; padding: 8px; border: 1px solid var(--border); border-radius: 8px; background: var(--surface); color: inherit; }
.token-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; }
.token-grid article { display: grid; gap: 6px; min-width: 0; padding: 12px; border: 1px solid var(--border); border-radius: 10px; }
.token-grid strong { font-size: clamp(1.1rem, 2vw, 1.5rem); }
small, .muted { color: var(--muted); }
.usage-alert { padding: 12px; border-radius: 10px; border: 1px solid var(--border); line-height: 1.6; }
.warning, .limited { border-color: #b68b37; background: rgba(182, 139, 55, .08); }
.warning-text { color: #b68b37; }
.model-consumption { display: grid; gap: 8px; min-width: 0; }
.quota, .model-row, .consumption-row { display: grid; gap: 7px; min-width: 0; padding: 12px 0; border-bottom: 1px solid var(--border); }
.model-row { padding: 12px; border: 1px solid var(--border); border-radius: 10px; }
.model-row b, .consumption-row b { overflow-wrap: anywhere; }
progress { width: 100%; height: 14px; accent-color: var(--accent); }
@media (max-width: 600px) {
  .provider-usage { padding: 14px; }
  .token-grid { grid-template-columns: minmax(0, 1fr); }
  .token-grid article { grid-template-columns: minmax(0, 1fr) auto; align-items: center; }
  .token-grid article small { grid-column: 1 / -1; }
}
</style>
