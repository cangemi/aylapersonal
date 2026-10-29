<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../lib/api'
import { money } from '../lib/format'
import Icon from './Icon.vue'
import BaseChart from './BaseChart.vue'
const data = ref(null),
  error = ref(''),
  months = ref(6)
const label = (c) => (c ? c.charAt(0).toUpperCase() + c.slice(1) : 'Sem categoria')
const monthName = (m) => ['jan', 'fev', 'mar', 'abr', 'mai', 'jun', 'jul', 'ago', 'set', 'out', 'nov', 'dez'][+m.slice(5) - 1] + '/' + m.slice(2, 4)
const pct = (v) => (v == null ? '—' : (v > 0 ? '+' : '') + v.toFixed(1).replace('.', ',') + '%')
const trendText = { UP: 'Seus gastos mensais estão subindo', DOWN: 'Seus gastos mensais estão caindo', STABLE: 'Seus gastos mensais estão estáveis' }
async function load() {
  error.value = ''
  try {
    data.value = await api('/finance/insights?months=' + months.value)
  } catch (e) {
    error.value = e.message
  }
}
const axes = (theme, fmt = (v) => money(Math.round(v))) => ({
  x: { grid: { display: false }, border: { display: false }, ticks: { color: theme.text, maxTicksLimit: 8 } },
  y: { grid: { color: theme.grid }, border: { display: false }, ticks: { color: theme.text, maxTicksLimit: 5, callback: fmt } },
})
const tip = { tooltip: { mode: 'index', intersect: false, callbacks: { label: (i) => `${i.dataset.label}: ${money(i.raw)}` } } }
const monthlyChart = (theme) => ({
  type: 'bar',
  data: {
    labels: data.value.months.map((m) => monthName(m.label)),
    datasets: [
      { type: 'bar', label: 'Receitas', data: data.value.months.map((m) => m.incomeCents), backgroundColor: '#81c6ac', borderRadius: 4, maxBarThickness: 22 },
      { type: 'bar', label: 'Despesas', data: data.value.months.map((m) => m.expenseCents), backgroundColor: '#b7a0ef', borderRadius: 4, maxBarThickness: 22 },
      { type: 'bar', label: 'Investido', data: data.value.months.map((m) => m.investedCents), backgroundColor: '#e0a46f', borderRadius: 4, maxBarThickness: 22 },
      { type: 'line', label: 'Tendência das despesas', data: data.value.trend.lineCents, borderColor: '#e06f8f', borderDash: [6, 4], pointRadius: 0, borderWidth: 2 },
    ],
  },
  options: { responsive: true, maintainAspectRatio: false, interaction: { mode: 'index', intersect: false }, plugins: { legend: { labels: { color: theme.text, boxWidth: 10 } }, ...tip }, scales: axes(theme) },
})
const cumulativeChart = (theme) => {
  const c = data.value.cumulative, n = c.previousCents.length
  return {
    type: 'line',
    data: {
      labels: Array.from({ length: n }, (_, i) => i + 1),
      datasets: [
        { label: 'Este mês', data: c.currentCents, borderColor: '#8a6fe0', backgroundColor: 'rgba(138,111,224,.15)', fill: true, pointRadius: 0, tension: 0.2, borderWidth: 2 },
        { label: 'Mês anterior', data: c.previousCents, borderColor: '#9aa5b1', borderDash: [5, 4], pointRadius: 0, tension: 0.2, borderWidth: 2 },
      ],
    },
    options: { responsive: true, maintainAspectRatio: false, interaction: { mode: 'index', intersect: false }, plugins: { legend: { labels: { color: theme.text, boxWidth: 10 } }, ...tip }, scales: axes(theme) },
  }
}
const weekdayChart = (theme) => ({
  type: 'bar',
  data: {
    labels: ['Seg', 'Ter', 'Qua', 'Qui', 'Sex', 'Sáb', 'Dom'],
    datasets: [{ label: 'Gastos', data: data.value.weekdayExpenseCents, backgroundColor: '#8a6fe0', borderRadius: 4, maxBarThickness: 28 }],
  },
  options: { responsive: true, maintainAspectRatio: false, plugins: { legend: { display: false }, ...tip }, scales: axes(theme) },
})
const growth = (c) => (c.previousCents ? ((c.currentCents - c.previousCents) * 100) / c.previousCents : null)
onMounted(load)
</script>
<template>
  <div v-if="error" class="banner error" role="alert">{{ error }}<button class="text-link" @click="load">Tentar novamente</button></div>
  <div v-else-if="!data" class="loading-line"><Icon name="loading" />Carregando análises…</div>
  <template v-else>
    <div class="stats-grid">
      <article class="stat-card">
        <div class="stat-top"><span>Gasto no mês</span><span class="stat-icon peach"><Icon name="expense" :size="18" /></span></div>
        <strong>{{ money(data.current.expenseCents) }}</strong>
        <small>Média de {{ money(data.current.dailyAverageCents) }} por dia · {{ pct(data.current.changePercent) }} vs. mesmo período do mês anterior</small>
      </article>
      <article class="stat-card">
        <div class="stat-top"><span>Projeção do mês</span><span class="stat-icon lavender"><Icon name="trend" :size="18" /></span></div>
        <strong>{{ money(data.current.projectedExpenseCents) }}</strong>
        <small>Mês passado fechou em {{ money(data.current.previousMonthExpenseCents) }}</small>
      </article>
      <article class="stat-card">
        <div class="stat-top"><span>Taxa de poupança</span><span class="stat-icon mint"><Icon name="wallet" :size="18" /></span></div>
        <strong>{{ data.current.savingsRatePercent == null ? '—' : data.current.savingsRatePercent.toFixed(1).replace('.', ',') + '%' }}</strong>
        <small>Investido no mês: {{ money(data.current.investedCents) }}</small>
      </article>
    </div>
    <section class="panel">
      <div class="panel-heading">
        <div><h2>Receitas, despesas e tendência</h2><p>{{ trendText[data.trend.direction] }}<span v-if="data.trend.direction !== 'STABLE'"> (cerca de {{ money(Math.abs(data.trend.slopeCentsPerMonth)) }} por mês)</span></p></div>
        <div class="segmented range-toggle">
          <button v-for="m in [3, 6, 12]" :key="m" :class="{ selected: months === m }" @click="months = m; load()">{{ m }} meses</button>
        </div>
      </div>
      <BaseChart :build="monthlyChart" :watch-source="data" label="Receitas, despesas e linha de tendência por mês" />
      <p class="footnote">Despesas não incluem aportes em investimento; eles aparecem em “Investido”. O mês atual usa a projeção na linha de tendência.</p>
    </section>
    <div class="invest-grid">
      <section class="panel">
        <div class="panel-heading"><div><h2>Ritmo do mês</h2><p>Gasto acumulado dia a dia</p></div></div>
        <BaseChart :build="cumulativeChart" :watch-source="data.cumulative" label="Gasto acumulado do mês comparado ao anterior" />
      </section>
      <section class="panel">
        <div class="panel-heading"><div><h2>Dias da semana</h2><p>Onde você mais gasta · últimos 90 dias</p></div></div>
        <BaseChart :build="weekdayChart" :watch-source="data.weekdayExpenseCents" label="Gastos por dia da semana" />
      </section>
    </div>
    <section class="panel">
      <div class="panel-heading"><div><h2>O que mudou nas categorias</h2><p>Este mês até hoje vs. mesmo período do mês anterior</p></div></div>
      <ul class="movement-list">
        <li v-for="c in data.categories" :key="c.category">
          <span><strong>{{ label(c.category) }}</strong><small>Antes: {{ money(c.previousCents) }}</small></span>
          <b>{{ money(c.currentCents) }} <small :class="growth(c) > 0 ? 'negative' : 'positive'">{{ pct(growth(c)) }}</small></b>
        </li>
      </ul>
      <p v-if="!data.categories.length" class="muted">Sem despesas neste mês ainda.</p>
    </section>
  </template>
</template>
