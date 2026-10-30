<script setup>
import Icon from './Icon.vue'
defineProps({ href: { type: String, required: true } })
</script>
<template>
  <a
    :href="href"
    target="_blank"
    rel="noopener noreferrer"
    title="Site externo — abre em uma nova aba"
  >
    <slot /><Icon name="external" :size="17" class="external-link-icon" />
    <span class="sr-only"> (site externo, abre em nova aba)</span>
  </a>
</template>
