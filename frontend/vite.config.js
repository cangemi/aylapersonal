import { resolve } from 'node:path'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const publicPageFiles = new Map([
  ['/', '/institucional/inicio.html'],
  ['/privacy', '/institucional/privacy.html'],
  ['/terms', '/institucional/terms.html'],
])

function publicPages(server) {
  server.middlewares.use((request, response, next) => {
    const url = request.url || ''
    const [path] = url.split('?', 1)
    const file = publicPageFiles.get(path)
    const withoutTrailingSlash = path.slice(0, -1)
    if (!file && path.endsWith('/') && publicPageFiles.has(withoutTrailingSlash)) {
      response.writeHead(308, { Location: `${withoutTrailingSlash}${url.slice(path.length)}` })
      response.end()
      return
    }
    if (file) request.url = file
    next()
  })
}

export default defineConfig({
  plugins: [
    vue(),
    { name: 'ayla-public-pages', configureServer: publicPages, configurePreviewServer: publicPages },
  ],
  build: {
    rolldownOptions: {
      input: ['index.html', ...Array.from(publicPageFiles.values(), (file) => file.slice(1))].map(
        (file) => resolve(import.meta.dirname, file),
      ),
    },
  },
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': { target: process.env.AYLA_API_URL || 'http://localhost:8080', changeOrigin: true },
      '/actuator': {
        target: process.env.AYLA_API_URL || 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  test: { environment: 'node', include: ['src/**/*.test.js'] },
})
