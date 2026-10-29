<script setup>
import { reactive, ref, watch, onBeforeUnmount, nextTick } from 'vue'
import { write } from '../lib/api'
import { units, money, moneyInput } from '../lib/format'
import { shoppingRowPayload } from '../lib/shoppingList'
import MaskedInput from './MaskedInput.vue'
import Icon from './Icon.vue'
const props = defineProps({ item: { type: Object, required: true } })
const emit = defineEmits(['saved', 'edit', 'remove', 'busy'])
const draft = reactive({ quantity: '', unit: 'UN', price: '', priceMode: 'TOTAL' })
const saving = ref(false),
  dirty = ref(false),
  error = ref('')
let pending,
  focused = false,
  alive = true,
  baseline = ''
function busy() {
  emit('busy', focused || saving.value || dirty.value)
}
function sync(item) {
  Object.assign(draft, {
    quantity: item.quantity == null ? '' : String(item.quantity),
    unit: item.unit || 'UN',
    price:
      item.unitPriceCents != null
        ? moneyInput(item.unitPriceCents)
        : item.totalPriceCents != null
          ? moneyInput(item.totalPriceCents)
          : '',
    priceMode:
      item.unitPriceCents != null
        ? 'UNIT'
        : item.totalPriceCents != null
          ? 'TOTAL'
          : draft.priceMode,
  })
  baseline = JSON.stringify(draft)
}
watch(
  () => props.item,
  (item) => {
    if (!dirty.value && !saving.value) sync(item)
  },
  { immediate: true },
)
function changed() {
  dirty.value = JSON.stringify(draft) !== baseline
  error.value = ''
  busy()
}
function unitChanged() {
  changed()
  if (draft.quantity !== '') return save()
}
function modeChanged() {
  changed()
  if (draft.price !== '') return save()
}
function discard() {
  sync(props.item)
  dirty.value = false
  error.value = ''
  busy()
}
function focusIn() {
  focused = true
  busy()
}
function focusOut(event) {
  if (!event.currentTarget.contains(event.relatedTarget)) {
    focused = false
    busy()
  }
}
async function save() {
  if (pending) return pending
  if (!dirty.value) return true
  saving.value = true
  error.value = ''
  busy()
  pending = (async () => {
    await Promise.resolve()
    try {
      const result = await write(
        '/shopping-list/items/' + props.item.id,
        'PATCH',
        shoppingRowPayload(props.item, draft),
      )
      if (alive) {
        dirty.value = false
        sync(result)
        emit('saved', result)
      }
      return true
    } catch (e) {
      if (alive)
        error.value =
          e.status === 409
            ? 'Esse item foi alterado. Seus valores foram mantidos; recarregue a lista antes de tentar novamente.'
            : e.message
      return false
    } finally {
      saving.value = false
      pending = null
      if (alive) busy()
    }
  })()
  return pending
}
async function toggle(event) {
  const target = event.target.checked
  if (!(await save())) {
    event.target.checked = props.item.status === 'IN_CART'
    return
  }
  await nextTick()
  if ((props.item.status === 'IN_CART') === target) return
  saving.value = true
  busy()
  try {
    const result = await write('/shopping-list/items/' + props.item.id, 'PATCH', {
      version: props.item.version,
      inCart: target,
    })
    if (alive) emit('saved', result)
  } catch (e) {
    if (alive) error.value = e.message
    event.target.checked = props.item.status === 'IN_CART'
  } finally {
    saving.value = false
    if (alive) busy()
  }
}
onBeforeUnmount(() => {
  alive = false
  emit('busy', false)
})
</script>
<template>
  <article
    :class="['shopping-list-row', { 'in-cart': item.status === 'IN_CART' }]"
    :aria-label="item.name"
    @focusin="focusIn"
    @focusout="focusOut"
  >
    <label class="shopping-row-check"
      ><input
        type="checkbox"
        :checked="item.status === 'IN_CART'"
        :disabled="saving"
        :aria-label="'No carrinho: ' + item.name"
        @change="toggle"
      /><span class="sr-only">No carrinho</span></label
    >
    <div class="shopping-row-name">
      <strong>{{ item.name }}</strong
      ><span :class="['shopping-row-state', { picked: item.status === 'IN_CART' }]"
        >{{ item.status === 'IN_CART' ? 'No carrinho' : 'Falta pegar'
        }}<template v-if="item.status === 'IN_CART' && item.totalPriceCents == null">
          · informe o preço</template
        ></span
      ><small v-if="item.category || item.notes" class="muted"
        >{{ item.category }}{{ item.category && item.notes ? ' · ' : '' }}{{ item.notes }}</small
      >
    </div>
    <label class="shopping-row-quantity"
      >Quantidade<MaskedInput
        v-model="draft.quantity"
        kind="decimal"
        :scale="3"
        min="0.001"
        max="999999999"
        placeholder="1"
        :aria-label="'Quantidade: ' + item.name"
        :disabled="saving"
        @update:model-value="changed"
        @change="save"
    /></label>
    <label class="shopping-row-unit"
      >Unidade<select
        v-model="draft.unit"
        :aria-label="'Unidade: ' + item.name"
        :disabled="saving"
        @change="unitChanged"
      >
        <option v-for="unit in units" :key="unit">{{ unit }}</option>
      </select></label
    >
    <label class="shopping-row-price"
      >Preço (R$)<MaskedInput
        v-model="draft.price"
        kind="money"
        placeholder="Sem preço"
        :aria-label="'Preço: ' + item.name"
        :disabled="saving"
        @update:model-value="changed"
        @change="save"
      /><small v-if="item.unitPriceCents != null"
        >Total: {{ money(item.totalPriceCents) }}</small
      ></label
    >
    <label class="shopping-row-mode"
      >Tipo de preço<select
        v-model="draft.priceMode"
        :aria-label="'Tipo de preço: ' + item.name"
        :disabled="saving"
        @change="modeChanged"
      >
        <option value="TOTAL">Total do item</option>
        <option value="UNIT">Por {{ draft.unit }}</option>
      </select></label
    >
    <div class="shopping-row-actions">
      <button
        class="icon-button"
        :disabled="saving || dirty"
        :aria-label="'Editar detalhes: ' + item.name"
        @click="emit('edit', item)"
      >
        <Icon name="edit" :size="16" /></button
      ><button
        class="icon-button"
        :disabled="saving"
        :aria-label="'Excluir: ' + item.name"
        @click="emit('remove', item)"
      >
        <Icon name="delete" :size="16" />
      </button>
    </div>
    <div v-if="dirty || saving || error" class="shopping-row-feedback">
      <span v-if="error" class="error-text" role="alert">{{ error }}</span
      ><span v-else class="muted" role="status">{{
        saving ? 'Salvando…' : 'Alterações ainda não salvas'
      }}</span
      ><button v-if="dirty && !saving" class="text-link" @click="save">Salvar</button
      ><button v-if="dirty && !saving" class="text-link" @click="discard">Descartar</button>
    </div>
  </article>
</template>
