<script setup>
import { ref, onMounted } from 'vue'
import { api, auth, query } from '../lib/api'
import { money, date, monthRange, today } from '../lib/format'
import Icon from '../components/Icon.vue'
import Chart from '../components/Chart.vue'
import EmptyState from '../components/EmptyState.vue'
const loading = ref(true),
  errors = ref([]),
  summary = ref(null),
  entries = ref([]),
  shopping = ref([]),
  pantry = ref(null)
const range = monthRange()
async function load() {
  loading.value = true
  errors.value = []
  const requests = [
    [
      'financeiro',
      () => api('/finance/summary?' + query({ ...range, groupBy: 'DAY' })),
      (v) => (summary.value = v),
    ],
    ['lançamentos', () => api('/finance/entries?size=5'), (v) => (entries.value = v.items)],
    ['compras', () => api('/shopping-list/items?size=4'), (v) => (shopping.value = v.items)],
    ['despensa', () => api('/pantry/items?lowStock=true&size=1'), (v) => (pantry.value = v)],
  ]
  await Promise.all(
    requests.map(async ([name, fetch, save]) => {
      try {
        save(await fetch())
      } catch (e) {
        errors.value.push(`${name}: ${e.message}`)
      }
    }),
  )
  loading.value = false
}
onMounted(load)
const month = new Intl.DateTimeFormat('pt-BR', { month: 'long' }).format(new Date())
</script>
<template>
  <div class="page-heading">
    <div>
      <span class="eyebrow">SEU DIA, MAIS LEVE</span>
      <h1>
        Oi<span v-if="auth.session?.user?.displayName">, {{ auth.session.user.displayName }}</span
        ><span class="greeting-star">✦</span>
      </h1>
      <RouterLink v-if="!auth.session?.user?.displayName" to="/configuracoes" class="text-link"
        >Como você quer ser chamado? Adicione seu nome.</RouterLink
      >
      <p>Vamos cuidar do que importa hoje?</p>
    </div>
    <span class="date-chip"><Icon name="calendar" :size="16" />{{ date(today()) }}</span>
  </div>
  <div v-if="errors.length" class="banner error">
    <span>{{ errors.join(' · ') }}</span
    ><button class="text-link" @click="load">Tentar novamente</button>
  </div>
  <section class="welcome-card">
    <div>
      <span class="pill"><span class="status-dot" />SUA ASSISTENTE PESSOAL</span>
      <h2>Um espaço para sua vida.<br /><span>Uma companhia para o seu dia.</span></h2>
      <p>
        Organize as contas, cuide da casa ou só jogue conversa fora. Eu tô por aqui para deixar tudo
        um pouco mais fácil.
      </p>
      <RouterLink to="/chat" class="button primary"
        >Vamos conversar<Icon name="arrow" :size="17"
      /></RouterLink>
    </div>
    <div class="welcome-art" aria-hidden="true">
      <img
        class="art-image"
        src="/ayla-celeste.png"
        alt=""
        width="700"
        height="636"
        decoding="async"
      /><span class="art-caption"
        >um pouquinho de ordem,<br /><em>um montão de possibilidades.</em></span
      >
    </div>
  </section>
  <div class="section-heading">
    <h2>Seu mês em um olhar</h2>
    <RouterLink to="/financeiro" class="text-link"
      >Ver financeiro<Icon name="right" :size="15"
    /></RouterLink>
  </div>
  <div class="stats-grid">
    <article
      v-for="card in [
        {
          label: 'Saldo registrado',
          value: summary?.balanceCents,
          icon: 'wallet',
          class: 'lavender',
          note: 'Acumulado até agora',
        },
        {
          label: 'Receitas do mês',
          value: summary?.period?.incomeCents,
          icon: 'income',
          class: 'mint',
          note: 'Entradas em ' + month,
        },
        {
          label: 'Despesas do mês',
          value: summary?.period?.expenseCents,
          icon: 'expense',
          class: 'peach',
          note: 'Saídas em ' + month,
        },
      ]"
      :key="card.label"
      class="stat-card"
    >
      <div class="stat-top">
        <span>{{ card.label }}</span
        ><span :class="['stat-icon', card.class]"><Icon :name="card.icon" :size="18" /></span>
      </div>
      <strong :class="{ skeleton: loading }">{{
        card.value == null ? '—' : money(card.value)
      }}</strong
      ><small>{{ card.note }}</small>
    </article>
  </div>
  <div class="dashboard-grid">
    <section class="panel">
      <div class="panel-heading">
        <div>
          <h2>Entrou e saiu</h2>
          <p>Movimentações em {{ month }}</p>
        </div>
        <div class="chart-legend">
          <span><i class="mint-dot" />Receitas</span><span><i class="purple-dot" />Despesas</span>
        </div>
      </div>
      <Chart v-if="summary?.series?.length" :series="summary.series" /><EmptyState
        v-else
        icon="wallet"
        :title="loading ? 'Carregando seu mês…' : 'Seu mês começa por aqui'"
        description="Os lançamentos registrados vão aparecer neste gráfico."
      />
    </section>
    <section class="panel shopping-preview">
      <div class="panel-heading">
        <div>
          <h2>Na sua lista</h2>
          <p>Para a próxima ida ao mercado</p>
        </div>
        <span class="stat-icon mint"><Icon name="shopping" :size="19" /></span>
      </div>
      <div v-for="item in shopping" :key="item.id" class="preview-item">
        <span class="fake-check" /><span>{{ item.name }}</span
        ><small>{{ item.quantity || '' }} {{ item.unit || '' }}</small>
      </div>
      <EmptyState
        v-if="!shopping.length"
        icon="shopping"
        title="Nada para comprar por enquanto"
        description="Adicione o que estiver faltando."
      /><RouterLink to="/compras" class="panel-bottom-link"
        >Abrir lista de compras<Icon name="arrow" :size="16"
      /></RouterLink>
    </section>
  </div>
  <div class="dashboard-grid bottom-grid">
    <section class="panel">
      <div class="panel-heading">
        <div>
          <h2>Últimos lançamentos</h2>
          <p>Seu dinheiro em movimento</p>
        </div>
        <RouterLink to="/financeiro" class="text-link"
          >Ver todos<Icon name="right" :size="15"
        /></RouterLink>
      </div>
      <div v-for="entry in entries" :key="entry.id" class="transaction-row">
        <span :class="['stat-icon', entry.type === 'INCOME' ? 'mint' : 'lavender']"
          ><Icon :name="entry.type === 'INCOME' ? 'income' : 'expense'" :size="18"
        /></span>
        <div class="grow">
          <strong>{{ entry.description }}</strong
          ><small>{{ entry.category }} · {{ date(entry.occurredAt) }}</small>
        </div>
        <strong :class="entry.type === 'INCOME' ? 'positive' : ''"
          >{{ entry.type === 'INCOME' ? '+' : '−' }} {{ money(entry.amountCents) }}</strong
        >
      </div>
      <EmptyState
        v-if="!entries.length"
        icon="wallet"
        title="Cada registro conta"
        description="Adicione sua primeira receita ou despesa."
      />
    </section>
    <section class="pantry-callout">
      <span class="stat-icon peach"><Icon name="pantry" :size="24" /></span>
      <h2>Como anda a despensa?</h2>
      <p>
        {{
          pantry?.total
            ? `${pantry.total} produto(s) com estoque baixo. Que tal colocar na lista?`
            : 'Saiba o que tem em casa antes de comprar. Menos desperdício, mais praticidade.'
        }}
      </p>
      <RouterLink to="/despensa" class="button secondary"
        >Ver minha despensa<Icon name="arrow" :size="16" /></RouterLink
      ><span class="callout-deco" aria-hidden="true">✧</span>
    </section>
  </div>
</template>
