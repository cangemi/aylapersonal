<script setup>
import { onMounted, onUnmounted, ref } from 'vue'

defineProps({ compact: Boolean })
const brandText = ref('')
const brandSettled = ref(false)
let brandTimer
let running = false

function animate() {
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    brandText.value = 'A'
    brandSettled.value = true
    return
  }
  if (running) return
  running = true
  brandSettled.value = false
  brandText.value = ''
  const frames = [
    ['A', 240],
    ['Ay', 150],
    ['Ayl', 190],
    ['Ayla', 1500],
    ['Ayl', 150],
    ['Ay', 180],
    ['A', 0],
  ]
  let index = 0
  function advance() {
    const [text, delay] = frames[index++]
    brandText.value = text
    brandSettled.value = index === frames.length
    if (index < frames.length) brandTimer = window.setTimeout(advance, delay)
    else running = false
  }
  brandTimer = window.setTimeout(advance, 180)
}
onMounted(animate)
onUnmounted(() => window.clearTimeout(brandTimer))
</script>

<template>
  <RouterLink
    to="/"
    :class="['login-logo', { 'is-settled': brandSettled, 'login-logo-compact': compact }]"
    aria-label="Ayla"
    @mouseenter="animate"
    @focus="animate"
  >
    <span class="login-logo-name" aria-hidden="true">
      <TransitionGroup name="brand-letter" tag="span" class="login-logo-letters">
        <span v-for="(letter, index) in brandText" :key="index" class="login-logo-letter">{{
          letter
        }}</span> </TransitionGroup
      ><span class="login-logo-star">✦</span>
    </span>
  </RouterLink>
</template>
