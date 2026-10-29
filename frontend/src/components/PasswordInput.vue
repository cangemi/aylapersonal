<script setup>
import { ref, watch, useAttrs, nextTick } from 'vue'
import { Eye, EyeOff } from '@lucide/vue'
defineOptions({ inheritAttrs: false })
const props = defineProps({ modelValue: { type: String, default: '' } })
const emit = defineEmits(['update:modelValue'])
const attrs = useAttrs()
const visible = ref(false)
const input = ref(null)
async function toggleVisibility() {
  const field = input.value
  if (!field) return
  const start = field.selectionStart ?? field.value.length
  const end = field.selectionEnd ?? start
  const direction = field.selectionDirection || 'none'
  const scrollLeft = field.scrollLeft
  visible.value = !visible.value
  await nextTick()
  if (input.value !== field || !field.isConnected) return
  field.focus({ preventScroll: true })
  field.setSelectionRange(start, end, direction)
  field.scrollLeft = scrollLeft
}
watch(
  () => props.modelValue,
  (value) => {
    if (!value) visible.value = false
  },
)
</script>
<template>
  <span class="password-field">
    <input
      ref="input"
      autocapitalize="none"
      autocorrect="off"
      :spellcheck="false"
      v-bind="attrs"
      :type="visible ? 'text' : 'password'"
      :value="modelValue"
      @input="emit('update:modelValue', $event.target.value)"
    />
    <button
      type="button"
      class="password-toggle"
      :aria-label="visible ? 'Ocultar senha' : 'Mostrar senha'"
      :title="visible ? 'Ocultar senha' : 'Mostrar senha'"
      :aria-pressed="visible"
      :disabled="attrs.disabled === true || attrs.disabled === ''"
      @pointerdown.prevent
      @click.stop.prevent="toggleVisibility"
    >
      <EyeOff v-if="visible" :size="19" aria-hidden="true" />
      <Eye v-else :size="19" aria-hidden="true" />
    </button>
  </span>
</template>
<style scoped>
.password-field {
  position: relative;
  display: block;
  width: 100%;
  min-width: 0;
}
.password-field input {
  padding-right: 52px;
  width: 100%;
}
.password-toggle {
  position: absolute;
  inset-inline-end: 4px;
  top: 50%;
  transform: translateY(-50%);
  display: flex;
  align-items: center;
  justify-content: center;
  width: 42px;
  height: 40px;
  padding: 0;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
}
.password-toggle:hover:not(:disabled) {
  color: var(--text);
  background: var(--panel-hover);
}
.password-toggle:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: -2px;
}
.password-toggle:disabled {
  cursor: default;
  opacity: 0.5;
}
</style>
