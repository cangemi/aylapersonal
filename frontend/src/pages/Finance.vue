<script setup>
import { ref, onMounted } from 'vue'
import { api, write, query } from '../lib/api'
import { money, cents, moneyInput, monthRange, timestamp, localDateTime } from '../lib/format'
import { toast, confirmAction } from '../lib/ui'
import Icon from '../components/Icon.vue'
import MaskedInput from '../components/MaskedInput.vue'
import Chart from '../components/Chart.vue'
import Modal from '../components/Modal.vue'
import EmptyState from '../components/EmptyState.vue'
import FinanceCategories from '../components/FinanceCategories.vue'
import FinanceTrends from '../components/FinanceTrends.vue'
const categoryOptions = [
  'Mercado',
  'Alimentação',
  'Casa',
  'Contas',
  'Transporte',
  'Saúde',
  'Lazer',
  'Jogos',
  'Assinaturas',
  'Educação',
  'Vestuário',
  'Beleza',
  'Pets',
  'Presentes',
  'Viagem',
  'Tecnologia',
  'Impostos e taxas',
  'Família',
  'Trabalho',
  'Salário',
  'Renda extra',
  'Investimento',
  'Outros',
]
const tab = ref('list'),
  applied = ref(monthRange())
const filters = ref({ ...monthRange(), type: '', category: '' }),
  page = ref(0),
  rows = ref({ items: [], total: 0 }),
  summary = ref(null),
  loading = ref(false),
  error = ref(''),
  form = ref(null),
  saving = ref(false),
  formError = ref('')
async function load() {
  loading.value = true
  error.value = ''
  try {
    const [list, totals] = await Promise.all([
      api('/finance/entries?' + query({ ...filters.value, page: page.value, size: 15 })),
      api(
        '/finance/summary?' +
          query({ from: filters.value.from, to: filters.value.to, groupBy: 'DAY' }),
      ),
    ])
    rows.value = list
    summary.value = totals
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}
function edit(entry) {
  formError.value = ''
  form.value = entry
    ? {
        ...entry,
        amount: moneyInput(entry.amountCents),
        occurredAt: localDateTime(entry.occurredAt),
      }
    : {
        type: 'EXPENSE',
        amount: '',
        description: '',
        category: '',
        occurredAt: localDateTime(new Date()),
      }
}
async function save() {
  saving.value = true
  formError.value = ''
  try {
    const f = form.value,
      input = {
        type: f.type,
        amountCents: cents(f.amount),
        description: f.description.trim(),
        category: f.category.trim(),
        occurredAt: new Date(f.occurredAt).toISOString(),
      }
    if (f.id) await write('/finance/entries/' + f.id, 'PUT', { version: f.version, entry: input })
    else await write('/finance/entries', 'POST', input)
    form.value = null
    toast('Lançamento salvo.')
    await load()
  } catch (e) {
    formError.value = e.message
  } finally {
    saving.value = false
  }
}
async function remove(entry) {
  if (
    !(await confirmAction(
      'Excluir lançamento?',
      entry.description + ' · ' + money(entry.amountCents),
    ))
  )
    return
  try {
    await write(`/finance/entries/${entry.id}?version=${entry.version}`, 'DELETE')
    toast('Lançamento excluído.')
    await load()
  } catch (e) {
    toast(e.message, 'error')
  }
}
function filter() {
  applied.value = { from: filters.value.from, to: filters.value.to }
  page.value = 0
  load()
}
function changePage(direction) {
  page.value += direction
  load()
}
function exportCsv() {
  const csv = [
    ['Data', 'Descrição', 'Categoria', 'Tipo', 'Centavos'],
    ...rows.value.items.map((e) => [
      e.occurredAt,
      e.description,
      e.category,
      e.type,
      e.amountCents,
    ]),
  ]
    .map((row) =>
      row
        .map(
          (v) =>
            '"' +
            String(v)
              .replaceAll('"', '""')
              .replace(/^[=+@-]/, "'$&") +
            '"',
        )
        .join(';'),
    )
    .join('\r\n')
  const url = URL.createObjectURL(new Blob(['\ufeff' + csv], { type: 'text/csv;charset=utf-8' }))
  const a = document.createElement('a')
  a.href = url
  a.download = 'ayla-lancamentos-pagina.csv'
  a.click()
  URL.revokeObjectURL(url)
}
onMounted(load)
</script>
<template>
  <div class="page-heading">
    <div>
      <span class="eyebrow">CADA ESCOLHA CONTA</span>
      <h1>Seu dinheiro, com clareza.</h1>
      <p>Registre, acompanhe e encontre seu equilíbrio.</p>
    </div>
    <button class="button primary" @click="edit()">
      <Icon name="plus" :size="18" />Novo lançamento
    </button>
  </div>
  <div v-if="error" class="banner error" role="alert">
    {{ error }}<button class="text-link" @click="load">Tentar novamente</button>
  </div>
  <div class="stats-grid">
    <article
      v-for="s in [
        {
          title: 'Saldo registrado',
          value: summary?.balanceCents,
          icon: 'wallet',
          color: 'lavender',
        },
        {
          title: 'Receitas no período',
          value: summary?.period?.incomeCents,
          icon: 'income',
          color: 'mint',
        },
        {
          title: 'Despesas no período',
          value: summary?.period?.expenseCents,
          icon: 'expense',
          color: 'peach',
        },
      ]"
      :key="s.title"
      class="stat-card"
    >
      <div class="stat-top">
        <span>{{ s.title }}</span
        ><span :class="['stat-icon', s.color]"><Icon :name="s.icon" :size="18" /></span>
      </div>
      <strong>{{ s.value == null ? '—' : money(s.value) }}</strong
      ><small>{{
        s.icon === 'wallet' ? 'Acumulado até agora' : 'Conforme o período selecionado'
      }}</small>
    </article>
  </div>
  <div class="tabs" role="tablist">
    <button
      v-for="t in [
        ['list', 'Lançamentos'],
        ['categories', 'Categorias'],
        ['trends', 'Tendências'],
      ]"
      :key="t[0]"
      role="tab"
      :aria-selected="tab === t[0]"
      :class="{ active: tab === t[0] }"
      @click="tab = t[0]"
    >
      {{ t[1] }}
    </button>
  </div>
  <form v-show="tab !== 'trends'" class="filter-bar" @submit.prevent="filter">
    <label>De<input v-model="filters.from" type="date" required /></label
    ><label>Até<input v-model="filters.to" type="date" :min="filters.from" required /></label
    ><label
      >Tipo<select v-model="filters.type">
        <option value="">Todos</option>
        <option value="INCOME">Receitas</option>
        <option value="EXPENSE">Despesas</option>
      </select></label
    ><label>Categoria<input v-model="filters.category" placeholder="Todas" /></label
    ><button class="button secondary" :disabled="loading">
      <Icon name="search" :size="16" />Filtrar
    </button>
  </form>
  <FinanceCategories v-if="tab === 'categories'" :from="applied.from" :to="applied.to" />
  <FinanceTrends v-else-if="tab === 'trends'" />
  <section v-if="tab === 'list' && summary?.series.length" class="panel finance-chart">
    <div class="panel-heading">
      <div>
        <h2>Seu período em movimento</h2>
        <p>Receitas e despesas por dia · sem filtro de tipo ou categoria</p>
      </div>
      <div class="chart-legend">
        <span><i class="mint-dot" />Receitas</span><span><i class="purple-dot" />Despesas</span>
      </div>
    </div>
    <Chart :series="summary.series" />
  </section>
  <section v-if="tab === 'list'" class="panel">
    <div class="panel-heading">
      <div>
        <h2>Lançamentos</h2>
        <p>{{ rows.total }} registro(s) no filtro</p>
      </div>
      <button class="button subtle" :disabled="!rows.items.length" @click="exportCsv">
        <Icon name="download" :size="16" />Exportar página
      </button>
    </div>
    <div v-if="loading" class="loading-line"><Icon name="loading" />Buscando lançamentos…</div>
    <div v-else-if="rows.items.length" class="table-wrap">
      <table class="responsive-table">
        <thead>
          <tr>
            <th>Descrição</th>
            <th>Categoria</th>
            <th>Data e hora</th>
            <th class="align-right">Valor</th>
            <th><span class="sr-only">Ações</span></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="entry in rows.items" :key="entry.id">
            <td data-label="Descrição">
              <span class="table-title"
                ><span :class="['stat-icon small', entry.type === 'INCOME' ? 'mint' : 'lavender']"
                  ><Icon :name="entry.type === 'INCOME' ? 'income' : 'expense'" :size="16" /></span
                >{{ entry.description }}</span
              >
            </td>
            <td data-label="Categoria">
              <span class="tag">{{ entry.category }}</span>
            </td>
            <td data-label="Data e hora" class="muted nowrap">{{ timestamp(entry.occurredAt) }}</td>
            <td
              data-label="Valor"
              :class="['align-right', 'nowrap', entry.type === 'INCOME' ? 'positive' : '']"
            >
              {{ entry.type === 'INCOME' ? '+' : '−' }} {{ money(entry.amountCents) }}
            </td>
            <td data-label="Ações">
              <div class="row-actions">
                <button class="icon-button" aria-label="Editar lançamento" @click="edit(entry)">
                  <Icon name="edit" :size="16" /></button
                ><button class="icon-button" aria-label="Excluir lançamento" @click="remove(entry)">
                  <Icon name="delete" :size="16" />
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <EmptyState
      v-else
      icon="wallet"
      title="Nada por aqui ainda"
      description="Adicione um lançamento ou escolha outro período."
      ><button class="button secondary" @click="edit()">Adicionar lançamento</button></EmptyState
    >
    <div v-if="rows.total > 15" class="pagination">
      <span>Página {{ page + 1 }} de {{ Math.ceil(rows.total / 15) }}</span
      ><button
        class="icon-button"
        aria-label="Página anterior"
        :disabled="page === 0 || loading"
        @click="changePage(-1)"
      >
        <Icon name="left" /></button
      ><button
        class="icon-button"
        aria-label="Próxima página"
        :disabled="(page + 1) * 15 >= rows.total || loading"
        @click="changePage(1)"
      >
        <Icon name="right" />
      </button>
    </div>
  </section>
  <p class="footnote">
    O saldo corresponde aos seus registros até agora. Não há sincronização com contas bancárias.
  </p>
  <Modal
    v-if="form"
    :title="form.id ? 'Editar lançamento' : 'Novo lançamento'"
    @close="!saving && (form = null)"
    ><form @submit.prevent="save">
      <div v-if="formError" class="banner error" role="alert">{{ formError }}</div>
      <div class="segmented">
        <button
          type="button"
          :class="{ selected: form.type === 'EXPENSE' }"
          @click="form.type = 'EXPENSE'"
        >
          Despesa</button
        ><button
          type="button"
          :class="{ selected: form.type === 'INCOME' }"
          @click="form.type = 'INCOME'"
        >
          Receita
        </button>
      </div>
      <label
        >Valor em reais<MaskedInput
          v-model="form.amount"
          inputmode="decimal"
          placeholder="0,00"
          required
        /><small>Exemplo: 0,12 para doze centavos.</small></label
      ><label
        >Descrição<input
          v-model="form.description"
          required
          maxlength="300"
          placeholder="Com o que foi?"
      /></label>
      <div class="form-grid">
        <label
          >Categoria<input
            v-model="form.category"
            required
            maxlength="80"
            list="category-options"
            placeholder="Ex.: Alimentação" /><datalist id="category-options">
            <option v-for="c in categoryOptions" :key="c" :value="c" /></datalist></label
        ><label
          >Data e hora local<input v-model="form.occurredAt" type="datetime-local" required
        /></label>
      </div>
      <div class="form-actions">
        <button class="button secondary" type="button" :disabled="saving" @click="form = null">
          Cancelar</button
        ><button class="button primary" :disabled="saving">
          <Icon v-if="saving" name="loading" :size="16" />Salvar lançamento
        </button>
      </div>
    </form></Modal
  >
</template>
