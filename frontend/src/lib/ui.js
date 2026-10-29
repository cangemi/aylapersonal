import { reactive, ref } from 'vue'
export const notices = reactive([])
export function toast(message, type = 'success') {
  const id = crypto.randomUUID()
  notices.push({ id, message, type })
  setTimeout(() => {
    const i = notices.findIndex((n) => n.id === id)
    if (i >= 0) notices.splice(i, 1)
  }, 6500)
}
export const confirmation = ref(null)
export function confirmAction(title, description = 'Essa ação não pode ser desfeita.') {
  return new Promise((resolve) => {
    confirmation.value = { title, description, resolve }
  })
}
export function settleConfirm(value) {
  confirmation.value?.resolve(value)
  confirmation.value = null
}
