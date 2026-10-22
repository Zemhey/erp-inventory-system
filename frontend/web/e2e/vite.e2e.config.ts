import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

const backend = process.env.E2E_BACKEND_URL ?? 'http://127.0.0.1:8081'

/**
 * E2E 专用 Vite 配置：3001 + 代理到 8081。
 * 不碰用户日常使用的 3000 / 8080。
 */
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('../src', import.meta.url)),
    },
  },
  server: {
    port: Number(process.env.E2E_WEB_PORT ?? 3001),
    host: '127.0.0.1',
    strictPort: true,
    proxy: {
      '/api': {
        target: backend,
        changeOrigin: true,
      },
    },
  },
})
