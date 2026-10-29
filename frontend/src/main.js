import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import '@fontsource-variable/dm-sans'
import '@fontsource-variable/manrope'
import '@fontsource/kodchasan/700.css'
import './style.css'
import './mobile.css'
createApp(App).use(router).mount('#app')
if (import.meta.env.PROD && 'serviceWorker' in navigator && window.isSecureContext) {
  window.addEventListener('load', () => {
    navigator.serviceWorker
      .register('/sw.js', { updateViaCache: 'none' })
      .catch(() => console.info('Instalação da Ayla indisponível neste navegador ou certificado.'))
  })
}
