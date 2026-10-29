import { reactive, watch } from 'vue'
import { auth } from './api'
// In-memory view of the server history; never persist private messages or media in localStorage.
export const transcripts = reactive({})
watch(
  () => auth.session?.user?.id,
  (next, previous) => {
    if (next !== previous) for (const key of Object.keys(transcripts)) delete transcripts[key]
  },
)
