<script setup>
import { ref, watch, onBeforeUnmount } from 'vue'
import { api, query } from '../lib/api'
import BaseChart from './BaseChart.vue'
import { timestamp } from '../lib/format'

const props = defineProps({
  filters: { type: Object, required: true },
  refreshAt: String,
  users: { type: Array, default: () => [] },
})
const usage = ref(null),
  error = ref(''),
  loading = ref(false)
const selectedUser = ref(props.filters.userId || ''),
  period = ref('filters'),
  page = ref(0),
  calls = ref({ items: [], total: 0 })
const number = (value) => Number(value || 0).toLocaleString('pt-BR')
const tokens = (value) => (value == null ? 'Não informado' : number(value))
let generation = 0
function parameters() {
  const params = { ...props.filters, userId: selectedUser.value }
  if (period.value !== 'filters') {
    const end = new Date(),
      start = new Date(end)
    if (period.value === '2m') {
      const day = start.getUTCDate()
      start.setUTCDate(1)
      start.setUTCMonth(start.getUTCMonth() - 2)
      const lastDay = new Date(
        Date.UTC(start.getUTCFullYear(), start.getUTCMonth() + 1, 0),
      ).getUTCDate()
      start.setUTCDate(Math.min(day, lastDay))
    } else start.setUTCDate(start.getUTCDate() - Number(period.value))
    params.from = start.toISOString()
    params.to = end.toISOString()
  }
  return { from: params.from, to: params.to, userId: params.userId, source: params.source }
}
async function load() {
  const current = ++generation
  loading.value = true
  error.value = ''
  try {
    const params = parameters()
    const [result, entries] = await Promise.all([
      api('/monitoring/ai-usage?' + query(params)),
      api('/monitoring/ai-usage/calls?' + query({ ...params, page: page.value, size: 25 })),
    ])
    if (generation === current) {
      usage.value = result
      calls.value = entries
    }
  } catch (failure) {
    if (generation === current) {
      error.value =
        'Não foi possível atualizar. Os dados exibidos são da última consulta concluída. ' +
        failure.message
    }
  } finally {
    if (generation === current) loading.value = false
  }
}
watch(() => props.refreshAt, load, { immediate: true })
watch(
  () => props.filters.userId,
  (value) => {
    selectedUser.value = value || ''
    page.value = 0
  },
)
function changeFilters() {
  page.value = 0
  load()
}
function changePage(direction) {
  page.value += direction
  load()
}
onBeforeUnmount(() => {
  generation++
})
function chartConfig(theme) {
  return {
    type: 'line',
    data: {
      labels: usage.value.daily.map(
        (row) => String(row.day).slice(8, 10) + '/' + String(row.day).slice(5, 7),
      ),
      datasets: [
        {
          label: 'Entrada',
          data: usage.value.daily.map((row) => Number(row.input_tokens)),
          backgroundColor: '#b7a0ef',
          borderColor: '#b7a0ef',
          borderWidth: 2,
          pointRadius: 3,
          tension: 0.15,
        },
        {
          label: 'Saída',
          data: usage.value.daily.map((row) => Number(row.output_tokens)),
          backgroundColor: '#81c6ac',
          borderColor: '#81c6ac',
          borderWidth: 2,
          pointRadius: 3,
          tension: 0.15,
        },
        {
          label: 'Total informado',
          data: usage.value.daily.map((row) => Number(row.total_tokens)),
          borderColor: '#e0a46f',
          backgroundColor: '#e0a46f',
          borderWidth: 2,
          borderDash: [5, 4],
          pointStyle: 'rectRot',
          pointRadius: 4,
          tension: 0.15,
        },
      ],
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      interaction: { mode: 'index', intersect: false },
      plugins: {
        legend: { labels: { color: theme.text, usePointStyle: true, boxWidth: 10 } },
        tooltip: {
          callbacks: { label: (item) => `${item.dataset.label}: ${number(item.raw)} tokens` },
        },
      },
      scales: {
        x: { ticks: { color: theme.text, maxTicksLimit: 10 }, grid: { color: theme.grid } },
        y: {
          beginAtZero: true,
          title: { display: true, text: 'Tokens', color: theme.text },
          ticks: { color: theme.text, precision: 0, callback: number },
          grid: { color: theme.grid },
        },
      },
    },
  }
}
</script>

<template>
  <section class="panel monitor-events" :aria-busy="loading">
    <div class="panel-heading">
      <div>
        <h2>Uso da IA</h2>
        <p>Tokens informados pelo Hermes, guardados por dois meses no banco.</p>
      </div>
      <button class="button secondary" :disabled="loading" @click="load">Atualizar uso</button>
    </div>
    <div class="ai-usage-filters">
      <label
        >Usuário da IA<select v-model="selectedUser" @change="changeFilters">
          <option value="">Todos os usuários</option>
          <option v-for="user in users" :key="user.id" :value="user.id">
            {{ user.name }} · {{ user.username }}
          </option>
        </select></label
      >
      <label
        >Período da IA<select v-model="period" @change="changeFilters">
          <option value="filters">Período dos filtros acima</option>
          <option value="7">Últimos 7 dias</option>
          <option value="30">Últimos 30 dias</option>
          <option value="2m">Últimos 2 meses</option>
        </select></label
      >
    </div>
    <div v-if="error" class="banner error" role="alert">{{ error }}</div>
    <template v-if="usage">
      <div class="monitor-metrics">
        <article>
          <span>Total de tokens informados</span
          ><strong>{{ number(usage.totals.total_tokens) }}</strong
          ><small>Somente contadores disponíveis</small>
        </article>
        <article>
          <span>Tokens de entrada</span><strong>{{ number(usage.totals.input_tokens) }}</strong
          ><small>Instruções, contexto e mensagens enviados</small>
        </article>
        <article>
          <span>Tokens de saída</span><strong>{{ number(usage.totals.output_tokens) }}</strong
          ><small>Geração informada pelo modelo</small>
        </article>
        <article>
          <span>Chamadas ao Hermes</span><strong>{{ number(usage.totals.calls) }}</strong
          ><small
            >{{ number(usage.totals.failures) }} não concluídas ·
            {{ number(usage.totals.unreported_calls) }} sem total informado</small
          >
        </article>
      </div>
      <p v-if="!Number(usage.totals.calls)" class="muted">
        Nenhuma chamada registrada neste período. Os próximos usos aparecerão aqui.
      </p>
      <p v-if="Number(usage.totals.incomplete_calls)" class="muted">
        {{ number(usage.totals.incomplete_calls) }} chamadas sem detalhamento completo de entrada e
        saída.
      </p>
      <BaseChart
        v-if="usage.daily.length"
        :build="chartConfig"
        :watch-source="usage.daily"
        label="Tokens por dia, no horário de Brasília"
      />
      <h3>Tokens por chamada</h3>
      <p class="muted">
        Cada linha representa uma chamada, incluindo análises de imagens e correções. O total acima
        considera todas as páginas.
      </p>
      <div class="table-wrap" v-if="calls.items.length">
        <table class="responsive-table">
          <thead>
            <tr>
              <th>Quando / usuário</th>
              <th>Modelo / origem</th>
              <th>Resultado</th>
              <th>Entrada</th>
              <th>Saída</th>
              <th>Total</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="call in calls.items" :key="call.id">
              <td data-label="Usuário">
                {{ call.user_name
                }}<small class="block muted">{{ timestamp(call.occurred_at) }}</small>
              </td>
              <td data-label="Modelo">
                {{ call.model || 'Não informado'
                }}<small class="block muted"
                  >{{ call.source === 'WHATSAPP' ? 'WhatsApp' : 'Site' }} ·
                  {{ call.purpose === 'VISION' ? 'Imagem' : 'Conversa' }}</small
                >
              </td>
              <td data-label="Resultado">
                {{ call.status < 400 ? 'Concluída' : 'Não concluída' }}
              </td>
              <td data-label="Entrada">{{ tokens(call.input_tokens) }}</td>
              <td data-label="Saída">{{ tokens(call.output_tokens) }}</td>
              <td data-label="Total">{{ tokens(call.total_tokens) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else class="muted">Nenhuma chamada nesta página.</p>
      <div class="pagination">
        <span>{{ number(calls.total) }} chamadas · página {{ page + 1 }}</span>
        <div class="button-row">
          <button
            class="button secondary"
            :disabled="page === 0 || loading"
            @click="changePage(-1)"
          >
            Chamadas anteriores
          </button>
          <button
            class="button secondary"
            :disabled="(page + 1) * 25 >= calls.total || loading"
            @click="changePage(1)"
          >
            Próximas chamadas
          </button>
        </div>
      </div>
      <h3>Por usuário</h3>
      <div class="table-wrap" v-if="usage.byUser.length">
        <table class="responsive-table">
          <thead>
            <tr>
              <th>Usuário</th>
              <th>Chamadas</th>
              <th>Entrada</th>
              <th>Saída</th>
              <th>Total informado</th>
              <th>Sem total</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in usage.byUser" :key="row.user_id || 'unknown'">
              <td data-label="Usuário">{{ row.user_name }}</td>
              <td data-label="Chamadas">{{ number(row.calls) }}</td>
              <td data-label="Entrada">{{ number(row.input_tokens) }}</td>
              <td data-label="Saída">{{ number(row.output_tokens) }}</td>
              <td data-label="Total informado">{{ number(row.total_tokens) }}</td>
              <td data-label="Sem total">{{ number(row.unreported_calls) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <h3>Por modelo</h3>
      <div class="table-wrap" v-if="usage.byModel.length">
        <table class="responsive-table">
          <thead>
            <tr>
              <th>Provedor / modelo</th>
              <th>Chamadas</th>
              <th>Entrada</th>
              <th>Saída</th>
              <th>Total informado</th>
              <th>Sem total</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in usage.byModel" :key="`${row.provider}/${row.model}`">
              <td data-label="Modelo">
                {{ row.model || 'Não informado'
                }}<small class="block muted">{{ row.provider || 'Provedor não informado' }}</small>
              </td>
              <td data-label="Chamadas">{{ number(row.calls) }}</td>
              <td data-label="Entrada">{{ number(row.input_tokens) }}</td>
              <td data-label="Saída">{{ number(row.output_tokens) }}</td>
              <td data-label="Total informado">{{ number(row.total_tokens) }}</td>
              <td data-label="Sem total">{{ number(row.unreported_calls) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="muted monitor-note">
        Histórico dos últimos dois meses, com limpeza automática dos registros mais antigos. Inclui
        chat, análise visual e chamadas extras de correção. Até 50 usuários e 50 modelos por
        ranking; o total inclui todos. Os dias seguem o horário de Brasília. Chamadas auxiliares
        internas, novas tentativas internas do Hermes, áudio e falhas sem retorno podem não estar
        incluídos. Tokens não representam valor cobrado nem saldo do plano gratuito. Mensagens e
        credenciais não são armazenadas neste painel.
      </p>
    </template>
    <p v-else-if="loading" class="muted">Consultando uso da IA…</p>
  </section>
</template>

<style scoped>
.ai-usage-filters {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  margin-bottom: 24px;
}
@media (max-width: 600px) {
  .ai-usage-filters {
    grid-template-columns: 1fr;
  }
}
</style>
