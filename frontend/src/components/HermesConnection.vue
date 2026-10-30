<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { api } from '../lib/api'
import Icon from './Icon.vue'
import ExternalLink from './ExternalLink.vue'

const props = defineProps({ onboarding: Boolean, management: Boolean })
const emit = defineEmits(['status', 'busy'])
const status = ref(null),
  loading = ref(true),
  busy = ref(false),
  error = ref(''),
  login = ref(null),
  loginState = ref(''),
  remaining = ref(0)
let disposed = false,
  pollTimer,
  clockTimer,
  expiresAt = 0,
  polling = false
const pending = computed(() => login.value && loginState.value === 'pending')
const countdown = computed(
  () => `${Math.floor(remaining.value / 60)}:${String(remaining.value % 60).padStart(2, '0')}`,
)
const connectionLabel = computed(() =>
  pending.value
    ? 'Autorização em andamento'
    : status.value?.ready
      ? 'Pronto para conversar'
      : status.value?.loggedIn
        ? 'Conta conectada'
        : 'Aguardando conexão',
)
function stopPolling() {
  clearTimeout(pollTimer)
  clearInterval(clockTimer)
}
function applyStatus(value) {
  if (disposed) return
  status.value = value
  emit('status', value)
  if ((value.ready || value.loggedIn) && !pending.value) {
    stopPolling()
    login.value = null
    loginState.value = 'approved'
  }
}
async function readStatus(prepare = false) {
  const current = await api('/me/hermes/status')
  if (disposed) return
  applyStatus(
    prepare && !current.provisioned
      ? await api('/me/hermes/provision', { method: 'POST' })
      : current,
  )
}
async function run(action) {
  if (busy.value || disposed) return
  busy.value = true
  emit('busy', true)
  error.value = ''
  try {
    await action()
  } catch (e) {
    if (!disposed) error.value = e.message
  } finally {
    busy.value = false
    loading.value = false
    if (!disposed) emit('busy', false)
  }
}
const refresh = () => run(() => readStatus(props.onboarding))
function expire() {
  stopPolling()
  loginState.value = 'expired'
  error.value =
    'O link de autorização expirou. Clique em “Reiniciar autorização” para abrir uma nova solicitação.'
}
function schedulePoll() {
  if (!pending.value || disposed) return
  clearTimeout(pollTimer)
  pollTimer = setTimeout(pollLogin, Math.max(3, Number(login.value.poll_interval) || 5) * 1000)
}
async function pollLogin() {
  if (!pending.value || disposed || polling) return
  polling = true
  const sessionId = login.value.session_id
  try {
    const result = await api('/me/hermes/login/' + encodeURIComponent(sessionId))
    if (disposed || sessionId !== login.value?.session_id) return
    if (['denied', 'expired', 'error'].includes(result.status)) {
      stopPolling()
      loginState.value = result.status
      error.value =
        result.status === 'denied'
          ? 'A autorização foi recusada na Nous. Reinicie a autorização quando quiser conectar sua conta.'
          : result.status === 'expired'
            ? 'O link de autorização expirou. Reinicie a autorização para tentar novamente.'
            : 'A Nous não concluiu a autorização. Reinicie a autorização para tentar novamente.'
      return
    }
    if (result.status === 'approved') {
      stopPolling()
      loginState.value = 'approved'
      login.value = null
    }
    await readStatus()
  } catch (e) {
    if (
      !disposed &&
      (login.value?.session_id === sessionId || (!login.value && loginState.value === 'approved'))
    ) {
      stopPolling()
      loginState.value = 'error'
      error.value = e.message
    }
  } finally {
    polling = false
    schedulePoll()
  }
}
const startLogin = () =>
  run(async () => {
    stopPolling()
    login.value = null
    loginState.value = ''
    await readStatus(true)
    if (disposed || !status.value?.connected) return
    const result = await api('/me/hermes/login', { method: 'POST' })
    if (disposed) return
    if (!result.session_id || !result.verification_url || !result.user_code)
      throw new Error('O Hermes não retornou o link de autorização. Tente conectar novamente.')
    login.value = result
    loginState.value = 'pending'
    const lifetime = Number(result.expires_in)
    expiresAt = Number.isFinite(lifetime) && lifetime > 0 ? Date.now() + lifetime * 1000 : 0
    if (expiresAt) {
      const tick = () => {
        remaining.value = Math.max(0, Math.ceil((expiresAt - Date.now()) / 1000))
        if (!remaining.value) expire()
      }
      tick()
      clockTimer = setInterval(tick, 1000)
    }
    schedulePoll()
  })
function visibilityChanged() {
  if (document.visibilityState === 'visible' && pending.value && !polling) {
    clearTimeout(pollTimer)
    pollLogin()
  }
}
onMounted(() => {
  refresh()
  document.addEventListener('visibilitychange', visibilityChanged)
})
onBeforeUnmount(() => {
  disposed = true
  stopPolling()
  document.removeEventListener('visibilitychange', visibilityChanged)
})
</script>
<template>
  <section :class="['hermes-connection', { panel: management }]" :aria-busy="busy || loading">
    <div class="hermes-heading">
      <div>
        <h2>
          {{
            pending
              ? 'Autorize sua conta Nous'
              : status?.ready
                ? 'Seu Hermes está conectado'
                : 'Conecte sua conta ao Hermes'
          }}
        </h2>
        <p class="muted">
          O Hermes prepara as respostas da Ayla. Para ativá-lo, você autoriza o acesso à sua própria
          conta Nous. Essa autorização acontece no site da Nous, em uma nova aba.
        </p>
      </div>
      <span v-if="status" :class="['tag', status.ready && !pending ? 'green' : '']">{{
        connectionLabel
      }}</span>
    </div>
    <p v-if="loading" class="banner info" role="status">
      <Icon name="loading" :size="18" />Preparando sua conexão com o Hermes…
    </p>
    <div v-if="error" class="banner error" role="alert">{{ error }}</div>
    <p v-if="status?.ready && !pending" class="banner success" role="status">
      Tudo certo! Sua conta Nous já está autorizada e a Ayla está pronta para conversar.
    </p>
    <p v-else-if="status?.loggedIn && !pending" class="banner info" role="status">
      Sua conta Nous está conectada. {{ status.message }}
    </p>
    <p v-else-if="status && !status.connected" class="banner info" role="status">
      {{ status.message }} Use “Verificar conexão” para tentar novamente.
    </p>

    <div class="button-row">
      <button
        v-if="pending || (!status?.ready && !status?.loggedIn)"
        type="button"
        class="button primary"
        :disabled="busy || loading || !status || (status.provisioned && !status.connected)"
        @click="startLogin"
      >
        <Icon :name="busy ? 'loading' : 'shield'" :size="17" />{{
          loginState ? 'Reiniciar autorização' : 'Conectar conta Nous'
        }}
      </button>
      <button type="button" class="button secondary" :disabled="busy || loading" @click="refresh">
        <Icon :name="busy ? 'loading' : 'refresh'" :size="17" />Verificar conexão
      </button>
      <button
        v-if="management && status?.loggedIn && !pending"
        type="button"
        class="button subtle"
        :disabled="busy"
        @click="startLogin"
      >
        Autorizar conta Nous novamente
      </button>
    </div>
    <template v-if="pending || (!status?.ready && !status?.loggedIn)">
      <div v-if="pending" class="hermes-authorization">
        <span class="eyebrow">CÓDIGO PARA CONFERÊNCIA</span>
        <strong class="hermes-user-code">{{ login.user_code }}</strong>
        <p>
          Confira se este mesmo código aparece na página da Nous. Não é um campo para preencher.
        </p>
        <ExternalLink class="button primary" :href="login.verification_url"
          >Abrir a Nous e autorizar</ExternalLink
        >
        <p class="muted" role="status">
          <Icon name="loading" :size="16" />Aguardando você autorizar na Nous…
        </p>
        <small v-if="expiresAt"
          >Link válido por mais {{ countdown }}. Se expirar, reinicie a autorização.</small
        >
      </div>
      <ol class="hermes-guide">
        <li>
          <span class="hermes-guide-number">1</span>
          <div>
            <strong>Comece a conexão aqui</strong>
            <p>Clique em “Conectar conta Nous” para gerar seu link de autorização.</p>
          </div>
        </li>
        <li>
          <span class="hermes-guide-number">2</span>
          <div>
            <strong>Entre na sua conta Nous</strong>
            <p>
              Abra o link pelo botão abaixo. Na página da Nous, faça login com seu e-mail ou uma das
              opções disponíveis. Se ainda não tiver uma conta, siga as instruções da página para
              criar a sua.
            </p>
          </div>
        </li>
        <li>
          <span class="hermes-guide-number">3</span>
          <div>
            <strong>Confira o código e autorize</strong>
            <p>
              Compare o código mostrado na Nous com o desta tela. Ele serve apenas para conferir a
              solicitação: <strong>você não precisa copiar nem colar o código</strong>. Se os dois
              forem iguais, confirme a autorização de acesso ao Hermes na página da Nous.
            </p>
          </div>
        </li>
        <li>
          <span class="hermes-guide-number">4</span>
          <div>
            <strong>Volte para a Ayla</strong>
            <p>
              Depois de autorizar, volte a esta aba. A conexão será reconhecida automaticamente.
              {{
                onboarding
                  ? 'Quando aparecer “Pronto para conversar”, clique em “Concluir”.'
                  : 'Aguarde aparecer “Pronto para conversar”.'
              }}
            </p>
          </div>
        </li>
      </ol>
    </template>

    <p v-if="!loading && status && !status.provisioned" class="footnote">
      Ao conectar, a Ayla prepara seu espaço no Hermes antes de abrir a autorização da Nous.
    </p>
    <div v-if="management" class="hermes-management">
      <section class="hermes-management-card">
        <Icon name="wallet" :size="22" />
        <h3>Conta e planos da Nous</h3>
        <p class="muted">
          Consulte seu uso, os planos disponíveis e as opções para ampliar sua assinatura. Entre com
          a mesma conta Nous que você conectou à Ayla.
        </p>
        <ExternalLink class="button secondary" href="https://portal.nousresearch.com/billing"
          >Ver planos e gerenciar assinatura</ExternalLink
        >
        <small
          >Preços, limites e contratação são apresentados pela Nous. Depois de alterar seu plano,
          volte aqui e clique em “Verificar conexão”.</small
        >
      </section>
      <section class="hermes-management-card">
        <Icon name="sparkles" :size="22" />
        <h3>Preferências da Ayla</h3>
        <p class="muted">
          Ajuste a inteligência e a personalidade da sua assistente nas configurações da plataforma.
        </p>
        <RouterLink
          class="button secondary"
          :to="{ path: '/configuracoes', query: { aba: 'agent' } }"
          >Ajustar inteligência e personalidade<Icon name="arrow" :size="17"
        /></RouterLink>
      </section>
    </div>
  </section>
</template>
<style scoped>
.hermes-connection {
  display: grid;
  gap: 20px;
  min-width: 0;
}
.hermes-heading {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
}
.hermes-heading > div {
  flex: 1;
  min-width: min(100%, 260px);
}
.hermes-heading p {
  margin-top: 10px;
  line-height: 1.8;
}
.hermes-guide {
  display: grid;
  gap: 20px;
  list-style: none;
  padding: 0;
  margin: 0;
}
.hermes-guide li {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}
.hermes-guide-number {
  display: grid;
  place-items: center;
  flex-shrink: 0;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--accent-bg);
  color: var(--accent);
  font-weight: 700;
}
.hermes-guide p {
  margin-top: 6px;
  color: var(--muted);
  line-height: 1.8;
  font-size: 13px;
}
.hermes-authorization {
  display: grid;
  gap: 12px;
  padding: 20px;
  border: 1px solid var(--accent);
  border-radius: 14px;
  background: var(--accent-bg);
}
.hermes-user-code {
  font-size: clamp(22px, 6vw, 32px);
  letter-spacing: 3px;
  color: var(--accent);
  overflow-wrap: anywhere;
}
.hermes-authorization p {
  line-height: 1.7;
  font-size: 13px;
}
.hermes-authorization p[role='status'] {
  display: flex;
  align-items: center;
  gap: 8px;
}
.hermes-management {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px 24px;
  border-top: 1px solid var(--line);
  padding-top: 22px;
}
.hermes-management-card {
  display: grid;
  grid-row: span 5;
  grid-template-rows: subgrid;
  gap: 14px;
  min-width: 0;
}
.hermes-management-card > svg {
  color: var(--accent);
}
.hermes-management-card p {
  line-height: 1.8;
}
.hermes-management-card .button {
  justify-self: stretch;
  justify-content: space-between;
  white-space: normal;
  text-align: left;
}
@media (max-width: 700px) {
  .hermes-management {
    grid-template-columns: 1fr;
    gap: 24px;
  }
  .hermes-management-card {
    grid-row: auto;
    grid-template-rows: none;
  }
  .hermes-authorization {
    padding: 16px;
  }
}
</style>
