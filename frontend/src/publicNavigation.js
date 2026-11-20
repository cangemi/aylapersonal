import '@fontsource/kodchasan/700.css'
import { watch } from 'vue'
import { auth, restore } from './lib/api'

const homeLinks = document.querySelectorAll('[data-home-link]')
const loginLinks = document.querySelectorAll('[data-public-login]')
const accountLinks = document.querySelectorAll('[data-signed-in]')

watch(
  [() => auth.checked, () => Boolean(auth.session)],
  ([checked, signedIn]) => {
    for (const link of homeLinks) link.href = signedIn ? '/painel?visao=geral' : '/'
    for (const link of loginLinks) link.hidden = !checked || signedIn
    for (const link of accountLinks) link.hidden = !checked || !signedIn
  },
  { immediate: true },
)

void restore()
