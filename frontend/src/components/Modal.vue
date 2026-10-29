<script setup>
import { ref, onMounted } from 'vue'
import Icon from './Icon.vue'
defineProps({ title: String, wide: Boolean })
const emit = defineEmits(['close'])
const dialog = ref()
onMounted(() => dialog.value.showModal())
</script>
<template>
  <dialog
    ref="dialog"
    :aria-label="title"
    :class="['modal', { wide }]"
    @cancel.prevent="emit('close')"
    @click="
      (event) => {
        if (event.target === dialog) emit('close')
      }
    "
  >
    <div class="modal-head">
      <h2>{{ title }}</h2>
      <button class="icon-button" aria-label="Fechar" @click="emit('close')">
        <Icon name="close" />
      </button>
    </div>
    <slot />
  </dialog>
</template>
