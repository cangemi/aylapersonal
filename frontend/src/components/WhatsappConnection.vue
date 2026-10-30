<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { api } from '../lib/api'
import { confirmAction } from '../lib/ui'
import Icon from './Icon.vue'

const emit = defineEmits(['status'])
const status = ref(null),
  mode = ref('own'),
  qr = ref(null),
  aylaQr = ref(null),
  busy = ref(false),
  loading = ref(false),
  error = ref('')
let disposed = false,
  polling = false,
  revision = 0
const ready = computed(() => status.value?.ready ?? status.value?.connected ?? false)
async function refresh() {
  if (polling || disposed) return
  polling = true
  const currentRevision = revision
  try {
    const current = await api('/me/whatsapp/status')
    if (disposed || currentRevision !== revision) return
    status.value = current
    mode.value = current.mode || 'own'
    emit('status', current)
    loading.value = true
    const [own, assistant] = await Promise.all([
      current.connected ? null : api('/me/whatsapp/qr'),
      current.mode === 'dedicated' && !current.ayla?.connected ? api('/me/whatsapp/ayla/qr') : null,
    ])
    if (!disposed && currentRevision === revision) {
      qr.value = own
      aylaQr.value = assistant
      error.value = ''
    }
  } catch (e) {
    if (!disposed) error.value = e.message
  } finally {
    polling = false
    loading.value = false
  }
}
async function selectMode(value) {
  if (busy.value || !status.value || mode.value === value) return
  busy.value = true
  ++revision
  error.value = ''
  try {
    const saved = await api('/me/whatsapp/configuration', { method: 'PUT', body: { mode: value } })
    status.value = saved
    mode.value = saved.mode
    qr.value = aylaQr.value = null
    emit('status', saved)
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
  await refresh()
}
async function action(name, role = 'user') {
  if (
    name === 'logout' &&
    !(await confirmAction(
      'Apagar esta sessão do WhatsApp?',
      'Será necessário ler outro QR code para conectar este número novamente.',
    ))
  )
    return
  busy.value = true
  error.value = ''
  try {
    await api('/me/whatsapp/' + (role === 'ayla' ? 'ayla/' : '') + name, { method: 'POST' })
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
  await refresh()
}
onMounted(() => {
  refresh()
})
onBeforeUnmount(() => {
  disposed = true
})
defineExpose({ refresh })
</script>
<template>
  <div class="whatsapp-connection">
    <p class="muted">
      Converse com a Ayla pelo chat do site ou conecte-a ao seu WhatsApp para levar essa companhia
      com você. Se preferir, você pode conectar depois.
    </p>
    <fieldset class="whatsapp-modes" :disabled="busy || !status">
      <legend>Como você quer usar o WhatsApp?</legend>
      <label class="whatsapp-mode">
        <input
          type="radio"
          name="whatsapp-mode"
          value="own"
          :checked="mode === 'own'"
          @change="selectMode('own')"
        />
        <span
          ><strong>Usar meu próprio número (principal)</strong
          ><small
            >A Ayla usa seu número atual. Sua conversa pessoal continua no grupo Ayla.</small
          ></span
        >
      </label>
      <label class="whatsapp-mode">
        <input
          type="radio"
          name="whatsapp-mode"
          value="dedicated"
          :checked="mode === 'dedicated'"
          @change="selectMode('dedicated')"
        />
        <span
          ><strong>Usar um número exclusivo para a Ayla</strong
          ><small
            >Conecte dois WhatsApps diferentes: o seu e o da Ayla. Os dois são necessários neste
            modo.</small
          ></span
        >
      </label>
    </fieldset>
    <div v-if="error" class="banner error" role="alert">
      {{ error }}
      <button type="button" class="text-link" :disabled="busy" @click="refresh">
        Tentar novamente
      </button>
    </div>
    <div v-if="status?.configurationError" class="banner error" role="alert">
      {{ status.configurationError }}
    </div>
    <section
      v-for="role in mode === 'dedicated' ? ['user', 'ayla'] : ['user']"
      :key="role"
      class="whatsapp-account"
    >
      <div>
        <h3>{{ role === 'ayla' ? 'WhatsApp exclusivo da Ayla' : 'Seu WhatsApp' }}</h3>
        <p class="muted">
          {{
            role === 'ayla'
              ? 'Leia este QR code com o celular do número que será a Ayla.'
              : 'No seu celular, abra WhatsApp → Aparelhos conectados → Conectar aparelho.'
          }}
        </p>
        <p
          v-if="(role === 'ayla' ? status?.ayla : status)?.connected"
          class="banner success"
          role="status"
        >
          Conectado · {{ (role === 'ayla' ? status?.ayla : status)?.number }}
        </p>
        <div class="button-row">
          <button
            v-if="!(role === 'ayla' ? status?.ayla : status)?.connected"
            type="button"
            class="button primary"
            :disabled="busy || !status"
            @click="action('connect', role)"
          >
            <Icon name="phone" :size="17" />{{
              role === 'ayla' ? 'Conectar número da Ayla' : 'Conectar meu WhatsApp'
            }}
          </button>
          <button
            v-else
            type="button"
            class="button secondary"
            :disabled="busy"
            @click="action('disconnect', role)"
          >
            Pausar conexão
          </button>
          <button
            v-if="(role === 'ayla' ? status?.ayla : status)?.sessionSaved"
            type="button"
            class="button secondary"
            :disabled="busy"
            @click="action('restart', role)"
          >
            Reconectar
          </button>
          <button
            v-if="(role === 'ayla' ? status?.ayla : status)?.sessionSaved"
            type="button"
            class="button subtle danger-text"
            :disabled="busy"
            @click="action('logout', role)"
          >
            Sair e apagar sessão
          </button>
        </div>
      </div>
      <div v-if="!(role === 'ayla' ? status?.ayla : status)?.connected" class="qr-area">
        <img
          v-if="!busy && !loading && (role === 'ayla' ? aylaQr : qr)?.qrImageDataUrl"
          :src="(role === 'ayla' ? aylaQr : qr).qrImageDataUrl"
          :alt="role === 'ayla' ? 'QR code do número da Ayla' : 'QR code do seu WhatsApp'"
        />
        <div v-else class="qr-placeholder" role="status" :aria-busy="busy || loading">
          <span v-if="busy || loading" class="qr-loading">
            <span class="qr-spinner" aria-hidden="true"></span>
            <span>{{ loading ? 'Carregando QR code…' : 'Preparando QR code…' }}</span>
          </span>
          <p v-else class="muted">Conecte para gerar o QR code</p>
        </div>
      </div>
    </section>
    <p v-if="mode === 'dedicated'" class="footnote">
      Somente os pedidos enviados pelo seu número acionam a Ayla. Na conversa privada entre vocês,
      ela responde pelo número exclusivo. Em outras conversas e grupos, use Ayla0101: a resposta sai
      pelo seu próprio WhatsApp, na mesma conversa.
    </p>
    <p v-if="mode === 'dedicated' && !ready" class="banner info" role="status">
      Conecte os dois números para ativar este modo. Você pode continuar usando o chat do site
      enquanto isso.
    </p>
  </div>
</template>
<style scoped>
.whatsapp-connection {
  display: grid;
  gap: 18px;
}
.whatsapp-modes {
  display: grid;
  gap: 12px;
  padding: 0;
  border: 0;
  min-width: 0;
}
.whatsapp-modes legend {
  margin-bottom: 12px;
  font-weight: 700;
}
.whatsapp-mode {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: 12px;
  cursor: pointer;
}
.whatsapp-mode:has(input:checked) {
  border-color: var(--accent);
  background: var(--accent-bg);
}
.whatsapp-mode input {
  width: 18px;
  height: 18px;
  flex-shrink: 0;
  margin-top: 3px;
}
.whatsapp-mode span {
  display: grid;
  gap: 6px;
}
.whatsapp-account {
  display: flex;
  justify-content: space-between;
  gap: 20px;
  align-items: center;
}
.whatsapp-account > div:first-child {
  min-width: 0;
  flex: 1;
}
.qr-area,
.qr-placeholder,
.qr-area img {
  width: 200px;
  height: 200px;
  flex-shrink: 0;
}
.qr-placeholder .qr-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  height: 100%;
}
.qr-spinner {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  border: 3px solid var(--line);
  border-top-color: var(--accent);
  animation: spin 700ms linear infinite;
}
@media (max-width: 700px) {
  .whatsapp-account {
    flex-direction: column;
    align-items: stretch;
  }
  .qr-area {
    align-self: center;
  }
}
</style>
