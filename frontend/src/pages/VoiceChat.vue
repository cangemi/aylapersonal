<script setup>
import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { useRoute } from 'vue-router'
import DOMPurify from 'dompurify'
import { marked } from 'marked'
import Icon from '../components/Icon.vue'
import { api } from '../lib/api'
import { acknowledgeChatJob, previewChatTurn, submitChatJob, waitForChatJob } from '../lib/chatQueue'
import { openVoiceCapture } from '../lib/voiceCapture'

const route = useRoute()
const initialId = typeof route.query.conversationId === 'string' ? route.query.conversationId : null
const conversationId = ref(initialId)
const active = ref(false)
const starting = ref(false)
const muted = ref(false)
const capturing = ref(false)
const speaking = ref(false)
const level = ref(0)
const error = ref('')
const notice = ref('')
const showText = ref(false)
const turns = ref([])
const lastSpoken = shallowRef(null)
const pendingReplies = ref([])
const current = shallowRef(null)
const unheard = ref(false)
const voiceInterruptions = ref(false)
let room, microphone, stopPlayback, audioPlayer, playbackKind, playingReply
let admissions = Promise.resolve()
let speechRevision = 0

const status = computed(() => {
  if (starting.value) return 'Aguardando o microfone…'
  if (speaking.value) return 'Ayla está falando'
  if (!active.value) return 'Conversa por voz'
  if (capturing.value) return 'Ouvindo você…'
  if (current.value) return 'Ayla está pensando…'
  if (muted.value) return 'Microfone silenciado'
  return 'Pode falar'
})
const chatLink = computed(() => ({
  path: '/chat',
  query: conversationId.value ? { conversationId: conversationId.value } : {},
}))

function setLevel(value) { level.value = Math.round(value * 20) / 20 }

async function start() {
  if (room) return
  error.value = notice.value = ''
  starting.value = true
  const connection = new AbortController()
  room = connection
  try {
    const opened = await openVoiceCapture({
      signal: connection.signal,
      isPlaying: () => speaking.value,
      canInterruptPlayback: () => voiceInterruptions.value && playbackKind !== 'hold',
      onLevel: setLevel,
      onSpeech() {
        interrupt()
        capturing.value = true
      },
      onUtterance(blob, problem) {
        capturing.value = false
        if (problem) void announceFailure(connection, problem)
        if (blob) void sendTurn(connection, blob)
        else if (current.value?.result) void deliverTurn(current.value)
      },
      onError(message) {
        end()
        error.value = message
      },
    })
    if (room !== connection) return
    microphone = opened
    active.value = true
  } catch (failure) {
    if (room !== connection) return
    end()
    error.value = failure.name === 'NotAllowedError'
      ? 'Permita o uso do microfone nas configurações do navegador para conversar.'
      : failure.name === 'NotFoundError'
        ? 'Nenhum microfone foi encontrado neste dispositivo.'
        : failure.message || 'Não consegui ativar o microfone.'
  } finally {
    if (room === connection) starting.value = false
  }
}

function pausePlayback() {
  stopPlayback?.()
  stopPlayback = null
  if (audioPlayer) {
    audioPlayer.pause()
    audioPlayer.removeAttribute('src')
    audioPlayer.load()
    audioPlayer = null
  }
  speaking.value = false
}

function interrupt() {
  speechRevision++
  if (speaking.value && playbackKind === 'reply' && playingReply) {
    rememberReply(playingReply)
    unheard.value = true
    notice.value = 'Resposta interrompida. Pode continuar falando ou ouvi-la novamente.'
  }
  pausePlayback()
}

function end() {
  const connection = room
  room = null
  connection?.abort()
  microphone = null
  interrupt()
  if (current.value) {
    unheard.value = true
  }
  current.value = null
  active.value = starting.value = capturing.value = muted.value = false
  setLevel(0)
}

function toggleMute() {
  muted.value = !muted.value
  capturing.value = false
  microphone?.setMuted(muted.value)
  if (current.value?.result) void deliverTurn(current.value)
}

function rememberReply(reply) {
  if (reply && !pendingReplies.value.some((item) => item.key === reply.key)) {
    pendingReplies.value = [...pendingReplies.value, reply]
    unheard.value = true
  }
}

function finishReply(reply) {
  pendingReplies.value = pendingReplies.value.filter((item) => item.key !== reply.key)
  unheard.value = pendingReplies.value.length > 0
  void acknowledgeChatJob(reply.jobId)
}

function uploadData(blob) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(reader.result.split(',')[1])
    reader.onerror = () => reject(new Error('Não consegui ler o áudio do microfone.'))
    reader.readAsDataURL(blob)
  })
}

async function sendTurn(connection, blob) {
  if (room !== connection) return
  error.value = notice.value = ''
  const interruptedVoice = !!current.value || unheard.value
  if (current.value) rememberReply(current.value.reply)
  unheard.value = false
  const turn = { room: connection, id: null, result: null, reply: null, holdDone: null }
  current.value = turn
  // Admission remains ordered; processing and listening for the next utterance can overlap.
  const accepted = admissions.then(async () => {
    if (connection.signal.aborted) return null
    const data = await uploadData(blob)
    if (connection.signal.aborted) return null
    const base = { conversationId: conversationId.value, liveVoice: true, interruptedVoice }
    let preview = null
    try {
      preview = await previewChatTurn({ ...base, message: 'Mensagem de voz', audios: [{ mimeType: 'audio/wav', data }] }, { signal: connection.signal })
    } catch (failure) {
      // Áudio em branco: o mesmo aviso seria repetido pela fila. Timeout/queda do preview segue pela fila com o áudio.
      if (failure.status === 422) throw failure
    }
    if (connection.signal.aborted) return null
    if (preview?.replied) {
      if (preview.conversationId) conversationId.value = preview.conversationId
      return { kind: 'local', preview }
    }
    if (preview?.hold && preview.transcript) {
      // Sem STT duplo: a fila recebe a transcrição já feita no preview.
      if (preview.conversationId) conversationId.value = preview.conversationId
      turn.holdDone = speakHold(connection, turn, preview.hold)
      const job = await submitChatJob({ ...base, message: preview.transcript })
      if (job.conversationId) conversationId.value = job.conversationId
      turn.id = job.id
      return { kind: 'job', job }
    }
    const job = await submitChatJob({ ...base, message: 'Mensagem de voz', audios: [{ mimeType: 'audio/wav', data }] })
    if (job.conversationId) conversationId.value = job.conversationId
    turn.id = job.id
    return { kind: 'job', job }
  })
  admissions = accepted.then(() => {}, () => {})

  try {
    const outcome = await accepted
    if (!outcome || connection.signal.aborted) return
    if (outcome.kind === 'local') {
      const preview = outcome.preview
      const answer = [preview.response, preview.textSupplement].filter(Boolean).join('\n\n')
      turns.value = [...turns.value.slice(-19), { heard: preview.transcript, answer }]
      turn.result = { response: answer, jobId: null, voiceTurn: preview.voiceTurn }
      if (preview.voiceTurn?.speech && preview.audio)
        turn.reply = { key: crypto.randomUUID(), audio: preview.audio, jobId: null }
      if (current.value !== turn) {
        rememberReply(turn.reply)
        if (!turn.reply) notice.value = 'A resposta anterior ficou disponível por escrito no chat.'
      } else if (!capturing.value) void deliverTurn(turn)
      return
    }
    const job = outcome.job
    const result = job.id ? await waitForChatJob(job, { signal: connection.signal, pollInterval: 300 }) : job
    if (room !== connection) return
    if (result.jobStatus && result.jobStatus !== 'COMPLETED') {
      if (current.value === turn) {
        current.value = null
        pausePlayback()
        void announceFailure(connection, result.response)
      } else {
        notice.value = 'Um pedido anterior não foi concluído. Confira a conversa no chat.'
      }
      return
    }
    const response = result.voiceTurn
    turns.value = [...turns.value.slice(-19), { heard: response?.transcript, answer: result.response }]
    turn.result = result
    if (response?.speech && result.audio)
      turn.reply = { key: result.jobId || crypto.randomUUID(), audio: result.audio, jobId: result.jobId }
    if (current.value !== turn) {
      rememberReply(turn.reply)
      if (!turn.reply) notice.value = 'A resposta anterior ficou disponível por escrito no chat.'
    } else if (!capturing.value) void deliverTurn(turn)
  } catch (failure) {
    if (room !== connection) return
    if (current.value === turn) {
      current.value = null
      pausePlayback()
      void announceFailure(connection, failure.message || 'Não consegui concluir esse turno.')
    } else notice.value = 'Não consegui acompanhar um pedido anterior. Confira a conversa no chat.'
  }
}

/** Fala de espera contextual enquanto o Hermes processa; silêncio quando não há ferramenta. */
function speakHold(connection, turn, text) {
  const revision = ++speechRevision
  return api('/me/agent/voice/speech', {
    method: 'POST', body: { text: String(text || '').slice(0, 300) }, signal: connection.signal,
  }).then((audio) => {
    if (room !== connection || revision !== speechRevision || current.value !== turn || capturing.value) return
    return new Promise((resolve) => play(audio, { kind: 'hold', onEnd: resolve, onError: resolve, onStop: resolve }))
  }).catch(() => {})
}

async function deliverTurn(turn) {
  if (room !== turn.room || current.value !== turn || !turn.result || capturing.value) return
  // Deixa a frase de espera terminar antes de começar a resposta.
  if (turn.holdDone) await turn.holdDone
  if (room !== turn.room || current.value !== turn || capturing.value) return
  current.value = null
  if (turn.reply) {
    lastSpoken.value = turn.reply
    play(turn.reply.audio, {
      reply: turn.reply,
      onEnd: () => finishReply(turn.reply),
      onError: () => rememberReply(turn.reply),
    })
  } else {
    pausePlayback()
    void announceFailure(turn.room, 'Não consegui gerar a resposta com a voz configurada. Você pode ler a conversa abaixo.')
    showText.value = true
  }
}

async function announceFailure(connection, message) {
  error.value = message
  const revision = ++speechRevision
  try {
    const audio = await api('/me/agent/voice/speech', {
      method: 'POST', body: { text: message.slice(0, 500) }, signal: connection.signal,
    })
    if (room === connection && revision === speechRevision && !current.value && !capturing.value)
      play(audio, { kind: 'notice' })
  } catch (failure) {
    if (room === connection && revision === speechRevision && failure.name !== 'AbortError')
      error.value = `${message} Também não consegui falar o aviso com a voz configurada.`
  }
}

function play(audio, { kind = 'reply', reply = null, onEnd = () => {}, onError = () => {}, onStop = () => {} } = {}) {
  pausePlayback()
  if (!audio?.mimeType || !audio?.data) {
    error.value = 'Não há áudio disponível para reproduzir.'
    showText.value = true
    onError()
    return
  }
  let url
  try {
    const bytes = Uint8Array.from(atob(audio.data), (character) => character.charCodeAt(0))
    url = URL.createObjectURL(new Blob([bytes], { type: audio.mimeType }))
    audioPlayer = new Audio(url)
  } catch {
    error.value = 'O áudio da resposta veio inválido. Você pode ler a conversa abaixo.'
    showText.value = true
    if (url) URL.revokeObjectURL(url)
    onError()
    return
  }
  const player = audioPlayer
  let finished = false
  const release = () => {
    if (finished) return
    finished = true
    URL.revokeObjectURL(url)
  }
  const done = () => {
    release()
    if (audioPlayer !== player) return
    audioPlayer = null
    stopPlayback = null
    playbackKind = null
    playingReply = null
    speaking.value = false
  }
  const unavailable = () => {
    if (audioPlayer !== player) return
    done()
    error.value = 'O dispositivo não conseguiu tocar a resposta. Você pode lê-la abaixo ou tentar ouvir novamente.'
    showText.value = true
    onError()
  }
  speaking.value = true
  playbackKind = kind
  playingReply = reply
  player.addEventListener('ended', () => {
    if (audioPlayer !== player) return
    done()
    onEnd()
  }, { once: true })
  player.addEventListener('error', unavailable, { once: true })
  stopPlayback = () => {
    onStop()
    release()
    if (audioPlayer !== player) return
    player.pause()
    player.removeAttribute('src')
    player.load()
    audioPlayer = null
    stopPlayback = null
    playbackKind = null
    playingReply = null
    speaking.value = false
    onStop()
  }
  void player.play().catch(unavailable)
}

function replay() {
  error.value = ''
  const saved = lastSpoken.value
  if (saved) play(saved.audio, {
    reply: saved,
    onEnd: () => finishReply(saved),
    onError: () => rememberReply(saved),
  })
}

function resumePending() {
  const reply = pendingReplies.value[0]
  if (!reply) return
  error.value = ''
  lastSpoken.value = reply
  play(reply.audio, {
    reply,
    onEnd: () => finishReply(reply),
    onError: () => rememberReply(reply),
  })
}

function onVisibilityChange() {
  if (document.hidden && (room || speaking.value)) end()
}

function safeMarkdown(text) { return DOMPurify.sanitize(marked.parse(text || '', { async: false })) }

onMounted(() => document.addEventListener('visibilitychange', onVisibilityChange))
onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', onVisibilityChange)
  end()
})
</script>

<template>
  <section class="voice-room" aria-label="Conversa por voz com Ayla">
    <RouterLink :to="chatLink" class="voice-back"><Icon name="left" :size="17" /> Chat</RouterLink>
    <div class="voice-stage" :class="{ 'is-speaking': speaking, 'is-listening': capturing }" :style="{ '--mic-level': level }">
      <div class="voice-portrait"><img src="/ayla-chat.png" width="256" height="225" alt="" /></div>
      <h1 aria-live="polite">{{ status }}</h1>
      <p v-if="speaking && active">
        {{ voiceInterruptions && playbackKind !== 'hold' ? 'Pode me interromper falando ou pelo botão.' : 'Para interromper, use o botão abaixo.' }}
      </p>
      <p v-else-if="!active && !starting && !speaking">Fale naturalmente. Você pode interromper a resposta pelo botão.</p>
      <p v-else-if="muted">Ative o microfone para voltar a falar.</p>
    </div>

    <div class="voice-actions">
      <button v-if="!active && !starting && !speaking" type="button" class="button primary" @click="start">
        <Icon name="mic" /> Iniciar conversa
      </button>
      <template v-else>
        <button v-if="active" type="button" class="button secondary" :aria-pressed="muted" @click="toggleMute">
          <Icon :name="muted ? 'mic-off' : 'mic'" /> {{ muted ? 'Ativar microfone' : 'Silenciar' }}
        </button>
        <button v-if="capturing" type="button" class="button secondary" @click="microphone?.finish()">
          <Icon name="send" /> Enviar fala
        </button>
        <button v-else-if="speaking" type="button" class="button secondary" @click="interrupt">
          <Icon name="stop" /> Interromper
        </button>
        <button v-if="active || starting" type="button" class="button danger" @click="end"><Icon name="close" /> Encerrar</button>
      </template>
    </div>
    <label v-if="active" class="voice-interruption-option">
      <input v-model="voiceInterruptions" type="checkbox" />
      Interromper falando (recomendado usar fones)
    </label>
    <p class="voice-feedback" role="status" aria-atomic="true">{{ error || notice }}</p>

    <div v-if="turns.length" class="voice-transcript">
      <div class="voice-transcript-controls">
        <button type="button" class="button secondary" :aria-expanded="showText" aria-controls="voice-transcript" @click="showText = !showText">
          {{ showText ? 'Ocultar conversa' : 'Ler conversa' }}
        </button>
        <button v-if="pendingReplies.length" type="button" class="button secondary" :disabled="capturing || speaking || !!current" @click="resumePending">
          <Icon name="volume" :size="17" /> Retomar resposta ({{ pendingReplies.length }})
        </button>
        <button v-if="lastSpoken" type="button" class="button secondary" :disabled="capturing || speaking || !!current" @click="replay">
          <Icon name="volume" :size="17" /> Ouvir de novo
        </button>
      </div>
      <div id="voice-transcript" v-show="showText">
        <article v-for="(turn, index) in turns" :key="index" class="voice-text-turn">
          <p v-if="turn.heard"><strong>Você</strong> {{ turn.heard }}</p>
          <strong>Ayla</strong>
          <div class="message-content" v-html="safeMarkdown(turn.answer)" />
        </article>
      </div>
    </div>
  </section>
</template>

<style scoped>
.voice-room { width: min(100%, 48rem); margin: auto; text-align: center; }
.voice-back { display: inline-flex; align-items: center; gap: .25rem; color: var(--muted); float: left; }
.voice-stage { clear: both; padding: clamp(1.8rem, 7vw, 4rem) 0 1.1rem; }
.voice-portrait { display: grid; place-items: center; width: clamp(9rem, 42vw, 13.5rem); aspect-ratio: 1; margin: 0 auto 1.5rem; border: 1px solid var(--accent); border-radius: 50%; background: radial-gradient(circle at 50% 55%, var(--accent-bg), var(--panel) 72%); box-shadow: 0 0 0 .4rem var(--accent-bg); }
.voice-portrait img { display: block; width: 75%; height: auto; }
.is-listening .voice-portrait { transform: scale(calc(1 + var(--mic-level) * .1)); }
.is-speaking .voice-portrait { animation: ayla-voice-pulse 1.5s ease-in-out infinite alternate; }
@keyframes ayla-voice-pulse { to { box-shadow: 0 0 0 .7rem var(--accent-bg), 0 0 2rem var(--accent-bg); } }
.voice-stage h1 { margin: 0 0 .55rem; font-family: 'Manrope Variable', sans-serif; font-size: clamp(1.4rem, 6vw, 2.15rem); }
.voice-stage p, .voice-feedback { color: var(--muted); }
.voice-actions, .voice-transcript-controls { display: flex; justify-content: center; flex-wrap: wrap; gap: .5rem; }
.voice-actions .button, .voice-transcript-controls .button { min-height: 44px; }
.voice-interruption-option { display: inline-flex; align-items: center; gap: .4rem; margin-top: .8rem; color: var(--muted); }
.voice-feedback { min-height: 1.3em; margin: .85rem 0; }
.voice-transcript { margin: 1.2rem auto; }
.voice-text-turn { margin-top: .65rem; padding: .85rem 1rem; text-align: left; overflow-wrap: anywhere; border: 1px solid var(--line); border-radius: .85rem; background: var(--panel); }
.voice-text-turn > p { margin: 0 0 .65rem; color: var(--muted); }
.voice-text-turn strong { display: block; margin-bottom: .15rem; }
.voice-text-turn :deep(a) { color: var(--accent-strong); text-decoration: underline; }
@media (prefers-reduced-motion: reduce) {
  .is-speaking .voice-portrait { animation: none; }
  .is-listening .voice-portrait { transform: none; }
}
</style>
