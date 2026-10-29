<script setup>
import { ref, onMounted, computed } from 'vue'
import { api, write } from '../lib/api'
import { money, cents, moneyInput, today, date as fmtDate, localDateTime } from '../lib/format'
import { toast } from '../lib/ui'
import Icon from '../components/Icon.vue'
import MaskedInput from '../components/MaskedInput.vue'
import BaseChart from '../components/BaseChart.vue'
import Modal from '../components/Modal.vue'
import EmptyState from '../components/EmptyState.vue'
const data = ref(null),
  loading = ref(true),
  error = ref(''),
  range = ref(90),
  sim = ref({ initial: '', monthly: '100,00', months: 12, cdi: '', percent: '' }),
  simResult = ref(null),
  simError = ref(''),
  simLoading = ref(false),
  move = ref(null),
  base = ref(null),
  moveError = ref(''),
  saving = ref(false),
  percentEdit = ref('')
const pct = (v, d = 2) =>
  v == null
    ? '—'
    : Number(v).toLocaleString('pt-BR', { minimumFractionDigits: d, maximumFractionDigits: d }) +
      '%'
const short = (iso) => iso.slice(8) + '/' + iso.slice(5, 7)
const monthLabel = (m) => {
  const [y, mo] = m.split('-')
  return (
    ['jan', 'fev', 'mar', 'abr', 'mai', 'jun', 'jul', 'ago', 'set', 'out', 'nov', 'dez'][mo - 1] +
    '/' +
    y.slice(2)
  )
}
async function load() {
  loading.value = true
  error.value = ''
  try {
    data.value = await api('/investments/overview')
    percentEdit.value = String(data.value.cdiPercent).replace('.', ',')
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}
const history = computed(() => (data.value?.history || []).slice(-range.value))
const hasMoves = computed(() => data.value?.hasData)
function lineConfig(theme, labels, sets, money_ = true) {
  return {
    type: 'line',
    data: { labels, datasets: sets },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      interaction: { mode: 'index', intersect: false },
      plugins: {
        legend: { display: false },
        tooltip: {
          callbacks: {
            label: (i) => `${i.dataset.label}: ${money_ ? money(i.raw) : pct(i.raw, 4)}`,
          },
        },
      },
      scales: {
        x: {
          grid: { display: false },
          border: { display: false },
          ticks: { color: theme.text, maxTicksLimit: 7 },
        },
        y: {
          grid: { color: theme.grid },
          border: { display: false },
          ticks: {
            color: theme.text,
            maxTicksLimit: 5,
            callback: (v) => (money_ ? money(Math.round(v)) : pct(v, 2)),
          },
        },
      },
    },
  }
}
const balanceChart = (theme) =>
  lineConfig(
    theme,
    history.value.map((p) => short(p.date)),
    [
      {
        label: 'Saldo',
        data: history.value.map((p) => p.balanceCents),
        borderColor: '#8a6fe0',
        backgroundColor: 'rgba(138,111,224,.15)',
        fill: true,
        pointRadius: 0,
        tension: 0.25,
        borderWidth: 2,
      },
      {
        label: 'Aportado',
        data: history.value.map((p) => p.investedCents),
        borderColor: '#81c6ac',
        borderDash: [5, 4],
        pointRadius: 0,
        borderWidth: 2,
        stepped: true,
      },
    ],
  )
const cdiChart = (theme) =>
  lineConfig(
    theme,
    (data.value?.cdi?.history || []).map((p) => short(p.date)),
    [
      {
        label: 'CDI a.a.',
        data: (data.value?.cdi?.history || []).map((p) => p.annualPercent),
        borderColor: '#e0a46f',
        pointRadius: 0,
        borderWidth: 2,
        stepped: true,
      },
    ],
    false,
  )
const monthsChart = (theme) => ({
  type: 'bar',
  data: {
    labels: (data.value?.months || []).map((m) => monthLabel(m.month)),
    datasets: [
      {
        label: 'Rendimento',
        data: (data.value?.months || []).map((m) => m.yieldCents),
        backgroundColor: '#81c6ac',
        borderRadius: 4,
        maxBarThickness: 28,
      },
    ],
  },
  options: {
    responsive: true,
    maintainAspectRatio: false,
    plugins: {
      legend: { display: false },
      tooltip: { callbacks: { label: (i) => `Rendimento: ${money(i.raw)}` } },
    },
    scales: {
      x: { grid: { display: false }, border: { display: false }, ticks: { color: theme.text } },
      y: {
        grid: { color: theme.grid },
        border: { display: false },
        ticks: { color: theme.text, maxTicksLimit: 5, callback: (v) => money(Math.round(v)) },
      },
    },
  },
})
const simChart = (theme) =>
  lineConfig(
    theme,
    (simResult.value?.points || []).map((p) => p.month + 'm'),
    [
      {
        label: 'Saldo',
        data: (simResult.value?.points || []).map((p) => p.balanceCents),
        borderColor: '#8a6fe0',
        backgroundColor: 'rgba(138,111,224,.15)',
        fill: true,
        pointRadius: 0,
        tension: 0.2,
        borderWidth: 2,
      },
      {
        label: 'Aportado',
        data: (simResult.value?.points || []).map((p) => p.investedCents),
        borderColor: '#81c6ac',
        borderDash: [5, 4],
        pointRadius: 0,
        borderWidth: 2,
      },
    ],
  )
const optionalNumber = (v) => {
  const t = String(v ?? '')
    .trim()
    .replace(',', '.')
  if (!t) return null
  const n = Number(t)
  if (!Number.isFinite(n) || n <= 0) throw new Error('Use números positivos nos percentuais.')
  return n
}
const optionalCents = (v) => (String(v ?? '').trim() ? cents(v, { allowZero: true }) : null)
async function simulate() {
  simLoading.value = true
  simError.value = ''
  try {
    const months = Number(sim.value.months)
    if (!Number.isInteger(months) || months < 1 || months > 600)
      throw new Error('Informe de 1 a 600 meses.')
    simResult.value = await write('/investments/simulate', 'POST', {
      initialCents: optionalCents(sim.value.initial),
      monthlyCents: optionalCents(sim.value.monthly),
      months,
      cdiAnnualPercent: optionalNumber(sim.value.cdi),
      percentCdi: optionalNumber(sim.value.percent),
    })
  } catch (e) {
    simError.value = e.message
  } finally {
    simLoading.value = false
  }
}
function openMove(type) {
  moveError.value = ''
  move.value = { type, amount: '', occurredAt: localDateTime(new Date()) }
}
async function saveMove() {
  saving.value = true
  moveError.value = ''
  try {
    const m = move.value,
      deposit = m.type === 'EXPENSE'
    await write('/finance/entries', 'POST', {
      type: m.type,
      amountCents: cents(m.amount),
      description: deposit ? 'Aporte na caixinha' : 'Resgate da caixinha',
      category: 'Investimento',
      occurredAt: new Date(m.occurredAt).toISOString(),
    })
    move.value = null
    toast(deposit ? 'Aporte registrado.' : 'Resgate registrado.')
    await load()
  } catch (e) {
    moveError.value = e.message
  } finally {
    saving.value = false
  }
}
function openBaseline() {
  moveError.value = ''
  const b = data.value?.baseline
  base.value = {
    from: b?.date || '',
    to: b?.date || today(),
    initial: '',
    inflow: '',
    outflow: '',
    yield: '',
    monthYield: b?.monthYieldCents ? moneyInput(b.monthYieldCents) : '',
    final: b ? moneyInput(b.balanceCents) : '',
  }
}
const centsOrZero = (v) => (String(v ?? '').trim() ? cents(v, { allowZero: true }) : 0)
async function saveBaseline() {
  saving.value = true
  moveError.value = ''
  try {
    const f = base.value
    if (f.from && f.from > f.to) throw new Error('O início do período deve ser antes do fim.')
    const initial = centsOrZero(f.initial)
    const inflow = centsOrZero(f.inflow)
    const outflow = centsOrZero(f.outflow)
    const gain = centsOrZero(f.yield)
    const expected = initial + inflow - outflow + gain
    const finalCents = String(f.final).trim() ? cents(f.final, { allowZero: true }) : expected
    if (Math.abs(finalCents - expected) > 5) {
      throw new Error(
        'O saldo final não bate: saldo inicial + entradas − saídas + rendimento deve ser igual ao saldo final.',
      )
    }
    await write('/investments/baseline', 'PUT', {
      date: f.to,
      balanceCents: finalCents,
      investedCents: Math.max(0, finalCents - gain),
      monthYieldCents: String(f.monthYield).trim()
        ? cents(f.monthYield, { allowZero: true })
        : f.from && f.from.slice(0, 7) === f.to.slice(0, 7)
          ? gain
          : null,
    })
    base.value = null
    toast('Saldo de partida salvo.')
    await load()
  } catch (e) {
    moveError.value = e.message
  } finally {
    saving.value = false
  }
}
async function clearBaseline() {
  try {
    await write('/investments/baseline', 'DELETE')
    base.value = null
    toast('Voltou a calcular só pelos lançamentos.')
    await load()
  } catch (e) {
    toast(e.message, 'error')
  }
}
async function savePercent() {
  try {
    const n = optionalNumber(percentEdit.value)
    if (n == null) throw new Error('Informe o percentual do CDI.')
    await write('/investments/settings', 'PUT', { cdiPercent: n })
    toast('Percentual do CDI atualizado.')
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
      <span class="eyebrow">DINHEIRO TRABALHANDO</span>
      <h1>Caixinha Nubank</h1>
      <p>
        Rende {{ pct(data?.cdiPercent, 0) }} do CDI. Aportes vêm dos lançamentos da categoria
        Investimento.
      </p>
    </div>
    <div class="invest-actions">
      <button class="button secondary" @click="openBaseline">Acertar com o Nubank</button>
      <button class="button primary" @click="openMove('EXPENSE')">
        <Icon name="plus" :size="18" />Aportar
      </button>
      <button class="button secondary" :disabled="!hasMoves" @click="openMove('INCOME')">
        Resgatar
      </button>
    </div>
  </div>
  <div v-if="error" class="banner error" role="alert">
    {{ error }}<button class="text-link" @click="load">Tentar novamente</button>
  </div>
  <div v-else-if="data?.cdi?.stale" class="banner">
    O CDI do Banco Central não está atualizado ({{ fmtDate(data.cdi.date) }}); usando o último valor
    conhecido.
  </div>
  <div v-if="loading && !data" class="loading-line"><Icon name="loading" />Carregando…</div>
  <template v-else-if="data">
    <div class="stats-grid invest-stats">
      <article
        v-for="s in [
          {
            t: 'Saldo atual',
            v: money(data.balanceCents),
            s: 'Aportado ' + money(data.netInvestedCents),
            i: 'wallet',
            c: 'lavender',
          },
          {
            t: 'Ganho total',
            v: money(data.gainCents),
            s: pct(data.gainPercent) + ' sobre o aportado',
            i: 'trend',
            c: 'mint',
          },
          {
            t: 'Rendeu no último dia',
            v: money(data.lastDayYieldCents),
            s: data.lastYieldDate ? fmtDate(data.lastYieldDate) : 'Sem dados',
            i: 'income',
            c: 'mint',
          },
          {
            t: 'Rendeu no mês',
            v: money(data.monthYieldCents),
            s: 'Últimos 30 dias: ' + money(data.last30DaysYieldCents),
            i: 'calendar',
            c: 'peach',
          },
          {
            t: 'Próximo dia útil',
            v: money(data.nextDayYieldCents),
            s: data.nextYieldDate ? fmtDate(data.nextYieldDate) : '',
            i: 'sparkles',
            c: 'lavender',
          },
          {
            t: 'Próximo mês',
            v: money(data.nextMonthYieldCents),
            s: 'Estimativa em ' + (data.nextMonth ? monthLabel(data.nextMonth) : '—'),
            i: 'trend',
            c: 'peach',
          },
        ]"
        :key="s.t"
        class="stat-card"
      >
        <div class="stat-top">
          <span>{{ s.t }}</span
          ><span :class="['stat-icon', s.c]"><Icon :name="s.i" :size="18" /></span>
        </div>
        <strong>{{ s.v }}</strong
        ><small>{{ s.s }}</small>
      </article>
    </div>
    <div v-if="data.baseline" class="banner">
      Saldo de partida: {{ money(data.baseline.balanceCents) }} em
      {{ fmtDate(data.baseline.date) }}, com {{ money(data.baseline.investedCents) }} aportados até
      lá. Lançamentos até essa data não contam.
      <button class="text-link" @click="openBaseline">Alterar</button>
    </div>
    <EmptyState
      v-if="!hasMoves"
      icon="trend"
      title="Nenhum aporte ainda"
      description="Informe o saldo atual da sua caixinha, aporte ou peça à Ayla para registrar um gasto na categoria Investimento."
    >
      <button class="button secondary" @click="openBaseline">Acertar com o Nubank</button>
    </EmptyState>
    <section v-if="history.length" class="panel finance-chart">
      <div class="panel-heading">
        <div>
          <h2>Evolução do saldo</h2>
          <p>Saldo com rendimento vs. total aportado</p>
        </div>
        <div class="segmented range-toggle">
          <button
            v-for="r in [30, 90, 365]"
            :key="r"
            :class="{ selected: range === r }"
            @click="range = r"
          >
            {{ r === 365 ? '1 ano' : r + ' dias' }}
          </button>
        </div>
      </div>
      <BaseChart
        :build="balanceChart"
        :watch-source="[history, range]"
        label="Evolução do saldo da caixinha"
      />
    </section>
    <div class="invest-grid">
      <section class="panel">
        <div class="panel-heading">
          <div>
            <h2>Rendimento por mês</h2>
            <p>Quanto a caixinha rendeu em cada mês</p>
          </div>
        </div>
        <BaseChart :build="monthsChart" :watch-source="data.months" label="Rendimento mensal" />
      </section>
      <section class="panel">
        <div class="panel-heading">
          <div>
            <h2>CDI</h2>
            <p>Taxa anual equivalente · fonte Banco Central</p>
          </div>
        </div>
        <div class="invest-cdi">
          <strong>{{ pct(data.cdi.annualPercent) }}</strong
          ><span>a.a. · {{ pct(data.cdi.dailyPercent, 4) }} ao dia útil</span>
          <small
            >Sua caixinha: {{ pct(data.productAnnualPercent) }} a.a. ({{
              pct(data.cdiPercent, 0)
            }}
            do CDI)</small
          >
        </div>
        <BaseChart
          :build="cdiChart"
          :watch-source="data.cdi.history"
          label="CDI anualizado nos últimos dias"
        />
        <form class="inline-form" @submit.prevent="savePercent">
          <label
            >% do CDI<MaskedInput
              kind="decimal"
              :scale="4"
              v-model="percentEdit"
              inputmode="decimal"
          /></label>
          <button class="button subtle">Salvar</button>
        </form>
      </section>
    </div>
    <section class="panel">
      <div class="panel-heading">
        <div>
          <h2>Projeção</h2>
          <p>Se o CDI atual se mantiver e sem novos aportes</p>
        </div>
      </div>
      <div class="projection-grid">
        <article v-for="p in data.projections" :key="p.label" class="projection">
          <span>{{ p.label }}</span
          ><strong>{{ money(p.balanceCents) }}</strong
          ><small>+ {{ money(p.yieldCents) }} · {{ fmtDate(p.until) }}</small>
        </article>
      </div>
    </section>
    <section class="panel">
      <div class="panel-heading">
        <div>
          <h2>Simulador</h2>
          <p>Teste valores diferentes. Vazio no valor inicial usa seu saldo atual.</p>
        </div>
      </div>
      <form class="sim-form" @submit.prevent="simulate">
        <div v-if="simError" class="banner error" role="alert">{{ simError }}</div>
        <label
          >Valor inicial (R$)<MaskedInput
            v-model="sim.initial"
            inputmode="decimal"
            placeholder="Saldo atual"
        /></label>
        <label
          >Aporte mensal (R$)<MaskedInput
            v-model="sim.monthly"
            inputmode="decimal"
            placeholder="0,00"
        /></label>
        <label
          >Meses<MaskedInput
            kind="integer"
            :scale="0"
            v-model="sim.months"
            min="1"
            max="600"
            required
        /></label>
        <label
          >CDI anual (%)<MaskedInput
            kind="decimal"
            :scale="4"
            v-model="sim.cdi"
            inputmode="decimal"
            :placeholder="pct(data.cdi.annualPercent)"
        /></label>
        <label
          >% do CDI<MaskedInput
            kind="decimal"
            :scale="4"
            v-model="sim.percent"
            inputmode="decimal"
            :placeholder="String(data.cdiPercent)"
        /></label>
        <button class="button primary" :disabled="simLoading">
          <Icon name="trend" :size="16" />Simular
        </button>
      </form>
      <template v-if="simResult">
        <div class="projection-grid sim-summary">
          <article class="projection">
            <span>Saldo final</span><strong>{{ money(simResult.finalBalanceCents) }}</strong>
          </article>
          <article class="projection">
            <span>Total aportado</span><strong>{{ money(simResult.investedCents) }}</strong>
          </article>
          <article class="projection">
            <span>Rendimento</span><strong>{{ money(simResult.gainCents) }}</strong
            ><small>{{ pct(simResult.productAnnualPercent) }} a.a.</small>
          </article>
        </div>
        <BaseChart
          :build="simChart"
          :watch-source="simResult"
          label="Simulação de evolução do saldo"
        />
      </template>
    </section>
    <section v-if="data.movements.length" class="panel">
      <div class="panel-heading">
        <div>
          <h2>Movimentações</h2>
          <p>Aportes e resgates mais recentes</p>
        </div>
      </div>
      <ul class="movement-list">
        <li v-for="m in data.movements" :key="m.entryId">
          <span
            ><strong>{{ m.type === 'DEPOSIT' ? 'Aporte' : 'Resgate' }}</strong> · {{ m.description
            }}<small>{{ fmtDate(m.date) }}</small></span
          >
          <b :class="m.type === 'DEPOSIT' ? '' : 'positive'"
            >{{ m.type === 'DEPOSIT' ? '+' : '−' }} {{ money(m.amountCents) }}</b
          >
        </li>
      </ul>
    </section>
    <p class="footnote">
      Rendimento estimado e bruto, sem IR e IOF, calculado com o CDI diário do Banco Central. O
      valor real no Nubank pode diferir um pouco.
    </p>
  </template>
  <Modal v-if="base" title="Acertar com o Nubank" @close="!saving && (base = null)">
    <form @submit.prevent="saveBaseline">
      <div v-if="moveError" class="banner error" role="alert">{{ moveError }}</div>
      <p class="muted">
        Copie o resumo do período que o Nubank mostra na caixinha. A partir do saldo final o
        rendimento é calculado, sem precisar do histórico.
      </p>
      <label>Início do período<input v-model="base.from" type="date" :max="today()" /></label>
      <label>Fim do período<input v-model="base.to" type="date" :max="today()" required /></label>
      <label
        >Saldo inicial (R$)<MaskedInput
          v-model="base.initial"
          inputmode="decimal"
          placeholder="0,00"
      /></label>
      <label
        >Entradas (R$)<MaskedInput v-model="base.inflow" inputmode="decimal" placeholder="0,00"
      /></label>
      <label
        >Saídas (R$)<MaskedInput v-model="base.outflow" inputmode="decimal" placeholder="0,00"
      /></label>
      <label
        >Rendimento (R$)<MaskedInput v-model="base.yield" inputmode="decimal" placeholder="0,00"
      /></label>
      <label
        >Rendimento do mês até agora (R$)<MaskedInput
          v-model="base.monthYield"
          inputmode="decimal"
          placeholder="Em branco = calculado pelo CDI"
      /></label>
      <label
        >Saldo final (R$)<MaskedInput
          v-model="base.final"
          inputmode="decimal"
          placeholder="Em branco = calculado"
      /></label>
      <p class="muted">
        Lançamentos de investimento até o fim do período deixam de contar. O rendimento informado
        vira o ganho já obtido.
      </p>
      <div class="modal-actions">
        <button
          v-if="data?.baseline"
          type="button"
          class="button subtle"
          :disabled="saving"
          @click="clearBaseline"
        >
          Remover
        </button>
        <button type="button" class="button subtle" :disabled="saving" @click="base = null">
          Cancelar
        </button>
        <button class="button primary" :disabled="saving">Salvar</button>
      </div>
    </form>
  </Modal>
  <Modal
    v-if="move"
    :title="move.type === 'EXPENSE' ? 'Aportar na caixinha' : 'Resgatar da caixinha'"
    @close="!saving && (move = null)"
  >
    <form @submit.prevent="saveMove">
      <div v-if="moveError" class="banner error" role="alert">{{ moveError }}</div>
      <label
        >Valor (R$)<MaskedInput
          v-model="move.amount"
          inputmode="decimal"
          placeholder="0,00"
          required
      /></label>
      <label>Data e hora<input v-model="move.occurredAt" type="datetime-local" required /></label>
      <p class="muted">
        Cria um lançamento na categoria Investimento; o rendimento começa no próximo dia útil.
      </p>
      <div class="modal-actions">
        <button type="button" class="button subtle" :disabled="saving" @click="move = null">
          Cancelar
        </button>
        <button class="button primary" :disabled="saving">Salvar</button>
      </div>
    </form>
  </Modal>
</template>
