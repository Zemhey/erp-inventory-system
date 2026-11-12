import { computed, ref, watch } from 'vue'
// #ifdef H5
import { setNavigationBarColor } from '@dcloudio/uni-h5'
// #endif

/**
 * 主题：浅色 / 深色 / 跟随系统。
 *
 * ★ 配色不新造一套：全部取自 Web 端 frontend/web/src/styles/theme.css 的 --ys-* 令牌
 *   （暖白底 #f6f2ea、瓷白卡片 #fffdfa、墨黑字 #23201c、黛青主色 #2e5d63），
 *   深色对应 theme-dark.css（暖墨 #14120f、黛青提亮到 #5aa4ab）。两端同一套布料，
 *   换的只是光照。
 *
 * ★ 为什么 CSS 变量要挂在页面根 view 的 class 上，而不是只靠 @media：
 *   跟随系统可以用 @media (prefers-color-scheme)，但"用户手动选了浅色、系统是深色"
 *   这种组合 @media 表达不了。小程序没有 :root，WXSS 里能挂 class 的最近祖先
 *   就是每个页自己的根 view —— 所以 useTheme() 把 class 交给页面绑定。
 *
 * ★ 原生那一层（导航栏 / 标签栏 / 页面底色）不吃 CSS 变量：
 *   theme.json 只跟随系统，手动档必须运行时调 setNavigationBarColor /
 *   setTabBarStyle / setBackgroundColor 才盖得住，否则深色系统下手动切浅色
 *   会留一条深色导航栏。applyChrome() 就是干这个的，每个页面 onShow 调一次。
 */

export type ThemeMode = 'system' | 'light' | 'dark'

const THEME_KEY = 'erp.theme'

/** 与 Web 端同一份约定，两端存的东西可比对（Web 只有 light/dark 两档）。 */
export const THEME_STORAGE_KEY = THEME_KEY

/** 原生层取色：CSS 变量到不了的地方，只能在这里跟 theme.css 对齐一次。 */
const CHROME = {
  light: {
    navBg: '#2e5d63',
    navFg: '#ffffff',
    pageBg: '#f6f2ea',
    tabBg: '#fffdfa',
    tabFg: '#6a6255',
    tabOn: '#2e5d63',
  },
  dark: {
    navBg: '#1d1a16',
    navFg: '#ffffff',
    pageBg: '#14120f',
    tabBg: '#1d1a16',
    tabFg: '#a99e8c',
    tabOn: '#5aa4ab',
  },
} as const

function readSavedMode(): ThemeMode {
  try {
    const raw = uni.getStorageSync(THEME_KEY)
    if (raw === 'system' || raw === 'light' || raw === 'dark') return raw
  } catch {
    // 存储读不到就按默认走，不值得为它打断首屏
  }
  return 'system'
}

/** 系统当前是不是深色。H5 走 matchMedia，小程序/App 走 getSystemInfoSync().theme。 */
function systemIsDark(): boolean {
  try {
    const g = globalThis as { matchMedia?: (q: string) => { matches: boolean } }
    if (typeof g.matchMedia === 'function') return g.matchMedia('(prefers-color-scheme: dark)').matches
    const info = uni.getSystemInfoSync() as { theme?: string }
    return info.theme === 'dark'
  } catch {
    return false
  }
}

const mode = ref<ThemeMode>(readSavedMode())
/** 只在 mode==='system' 时参与判定；系统切换时由 watchSystemTheme 推进。 */
const systemDark = ref(systemIsDark())

const resolved = computed<'light' | 'dark'>(() =>
  mode.value === 'system' ? (systemDark.value ? 'dark' : 'light') : mode.value,
)

/** 绑到页面根 view 上：class="page xxx" :class="themeClass"。 */
const themeClass = computed(() => (resolved.value === 'dark' ? 't-dark' : 't-light'))

/**
 * H5 的桌面手机框和全局弹层画在页面根 view 之外（body / #u-a-*），
 * 拿不到根 view 上的 class；把同一档挂到 body 上，外框才跟着主题换色。
 */
// #ifdef H5
watch(
  resolved,
  (value) => {
    const body = typeof document === 'undefined' ? null : document.body
    if (!body) return
    body.classList.toggle('t-dark', value === 'dark')
    body.classList.toggle('t-light', value === 'light')
  },
  { immediate: true },
)
// #endif

function setMode(next: ThemeMode): void {
  mode.value = next
  try {
    uni.setStorageSync(THEME_KEY, next)
  } catch {
    // 存不下就本次会话内生效，不弹错
  }
  if (next === 'system') systemDark.value = systemIsDark()
  applyChrome()
}

/**
 * 把当前配色同步到原生层。
 *
 * 每个页面 onShow 都要调一次：导航栏颜色是**页面级**状态，切页会被重置成
 * pages.json / theme.json 里的静态值，只在设置页点一下是不够的。
 *
 * ★ 为什么每条都要先判断"这个端有没有这个 API"：
 *   uni 的 API 是分端实现的 —— setBackgroundColor / setTabBarStyle 在 H5 上
 *   压根不是函数，直接调会抛 TypeError；一抛，后面几条就都不执行了，
 *   表现就是"H5 上导航栏跟着变、底栏颜色不变"这种半拉子状态。
 *   fail 回调只兜"API 存在但这次调用失败"（比如在非标签页调 setTabBarStyle），
 *   兜不住"API 不存在"。
 */
function callNative(name: 'setNavigationBarColor' | 'setBackgroundColor' | 'setTabBarStyle', options: Record<string, unknown>): void {
  const api = uni as unknown as Record<string, unknown>
  const fn = api[name]
  if (typeof fn !== 'function') return
  try {
    ;(fn as (o: unknown) => void)({ ...options, fail: () => undefined })
  } catch {
    // 个别端在特定页面（如自定义导航栏的登录页）会同步抛错，不影响其余条目
  }
}

function applyChrome(): void {
  const c = CHROME[resolved.value]
  // #ifdef H5
  /* 生产包里 uni[name] 是动态取值，会被 tree-shaking 去掉，深色下导航栏停在黛青；
     静态引入 H5 的 API 才能保证它进包（dev 下反而看不出这个问题）。 */
  setNavigationBarColor({ frontColor: c.navFg, backgroundColor: c.navBg }).catch(() => undefined)
  // #endif
  // #ifndef H5
  callNative('setNavigationBarColor', { frontColor: c.navFg, backgroundColor: c.navBg })
  // #endif
  callNative('setBackgroundColor', {
    backgroundColor: c.pageBg,
    backgroundColorTop: c.pageBg,
    backgroundColorBottom: c.pageBg,
  })
  callNative('setTabBarStyle', {
    color: c.tabFg,
    selectedColor: c.tabOn,
    backgroundColor: c.tabBg,
    borderStyle: 'black',
  })
}

/** 跟随系统时监听系统切换；手动档不需要（用户的选择优先）。 */
function watchSystemTheme(): void {
  const onSystemThemeChange = () => {
    if (mode.value !== 'system') return
    systemDark.value = systemIsDark()
    applyChrome()
  }
  try {
    const hook = uni as unknown as { onThemeChange?: (cb: (res: { theme: string }) => void) => void }
    if (typeof hook.onThemeChange === 'function') {
      hook.onThemeChange(() => onSystemThemeChange())
      return
    }
    const g = globalThis as { matchMedia?: (q: string) => { addEventListener?: (t: string, cb: () => void) => void } }
    if (typeof g.matchMedia === 'function') {
      g.matchMedia('(prefers-color-scheme: dark)')?.addEventListener?.('change', onSystemThemeChange)
    }
  } catch {
    // 平台不支持监听就算了，冷启动时仍会按系统当前值取一次
  }
}

export const theme = {
  mode,
  resolved,
  themeClass,
  isDark: computed(() => resolved.value === 'dark'),
  setMode,
  applyChrome,
  watchSystemTheme,
}
