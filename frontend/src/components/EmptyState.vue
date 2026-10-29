<script setup>
import Icon from './Icon.vue'
defineProps({ icon: { type: String, default: 'sparkles' }, title: String, description: String })
</script>
<template>
  <div class="empty-state">
    <span class="empty-icon"><Icon :name="icon" :size="28" /></span>
    <h3>{{ title }}</h3>
    <p>{{ description }}</p>
    <slot />
  </div>
</template>
