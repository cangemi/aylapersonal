<script setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { api, auth } from '../lib/api'
import { transcripts } from '../lib/chatState'
import {
  sendQueuedChat,
  waitForChatJob,
  pendingChatJobs,
  acknowledgeChatJob,
} from '../lib/chatQueue'
import { toast, confirmAction } from '../lib/ui'
import DOMPurify from 'dompurify'
import { marked } from 'marked'
import Icon from '../components/Icon.vue'
import Modal from '../components/Modal.vue'
import PersonalChatDialog from '../components/PersonalChatDialog.vue'
const personalChatOpen = ref(false)
const conversationPicker = ref(false)
const composerInput = ref(null)
const deleting = ref(null)
function startNewConversation() {
  if (busy.value || deleting.value) return
  conversationPicker.value = false
  newForm.value = true
}
const conversations = ref([]),
  active = ref(null),
  text = ref(''),
  busy = ref(false),
  loading = ref(false),
  historyLoading = ref(false),
  historyError = ref(''),
  hasMore = ref(false),
  nextOffset = ref(0),
  error = ref(''),
  attachments = ref([]),
  filesInput = ref(),
  scroll = ref(),
  forceSearch = ref(false),
  audioReply = ref(false),
  recording = ref(false),
  reading = ref(false),
  newForm = ref(false),
  title = ref(''),
  newBusy = ref(false)
const availableHeight = ref(null)
const queueStatus = ref('')
const queueAbort = new AbortController()
const queueOptions = {
  signal: queueAbort.signal,
  onStatus: (job) => {
    queueStatus.value = job.message || 'A Ayla está preparando sua resposta.'
  },
}
function updateViewport() {
  const headerHeight = document.querySelector('.topbar')?.getBoundingClientRect().height || 62
  const dockHeight = document.querySelector('.mobile-dock')?.getBoundingClientRect().height || 0
  availableHeight.value =
    window.innerWidth <= 900
      ? Math.max(
          0,
          (window.visualViewport?.height || window.innerHeight) - headerHeight - dockHeight,
        ) + 'px'
      : null
}
async function suggest(prompt) {
  text.value = prompt
  await nextTick()
  composerInput.value?.focus()
}
let recorder,
  stream,
  recordTimer,
  destroyed = false
let historyRequest = 0
let selectionRequest = 0
const messages = computed(() => transcripts[active.value] || [])
const owner = auth.session?.user?.id
const markdown = (value) =>
  DOMPurify.sanitize(marked.parse(value || '', { breaks: true }), {
    FORBID_TAGS: ['img', 'video', 'audio', 'iframe', 'style', 'form'],
    FORBID_ATTR: ['style'],
  })
async function bottom() {
  await nextTick()
  scroll.value?.scrollTo({
    top: messages.value.length ? scroll.value.scrollHeight : 0,
    behavior: messages.value.length ? 'smooth' : 'auto',
  })
}
const deletedConversations = new Set()
async function load() {
  loading.value = true
  try {
    conversations.value = (await api('/conversations')).filter(
      (conversation) => !deletedConversations.has(conversation.conversationId),
    )
    active.value =
      (
        conversations.value.find((c) => c.conversationId === active.value) ||
        conversations.value.find((c) => c.current) ||
        conversations.value[0]
      )?.conversationId || null
    error.value = ''
    if (active.value) {
      await loadMessages(active.value)
      await resumeQueued(active.value)
    }
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}
async function resumeQueued(conversation) {
  const pending = await pendingChatJobs(conversation)
  if (!Array.isArray(pending) || !pending.length || destroyed || auth.session?.user?.id !== owner)
    return
  busy.value = true
  try {
    for (const job of pending) {
      const existing = (transcripts[conversation] ||= [])
      if (
        job.userMessage &&
        !existing.some((message) => message.role === 'user' && message.text === job.userMessage)
      )
        existing.push({ role: 'user', text: job.userMessage, jobId: job.id })
      const result = await waitForChatJob(job, queueOptions)
      if (destroyed || auth.session?.user?.id !== owner) return
      const list = (transcripts[conversation] ||= [])
      if (
        !list.some((message) => message.role === 'assistant' && message.jobId === job.id) &&
        !(list.at(-1)?.role === 'assistant' && list.at(-1)?.text === result.response)
      )
        list.push({ role: 'assistant', text: result.response, audio: result.audio, jobId: job.id })
      await acknowledgeChatJob(job.id)
    }
    await bottom()
  } finally {
    busy.value = false
    queueStatus.value = ''
  }
}
async function loadMessages(conversation, older = false) {
  if (!conversation || (older && historyLoading.value)) return
  const revision = ++historyRequest
  const originalHeight = scroll.value?.scrollHeight || 0
  const originalTop = scroll.value?.scrollTop || 0
  historyLoading.value = true
  historyError.value = ''
  try {
    const result = await api(
      `/conversations/${conversation}/messages?limit=100&offset=${older ? nextOffset.value : 0}`,
    )
    if (
      destroyed ||
      revision !== historyRequest ||
      active.value !== conversation ||
      auth.session?.user?.id !== owner
    )
      return
    const incoming = result.items.map((message) => ({ ...message, id: String(message.id) }))
    if (older) {
      const ids = new Set(incoming.map((message) => message.id))
      transcripts[conversation] = [
        ...incoming,
        ...(transcripts[conversation] || []).filter(
          (message) => !message.id || !ids.has(message.id),
        ),
      ]
    } else transcripts[conversation] = incoming
    hasMore.value = result.hasMore
    nextOffset.value = result.nextOffset
    if (older) {
      await nextTick()
      if (scroll.value)
        scroll.value.scrollTop = originalTop + scroll.value.scrollHeight - originalHeight
    } else await bottom()
  } catch (e) {
    if (revision === historyRequest && !destroyed) historyError.value = e.message
  } finally {
    if (revision === historyRequest) historyLoading.value = false
  }
}
async function select(conversation) {
  if (busy.value || deleting.value) return
  const revision = ++selectionRequest
  try {
    await api(`/conversations/${conversation.conversationId}/current`, { method: 'PUT' })
    if (revision !== selectionRequest || destroyed) return
    active.value = conversation.conversationId
    conversationPicker.value = false
    attachments.value = []
    hasMore.value = false
    nextOffset.value = 0
    await loadMessages(conversation.conversationId)
    await resumeQueued(conversation.conversationId)
  } catch (e) {
    toast(e.message, 'error')
  }
}
async function removeConversation(conversation) {
  if (busy.value || deleting.value || recording.value || reading.value) return
  deleting.value = conversation.conversationId
  selectionRequest++
  try {
    const plan = await api(`/conversations/${conversation.conversationId}/deletion`)
    if (
      !(await confirmAction(
        'Apagar esta conversa?',
        `${plan.description} Memórias, finanças e agenda permanecem salvas. Envios ainda na fila serão cancelados.`,
      ))
    )
      return
    const result = await api(
      `/conversations/${conversation.conversationId}?removeWhatsappGroup=${plan.removeWhatsappGroup === true}`,
      { method: 'DELETE' },
    )
    deletedConversations.add(conversation.conversationId)
    delete transcripts[conversation.conversationId]
    conversations.value = conversations.value.filter(
      (item) => item.conversationId !== conversation.conversationId,
    )
    if (active.value === conversation.conversationId) {
      historyRequest++
      active.value = null
      text.value = ''
      attachments.value = []
      historyLoading.value = false
      historyError.value = ''
      hasMore.value = false
      nextOffset.value = 0
      await load()
    }
    toast(
      result.whatsappGroupDeleted
        ? 'Conversa apagada e grupo pessoal removido do WhatsApp.'
        : 'Conversa apagada na Ayla.',
    )
  } catch (error) {
    toast(error.message, 'error')
  } finally {
    deleting.value = null
  }
}
async function create() {
  newBusy.value = true
  try {
    const c = await api('/conversations', {
      method: 'POST',
      body: { title: title.value.trim() || undefined },
    })
    conversations.value.unshift(c)
    active.value = c.conversationId
    historyRequest++
    historyLoading.value = false
    historyError.value = ''
    hasMore.value = false
    nextOffset.value = 0
    transcripts[c.conversationId] = []
    text.value = ''
    error.value = ''
    newForm.value = false
    conversationPicker.value = false
    title.value = ''
    attachments.value = []
    await bottom()
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    newBusy.value = false
  }
}
async function send() {
  if (
    deleting.value ||
    busy.value ||
    historyLoading.value ||
    historyError.value ||
    loading.value ||
    reading.value ||
    recording.value ||
    (!text.value.trim() && !attachments.value.length)
  )
    return
  busy.value = true
  error.value = ''
  let conversation = active.value
  const content =
    text.value.trim() ||
    (attachments.value.some((a) => a.kind === 'audios')
      ? 'Ouça este áudio e me responda.'
      : 'Analise os arquivos que enviei.')
  try {
    if (!conversation) {
      const c = await api('/conversations', {
        method: 'POST',
        body: { title: content.slice(0, 70) },
      })
      conversation = c.conversationId
      conversations.value.unshift(c)
      active.value = conversation
    }
    const list = (transcripts[conversation] ||= [])
    const media = attachments.value.map((a) => ({ ...a }))
    list.push({
      role: 'user',
      text: content,
      files: media.map((a) => ({ name: a.name, mimeType: a.mimeType, kind: a.kind })),
    })
    text.value = ''
    attachments.value = []
    await bottom()
    const payload = {
      message: content,
      conversationId: conversation,
      ...(forceSearch.value ? { searchRequired: true } : {}),
      ...(audioReply.value ? { audioReply: true } : {}),
    }
    for (const kind of ['images', 'audios', 'documents']) {
      const group = media.filter((a) => a.kind === kind)
      if (group.length)
        payload[kind] = group.map((a) => ({
          mimeType: a.mimeType,
          data: a.data,
          ...(kind === 'documents' ? { name: a.name } : {}),
        }))
    }
    try {
      const result = await sendQueuedChat(payload, queueOptions)
      if (auth.session?.user?.id === owner)
        list.push({
          role: 'assistant',
          text: result.response,
          audio: result.audio,
          jobId: result.jobId,
        })
      await acknowledgeChatJob(result.jobId)
    } catch (e) {
      if (destroyed || e.name === 'AbortError') return
      if (auth.session?.user?.id === owner)
        list.push({
          role: 'error',
          text:
            e.message +
            ' Não reenvie uma operação financeira sem conferir se ela já foi concluída.',
        })
    }
    await bottom()
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
    queueStatus.value = ''
  }
}
const documentTypes = {
  pdf: 'application/pdf',
  docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  doc: 'application/msword',
  txt: 'text/plain',
}
async function addFiles(files) {
  reading.value = true
  try {
    for (const file of files) {
      let mime = file.type.split(';')[0]
      const ext = file.name.split('.').pop().toLowerCase()
      if (documentTypes[ext]) mime = documentTypes[ext]
      if (mime === 'audio/x-wav') mime = 'audio/wav'
      const kind = ['image/jpeg', 'image/png', 'image/webp'].includes(mime)
        ? 'images'
        : [
              'audio/ogg',
              'audio/mpeg',
              'audio/mp4',
              'audio/wav',
              'audio/webm',
              'audio/flac',
              'audio/aac',
            ].includes(mime)
          ? 'audios'
          : Object.values(documentTypes).includes(mime)
            ? 'documents'
            : null
      if (!kind) throw new Error('Formato não suportado. Envie imagem, áudio, PDF, Word ou TXT.')
      const group = attachments.value.filter((a) => a.kind === kind)
      const limit = kind === 'images' ? 4 : 6,
        total = kind === 'images' ? 6 : 12,
        count = kind === 'documents' ? 2 : 3
      if (file.name.length > 160) throw new Error('Renomeie o arquivo para até 160 caracteres.')
      if (
        file.size === 0 ||
        file.size > limit * 1024 * 1024 ||
        group.length >= count ||
        group.reduce((n, a) => n + a.size, 0) + file.size > total * 1024 * 1024
      )
        throw new Error(
          `Limite: ${count} ${kind === 'images' ? 'imagens' : kind === 'audios' ? 'áudios' : 'documentos'}, até ${limit} MB por arquivo e ${total} MB no total.`,
        )
      const data = await new Promise((resolve, reject) => {
        const reader = new FileReader()
        reader.onload = () => resolve(reader.result.split(',')[1])
        reader.onerror = () => reject(new Error('Não consegui ler o arquivo.'))
        reader.readAsDataURL(file)
      })
      if (!destroyed)
        attachments.value.push({ name: file.name, mimeType: mime, kind, data, size: file.size })
    }
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    reading.value = false
    if (filesInput.value) filesInput.value.value = ''
  }
}
async function record() {
  if (recording.value) {
    recorder?.stop()
    return
  }
  if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === 'undefined') {
    toast('Este navegador não permite gravar áudio. Anexe um arquivo de áudio.', 'error')
    return
  }
  try {
    stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    if (destroyed) {
      stream.getTracks().forEach((t) => t.stop())
      return
    }
    const mime = ['audio/webm;codecs=opus', 'audio/ogg;codecs=opus', 'audio/mp4'].find((t) =>
      MediaRecorder.isTypeSupported(t),
    )
    if (!mime) throw new Error('Use o envio de arquivo de áudio neste navegador.')
    const chunks = []
    recorder = new MediaRecorder(stream, { mimeType: mime })
    recorder.ondataavailable = (e) => {
      if (e.data.size) chunks.push(e.data)
    }
    recorder.onstop = () => {
      clearTimeout(recordTimer)
      stream?.getTracks().forEach((t) => t.stop())
      recording.value = false
      if (!destroyed)
        addFiles([
          new File(
            chunks,
            'mensagem.' +
              (mime.startsWith('audio/mp4')
                ? 'm4a'
                : mime.startsWith('audio/ogg')
                  ? 'ogg'
                  : 'webm'),
            { type: mime },
          ),
        ])
    }
    recorder.start()
    recording.value = true
    recordTimer = setTimeout(() => {
      if (recorder?.state === 'recording') recorder.stop()
    }, 120000)
  } catch (e) {
    stream?.getTracks().forEach((t) => t.stop())
    toast(e.message || 'Não foi possível acessar o microfone.', 'error')
  }
}
onMounted(() => {
  load()
  updateViewport()
  window.addEventListener('resize', updateViewport)
  window.visualViewport?.addEventListener('resize', updateViewport)
})
onBeforeUnmount(() => {
  queueAbort.abort()
  window.removeEventListener('resize', updateViewport)
  window.visualViewport?.removeEventListener('resize', updateViewport)
  destroyed = true
  clearTimeout(recordTimer)
  if (recorder?.state === 'recording') recorder.stop()
  stream?.getTracks().forEach((t) => t.stop())
})
function keydown(event) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    send()
  }
}
</script>
<template>
  <div class="chat-layout" :style="{ '--chat-available-height': availableHeight }">
    <aside class="conversation-sidebar">
      <div class="section-heading">
        <h2>Conversas</h2>
        <button
          class="icon-button"
          aria-label="Atualizar conversas"
          :disabled="busy || loading || historyLoading"
          @click="load"
        >
          <Icon :name="loading ? 'loading' : 'refresh'" :size="17" />
        </button>
        <button
          class="icon-button"
          aria-label="Nova conversa"
          :disabled="busy || loading || !!deleting"
          @click="startNewConversation"
        >
          <Icon name="plus" />
        </button>
      </div>
      <div class="conversation-row" v-for="c in conversations" :key="c.conversationId">
        <button
          :class="['conversation-link', { active: active === c.conversationId }]"
          :disabled="busy || !!deleting || recording || reading"
          @click="select(c)"
        >
          <Icon name="chat" :size="17" /><span>{{ c.title }}</span></button
        ><button
          class="icon-button conversation-delete"
          :aria-label="'Apagar conversa ' + c.title"
          :disabled="busy || !!deleting || recording || reading"
          @click="removeConversation(c)"
        >
          <Icon :name="deleting === c.conversationId ? 'loading' : 'delete'" :size="16" />
        </button>
      </div>
      <p v-if="!conversations.length && !loading" class="muted footnote">
        Comece uma conversa. A Ayla está por aqui.
      </p>
      <div class="conversation-note">
        <Icon name="shield" :size="16" />
        <p>Mensagens carregadas do histórico salvo. Cada conversa mantém seu próprio contexto.</p>
      </div>
    </aside>
    <section class="chat-main">
      <div class="chat-header">
        <button
          class="icon-button mobile-chat-action"
          aria-label="Escolher conversa"
          @click="conversationPicker = true"
        >
          <Icon name="chat" />
        </button>
        <img class="ayla-avatar" src="/ayla-chat.png" alt="" width="256" height="225" />
        <div><strong>Ayla</strong><small>Sua assistente, do seu jeito.</small></div>
        <span class="tag lavender">Vamos conversar</span>
        <button
          class="icon-button mobile-chat-action chat-new-button"
          aria-label="Nova conversa"
          :disabled="busy || loading || !!deleting"
          @click="startNewConversation"
        >
          <Icon name="plus" />
        </button>
        <button
          class="button secondary chat-whatsapp-button"
          aria-label="Começar uma conversa com Ayla no WhatsApp"
          @click="personalChatOpen = true"
        >
          <Icon name="phone" :size="17" /><span>Conversar com Ayla no WhatsApp</span>
        </button>
      </div>
      <div v-if="error" class="banner error" role="alert">
        {{ error }}<button class="text-link" @click="load">Atualizar</button>
      </div>
      <div v-if="historyError" class="banner error" role="alert">
        {{ historyError
        }}<button class="text-link" @click="loadMessages(active)">
          Tentar carregar mensagens novamente
        </button>
      </div>
      <div ref="scroll" class="chat-scroll" aria-live="polite">
        <button
          v-if="hasMore"
          class="button secondary history-more"
          :disabled="busy || historyLoading"
          @click="loadMessages(active, true)"
        >
          <Icon v-if="historyLoading" name="loading" :size="16" />Carregar mensagens anteriores
        </button>
        <div v-if="(loading || historyLoading) && !messages.length" class="loading-line">
          <Icon name="loading" />Carregando mensagens…
        </div>
        <div
          v-if="!messages.length && !loading && !historyLoading && !historyError"
          class="chat-welcome"
        >
          <img
            class="chat-welcome-star"
            src="/ayla-ideias.png"
            alt=""
            width="640"
            height="440"
            decoding="async"
          />
          <h1>O que vamos fazer hoje?</h1>
          <p>Uma ideia, uma tarefa ou só um papo.<br />Pode chegar, tô por aqui.</p>
          <div class="suggestions">
            <button
              v-for="[icon, prompt] in [
                ['wallet', 'Quanto gastei este mês?'],
                ['shopping', 'Quero organizar minha lista de compras.'],
                ['pantry', 'O que está acabando na despensa?'],
                ['sparkles', 'Me ajuda a planejar meu dia?'],
              ]"
              :key="prompt"
              :disabled="busy"
              @click="suggest(prompt)"
            >
              <Icon :name="icon" :size="19" /><span>{{ prompt }}</span
              ><Icon name="arrow" :size="15" />
            </button>
          </div>
        </div>
        <article
          v-for="(message, index) in messages"
          :key="index"
          :class="['chat-message', message.role]"
        >
          <img
            v-if="message.role === 'assistant'"
            class="ayla-avatar small"
            src="/ayla-chat.png"
            alt=""
          />
          <div class="message-content">
            <span class="message-author">{{
              message.role === 'assistant'
                ? 'Ayla'
                : message.role === 'user'
                  ? 'Você'
                  : 'Não consegui concluir'
            }}</span>
            <div
              v-if="message.role === 'assistant'"
              class="markdown"
              v-html="markdown(message.text)"
            />
            <p v-else class="user-text">{{ message.text }}</p>
            <div v-for="(file, i) in message.files" :key="i" class="message-file">
              <Icon name="attach" :size="15" />{{ file.name }}
            </div>
            <audio
              v-if="message.audio"
              controls
              :src="`data:${message.audio.mimeType};base64,${message.audio.data}`"
            />
          </div>
        </article>
        <div v-if="busy" class="chat-message assistant">
          <img class="ayla-avatar small" src="/ayla-chat.png" alt="" />
          <div class="thinking">
            <i /><i /><i /><span>{{ queueStatus || 'Ayla está pensando…' }}</span>
          </div>
        </div>
      </div>
      <div class="composer-wrap">
        <div v-if="attachments.length" class="attachment-list">
          <span v-for="(file, i) in attachments" :key="i" class="attachment-chip"
            ><Icon :name="file.kind === 'audios' ? 'audio' : 'attach'" :size="14" />{{ file.name
            }}<button
              class="icon-button"
              :disabled="busy || reading"
              aria-label="Remover anexo"
              @click="attachments.splice(i, 1)"
            >
              <Icon name="close" :size="13" /></button
          ></span>
        </div>
        <form class="composer" @submit.prevent="send">
          <textarea
            ref="composerInput"
            v-model="text"
            placeholder="Conversa comigo…"
            aria-label="Sua mensagem para a Ayla"
            rows="2"
            maxlength="32000"
            :disabled="busy || loading || historyLoading || !!historyError"
            @keydown="keydown"
          />
          <div class="composer-actions">
            <div class="button-row">
              <input
                ref="filesInput"
                type="file"
                class="sr-only"
                multiple
                accept="image/jpeg,image/png,image/webp,audio/*,.pdf,.doc,.docx,.txt"
                @change="addFiles($event.target.files)"
              /><button
                type="button"
                class="icon-button"
                title="Anexar imagem, áudio ou documento"
                aria-label="Anexar arquivo"
                :disabled="busy || reading || recording"
                @click="filesInput.click()"
              >
                <Icon :name="reading ? 'loading' : 'attach'" :size="19" /></button
              ><button
                type="button"
                :class="['icon-button', { recording }]"
                :aria-label="recording ? 'Concluir gravação' : 'Gravar áudio'"
                :disabled="busy || reading"
                @click="record"
              >
                <Icon :name="recording ? 'stop' : 'mic'" :size="19" /></button
              ><button
                type="button"
                :class="['composer-option', { selected: forceSearch }]"
                :aria-pressed="forceSearch"
                @click="forceSearch = !forceSearch"
              >
                <Icon name="globe" :size="16" /><span>Pesquisar</span></button
              ><button
                type="button"
                :class="['composer-option', { selected: audioReply }]"
                :aria-pressed="audioReply"
                @click="audioReply = !audioReply"
              >
                <Icon name="audio" :size="16" /><span>Responder em áudio</span>
              </button>
            </div>
            <button
              class="send-button"
              aria-label="Enviar mensagem"
              :disabled="
                busy ||
                reading ||
                recording ||
                loading ||
                historyLoading ||
                !!historyError ||
                (!text.trim() && !attachments.length)
              "
            >
              <Icon :name="busy ? 'loading' : 'send'" :size="19" />
            </button>
          </div>
        </form>
        <p class="composer-note">
          {{
            recording
              ? 'Gravando… toque no quadrado para concluir.'
              : reading
                ? 'Preparando arquivos…'
                : 'Enter para enviar · Shift + Enter para uma nova linha'
          }}
        </p>
      </div>
    </section>
  </div>
  <PersonalChatDialog v-if="personalChatOpen" @close="personalChatOpen = false" />
  <Modal v-if="conversationPicker" title="Suas conversas" @close="conversationPicker = false">
    <div class="mobile-conversation-list">
      <button
        class="button primary"
        :disabled="busy || loading || !!deleting"
        @click="startNewConversation"
      >
        <Icon name="plus" :size="18" />Novo assunto
      </button>
      <div class="conversation-row" v-for="c in conversations" :key="c.conversationId">
        <button
          :class="['conversation-link', { active: active === c.conversationId }]"
          :disabled="busy || !!deleting || recording || reading"
          @click="select(c)"
        >
          <Icon name="chat" :size="19" /><span>{{ c.title }}</span
          ><Icon v-if="active === c.conversationId" name="check" :size="17" /></button
        ><button
          class="icon-button conversation-delete"
          :aria-label="'Apagar conversa ' + c.title"
          :disabled="busy || !!deleting || recording || reading"
          @click="removeConversation(c)"
        >
          <Icon :name="deleting === c.conversationId ? 'loading' : 'delete'" :size="17" />
        </button>
      </div>
      <p v-if="!conversations.length" class="muted">
        Seu primeiro papo começa aqui. Escreva uma mensagem ou escolha uma sugestão.
      </p>
    </div>
  </Modal>
  <Modal v-if="newForm" title="Um novo assunto" @close="!newBusy && (newForm = false)"
    ><form @submit.prevent="create">
      <label
        >Nome da conversa<input
          v-model="title"
          maxlength="120"
          placeholder="Ex.: Ideias para o fim de semana"
      /></label>
      <div class="form-actions">
        <button type="button" class="button secondary" :disabled="newBusy" @click="newForm = false">
          Cancelar</button
        ><button class="button primary" :disabled="newBusy">Criar conversa</button>
      </div>
    </form></Modal
  >
</template>
