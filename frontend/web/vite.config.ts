import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

/**
 * Vite 配置。
 *
 * ★ 关键点：dev 阶段通过 proxy 把 /api 转发到后端 8080。
 *   为什么不用 CORS：开发时前端跑在 5173，后端在 8080，浏览器会因跨域拦截。
 *   用 proxy 让浏览器以为一切同源，既省掉后端加 CORS 配置，
 *   也让"接口路径"在开发与生产（Nginx 反代）两种环境下保持一致。
 */
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    // ★ 端口选 3000 而不是 Vite 默认的 5173。
    //   本机实测：5173 与 5174 都无法绑定（`listen EACCES`），
    //   而 3000 / 8080 / 8081 / 9000 均可正常绑定。
    //   5173 不在 `netsh int ipv4 show excludedportrange` 列出的排除段里，
    //   属于被其他保留策略占用的情形 —— 换端口是最省事的解法，
    //   不必为此去改系统保留配置（那需要管理员权限且有副作用）。
    port: 3000,
    // 显式绑 IPv4 回环：本机 localhost 解析到 ::1，而 Vite 绑 ::1 曾被拒
    host: '127.0.0.1',
    // 端口被占用时直接失败，而不是静默换端口 —— 否则你会对着旧端口调试半天
    strictPort: true,
    proxy: {
      // 与后端 application.yml 的 server.servlet.context-path 保持一致
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    // 生产构建不生成 sourcemap：减少产物体积，且不泄露源码结构
    sourcemap: false,
  },
})
