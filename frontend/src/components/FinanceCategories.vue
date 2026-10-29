<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { api, query } from '../lib/api'
import { money, timestamp, today } from '../lib/format'
import Icon from './Icon.vue'
import BaseChart from './BaseChart.vue'
const props = defineProps({ from: String, to: String })
const rows = ref([]),
  selected = ref(''),
  detail = ref({ months: [], entries: [], total: 0 }),
  loading = ref(false),
  error = ref('')
const palette = ['#8a6fe0', '#81c6ac', '#e0a46f', '#6fa8e0', '#e06f8f', '#c4c46f', '#6fe0d1', '#b06fe0', '#9aa5b1']
const label = (c) => (c ? c.charAt(0).toUpperCase() + c.slice(1) : 'Sem categoria')
const total = computed(() => rows.value.reduce((s, r) => s + r.expenseCents, 0))
const index = computed(() => rows.value.findIndex((r) => r.label === selected.value))
const current = computed(() => rows.value[index.value])
const share = (r) => (total.value ? ((r.expenseCents * 100) / total.value).toFixed(1).replace('.', ',') + '%' : '—')
const color = (i) => palette[i % palette.length]
function monthsBack(n) {
  const [y, m] = today().slice(0, 7).split('-').map(Number),
    d = new Date(Date.UTC(y, m - 1 - n, 1))
  return d.toISOString().slice(0, 10)
}
async function load() {
  loading.value = true
  error.value = ''
  try {
    const s = await api('/finance/summary?' + query({ from: props.from, to: props.to, groupBy: 'CATEGORY' }))
    rows.value = s.series.filter((r) => r.expenseCents > 0).sort((a, b) => b.expenseCents - a.expenseCents)
    if (!rows.value.some((r) => r.label === selected.value)) selected.value = rows.value[0]?.label || ''
    await loadDetail()
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}
async function loadDetail() {
  if (!selected.value) {
    detail.value = { months: [], entries: [], total: 0 }
    return
  }
  try {
    const [months, entries] = await Promise.all([
      api('/finance/summary?' + query({ from: monthsBack(5), to: today(), groupBy: 'MONTH', category: selected.value })),
      api('/finance/entries?' + query({ from: props.from, to: props.to, type: 'EXPENSE', category: selected.value, size: 8 })),
    ])
    detail.value = { months: months.series, entries: entries.items, total: entries.total }
  } catch (e) {
    error.value = e.message
  }
}
function select(name) {
  selected.value = name
  loadDetail()
}
function step(direction) {
  if (!rows.value.length) return
  select(rows.value[(index.value + direction + rows.value.length) % rows.value.length].label)
}
const donut = (theme) => ({
  type: 'doughnut',
  data: {
    labels: rows.value.map((r) => label(r.label)),
    datasets: [{
      data: rows.value.map((r) => r.expenseCents),
      backgroundColor: rows.value.map((r, i) => color(i)),
      borderColor: 'transparent',
      offset: rows.value.map((r) => (r.label === selected.value ? 14 : 0)),
    }],
  },
  options: {
    responsive: true,
    maintainAspectRatio: false,
    cutout: '62%',
    onClick: (_, els) => els.length && select(rows.value[els[0].index].label),
    plugins: { legend: { display: false }, tooltip: { callbacks: { label: (i) => `${i.label}: ${money(i.raw)}` } } },
  },
})
const monthly = (theme) => ({
  type: 'bar',
  data: {
    labels: detail.value.months.map((m) => m.label.slice(5) + '/' + m.label.slice(2, 4)),
    datasets: [{ label: label(selected.value), data: detail.value.months.map((m) => m.expenseCents), backgroundColor: color(Math.max(index.value, 0)), borderRadius: 4, maxBarThickness: 28 }],
  },
  options: {
    responsive: true,
    maintainAspectRatio: false,
    plugins: { legend: { display: false }, tooltip: { callbacks: { label: (i) => money(i.raw) } } },
    scales: {
      x: { grid: { display: false }, border: { display: false }, ticks: { color: theme.text } },
      y: { grid: { color: theme.grid }, border: { display: false }, ticks: { color: theme.text, maxTicksLimit: 4, callback: (v) => money(Math.round(v)) } },
    },
  },
})
watch(() => [props.from, props.to], load)
onMounted(load)
</script>
<template>
  <div v-if="error" class="banner error" role="alert">{{ error }}</div>
  <div v-if="loading && !rows.length" class="loading-line"><Icon name="loading" />Carregando categorias…</div>
  <p v-else-if="!rows.length" class="muted">Sem despesas no período selecionado.</p>
  <div v-else class="category-layout">
    <section class="panel">
      <div class="panel-heading"><div><h2>Gastos por categoria</h2><p>Despesas do período · clique para detalhar</p></div></div>
      <div class="donut-wrap">
        <BaseChart :build="donut" :watch-source="[rows, selected]" label="Despesas por categoria" />
        <div class="donut-center"><span>Total</span><strong>{{ money(total) }}</strong></div>
      </div>
      <ul class="category-list">
        <li v-for="(r, i) in rows" :key="r.label">
          <button :class="{ active: r.label === selected }" @click="select(r.label)">
            <i :style="{ background: color(i) }" /><span>{{ label(r.label) }}</span><small>{{ share(r) }}</small><b>{{ money(r.expenseCents) }}</b>
          </button>
        </li>
      </ul>
    </section>
    <section v-if="current" class="panel">
      <div class="panel-heading category-nav">
        <button class="icon-button" aria-label="Categoria anterior" @click="step(-1)"><Icon name="left" /></button>
        <div>
          <h2>{{ label(current.label) }}</h2>
          <p>{{ money(current.expenseCents) }} · {{ share(current) }} dos gastos · {{ index + 1 }} de {{ rows.length }}</p>
        </div>
        <button class="icon-button" aria-label="Próxima categoria" @click="step(1)"><Icon name="right" /></button>
      </div>
      <h3 class="subhead">Últimos 6 meses</h3>
      <BaseChart :build="monthly" :watch-source="[detail.months, selected]" :label="'Gastos mensais em ' + label(current.label)" />
      <h3 class="subhead">Lançamentos no período ({{ detail.total }})</h3>
      <ul class="movement-list">
        <li v-for="e in detail.entries" :key="e.id">
          <span>{{ e.description }}<small>{{ timestamp(e.occurredAt) }}</small></span><b>− {{ money(e.amountCents) }}</b>
        </li>
      </ul>
    </section>
  </div>
</template>
