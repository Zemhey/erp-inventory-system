/// <reference types="vite/client" />

/**
 * .vue 文件的模块类型声明。
 *
 * 为什么必须手写：TypeScript 不认识 .vue 后缀，
 * 缺少这个声明时 `import App from './App.vue'` 会报
 * "Cannot find module './App.vue'"。
 * Vite 官方模板都包含此文件。
 */
declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}
