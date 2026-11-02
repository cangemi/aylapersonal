<template>
  <div class="page-heading">
    <div>
      <span class="eyebrow">LEMBRAR, SEMPRE NO HORÁRIO</span>
      <h1>Seus alarmes.</h1>
      <p>Um toque, um horário e a Ayla te chama. Únicos ou repetidos todos os dias.</p>
    </div>
    <span class="stat-icon lavender"><Icon name="bell" :size="24" /></span>
  </div>

  <div v-if="error" class="banner error" role="alert">
    {{ error }}<button class="text-link" @click="load">Tentar novamente</button>
  </div>

  <div v-if="loading" class="loading-line"><Icon name="loading" />Carregando alarmes…</div>

  <button class="button primary alarm-new-button" @click="openCreate">
    <Icon name="plus" :size="18" />Novo alarme
  </button>

  <Modal v-if="dialog" :title="editing ? 'Editar alarme' : 'Novo alarme'" @close="!saving && closeDialog()">
    <form class="form-grid" @submit.prevent="saveAlarm">
      <label>Título do alarme<input v-model="form.title" required maxlength="200" placeholder="Ex.: Pausa no trabalho" /></label>
      <label>Descrição (opcional)<input v-model="form.description" maxlength="4000" placeholder="O que precisa lembrar?" /></label>
      <div class="form-grid two">
        <label>Tipo
          <select v-model="form.type">
            <option value="ONCE">Uma vez</option>
            <option value="DAILY">Todos os dias</option>
            <option value="WEEKLY">Um dia da semana</option>
          </select>
        </label>
        <label>Fuso horário<input v-model="form.timeZone" placeholder="America/Sao_Paulo" /></label>
      </div>
      <label v-if="form.type === 'ONCE'">Quando<input type="datetime-local" v-model="form.alarmAt" required /></label>
      <label v-if="form.type === 'DAILY' || form.type === 'WEEKLY'">Horário<input type="time" v-model="form.timeOfDay" required /></label>
      <label v-if="form.type === 'WEEKLY'">Dia da semana
        <select v-model.number="form.dayOfWeek" required>
          <option :value="1">Segunda</option>
          <option :value="2">Terça</option>
          <option :value="3">Quarta</option>
          <option :value="4">Quinta</option>
          <option :value="5">Sexta</option>
          <option :value="6">Sábado</option>
          <option :value="7">Domingo</option>
        </select>
      </label>
      <p class="muted footnote">
        “Todos os dias” repete diariamente; “Um dia da semana” repete apenas no dia escolhido.
      </p>
      <label class="check-label"><input v-model="form.confirmNeeded" type="checkbox" />Pedir confirmação (repete a cada 5 minutos até confirmar)</label>
      <div class="form-actions">
        <button type="button" class="button secondary" :disabled="saving" @click="closeDialog">Cancelar</button>
        <button class="button primary" :disabled="saving">
          {{ saving ? 'Salvando…' : editing ? 'Salvar alterações' : 'Criar alarme' }}
        </button>
      </div>
    </form>
  </Modal>

  <section>
    <div class="section-heading">
      <h2>Alarmes salvos <span class="count">{{ alarms.length }}</span></h2>
    </div>
    <div v-if="!alarms.length && !loading" class="empty-state alarms-empty">
      <span class="empty-icon"><Icon name="bell" :size="28" /></span>
      <h3>Nenhum alarme por enquanto</h3>
      <p>Crie um ao lado ou peça à Ayla: “me lembre daqui a 30 minutos”.</p>
    </div>
    <ul v-else class="alarm-list">
      <li v-for="alarm in alarms" :key="alarm.id" class="alarm-card">
        <div class="alarm-main">
          <div class="item-top">
            <span class="tag" :class="alarm.type === 'DAILY' ? 'lavender' : alarm.type === 'WEEKLY' ? 'green' : 'blue'">
              {{
                alarm.type === 'DAILY' ? 'Diário' : alarm.type === 'WEEKLY' ? 'Semanal' : 'Único'
              }}
            </span>
            <span v-if="alarm.confirmNeeded" class="tag amber">Confirmação</span>
            <span v-if="alarm.active === false" class="tag green">Já disparou</span>
          </div>
          <h3>{{ alarm.title }}</h3>
          <p class="muted">
            {{
              alarm.type === 'ONCE'
                ? formatAlarm(alarm.alarmAt)
                : alarm.type === 'DAILY'
                  ? 'Todos os dias às ' + alarm.timeOfDay
                  : 'Toda ' +
                    ['segunda', 'terça', 'quarta', 'quinta', 'sexta', 'sábado', 'domingo'][
                      Number(alarm.dayOfWeek || 1) - 1
                    ] +
                    ' às ' +
                    alarm.timeOfDay
            }}
            <span v-if="alarm.timeZone"> · {{ alarm.timeZone }}</span>
          </p>
          <p v-if="alarm.description" class="alarm-description">{{ alarm.description }}</p>
        </div>
        <div class="row-actions">
          <button v-if="alarm.confirmPending" class="button primary" @click="confirmAlarm(alarm)">
            <Icon name="check" :size="16" />Confirmar
          </button>
          <button class="button secondary" @click="editAlarm(alarm)">
            <Icon name="edit" :size="16" />Editar
          </button>
          <button class="button danger" @click="deleteAlarm(alarm)">
            <Icon name="delete" :size="16" />Excluir
          </button>
        </div>
      </li>
    </ul>
  </section>



  <div class="tip-strip">
    <Icon name="chat" :size="20" />
    <span>Também vale pedir à Ayla: “me lembre daqui a 30 minutos”, “alarme todos os dias às 8h” ou “alarme toda terça às 7h”.</span>
    <RouterLink to="/chat" class="text-link">Conversar<Icon name="arrow" :size="16" /></RouterLink>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { api, write } from '../lib/api'
import { toast } from '../lib/ui'
import Icon from '../components/Icon.vue'
import Modal from '../components/Modal.vue'

const alarms = ref([]),
  loading = ref(false),
  error = ref(''),
  saving = ref(false),
  editing = ref(null),
  dialog = ref(false)

const form = ref({
  title: '',
  description: '',
  type: 'ONCE',
  alarmAt: '',
  timeOfDay: '',
  dayOfWeek: 2,
  timeZone: 'America/Sao_Paulo',
  confirmNeeded: false,
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    alarms.value = await api('/alarms')
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

function resetForm() {
  editing.value = null
  form.value = {
    title: '',
    description: '',
    type: 'ONCE',
    alarmAt: '',
    timeOfDay: '',
    dayOfWeek: 2,
    timeZone: 'America/Sao_Paulo',
    confirmNeeded: false,
  }
}

function openCreate() {
  resetForm()
  dialog.value = true
}

function closeDialog() {
  dialog.value = false
  resetForm()
}

function toIso(local) {
  if (!local) return null
  return new Date(local).toISOString()
}

function formatAlarm(value) {
  if (!value) return 'Horário a definir'
  try {
    return new Intl.DateTimeFormat('pt-BR', {
      dateStyle: 'short',
      timeStyle: 'short',
    }).format(new Date(value))
  } catch {
    return value
  }
}

async function saveAlarm() {
  saving.value = true
  try {
    const body = {
      title: form.value.title,
      description: form.value.description,
      type: form.value.type,
      timeZone: form.value.timeZone,
      confirmNeeded: form.value.confirmNeeded,
    }
    if (form.value.type === 'ONCE') body.alarmAt = toIso(form.value.alarmAt)
    else {
      body.timeOfDay = form.value.timeOfDay
      if (form.value.type === 'WEEKLY') body.dayOfWeek = form.value.dayOfWeek
    }
    if (editing.value)
      await write(`/alarms/${editing.value.id}`, 'PATCH', { ...body, version: editing.value.version })
    else await write('/alarms', 'POST', body)
    toast(editing.value ? 'Alarme atualizado.' : 'Alarme criado.')
    closeDialog()
    await load()
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    saving.value = false
  }
}

function editAlarm(alarm) {
  editing.value = alarm
  form.value = {
    title: alarm.title,
    description: alarm.description || '',
    type: alarm.type,
    alarmAt: alarm.alarmAt ? new Date(alarm.alarmAt).toISOString().slice(0, 16) : '',
    timeOfDay: alarm.timeOfDay || '',
    dayOfWeek: alarm.dayOfWeek || 2,
    timeZone: alarm.timeZone || 'America/Sao_Paulo',
    confirmNeeded: alarm.confirmNeeded,
  }
  dialog.value = true
}

async function deleteAlarm(alarm) {
  if (!confirm(`Excluir o alarme “${alarm.title}”?`)) return
  try {
    await api(`/alarms/${alarm.id}?version=${alarm.version}`, { method: 'DELETE' })
    toast('Alarme excluído.')
    await load()
  } catch (e) {
    toast(e.message, 'error')
  }
}

async function confirmAlarm(alarm) {
  try {
    await write(`/alarms/${alarm.id}/confirm`, 'POST', {})
    toast('Alarme confirmado.')
    await load()
  } catch (e) {
    toast(e.message, 'error')
  }
}


onMounted(load)
</script>

<style scoped>
.alarm-new-button { display: block; margin-left: auto; margin-bottom: 1rem; }
.alarm-form-panel h2 { margin-top: 0; }
.form-grid { display: grid; gap: .75rem; }
.form-grid.two { grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); }
.check-label { display: flex; gap: .6rem; align-items: center; font-weight: 500; }
.alarm-list { list-style: none; padding: 0; margin: 0; display: grid; gap: .75rem; }
.alarm-card { display: flex; gap: 1rem; justify-content: space-between; align-items: center; flex-wrap: wrap; padding: 1rem; border-radius: 14px; background: var(--surface, rgba(255, 255, 255, .04)); border: 1px solid var(--border, rgba(255, 255, 255, .08)); }
.alarm-main { flex: 1; min-width: 220px; }
.alarm-main h3 { margin: .35rem 0; }
.alarm-description { margin-top: .35rem; }
.row-actions { display: flex; gap: .5rem; flex-wrap: wrap; }
.notification-list { list-style: none; padding: 0; margin: 0; display: grid; gap: .6rem; }
.notification-list li { display: flex; gap: .75rem; align-items: center; flex-wrap: wrap; }
.alarms-empty { text-align: center; }
.tag.amber { background: rgba(245, 158, 11, .18); color: #fbbf24; }
.tag.green { background: rgba(34, 197, 94, .18); color: #4ade80; }
.tag.blue { background: rgba(59, 130, 246, .18); color: #60a5fa; }
</style>
