<script setup>
import { ref, watch, nextTick, useAttrs } from 'vue'
import { numericMask, destinationMask, pastedNumber } from '../lib/inputMasks'

const props = defineProps({
  modelValue: { type: [String, Number], default: '' },
  kind: { type: String, default: 'money' },
  scale: { type: Number, default: undefined },
})
const emit = defineEmits(['update:modelValue'])
const attrs = useAttrs()
const input = ref(null),
  display = ref(''),
  focused = ref(false)
function format(value, pad = false) {
  return props.kind === 'destination'
    ? destinationMask(value)
    : numericMask(value, { kind: props.kind, scale: props.scale, pad })
}
function fromModel(value) {
  const raw = String(value ?? '')
  return props.kind === 'decimal' ? raw.replace('.', ',') : raw
}
function validate(model) {
  if (!input.value || props.kind === 'destination') return
  let error = ''
  if (model && !/^\d+(?:[.,]\d*)?$/.test(model)) error = 'Informe um valor positivo.'
  if (props.kind !== 'money' && model && !error) {
    const value = Number(model)
    if (attrs.min != null && value < Number(attrs.min))
      error = 'O valor mínimo é ' + String(attrs.min).replace('.', ',') + '.'
    if (attrs.max != null && value > Number(attrs.max))
      error = 'O valor máximo é ' + String(attrs.max).replace('.', ',') + '.'
  }
  input.value.setCustomValidity(error)
}
watch(
  () => [props.modelValue, props.kind, props.scale],
  () => {
    display.value = format(fromModel(props.modelValue), !focused.value).display
  },
  { immediate: true },
)
function update(event) {
  const el = event.target,
    raw = el.value,
    caret = el.selectionStart ?? raw.length
  const tokens = raw
    .slice(0, caret)
    .replace(props.kind === 'destination' ? /[^\d@a-z.\-]/gi : /[^\d,\-]/g, '').length
  const result = format(raw)
  display.value = result.display
  el.value = result.display
  validate(result.model)
  emit('update:modelValue', result.model)
  nextTick(() => {
    if (document.activeElement !== el) return
    let count = 0,
      position = 0
    const significant = props.kind === 'destination' ? /[\d@a-z.\-]/i : /[\d,\-]/
    while (position < el.value.length && count < tokens) {
      if (significant.test(el.value[position])) count++
      position++
    }
    el.setSelectionRange(position, position)
  })
}
function blur() {
  focused.value = false
  const result = format(display.value, true)
  display.value = result.display
  validate(result.model)
  emit('update:modelValue', result.model)
}
function paste(event) {
  if (props.kind === 'destination') return
  event.preventDefault()
  const el = input.value,
    pasted = pastedNumber(event.clipboardData.getData('text'), props.kind)
  el.setRangeText(pasted, el.selectionStart, el.selectionEnd, 'end')
  update({ target: el })
}
function beforeInput(event) {
  if (props.kind === 'destination' || event.data !== '.') return
  event.preventDefault()
  const el = input.value
  el.setRangeText(',', el.selectionStart, el.selectionEnd, 'end')
  update({ target: el })
}
</script>
<template>
  <input
    ref="input"
    type="text"
    :value="display"
    :inputmode="kind === 'destination' ? 'text' : kind === 'integer' ? 'numeric' : 'decimal'"
    @focus="focused = true"
    @beforeinput="beforeInput"
    @input="update"
    @blur="blur"
    @paste="paste"
  />
</template>
