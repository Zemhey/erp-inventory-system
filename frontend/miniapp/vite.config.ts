import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

/**
 * H5 开发时通过 Vite 代理把 /api 转给本泳道后端 8082。
 * 小程序 / Android 没有代理，必须在构建时用 VITE_API_BASE_URL 配绝对地址
 * （见 README「三端配置」与 src/config.ts 的就绪校验）。
 */
export default defineConfig({
  plugins: [uni()],
  server: {
    // 本泳道固定 3002：8080/3000 是用户进程，8081/3001 是 E2E 保留端口
    port: 3002,
    host: '127.0.0.1',
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8082',
        changeOrigin: true,
      },
    },
  },
  build: {
    sourcemap: false,
  },
})
