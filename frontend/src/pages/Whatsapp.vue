<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { api } from '../lib/api'
import { toast, confirmAction } from '../lib/ui'
import Icon from '../components/Icon.vue'
import MaskedInput from '../components/MaskedInput.vue'
import Modal from '../components/Modal.vue'
import EmptyState from '../components/EmptyState.vue'
import WhatsappConnection from '../components/WhatsappConnection.vue'
defineProps({ embedded: Boolean })
const status = ref(null),
  connection = ref(null),
  chats = ref([]),
  error = ref(''),
  busy = ref(false),
  form = ref(null),
  formError = ref('')
const ready = computed(() => status.value?.ready ?? status.value?.connected ?? false)
const dedicated = computed(() => status.value?.mode === 'dedicated')
let timer,
  disposed = false,
  polling = false
async function load() {
  if (polling) return
  polling = true
  try {
    const c = await api('/me/whatsapp/chats')
    if (disposed) return
    chats.value = c
    error.value = ''
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
async function createPersonalChat() {
  busy.value = true
  try {
    const result = await api('/me/whatsapp/personal-chat', { method: 'POST' })
    if (result.mode === 'dedicated') {
      window.location.assign(result.url)
      return
    }
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
    await connection.value?.refresh()
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
    await api('/me/whatsapp/chats' + (f.id ? '/' + f.id : ''), {
      method: f.id ? 'PUT' : 'POST',
      body,
    })
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
  <div v-if="!embedded" class="page-heading">
    <div>
      <span class="eyebrow">SEMPRE POR PERTO</span>
      <h1>Uma conversa de distância.</h1>
      <p>Conecte seu WhatsApp e leve a Ayla com você.</p>
    </div>
    <span :class="['pill', ready ? 'green' : 'neutral']"
      ><span class="status-dot" />{{
        ready ? 'Conectado' : status?.state === 'connecting' ? 'Conectando…' : 'Desconectado'
      }}</span
    >
  </div>
  <div v-if="error" class="banner error" role="alert">
    {{ error }}<button class="text-link" @click="load">Atualizar</button>
  </div>
  <section class="panel">
    <WhatsappConnection ref="connection" @status="status = $event" />
  </section>
  <section class="panel personal-chat-panel">
    <div class="panel-heading">
      <div>
        <h2>Seu cantinho com a Ayla</h2>
        <p>
          {{
            dedicated
              ? 'Converse diretamente com o número exclusivo da Ayla pelo seu WhatsApp.'
              : 'Um grupo chamado Ayla, só com seu número, para conversar com ela no WhatsApp.'
          }}
        </p>
      </div>
      <Icon name="chat" />
    </div>
    <p class="muted">
      Essa será sua conversa principal. A anterior passa a atender quando você chamar Ayla0101.
    </p>
    <div class="button-row">
      <button
        class="button primary"
        :disabled="busy || !ready"
        :title="dedicated ? 'Abre o WhatsApp fora da Ayla' : undefined"
        @click="createPersonalChat"
      >
        <Icon name="plus" :size="17" />{{
          busy
            ? 'Aguarde…'
            : status?.personalChatId || dedicated
              ? 'Continuar conversa com Ayla'
              : 'Começar conversa com Ayla'
        }}
        <Icon v-if="dedicated" name="external" :size="17" />
        <span v-if="dedicated" class="sr-only"> (abre o WhatsApp fora da Ayla)</span>
      </button>
    </div>
    <p v-if="!dedicated && status?.personalChatId" class="footnote">
      Pronto! Abra seu WhatsApp e procure o grupo Ayla. Você já pode falar por lá.
    </p>
    <p v-else-if="!ready" class="footnote">
      Conclua as conexões acima para conversar pelo WhatsApp.
    </p>
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
