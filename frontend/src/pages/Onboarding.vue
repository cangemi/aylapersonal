<script setup>
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { api, auth, setSession } from '../lib/api'
import Icon from '../components/Icon.vue'
import AylaLogo from '../components/AylaLogo.vue'
const router = useRouter(),
  user = auth.session.user,
  steps = [
    ...(user.displayName ? [] : [{ id: 'name', label: 'Seu nome' }]),
    ...(user.passwordChangeRequired ? [{ id: 'password', label: 'Nova senha' }] : []),
    { id: 'whatsapp', label: 'WhatsApp' },
    { id: 'hermes', label: 'Hermes' },
  ],
  index = ref(0),
  step = computed(() => steps[index.value]?.id),
  busy = ref(false),
  error = ref(''),
  displayName = ref(user.displayName || ''),
  password = ref({ currentPassword: '', newPassword: '', confirm: '' }),
  wa = ref(null),
  qr = ref(null),
  hermes = ref(null),
  login = ref(null)
let timer,
  loginTimer,
  disposed = false,
  resumed = false,
  hermesChecked = false
function stopPolling() {
  clearInterval(timer)
  clearInterval(loginTimer)
  timer = loginTimer = null
}
async function pollWhatsapp() {
  try {
    wa.value = await api('/me/whatsapp/status')
    qr.value = wa.value.connected ? null : await api('/me/whatsapp/qr')
    // Resuming: a WhatsApp already connected means this step was done before.
    if (!resumed) {
      resumed = true
      if (wa.value.connected && step.value === 'whatsapp') next()
    }
  } catch (e) {
    if (!disposed) error.value = e.message
  }
}
async function loadHermes() {
  error.value = ''
  try {
    const current = await api('/me/hermes/status')
    hermes.value = current.provisioned ? current : await api('/me/hermes/provision', { method: 'POST' })
    if (hermes.value.ready) {
      stopPolling()
      login.value = null
      // Resuming: an already prepared Hermes means this step was done before.
      if (!hermesChecked && current.provisioned && step.value === 'hermes') await advance()
    }
    hermesChecked = true
  } catch (e) {
    if (!disposed) error.value = e.message
  }
}
async function pollLogin() {
  if (!login.value) return
  try {
    await api('/me/hermes/login/' + login.value.session_id)
  } catch {
    /* o status abaixo é a fonte da verdade */
  }
  await loadHermes()
}
const startLogin = () =>
  run(async () => {
    login.value = await api('/me/hermes/login', { method: 'POST' })
    clearInterval(loginTimer)
    loginTimer = setInterval(pollLogin, Math.max(3, login.value.poll_interval || 5) * 1000)
  })
watch(
  step,
  (value) => {
    stopPolling()
    error.value = ''
    if (value === 'whatsapp') {
      pollWhatsapp()
      timer = setInterval(pollWhatsapp, 4000)
    } else if (value === 'hermes') {
      busy.value = true
      loadHermes().finally(() => (busy.value = false))
    }
  },
  { immediate: true },
)
onBeforeUnmount(() => {
  disposed = true
  stopPolling()
})
async function run(action) {
  busy.value = true
  error.value = ''
  try {
    await action()
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}
const next = () => index.value++
const saveName = () =>
  run(async () => {
    const saved = await api('/auth/profile', {
      method: 'PUT',
      body: { displayName: displayName.value },
    })
    setSession({ ...auth.session, user: { ...auth.session.user, ...saved } })
    advance()
  })
const savePassword = () =>
  run(async () => {
    const p = password.value
    if (p.newPassword !== p.confirm) throw new Error('As novas senhas não coincidem.')
    await api('/auth/change-password', {
      method: 'POST',
      body: { currentPassword: p.currentPassword, newPassword: p.newPassword },
    })
    // Changing the password revokes every session, so sign in again with the new one.
    const session = await api('/auth/login', {
      public: true,
      method: 'POST',
      body: { username: user.username, password: p.newPassword },
    })
    setSession(session)
    password.value = { currentPassword: '', newPassword: '', confirm: '' }
    advance()
  })
const connectWhatsapp = () =>
  run(async () => {
    await api('/me/whatsapp/connect', { method: 'POST' })
    await pollWhatsapp()
  })
async function finish() {
  busy.value = true
  error.value = ''
  try {
    const saved = await api('/auth/onboarding/complete', { method: 'POST' })
    setSession({ ...auth.session, user: saved })
    await router.replace('/')
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}
async function leave() {
  try {
    await api('/auth/logout', { method: 'POST' })
  } catch {
    /* sessão já encerrada */
  }
  setSession(null)
  router.replace('/login')
}
const last = computed(() => index.value === steps.length - 1)
const advance = () => (last.value ? finish() : next())
if (!steps.length) finish()
</script>
<template>
  <main class="onboarding">
    <form class="panel onboarding-card" @submit.prevent>
      <div class="login-identity"><AylaLogo /></div>
      <span class="eyebrow">CONFIGURAÇÃO INICIAL · {{ index + 1 }} DE {{ steps.length }}</span>
      <ol class="onboarding-steps">
        <li
          v-for="(s, i) in steps"
          :key="s.id"
          :class="{ active: i === index, done: i < index }"
        >
          {{ s.label }}
        </li>
      </ol>
      <div v-if="error" class="banner error" role="alert">{{ error }}</div>

      <template v-if="step === 'name'">
        <h2>Como a Ayla deve te chamar?</h2>
        <label
          >Seu nome<input
            v-model="displayName"
            required
            maxlength="80"
            placeholder="Ex.: Godofredo"
            :disabled="busy"
        /></label>
        <button class="button primary full" :disabled="busy || !displayName.trim()" @click="saveName">
          Continuar<Icon name="arrow" :size="18" />
        </button>
      </template>

      <template v-else-if="step === 'password'">
        <h2>Crie a sua senha</h2>
        <p class="muted">A senha atual foi definida por quem criou o seu acesso.</p>
        <label
          >Senha atual<input
            v-model="password.currentPassword"
            type="password"
            autocomplete="current-password"
            required
            :disabled="busy"
        /></label>
        <label
          >Nova senha (mínimo de 12 caracteres)<input
            v-model="password.newPassword"
            type="password"
            autocomplete="new-password"
            minlength="12"
            maxlength="256"
            required
            :disabled="busy"
        /></label>
        <label
          >Repita a nova senha<input
            v-model="password.confirm"
            type="password"
            autocomplete="new-password"
            required
            :disabled="busy"
        /></label>
        <button
          class="button primary full"
          :disabled="busy || !password.currentPassword || !password.newPassword"
          @click="savePassword"
        >
          Trocar senha e continuar<Icon name="arrow" :size="18" />
        </button>
      </template>

      <template v-else-if="step === 'whatsapp'">
        <h2>Conecte o seu WhatsApp</h2>
        <p class="muted">
          Abra o WhatsApp no celular, vá em Aparelhos conectados e leia o QR code abaixo.
        </p>
        <div v-if="wa?.connected" class="banner success" role="status">
          WhatsApp conectado{{ wa.number ? ' · ' + wa.number : '' }}.
        </div>
        <div v-else class="qr-area">
          <img v-if="qr?.qrImageDataUrl" :src="qr.qrImageDataUrl" alt="QR code do WhatsApp" />
          <div v-else class="qr-placeholder" role="status" aria-live="polite">
            {{
              busy || !wa || wa.state === 'connecting' || wa.state === 'starting'
                ? 'Carregando QR code…'
                : 'Ainda sem QR code.'
            }}
          </div>
          <button class="button" type="button" :disabled="busy" @click="connectWhatsapp">
            Gerar QR code
          </button>
        </div>
        <button class="button primary full" :disabled="busy || !wa?.connected" @click="advance">
          Continuar<Icon name="arrow" :size="18" />
        </button>
      </template>

      <template v-else-if="step === 'hermes'">
        <h2>Prepare o Hermes</h2>
        <p class="muted">Criamos o seu assistente pessoal aqui mesmo, sem sair da Ayla.</p>
        <div
          v-if="hermes"
          :class="['banner', hermes.ready ? 'success' : 'info']"
          role="status"
        >
          {{ hermes.message }}
        </div>
        <div v-if="login" class="login-code">
          <p>Abra o link, entre na Nous e confirme o código:</p>
          <strong class="user-code">{{ login.user_code }}</strong>
          <a class="button" :href="login.verification_url" target="_blank" rel="noopener">
            Abrir a Nous
          </a>
          <p class="muted">Aguardando a confirmação…</p>
        </div>
        <div class="onboarding-actions">
          <button
            v-if="hermes?.connected && !hermes.loggedIn"
            class="button"
            type="button"
            :disabled="busy"
            @click="startLogin"
          >
            {{ login ? 'Gerar novo código' : 'Entrar na conta Nous' }}
          </button>
          <button v-else class="button" type="button" :disabled="busy" @click="loadHermes">
            Verificar novamente
          </button>
          <button class="button primary" :disabled="busy || !hermes?.ready" @click="advance">
            Concluir<Icon name="arrow" :size="18" />
          </button>
        </div>
      </template>

      <button type="button" class="text-link full" :disabled="busy" @click="leave">
        Sair
      </button>
    </form>
  </main>
</template>
<style scoped>
.onboarding {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 24px;
}
.onboarding-card {
  width: min(520px, 100%);
  display: grid;
  gap: 16px;
}
.onboarding-steps {
  display: flex;
  gap: 8px;
  list-style: none;
  padding: 0;
  margin: 0;
  flex-wrap: wrap;
  font-size: 0.85rem;
  opacity: 0.6;
}
.onboarding-steps li.active {
  opacity: 1;
  font-weight: 700;
}
.onboarding-steps li.done {
  opacity: 0.9;
}
.onboarding-steps li.done::before {
  content: '✓ ';
}
.onboarding-actions {
  display: flex;
  gap: 8px;
  justify-content: space-between;
}
.onboarding .qr-area {
  width: 100%;
  height: auto;
  flex-direction: column;
  gap: 14px;
}
.onboarding .qr-area > img {
  width: 240px;
  height: 240px;
}
.onboarding .qr-placeholder {
  width: 240px;
  height: 240px;
  font-size: 0.9rem;
}
.login-code {
  display: grid;
  gap: 8px;
  justify-items: start;
}
.user-code {
  font-size: 1.8rem;
  letter-spacing: 0.15em;
}
</style>
