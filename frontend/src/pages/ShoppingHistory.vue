<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { api, query, write } from '../lib/api'
import { money, timestamp, cents } from '../lib/format'
import BaseChart from '../components/BaseChart.vue'
import MaskedInput from '../components/MaskedInput.vue'
import Modal from '../components/Modal.vue'
import '../shopping.css'
const tab = ref('lists'),
  purchases = ref({ items: [], total: 0 }),
  page = ref(0),
  details = ref(null)
const products = ref({ items: [], total: 0 }),
  productPage = ref(0),
  search = ref(''),
  selected = ref(''),
  history = ref(null),
  historyPage = ref(0)
const from = ref(''),
  to = ref(''),
  error = ref(''),
  busy = ref(false),
  offer = ref(''),
  comparison = ref(null)
const product = computed(() =>
  products.value.items.find((p) => p.name + '|' + p.unit === selected.value),
)
let alive = true,
  revision = 0
onBeforeUnmount(() => {
  alive = false
  revision++
})
async function request(task) {
  const version = ++revision
  busy.value = true
  error.value = ''
  try {
    await task(version)
  } catch (e) {
    if (alive && version === revision) error.value = e.message
  } finally {
    if (alive && version === revision) busy.value = false
  }
}
function paginate(kind, direction) {
  if (kind === 'page') {
    page.value += direction
    loadLists()
  } else if (kind === 'productPage') {
    productPage.value += direction
    loadProducts(false)
  } else {
    historyPage.value += direction
    loadHistory(false)
  }
}
async function loadLists() {
  await request(async (v) => {
    const r = await api('/shopping-list/checkouts?' + query({ page: page.value, size: 12 }))
    if (alive && v === revision) purchases.value = r
  })
}
async function openPurchase(id) {
  await request(async (v) => {
    const r = await api('/shopping-list/checkouts/' + id)
    if (alive && v === revision) details.value = r
  })
}
async function loadProducts(reset = true) {
  if (reset) productPage.value = 0
  await request(async (v) => {
    const r = await api(
      '/shopping-list/prices/products?' +
        query({ q: search.value, page: productPage.value, size: 50 }),
    )
    if (alive && v === revision) {
      products.value = r
      selected.value = ''
      history.value = null
      comparison.value = null
    }
  })
}
async function loadHistory(reset = true) {
  if (reset) historyPage.value = 0
  comparison.value = null
  offer.value = ''
  if (!product.value) {
    revision++
    busy.value = false
    history.value = null
    return
  }
  const p = { ...product.value }
  await request(async (v) => {
    const r = await api(
      '/shopping-list/prices/history?' +
        query({
          name: p.name,
          unit: p.unit,
          from: from.value,
          to: to.value,
          page: historyPage.value,
          size: 100,
        }),
    )
    if (alive && v === revision) history.value = r
  })
}
async function compareOffer() {
  if (!product.value) return
  const p = { ...product.value }
  await request(async (v) => {
    const r = await write('/shopping-list/prices/compare', 'POST', {
      name: p.name,
      unit: p.unit,
      unitPriceCents: cents(offer.value, { allowZero: true }),
    })
    if (alive && v === revision) comparison.value = r
  })
}
function trend(value) {
  return (
    {
      INCREASED: 'Subiu',
      DECREASED: 'Baixou',
      SAME: 'Mesmo preço',
      NO_HISTORY: 'Sem compra anterior',
      NO_COMPARISON: 'Ainda sem comparação',
    }[value] || '—'
  )
}
async function switchTab(value) {
  tab.value = value
  details.value = null
  if (value === 'lists') await loadLists()
  else await loadProducts()
}
function buildChart(theme) {
  const points = history.value?.items || []
  return {
    type: 'line',
    data: {
      labels: points.map((p) => timestamp(p.paidAt)),
      datasets: [
        {
          label: 'Preço por ' + (history.value?.unit || 'unidade'),
          data: points.map((p) => Number(p.referencePrice)),
          borderColor: '#ad8cff',
          backgroundColor: '#ad8cff20',
          fill: true,
          tension: 0.15,
          pointRadius: 4,
        },
      ],
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: {
        legend: { display: false },
        tooltip: {
          callbacks: {
            label: (c) => points[c.dataIndex].referenceLabel + ' / ' + history.value.unit,
          },
        },
      },
      scales: {
        x: { ticks: { color: theme.text, maxTicksLimit: 6 }, grid: { display: false } },
        y: {
          ticks: {
            color: theme.text,
            callback: (v) =>
              new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(v),
          },
          grid: { color: theme.grid },
        },
      },
    },
  }
}
onMounted(loadLists)
</script>
<template>
  <div class="page-heading">
    <div>
      <span class="eyebrow">COMPRAS COM HISTÓRIA</span>
      <h1>O que mudou de preço?</h1>
      <p>Suas listas guardadas e os preços que você realmente pagou.</p>
    </div>
    <RouterLink class="button secondary" to="/compras">Voltar à lista atual</RouterLink>
  </div>
  <div class="segmented">
    <button :class="{ selected: tab === 'lists' }" @click="switchTab('lists')">
      Listas arquivadas</button
    ><button :class="{ selected: tab === 'prices' }" @click="switchTab('prices')">
      Evolução de preços
    </button>
  </div>
  <div v-if="error" class="banner error" role="alert">{{ error }}</div>
  <p v-if="busy" class="muted" role="status">Carregando…</p>
  <template v-if="tab === 'lists'">
    <div v-if="purchases.items.length" class="item-grid shopping-history-grid">
      <button
        v-for="p in purchases.items"
        :key="p.id"
        class="item-card purchase-card"
        @click="openPurchase(p.id)"
      >
        <span class="tag">Arquivada</span>
        <h3>{{ timestamp(p.paidAt) }}</h3>
        <strong>{{ money(p.totalCents) }}</strong
        ><span class="muted">{{ p.purchasedCount }} comprado(s) · {{ p.itemCount }} na lista</span
        ><span class="text-link">Ver itens e preços</span>
      </button>
    </div>
    <div v-else-if="!busy" class="panel">
      <h2>Suas compras ficam aqui</h2>
      <p class="muted">
        Ao finalizar a lista, guardamos a data, os itens e os preços para você consultar depois.
      </p>
    </div>
    <div v-if="purchases.total > 12" class="pagination">
      <button class="button secondary" :disabled="!page || busy" @click="paginate('page', -1)">
        Anterior</button
      ><span>Página {{ page + 1 }}</span
      ><button
        class="button secondary"
        :disabled="(page + 1) * 12 >= purchases.total || busy"
        @click="paginate('page', 1)"
      >
        Próxima
      </button>
    </div>
  </template>
  <template v-else>
    <section class="panel shopping-price-filters">
      <form class="search-box" @submit.prevent="loadProducts()">
        <input
          v-model="search"
          maxlength="160"
          placeholder="Buscar produto comprado"
          aria-label="Buscar produto"
        /><button class="text-link" :disabled="busy">Buscar</button>
      </form>
      <label
        >Produto e unidade<select v-model="selected" @change="loadHistory()">
          <option value="">Selecione um produto</option>
          <option
            v-for="p in products.items"
            :key="p.name + '|' + p.unit"
            :value="p.name + '|' + p.unit"
          >
            {{ p.name }} · {{ p.unit }} ({{ p.purchases }} compras)
          </option>
        </select></label
      >
      <div v-if="products.total > 50" class="pagination">
        <button :disabled="!productPage || busy" @click="paginate('productPage', -1)">
          Anterior</button
        ><span>{{ productPage + 1 }}</span
        ><button
          :disabled="(productPage + 1) * 50 >= products.total || busy"
          @click="paginate('productPage', 1)"
        >
          Mais produtos
        </button>
      </div>
      <form class="form-grid" @submit.prevent="loadHistory()">
        <label>De<input v-model="from" type="date" /></label
        ><label>Até<input v-model="to" type="date" :min="from" /></label
        ><button class="button secondary" :disabled="!selected || busy">Aplicar período</button>
      </form>
      <p class="muted">
        Compare o mesmo produto por unidade, quilo ou litro. Pacotes ficam separados quando o
        conteúdo não é conhecido.
      </p>
    </section>
    <p v-if="!products.total && !busy" class="muted">
      Os produtos aparecerão depois da primeira compra finalizada.
    </p>
    <template v-if="history">
      <div class="shopping-price-stats">
        <article class="panel">
          <span>Último preço</span
          ><strong>{{ history.latestLabel || '—' }} / {{ history.unit }}</strong
          ><small
            >{{ trend(history.trend)
            }}<template v-if="history.changePercent != null">
              · {{ history.changePercent }}%</template
            ></small
          >
        </article>
        <article class="panel">
          <span>Menor preço</span><strong>{{ history.minimumLabel || '—' }}</strong>
        </article>
        <article class="panel">
          <span>Maior preço</span><strong>{{ history.maximumLabel || '—' }}</strong>
        </article>
        <article class="panel">
          <span>Média do período</span><strong>{{ history.averageLabel || '—' }}</strong>
        </article>
      </div>
      <section v-if="history.items.length" class="panel shopping-price-chart">
        <h2>{{ history.name }} · preço por {{ history.unit }}</h2>
        <BaseChart :build="buildChart" :watch-source="history" label="Evolução dos preços pagos" />
        <p class="muted">
          {{ history.total }} registro(s) no período. Cada ponto é uma compra. O gráfico mostra até
          100 registros por página.
        </p>
      </section>
      <p v-else class="muted">Nenhuma compra desse produto no período escolhido.</p>
      <section class="panel">
        <h2>Essa oferta vale a pena?</h2>
        <form class="shopping-offer" @submit.prevent="compareOffer">
          <label
            >Preço por {{ product?.unit
            }}<MaskedInput v-model="offer" kind="money" required placeholder="0,00" /></label
          ><button class="button secondary" :disabled="busy || !offer">
            Comparar com a última compra
          </button>
        </form>
        <p v-if="comparison">
          {{ comparison.currentLabel }} / {{ comparison.unit }} · {{ trend(comparison.trend)
          }}<template v-if="comparison.hasHistory">
            em relação a {{ comparison.previousLabel
            }}<template v-if="comparison.changePercent != null">
              ({{ comparison.changePercent }}%)</template
            >, pagos em {{ timestamp(comparison.previousPaidAt) }}</template
          >.
        </p>
        <p class="muted">
          Só consulta: comparar não altera sua lista. A referência é a última compra,
          independentemente do período do gráfico.
        </p>
      </section>
      <div class="panel shopping-history-table">
        <table>
          <thead>
            <tr>
              <th>Data da compra</th>
              <th>Quantidade</th>
              <th>Total pago</th>
              <th>Por {{ history.unit }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="p in history.items" :key="p.purchaseId + '|' + p.paidAt">
              <td>{{ timestamp(p.paidAt) }}</td>
              <td>{{ p.quantity }} {{ p.purchasedUnit }}</td>
              <td>{{ money(p.totalCents) }}</td>
              <td>{{ p.referenceLabel }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="history.total > 100" class="pagination">
        <button
          class="button secondary"
          :disabled="busy || !historyPage"
          @click="paginate('historyPage', -1)"
        >
          Mais recentes</button
        ><span>Página {{ historyPage + 1 }}</span
        ><button
          class="button secondary"
          :disabled="busy || (historyPage + 1) * 100 >= history.total"
          @click="paginate('historyPage', 1)"
        >
          Mais antigas
        </button>
      </div>
    </template>
  </template>
  <Modal v-if="details" title="Lista arquivada" @close="details = null"
    ><p>
      {{ timestamp(details.purchase.paidAt) }} ·
      <strong>{{ money(details.purchase.totalCents) }}</strong>
    </p>
    <div v-for="item in details.items" :key="item.id" class="shopping-archive-item">
      <div>
        <strong>{{ item.name }}</strong>
        <p class="muted">
          {{ item.quantity ?? 'Quantidade não informada' }} {{ item.unit || '' }} ·
          {{ item.purchased ? 'Comprado' : 'Ficou fora do carrinho' }}
        </p>
      </div>
      <strong>{{
        item.totalPriceCents == null ? 'Sem preço' : money(item.totalPriceCents)
      }}</strong>
    </div>
    <p class="muted">
      Itens não comprados não entram no total, na despensa nem no financeiro.
    </p></Modal
  >
</template>
