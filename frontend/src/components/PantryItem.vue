<script setup>
import { date } from '../lib/format'
import Icon from './Icon.vue'
defineProps({ item: { type: Object, required: true }, view: { type: String, default: 'GRID' } })
defineEmits(['edit', 'remove', 'adjust'])
</script>
<template>
  <article :class="['pantry-item', 'pantry-item-' + view.toLowerCase()]" :aria-label="item.name">
    <div class="pantry-item-info">
      <span v-if="view === 'GRID'" class="stat-icon peach"><Icon name="pantry" :size="21" /></span>
      <h3>{{ item.name }}</h3>
      <p class="pantry-item-category muted">{{ item.category || 'Sem categoria' }}</p>
      <p v-if="item.notes" class="pantry-item-notes muted">{{ item.notes }}</p>
      <span :class="['tag', item.expired ? 'red' : item.lowStock ? 'amber' : 'green']">{{
        item.expired ? 'Vencido' : item.lowStock ? 'Estoque baixo' : 'Em estoque'
      }}</span>
    </div>
    <div class="pantry-item-stock">
      <strong
        >{{ item.quantity }} <small>{{ item.unit }}</small></strong
      >
      <span class="muted">Mínimo: {{ item.minimumQuantity }} {{ item.unit }}</span>
    </div>
    <div class="pantry-item-expiry muted">
      <Icon name="calendar" :size="14" /><span>{{
        item.expiresOn ? 'Validade: ' + date(item.expiresOn) : 'Sem validade informada'
      }}</span>
    </div>
    <div class="pantry-item-actions row-actions">
      <button
        type="button"
        class="icon-button"
        :aria-label="'Editar ' + item.name"
        @click="$emit('edit', item)"
      >
        <Icon name="edit" :size="16" />
      </button>
      <button
        type="button"
        class="icon-button"
        :aria-label="'Excluir ' + item.name"
        @click="$emit('remove', item)"
      >
        <Icon name="delete" :size="16" />
      </button>
    </div>
    <button
      type="button"
      class="button secondary pantry-item-adjust"
      :aria-label="'Ajustar estoque de ' + item.name"
      @click="$emit('adjust', item)"
    >
      <Icon name="pantry" :size="16" />{{ view === 'COMPACT' ? 'Estoque' : 'Ajustar estoque' }}
    </button>
  </article>
</template>
<style scoped>
.pantry-item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  grid-template-areas: 'info actions' 'stock stock' 'expiry expiry' 'adjust adjust';
  gap: 16px 10px;
  padding: 20px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: var(--panel);
  min-width: 0;
}
.pantry-item-info {
  grid-area: info;
  display: grid;
  align-content: start;
  justify-items: start;
  gap: 9px;
  min-width: 0;
}
.pantry-item-info h3 {
  overflow-wrap: anywhere;
  line-height: 1.5;
}
.pantry-item-category,
.pantry-item-notes {
  font-size: 12px;
  line-height: 1.7;
  overflow-wrap: anywhere;
}
.pantry-item-stock {
  grid-area: stock;
  display: grid;
  align-content: center;
  gap: 5px;
}
.pantry-item-stock strong {
  font-size: 28px;
  font-family: var(--font-heading, inherit);
}
.pantry-item-stock small {
  font-size: 13px;
  font-weight: 500;
  color: var(--muted);
}
.pantry-item-stock > span {
  font-size: 11px;
}
.pantry-item-expiry {
  grid-area: expiry;
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 11px;
  line-height: 1.6;
}
.pantry-item-expiry svg {
  flex-shrink: 0;
}
.pantry-item-actions {
  grid-area: actions;
  align-self: start;
}
.pantry-item-adjust {
  grid-area: adjust;
  align-self: end;
}
.pantry-item-list {
  grid-template-columns: minmax(0, 1.4fr) minmax(90px, 0.6fr) minmax(0, 1fr) auto;
  grid-template-areas: 'info stock expiry actions' 'info stock expiry adjust';
  gap: 10px 22px;
}
.pantry-item-list .pantry-item-stock strong {
  font-size: 23px;
}
.pantry-item-list .pantry-item-expiry {
  align-self: center;
}
.pantry-item-compact {
  padding: 12px 16px;
  grid-template-columns: minmax(0, 1fr) minmax(65px, auto) auto;
  grid-template-areas: 'info stock actions' 'info stock adjust';
  gap: 6px 14px;
}
.pantry-item-compact .pantry-item-info {
  gap: 5px;
  align-content: center;
}
.pantry-item-compact .pantry-item-stock strong {
  font-size: 20px;
}
.pantry-item-compact .pantry-item-category,
.pantry-item-compact .pantry-item-notes,
.pantry-item-compact .pantry-item-expiry,
.pantry-item-compact .pantry-item-stock > span {
  display: none;
}
.pantry-item-compact .pantry-item-adjust {
  padding: 7px 10px;
  font-size: 11px;
}
@media (max-width: 700px) {
  .pantry-item-list {
    grid-template-columns: minmax(0, 1fr) auto;
    grid-template-areas: 'info actions' 'stock expiry' 'adjust adjust';
    gap: 14px;
  }
}
@media (max-width: 380px) {
  .pantry-item-compact {
    padding: 12px;
    gap: 7px;
  }
  .pantry-item-compact .pantry-item-stock strong {
    font-size: 17px;
  }
}
</style>
