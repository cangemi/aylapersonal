import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
export default defineConfig({
  plugins: [vue()],
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
