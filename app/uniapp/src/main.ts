import { createSSRApp } from 'vue'
import App from './App.vue'

// uni-app Vue3 的固定入口形态：必须导出 createApp 工厂，而不是直接 mount。
export function createApp() {
  const app = createSSRApp(App)
  return { app }
}
