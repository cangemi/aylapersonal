<script setup>
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { api, setSession } from '../lib/api'
import Icon from '../components/Icon.vue'
import AylaLogo from '../components/AylaLogo.vue'
import backLogin from '../../assets/images/BackLogin.png'
const router = useRouter(),
  route = useRoute(),
  mode = ref('login'),
  busy = ref(false),
  error = ref(''),
  notice = ref(''),
  username = ref(''),
  password = ref(''),
  setupKey = ref('')
function switchMode(value) {
  mode.value = value
  error.value = ''
  notice.value = ''
  password.value = ''
  setupKey.value = ''
}
async function submit() {
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    if (mode.value === 'forgot') {
      const result = await api('/auth/forgot-password', {
        public: true,
        method: 'POST',
        body: { username: username.value },
      })
      notice.value = result.message
      return
    }
    if (mode.value === 'setup') {
      await api('/auth/setup', {
        public: true,
        method: 'POST',
        headers: { 'X-Ayla-Setup-Key': setupKey.value },
        body: { username: username.value, password: password.value },
      })
      setupKey.value = ''
    }
    const session = await api('/auth/login', {
      public: true,
      method: 'POST',
      body: { username: username.value, password: password.value },
    })
    setSession(session)
    password.value = ''
    const redirect =
      typeof route.query.redirect === 'string' &&
      route.query.redirect.startsWith('/') &&
      !route.query.redirect.startsWith('//')
        ? route.query.redirect
        : '/'
    await router.replace(redirect)
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <main class="login-layout" :style="{ '--login-background': `url(${backLogin})` }">
    <section class="login-story">
      <div class="story-content">
        <span class="eyebrow">MENOS CORRERIA. MAIS VOCÊ.</span>
        <h1>A vida acontece.<br />A gente <em>organiza.</em></h1>
        <p>
          Suas contas, suas ideias, sua casa.<br />Um espaço para cuidar do que importa,<br />com
          uma companhia que te entende.
        </p>
      </div>
      <div class="login-floating-cards" aria-hidden="true">
        <span class="login-floating-card card-day"
          ><Icon name="wallet" :size="20" />Tudo em dia</span
        >
        <span class="login-floating-card card-you"
          ><Icon name="sparkles" :size="22" />Do seu jeito</span
        >
        <span class="login-floating-card card-talk"
          ><Icon name="chat" :size="20" />Pode falar comigo</span
        >
      </div>
      <span class="story-footer">Seu cotidiano, com um pouco mais de leveza.</span>
    </section>
    <section class="login-form-section">
      <form class="login-form" @submit.prevent="submit">
        <div class="login-identity">
          <AylaLogo />
          <span>Seu espaço pessoal<span class="login-status">Entre e fique à vontade.</span></span>
        </div>
        <h2>
          {{
            mode === 'forgot'
              ? 'Vamos recuperar seu acesso.'
              : mode === 'setup'
                ? 'Seu espaço começa aqui.'
                : 'Que bom te ver de novo.'
          }}
        </h2>
        <p class="muted">
          {{
            mode === 'forgot'
              ? 'Enviaremos uma senha para o WhatsApp vinculado à sua conta.'
              : mode === 'setup'
                ? 'Crie o primeiro acesso para a sua Ayla.'
                : 'Entre para continuar de onde parou.'
          }}
        </p>
        <div v-if="error" class="banner error" role="alert">{{ error }}</div>
        <div v-if="notice" class="banner success" role="status">{{ notice }}</div>
        <label
          >Seu login<input
            v-model="username"
            autocomplete="username"
            required
            maxlength="120"
            placeholder="Como você se chama por aqui?"
            :disabled="busy" /></label
        ><label v-if="mode !== 'forgot'"
          >Senha<input
            v-model="password"
            type="password"
            :autocomplete="mode === 'setup' ? 'new-password' : 'current-password'"
            :minlength="mode === 'setup' ? 12 : 1"
            required
            maxlength="256"
            placeholder="Sua senha"
            :disabled="busy" /></label
        ><label v-if="mode === 'setup'"
          >Chave de configuração<input
            v-model="setupKey"
            type="password"
            autocomplete="off"
            required
            placeholder="AYLA_AUTH_SETUP_KEY"
            :disabled="busy"
          /><small
            >Use a chave do arquivo .env da API. Ela é necessária apenas no primeiro
            cadastro.</small
          ></label
        ><button
          v-if="mode === 'login'"
          type="button"
          class="text-link forgot-link"
          @click="switchMode('forgot')"
        >
          Esqueci minha senha</button
        ><button class="button primary full" :disabled="busy">
          <Icon v-if="busy" name="loading" :size="18" />{{
            mode === 'forgot'
              ? 'Enviar nova senha'
              : mode === 'setup'
                ? 'Criar meu espaço'
                : 'Entrar no meu espaço'
          }}<Icon name="arrow" :size="18" /></button
        ><button
          v-if="mode !== 'login'"
          class="text-link full"
          type="button"
          @click="switchMode('login')"
        >
          Voltar para o login
        </button>
        <p v-else class="login-bottom">
          Primeira vez por aqui?
          <button type="button" class="text-link" @click="switchMode('setup')">
            Configurar acesso
          </button>
        </p>
        <p class="security-note">
          <Icon name="shield" :size="14" />Seus dados ficam no seu espaço.
        </p>
      </form>
    </section>
  </main>
</template>
