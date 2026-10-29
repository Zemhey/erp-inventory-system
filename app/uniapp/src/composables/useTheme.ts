import { onShow } from '@dcloudio/uni-app'
import { theme } from '@/stores/theme'

/**
 * 页面接线用的主题钩子。
 *
 * 用法（每个页两行）：
 *   const { themeClass } = useTheme()
 *   <view class="page xxx" :class="themeClass">
 *
 * ★ 为什么在 onShow 里调 applyChrome 而不是只在启动时调一次：
 *   原生导航栏颜色是页面级状态，切页会被 pages.json / theme.json 的静态值重置，
 *   只在 App.vue 里设一次的话，跳到第二个页面就掉回默认色了。
 * ★ 这里注册的是第二个 onShow，和页面自己拉数据的 onShow 并存，互不影响。
 */
export function useTheme() {
  onShow(() => {
    theme.applyChrome()
  })

  return {
    themeClass: theme.themeClass,
    themeMode: theme.mode,
    resolvedTheme: theme.resolved,
    setThemeMode: theme.setMode,
  }
}
