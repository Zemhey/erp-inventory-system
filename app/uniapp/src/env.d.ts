/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** API 根地址；H5 缺省为 /api/v1（走 Vite 代理），小程序/Android 必须为绝对地址。 */
  readonly VITE_API_BASE_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>
  export default component
}
