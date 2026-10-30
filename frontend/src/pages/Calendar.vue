<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, query, write } from '../lib/api'
import { toast, confirmAction } from '../lib/ui'
import Icon from '../components/Icon.vue'
import Modal from '../components/Modal.vue'
import EmptyState from '../components/EmptyState.vue'
import '../calendar.css'
const route = useRoute(),
  router = useRouter()
const today = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'America/Sao_Paulo',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
}).format(new Date())
const month = ref(today.slice(0, 7)),
  selected = ref(today),
  items = ref([]),
  total = ref(0),
  loading = ref(false),
  loaded = ref(false),
  refreshError = ref(''),
  error = ref(''),
  search = ref('')
const google = ref({ configured: false, connected: false }),
  connecting = ref(false),
  syncRequesting = ref(false),
  notifications = ref([]),
  archivedNotifications = ref([])
const dialog = ref(false),
  saving = ref(false),
  formError = ref(''),
  editing = ref(null),
  form = ref({}),
  reminders = ref([])
let refreshTimer,
  revision = 0,
  extrasRevision = 0,
  inFlight = 0,
  destroyed = false
const monthStart = computed(() => month.value + '-01')
const monthEnd = computed(() => {
  const [y, m] = month.value.split('-').map(Number)
  return month.value + '-' + String(new Date(y, m, 0).getDate()).padStart(2, '0')
})
const monthName = computed(() =>
  new Date(monthStart.value + 'T12:00:00').toLocaleDateString('pt-BR', {
    month: 'long',
    year: 'numeric',
  }),
)
const dayKey = (instant) =>
  new Intl.DateTimeFormat('en-CA', {
    timeZone: 'America/Sao_Paulo',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date(instant))
const labelDate = (day) =>
  new Date(day + 'T12:00:00').toLocaleDateString('pt-BR', {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
  })
const time = (event) =>
  event.allDay
    ? 'Dia inteiro'
    : new Intl.DateTimeFormat('pt-BR', {
        timeZone: event.timeZone,
        hour: '2-digit',
        minute: '2-digit',
      }).format(new Date(event.startsAt)) +
      ' – ' +
      new Intl.DateTimeFormat('pt-BR', {
        timeZone: event.timeZone,
        hour: '2-digit',
        minute: '2-digit',
      }).format(new Date(event.endsAt))
const spans = new WeakMap()
function onDay(event, day) {
  let span = spans.get(event)
  if (!span) {
    span = [dayKey(event.startsAt), dayKey(new Date(new Date(event.endsAt).getTime() - 1))]
    spans.set(event, span)
  }
  return span[0] <= day && span[1] >= day
}
const days = computed(() => {
  const [y, m] = month.value.split('-').map(Number),
    offset = (new Date(y, m - 1, 1).getDay() + 6) % 7
  return Array.from({ length: 42 }, (_, i) => {
    const date = new Date(y, m - 1, 1 - offset + i, 12)
    return {
      number: date.getDate(),
      date: `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`,
      outside: date.getMonth() !== m - 1,
    }
  })
})
const visible = computed(() =>
  items.value.filter((event) =>
    selected.value
      ? onDay(event, selected.value)
      : dayKey(event.startsAt) <= monthEnd.value &&
        dayKey(new Date(new Date(event.endsAt).getTime() - 1)) >= monthStart.value,
  ),
)
function chooseDay(day) {
  selected.value = day.date
  if (day.outside) {
    month.value = day.date.slice(0, 7)
    load()
  }
}
function changeMonth(offset) {
  const [y, m] = month.value.split('-').map(Number)
  const date = new Date(y, m - 1 + offset, 1)
  month.value = date.getFullYear() + '-' + String(date.getMonth() + 1).padStart(2, '0')
  selected.value = null
  load()
}
function retainRows(previous, incoming) {
  const known = new Map(previous.map((row) => [row.id, row]))
  const next = incoming.map((row) => {
    const existing = known.get(row.id)
    return existing && JSON.stringify(existing) === JSON.stringify(row) ? existing : row
  })
  return next.length === previous.length && next.every((row, index) => row === previous[index])
    ? previous
    : next
}
async function load({ background = false } = {}) {
  if (destroyed) return
  if (background && inFlight) return
  inFlight++
  const current = ++revision
  if (!background) loading.value = true
  try {
    const period = { from: monthStart.value, to: monthEnd.value, q: search.value, size: 100 }
    const page = await api('/calendar/events?' + query(period))
    if (current !== revision || destroyed) return
    const incoming = [...page.items]
    for (let n = 1; incoming.length < page.total && n < 50; n++) {
      const next = await api('/calendar/events?' + query({ ...period, page: n }))
      if (current !== revision || destroyed) return
      incoming.push(...next.items)
    }
    items.value = retainRows(items.value, incoming)
    total.value = page.total
    loaded.value = true
    error.value = ''
    refreshError.value = ''
  } catch (e) {
    if (current === revision && !destroyed) {
      if (background)
        refreshError.value = 'Não foi possível atualizar agora. Mantive os dados anteriores.'
      else error.value = e.message
    }
  } finally {
    inFlight--
    if (current === revision) loading.value = false
  }
}
async function extras() {
  if (destroyed) return
  const current = ++extrasRevision
  try {
    const [state, alerts, archived] = await Promise.all([
      api('/calendar/google'),
      api('/calendar/reminders'),
      api('/calendar/reminders?archived=true'),
    ])
    if (!destroyed && current === extrasRevision) {
      if (JSON.stringify(google.value) !== JSON.stringify(state)) google.value = state
      notifications.value = retainRows(notifications.value, alerts)
      archivedNotifications.value = retainRows(archivedNotifications.value, archived)
    }
  } catch {
    /* Keep the last visible data while the server is unavailable. */
  }
}
function localInput(instant, zone) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: zone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(new Date(instant))
  const p = Object.fromEntries(parts.map((part) => [part.type, part.value]))
  return `${p.year}-${p.month}-${p.day}T${p.hour}:${p.minute}`
}
function toOffset(value, zone) {
  const target = Date.parse(value + 'Z')
  if (!Number.isFinite(target)) throw new Error('Informe data e horário válidos.')
  let guess = target
  for (let i = 0; i < 3; i++) {
    const represented = Date.parse(localInput(new Date(guess), zone) + 'Z')
    guess += target - represented
  }
  if (localInput(new Date(guess), zone) !== value)
    throw new Error('Este horário não existe nesse fuso. Escolha outro horário.')
  return new Date(guess).toISOString()
}
function open(event) {
  editing.value = event || null
  formError.value = ''
  const zone = event?.timeZone || 'America/Sao_Paulo',
    date = selected.value || monthStart.value
  form.value = {
    title: event?.title || '',
    description: event?.description || '',
    location: event?.location || '',
    timeZone: zone,
    allDay: event?.allDay || false,
    startsAt: event ? localInput(event.startsAt, zone) : date + 'T09:00',
    endsAt: event ? localInput(event.endsAt, zone) : date + 'T10:00',
  }
  reminders.value = (event?.reminderMinutes || []).map((minutes) =>
    minutes > 0 && minutes % 1440 === 0
      ? { amount: minutes / 1440, unit: 1440 }
      : minutes > 0 && minutes % 60 === 0
        ? { amount: minutes / 60, unit: 60 }
        : { amount: minutes, unit: 1 },
  )
  dialog.value = true
}
async function save() {
  saving.value = true
  formError.value = ''
  try {
    const reminderMinutes = reminders.value.map((r) => Number(r.amount) * Number(r.unit))
    if (reminderMinutes.some((m) => !Number.isInteger(m) || m < 0 || m > 40320))
      throw new Error('Escolha uma antecedência de até 28 dias, em dias, horas ou minutos.')
    const body = {
      ...form.value,
      startsAt: toOffset(form.value.startsAt, form.value.timeZone),
      endsAt: toOffset(form.value.endsAt, form.value.timeZone),
      ...(!editing.value ||
      JSON.stringify([...reminderMinutes].sort((a, b) => a - b)) !==
        JSON.stringify([...(editing.value.reminderMinutes || [])].sort((a, b) => a - b))
        ? { reminderMinutes }
        : {}),
      ...(editing.value ? { version: editing.value.version } : {}),
    }
    await write(
      '/calendar/events' + (editing.value ? '/' + editing.value.id : ''),
      editing.value ? 'PATCH' : 'POST',
      body,
    )
    dialog.value = false
    toast(editing.value ? 'Compromisso atualizado.' : 'Pronto, está na sua agenda.')
    await Promise.all([load(), extras()])
  } catch (e) {
    formError.value = e.message
  } finally {
    saving.value = false
  }
}
async function cancel(event) {
  if (
    !(await confirmAction(
      'Cancelar compromisso?',
      `“${event.title}” será retirado da agenda e seus lembretes serão cancelados.`,
    ))
  )
    return
  try {
    await api(`/calendar/events/${event.id}?version=${event.version}`, { method: 'DELETE' })
    toast('Compromisso cancelado.')
    await Promise.all([load(), extras()])
  } catch (e) {
    toast(e.message, 'error')
  }
}
async function connect() {
  connecting.value = true
  try {
    const result = await api('/calendar/google/connect', { method: 'POST' })
    window.location.assign(result.authorizationUrl)
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    connecting.value = false
  }
}
async function disconnect() {
  if (
    !(await confirmAction(
      'Desconectar o Google?',
      'As agendas deixam de sincronizar. Os compromissos existentes continuam disponíveis.',
    ))
  )
    return
  try {
    await api('/calendar/google', { method: 'DELETE' })
    await extras()
    toast('Google desconectado.')
  } catch (e) {
    toast(e.message, 'error')
  }
}
async function sync() {
  if (syncRequesting.value || google.value.syncStatus === 'SYNCING') return
  syncRequesting.value = true
  try {
    await api('/calendar/google/sync', { method: 'POST' })
    toast('Sincronização solicitada. A agenda será atualizada em instantes.')
    await extras()
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    syncRequesting.value = false
  }
}
async function read(id) {
  try {
    await api(`/calendar/reminders/${id}/read`, { method: 'POST' })
    await extras()
  } catch (e) {
    toast(e.message, 'error')
  }
}
const syncLabel = computed(
  () =>
    ({
      OK: 'Agendas atualizadas',
      PENDING: 'Atualização na fila',
      SYNCING: 'Sincronizando…',
      RETRYING: 'Conexão temporariamente indisponível; vamos tentar de novo.',
      RECONNECT: 'Autorize sua conta Google novamente.',
    })[google.value.syncStatus] || 'Aguardando atualização',
)
function archivedFor(event) {
  const notices = new Map(
    archivedNotifications.value
      .filter((notice) => notice.eventId === event.id)
      .map((notice) => [notice.id, notice]),
  )
  for (const reminder of event.scheduledReminders || []) {
    if (reminder.deliveryStatus === 'SENT' && !notices.has(reminder.id)) {
      notices.set(reminder.id, { ...reminder, whatsappStatus: reminder.deliveryStatus })
    }
  }
  return [...notices.values()].sort(
    (a, b) => new Date(b.dueAt || b.notifiedAt) - new Date(a.dueAt || a.notifiedAt),
  )
}
function pendingScheduled(event) {
  return (event?.scheduledReminders || []).filter(
    (reminder) => reminder.type === 'AT_TIME' && reminder.deliveryStatus !== 'SENT',
  )
}
function pendingRelative(event) {
  return (event.reminderMinutes || []).filter(
    (minutes) =>
      !(event.scheduledReminders || []).some(
        (reminder) =>
          reminder.type === 'BEFORE_START' &&
          reminder.minutesBefore === minutes &&
          reminder.deliveryStatus === 'SENT',
      ),
  )
}
function scheduledDate(reminder, event) {
  return new Intl.DateTimeFormat('pt-BR', {
    timeZone: event.timeZone,
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).format(new Date(reminder.dueAt))
}
function scheduledStatus(reminder) {
  return (
    {
      PENDING: 'Agendado',
      FRONT_ONLY: 'Aviso somente no app',
      SENDING: 'Enviando',
      SENT: 'Enviado ao WhatsApp',
      UNCERTAIN: 'Envio não confirmado',
      SKIPPED: 'Não enviado',
    }[reminder.deliveryStatus] || 'Agendado'
  )
}
function reminderLabel(minutes) {
  return minutes === 0
    ? 'Na hora'
    : minutes % 1440 === 0
      ? `${minutes / 1440} dia(s) antes`
      : minutes % 60 === 0
        ? `${minutes / 60} hora(s) antes`
        : `${minutes} min antes`
}
onMounted(async () => {
  if (route.query.google) {
    toast(
      route.query.google === 'connected'
        ? 'Google conectado. A sincronização vai começar.'
        : 'Não foi possível conectar o Google. Tente novamente.',
      route.query.google === 'connected' ? 'success' : 'error',
    )
    const clean = { ...route.query }
    delete clean.google
    router.replace({ query: clean })
  }
  await Promise.all([load(), extras()])
  if (destroyed) return
  refreshTimer = setInterval(() => {
    if (!dialog.value && !document.hidden) {
      load({ background: true })
      extras()
    }
  }, 60000)
})
onBeforeUnmount(() => {
  destroyed = true
  revision++
  clearInterval(refreshTimer)
})
</script>
<template>
  <div class="page-heading">
    <div>
      <p class="eyebrow">TEMPO PARA O QUE IMPORTA</p>
      <h1>Sua agenda<span class="accent">.</span></h1>
      <p>Os planos, os encontros e um lembrete na hora certa.</p>
    </div>
    <button class="button primary" @click="open()">
      <Icon name="plus" :size="18" />Novo compromisso
    </button>
  </div>
  <div v-if="error" class="banner error" role="alert">
    {{ error }} <button class="text-link" @click="load">Tentar novamente</button>
  </div>
  <div class="calendar-grid-layout">
    <section class="panel month-card" aria-label="Calendário do mês">
      <div class="calendar-month-heading">
        <button class="icon-button" aria-label="Mês anterior" @click="changeMonth(-1)">
          <Icon name="left" />
        </button>
        <h2>{{ monthName }}</h2>
        <button class="icon-button" aria-label="Próximo mês" @click="changeMonth(1)">
          <Icon name="right" />
        </button>
      </div>
      <div class="calendar-days">
        <span
          v-for="(day, index) in ['S', 'T', 'Q', 'Q', 'S', 'S', 'D']"
          :key="index"
          class="calendar-weekday"
          aria-hidden="true"
          >{{ day }}</span
        ><template v-for="day in days" :key="day.date"
          ><button
            :class="[
              'calendar-day',
              { selected: selected === day.date, today: day.date === today, outside: day.outside },
            ]"
            :aria-label="labelDate(day.date)"
            :aria-pressed="selected === day.date"
            @click="chooseDay(day)"
          >
            {{ day.number }}<i v-if="items.some((event) => onDay(event, day.date))" /></button
        ></template>
      </div>
      <button class="text-link calendar-whole-month" @click="selected = null">
        Ver o mês inteiro · {{ total }} compromisso(s)
      </button>
    </section>
    <section class="panel calendar-events-panel">
      <div class="calendar-agenda-heading">
        <div>
          <p class="eyebrow">SEUS COMPROMISSOS</p>
          <h2>{{ selected ? labelDate(selected) : monthName }}</h2>
        </div>
        <Icon name="calendar" :size="25" />
      </div>
      <form class="calendar-search" @submit.prevent="load">
        <input
          v-model="search"
          aria-label="Buscar compromissos no mês"
          placeholder="Procurar na agenda…"
          maxlength="200"
        /><button class="icon-button" aria-label="Buscar"><Icon name="search" /></button>
      </form>
      <p class="calendar-update-status muted footnote" role="status">
        {{ loading ? 'Carregando agenda…' : refreshError }}
      </p>
      <div class="calendar-event-list">
        <EmptyState
          v-show="loaded && !visible.length"
          icon="calendar"
          title="Um espaço nos seus planos"
          description="Se tiver algo para lembrar, é só marcar por aqui ou pedir para a Ayla."
        />
        <article v-for="event in visible" :key="event.id" class="calendar-event">
          <div class="calendar-event-time">
            <strong>{{ event.allDay ? 'Dia todo' : time(event).split(' – ')[0] }}</strong
            ><small>{{
              new Date(event.startsAt).toLocaleDateString('pt-BR', {
                timeZone: event.timeZone,
                day: '2-digit',
                month: 'short',
              })
            }}</small>
          </div>
          <div class="calendar-event-body">
            <h3>{{ event.title }}</h3>
            <p>
              {{ time(event) }}<span v-if="event.location"> · {{ event.location }}</span>
            </p>
            <p v-if="event.description" class="calendar-event-description">
              {{ event.description }}
            </p>
            <div class="calendar-event-reminders">
              <span v-for="minutes in pendingRelative(event)" :key="minutes"
                ><Icon name="bell" :size="12" />{{ reminderLabel(minutes) }}</span
              ><span
                v-for="reminder in pendingScheduled(event)"
                :key="reminder.id"
                :title="scheduledStatus(reminder)"
              >
                <Icon name="bell" :size="12" />{{ scheduledDate(reminder, event) }} ·
                {{ scheduledStatus(reminder) }} </span
              ><span v-if="google.connected && event.googlePending">Google pendente</span
              ><span v-else-if="google.connected && event.googleLinked">Google conectado</span>
            </div>
            <details v-if="archivedFor(event).length" class="calendar-event-archives">
              <summary>
                Lembretes arquivados <span>{{ archivedFor(event).length }}</span>
              </summary>
              <div
                v-for="notice in archivedFor(event)"
                :key="notice.id"
                class="calendar-event-archive-row"
              >
                <span>{{
                  scheduledDate({ dueAt: notice.dueAt || notice.notifiedAt }, event)
                }}</span>
                <small>{{
                  notice.whatsappStatus === 'SENT' ? 'Enviado ao WhatsApp' : 'Lido no app'
                }}</small>
              </div>
            </details>
          </div>
          <div class="calendar-event-actions">
            <button class="icon-button" :aria-label="'Editar ' + event.title" @click="open(event)">
              <Icon name="edit" :size="17" /></button
            ><button
              class="icon-button"
              :aria-label="'Cancelar ' + event.title"
              @click="cancel(event)"
            >
              <Icon name="delete" :size="17" />
            </button>
          </div>
        </article>
      </div>
      <p class="footnote muted calendar-chat-tip">
        <Icon name="sparkles" :size="16" />Pode pedir também: “Ayla, marque dentista amanhã às 15h e
        me lembre duas horas antes”.
      </p>
    </section>
    <section class="panel google-calendar-card">
      <span class="stat-icon lavender"><Icon name="calendar" /></span>
      <div class="google-calendar-copy">
        <h2>Google Agenda</h2>
        <p v-if="!google.connected">
          Sua agenda funciona por aqui. Se quiser, conecte o Google para manter seus compromissos
          nos dois lugares.
        </p>
        <template v-else
          ><p>{{ google.label }}</p>
          <span class="tag lavender">{{ syncLabel }}</span>
          <p v-if="google.lastSyncAt" class="footnote">
            Última atualização: {{ new Date(google.lastSyncAt).toLocaleString('pt-BR') }}
          </p></template
        >
        <p v-if="!google.configured" class="footnote muted">
          A conexão Google ainda não foi habilitada. Sua agenda própria já está pronta.
        </p>
      </div>
      <div class="button-row">
        <button
          v-if="!google.connected || google.syncStatus === 'RECONNECT'"
          class="button secondary"
          :disabled="!google.configured || connecting"
          title="Abre o site do Google para autorizar a conexão"
          @click="connect"
        >
          <Icon name="calendar" :size="17" />Conectar Google<Icon name="external" :size="17" /><span
            class="sr-only"
          >
            (abre a autorização no site do Google)</span
          ></button
        ><template v-if="google.connected"
          ><button
            class="button secondary"
            :disabled="syncRequesting || google.syncStatus === 'SYNCING'"
            :aria-busy="syncRequesting || google.syncStatus === 'SYNCING'"
            @click="sync"
          >
            <Icon :name="syncRequesting || google.syncStatus === 'SYNCING' ? 'loading' : 'refresh'" :size="16" />
            {{ syncRequesting ? 'Solicitando…' : google.syncStatus === 'SYNCING' ? 'Sincronizando…' : 'Sincronizar agora' }}
          </button
          ><button class="text-link" @click="disconnect">Desconectar</button></template
        >
      </div>
    </section>
  </div>
  <section v-if="notifications.length" class="panel calendar-notifications" aria-label="Lembretes">
    <h2><Icon name="bell" :size="19" />Só para não esquecer</h2>
    <div v-for="notice in notifications" :key="notice.id" class="calendar-notice">
      <div>
        <strong>{{ notice.title }}</strong>
        <p>
          {{ labelDate(dayKey(notice.startsAt)) }} ·
          {{
            new Date(notice.startsAt).toLocaleTimeString('pt-BR', {
              timeZone: notice.timeZone,
              hour: '2-digit',
              minute: '2-digit',
            })
          }}
        </p>
        <small v-if="notice.reminderType === 'AT_TIME' && notice.dueAt"
          >Aviso em {{ scheduledDate({ dueAt: notice.dueAt }, notice) }}</small
        >
        <small v-if="notice.whatsappStatus === 'SENT'">Enviado ao WhatsApp.</small>
        <small v-else-if="notice.whatsappStatus === 'FRONT_ONLY'"
          >Disponível no app · nenhum WhatsApp estava ativo.</small
        >
        <small v-else-if="notice.whatsappStatus === 'PENDING'"
          >Aviso no app disponível; aguardando WhatsApp.</small
        ><small v-else-if="['SENDING', 'UNCERTAIN'].includes(notice.whatsappStatus)"
          >A entrega no WhatsApp não foi confirmada.</small
        >
      </div>
      <button class="text-link" @click="read(notice.id)">Entendi</button>
    </div>
  </section>
  <Modal
    v-if="dialog"
    :title="editing ? 'Editar compromisso' : 'O que vamos marcar?'"
    @close="!saving && (dialog = false)"
    ><form class="calendar-event-form" @submit.prevent="save">
      <div v-if="formError" class="banner error" role="alert">{{ formError }}</div>
      <label
        >Compromisso<input
          v-model="form.title"
          maxlength="200"
          required
          placeholder="Ex.: Dentista"
      /></label>
      <div class="form-grid">
        <label>Início<input v-model="form.startsAt" type="datetime-local" required /></label
        ><label>Término<input v-model="form.endsAt" type="datetime-local" required /></label>
      </div>
      <label class="calendar-checkbox"
        ><input v-model="form.allDay" type="checkbox" />Dia inteiro</label
      ><label
        >Fuso horário<select v-model="form.timeZone">
          <option value="America/Sao_Paulo">Brasília · São Paulo</option>
          <option value="America/Manaus">Manaus</option>
          <option value="America/Rio_Branco">Rio Branco</option>
          <option value="America/Noronha">Fernando de Noronha</option>
          <option value="Europe/Lisbon">Lisboa</option>
          <option
            v-if="
              ![
                'America/Sao_Paulo',
                'America/Manaus',
                'America/Rio_Branco',
                'America/Noronha',
                'Europe/Lisbon',
              ].includes(form.timeZone)
            "
            :value="form.timeZone"
          >
            {{ form.timeZone }}
          </option>
        </select></label
      ><label
        >Local <span class="muted">· opcional</span
        ><input
          v-model="form.location"
          maxlength="500"
          placeholder="Endereço, sala ou link da reunião" /></label
      ><label
        >Observações <span class="muted">· opcional</span
        ><textarea v-model="form.description" maxlength="4000" rows="2" />
      </label>
      <fieldset class="calendar-reminder-fieldset">
        <legend>Lembrar antes</legend>
        <p class="muted footnote">
          Aviso no app e no WhatsApp principal conectado. Até cinco lembretes, com antecedência
          máxima de 28 dias.
        </p>
        <div v-for="(reminder, index) in reminders" :key="index" class="calendar-reminder-row">
          <input
            v-model="reminder.amount"
            type="number"
            min="0"
            max="40320"
            step="1"
            required
            :aria-label="'Antecedência do lembrete ' + (index + 1)"
          /><select v-model="reminder.unit" aria-label="Unidade de antecedência">
            <option :value="1">minutos antes</option>
            <option :value="60">horas antes</option>
            <option :value="1440">dias antes</option></select
          ><button
            type="button"
            class="icon-button"
            aria-label="Retirar lembrete"
            @click="reminders.splice(index, 1)"
          >
            <Icon name="close" :size="17" />
          </button>
        </div>
        <button
          v-if="reminders.length < 5"
          type="button"
          class="text-link"
          @click="reminders.push({ amount: 1, unit: 60 })"
        >
          <Icon name="plus" :size="15" />Adicionar lembrete
        </button>
        <p v-if="!reminders.length" class="muted footnote">
          Sem aviso de antecedência. Você pode adicionar depois.
        </p>
      </fieldset>
      <fieldset v-if="pendingScheduled(editing).length" class="calendar-reminder-fieldset">
        <legend>Avisos em data e horário</legend>
        <p class="muted footnote">Avisos combinados com a Ayla para um horário específico.</p>
        <div
          v-for="reminder in pendingScheduled(editing)"
          :key="reminder.id"
          class="calendar-scheduled-reminder"
        >
          <Icon name="bell" :size="16" />
          <div>
            <strong>{{ scheduledDate(reminder, editing) }}</strong
            ><small>{{ scheduledStatus(reminder) }}</small>
          </div>
        </div>
      </fieldset>
      <div class="form-actions">
        <button type="button" class="button secondary" :disabled="saving" @click="dialog = false">
          Voltar</button
        ><button class="button primary" :disabled="saving">
          <Icon :name="saving ? 'loading' : 'check'" :size="17" />{{
            saving ? 'Salvando…' : 'Salvar compromisso'
          }}
        </button>
      </div>
    </form></Modal
  >
</template>
