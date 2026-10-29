<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { api } from '../lib/api'
import { toast, confirmAction } from '../lib/ui'
import { timestamp } from '../lib/format'
import Icon from '../components/Icon.vue'
import MaskedInput from '../components/MaskedInput.vue'
import Modal from '../components/Modal.vue'
import EmptyState from '../components/EmptyState.vue'
const status = ref(null),
  qr = ref(null),
  chats = ref([]),
  error = ref(''),
  busy = ref(false),
  form = ref(null),
  formError = ref('')
let timer,
  disposed = false,
  polling = false
async function load() {
  if (polling) return
  polling = true
  try {
    const [s, c] = await Promise.all([api('/me/whatsapp/status'), api('/me/whatsapp/chats')])
    if (disposed) return
    status.value = s
    chats.value = c
    error.value = ''
    if (!s.connected) {
      qr.value = await api('/me/whatsapp/qr')
    } else qr.value = null
  } catch (e) {
    if (!disposed) error.value = e.message
  } finally {
    polling = false
  }
}
onMounted(() => {
  load()
  timer = setInterval(load, 6000)
})
onBeforeUnmount(() => {
  disposed = true
  clearInterval(timer)
})
async function action(name) {
  if (
    name === 'logout' &&
    !(await confirmAction(
      'Sair do WhatsApp?',
      'A sessão salva será apagada. Será necessário ler outro QR code.',
    ))
  )
    return
  busy.value = true
  try {
    await api('/me/whatsapp/' + name, { method: 'POST' })
    await load()
    toast(name === 'connect' ? 'Conexão solicitada.' : 'Solicitação concluída.')
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    busy.value = false
  }
}
async function createPersonalChat() {
  busy.value = true
  try {
    const result = await api('/me/whatsapp/personal-chat', { method: 'POST' })
    toast(
      result.created
        ? 'Grupo Ayla criado! Abra seu WhatsApp para conversar.'
        : 'Sua conversa Ayla está pronta. Abra o grupo no WhatsApp.',
    )
    if (result.pictureUpdated === false)
      toast(
        'A conversa está pronta, mas a foto não foi atualizada. Você pode tentar novamente.',
        'error',
      )
    await load()
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    busy.value = false
  }
}
function edit(chat) {
  formError.value = ''
  form.value = chat
    ? {
        ...chat,
        destination: chat.chatId || chat.phone,
        originalDestination: chat.chatId || chat.phone,
      }
    : { name: '', destination: '', enabled: true, mentionOnly: true }
}
async function save() {
  busy.value = true
  formError.value = ''
  try {
    const f = form.value
    const body = { name: f.name, enabled: f.enabled, mentionOnly: f.mentionOnly }
    if (!f.id || f.destination.trim() !== f.originalDestination) {
      if (f.destination.includes('@')) body.chatId = f.destination.trim()
      else body.phone = f.destination.replace(/\D/g, '')
    }
    await api('/me/whatsapp/chats' + (f.id ? '/' + f.id : ''), { method: f.id ? 'PUT' : 'POST', body })
    form.value = null
    toast('Conversa salva.')
    await load()
  } catch (e) {
    formError.value = e.message
  } finally {
    busy.value = false
  }
}
async function remove(chat) {
  if (!(await confirmAction('Remover esta conversa?', chat.name))) return
  try {
    await api('/me/whatsapp/chats/' + chat.id, { method: 'DELETE' })
    toast('Conversa removida.')
    await load()
  } catch (e) {
    toast(e.message, 'error')
  }
}
</script>
<template>
  <div class="page-heading">
    <div>
      <span class="eyebrow">SEMPRE POR PERTO</span>
      <h1>Uma conversa de distância.</h1>
      <p>Conecte seu WhatsApp e leve a Ayla com você.</p>
    </div>
    <span :class="['pill', status?.connected ? 'green' : 'neutral']"
      ><span class="status-dot" />{{
        status?.connected
          ? 'Conectado'
          : status?.state === 'connecting'
            ? 'Conectando…'
            : 'Desconectado'
      }}</span
    >
  </div>
  <div v-if="error" class="banner error" role="alert">
    {{ error }}<button class="text-link" @click="load">Atualizar</button>
  </div>
  <section class="panel connection-panel">
    <div>
      <span class="stat-icon mint large"><Icon name="phone" :size="30" /></span>
      <h2>{{ status?.connected ? 'Pronto para conversar.' : 'Vamos conectar?' }}</h2>
      <p class="muted">
        {{
          status?.connected
            ? `${status.name || 'Seu WhatsApp'} · ${status.number || ''}`
            : 'No celular, abra WhatsApp → Dispositivos conectados → Conectar dispositivo.'
        }}
      </p>
      <p v-if="status?.sessionSaved" class="footnote">
        <Icon name="shield" :size="14" />Sessão salva · {{ timestamp(status.sessionSavedAt) }}
      </p>
      <div class="button-row">
        <button
          v-if="!status?.connected"
          class="button primary"
          :disabled="busy"
          @click="action('connect')"
        >
          <Icon name="phone" :size="17" />Conectar WhatsApp</button
        ><button
          v-if="status?.connected"
          class="button secondary"
          :disabled="busy"
          @click="action('disconnect')"
        >
          Pausar conexão</button
        ><button class="button secondary" :disabled="busy" @click="action('restart')">
          <Icon name="refresh" :size="16" />Reconectar</button
        ><button
          v-if="status?.sessionSaved"
          class="button subtle danger-text"
          :disabled="busy"
          @click="action('logout')"
        >
          Sair e apagar sessão
        </button>
      </div>
    </div>
    <div class="qr-area">
      <img
        v-if="qr?.qrImageDataUrl"
        :src="qr.qrImageDataUrl"
        alt="QR code para conectar o WhatsApp"
      />
      <div v-else :class="['qr-placeholder', { connected: status?.connected }]">
        <Icon :name="status?.connected ? 'done' : 'phone'" :size="54" /><span>{{
          status?.connected
            ? 'Tudo conectado'
            : !status || status?.state === 'connecting' || busy
              ? 'Carregando QR code…'
              : 'Seu QR code aparece aqui'
        }}</span>
      </div>
    </div>
  </section>
  <section class="panel personal-chat-panel">
    <div class="panel-heading">
      <div>
        <h2>Seu cantinho com a Ayla</h2>
        <p>Um grupo chamado Ayla, só com seu número, para conversar com ela no WhatsApp.</p>
      </div>
      <Icon name="chat" />
    </div>
    <p class="muted">
      Essa será sua conversa principal. A anterior passa a atender quando você chamar Ayla0101.
    </p>
    <div class="button-row">
      <button
        class="button primary"
        :disabled="busy || !status?.connected"
        @click="createPersonalChat"
      >
        <Icon name="plus" :size="17" />{{
          busy
            ? 'Aguarde…'
            : status?.personalChatId
              ? 'Continuar conversa com Ayla'
              : 'Começar conversa com Ayla'
        }}
      </button>
    </div>
    <p v-if="status?.personalChatId" class="footnote">
      Pronto! Abra seu WhatsApp e procure o grupo Ayla. Você já pode falar por lá.
    </p>
    <p v-else-if="!status?.connected" class="footnote">Conecte seu WhatsApp para criar o grupo.</p>
  </section>
  <div class="tip-strip">
    <Icon name="shield" /><span
      >Somente mensagens enviadas pelo seu próprio número podem chamar a Ayla. Em outra conversa ou
      grupo, use <strong>Ayla0101</strong>. Mensagens de terceiros não acionam respostas.</span
    >
  </div>
  <section class="panel">
    <div class="panel-heading">
      <div>
        <h2>Conversas configuradas</h2>
        <p>Uma conversa principal para o dia a dia. As demais atendem chamadas pontuais.</p>
      </div>
      <button class="button secondary" @click="edit()">
        <Icon name="plus" :size="16" />Adicionar conversa
      </button>
    </div>
    <div class="table-wrap" v-if="chats.length">
      <table class="responsive-table">
        <thead>
          <tr>
            <th>Conversa</th>
            <th>Modo de resposta</th>
            <th>Estado</th>
            <th><span class="sr-only">Ações</span></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="chat in chats" :key="chat.id">
            <td data-label="Conversa">
              <strong>{{ chat.name }}</strong
              ><small class="block muted">{{ chat.phone || chat.chatId }}</small>
            </td>
            <td data-label="Modo">
              {{ chat.mentionOnly ? 'Chamada pontual · Ayla0101' : 'Conversa principal' }}
            </td>
            <td data-label="Estado">
              <span :class="['tag', chat.enabled ? 'green' : '']">{{
                chat.enabled ? 'Ativa' : 'Pausada'
              }}</span>
            </td>
            <td data-label="Ações">
              <div class="row-actions">
                <button class="icon-button" aria-label="Editar conversa" @click="edit(chat)">
                  <Icon name="edit" :size="16" /></button
                ><button class="icon-button" aria-label="Excluir conversa" @click="remove(chat)">
                  <Icon name="delete" :size="16" />
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <EmptyState
      v-else
      icon="chat"
      title="Por onde vamos conversar?"
      description="Cadastre uma conversa ou envie configurar Ayla0101 pelo próprio número no WhatsApp."
    />
  </section>
  <Modal
    v-if="form"
    :title="form.id ? 'Editar conversa' : 'Adicionar conversa'"
    @close="!busy && (form = null)"
    ><form @submit.prevent="save">
      <div v-if="formError" class="banner error" role="alert">{{ formError }}</div>
      <label
        >Nome<input
          v-model="form.name"
          required
          maxlength="120"
          placeholder="Ex.: Minha conversa" /></label
      ><label
        >Telefone ou identificador do grupo<MaskedInput
          kind="destination"
          v-model="form.destination"
          required
          placeholder="5511999999999 ou …@g.us"
        /><small>Telefone com código do país e DDD.</small></label
      ><label class="check-label"
        ><input v-model="form.enabled" type="checkbox" />Conversa habilitada</label
      ><label class="check-label"
        ><input v-model="form.mentionOnly" type="checkbox" />Chamada pontual por Ayla0101 (contexto
        isolado)</label
      >
      <p class="footnote">
        Desmarcar define esta como a principal e substitui a anterior. Só a principal acessa suas
        memórias e pode consultar contexto das outras.
      </p>
      <div class="form-actions">
        <button type="button" class="button secondary" :disabled="busy" @click="form = null">
          Cancelar</button
        ><button class="button primary" :disabled="busy">Salvar conversa</button>
      </div>
    </form></Modal
  >
</template>
