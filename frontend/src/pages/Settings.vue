<script setup>
import { ref, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { api, auth, setSession, query } from '../lib/api'
import { timestamp } from '../lib/format'
import { useRouter } from 'vue-router'
import { toast, confirmAction } from '../lib/ui'
import Icon from '../components/Icon.vue'
import Modal from '../components/Modal.vue'
const owner = auth.session?.user?.role === 'OWNER',
  displayName = ref(auth.session?.user?.displayName || ''),
  router = useRouter(),
  tab = ref('account'),
  password = ref({ currentPassword: '', newPassword: '', confirm: '' }),
  recovery = ref(''),
  voice = ref(null),
  voices = ref([]),
  models = ref([]),
  config = ref({ model: '', instructions: '' }),
  status = ref(null),
  busy = ref(false),
  preview = ref(null),
  previewSettings = ref(null),
  previewPlayer = ref(null),
  error = ref(''),
  user = ref({ username: '', password: '' })
const accounts = ref({ items: [], total: 0 }),
  accountsPage = ref(0),
  accountsSearch = ref(''),
  accountsApplied = ref(''),
  accountsLoading = ref(false),
  accountsError = ref('')
const resetTarget = ref(null),
  resetForm = ref({ newPassword: '', confirm: '' }),
  resetError = ref('')
function closeReset() {
  if (busy.value) return
  resetTarget.value = null
  resetForm.value = { newPassword: '', confirm: '' }
  resetError.value = ''
}
function openReset(account) {
  resetForm.value = { newPassword: '', confirm: '' }
  resetError.value = ''
  resetTarget.value = account
}
async function loadUsers() {
  if (!owner) return
  accountsLoading.value = true
  accountsError.value = ''
  try {
    accounts.value = await api(
      '/auth/users?' + query({ page: accountsPage.value, size: 20, q: accountsApplied.value }),
    )
  } catch (e) {
    accountsError.value = e.message
  } finally {
    accountsLoading.value = false
  }
}
function searchUsers() {
  accountsApplied.value = accountsSearch.value
  accountsPage.value = 0
  loadUsers()
}
function changeUsersPage(direction) {
  accountsPage.value += direction
  loadUsers()
}
async function removeUser(account) {
  const name = account.displayName || account.username
  if (
    !(await confirmAction(
      'Excluir ' + name + '?',
      'Todos os dados deste usuário serão apagados e não poderão ser recuperados.',
    ))
  )
    return
  await execute(async () => {
    await api('/auth/users/' + encodeURIComponent(account.id), { method: 'DELETE' })
    if (accounts.value.items.length === 1 && accountsPage.value > 0) accountsPage.value--
    await loadUsers()
  }, 'Usuário excluído.')
}
async function toggleUser(account) {
  const enable = !account.enabled
  if (
    !enable &&
    !(await confirmAction(
      'Inativar ' + (account.displayName || account.username) + '?',
      'O acesso será bloqueado e as sessões serão encerradas. Os dados serão preservados.',
    ))
  )
    return
  await execute(
    async () => {
      await api('/auth/users/' + encodeURIComponent(account.id) + '/status', {
        method: 'PATCH',
        body: { enabled: enable },
      })
      await loadUsers()
    },
    enable ? 'Usuário reativado. Ele pode entrar novamente.' : 'Usuário inativado.',
  )
}
watch(tab, (value) => {
  if (value === 'users' && owner) loadUsers()
  else closeReset()
})
async function resetPassword() {
  if (resetForm.value.newPassword !== resetForm.value.confirm) {
    resetError.value = 'As senhas não coincidem.'
    return
  }
  busy.value = true
  resetError.value = ''
  const self = resetTarget.value.id === auth.session?.user?.id
  try {
    await api('/auth/users/' + encodeURIComponent(resetTarget.value.id) + '/reset-password', {
      method: 'POST',
      body: { newPassword: resetForm.value.newPassword },
    })
    resetTarget.value = null
    resetForm.value = { newPassword: '', confirm: '' }
    if (self) {
      setSession(null)
      toast('Senha redefinida. Entre novamente.')
      await router.replace('/login')
    } else toast('Senha redefinida. O usuário já pode entrar com a nova senha.')
  } catch (e) {
    resetError.value = e.message
  } finally {
    busy.value = false
  }
}
let voiceRevision = 0
let disposed = false
function clearPreview() {
  previewPlayer.value?.pause()
  if (preview.value?.startsWith('blob:')) URL.revokeObjectURL(preview.value)
  preview.value = null
  previewSettings.value = null
}
watch(
  voice,
  () => {
    voiceRevision++
    clearPreview()
  },
  { deep: true, flush: 'sync' },
)
onBeforeUnmount(() => {
  disposed = true
  clearPreview()
})
async function load() {
  const tasks = [
    [
      'voz',
      () => Promise.all([api('/me/agent/voice'), api('/me/agent/voice/voices')]),
      ([v, list]) => {
        voice.value = v
        voices.value = list
      },
    ],
    [
      'agente',
      () =>
        Promise.all([
          api('/me/agent/configuration'),
          api('/me/agent/models'),
          owner ? api('/hermes/status') : null,
        ]),
      ([c, list, s]) => {
        config.value = { model: c.model || '', instructions: c.instructions || '' }
        models.value = list
        status.value = s
      },
    ],
  ]
  await Promise.all(
    tasks.map(async ([name, fetch, save]) => {
      try {
        save(await fetch())
      } catch (e) {
        error.value += (error.value ? ' · ' : '') + name + ': ' + e.message
      }
    }),
  )
}
async function execute(action, message) {
  busy.value = true
  try {
    await action()
    if (message) toast(message)
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    busy.value = false
  }
}
async function changePassword() {
  if (password.value.newPassword !== password.value.confirm) {
    toast('As novas senhas não coincidem.', 'error')
    return
  }
  await execute(async () => {
    await api('/auth/change-password', {
      method: 'POST',
      body: {
        currentPassword: password.value.currentPassword,
        newPassword: password.value.newPassword,
      },
    })
    password.value = { currentPassword: '', newPassword: '', confirm: '' }
    setSession(null)
    router.replace('/login')
  }, 'Senha alterada. Entre novamente.')
}
async function saveProfile() {
  await execute(async () => {
    const saved = await api('/auth/profile', {
      method: 'PUT',
      body: { displayName: displayName.value },
    })
    setSession({ ...auth.session, user: saved })
    displayName.value = saved.displayName
  }, 'Nome atualizado!')
}
async function bind() {
  await execute(async () => {
    const result = await api('/auth/recovery-channel', {
      method: 'POST',
      body: { currentPassword: recovery.value },
    })
    recovery.value = ''
    toast('Número vinculado: ' + result.maskedNumber)
  }, null)
}
async function logoutAll() {
  if (
    !(await confirmAction(
      'Encerrar todas as sessões?',
      'Você precisará entrar novamente em todos os dispositivos.',
    ))
  )
    return
  await execute(async () => {
    await api('/auth/logout-all', { method: 'POST' })
    setSession(null)
    router.replace('/login')
  })
}
async function saveVoice() {
  clearPreview()
  await api('/me/agent/voice', {
    method: 'PUT',
    body: {
      voice: voice.value.voice,
      speed: Number(voice.value.speed),
      pitchHz: Number(voice.value.pitchHz),
    },
  })
}
async function hear() {
  const selected = {
    voice: voice.value.voice,
    speed: Number(voice.value.speed),
    pitchHz: Number(voice.value.pitchHz),
  }
  const revision = voiceRevision
  await execute(async () => {
    clearPreview()
    const audio = await api('/me/agent/voice/preview', {
      method: 'POST',
      body: selected,
    })
    if (disposed || revision !== voiceRevision) return
    const bytes = Uint8Array.from(atob(audio.data), (character) => character.charCodeAt(0))
    previewSettings.value = {
      ...selected,
      name: voices.value.find((item) => item.id === selected.voice)?.name || selected.voice,
    }
    preview.value = URL.createObjectURL(new Blob([bytes], { type: audio.mimeType }))
    await nextTick()
    previewPlayer.value?.load()
    try {
      await previewPlayer.value?.play()
    } catch {
      /* The controls remain available when autoplay is blocked. */
    }
  })
}
async function createUser() {
  await execute(async () => {
    await api('/auth/users', { method: 'POST', body: user.value })
    user.value = { username: '', password: '' }
    accountsSearch.value = ''
    accountsApplied.value = ''
    accountsPage.value = 0
    await loadUsers()
  }, 'Usuário criado. Para usar o chat, configure um perfil Hermes separado no backend.')
}
async function saveAgent() {
  await api('/me/agent/configuration', {
    method: 'PUT',
    body: { model: config.value.model || null, instructions: config.value.instructions || null },
  })
  if (owner) status.value = await api('/hermes/status')
}
function refreshSettings() {
  error.value = ''
  load()
}
onMounted(load)
</script>
<template>
  <div class="page-heading">
    <div>
      <span class="eyebrow">DO SEU JEITO</span>
      <h1>Os detalhes fazem a diferença.</h1>
      <p>Ajuste sua conta e a companhia que te acompanha.</p>
    </div>
    <span class="stat-icon lavender"><Icon name="settings" :size="24" /></span>
  </div>
  <div v-if="owner && status" class="banner info current-model" role="status">
    <Icon name="sparkles" :size="18" />
    <span
      >Modelo em uso:
      <strong>{{ status.model || 'indisponível no momento' }}</strong>
      <small v-if="!config.model && status.model"> · escolha automática</small
      ><small v-if="!status.ready"> · {{ status.message }}</small></span
    >
  </div>
  <div class="tabs">
    <button
      v-for="item in owner
        ? [
            ['account', 'Minha conta'],
            ['voice', 'Voz da Ayla'],
            ['agent', 'Inteligência e personalidade'],
            ['users', 'Usuários'],
          ]
        : [
            ['account', 'Minha conta'],
            ['voice', 'Voz da Ayla'],
            ['agent', 'Inteligência e personalidade'],
          ]"
      :key="item[0]"
      :class="{ active: tab === item[0] }"
      @click="tab = item[0]"
    >
      {{ item[1] }}
    </button>
  </div>
  <div v-if="error" class="banner error" role="alert">
    {{ error }}<button class="text-link" @click="refreshSettings">Atualizar</button>
  </div>
  <div v-if="tab === 'account'" class="settings-grid">
    <section class="panel">
      <div class="panel-heading">
        <div>
          <h2>Sua conta</h2>
          <p>{{ auth.session?.user?.username }} · {{ owner ? 'Proprietário' : 'Usuário' }}</p>
        </div>
        <Icon name="shield" />
      </div>
      <form @submit.prevent="saveProfile">
        <h3>Como você quer ser chamado?</h3>
        <label
          >Seu nome<input
            v-model="displayName"
            autocomplete="nickname"
            maxlength="80"
            required
            placeholder="Seu nome ou apelido"
        /></label>
        <p class="muted">Aparece no seu painel e é o nome que a Ayla vai usar com você.</p>
        <button class="button primary" :disabled="busy">Salvar nome</button>
      </form>
      <form @submit.prevent="changePassword">
        <h3>Alterar senha</h3>
        <label
          >Senha atual<input
            v-model="password.currentPassword"
            type="password"
            autocomplete="current-password"
            required /></label
        ><label
          >Nova senha<input
            v-model="password.newPassword"
            type="password"
            autocomplete="new-password"
            minlength="12"
            maxlength="256"
            required
          /><small>Pelo menos 12 caracteres, até 72 bytes.</small></label
        ><label
          >Confirme a nova senha<input
            v-model="password.confirm"
            type="password"
            autocomplete="new-password"
            required /></label
        ><button class="button primary" :disabled="busy">Salvar nova senha</button>
      </form>
    </section>
    <div class="settings-stack">
      <section class="panel">
        <div class="panel-heading">
          <div>
            <h2>Recuperação pelo WhatsApp</h2>
            <p>Um caminho de volta, se esquecer sua senha.</p>
          </div>
          <Icon name="phone" />
        </div>
        <p class="muted">
          Vincule o número atualmente conectado ao seu usuário. Uma troca de número precisa ser
          confirmada com sua senha.
        </p>
        <form @submit.prevent="bind">
          <label
            >Confirme sua senha atual<input
              v-model="recovery"
              type="password"
              autocomplete="current-password"
              required /></label
          ><button class="button secondary" :disabled="busy">Vincular número conectado</button>
        </form>
      </section>
      <section class="panel">
        <h2>Sessões ativas</h2>
        <p class="muted">Encerre seu acesso em todos os dispositivos, incluindo este.</p>
        <button class="button secondary danger-text" :disabled="busy" @click="logoutAll">
          <Icon name="logout" :size="17" />Sair de todos os dispositivos
        </button>
      </section>
    </div>
  </div>
  <section v-if="tab === 'voice'" class="panel settings-narrow">
    <div class="panel-heading">
      <div>
        <h2>Uma voz para a sua Ayla</h2>
        <p>Encontre um tom que combine com você.</p>
      </div>
      <Icon name="audio" />
    </div>
    <form v-if="voice" @submit.prevent="execute(saveVoice, 'Voz atualizada.')">
      <label
        >Voz<select v-model="voice.voice" :disabled="busy">
          <option v-for="v in voices" :key="v.id" :value="v.id">{{ v.name }}</option>
        </select></label
      ><label
        >Velocidade <span class="range-value">{{ Number(voice.speed).toFixed(2) }}×</span
        ><input
          v-model.number="voice.speed"
          type="range"
          min="0.75"
          max="1.50"
          step="0.01"
          :disabled="busy"
        /><small>Mais devagar <span class="float-right">Mais rápido</span></small></label
      ><label
        >Tom
        <span class="range-value">{{ voice.pitchHz > 0 ? '+' : '' }}{{ voice.pitchHz }} Hz</span
        ><input
          v-model.number="voice.pitchHz"
          type="range"
          min="-50"
          max="50"
          step="1"
          :disabled="busy"
        /><small>Mais grave <span class="float-right">Mais agudo</span></small></label
      >
      <div class="button-row">
        <button class="button primary" :disabled="busy">Salvar voz</button
        ><button type="button" class="button secondary" :disabled="busy" @click="hear">
          <Icon :name="busy ? 'loading' : 'volume'" :size="17" />Ouvir sem salvar
        </button>
      </div>
      <audio
        v-if="preview"
        :key="preview"
        ref="previewPlayer"
        :src="preview"
        controls
        class="voice-preview"
      />
      <p v-if="previewSettings" class="footnote">
        Prévia: {{ previewSettings.name }} · {{ previewSettings.speed.toFixed(2) }}× ·
        {{ previewSettings.pitchHz > 0 ? '+' : '' }}{{ previewSettings.pitchHz }} Hz
      </p>
      <p class="footnote">
        Ouça e compare à vontade. Os ajustes só serão aplicados à Ayla ao clicar em Salvar voz.
      </p>
    </form>
    <div v-else class="loading-line">
      Configuração indisponível. Atualize para tentar novamente.
    </div>
  </section>
  <section v-if="tab === 'agent'" class="panel settings-narrow">
    <div class="panel-heading">
      <div>
        <h2>Ayla, com a sua cara</h2>
        <p>{{ status?.message || 'Configuração do agente Hermes' }}</p>
      </div>
      <span class="tag green">Modelos gratuitos</span>
    </div>
    <form @submit.prevent="execute(saveAgent, 'Configuração da Ayla salva.')">
      <label
        >Modelo<select v-model="config.model">
          <option value="">Escolha automática · gratuito</option>
          <option
            v-if="config.model && !models.some((m) => m.model === config.model)"
            :value="config.model"
          >
            {{ config.model }} (indisponível no catálogo atual)
          </option>
          <option v-for="m in models" :key="m.model" :value="m.model">{{ m.model }}</option></select
        ><small
          >O backend permite somente modelos confirmados como gratuitos no Hermes.</small
        ></label
      ><label
        >Suas preferências de conversa<textarea
          v-model="config.instructions"
          rows="7"
          maxlength="4000"
          placeholder="Ex.: Prefiro respostas curtas e um papo bem informal."
        /><small>Complementam a personalidade da Ayla.</small></label
      ><button class="button primary" :disabled="busy">Salvar preferências</button>
    </form>
  </section>
  <div v-if="tab === 'users' && owner" class="settings-users">
    <section class="panel settings-narrow">
      <div class="panel-heading">
        <div>
          <h2>Um espaço para cada pessoa</h2>
          <p>Crie acessos com dados separados dos seus.</p>
        </div>
        <Icon name="shield" />
      </div>
      <form @submit.prevent="createUser">
        <label
          >Login do usuário<input
            v-model="user.username"
            autocomplete="off"
            minlength="3"
            maxlength="120"
            required /></label
        ><label
          >Senha inicial<input
            v-model="user.password"
            type="password"
            autocomplete="new-password"
            minlength="12"
            maxlength="256"
            required
        /></label>
        <div class="banner info">
          Cada usuário tem dados isolados. O chat exige que um perfil Hermes próprio seja
          configurado no backend.
        </div>
        <button class="button primary" :disabled="busy">Criar usuário</button>
      </form>
    </section>
    <section class="panel users-list-panel" :aria-busy="accountsLoading">
      <div class="panel-heading">
        <div>
          <h2>Usuários cadastrados</h2>
          <p>{{ accounts.total }} usuários encontrados.</p>
        </div>
        <button class="button secondary" :disabled="accountsLoading || busy" @click="loadUsers">
          <Icon name="refresh" :size="16" />Atualizar usuários
        </button>
      </div>
      <form class="users-search" @submit.prevent="searchUsers">
        <label
          >Buscar usuário<input
            v-model="accountsSearch"
            maxlength="120"
            placeholder="Nome ou login" /></label
        ><button class="button secondary" :disabled="accountsLoading || busy">
          <Icon name="search" :size="16" />Buscar
        </button>
      </form>
      <div v-if="accountsError" class="banner error" role="alert">{{ accountsError }}</div>
      <p v-if="!accounts.items.length" class="muted">
        {{ accountsLoading ? 'Carregando usuários…' : 'Nenhum usuário encontrado.' }}
      </p>
      <div v-else class="table-wrap">
        <table class="responsive-table">
          <thead>
            <tr>
              <th>Usuário</th>
              <th>Permissão</th>
              <th>Estado</th>
              <th>Cadastro</th>
              <th><span class="sr-only">Ações</span></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="account in accounts.items" :key="account.id">
              <td data-label="Usuário">
                <strong>{{ account.displayName || account.username }}</strong
                ><small v-if="account.displayName" class="block muted">{{ account.username }}</small
                ><span v-if="account.id === auth.session?.user?.id" class="tag">Você</span>
              </td>
              <td data-label="Permissão">
                {{ account.role === 'OWNER' ? 'Superadministrador' : 'Usuário' }}
              </td>
              <td data-label="Estado">
                <span :class="['tag', account.enabled ? 'green' : '']">{{
                  account.enabled ? 'Ativo' : 'Desativado'
                }}</span>
              </td>
              <td data-label="Cadastro">{{ timestamp(account.createdAt) }}</td>
              <td data-label="Ações">
                <div class="row-actions user-actions">
                  <button
                    v-if="account.role !== 'OWNER'"
                    class="button secondary"
                    :disabled="busy"
                    :aria-label="(account.enabled ? 'Inativar ' : 'Reativar ') + account.username"
                    @click="toggleUser(account)"
                  >
                    <Icon name="shield" :size="16" />{{ account.enabled ? 'Inativar' : 'Reativar' }}
                  </button>
                  <button
                    class="button secondary"
                    :disabled="busy"
                    :aria-label="'Redefinir senha de ' + account.username"
                    @click="openReset(account)"
                  >
                    <Icon name="shield" :size="16" />Redefinir senha
                  </button>
                  <button
                    v-if="account.role !== 'OWNER'"
                    class="button secondary danger-outline"
                    :disabled="busy"
                    :aria-label="'Excluir ' + account.username"
                    @click="removeUser(account)"
                  >
                    <Icon name="delete" :size="16" />Excluir
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="pagination">
        <span>Página {{ accountsPage + 1 }}</span>
        <div class="button-row">
          <button
            class="button secondary"
            :disabled="accountsPage === 0 || accountsLoading || busy"
            @click="changeUsersPage(-1)"
          >
            Anterior</button
          ><button
            class="button secondary"
            :disabled="(accountsPage + 1) * 20 >= accounts.total || accountsLoading || busy"
            @click="changeUsersPage(1)"
          >
            Próxima
          </button>
        </div>
      </div>
    </section>
  </div>
  <Modal v-if="resetTarget" title="Redefinir senha" @close="closeReset">
    <p>
      Defina uma nova senha para
      <strong>{{ resetTarget.displayName || resetTarget.username }}</strong> ({{
        resetTarget.username
      }}).
    </p>
    <p class="muted">
      As sessões dessa conta serão encerradas. Para entrar novamente, será necessário usar a nova
      senha.
    </p>
    <div v-if="resetTarget.id === auth.session?.user?.id" class="banner info">
      Esta é a sua conta. Você precisará entrar novamente após salvar.
    </div>
    <form @submit.prevent="resetPassword">
      <div v-if="resetError" class="banner error" role="alert">{{ resetError }}</div>
      <label
        >Nova senha do usuário<input
          v-model="resetForm.newPassword"
          type="password"
          autocomplete="new-password"
          minlength="12"
          maxlength="256"
          required
          :disabled="busy"
        /><small>Pelo menos 12 caracteres, até 72 bytes.</small></label
      >
      <label
        >Confirme a senha do usuário<input
          v-model="resetForm.confirm"
          type="password"
          autocomplete="new-password"
          minlength="12"
          maxlength="256"
          required
          :disabled="busy"
      /></label>
      <div class="form-actions">
        <button type="button" class="button secondary" :disabled="busy" @click="closeReset">
          Cancelar</button
        ><button class="button primary" :disabled="busy">
          {{ busy ? 'Salvando…' : 'Salvar nova senha do usuário' }}
        </button>
      </div>
    </form>
  </Modal>
</template>
