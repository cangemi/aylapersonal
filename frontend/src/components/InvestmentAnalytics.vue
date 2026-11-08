<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { api } from '../lib/api'
import { money, date, today } from '../lib/format'
import { assetLabels, combineSeries, groupHoldings, periodStatus, sumPeriods } from '../lib/investmentAnalytics'
import BaseChart from './BaseChart.vue'
import Icon from './Icon.vue'
import InvestmentPeriodTable from './InvestmentPeriodTable.vue'

const props = defineProps({ snapshot: Object })
const data = ref(null), loading = ref(false), error = ref(''), root = ref(null)
const initialDate = today(), currentDate = computed(() => data.value?.asOf || initialDate)
const currentYear = computed(() => Number(currentDate.value.slice(0, 4)))
const year = ref(currentYear.value), month = ref(currentDate.value.slice(5, 7)), asset = ref('all'), bank = ref('all'), position = ref('all')
const grouping = ref('assetType'), comparisonPeriod = ref('month'), evolutionMode = ref('balance')
let revision = 0
const monthNames = ['Janeiro', 'Fevereiro', 'Março', 'Abril', 'Maio', 'Junho', 'Julho', 'Agosto', 'Setembro', 'Outubro', 'Novembro', 'Dezembro']
const palette = ['#b18cfa', '#81c6ac', '#edb68a', '#76b5da', '#e69bc2', '#c6cd88', '#9f9bed', '#e39595', '#69c5be', '#be9f7c', '#88a8e1', '#cda5df', '#abd2c1', '#c6b8a0']
const pct = (value) => value == null ? '—' : Number(value).toLocaleString('pt-BR', { maximumFractionDigits: 2 }) + '%'
const monthLabel = (key) => `${monthNames[Number(key.slice(5, 7)) - 1].slice(0, 3)}/${key.slice(0, 4)}`
const availableYears = computed(() => Array.from({ length: currentYear.value - Math.min(data.value?.firstYear || currentYear.value, year.value) + 1 }, (_, i) => currentYear.value - i))
const availableMonths = computed(() => monthNames.map((label, index) => ({ label, key: String(index + 1).padStart(2, '0') }))
  .filter((item) => year.value < currentYear.value || item.key <= currentDate.value.slice(5, 7)))
const institutions = computed(() => [...new Set((data.value?.holdings || []).map((h) => h.institution))].sort())
const types = computed(() => [...new Set((data.value?.holdings || []).map((h) => h.assetType))])
const eligible = computed(() => (data.value?.holdings || []).filter((h) => (asset.value === 'all' || h.assetType === asset.value) && (bank.value === 'all' || h.institution === bank.value)))
const holdings = computed(() => eligible.value.filter((h) => position.value === 'all' || h.id === position.value))
const scope = computed(() => position.value !== 'all' ? holdings.value[0]?.name || 'Aplicação selecionada'
  : [asset.value === 'all' ? 'Todos os tipos' : assetLabels[asset.value], bank.value === 'all' ? 'Todas as instituições' : bank.value].join(' · '))
const totalBalance = computed(() => holdings.value.reduce((sum, h) => sum + h.balanceCents, 0))
const totalGain = computed(() => holdings.value.reduce((sum, h) => sum + h.gainCents, 0))
const day = computed(() => sumPeriods(holdings.value.map((h) => h.performance.day)))
const currentMonth = computed(() => sumPeriods(holdings.value.map((h) => h.performance.month)))
const selectedYear = computed(() => sumPeriods(holdings.value.map((h) => h.performance.year)))
const days = computed(() => combineSeries(holdings.value, 'days'))
const daily = computed(() => days.value.filter((p) => p.key.startsWith(`${year.value}-${month.value}`)))
const months = computed(() => combineSeries(holdings.value, 'months').map((p) => ({ ...p, label: monthLabel(p.key) })))
const years = computed(() => combineSeries(holdings.value, 'years'))
const groups = computed(() => groupHoldings(holdings.value, grouping.value))
const comparisonGroups = computed(() => [...groups.value].sort((a, b) => {
  const left = a[comparisonPeriod.value], right = b[comparisonPeriod.value]
  if (left.complete !== right.complete) return left.complete ? -1 : 1
  return (right.gainCents ?? right.observedGainCents) - (left.gainCents ?? left.observedGainCents)
}))
const incomplete = computed(() => holdings.value.filter((h) => !h.performance.day?.complete))
const summaries = computed(() => [
  { label: 'Saldo acompanhado no filtro', value: money(totalBalance.value), detail: 'Saldos informados e estimativas atuais' },
  { label: 'Ganho acumulado no filtro', value: money(totalGain.value), detail: 'Inclui ganho inicial e proventos; não é ganho do período' },
  { label: `Rendimento de hoje · ${date(data.value?.asOf)}`, period: day.value },
  { label: `Mês vigente · 01/${currentDate.value.slice(5, 7)} até hoje`, period: currentMonth.value },
  { label: year.value === currentYear.value ? `Ano ${year.value} · 01/01 até hoje` : `Rendimento de ${year.value}`, period: selectedYear.value },
  { label: `Proventos recebidos em ${year.value}`, value: money(selectedYear.value.incomeCents), detail: 'Já incluídos no rendimento; não são somados novamente' },
])

async function load() {
  const request = ++revision
  loading.value = true
  error.value = ''
  try {
    const result = await api(`/investments/analytics?year=${year.value}`)
    if (request !== revision) return
    if (!Array.isArray(result.holdings)) throw new Error('Não foi possível ler a análise dos investimentos.')
    data.value = result
    if (position.value !== 'all' && !eligible.value.some((h) => h.id === position.value)) position.value = 'all'
  } catch (e) { if (request === revision) error.value = e.message }
  finally { if (request === revision) loading.value = false }
}
watch([() => props.snapshot, year], load, { immediate: true })
onBeforeUnmount(() => { revision++ })
watch(year, () => { if (!availableMonths.value.some((m) => m.key === month.value)) month.value = availableMonths.value.at(-1)?.key || '01' })
function focus(id) { asset.value = 'all'; bank.value = 'all'; position.value = id; root.value?.scrollIntoView({ behavior: 'smooth', block: 'start' }) }
defineExpose({ focus })
function resetFilters() { asset.value = 'all'; bank.value = 'all'; position.value = 'all' }
function showCurrentMonth() { year.value = currentYear.value; month.value = currentDate.value.slice(5, 7) }
const periodValue = (p) => p?.gainCents == null ? '—' : money(p.gainCents)
const status = (p) => `${periodStatus(p)}${p?.gainPercent != null ? ' · ' + pct(p.gainPercent) + ' da base' : ''}`

function cartesian(theme, horizontal = false) {
  return { responsive: true, maintainAspectRatio: false, indexAxis: horizontal ? 'y' : 'x',
    plugins: { legend: { labels: { color: theme.text } }, tooltip: { callbacks: { label: (item) => `${item.dataset.label}: ${money(item.raw)}` } } },
    scales: { x: { ticks: { color: theme.text, maxTicksLimit: 8, ...(horizontal ? { callback: (v) => money(Math.round(v)) } : {}) }, grid: { color: theme.grid } },
      y: { ticks: { color: theme.text, ...(!horizontal ? { callback: (v) => money(Math.round(v)) } : {}) }, grid: { color: theme.grid } } } }
}
function returnChart(theme, rows, labels) {
  const options = cartesian(theme)
  options.plugins.tooltip.callbacks.afterLabel = (item) => status(rows[item.dataIndex])
  return { type: 'bar', data: { labels, datasets: [
    { label: 'Rendimento com base suficiente', data: rows.map((p) => p.gainCents),
      backgroundColor: rows.map((p) => p.gainCents < 0 ? '#e39595' : '#81c6ac'), borderRadius: 3, maxBarThickness: 30 },
    { label: 'Variação registrada · dados incompletos', data: rows.map((p) => !p.complete ? p.observedGainCents : null),
      backgroundColor: '#edb68a', borderRadius: 3, maxBarThickness: 30 },
  ] }, options }
}
const dailyChart = (theme) => returnChart(theme, daily.value, daily.value.map((p) => p.key.slice(8)))
const monthlyChart = (theme) => returnChart(theme, months.value, months.value.map((p) => p.label))
const yearlyChart = (theme) => returnChart(theme, years.value, years.value.map((p) => p.key))
const evolutionChart = (theme) => ({ type: 'line', data: {
  labels: days.value.map((p) => date(p.key)), datasets: evolutionMode.value === 'gain' ? [
    { label: 'Ganho acumulado registrado', data: days.value.map((p) => p.accumulatedGainCents), borderColor: '#81c6ac', pointRadius: 0, tension: .1 },
  ] : [
    { label: 'Saldo registrado / estimado', data: days.value.map((p) => p.balanceCents), borderColor: '#b18cfa', backgroundColor: 'rgba(177,140,250,.1)', fill: true, pointRadius: 0, tension: .1 },
    { label: 'Capital líquido aplicado', data: days.value.map((p) => p.netInvestedCents), borderColor: '#81c6ac', borderDash: [5, 4], pointRadius: 0 },
  ], }, options: cartesian(theme) })
const flowChart = (theme) => ({ type: 'bar', data: { labels: months.value.map((p) => p.label), datasets: [
  { label: 'Aportes', data: months.value.map((p) => p.depositsCents), backgroundColor: '#b18cfa', borderRadius: 3 },
  { label: 'Resgates', data: months.value.map((p) => -p.withdrawalsCents), backgroundColor: '#e39595', borderRadius: 3 },
  { label: 'Proventos pagos', data: months.value.map((p) => p.incomeCents), backgroundColor: '#76b5da', borderRadius: 3 },
] }, options: cartesian(theme) })
const allocationChart = (theme) => ({ type: 'doughnut', data: { labels: groups.value.map((g) => g.name), datasets: [
  { data: groups.value.map((g) => g.balanceCents), backgroundColor: groups.value.map((_, i) => palette[i % palette.length]), borderWidth: 0 },
] }, options: { responsive: true, maintainAspectRatio: false, cutout: '68%', plugins: {
  legend: { position: 'bottom', labels: { color: theme.text, boxWidth: 12, padding: 15 } },
  tooltip: { callbacks: { label: (i) => `${i.label}: ${money(i.raw)} · ${pct(totalBalance.value ? i.raw * 100 / totalBalance.value : 0)}` } },
} } })
const comparisonChart = (theme) => ({ type: 'bar', data: { labels: comparisonGroups.value.map((g) => g.name), datasets: [{
  label: comparisonPeriod.value === 'day' ? 'Hoje' : comparisonPeriod.value === 'month' ? 'Mês vigente até hoje' : `Ano ${year.value}`,
  data: comparisonGroups.value.map((g) => g[comparisonPeriod.value].gainCents ?? g[comparisonPeriod.value].observedGainCents),
  backgroundColor: comparisonGroups.value.map((g) => !g[comparisonPeriod.value].complete ? '#edb68a' : g[comparisonPeriod.value].gainCents < 0 ? '#e39595' : '#81c6ac'), borderRadius: 3,
}] }, options: { ...cartesian(theme, true), plugins: { ...cartesian(theme).plugins, tooltip: { callbacks: {
  label: (item) => `${comparisonGroups.value[item.dataIndex][comparisonPeriod.value].complete ? 'Rendimento' : 'Variação registrada (incompleta)'}: ${money(item.raw)}`,
  afterLabel: (item) => status(comparisonGroups.value[item.dataIndex][comparisonPeriod.value]),
} } } } })

function downloadCsv() {
  const cell = (value) => `"${String(typeof value === 'string' && /^\s*[=+@\-\t\r]/.test(value) && !/^-?\d+(?:,\d+)?$/.test(value) ? "'" + value : value ?? '').replaceAll('"', '""')}"`
  const reais = (value) => value == null ? '' : (value / 100).toFixed(2).replace('.', ',')
  const rows = [['Data', 'Aplicação', 'Instituição', 'Tipo', 'Saldo (R$)', 'Capital líquido (R$)', 'Rendimento (R$)', 'Variação registrada (R$)', 'Aportes (R$)', 'Resgates (R$)', 'Proventos (R$)', 'Base suficiente', 'Histórico parcial', 'Estimado']]
  for (const holding of holdings.value) for (const period of holding.performance.days) rows.push([
    period.key, holding.name, holding.institution, assetLabels[holding.assetType], reais(period.balanceCents), reais(period.netInvestedCents), reais(period.gainCents),
    reais(period.observedGainCents), reais(period.depositsCents), reais(period.withdrawalsCents), reais(period.incomeCents),
    period.complete ? 'Sim' : 'Não', period.partial ? 'Sim' : 'Não', period.estimated ? 'Sim' : 'Não',
  ])
  const url = URL.createObjectURL(new Blob(['\uFEFF' + rows.map((row) => row.map(cell).join(';')).join('\r\n')], { type: 'text/csv;charset=utf-8' }))
  const link = document.createElement('a'); link.href = url; link.download = `investimentos-${year.value}.csv`; link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
</script>

<template>
  <section ref="root" class="investment-analytics" aria-label="Análise completa dos investimentos">
    <div class="panel performance-controls">
      <div class="panel-heading"><div><span class="eyebrow">RENDIMENTOS E EVOLUÇÃO</span><h2>Análise da carteira</h2><p>Dia, mês vigente até hoje e anos anteriores. Aportes e resgates ficam separados do rendimento.</p></div>
        <button class="button secondary" :disabled="loading || !holdings.length" @click="downloadCsv"><Icon name="download" :size="16" />Exportar CSV</button></div>
      <div class="performance-filters">
        <label>Ano da análise<select v-model.number="year"><option v-for="value in availableYears" :key="value" :value="value">{{ value }}{{ value === currentYear ? ' · até hoje' : '' }}</option></select></label>
        <label>Tipo de investimento<select v-model="asset" @change="position = 'all'"><option value="all">Todos os tipos</option><option v-for="type in types" :key="type" :value="type">{{ assetLabels[type] || type }}</option></select></label>
        <label>Instituição<select v-model="bank" @change="position = 'all'"><option value="all">Todas as instituições</option><option v-for="value in institutions" :key="value">{{ value }}</option></select></label>
        <label>Aplicação<select v-model="position"><option value="all">Todas as aplicações do filtro</option><option v-for="holding in eligible" :key="holding.id" :value="holding.id">{{ holding.name }} · {{ holding.institution }}</option></select></label>
      </div>
      <div class="performance-presets"><button class="text-link" @click="showCurrentMonth">Mês vigente</button><button class="text-link" @click="year = currentYear">Ano atual</button><button class="text-link" @click="resetFilters">Limpar filtros</button><span>{{ scope }}</span></div>
      <p v-if="data?.cdiDate" class="footnote">Posição em {{ date(data.asOf) }} · último CDI disponível: {{ date(data.cdiDate) }}.</p>
    </div>
    <div v-if="loading" class="loading-line" role="status"><Icon name="loading" />Calculando o histórico…</div>
    <div v-if="error" class="banner error" role="alert">{{ error }} <button class="text-link" @click="load">Tentar novamente</button></div>
    <template v-if="data && !loading && !error">
      <div v-for="warning in data.warnings" :key="warning" class="banner">{{ warning }}</div>
      <p v-if="!holdings.length" class="muted">Nenhuma aplicação neste filtro. Cadastre um investimento ou altere os filtros para começar a análise.</p>
      <template v-else>
        <div class="stats-grid performance-stats"><article v-for="item in summaries" :key="item.label" class="stat-card">
          <span>{{ item.label }}</span><strong>{{ item.period ? periodValue(item.period) : item.value }}</strong>
          <small>{{ item.period ? status(item.period) : item.detail }}</small>
          <small v-if="item.period && !item.period.complete && item.period.count">Variação registrada: {{ money(item.period.observedGainCents) }} · não define o rendimento completo.</small>
        </article></div>
        <div v-if="incomplete.length" class="banner performance-coverage">
          {{ incomplete.length }} aplicação(ões) sem base completa para o rendimento de hoje. CDI ainda não publicado e saldos manuais desatualizados ficam identificados; os gráficos não assumem rendimento zero.
          <details><summary>Ver cobertura por aplicação</summary><ul><li v-for="holding in incomplete" :key="holding.id"><b>{{ holding.name }}</b> · último saldo informado em {{ date(holding.performance.lastValuationDate) }} · acompanha desde {{ date(holding.performance.trackingFrom) }}.</li></ul></details>
        </div>
        <section class="panel"><div class="panel-heading"><div><h3>Evolução em {{ year }}</h3><p>Saldos registrados ou estimados ao longo do ano selecionado.</p></div>
          <label class="performance-inline">Visualizar<select v-model="evolutionMode"><option value="balance">Saldo e capital aplicado</option><option value="gain">Ganho acumulado registrado</option></select></label></div>
          <BaseChart v-if="days.length" :build="evolutionChart" :watch-source="[days, evolutionMode]" label="Evolução patrimonial no ano selecionado" />
          <p v-else class="muted">Sem histórico para este ano.</p>
          <p class="footnote">Saldos manuais permanecem no último valor informado até a próxima atualização. O ganho acumulado inclui a diferença entre saldo e capital inicial; essa diferença não é atribuída a um dia, mês ou ano específico.</p>
        </section>
        <div class="performance-chart-grid">
          <section class="panel"><div class="panel-heading"><div><h3>Rendimento diário</h3><p>{{ monthNames[Number(month) - 1] }} de {{ year }}{{ year === currentYear && month === currentDate.slice(5, 7) ? ' · do dia 1 até hoje' : '' }}</p></div>
            <label class="performance-inline">Mês do gráfico<select v-model="month"><option v-for="item in availableMonths" :key="item.key" :value="item.key">{{ item.label }}</option></select></label></div>
            <BaseChart v-if="daily.length" :build="dailyChart" :watch-source="daily" label="Rendimento de cada dia do mês escolhido" /><p v-else class="muted">Sem histórico neste mês.</p>
          </section>
          <section class="panel"><div class="panel-heading"><div><h3>Rendimento mensal de {{ year }}</h3><p>Somente meses decorridos; o mês atual termina hoje.</p></div></div>
            <BaseChart v-if="months.length" :build="monthlyChart" :watch-source="months" label="Rendimento por mês no ano escolhido" /><p v-else class="muted">Sem histórico neste ano.</p>
          </section>
          <section class="panel"><div class="panel-heading"><div><h3>Rendimento por ano</h3><p>Todo o histórico cadastrado; ano atual até hoje.</p></div></div>
            <BaseChart v-if="years.length" :build="yearlyChart" :watch-source="years" label="Comparação dos rendimentos de todos os anos" />
          </section>
          <section class="panel"><div class="panel-heading"><div><h3>Aportes, resgates e proventos</h3><p>Movimentações em {{ year }} após o saldo de partida.</p></div></div>
            <BaseChart v-if="months.length" :build="flowChart" :watch-source="months" label="Fluxos mensais separados dos rendimentos" />
          </section>
        </div>
        <div class="panel"><div class="panel-heading"><div><h3>Distribuição e comparação</h3><p>Os mesmos filtros valem para gráficos, indicadores e tabelas.</p></div>
          <label class="performance-inline">Agrupar por<select v-model="grouping"><option value="assetType">Tipo de investimento</option><option value="institution">Instituição</option></select></label></div>
          <div class="performance-chart-grid performance-inner-grid">
            <section><h4>Onde está o saldo atual</h4><BaseChart v-if="totalBalance > 0" :build="allocationChart" :watch-source="[groups, grouping]" label="Distribuição do patrimônio por tipo ou instituição" /><p v-else class="muted">Sem saldo positivo para distribuir.</p></section>
            <section><div class="panel-heading"><h4>O que rendeu mais ou menos</h4><label class="performance-inline">Comparar rendimento<select v-model="comparisonPeriod"><option value="day">Hoje</option><option value="month">Mês vigente até hoje</option><option value="year">Ano {{ year }}</option></select></label></div>
              <BaseChart :build="comparisonChart" :watch-source="[groups, comparisonPeriod]" label="Comparação de rendimentos por tipo ou instituição" /></section>
          </div>
          <div class="performance-table-wrap" tabindex="0" aria-label="Rendimentos por tipo ou instituição"><table class="performance-table"><thead><tr><th scope="col">{{ grouping === 'assetType' ? 'Tipo' : 'Instituição' }}</th><th scope="col">Saldo atual</th><th scope="col">Participação</th><th scope="col">Hoje</th><th scope="col">Mês vigente até hoje</th><th scope="col">Ano {{ year }}</th></tr></thead><tbody>
            <tr v-for="group in groups" :key="group.key"><th scope="row">{{ group.name }}</th><td>{{ money(group.balanceCents) }}</td><td>{{ pct(totalBalance ? group.balanceCents * 100 / totalBalance : 0) }}</td>
              <td v-for="field in ['day', 'month', 'year']" :key="field">{{ periodValue(group[field]) }}<small>{{ status(group[field]) }}</small><small v-if="!group[field].complete">{{ money(group[field].observedGainCents) }} registrados</small></td></tr>
          </tbody></table></div>
        </div>
        <section class="panel"><div class="panel-heading"><div><h3>Resultado de cada aplicação</h3><p>Rendimento de hoje, do início do mês vigente até hoje e do ano selecionado.</p></div></div>
          <div class="performance-table-wrap" tabindex="0" aria-label="Rendimento por aplicação"><table class="performance-table"><thead><tr><th scope="col">Aplicação</th><th scope="col">Instituição / tipo</th><th scope="col">Saldo atual</th><th scope="col">Hoje</th><th scope="col">Mês vigente</th><th scope="col">Ano {{ year }}</th><th scope="col">Início do acompanhamento</th></tr></thead><tbody>
            <tr v-for="holding in holdings" :key="holding.id"><th scope="row">{{ holding.name }}</th><td>{{ holding.institution }}<small>{{ assetLabels[holding.assetType] }}</small></td><td>{{ money(holding.balanceCents) }}</td>
              <td v-for="field in ['day', 'month', 'year']" :key="field">{{ holding.performance[field]?.gainCents == null ? '—' : money(holding.performance[field].gainCents) }}
                <small>{{ status(sumPeriods([holding.performance[field]])) }}</small></td><td>{{ date(holding.performance.trackingFrom) }}</td></tr>
          </tbody></table></div>
        </section>
        <section class="panel"><h3>Detalhamento dos períodos</h3>
          <details open><summary>Resultados anuais · todo o histórico</summary><InvestmentPeriodTable :rows="years" label="Resultados anuais dos investimentos" heading="Ano" /></details>
          <details open><summary>Resultados mensais · {{ year }}</summary><InvestmentPeriodTable :rows="months" label="Resultados mensais dos investimentos" heading="Mês" /></details>
          <details><summary>Resultados diários · {{ monthNames[Number(month) - 1] }} de {{ year }}</summary><InvestmentPeriodTable :rows="daily.map((p) => ({ ...p, label: date(p.key) }))" label="Resultados diários dos investimentos" heading="Dia" /></details>
        </section>
        <p class="footnote performance-method">Rendimento = variação do saldo − aportes + resgates + proventos pagos. Ganhos anteriores ao saldo de partida não entram nos rendimentos por período. “Ganho/base” usa o saldo no início do acompanhamento dentro do período mais seus aportes; não é taxa anualizada nem TIR. Barras em laranja mostram apenas variações registradas em intervalos sem base suficiente. Para aplicações manuais, o retorno do período exige saldos nas duas pontas; uma atualização isolada não revela em qual dia ocorreu a valorização. CDI e taxas informadas são estimativas brutas, sem impostos, taxas ou marcação a mercado.</p>
      </template>
    </template>
  </section>
</template>

<style scoped>
.investment-analytics { scroll-margin-top: 1rem; margin: 1.5rem 0; min-width: 0; }
.performance-filters { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 1rem; }
.performance-filters label, .performance-inline { display: flex; flex-direction: column; gap: .4rem; font-size: .8rem; color: var(--muted); }
.performance-filters select { min-width: 0; width: 100%; }
.performance-presets { display: flex; flex-wrap: wrap; align-items: center; gap: 1rem; margin-top: .7rem; }
.performance-presets span { margin-left: auto; color: var(--muted); font-size: .8rem; }
.performance-stats .stat-card { min-width: 0; }
.performance-stats strong { font-size: 1.6rem; margin: .5rem 0; }
.performance-stats > article > span { color: var(--muted); font-size: .85rem; }
.performance-chart-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 1.25rem; }
.performance-chart-grid > section { min-width: 0; }
.performance-inner-grid { margin-bottom: 1rem; }
.performance-inline { min-width: 130px; }
.performance-inline select { max-width: 260px; }
.performance-coverage details { margin-top: .5rem; }
.performance-coverage ul { margin: .5rem 0; padding-left: 1.2rem; }
summary { cursor: pointer; padding: .9rem 0; color: var(--text); font-size: .85rem; }
.performance-method { margin: 1rem 0; line-height: 1.7; }
@media(max-width: 1000px) { .performance-filters { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media(max-width: 700px) { .performance-chart-grid { grid-template-columns: minmax(0, 1fr); } .performance-presets span { margin-left: 0; width: 100%; } .performance-filters { gap: .65rem; } .performance-controls { padding: 1rem; } }
</style>
