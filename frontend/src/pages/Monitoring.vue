<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { api, query } from '../lib/api'
import { timestamp, localDateTime } from '../lib/format'
import { toast } from '../lib/ui'
import Icon from '../components/Icon.vue'
import ExternalLink from '../components/ExternalLink.vue'
import Modal from '../components/Modal.vue'
import AiUsagePanel from '../components/AiUsagePanel.vue'
import MonitorFeedRow from '../components/MonitorFeedRow.vue'

const grafanaUrl = import.meta.env.VITE_GRAFANA_URL || ''

const filters = ref({
  from: localDateTime(Date.now() - 86400000),
  to: '',
  userId: '',
  source: '',
  outcome: 'ALL',
  q: '',
})
const applied = ref({ ...filters.value }),
  users = ref([]),
  overview = ref(null),
  rows = ref({ items: [], total: 0 }),
  page = ref(0)
const loading = ref(false),
  error = ref(''),
  live = ref(true),
  updated = ref(null),
  detail = ref(null),
  detailError = ref(''),
  detailBusy = ref(false)
const view = ref('users'),
  collapsed = ref(new Set()),
  // Filters start open on desktop and folded on phones, where space is scarce.
  filtersOpen = ref(typeof window === 'undefined' || window.innerWidth > 600),
  traces = ref({ items: [], total: 0 }),
  tracesPage = ref(0),
  traceCollapsed = ref(new Set()),
  journey = ref(null),
  journeyError = ref(''),
  journeyBusy = ref(false)
let timer,
  disposed = false
const sources = {
  WEB: 'Site',
  WHATSAPP: 'WhatsApp',
  ANONYMOUS: 'Não autenticado',
}
const jobStates = {
  QUEUED: 'Na fila',
  RUNNING: 'Preparando resposta',
  COMPLETED: 'Concluída',
  FAILED: 'Não concluída',
  NEEDS_REVIEW: 'Precisa de revisão',
  CANCELLED: 'Cancelada',
}
const number = (value) => Number(value || 0).toLocaleString('pt-BR')
const tokens = (value) => (value == null ? '—' : number(value))
function parameters() {
  const f = applied.value
  return {
    ...f,
    from: f.from ? new Date(f.from).toISOString() : undefined,
    to: f.to ? new Date(f.to).toISOString() : undefined,
  }
}
async function load() {
  if (loading.value || disposed) return
  loading.value = true
  error.value = ''
  try {
    const params = parameters()
    if (view.value === 'calls') {
      const [summary, calls] = await Promise.all([
        api('/monitoring?' + query(params)),
        api(
          '/monitoring/traces?' +
            query({ ...params, page: tracesPage.value, size: 25 }),
        ),
      ])
      if (disposed) return
      overview.value = summary
      traces.value = calls
    } else {
      const [summary, feed] = await Promise.all([
        api('/monitoring?' + query(params)),
        api('/monitoring/feed?' + query({ ...params, page: page.value, size: 50 })),
      ])
      if (disposed) return
      overview.value = summary
      rows.value = feed
    }
    updated.value = new Date().toISOString()
  } catch (e) {
    if (!disposed) error.value = e.message
  } finally {
    loading.value = false
  }
}
/** One card per user, ordered by the most recent activity in the page. */
const groups = computed(() => {
  const byUser = new Map()
  for (const item of rows.value.items) {
    const key = item.user_id || 'anonymous'
    let group = byUser.get(key)
    if (!group) {
      group = {
        key,
        name: item.user_name,
        username: item.username,
        items: [],
        errors: 0,
        aiCalls: 0,
        tokens: 0,
      }
      byUser.set(key, group)
    }
    group.items.push(item)
    if (item.status >= 400) group.errors++
    if (item.kind === 'ia') {
      group.aiCalls++
      group.tokens += Number(item.total_tokens || 0)
    }
  }
  return [...byUser.values()]
})
function toggleGroup(key) {
  const next = new Set(collapsed.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  collapsed.value = next
}
  const traceGroups = computed(() => {
    const byUser = new Map()
    for (const call of traces.value.items) {
      const key = call.user_id || 'anonymous'
      let group = byUser.get(key)
      if (!group) {
        group = { key, name: call.user_name, username: call.username, calls: [] }
        byUser.set(key, group)
      }
      group.calls.push(call)
    }
    return [...byUser.values()]
  })
  function toggleTraceGroup(key) {
    const next = new Set(traceCollapsed.value)
    if (next.has(key)) next.delete(key)
    else next.add(key)
    traceCollapsed.value = next
  }
function apply() {
  applied.value = { ...filters.value }
  page.value = 0
  tracesPage.value = 0
  load()
}
function changePage(direction) {
  page.value += direction
  load()
}
function changeTracesPage(direction) {
  tracesPage.value += direction
  load()
}
function selectView(next) {
  if (view.value === next) return
  view.value = next
  load()
}
async function showDetail(event) {
  detail.value = event
  detailError.value = ''
  detailBusy.value = true
  try {
    const result = await api('/monitoring/events/' + event.id)
    if (detail.value?.id === event.id) detail.value = result
  } catch (e) {
    detailError.value = e.message
  } finally {
    detailBusy.value = false
  }
}
async function showJourney(reference) {
  journey.value = null
  journeyError.value = ''
  journeyBusy.value = true
  try {
    journey.value = await api('/monitoring/traces/' + reference)
  } catch (e) {
    journey.value = null
    toast(e.message, 'error')
  } finally {
    journeyBusy.value = false
  }
}
/** Timeline from message receipt to delivery: queue, AI calls and related events in order. */
const timeline = computed(() => {
  const call = journey.value
  if (!call) return []
  const wait = call.started_at && call.created_at
    ? new Date(call.started_at) - new Date(call.created_at)
    : null
  const steps = [
    {
      key: 'received',
      label: 'Mensagem recebida',
      at: call.created_at,
      detail: sources[call.source] || call.source,
    },
    wait == null
      ? null
      : {
          key: 'queue',
          label: wait > 30000 ? 'Aguardou a fila da Ayla' : 'Ayla recebeu o pedido e começou a processar',
          at: call.started_at,
          detail: `Espera de ${number(wait)} ms${Number(call.attempts) > 1 ? ` · ${number(call.attempts)} tentativas` : ''}`,
        },
    ...call.ai_calls.map((item, index) => ({
      key: 'ai-' + index,
      label: item.purpose === 'VISION' ? 'Analisou a imagem com a IA' : 'Respondeu com a IA',
      at: item.occurred_at,
      detail: `${item.model || 'Modelo não informado'} · entrada ${tokens(item.input_tokens)} · saída ${tokens(item.output_tokens)} · total ${tokens(item.total_tokens)} tokens · ${number(item.duration_ms)} ms`,
      error: item.status >= 400,
    })),
    ...call.related.map((item) => ({
      key: 'event-' + item.id,
      label: item.action,
      at: item.occurred_at,
      detail: `${item.method} ${item.route} · ${item.status} · ${number(item.duration_ms)} ms`,
      error: item.status >= 400,
      reason: item.error_reason,
      item,
    })),
    call.finished_at
      ? {
          key: 'finished',
          label: jobStates[call.status] || call.status,
          at: call.finished_at,
          detail:
            call.status === 'COMPLETED'
              ? 'A Ayla terminou de preparar a resposta.'
              : 'O pedido terminou sem confirmação de sucesso.',
          error: !['COMPLETED', 'QUEUED', 'RUNNING'].includes(call.status),
        }
      : null,
    call.delivered_at
      ? {
          key: 'delivered',
          label: 'Resposta entregue',
          at: call.delivered_at,
          detail: sources[call.source] || call.source,
        }
      : null,
  ]
  return steps.filter(Boolean).sort((a, b) => new Date(a.at) - new Date(b.at))
})
async function copyReference() {
  try {
    await navigator.clipboard.writeText(detail.value.id)
    toast('Referência copiada.')
  } catch {
    toast('Selecione e copie a referência exibida.', 'error')
  }
}
onMounted(async () => {
  load()
  try {
    users.value = await api('/monitoring/users')
  } catch (e) {
    toast(e.message, 'error')
  }
  if (!disposed)
    timer = setInterval(() => {
      if (live.value && !document.hidden && !detail.value && !journey.value) load()
    }, 15000)
})
onBeforeUnmount(() => {
  disposed = true
  clearInterval(timer)
})
</script>

<template>
  <div class="page-heading">
    <div>
      <span class="eyebrow">ADMINISTRAÇÃO</span>
      <h1>Monitoramento da aplicação.</h1>
      <p>Acompanhe o uso, identifique falhas e encontre onde atuar.</p>
    </div>
    <span class="pill lavender"><Icon name="shield" :size="14" />Superadministrador</span>
  </div>
  <div class="monitor-toolbar">
    <ExternalLink v-if="grafanaUrl" class="button secondary" :href="grafanaUrl">
      <Icon name="monitor" :size="16" />Aplicação e VPS no Grafana
    </ExternalLink>
    <label class="check-label"
      ><input v-model="live" type="checkbox" />Atualizar a cada 15 segundos</label
    >
    <span class="muted">{{
      updated ? 'Atualizado ' + timestamp(updated) : 'Carregando atividade…'
    }}</span>
    <button class="button secondary" :disabled="loading" @click="load">
      <Icon :name="loading ? 'loading' : 'refresh'" :size="16" />Atualizar
    </button>
  </div>
  <details class="monitor-filter-drawer" :open="filtersOpen">
    <summary><Icon name="search" :size="16" />Filtros</summary>
    <form class="panel monitor-filters" @submit.prevent="apply">
      <label>De<input v-model="filters.from" type="datetime-local" required /></label>
      <label
        >Até<input v-model="filters.to" type="datetime-local" :min="filters.from" /><small
          >Em branco: até agora. Período máximo de 31 dias.</small
        ></label
      >
      <label
        >Usuário<select v-model="filters.userId">
          <option value="">Todos os usuários</option>
          <option v-for="user in users" :key="user.id" :value="user.id">
            {{ user.name }} · {{ user.username }}
          </option>
        </select></label
      >
      <label
        >Origem<select v-model="filters.source">
          <option value="">Todas as origens</option>
          <option v-for="(name, value) in sources" :key="value" :value="value">{{ name }}</option>
        </select></label
      >
      <label
        >Resultado<select v-model="filters.outcome">
          <option value="ALL">Todos</option>
          <option value="SUCCESS">Sucesso</option>
          <option value="ERROR">Todos os erros</option>
          <option value="CLIENT_ERROR">Validação e acesso (4xx)</option>
          <option value="SERVER_ERROR">Falhas internas e integrações (5xx)</option>
        </select></label
      >
      <label
        >Buscar ação ou erro<input
          v-model="filters.q"
          maxlength="120"
          placeholder="Ex.: financeiro, HermesException"
      /></label>
      <button class="button primary" :disabled="loading">
        <Icon name="search" :size="16" />Aplicar filtros
      </button>
    </form>
  </details>
  <div v-if="error" class="banner error" role="alert">{{ error }}</div>
  <div class="monitor-metrics" v-if="overview">
    <article class="panel">
      <span>Operações no período</span><strong>{{ number(overview.totals.total) }}</strong
      ><small>Requisições e eventos de processamento</small>
    </article>
    <article class="panel">
      <span>Erros</span
      ><strong :class="{ 'monitor-error-text': Number(overview.totals.errors) > 0 }">{{
        number(overview.totals.errors)
      }}</strong
      ><small>{{ number(overview.totals.server_errors) }} falhas internas ou de integração</small>
    </article>
    <article class="panel">
      <span>Usuários com atividade</span><strong>{{ number(overview.totals.active_users) }}</strong
      ><small>Usuários identificados nos registros</small>
    </article>
    <article class="panel">
      <span>Tempo médio</span><strong>{{ number(overview.totals.average_ms) }} <em>ms</em></strong
      ><small>95% das operações até {{ number(overview.totals.p95_ms) }} ms</small>
    </article>
  </div>
  <section class="panel monitor-events" :aria-busy="loading">
    <div class="panel-heading">
      <div>
        <h2>Atividade</h2>
        <p v-if="view === 'calls'">
          {{ number(traces.total) }} chamadas feitas à Ayla. Selecione Jornada para ver cada etapa,
          do recebimento até a resposta.
        </p>
        <p v-else>
          {{ number(rows.total) }} registros de API e de IA no mesmo fluxo. Selecione Jornada para
          ver o caminho de uma chamada à Ayla.
        </p>
      </div>
      <div class="monitor-view" role="tablist" aria-label="Como exibir a atividade">
        <button
          :class="['button', view === 'users' ? 'primary' : 'secondary']"
          role="tab"
          :aria-selected="view === 'users'"
          @click="selectView('users')"
        >
          <Icon name="grid" :size="16" />Por usuário
        </button>
        <button
          :class="['button', view === 'list' ? 'primary' : 'secondary']"
          role="tab"
          :aria-selected="view === 'list'"
          @click="selectView('list')"
        >
          <Icon name="list" :size="16" />Lista única
        </button>
        <button
          :class="['button', view === 'calls' ? 'primary' : 'secondary']"
          role="tab"
          :aria-selected="view === 'calls'"
          @click="selectView('calls')"
        >
          <Icon name="chat" :size="16" />Chamadas à Ayla
        </button>
      </div>
    </div>
    <div v-if="view === 'calls'">
      <div v-if="!traces.items.length" class="empty-state">
        <Icon name="chat" :size="32" />
        <p>
          {{
            loading
              ? 'Buscando chamadas…'
              : 'Nenhuma chamada à Ayla neste período com os filtros selecionados.'
          }}
        </p>
      </div>
      <template v-if="traces.items.length">
        <section v-for="group in traceGroups" :key="group.key" class="monitor-user">
          <button
            class="monitor-user-head"
            :aria-expanded="!traceCollapsed.has(group.key)"
            @click="toggleTraceGroup(group.key)"
          >
            <span class="monitor-user-avatar">{{ (group.name || '?').slice(0, 1).toUpperCase() }}</span>
            <span class="monitor-user-name">
              <strong>{{ group.name }}</strong>
              <small>{{ group.username || 'Sem usuário identificado' }}</small>
            </span>
            <span class="monitor-user-stats">
              <span class="tag">{{ number(group.calls.length) }} chamadas</span>
              <span class="tag lavender"
                >{{ number(group.calls.reduce((sum, call) => sum + Number(call.total_tokens || 0), 0)) }} tokens</span
              >
            </span>
            <Icon :name="traceCollapsed.has(group.key) ? 'right' : 'left'" :size="18" />
          </button>
          <div v-if="!traceCollapsed.has(group.key)" class="table-wrap">
            <table class="responsive-table">
              <thead>
                <tr>
                  <th>Quando</th>
                  <th>Situação</th>
                  <th>Origem</th>
                  <th>IA</th>
                  <th>Duração</th>
                  <th><span class="sr-only">Jornada</span></th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="call in group.calls" :key="call.id">
                  <td data-label="Usuário">
                    <strong>{{ group.name }}</strong
                    ><small class="block muted">{{ timestamp(call.created_at) }}</small>
                  </td>
                  <td data-label="Situação">
                    <span :class="['tag', call.status === 'COMPLETED' ? 'green' : 'red']">{{
                      jobStates[call.status] || call.status
                    }}</span
                    ><small v-if="Number(call.attempts) > 1" class="block muted"
                      >{{ number(call.attempts) }} tentativas</small
                    >
                  </td>
                  <td data-label="Origem">{{ sources[call.source] || call.source }}</td>
                  <td data-label="IA">
                    {{ number(call.ai_calls) }} chamada<span v-if="call.ai_calls != 1">s</span>
                    <small class="block muted">{{ number(call.total_tokens) }} tokens</small>
                  </td>
                  <td data-label="Duração">
                    {{ call.duration_ms == null ? 'Em andamento' : number(call.duration_ms) + ' ms' }}
                  </td>
                  <td data-label="Jornada">
                    <button class="button secondary" @click="showJourney(call.id)">Jornada</button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </template>
      <div class="pagination">
        <span>{{ number(traces.total) }} chamadas · página {{ tracesPage + 1 }}</span>
        <div class="button-row">
          <button
            class="button secondary"
            :disabled="tracesPage === 0 || loading"
            @click="changeTracesPage(-1)"
          >
            Anterior</button
          ><button
            class="button secondary"
            :disabled="(tracesPage + 1) * 25 >= traces.total || loading"
            @click="changeTracesPage(1)"
          >
            Próxima
          </button>
        </div>
      </div>
    </div>
    <div v-else-if="!rows.items.length" class="empty-state">
      <Icon name="monitor" :size="32" />
      <p>
        {{
          loading
            ? 'Buscando atividade…'
            : 'Nenhuma atividade neste período com os filtros selecionados.'
        }}
      </p>
    </div>
    <template v-else-if="view === 'users'">
      <section v-for="group in groups" :key="group.key" class="monitor-user">
        <button
          class="monitor-user-head"
          :aria-expanded="!collapsed.has(group.key)"
          @click="toggleGroup(group.key)"
        >
          <span class="monitor-user-avatar">{{ (group.name || '?').slice(0, 1).toUpperCase() }}</span>
          <span class="monitor-user-name">
            <strong>{{ group.name }}</strong>
            <small>{{ group.username || 'Sem usuário identificado' }}</small>
          </span>
          <span class="monitor-user-stats">
            <span class="tag">{{ number(group.items.length) }} registros</span>
            <span v-if="group.errors" class="tag red">{{ number(group.errors) }} erros</span>
            <span v-if="group.aiCalls" class="tag lavender"
              >{{ number(group.aiCalls) }} chamadas · {{ number(group.tokens) }} tokens</span
            >
          </span>
          <Icon :name="collapsed.has(group.key) ? 'right' : 'left'" :size="18" />
        </button>
        <ul v-if="!collapsed.has(group.key)" class="monitor-feed">
          <MonitorFeedRow
            v-for="item in group.items"
            :key="item.id"
            :item="item"
            @details="showDetail"
            @journey="showJourney"
          />
        </ul>
      </section>
    </template>
    <ul v-else class="monitor-feed">
      <MonitorFeedRow
        v-for="item in rows.items"
        :key="item.id"
        :item="item"
        @details="showDetail"
        @journey="showJourney"
      />
    </ul>
    <div v-if="view !== 'calls'" class="pagination">
      <span>{{ number(rows.total) }} registros · página {{ page + 1 }}</span>
      <div class="button-row">
        <button class="button secondary" :disabled="page === 0 || loading" @click="changePage(-1)">
          Anterior</button
        ><button
          class="button secondary"
          :disabled="(page + 1) * 50 >= rows.total || loading"
          @click="changePage(1)"
        >
          Próxima
        </button>
      </div>
    </div>
  </section>
  <AiUsagePanel :filters="parameters()" :refresh-at="updated" :users="users" />
  <section class="panel monitor-failures" v-if="overview?.failures?.length">
    <div class="panel-heading">
      <div>
        <h2>Ações com mais falhas</h2>
        <p>Priorize as operações que precisam de atenção.</p>
      </div>
      <Icon name="error" />
    </div>
    <div v-for="item in overview.failures" :key="item.method + item.route" class="monitor-failure">
      <div>
        <strong>{{ item.action }}</strong
        ><code>{{ item.method }} {{ item.route }}</code>
      </div>
      <span class="tag">{{ number(item.failures) }} falhas</span>
    </div>
  </section>
  <p class="muted monitor-note">
    Registros a partir da ativação do monitoramento. Consultas e alterações são operações da API,
    não contagem de cliques. Senhas, tokens, mensagens e arquivos não são armazenados aqui.
    Consultas do próprio painel e verificações de conexão bem-sucedidas não entram no histórico.
  </p>
  <Modal v-if="detail" title="Detalhes da operação" @close="detail = null">
    <div v-if="detailError" class="banner error" role="alert">{{ detailError }}</div>
    <p v-if="detailBusy" class="muted">Carregando detalhes…</p>
    <dl class="monitor-detail">
      <dt>Usuário</dt>
      <dd>
        {{ detail.user_name }}<small class="block muted">{{ detail.username }}</small>
      </dd>
      <dt>Ação</dt>
      <dd>{{ detail.action }}</dd>
      <dt>Data e hora</dt>
      <dd>{{ timestamp(detail.occurred_at) }}</dd>
      <dt>Origem</dt>
      <dd>{{ sources[detail.source] }}</dd>
      <dt>Operação</dt>
      <dd>
        <code>{{ detail.method }} {{ detail.route }}</code>
      </dd>
      <dt>Resultado</dt>
      <dd>{{ detail.status }} · {{ detail.error_reason || 'Concluída com sucesso.' }}</dd>
      <dt>Duração</dt>
      <dd>{{ detail.method === 'EVENT' ? 'Não medido' : number(detail.duration_ms) + ' ms' }}</dd>
      <template v-if="detail.error_type"
        ><dt>Tipo do erro</dt>
        <dd>{{ detail.error_type }}</dd></template
      >
      <dt>Referência</dt>
      <dd>
        <code>{{ detail.id }}</code
        ><button class="text-link" @click="copyReference">Copiar referência</button>
      </dd>
    </dl>
    <template v-if="detail.stack_summary"
      ><h3>Localização no código</h3>
      <pre class="monitor-stack">{{ detail.stack_summary }}</pre>
      <p class="muted">
        Use a referência, o horário e a operação para correlacionar com os logs da API.
      </p></template
    >
  </Modal>
  <Modal v-if="journey || journeyBusy || journeyError" title="Jornada da chamada" wide @close="journey = null">
    <div v-if="journeyError" class="banner error" role="alert">{{ journeyError }}</div>
    <p v-if="journeyBusy" class="muted">Carregando a jornada…</p>
    <template v-if="journey">
      <div class="monitor-journey-head">
        <span :class="['tag', journey.status === 'COMPLETED' ? 'green' : 'red']">{{
          jobStates[journey.status] || journey.status
        }}</span>
        <span class="tag">{{ sources[journey.source] || journey.source }}</span>
        <span class="tag">{{ journey.user_name }}</span>
        <span class="tag"
          >{{ journey.duration_ms == null ? 'Em andamento' : number(journey.duration_ms) + ' ms' }} ·
          {{ number(journey.ai_calls.length) }} chamadas à IA ·
          {{ number(journey.ai_calls.reduce((sum, call) => sum + Number(call.total_tokens || 0), 0)) }}
          tokens</span
        >
      </div>
      <p class="muted">
        O que acontece em uma chamada da Ayla: a mensagem entra, espera a fila, vai para o modelo e
        a resposta volta para quem pediu. Nenhuma mensagem é exibida aqui.
      </p>
      <ol class="monitor-timeline">
        <li v-for="step in timeline" :key="step.key" :class="{ error: step.error }">
          <span class="monitor-timeline-dot" aria-hidden="true"></span>
          <div>
            <strong>{{ step.label }}</strong>
            <small class="muted">{{ timestamp(step.at) }} · {{ step.detail }}</small>
            <small v-if="step.reason" class="monitor-error-text">{{ step.reason }}</small>
            <button v-if="step.item" class="text-link" type="button" @click="showDetail(step.item); journey = null">
              Detalhes
            </button>
          </div>
        </li>
      </ol>
      <p v-if="!journey.related.length" class="muted monitor-note">
        Nenhuma outra ação da API foi vinculada a esse pedido além do envio e das chamadas ao
        modelo. Ações executadas internamente pela Ayla (leitura do histórico, downloads de mídia e
        ferramentas) não passam pela fila HTTP e nem aparecem aqui.
      </p>
      <p class="muted monitor-note">
        Referência: <code>{{ journey.id }}</code>
      </p>
    </template>
  </Modal>
</template>
