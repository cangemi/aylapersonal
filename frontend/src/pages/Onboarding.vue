<script setup>
import PasswordInput from '../components/PasswordInput.vue'
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { api, auth, setSession } from '../lib/api'
import Icon from '../components/Icon.vue'
import AylaLogo from '../components/AylaLogo.vue'
import WhatsappConnection from '../components/WhatsappConnection.vue'
const router = useRouter(),
  user = auth.session.user,
  steps = [
    { id: 'name', label: 'Seu nome' },
    { id: 'password', label: 'Nova senha' },
    { id: 'whatsapp', label: 'WhatsApp' },
    { id: 'hermes', label: 'Hermes' },
  ],
  index = ref(!user.displayName ? 0 : user.passwordChangeRequired ? 1 : 2),
  furthest = ref(index.value),
  completed = ref(
    new Set([
      ...(user.displayName ? ['name'] : []),
      ...(!user.passwordChangeRequired ? ['password'] : []),
    ]),
  ),
  skipped = ref(new Set()),
  step = computed(() => steps[index.value]?.id),
  busy = ref(false),
  error = ref(''),
  displayName = ref(user.displayName || ''),
  password = ref({ currentPassword: '', newPassword: '', confirm: '' }),
  wa = ref(null),
  hermes = ref(null),
  login = ref(null)
let loginTimer,
  disposed = false
function stopPolling() {
  clearInterval(loginTimer)
  loginTimer = null
}
async function loadHermes() {
  error.value = ''
  try {
    const current = await api('/me/hermes/status')
    hermes.value = current.provisioned
      ? current
      : await api('/me/hermes/provision', { method: 'POST' })
    if (hermes.value.ready) {
      stopPolling()
      login.value = null
    }
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
    if (value === 'hermes') {
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
function next() {
  index.value++
  furthest.value = Math.max(furthest.value, index.value)
}
function goTo(value) {
  if (!busy.value && value >= 0 && value <= furthest.value) index.value = value
}
function skipWhatsapp() {
  skipped.value.add('whatsapp')
  completed.value.delete('whatsapp')
  next()
}
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
const advance = () => {
  if (last.value) return finish()
  completed.value.add(step.value)
  skipped.value.delete(step.value)
  next()
}
</script>
<template>
  <main class="onboarding">
    <form class="panel onboarding-card" @submit.prevent>
      <div class="login-identity"><AylaLogo /></div>
      <div class="onboarding-progress">
        <div
          class="onboarding-progress-track"
          role="progressbar"
          aria-label="Progresso da configuração"
          :aria-valuemin="0"
          :aria-valuemax="steps.length"
          :aria-valuenow="index + 1"
          :aria-valuetext="`Etapa ${index + 1} de ${steps.length}: ${steps[index].label}`"
        >
          <span
            class="onboarding-progress-fill"
            :style="{ width: `${(index / (steps.length - 1)) * 100}%` }"
          />
        </div>
        <ol
          class="onboarding-steps"
          :style="{ '--step-count': steps.length }"
          aria-label="Etapas da configuração"
        >
          <li
            v-for="(s, i) in steps"
            :key="s.id"
            :class="{ active: i === index, done: completed.has(s.id), skipped: skipped.has(s.id) }"
          >
            <button
              type="button"
              class="onboarding-step-button"
              :disabled="busy || i > furthest"
              :aria-current="i === index ? 'step' : undefined"
              @click="goTo(i)"
            >
              <span class="onboarding-step-number" aria-hidden="true"
                ><Icon v-if="completed.has(s.id) && i !== index" name="done" :size="15" /><template
                  v-else
                  >{{ i + 1 }}</template
                ></span
              >
              <span>{{ s.label }}</span>
              <span v-if="completed.has(s.id)" class="sr-only"> — concluída</span>
              <span v-else-if="skipped.has(s.id)" class="sr-only"> — pulada, você pode voltar</span>
            </button>
          </li>
        </ol>
      </div>
      <div v-if="error" class="banner error" role="alert">{{ error }}</div>

      <Transition name="onboarding-step" mode="out-in">
        <section :key="step" class="onboarding-step-content">
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
            <button
              class="button primary full"
              :disabled="busy || !displayName.trim()"
              @click="saveName"
            >
              Continuar<Icon name="arrow" :size="18" />
            </button>
          </template>

          <template v-else-if="step === 'password'">
            <template v-if="completed.has('password')">
              <h2>Sua senha está pronta</h2>
              <p class="banner success">
                Esta etapa já foi concluída. Você pode continuar a configuração.
              </p>
              <button type="button" class="button primary full" :disabled="busy" @click="advance">
                Continuar<Icon name="arrow" :size="18" />
              </button>
            </template>
            <template v-else>
              <h2>Crie a sua senha</h2>
              <p class="muted">A senha atual foi definida por quem criou o seu acesso.</p>
              <label
                >Senha atual<PasswordInput
                  v-model="password.currentPassword"
                  autocomplete="current-password"
                  required
                  :disabled="busy"
              /></label>
              <label
                >Nova senha (mínimo de 12 caracteres)<PasswordInput
                  v-model="password.newPassword"
                  autocomplete="new-password"
                  minlength="12"
                  maxlength="256"
                  required
                  :disabled="busy"
              /></label>
              <label
                >Repita a nova senha<PasswordInput
                  v-model="password.confirm"
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
          </template>

          <template v-else-if="step === 'whatsapp'">
            <h2>Leve a Ayla para o WhatsApp</h2>
            <WhatsappConnection @status="wa = $event" />
            <button
              type="button"
              class="button primary full"
              :disabled="busy || !(wa?.ready ?? wa?.connected)"
              @click="advance"
            >
              Continuar<Icon name="arrow" :size="18" />
            </button>
            <button
              type="button"
              class="button secondary full"
              :disabled="busy"
              @click="skipWhatsapp"
            >
              Pular por enquanto
            </button>
          </template>

          <template v-else-if="step === 'hermes'">
            <h2>Prepare o Hermes</h2>
            <p class="muted">Criamos o seu assistente pessoal aqui mesmo, sem sair da Ayla.</p>
            <div v-if="hermes" :class="['banner', hermes.ready ? 'success' : 'info']" role="status">
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
        </section>
      </Transition>
      <button
        v-if="index > 0"
        type="button"
        class="text-link full"
        :disabled="busy"
        @click="goTo(index - 1)"
      >
        <Icon name="arrow" :size="16" class="onboarding-back-icon" />Voltar
      </button>
      <button type="button" class="text-link full" :disabled="busy" @click="leave">Sair</button>
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
.login-identity {
  align-items: center;
  text-align: center;
}
.onboarding-card {
  width: min(520px, 100%);
  display: grid;
  gap: 16px;
}
.onboarding-progress {
  --progress-purple: #c4a4ff;
  --progress-track: #302440;
  --progress-label: #d8c0ff;
  position: relative;
  display: grid;
  margin: 4px 0 10px;
}
:global(:root[data-theme='light'] .onboarding-progress) {
  --progress-purple: #a77ae9;
  --progress-track: #e9def8;
  --progress-label: #67409f;
}
.onboarding-progress-track {
  position: absolute;
  top: 13px;
  left: 12.5%;
  right: 12.5%;
  height: 4px;
  overflow: hidden;
  border-radius: 999px;
  background: var(--progress-track);
}
.onboarding-progress-fill {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #a679e8, var(--progress-purple));
  transition: width 450ms cubic-bezier(0.22, 1, 0.36, 1);
}
.onboarding-steps {
  display: grid;
  grid-template-columns: repeat(var(--step-count), minmax(0, 1fr));
  gap: 0;
  list-style: none;
  padding: 0;
  margin: 0;
  font-size: 0.75rem;
}
.onboarding-steps li {
  min-width: 0;
  color: var(--muted);
  text-align: center;
  line-height: 1.4;
  transition: color 250ms ease;
}
.onboarding-step-button {
  position: relative;
  width: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 9px;
  padding: 0 2px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: inherit;
  font: inherit;
  cursor: pointer;
}
.onboarding-step-button:disabled {
  opacity: 1;
  cursor: default;
}
.onboarding-step-button:focus-visible {
  outline: 2px solid var(--progress-purple);
  outline-offset: 5px;
}
.onboarding-step-number {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border-radius: 50%;
  border: 1px solid var(--line);
  background: var(--panel);
  font-weight: 700;
  transition:
    background 250ms ease,
    border-color 250ms ease,
    box-shadow 250ms ease;
}
.onboarding-steps li.active {
  color: var(--progress-label);
  font-weight: 700;
}
.onboarding-steps li.active .onboarding-step-number {
  border-color: var(--progress-purple);
  background: var(--accent-bg);
  box-shadow: 0 0 0 4px color-mix(in srgb, var(--progress-purple) 16%, transparent);
}
.onboarding-steps li.done .onboarding-step-number {
  color: #231335;
  border-color: var(--progress-purple);
  background: var(--progress-purple);
}
.onboarding-steps li.skipped:not(.active) .onboarding-step-number {
  border-style: dashed;
  border-color: var(--progress-purple);
}
.onboarding-back-icon {
  transform: rotate(180deg);
  margin-right: 6px;
}
.onboarding-step-content {
  display: grid;
  gap: 16px;
  min-width: 0;
}
.onboarding-step-enter-active,
.onboarding-step-leave-active {
  transition:
    opacity 160ms ease,
    transform 160ms ease;
}
.onboarding-step-enter-from {
  opacity: 0;
  transform: translateX(8px);
}
.onboarding-step-leave-to {
  opacity: 0;
  transform: translateX(-8px);
}
@media (prefers-reduced-motion: reduce) {
  .onboarding-progress-fill,
  .onboarding-steps li,
  .onboarding-step-number,
  .onboarding-step-enter-active,
  .onboarding-step-leave-active {
    transition: none;
  }
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
