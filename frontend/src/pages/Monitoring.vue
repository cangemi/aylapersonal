<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { api, query } from '../lib/api'
import { timestamp, localDateTime } from '../lib/format'
import { toast } from '../lib/ui'
import Icon from '../components/Icon.vue'
import Modal from '../components/Modal.vue'
import AiUsagePanel from '../components/AiUsagePanel.vue'

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
let timer,
  disposed = false
const sources = {
  WEB: 'Site',
  WHATSAPP: 'WhatsApp',
  HERMES: 'Ferramenta da Ayla',
  ANONYMOUS: 'Não autenticado',
}
const number = (value) => Number(value || 0).toLocaleString('pt-BR')
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
    const [summary, events] = await Promise.all([
      api('/monitoring?' + query(params)),
      api('/monitoring/events?' + query({ ...params, page: page.value, size: 25 })),
    ])
    if (disposed) return
    overview.value = summary
    rows.value = events
    updated.value = new Date().toISOString()
  } catch (e) {
    if (!disposed) error.value = e.message
  } finally {
    loading.value = false
  }
}
function apply() {
  applied.value = { ...filters.value }
  page.value = 0
  load()
}
function changePage(direction) {
  page.value += direction
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
      if (live.value && !document.hidden && !detail.value) load()
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
    <a
      v-if="grafanaUrl"
      class="button secondary"
      :href="grafanaUrl"
      target="_blank"
      rel="noopener noreferrer"
    >
      <Icon name="monitor" :size="16" />Aplicação e VPS no Grafana
    </a>
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
  <section class="panel monitor-events" :aria-busy="loading">
    <div class="panel-heading">
      <div>
        <h2>Atividade dos usuários</h2>
        <p>
          {{ number(rows.total) }} registros encontrados. Selecione Detalhes para investigar uma
          operação.
        </p>
      </div>
      <Icon name="monitor" />
    </div>
    <div v-if="!rows.items.length" class="empty-state">
      <Icon name="monitor" :size="32" />
      <p>
        {{
          loading
            ? 'Buscando atividade…'
            : 'Nenhuma atividade neste período com os filtros selecionados.'
        }}
      </p>
    </div>
    <div class="table-wrap" v-else>
      <table class="responsive-table">
        <thead>
          <tr>
            <th>Quando / usuário</th>
            <th>Ação</th>
            <th>Origem</th>
            <th>Resultado</th>
            <th>Duração</th>
            <th><span class="sr-only">Detalhes</span></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="event in rows.items" :key="event.id">
            <td data-label="Usuário">
              <strong>{{ event.user_name }}</strong
              ><small class="block muted">{{ timestamp(event.occurred_at) }}</small>
            </td>
            <td data-label="Ação">
              <strong>{{ event.action }}</strong
              ><code class="monitor-route">{{ event.method }} {{ event.route }}</code>
            </td>
            <td data-label="Origem">{{ sources[event.source] || event.source }}</td>
            <td data-label="Resultado">
              <span :class="['tag', event.status < 400 ? 'green' : 'monitor-error-text']"
                >{{
                  event.status < 400 ? 'Sucesso' : event.status >= 500 ? 'Falha' : 'Não concluída'
                }}
                · {{ event.status }}</span
              ><small v-if="event.error_type" class="block muted">{{ event.error_type }}</small>
            </td>
            <td data-label="Duração">
              {{ event.method === 'EVENT' ? 'Não medido' : number(event.duration_ms) + ' ms' }}
            </td>
            <td data-label="Detalhes">
              <button class="button secondary" @click="showDetail(event)">Detalhes</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div class="pagination">
      <span>{{ number(rows.total) }} registros · página {{ page + 1 }}</span>
      <div class="button-row">
        <button class="button secondary" :disabled="page === 0 || loading" @click="changePage(-1)">
          Anterior</button
        ><button
          class="button secondary"
          :disabled="(page + 1) * 25 >= rows.total || loading"
          @click="changePage(1)"
        >
          Próxima
        </button>
      </div>
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
</template>
