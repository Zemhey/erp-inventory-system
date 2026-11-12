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

/** H5 运行时没有独立类型声明；这里只补我们用到的那个 API。 */
declare module '@dcloudio/uni-h5' {
  export function setNavigationBarColor(options: {
    frontColor?: string
    backgroundColor?: string
    animation?: { duration?: number; timingFunc?: string }
  }): Promise<void>
}
