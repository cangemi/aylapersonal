<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import Icon from './Icon.vue'
import Modal from './Modal.vue'
const open = ref(false),
  prompt = ref(null),
  installing = ref(false)
const standalone = window.matchMedia('(display-mode: standalone)')
const installed = ref(standalone.matches || window.navigator.standalone === true)
const ios =
  /iPhone|iPad|iPod/.test(navigator.userAgent) ||
  (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1)
function available(event) {
  event.preventDefault()
  prompt.value = event
}
function complete() {
  installed.value = true
  open.value = false
  prompt.value = null
}
function modeChanged(event) {
  if (event.matches) complete()
}
async function install() {
  if (!prompt.value) {
    open.value = true
    return
  }
  installing.value = true
  const pending = prompt.value
  try {
    await pending.prompt()
    const result = await pending.userChoice
    if (result.outcome === 'accepted') open.value = false
  } catch {
    open.value = true
  } finally {
    prompt.value = null
    installing.value = false
  }
}
onMounted(() => {
  window.addEventListener('beforeinstallprompt', available)
  window.addEventListener('appinstalled', complete)
  standalone.addEventListener('change', modeChanged)
})
onBeforeUnmount(() => {
  window.removeEventListener('beforeinstallprompt', available)
  window.removeEventListener('appinstalled', complete)
  standalone.removeEventListener('change', modeChanged)
})
</script>
<template>
  <button v-if="!installed" class="install-app-button" :disabled="installing" @click="install">
    <Icon name="download" :size="17" /><span>Instalar Ayla</span>
  </button>
  <Modal v-if="open" title="Ayla na sua tela inicial" @close="open = false">
    <p>Um atalho só nosso: abra a Ayla como aplicativo, direto na conversa.</p>
    <p v-if="ios">
      No Safari, toque em <strong>Compartilhar</strong> e depois em
      <strong>Adicionar à Tela de Início</strong>. Ative “Abrir como App”, se aparecer.
    </p>
    <p v-else>
      Abra o menu do navegador e escolha <strong>Instalar aplicativo</strong> ou
      <strong>Adicionar à tela inicial</strong>. No Android, use o Chrome ou outro navegador
      compatível.
    </p>
    <p class="muted footnote">
      A instalação precisa de HTTPS com certificado confiável. Na sua rede local, instale o
      certificado CA da Ayla no aparelho; apenas ignorar o aviso do navegador pode impedir a
      instalação. O servidor deve ficar ligado para conversar.
    </p>
    <div class="form-actions">
      <button class="button primary" @click="open = false">Entendi</button>
    </div>
  </Modal>
</template>
