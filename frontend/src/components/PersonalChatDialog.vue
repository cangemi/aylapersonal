<script setup>
import { ref, computed, onMounted } from 'vue'
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
const connected = computed(() => status.value?.ready ?? status.value?.connected ?? false)
const dedicated = computed(() => status.value?.mode === 'dedicated')
async function load() {
  loading.value = true
  error.value = ''
  try {
    status.value = await api('/me/whatsapp/status')
    if (dedicated.value && connected.value) await create()
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
    if (result.mode === 'dedicated') {
      window.location.assign(result.url)
      return
    }
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
    <p v-if="!loading && !connected">
      Para conversar com a Ayla pelo WhatsApp, conecte seu número nas configurações. Se escolher um
      número exclusivo para a Ayla, conecte também o seu WhatsApp. Você pode continuar conversando
      pelo chat do site sem conectar o WhatsApp.
    </p>
    <p v-else-if="dedicated">
      Vamos abrir a conversa privada com o número exclusivo da Ayla no seu WhatsApp.
    </p>
    <p v-else-if="connected">
      Um grupo só com seu número, com a foto da Ayla. Ele será sua conversa principal; se já
      existir, vamos reutilizá-lo.
    </p>
    <p v-if="connected" class="muted">
      A conversa principal anterior passa a atender quando você chamar Ayla0101.
    </p>
    <div v-if="error" class="banner error" role="alert">
      {{ error }}<button class="text-link" :disabled="busy" @click="load">Atualizar conexão</button>
    </div>
    <p v-if="ready" class="banner success" role="status">
      Tudo pronto! Abra seu WhatsApp e procure o grupo Ayla.
    </p>
    <p v-if="pictureWarning" class="banner error" role="alert">
      A conversa está pronta, mas a foto não foi atualizada. Tente novamente para aplicar a imagem.
    </p>
    <div class="form-actions">
      <button class="button secondary" :disabled="busy" @click="emit('close')">
        {{ connected ? 'Fechar' : 'Cancelar' }}
      </button>
      <RouterLink
        v-if="!loading && !connected"
        to="/whatsapp"
        class="button primary"
        @click="emit('close')"
        >Configurar WhatsApp</RouterLink
      >
      <button
        v-else
        class="button primary"
        :disabled="busy || loading || !connected"
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
