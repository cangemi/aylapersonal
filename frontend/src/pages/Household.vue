<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount, reactive } from 'vue'
import { useRoute } from 'vue-router'
import { api, write, query, auth } from '../lib/api'
import { units, date, money, cents, moneyInput } from '../lib/format'
import { toast, confirmAction } from '../lib/ui'
import Icon from '../components/Icon.vue'
import MaskedInput from '../components/MaskedInput.vue'
import Modal from '../components/Modal.vue'
import EmptyState from '../components/EmptyState.vue'
import ShoppingListRow from '../components/ShoppingListRow.vue'
import { shoppingOrder, saveShoppingOrder } from '../lib/shoppingList'
import '../shopping.css'
const route = useRoute(),
  pantry = computed(() => route.meta.kind === 'pantry'),
  base = computed(() => (pantry.value ? '/pantry/items' : '/shopping-list/items')),
  pageSize = computed(() => (pantry.value ? 12 : 50))
const items = ref({ items: [], total: 0 }),
  loading = ref(false),
  error = ref(''),
  q = ref(''),
  low = ref(false),
  all = ref(false),
  page = ref(0),
  form = ref(null),
  formError = ref(''),
  saving = ref(false),
  adjust = ref(null),
  cart = ref({
    cartTotalCents: 0,
    cartCount: 0,
    pendingCount: 0,
    missingPrices: 0,
    missingQuantities: 0,
    cartItems: [],
  })
const order = ref(shoppingOrder(localStorage, auth.session?.user?.id)),
  rowBusy = reactive(new Set())
function setRowBusy(id, busy) {
  if (busy) rowBusy.add(id)
  else rowBusy.delete(id)
}
function orderChanged() {
  saveShoppingOrder(localStorage, auth.session?.user?.id, order.value)
  page.value = 0
  load({ background: true })
}
function rowSaved(item) {
  items.value = {
    ...items.value,
    items: items.value.items.map((old) => (old.id === item.id ? item : old)),
  }
  load({ background: true })
}
let loadVersion = 0,
  refreshTimer,
  alive = true,
  inFlight = 0
async function load({ background = false } = {}) {
  if (!alive || (background && inFlight)) return
  inFlight++
  const revision = ++loadVersion
  if (!background) {
    loading.value = true
    error.value = ''
  }
  try {
    const [result, summary] = await Promise.all([
      api(
        base.value +
          '?' +
          query({
            q: q.value,
            lowStock: pantry.value ? low.value : undefined,
            all: pantry.value ? undefined : all.value,
            order: pantry.value ? undefined : order.value,
            page: page.value,
            size: pageSize.value,
          }),
      ),
      pantry.value ? Promise.resolve(null) : api('/shopping-list/cart'),
    ])
    if (revision === loadVersion && alive) {
      if (JSON.stringify(items.value) !== JSON.stringify(result)) items.value = result
      if (summary && JSON.stringify(cart.value) !== JSON.stringify(summary)) cart.value = summary
    }
  } catch (e) {
    if (revision === loadVersion && !background) error.value = e.message
  } finally {
    inFlight--
    if (revision === loadVersion) loading.value = false
  }
}
watch(
  () => route.meta.kind,
  () => {
    page.value = 0
    q.value = ''
    low.value = false
    all.value = false
    form.value = null
    adjust.value = null
    load()
  },
  { immediate: true },
)
function filter() {
  page.value = 0
  load()
}
function changePage(direction) {
  page.value += direction
  load()
}
function openAdjust(item) {
  formError.value = ''
  adjust.value = { ...item, action: 'CONSUME', amount: '' }
}
function edit(item) {
  formError.value = ''
  form.value = item
    ? {
        ...item,
        price:
          item.unitPriceCents != null
            ? moneyInput(item.unitPriceCents)
            : item.totalPriceCents != null
              ? moneyInput(item.totalPriceCents)
              : '',
        priceMode: item.unitPriceCents != null ? 'UNIT' : 'TOTAL',
        inCart: item.status === 'IN_CART',
      }
    : {
        name: '',
        quantity: pantry.value ? '0' : '',
        unit: 'UN',
        minimumQuantity: '0',
        category: '',
        notes: '',
        expiresOn: '',
        price: '',
        priceMode: 'TOTAL',
        inCart: false,
      }
}
async function save() {
  saving.value = true
  formError.value = ''
  try {
    const f = form.value,
      body = {
        name: f.name.trim(),
        category: f.category,
        notes: f.notes,
        ...(f.id ? { version: f.version } : {}),
      }
    if (f.quantity !== '' && f.quantity != null) {
      body.quantity = String(f.quantity)
      body.unit = f.unit
    }
    if (!pantry.value) {
      body.inCart = f.inCart
      if (f.price !== '')
        body[f.priceMode === 'UNIT' ? 'unitPriceCents' : 'totalPriceCents'] = cents(f.price, {
          allowZero: true,
        })
      else if (f.id && (f.totalPriceCents != null || f.unitPriceCents != null))
        body.clearPrice = true
    }
    if (pantry.value) {
      body.minimumQuantity = String(f.minimumQuantity || 0)
      if (f.expiresOn) body.expiresOn = f.expiresOn
      else if (f.id) body.clearExpiry = true
    }
    await write(base.value + (f.id ? '/' + f.id : ''), f.id ? 'PATCH' : 'POST', body)
    form.value = null
    toast('Item salvo.')
    await load()
  } catch (e) {
    formError.value = e.message
  } finally {
    saving.value = false
  }
}
async function remove(item) {
  if (
    !(await confirmAction(
      'Excluir ' + item.name + '?',
      !pantry.value && item.stockApplied
        ? 'Excluir este registro não altera a despensa nem a despesa já registrada.'
        : 'O item será removido.',
    ))
  )
    return
  try {
    await write(`${base.value}/${item.id}?version=${item.version}`, 'DELETE')
    toast('Item excluído.')
    await load()
  } catch (e) {
    toast(e.message, 'error')
  }
}
async function generate() {
  saving.value = true
  try {
    const result = await write('/shopping-list/from-pantry', 'POST')
    toast(`${result.items.length} item(s) sugerido(s) na lista de compras.`)
    if (!pantry.value) await load()
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    saving.value = false
  }
}
async function saveAdjust() {
  saving.value = true
  formError.value = ''
  try {
    const a = adjust.value
    await write(`/pantry/items/${a.id}/adjustments`, 'POST', {
      version: a.version,
      action: a.action,
      quantity: String(a.amount),
      unit: a.unit,
    })
    adjust.value = null
    toast('Estoque atualizado.')
    await load()
  } catch (e) {
    formError.value = e.message
  } finally {
    saving.value = false
  }
}
async function toggleCart(item) {
  if (item.status !== 'IN_CART' && item.totalPriceCents == null) {
    edit(item)
    form.value.inCart = true
    return
  }
  saving.value = true
  try {
    await write('/shopping-list/items/' + item.id, 'PATCH', {
      version: item.version,
      inCart: item.status !== 'IN_CART',
    })
    await load()
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    saving.value = false
  }
}
async function checkout() {
  saving.value = true
  try {
    const current = await api('/shopping-list/cart')
    cart.value = current
    if (!current.cartCount) throw new Error('Seu carrinho está vazio.')
    if (current.missingPrices || current.missingQuantities)
      throw new Error('Complete o preço e a quantidade dos itens do carrinho antes de finalizar.')
    if (
      !(await confirmAction(
        'Registrar pagamento?',
        money(current.cartTotalCents) +
          ' · Os produtos irão para a despensa e o total será registrado como Compra mercado. A lista será arquivada; itens fora do carrinho ficam como não comprados.',
      ))
    )
      return
    const result = await write('/shopping-list/checkout', 'POST', {
      items: current.cartItems.map((item) => ({ id: item.id, version: item.version })),
    })
    toast(
      'Compra concluída: ' +
        money(result.totalCents) +
        (result.financialEntry
          ? '. Despensa e financeiro atualizados.'
          : '. Despensa atualizada, sem despesa para o total zero.'),
    )
    await load()
  } catch (e) {
    toast(e.message, 'error')
    await load()
  } finally {
    saving.value = false
  }
}
onMounted(() => {
  refreshTimer = setInterval(() => {
    if (!document.hidden && !form.value && !adjust.value && !saving.value && !rowBusy.size)
      load({ background: true })
  }, 15000)
})
onBeforeUnmount(() => {
  alive = false
  loadVersion++
  clearInterval(refreshTimer)
})
</script>
<template>
  <div class="page-heading">
    <div>
      <span class="eyebrow">CASA EM ORDEM</span>
      <h1>{{ pantry ? 'O que tem por aqui.' : 'O que falta por aí.' }}</h1>
      <p>
        {{
          pantry
            ? 'Menos desperdício. Mais praticidade no dia a dia.'
            : 'Marque o que pegou e informe os preços aqui mesmo.'
        }}
      </p>
    </div>
    <button class="button primary" @click="edit()">
      <Icon name="plus" :size="18" />{{ pantry ? 'Adicionar produto' : 'Adicionar à lista' }}
    </button>
  </div>
  <div v-if="error" class="banner error" role="alert">
    {{ error }}<button class="text-link" @click="load">Tentar novamente</button>
  </div>
  <section v-if="!pantry" class="panel shopping-cart-summary" aria-label="Resumo do carrinho">
    <div>
      <span class="eyebrow">NO CARRINHO · {{ cart.cartCount }} ITEM(NS)</span
      ><strong>{{ money(cart.cartTotalCents) }}</strong>
      <p class="muted">
        {{ cart.pendingCount }} na lista para pegar<span v-if="cart.missingPrices">
          · {{ cart.missingPrices }} sem preço (total parcial)</span
        ><span v-if="cart.missingQuantities">
          · {{ cart.missingQuantities }} sem quantidade para a despensa</span
        >
      </p>
    </div>
    <button
      class="button primary"
      :disabled="
        saving ||
        !!rowBusy.size ||
        !cart.cartCount ||
        !!cart.missingPrices ||
        !!cart.missingQuantities
      "
      @click="checkout"
    >
      <Icon name="check" :size="18" />Já paguei · finalizar compra
    </button>
  </section>
  <RouterLink v-if="!pantry" to="/compras/historico" class="button secondary"
    >Compras anteriores e evolução de preços</RouterLink
  >
  <div class="household-toolbar">
    <label v-if="!pantry" class="shopping-order"
      >Ordenar lista<select v-model="order" aria-label="Ordenar lista" @change="orderChanged">
        <option value="NORMAL">Ordem normal (A–Z)</option>
        <option value="CART_LAST">Itens no carrinho por último</option>
      </select></label
    >
    <form class="search-box" @submit.prevent="filter">
      <Icon name="search" :size="18" /><input
        v-model="q"
        :placeholder="pantry ? 'Buscar na despensa…' : 'Buscar na lista…'"
        aria-label="Buscar produto"
      /><button class="text-link" :disabled="loading">Buscar</button>
    </form>
    <label v-if="pantry" class="check-label"
      ><input v-model="low" type="checkbox" @change="filter" />Só estoque baixo</label
    ><button class="button secondary" :disabled="saving" @click="generate">
      <Icon name="sparkles" :size="16" />{{
        pantry ? 'Levar faltantes à lista' : 'Sugerir pela despensa'
      }}
    </button>
  </div>
  <div class="section-heading">
    <h2>
      {{ pantry ? 'Seus produtos' : 'Sua lista' }} <span class="count">{{ items.total }}</span>
    </h2>
    <small class="muted">{{
      pantry ? 'Acompanhe quantidades e validades' : 'Marque os itens conforme comprar'
    }}</small>
  </div>
  <div v-if="loading" class="loading-line"><Icon name="loading" />Carregando seus itens…</div>
  <div
    v-else-if="!pantry && items.items.length"
    class="shopping-list"
    aria-label="Lista de compras"
  >
    <template v-for="(item, index) in items.items" :key="item.id">
      <h3
        v-if="
          order === 'CART_LAST' && (index === 0 || items.items[index - 1].status !== item.status)
        "
        class="shopping-list-divider"
      >
        {{ item.status === 'IN_CART' ? 'Já no carrinho' : 'Falta pegar' }}
      </h3>
      <ShoppingListRow
        :item="item"
        @saved="rowSaved"
        @edit="edit"
        @remove="remove"
        @busy="setRowBusy(item.id, $event)"
      />
    </template>
  </div>
  <div v-else-if="items.items.length" class="item-grid">
    <article
      v-for="item in items.items"
      :key="item.id"
      :class="['item-card', { purchased: item.status === 'PURCHASED' }]"
    >
      <div class="item-top">
        <span :class="['stat-icon', pantry ? 'peach' : 'mint']"
          ><Icon :name="pantry ? 'pantry' : 'shopping'" :size="21" /></span
        ><span v-if="item.expired" class="tag red">Vencido</span
        ><span v-else-if="item.lowStock" class="tag amber">Estoque baixo</span
        ><span v-else-if="item.status === 'IN_CART'" class="tag green"
          ><Icon name="check" :size="13" />No carrinho</span
        ><span v-else-if="item.status === 'PURCHASED'" class="tag green">Comprado</span>
        <div class="row-actions">
          <button
            v-if="item.status !== 'PURCHASED'"
            class="icon-button"
            aria-label="Editar produto"
            @click="edit(item)"
          >
            <Icon name="edit" :size="16" /></button
          ><button class="icon-button" aria-label="Excluir produto" @click="remove(item)">
            <Icon name="delete" :size="16" />
          </button>
        </div>
      </div>
      <h3>{{ item.name }}</h3>
      <span class="item-quantity"
        >{{ item.quantity ?? 'A definir' }} <small>{{ item.unit || '' }}</small></span
      >
      <p v-if="!pantry" class="shopping-item-price">
        <strong>{{
          item.totalPriceCents == null ? 'Preço a informar' : money(item.totalPriceCents)
        }}</strong
        ><small v-if="item.unitPriceCents != null"
          >{{ money(item.unitPriceCents) }} / {{ item.unit }}</small
        >
      </p>
      <p class="muted">{{ item.category || 'Sem categoria' }}</p>
      <p v-if="item.notes" class="item-notes">{{ item.notes }}</p>
      <div v-if="pantry" class="item-meta">
        <span>Mínimo: {{ item.minimumQuantity }} {{ item.unit }}</span
        ><span v-if="item.expiresOn">Validade: {{ date(item.expiresOn) }}</span>
      </div>
      <div class="item-bottom">
        <button v-if="pantry" class="button secondary full" @click="openAdjust(item)">
          <Icon name="pantry" :size="16" />Ajustar estoque</button
        ><button
          v-else-if="item.status !== 'PURCHASED'"
          class="button secondary full"
          @click="toggleCart(item)"
          :disabled="saving"
          :aria-pressed="item.status === 'IN_CART'"
        >
          <Icon :name="item.status === 'IN_CART' ? 'check' : 'plus'" :size="16" />{{
            item.status === 'IN_CART' ? 'Retirar do carrinho' : 'Colocar no carrinho'
          }}</button
        ><span v-else class="muted"
          ><Icon name="done" :size="16" />{{
            item.stockApplied ? 'Adicionado à despensa' : 'Compra concluída'
          }}</span
        >
      </div>
    </article>
  </div>
  <EmptyState
    v-else
    :icon="pantry ? 'pantry' : 'shopping'"
    :title="
      q
        ? 'Nenhum item encontrado'
        : pantry
          ? 'Sua despensa, organizada'
          : 'Uma lista pronta para começar'
    "
    :description="
      q
        ? 'Tente outro nome ou remova os filtros.'
        : pantry
          ? 'Adicione seus produtos e acompanhe o que está acabando.'
          : 'Adicione o que precisa comprar. A quantidade pode ficar para depois.'
    "
    ><button class="button primary" @click="edit()">
      <Icon name="plus" :size="16" />Adicionar primeiro item
    </button></EmptyState
  >
  <div v-if="items.total > pageSize" class="pagination">
    <span>Página {{ page + 1 }} de {{ Math.ceil(items.total / pageSize) }}</span
    ><button
      class="icon-button"
      aria-label="Página anterior"
      :disabled="!page || loading"
      @click="changePage(-1)"
    >
      <Icon name="left" /></button
    ><button
      class="icon-button"
      aria-label="Próxima página"
      :disabled="(page + 1) * pageSize >= items.total || loading"
      @click="changePage(1)"
    >
      <Icon name="right" />
    </button>
  </div>
  <div class="tip-strip">
    <Icon name="chat" :size="20" /><span
      >Também vale pedir à Ayla: “{{
        pantry ? 'Acabou o arroz, coloca na minha lista?' : 'Anota café e pão na lista de compras.'
      }}”</span
    ><RouterLink to="/chat" class="text-link">Conversar<Icon name="arrow" :size="16" /></RouterLink>
  </div>
  <Modal
    v-if="form"
    :title="form.id ? 'Editar produto' : pantry ? 'Adicionar à despensa' : 'Adicionar à lista'"
    @close="!saving && (form = null)"
    ><form @submit.prevent="save">
      <div v-if="formError" class="banner error" role="alert">{{ formError }}</div>
      <label
        >Produto<input v-model="form.name" required maxlength="160" placeholder="Ex.: Arroz"
      /></label>
      <div class="form-grid">
        <label
          >Quantidade{{ pantry ? '' : ' (opcional)'
          }}<MaskedInput
            kind="decimal"
            :scale="3"
            v-model="form.quantity"
            min="0"
            max="999999999"
            :required="pantry" /></label
        ><label
          >Unidade<select v-model="form.unit">
            <option v-for="unit in units" :key="unit">{{ unit }}</option>
          </select></label
        >
      </div>
      <div v-if="pantry" class="form-grid">
        <label
          >Estoque mínimo<MaskedInput
            kind="decimal"
            :scale="3"
            v-model="form.minimumQuantity"
            min="0"
            max="999999999"
            required /></label
        ><label>Validade (opcional)<input v-model="form.expiresOn" type="date" /></label>
      </div>
      <template v-if="!pantry">
        <div class="form-grid">
          <label
            >Preço (opcional)<MaskedInput
              v-model="form.price"
              kind="money"
              placeholder="0,00"
              @update:model-value="form.inCart = true"
          /></label>
          <label
            >Esse preço é<select v-model="form.priceMode">
              <option value="TOTAL">Total deste produto</option>
              <option value="UNIT">Por unidade ({{ form.unit }})</option>
            </select></label
          >
        </div>
        <label class="check-label"
          ><input v-model="form.inCart" type="checkbox" />Já está no carrinho</label
        >
      </template>
      <label
        >Categoria (opcional)<input
          v-model="form.category"
          maxlength="80"
          placeholder="Ex.: Mercearia" /></label
      ><label
        >Observações (opcional)<textarea v-model="form.notes" maxlength="500" rows="2" />
      </label>
      <div class="form-actions">
        <button type="button" class="button secondary" :disabled="saving" @click="form = null">
          Cancelar</button
        ><button class="button primary" :disabled="saving">
          {{ saving ? 'Salvando…' : 'Salvar produto' }}
        </button>
      </div>
    </form></Modal
  >
  <Modal v-if="adjust" title="Ajustar estoque" @close="!saving && (adjust = null)"
    ><form @submit.prevent="saveAdjust">
      <p class="muted">{{ adjust.name }} · {{ adjust.quantity }} {{ adjust.unit }} disponíveis</p>
      <div v-if="formError" class="banner error" role="alert">{{ formError }}</div>
      <div class="segmented">
        <button
          type="button"
          :class="{ selected: adjust.action === 'CONSUME' }"
          @click="adjust.action = 'CONSUME'"
        >
          Consumir</button
        ><button
          type="button"
          :class="{ selected: adjust.action === 'ADD' }"
          @click="adjust.action = 'ADD'"
        >
          Adicionar
        </button>
      </div>
      <label
        >Quantidade em {{ adjust.unit
        }}<MaskedInput
          kind="decimal"
          :scale="3"
          v-model="adjust.amount"
          min="0.001"
          max="999999999"
          required
      /></label>
      <div class="form-actions">
        <button type="button" class="button secondary" :disabled="saving" @click="adjust = null">
          Cancelar</button
        ><button class="button primary" :disabled="saving">Atualizar estoque</button>
      </div>
    </form></Modal
  >
</template>
