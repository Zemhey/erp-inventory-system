import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

/**
 * H5 开发时通过 Vite 代理把 /api 转给本泳道后端 8082。
 * 小程序 / Android 没有代理，必须在构建时用 VITE_API_BASE_URL 配绝对地址
 * （见 README「三端配置」与 src/config.ts 的就绪校验）。
 *
 * 端口与代理目标可用环境变量覆盖，供 E2E 使用独立端口（3005 → 8085）：
 *   MINIAPP_DEV_PORT=3005 MINIAPP_API_TARGET=http://127.0.0.1:8085
 */

const devPort = Number(process.env.MINIAPP_DEV_PORT ?? 3002)
const apiTarget = process.env.MINIAPP_API_TARGET ?? 'http://127.0.0.1:8082'

export default defineConfig({
  plugins: [uni()],
  server: {
    port: devPort,
    host: '127.0.0.1',
    strictPort: true,
    proxy: {
      '/api': {
        target: apiTarget,
        changeOrigin: true,
      },
    },
  },
  build: {
    sourcemap: false,
  },
})
