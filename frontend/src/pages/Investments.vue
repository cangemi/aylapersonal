<script setup>
import { computed, onMounted, ref } from 'vue'
import { api, write } from '../lib/api'
import { money, cents, moneyInput, today, date } from '../lib/format'
import { toast } from '../lib/ui'
import Icon from '../components/Icon.vue'
import Modal from '../components/Modal.vue'
import MaskedInput from '../components/MaskedInput.vue'
import BaseChart from '../components/BaseChart.vue'
import EmptyState from '../components/EmptyState.vue'
import LegacyInvestment from '../components/LegacyInvestment.vue'
import InvestmentAnalytics from '../components/InvestmentAnalytics.vue'
import { assetLabels } from '../lib/investmentAnalytics'

const portfolio = ref(null), loading = ref(false), error = ref(''), selectedId = ref('')
const form = ref(null), movement = ref(null), removal = ref(null), saving = ref(false), formError = ref('')
const types = assetLabels
const analysisPanel = ref(null)
const rates = { CDI: '% do CDI', ANNUAL: 'Taxa anual informada', MONTHLY: 'Taxa mensal informada', MANUAL: 'Valor atualizado manualmente' }
const eventTypes = { DEPOSIT: 'Aporte', WITHDRAWAL: 'Resgate', INCOME: 'Provento recebido', VALUATION: 'Atualização de saldo', RATE_CHANGE: 'Alteração de taxa' }
const selected = computed(() => portfolio.value?.positions.find((item) => item.position.id === selectedId.value))
const eventHistory = computed(() => [...(selected.value?.events || [])].reverse())
const allocation = computed(() => {
  const banks = new Map()
  for (const item of portfolio.value?.positions || [])
    banks.set(item.position.institution, (banks.get(item.position.institution) || 0) + item.balanceCents)
  if (portfolio.value?.legacy) banks.set('Nubank · caixinha existente', portfolio.value.legacy.balanceCents)
  return [...banks].sort((a, b) => b[1] - a[1])
})
const percent = (value) => Number(value || 0).toLocaleString('pt-BR', { maximumFractionDigits: 4 }) + '%'
const rateLabel = (rate) => rate.type === 'MANUAL' ? 'Saldo informado' : rate.type === 'CDI'
  ? `${percent(rate.percent)} do CDI` : `${percent(rate.percent)} ${rate.type === 'ANNUAL' ? 'a.a.' : 'a.m.'}`

async function load() {
  loading.value = true
  error.value = ''
  try {
    portfolio.value = await api('/investments/portfolio')
    if (!portfolio.value.positions.some((item) => item.position.id === selectedId.value) && selectedId.value !== 'legacy')
      selectedId.value = portfolio.value.positions[0]?.position.id || ''
  } catch (e) { error.value = e.message }
  finally { loading.value = false }
}

function edit(item = null) {
  formError.value = ''
  const p = item?.position, rate = item?.currentRate
  form.value = { id: p?.id, version: p?.version, requestKey: crypto.randomUUID(), name: p?.name || '', institution: p?.institution || '',
    assetType: p?.assetType || 'CAIXINHA', ticker: p?.ticker || '', openedOn: p?.openedOn || today(),
    balance: p ? moneyInput(p.openingBalanceCents) : '', invested: p ? moneyInput(p.openingInvestedCents) : '',
    rateType: rate?.type || 'CDI', ratePercent: String(rate?.percent ?? 100).replace('.', ','),
    dayBasis: rate?.dayBasis || 'BUSINESS', maturityDate: p?.maturityDate || '', notes: p?.notes || '' }
}
function selectType() {
  if (['ACAO', 'FII', 'ETF', 'FUNDO', 'PREVIDENCIA', 'CRIPTO', 'POUPANCA', 'TESOURO', 'OUTRO'].includes(form.value.assetType))
    form.value.rateType = 'MANUAL'
}
async function savePosition() {
  saving.value = true
  formError.value = ''
  try {
    const f = form.value
    const balance = cents(f.balance, { allowZero: true })
    const rate = f.rateType === 'MANUAL' ? 0 : Number(String(f.ratePercent).replace(',', '.'))
    if (!Number.isFinite(rate) || rate < 0 || rate > 1000) throw new Error('Informe uma taxa entre 0 e 1000%.')
    const body = {
      name: f.name, institution: f.institution, assetType: f.assetType, ticker: f.ticker,
      openedOn: f.openedOn, openingBalanceCents: balance,
      openingInvestedCents: f.invested.trim() ? cents(f.invested, { allowZero: true }) : balance,
      rateType: f.rateType, ratePercent: rate, dayBasis: f.rateType === 'CDI' ? 'BUSINESS' : f.dayBasis,
      maturityDate: f.maturityDate || null, notes: f.notes, version: f.version ?? null,
    }
    const result = f.id ? await write('/investments/positions/' + f.id, 'PUT', body)
      : await api('/investments/positions', { method: 'POST', body, headers: { 'Idempotency-Key': f.requestKey } })
    selectedId.value = result.position.id
    form.value = null
    toast('Aplicação salva.')
    await load()
  } catch (e) { formError.value = e.message }
  finally { saving.value = false }
}
function openMovement(type) {
  formError.value = ''
  movement.value = { id: selected.value.position.id, version: selected.value.position.version,
    type, date: today(), amount: '', notes: '' }
}
async function saveMovement() {
  saving.value = true
  formError.value = ''
  try {
    const m = movement.value
    await write(`/investments/positions/${m.id}/events`, 'POST', {
      type: m.type, date: m.date, amountCents: cents(m.amount, { allowZero: m.type === 'VALUATION' }),
      notes: m.notes, version: m.version,
    })
    movement.value = null
    toast('Movimentação registrada.')
    await load()
  } catch (e) { formError.value = e.message }
  finally { saving.value = false }
}
async function remove() {
  saving.value = true
  formError.value = ''
  try {
    const r = removal.value
    await write(`/investments/positions/${r.id}${r.eventId ? '/events/' + r.eventId : ''}?version=${r.version}`, 'DELETE')
    removal.value = null
    toast('Registro excluído.')
    await load()
  } catch (e) { formError.value = e.message }
  finally { saving.value = false }
}
function confirmRemoval(event = null) {
  formError.value = ''
  removal.value = { id: selected.value.position.id, version: selected.value.position.version,
    eventId: event?.id, title: event ? eventTypes[event.type] : selected.value.position.name }
}
const chart = (theme) => ({ type: 'line', data: {
  labels: (selected.value?.history || []).map((p) => date(p.date)),
  datasets: [
    { label: 'Saldo', data: (selected.value?.history || []).map((p) => p.balanceCents), borderColor: '#8a6fe0',
      backgroundColor: 'rgba(138,111,224,.12)', fill: true, pointRadius: 0, tension: .15 },
    { label: 'Capital líquido', data: (selected.value?.history || []).map((p) => p.netInvestedCents),
      borderColor: '#81c6ac', borderDash: [5, 4], pointRadius: 0 },
  ] }, options: { responsive: true, maintainAspectRatio: false, plugins: {
    legend: { labels: { color: theme.text } }, tooltip: { callbacks: { label: (i) => `${i.dataset.label}: ${money(i.raw)}` } },
  }, scales: { x: { ticks: { color: theme.text, maxTicksLimit: 6 }, grid: { display: false } },
    y: { ticks: { color: theme.text, callback: (v) => money(Math.round(v)) }, grid: { color: theme.grid } } } } })
onMounted(load)
</script>

<template>
  <div class="page-heading">
    <div><span class="eyebrow">SEU PATRIMÔNIO</span><h1>Investimentos</h1>
      <p>Suas aplicações, em qualquer banco ou corretora. Comece pelo saldo que você já tem.</p></div>
    <div class="invest-actions">
      <button class="button secondary" :disabled="loading" @click="load"><Icon name="refresh" :size="16" />Atualizar</button>
      <button class="button primary" @click="edit()"><Icon name="plus" :size="18" />Adicionar investimento</button>
    </div>
  </div>
  <div v-if="error" class="banner error" role="alert">{{ error }} <button class="text-link" @click="load">Tentar novamente</button></div>
  <div v-if="loading && !portfolio" class="loading-line"><Icon name="loading" />Carregando carteira…</div>
  <template v-if="portfolio">
    <div v-for="warning in portfolio.warnings" :key="warning" class="banner">{{ warning }}</div>
    <div class="stats-grid">
      <article class="stat-card"><span>Saldo acompanhado</span><strong>{{ money(portfolio.balanceCents) }}</strong><small>Posição em {{ date(portfolio.asOf) }} · valores em reais</small></article>
      <article class="stat-card"><span>Capital líquido aplicado</span><strong>{{ money(portfolio.netInvestedCents) }}</strong><small>Capital inicial + aportes − resgates</small></article>
      <article class="stat-card"><span>Ganho ou perda total</span><strong>{{ money(portfolio.gainCents) }}</strong><small>Inclui {{ money(portfolio.incomeCents) }} em proventos recebidos</small></article>
    </div>
    <InvestmentAnalytics ref="analysisPanel" :snapshot="portfolio" />
    <EmptyState v-if="!portfolio.positions.length && !portfolio.legacy" icon="trend" title="Sua carteira começa aqui"
      description="Cadastre uma caixinha do Nubank, um CDB de outro banco, ações, fundos, cripto ou qualquer outra aplicação.">
      <button class="button primary" @click="edit()">Informar meu saldo atual</button>
    </EmptyState>
    <section v-if="portfolio.positions.length || portfolio.legacy" class="panel">
      <div class="panel-heading"><div><h2>Minhas aplicações</h2><p>Selecione uma aplicação para ver os detalhes e registrar movimentações.</p></div></div>
      <div class="portfolio-grid">
        <button v-for="item in portfolio.positions" :key="item.position.id" type="button"
          :class="['portfolio-card', { selected: selectedId === item.position.id }]" @click="selectedId = item.position.id">
          <span>{{ item.position.institution }} · {{ types[item.position.assetType] }}</span>
          <b>{{ item.position.name }}</b><strong>{{ money(item.balanceCents) }}</strong>
          <small>{{ rateLabel(item.currentRate) }} · {{ item.estimated ? 'Estimativa bruta' : 'Valor informado' }}</small>
          <small>{{ item.gainCents < 0 ? 'Perda' : 'Ganho' }}: {{ money(item.gainCents) }}</small>
        </button>
        <button v-if="portfolio.legacy" type="button" :class="['portfolio-card', { selected: selectedId === 'legacy' }]" @click="selectedId = 'legacy'">
          <span>Nubank · cadastro existente</span><b>Caixinha Nubank</b><strong>{{ money(portfolio.legacy.balanceCents) }}</strong>
          <small>Vinculada aos lançamentos do financeiro</small><small>Ganho: {{ money(portfolio.legacy.gainCents) }}</small>
        </button>
      </div>
    </section>
    <template v-if="selected">
      <section class="panel">
        <div class="panel-heading"><div><h2>{{ selected.position.name }}</h2>
          <p>{{ selected.position.institution }} · {{ types[selected.position.assetType] }}<span v-if="selected.position.ticker"> · {{ selected.position.ticker }}</span></p></div>
          <div class="invest-actions"><button class="button subtle" @click="edit(selected)">Editar</button><button class="button subtle" @click="confirmRemoval()">Excluir</button></div>
        </div>
        <div class="invest-actions">
          <button class="button secondary" @click="analysisPanel?.focus(selected.position.id)">Analisar esta aplicação</button>
          <button class="button primary" @click="openMovement('DEPOSIT')">Aportar</button>
          <button class="button secondary" @click="openMovement('WITHDRAWAL')">Resgatar</button>
          <button class="button secondary" @click="openMovement('VALUATION')">Atualizar saldo pelo extrato</button>
          <button class="button secondary" @click="openMovement('INCOME')">Registrar provento</button>
        </div>
        <div class="portfolio-details">
          <div><span>Regra de rendimento</span><b>{{ rateLabel(selected.currentRate) }}</b><small v-if="selected.currentRate.type !== 'MANUAL'">{{ selected.currentRate.dayBasis === 'BUSINESS' ? 'Dias úteis' : 'Dias corridos' }}</small></div>
          <div><span>Ganho / perda acumulado</span><b>{{ money(selected.gainCents) }}</b><small>{{ percent(selected.gainPercent) }} sobre o capital aportado</small></div>
          <div><span>Próximos 30 dias</span><b>{{ selected.next30DaysGainCents == null ? 'Sem projeção automática' : money(selected.next30DaysGainCents) }}</b><small>Sem novos aportes; taxa atual constante</small></div>
          <div><span>Último saldo informado</span><b>{{ date(selected.lastValuationDate) }}</b><small v-if="selected.position.maturityDate">Vencimento: {{ date(selected.position.maturityDate) }}</small></div>
        </div>
        <div v-for="warning in selected.warnings" :key="warning" class="banner">{{ warning }}</div>
        <p v-if="selected.position.notes" class="muted">{{ selected.position.notes }}</p>
        <BaseChart v-if="selected.history.length > 1" :build="chart" :watch-source="selected" label="Evolução do investimento selecionado" />
        <p class="footnote">Estimativas de renda fixa são brutas, sem impostos, taxas ou marcação a mercado. A data de partida não rende novamente. Atualizações pelo extrato substituem o saldo calculado naquela data e preservam o capital aportado.</p>
      </section>
      <section class="panel">
        <div class="panel-heading"><div><h2>Histórico da aplicação</h2><p>Saldo inicial de {{ money(selected.position.openingBalanceCents) }} em {{ date(selected.position.openedOn) }}.</p></div></div>
        <p v-if="!eventHistory.length" class="muted">Nenhuma movimentação após o saldo inicial.</p>
        <ul v-else class="movement-list"><li v-for="event in eventHistory" :key="event.id">
          <span><strong>{{ eventTypes[event.type] }}</strong><small>{{ date(event.date) }}<span v-if="event.notes"> · {{ event.notes }}</span></small></span>
          <b>{{ event.type === 'RATE_CHANGE' ? rateLabel(event.rate) : money(event.amountCents) }}</b>
          <button type="button" class="text-link" :aria-label="'Excluir ' + eventTypes[event.type] + ' de ' + date(event.date)" @click="confirmRemoval(event)">Excluir</button>
        </li></ul>
      </section>
    </template>
    <section v-if="selectedId === 'legacy'" class="legacy-investment"><LegacyInvestment @updated="load" /></section>
    <section v-if="allocation.length" class="panel">
      <div class="panel-heading"><div><h2>Distribuição por instituição</h2><p>Onde está o saldo acompanhado.</p></div></div>
      <ul class="movement-list"><li v-for="[bank, value] in allocation" :key="bank"><span>{{ bank }}</span><b>{{ money(value) }}</b></li></ul>
    </section>
    <p class="footnote">CDI: dados públicos gratuitos do Banco Central, armazenados em cache. Não há conexão com sua conta bancária nem API paga. Para renda variável, poupança e produtos indexados à inflação ou à Selic, informe o saldo atual do extrato; uma taxa informada cria apenas uma estimativa.</p>
  </template>
  <button v-if="portfolio && !portfolio.legacy" class="text-link" @click="selectedId = selectedId === 'legacy' ? '' : 'legacy'">Abrir cadastro anterior da caixinha</button>

  <Modal v-if="form" :title="form.id ? 'Editar investimento' : 'Adicionar investimento'" wide @close="!saving && (form = null)">
    <form @submit.prevent="savePosition">
      <div v-if="formError" class="banner error" role="alert">{{ formError }}</div>
      <div class="portfolio-form-grid">
        <label>Nome da aplicação<input v-model="form.name" maxlength="120" placeholder="Reserva de emergência" required /></label>
        <label>Banco ou corretora<input v-model="form.institution" maxlength="120" placeholder="Nubank, Inter, Itaú…" required /></label>
        <label>Tipo de investimento<select v-model="form.assetType" @change="selectType"><option v-for="(label, key) in types" :key="key" :value="key">{{ label }}</option></select></label>
        <label>Código / ticker (opcional)<input v-model="form.ticker" maxlength="32" placeholder="Ex.: PETR4, BTC" /></label>
        <label>Data do saldo de partida<input v-model="form.openedOn" type="date" min="1990-01-01" :max="today()" :disabled="!!form.id" required /></label>
        <label>Saldo que já tenho (R$)<MaskedInput v-model="form.balance" :disabled="!!form.id" placeholder="0,00" required /></label>
        <label>Capital aplicado até essa data (R$)<MaskedInput v-model="form.invested" :disabled="!!form.id" placeholder="Em branco = saldo informado" /></label>
        <label>Vencimento (opcional)<input v-model="form.maturityDate" type="date" :min="form.openedOn" /></label>
        <label>Como acompanhar o rendimento<select v-model="form.rateType"><option v-for="(label, key) in rates" :key="key" :value="key">{{ label }}</option></select></label>
        <label v-if="form.rateType !== 'MANUAL'">{{ form.rateType === 'CDI' ? 'Percentual do CDI (%)' : form.rateType === 'ANNUAL' ? 'Taxa anual (%)' : 'Taxa mensal (%)' }}<MaskedInput v-model="form.ratePercent" kind="decimal" :scale="6" min="0" max="1000" required /></label>
        <label v-if="['ANNUAL', 'MONTHLY'].includes(form.rateType)">Base de cálculo<select v-model="form.dayBasis"><option value="BUSINESS">Dias úteis (252/ano; 21/mês)</option><option value="CALENDAR">Dias corridos (365/ano; 30/mês)</option></select></label>
      </div>
      <p class="muted">Exemplo: saldo de R$ 1.050 e capital aplicado de R$ 1.000 registram R$ 50 de ganho já acumulado. Se não souber o capital, deixe em branco; os ganhos passam a ser acompanhados a partir desse saldo.</p>
      <p v-if="form.id" class="muted">Use “Atualizar saldo pelo extrato” para ajustar o valor atual. Alterações na taxa valem a partir de hoje, para o rendimento dos próximos dias.</p>
      <p v-if="form.rateType === 'CDI'" class="muted">Informe a taxa contratada no seu banco. Ex.: 100 para 100% do CDI ou 110 para 110%. O tipo “caixinha” não define a taxa automaticamente.</p>
      <p v-if="form.rateType === 'MANUAL'" class="muted">O saldo não muda sozinho. Atualize pelo extrato ou pelo valor de mercado em reais; ganhos e perdas serão calculados sem exigir uma API de cotações.</p>
      <label>Observações<textarea v-model="form.notes" maxlength="2000" rows="2" placeholder="Liquidez, taxa contratada, objetivo…" /></label>
      <p class="muted">Este cadastro acompanha a aplicação separadamente e não cria despesas ou receitas no financeiro.</p>
      <div class="modal-actions"><button type="button" class="button subtle" :disabled="saving" @click="form = null">Cancelar</button><button class="button primary" :disabled="saving">{{ saving ? 'Salvando…' : 'Salvar investimento' }}</button></div>
    </form>
  </Modal>
  <Modal v-if="movement" :title="eventTypes[movement.type]" @close="!saving && (movement = null)">
    <form @submit.prevent="saveMovement">
      <div v-if="formError" class="banner error" role="alert">{{ formError }}</div>
      <label>{{ movement.type === 'VALUATION' ? 'Saldo total no extrato / valor de mercado (R$)' : 'Valor (R$)' }}<MaskedInput v-model="movement.amount" required placeholder="0,00" /></label>
      <label>Data<input v-model="movement.date" type="date" :min="selected?.position.openedOn" :max="today()" required /></label>
      <label>Observação<input v-model="movement.notes" maxlength="500" /></label>
      <p v-if="movement.type === 'VALUATION'" class="muted">Informe o saldo total, incluindo rendimentos ou perdas, e não só a diferença. O capital aportado não será alterado.</p>
      <p v-else-if="movement.type === 'INCOME'" class="muted">Dividendos, cupons e outros valores pagos fora da aplicação. Em renda fixa estimada, o valor pago sai do saldo calculado, pois o rendimento já estava acumulado nele. Em saldo manual, o valor de mercado é preservado; atualize pelo extrato quando necessário. Se reinvestir, registre também um aporte.</p>
      <p class="muted">Esta movimentação pertence à aplicação e não cria um lançamento no financeiro.</p>
      <div class="modal-actions"><button type="button" class="button subtle" :disabled="saving" @click="movement = null">Cancelar</button><button class="button primary" :disabled="saving">Salvar</button></div>
    </form>
  </Modal>
  <Modal v-if="removal" title="Excluir registro" @close="!saving && (removal = null)">
    <div v-if="formError" class="banner error" role="alert">{{ formError }}</div>
    <p>Excluir “{{ removal.title }}”{{ removal.eventId ? ' e recalcular o histórico' : ' e todo o histórico desta aplicação' }}?</p>
    <div class="modal-actions"><button class="button subtle" :disabled="saving" @click="removal = null">Cancelar</button><button class="button primary" :disabled="saving" @click="remove">Excluir</button></div>
  </Modal>
</template>

<style scoped>
.portfolio-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(230px, 1fr)); gap: 1rem; }
.portfolio-card { display: flex; flex-direction: column; gap: .45rem; padding: 1.2rem; text-align: left; border: 1px solid var(--line); border-radius: 16px; background: var(--panel); color: inherit; cursor: pointer; }
.portfolio-card.selected { border-color: #8a6fe0; box-shadow: 0 0 0 2px rgba(138,111,224,.15); }
.portfolio-card strong { font-size: 1.5rem; margin: .25rem 0; }
.portfolio-card > span, .portfolio-card small, .portfolio-details span, .portfolio-details small { color: var(--muted); font-size: .8rem; }
.portfolio-details { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 1.5rem; margin: 1.5rem 0; }
.portfolio-details > div { display: flex; flex-direction: column; gap: .35rem; }
.portfolio-form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 1rem; }
.legacy-investment { margin-top: 1.5rem; }
.stat-card > span { color: var(--muted); }
.stat-card > strong { margin: .6rem 0; }
@media (max-width: 600px) { .portfolio-form-grid { grid-template-columns: 1fr; } .portfolio-card { min-width: 0; } }
</style>
