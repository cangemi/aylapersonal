<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../lib/api'
import Modal from './Modal.vue'
import Icon from './Icon.vue'

const emit = defineEmits(['close'])
const status = ref(null),
  busy = ref(false),
  loading = ref(true),
  error = ref(''),
  ready = ref(false),
  pictureWarning = ref(false)
async function load() {
  loading.value = true
  error.value = ''
  try {
    status.value = await api('/me/whatsapp/status')
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}
async function create() {
  busy.value = true
  error.value = ''
  try {
    const result = await api('/me/whatsapp/personal-chat', { method: 'POST' })
    ready.value = true
    pictureWarning.value = result.pictureUpdated === false
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}
onMounted(load)
</script>
<template>
  <Modal title="Começar uma conversa com Ayla no WhatsApp" @close="!busy && emit('close')">
    <div class="personal-chat-preview">
      <img src="/ayla-chat.png" alt="Avatar da Ayla" width="80" height="80" />
      <div>
        <strong>Ayla</strong>
        <p>Sua conversa pessoal no WhatsApp.</p>
      </div>
    </div>
    <p>
      Um grupo só com seu número, com a foto da Ayla. Ele será sua conversa principal; se já
      existir, vamos reutilizá-lo.
    </p>
    <p class="muted">A conversa principal anterior passa a atender quando você chamar Ayla0101.</p>
    <div v-if="error" class="banner error" role="alert">
      {{ error }}<button class="text-link" :disabled="busy" @click="load">Atualizar conexão</button>
    </div>
    <p v-if="ready" class="banner success" role="status">
      Tudo pronto! Abra seu WhatsApp e procure o grupo Ayla.
    </p>
    <p v-if="pictureWarning" class="banner error" role="alert">
      A conversa está pronta, mas a foto não foi atualizada. Tente novamente para aplicar a imagem.
    </p>
    <p v-if="!loading && status && !status.connected" class="muted">
      Conecte seu WhatsApp para continuar.
      <RouterLink to="/whatsapp" class="text-link" @click="emit('close')"
        >Ir para WhatsApp</RouterLink
      >
    </p>
    <div class="form-actions">
      <button class="button secondary" :disabled="busy" @click="emit('close')">Fechar</button>
      <button
        class="button primary"
        :disabled="busy || loading || !status?.connected"
        @click="create"
      >
        <Icon name="phone" :size="17" />{{
          busy
            ? 'Preparando…'
            : status?.personalChatId || ready
              ? 'Continuar conversa com Ayla'
              : 'Começar conversa com Ayla'
        }}
      </button>
    </div>
  </Modal>
</template>
