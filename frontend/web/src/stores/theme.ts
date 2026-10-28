import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

/**
 * 主题（浅色 / 深色）。
 *
 * ★ 切换的是 <html class="dark">，而不是给每个组件传 prop：
 *   项目已有 80 个页面，全部依赖 --ys-* / --el-* 两层 CSS 变量。
 *   只翻一个 class，Element Plus 自带的 dark/css-vars.css 和我们的
 *   theme-dark.css 会同时接管，页面组件一行都不用改。
 *
 * ★ 初始值三档优先级：用户显式选择 → 系统偏好 → 浅色。
 *   用户一旦点过按钮，就不再用系统偏好覆盖他（prefers-color-scheme 只做首次默认）。
 *
 * ★ 首屏防闪：index.html 的内联脚本会在样式加载前先打上 class，
 *   这里只负责"接管之后"的状态同步，不与内联脚本抢跑。
 */

export type ThemeMode = 'light' | 'dark'

export const THEME_KEY = 'erp.theme'

/** 用户在 index.html 内联脚本里已经算过一次，这里读同一份约定。 */
export function readInitialMode(): ThemeMode {
  try {
    const saved = localStorage.getItem(THEME_KEY)
    if (saved === 'light' || saved === 'dark') return saved
  } catch {
    // localStorage 被禁用（隐私模式 / 沙箱 iframe）时按系统偏好走
  }
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

function applyToDocument(mode: ThemeMode): void {
  const root = document.documentElement
  root.classList.toggle('dark', mode === 'dark')
  root.dataset.theme = mode
  // 让浏览器原生控件（滚动条、日期选择器、表单）跟着变，不只是我们的 CSS
  root.style.colorScheme = mode
}

export const useThemeStore = defineStore('theme', () => {
  const mode = ref<ThemeMode>(readInitialMode())
  const isDark = computed(() => mode.value === 'dark')

  // store 首次被使用时对齐一次 DOM（例如从其它标签页切回来、或 index.html 没跑到）
  applyToDocument(mode.value)

  function setMode(next: ThemeMode): void {
    mode.value = next
    applyToDocument(next)
    try {
      localStorage.setItem(THEME_KEY, next)
    } catch {
      // 存不下就算了，本次会话内仍然生效
    }
  }

  function toggle(): void {
    setMode(mode.value === 'dark' ? 'light' : 'dark')
  }

  return { mode, isDark, setMode, toggle }
})
