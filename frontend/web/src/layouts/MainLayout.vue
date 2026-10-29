<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowDown,
  DArrowLeft,
  DArrowRight,
  Key,
  Moon,
  OfficeBuilding,
  Search,
  Sunny,
  SwitchButton,
} from '@element-plus/icons-vue'
import { navGroups } from '@/router/registry'
import { useSessionStore } from '@/stores/session'
import { useThemeStore } from '@/stores/theme'
import { onAuthEvent } from '@/api/client'
import ChangePasswordDialog from '@/components/ChangePasswordDialog.vue'
import { skeletonApi, type SearchHit, type SearchResult } from '@/api/skeleton'

/**
 * 主布局：左侧菜单 + 顶栏 + 内容区。
 *
 * ★ 菜单按权限过滤，而不是写死一长串。
 *   权限码与后端 @RequirePermission 完全一致（pur:read / sal:read ...），
 *   这样运营给自己的账号增减权限时，前端菜单会自动跟着变，
 *   不需要改前端代码发版。
 */

const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const theme = useThemeStore()

/**
 * 主题切换。
 *
 * 只在切换的这一刻给 <html> 挂 theme-animate：
 * 常驻过渡会让首次渲染也"淡入"，反而显得卡；切换时的 280ms 渐变则让
 * 深/浅切换像灯被慢慢调亮，而不是"啪"地闪一下。
 */
let themeAnimTimer: ReturnType<typeof setTimeout> | undefined
function toggleTheme() {
  const root = document.documentElement
  root.classList.add('theme-animate')
  theme.toggle()
  clearTimeout(themeAnimTimer)
  themeAnimTimer = setTimeout(() => root.classList.remove('theme-animate'), 320)
}

const collapsed = ref(false)

/**
 * 窄屏用图标侧栏。
 *
 * 移动端不做抽屉式导航（那是 M4 小程序的活）：把 210px 侧栏固定收成 64px 图标条，
 * 至少保证内容区不被挤成一条缝。桌面端仍可手动折叠/展开。
 */
const isMobile = ref(false)
let mediaQuery: MediaQueryList | null = null

function syncMobile(event?: MediaQueryListEvent) {
  isMobile.value = event ? event.matches : (mediaQuery?.matches ?? false)
  if (isMobile.value) collapsed.value = true
}

onMounted(() => {
  mediaQuery = window.matchMedia('(max-width: 768px)')
  syncMobile()
  mediaQuery.addEventListener('change', syncMobile)
  // B1：刷新页面后回填租户/成员列表，并顺带验证"租户是否被停用、token 是否仍有效"。
  // 失败（401）由 axios 拦截器统一跳登录；这里不再重复提示。
  if (session.isLoggedIn) {
    void session.refresh().catch(() => undefined)
  }
})

onBeforeUnmount(() => {
  mediaQuery?.removeEventListener('change', syncMobile)
})

const asideWidth = computed(() => (collapsed.value || isMobile.value ? '64px' : '210px'))
const menuCollapsed = computed(() => collapsed.value || isMobile.value)

/**
 * 菜单分组：直接取导航模块注册表（src/router/modules/*.ts）。
 *
 * ★ 侧边栏不再在本文件里硬编码 —— 一个业务模块的分组、菜单项、路由写在同一个文件，
 *   加页面不需要同时改路由表和侧边栏两处，多人并行开发也不会互相冲突。
 * ★ 权限过滤仍然逐个入口做（visibleGroups），空组自动隐藏。
 */
const menuGroups = navGroups

const visibleGroups = computed(() =>
  menuGroups
    .map((group) => ({
      ...group,
      items: group.items.filter((m) => !m.permission || session.hasPermission(m.permission)),
    }))
    .filter((group) => group.items.length > 0),
)

const activeMenu = computed(() => route.path)
const currentTitle = computed(() => (route.meta.title as string) || '')

const currentGroup = computed(() =>
  menuGroups.find((group) => group.items.some((m) => m.path === route.path)),
)
/** 工作台自身即是组名，不在页头重复显示 "工作台 / 工作台"。 */
const currentGroupTitle = computed(() => {
  const groupTitle = currentGroup.value?.title ?? ''
  return groupTitle && groupTitle !== currentTitle.value ? groupTitle : ''
})

/**
 * 侧栏分组的展开状态：允许同时展开多组，并记住上次展开过哪些。
 *
 * ★ 为什么不用 unique-opened（手风琴）：
 *   本系统 10 个分组、130+ 条菜单，实际干活是"边看库存边点采购"的穿插操作。
 *   开一个关一个，等于每次切业务线都要重新展开同一组 —— 导航被当成了标签页。
 *   现在多个分组可以同时开着，展开/收起的集合写进 localStorage，
 *   刷新、重新登录回来还是原样。
 *
 * ★ 为什么要自己维护 openedGroups：
 *   el-menu 没有 v-model:openeds，只有 default-openeds，而后者仅首次挂载时读一次
 *   （源码里 openedMenus 初始化时 slice 一份，之后没有 watcher）。
 *   所以用 @open / @close 事件同步一份自己的状态，再喂回 default-openeds 供下次挂载用。
 *
 * ★ 三个必须覆盖的入口：
 *   1. 用户手动点开 / 点收   → @open / @close
 *   2. 穿过路由（含前进后退）→ 确保目标分组展开（menuRef.open 内部也会 emit open）
 *   3. 侧栏从收起恢复        → Element 在 collapse 时静默清空内部展开态，这里补开回来
 */
const OPENED_KEY = 'erp.nav.opened'

function readOpenedGroups(): string[] {
  try {
    const raw = localStorage.getItem(OPENED_KEY)
    const parsed: unknown = raw ? JSON.parse(raw) : null
    // 存进去的可能被手改过 / 是旧版本留下的分组 key，这里只认字符串
    return Array.isArray(parsed) ? parsed.filter((k): k is string => typeof k === 'string') : []
  } catch {
    return []
  }
}

const menuRef = ref<{ open: (index: string) => void } | null>(null)
const openedGroups = ref<string[]>(readOpenedGroups())

function persistOpened() {
  try {
    localStorage.setItem(OPENED_KEY, JSON.stringify(openedGroups.value))
  } catch {
    // 隐私模式 / 配额满时 localStorage 不可写 —— 记忆失败不影响本次使用
  }
}

function handleMenuOpen(index: string) {
  // 收起态下的"展开"只是鼠标划过图标弹出的浮层（Element 也走 openMenu），
  // 不能算用户想常开这一组，否则划几下图标就把展开列表污染了。
  if (!index || menuCollapsed.value || openedGroups.value.includes(index)) return
  openedGroups.value = [...openedGroups.value, index]
  persistOpened()
}

function handleMenuClose(index: string) {
  if (!openedGroups.value.includes(index)) return
  openedGroups.value = openedGroups.value.filter((k) => k !== index)
  persistOpened()
}

/** 首屏：当前页面所在分组必须在展开集合里，否则会出现"高亮项藏在关着的组里"。 */
const defaultOpeneds = computed(() => {
  const group = currentGroup.value
  if (!group || group.flat || openedGroups.value.includes(group.key)) {
    return openedGroups.value
  }
  return [...openedGroups.value, group.key]
})

watch(
  () => route.path,
  () => {
    const group = currentGroup.value
    if (!group || group.flat || menuCollapsed.value) return
    nextTick(() => menuRef.value?.open(group.key))
  },
)

watch(menuCollapsed, (isCollapsed) => {
  if (!isCollapsed) restoreOpened()
})

/**
 * 展开侧栏后，把上次开着的分组补开回来。
 *
 * ★ 不能只等一个 nextTick：收起态下子菜单被换成浮层，Element 把内部注册表
 *   （subMenus）清掉了；展开后这批 sub-menu 要重新挂载才登记回来，此时调
 *   open() 会因为取不到 indexPath 直接抛
 *   "Cannot destructure property 'indexPath' of 'subMenus.value[index]'"。
 *   所以按 40ms 重试，直到注册表认得出这些分组为止（上限约 0.5s）。
 *
 * ★ 目标集合要并上当前页所在分组：收起期间用户可能从浮层换到别的组，
 *   展开后停在"当前页的分组是关着的"状态会让人以为导航丢了。
 */
function restoreOpened(tries = 12) {
  const validKeys = visibleGroups.value.filter((g) => !g.flat).map((g) => g.key)
  const targets = new Set(openedGroups.value.filter((key) => validKeys.includes(key)))
  const group = currentGroup.value
  if (group && !group.flat) targets.add(group.key)
  if (targets.size === 0) return
  nextTick(() => {
    let pending = false
    for (const key of targets) {
      try {
        menuRef.value?.open(key)
      } catch {
        pending = true
      }
    }
    if (pending && tries > 0) setTimeout(() => restoreOpened(tries - 1), 40)
  })
}

/** 切换器是否正在请求（避免连点产生多个并发切换）。 */
const switchingTenant = ref(false)

/**
 * 切换行动租户（B1）。
 *
 * ★ 必须等后端返回新 token 再更新会话：切换成功后角色/权限/菜单都换成
 *   目标租户的；若当前页面在新租户里没有权限，直接回工作台，避免停在一个
 *   注定 403 的页面。`router-view` 以 tenantId 为 key，切换后会重新挂载并取数，
 *   不会把旧租户的列表留在屏幕上。
 */
async function handleSwitchTenant(command: string | number | object) {
  const tenantCode = String(command)
  if (!tenantCode || tenantCode === session.tenantCode) return
  switchingTenant.value = true
  try {
    const result = await session.switchTenant(tenantCode)
    ElMessage.success(`已切换到租户「${result.tenantName}」`)
    const required = route.meta.permission
    if (required && !session.hasPermission(required)) {
      void router.push({ name: 'dashboard' })
    }
  } catch {
    // 错误消息已由 axios 拦截器提示（403 非成员/停用，401 登录失效）
  } finally {
    switchingTenant.value = false
  }
}

async function handleLogout() {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '提示', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  session.logout()
  router.push({ name: 'login' })
}

// ─────────────────── 修改密码（FR-IAM-06） ───────────────────

/**
 * 顶栏自助改密 + 强制改密引导。
 *
 * 强制态有两个来源，缺一不可：
 * 1. `session.mustChangePassword`（登录响应 / /auth/me 下发）—— 覆盖"登录后就该改"
 *    与"刷新页面时仍是过期态"；UserRoleView 上写着"首次登录将被强制修改密码"，
 *    兑现的就是这一条。
 * 2. axios 收到 403 `IAM_PASSWORD_EXPIRED` 时广播的事件 —— 覆盖"会话中途才过期"
 *    （密码有效期按天算，没人规定它不能在用户点菜单的那一刻到期）。
 *
 * 后端 PasswordExpiryFilter 在这种状态下会把除改密以外的接口全拦掉，
 * 所以这里不给关闭、不给跳过，只留一个"退出登录"逃生口。
 */
const pwdVisible = ref(false)
const pwdBlockedByApi = ref(false)
const pwdForced = computed(() => session.mustChangePassword || pwdBlockedByApi.value)

function openPasswordDialog() {
  pwdVisible.value = true
}

watch(
  pwdForced,
  (forced) => {
    if (forced) pwdVisible.value = true
  },
  // ★ immediate 不能省：mustChangePassword 是从 localStorage 直接恢复的，
  //   过期用户刷新页面时它在 setup 阶段就已经是 true —— 不加 immediate 就
  //   "永远不变所以永远不触发"，界面停在正常工作台上，点哪儿都是 403。
  { immediate: true },
)

// 改密成功后 mustChangePassword 转假，清掉事件来源的兜底标记，避免对话框被反复顶开。
watch(
  () => session.mustChangePassword,
  (needed) => {
    if (!needed) pwdBlockedByApi.value = false
  },
)

let unsubscribeAuthEvent: (() => void) | undefined

onMounted(() => {
  unsubscribeAuthEvent = onAuthEvent('password-expired', () => {
    pwdBlockedByApi.value = true
    // /auth/me 在过滤器白名单里，借它把后端的真实理由（规则文案）取回来。
    void session.refresh().catch(() => undefined)
  })
})

onBeforeUnmount(() => {
  unsubscribeAuthEvent?.()
})

// ─────────────────────────── 全局搜索（FR-V3-05） ───────────────────────────

/**
 * 顶栏全局搜索：跨 商品 / 客户 / 供应商 / 单据 模糊查。
 *
 * ★ 不依赖任何业务模块权限：搜索本身用 dashboard:read 聚合端点，
 *   命中结果点击跳转到对应主数据 / 单据列表页（跳转目标页各自再判权限）。
 * ★ 防抖 300ms：避免每敲一个字就打一次后端；清空关键词立即收起结果面板。
 */
const searchKeyword = ref('')
const searchOpen = ref(false)
const searchLoading = ref(false)
const searchResult = ref<SearchResult | null>(null)
const searchError = ref('')
let searchTimer: ReturnType<typeof setTimeout> | undefined

function runSearch() {
  const kw = searchKeyword.value.trim()
  if (!kw) {
    searchResult.value = null
    searchOpen.value = false
    return
  }
  searchLoading.value = true
  searchError.value = ''
  skeletonApi
    .search(kw)
    .then((r) => {
      searchResult.value = r
      searchOpen.value = true
    })
    .catch((cause: unknown) => {
      const e = cause as { response?: { data?: { message?: string } }; message?: string }
      searchError.value = e?.response?.data?.message || e?.message || '搜索失败'
      searchResult.value = null
    })
    .finally(() => {
      searchLoading.value = false
    })
}

function onSearchInput() {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(runSearch, 300)
}

function onSearchFocus() {
  if (searchResult.value && searchKeyword.value.trim()) searchOpen.value = true
}

const searchGroups = computed(() => {
  const r = searchResult.value
  if (!r) return []
  return [
    { key: 'products', label: '商品', hits: r.products },
    { key: 'customers', label: '客户', hits: r.customers },
    { key: 'suppliers', label: '供应商', hits: r.suppliers },
    { key: 'orders', label: '单据', hits: r.orders },
  ].filter((g) => g.hits.length > 0)
})

const searchEmpty = computed(
  () =>
    searchResult.value &&
    !searchLoading.value &&
    !searchError.value &&
    searchGroups.value.length === 0,
)

function orderRoute(type: string | undefined): string {
  switch (type) {
    case 'purchase':
      return '/purchase-orders'
    case 'sales':
      return '/sales-orders'
    case 'return':
      return '/return-orders'
    case 'transfer':
      return '/inventory-transfers'
    default:
      return '/dashboard'
  }
}

function goSearchHit(hit: SearchHit, kind: 'products' | 'customers' | 'suppliers' | 'orders') {
  searchOpen.value = false
  const path =
    kind === 'products'
      ? '/products'
      : kind === 'customers'
        ? '/customers'
        : kind === 'suppliers'
          ? '/suppliers'
          : orderRoute(hit.type)
  void router.push(path)
  searchKeyword.value = ''
}
</script>

<template>
  <el-container class="layout">
    <!-- 侧边栏 -->
    <el-aside :width="asideWidth" class="aside">
      <div class="logo">
        <span class="weave" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor">
            <path d="M12 7.4V6.2a2 2 0 1 1 2.6-1.9" />
            <path d="m12 7.4-8 6.2h16l-8-6.2Z" />
            <path d="M4 20h16" />
          </svg>
        </span>
        <span v-if="!menuCollapsed" class="logo-text">
          <b>云裳</b>
          <i>RETAIL OPS</i>
        </span>
      </div>
      <el-menu
        ref="menuRef"
        :default-active="activeMenu"
        :default-openeds="defaultOpeneds"
        :collapse="menuCollapsed"
        router
        class="menu"
        @open="handleMenuOpen"
        @close="handleMenuClose"
      >
        <template v-for="group in visibleGroups" :key="group.key">
          <!-- 工作台是单入口，直接作为一级菜单项，不套一个只有一项的二级菜单 -->
          <el-menu-item v-if="group.flat" :index="group.items[0].path">
            <el-icon><component :is="group.items[0].icon" /></el-icon>
            <template #title>{{ group.items[0].title }}</template>
          </el-menu-item>
          <el-sub-menu v-else :index="group.key" popper-class="erp-menu-popup">
            <template #title>
              <el-icon><component :is="group.icon" /></el-icon>
              <span>{{ group.title }}</span>
            </template>
            <el-menu-item v-for="m in group.items" :key="m.path" :index="m.path">
              <el-icon><component :is="m.icon" /></el-icon>
              <template #title>{{ m.title }}</template>
            </el-menu-item>
          </el-sub-menu>
        </template>
      </el-menu>

      <!-- 折叠开关沉到侧栏底部，和内容区隔开 -->
      <div v-if="!isMobile" class="aside-foot">
        <button class="collapse-btn" type="button" @click="collapsed = !collapsed">
          <el-icon><component :is="collapsed ? DArrowRight : DArrowLeft" /></el-icon>
          <span v-if="!menuCollapsed">收起侧栏</span>
        </button>
      </div>
    </el-aside>

    <el-container>
      <!-- 顶栏 -->
      <el-header class="header">
        <div class="header-left">
          <el-button v-if="!isMobile" text @click="collapsed = !collapsed">
            {{ collapsed ? '»' : '«' }}
          </el-button>
          <el-breadcrumb separator="/" class="header-title">
            <el-breadcrumb-item v-if="currentGroupTitle">{{ currentGroupTitle }}</el-breadcrumb-item>
            <el-breadcrumb-item>{{ currentTitle || '工作台' }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div class="header-right">
          <!-- FR-V3-05：顶栏全局搜索，跨 商品/客户/供应商/单据 模糊查 -->
          <div class="global-search">
            <el-input
              v-model="searchKeyword"
              :prefix-icon="Search"
              placeholder="搜索 商品 / 客户 / 供应商 / 单号"
              clearable
              class="search-input"
              @input="onSearchInput"
              @focus="onSearchFocus"
              @keyup.enter="runSearch"
            />
            <div v-if="searchOpen" class="search-overlay" @click="searchOpen = false" />
            <div v-if="searchOpen && searchKeyword.trim()" class="search-panel">
              <div v-if="searchLoading" class="search-loading">搜索中…</div>
              <el-alert
                v-else-if="searchError"
                type="warning"
                show-icon
                :closable="false"
                :title="`搜索失败：${searchError}`"
              />
              <div v-else-if="searchEmpty" class="search-empty">
                没有匹配「{{ searchKeyword }}」的结果
              </div>
              <template v-else>
                <div v-for="g in searchGroups" :key="g.key" class="search-group">
                  <div class="search-group-title">{{ g.label }}</div>
                  <div
                    v-for="hit in g.hits.slice(0, 8)"
                    :key="`${g.key}-${hit.id}`"
                    class="search-hit"
                    @click="goSearchHit(hit, g.key as 'products' | 'customers' | 'suppliers' | 'orders')"
                  >
                    <template v-if="g.key === 'orders'">
                      <span class="search-hit-no">{{ hit.no }}</span>
                      <span class="search-hit-sub">{{ hit.status }}</span>
                    </template>
                    <template v-else>
                      <span class="search-hit-no">{{ hit.name }}</span>
                      <span class="search-hit-sub">{{ hit.code }}</span>
                    </template>
                  </div>
                </div>
              </template>
            </div>
          </div>

          <!-- B1：当前租户。只有真的是多租户成员才渲染切换器（单租户只显示名称）。 -->
          <el-dropdown
            v-if="session.isMultiTenant"
            trigger="click"
            :disabled="switchingTenant"
            @command="handleSwitchTenant"
          >
            <span class="tenant-chip" title="切换租户">
              <el-icon><OfficeBuilding /></el-icon>
              <span class="tenant-name">{{ session.tenantName || session.tenantCode }}</span>
              <span class="tenant-code">（{{ session.tenantCode }}）</span>
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item
                  v-for="t in session.tenants"
                  :key="t.id"
                  :command="t.code"
                  :disabled="!t.enabled || t.id === session.tenantId"
                >
                  {{ t.name }}（{{ t.code }}）
                  <el-tag v-if="t.id === session.tenantId" size="small" type="success" class="ml-8">
                    当前
                  </el-tag>
                  <el-tag v-else-if="!t.enabled" size="small" type="info" class="ml-8">
                    已停用
                  </el-tag>
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <span v-else-if="session.tenantName" class="tenant-chip static">
            <el-icon><OfficeBuilding /></el-icon>
            <span class="tenant-name">{{ session.tenantName }}</span>
            <span class="tenant-code">（{{ session.tenantCode }}）</span>
          </span>

          <span class="text-muted mr-8 user-label">
            {{ session.displayName || session.username }}
            <template v-if="session.roles.length">（{{ session.roles.join(' / ') }}）</template>
          </span>
          <el-tooltip
            v-if="session.mustChangePassword"
            content="当前账号被要求先修改密码"
            placement="bottom"
          >
            <el-button text type="warning" :icon="Key" @click="openPasswordDialog">
              改密
            </el-button>
          </el-tooltip>
          <el-button v-else text :icon="Key" @click="openPasswordDialog">修改密码</el-button>
          <!-- 深色 / 浅色切换：右上角，图标按钮 + 悬浮提示 -->
          <el-tooltip
            :content="theme.isDark ? '切换到浅色模式' : '切换到深色模式'"
            placement="bottom"
            :show-after="300"
          >
            <button
              type="button"
              class="theme-toggle"
              :aria-label="theme.isDark ? '切换到浅色模式' : '切换到深色模式'"
              :aria-pressed="theme.isDark"
              @click="toggleTheme"
            >
              <el-icon class="theme-icon">
                <component :is="theme.isDark ? Sunny : Moon" />
              </el-icon>
            </button>
          </el-tooltip>
          <el-button text :icon="SwitchButton" @click="handleLogout">退出</el-button>
        </div>
      </el-header>

      <!-- 内容区：以 tenantId 为 key，切换租户后强制重挂当前页，避免残留旧租户数据 -->
      <el-main class="main">
        <router-view :key="session.tenantId ?? 'no-tenant'" />
      </el-main>

      <el-footer class="beian" height="34px">
        <a href="https://beian.miit.gov.cn/" target="_blank" rel="noopener noreferrer">粤ICP备2026167520号</a>
        <span class="beian-sep">·</span>
        <span>个人技术作品演示，数据为虚构种子</span>
      </el-footer>
    </el-container>

    <!-- FR-IAM-06：自助改密；强制态下不给关闭 -->
    <ChangePasswordDialog v-model="pwdVisible" :forced="pwdForced" />
  </el-container>
</template>

<style scoped>
.layout {
  height: 100vh;
}

.aside {
  display: flex;
  flex-direction: column;
  background-color: var(--ys-warm-50);
  border-right: 1px solid var(--ys-line);
  transition: width 0.24s cubic-bezier(0.22, 0.58, 0.32, 1);
  overflow-x: hidden;
}

/* 品牌区：织唛 + 字标，和登录页同一套 */
.logo {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 56px;
  padding: 0 18px;
  flex: none;
  border-bottom: 1px solid var(--ys-line-soft);
}

.weave {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  flex: none;
  border-radius: 7px;
  color: #f6f1e6;
  background: var(--ys-daiqing);
  background-image: repeating-linear-gradient(
      90deg,
      rgba(255, 255, 255, 0.11) 0 1px,
      transparent 1px 3px
    ),
    repeating-linear-gradient(0deg, rgba(255, 255, 255, 0.09) 0 1px, transparent 1px 3px);
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.16);
}

.weave::after {
  content: '';
  position: absolute;
  inset: 4px;
  border: 1px dashed rgba(243, 237, 226, 0.5);
  border-radius: 3px;
}

.weave svg {
  width: 17px;
  height: 17px;
  stroke-width: 1.9;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.logo-text {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
  min-width: 0;
}

.logo-text b {
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.06em;
  color: var(--ys-mo);
}

.logo-text i {
  font-style: normal;
  font-family: var(--ys-font-num);
  font-size: 9px;
  letter-spacing: 0.14em;
  color: var(--ys-mo-4);
  margin-top: 2px;
}

.menu {
  flex: 1;
  border-right: none;
  background-color: transparent;
  padding: 8px 8px 4px;
  overflow-y: auto;
}

.menu :deep(.el-menu-item),
.menu :deep(.el-sub-menu__title) {
  height: 38px;
  line-height: 38px;
  margin: 1px 0;
  border-radius: 9px;
  font-size: 13px;
  color: var(--ys-mo-2);
}

.menu :deep(.el-menu-item .el-icon),
.menu :deep(.el-sub-menu__title .el-icon) {
  color: var(--ys-mo-3);
}

.menu :deep(.el-menu-item:hover),
.menu :deep(.el-sub-menu__title:hover) {
  background-color: var(--ys-hover);
  color: var(--ys-mo);
}

.menu :deep(.el-menu-item.is-active) {
  position: relative;
  color: var(--ys-daiqing-deep);
  background-color: var(--ys-daiqing-wash);
  font-weight: 500;
}

/* 选中项左侧那道 2px 黛青"缝线" */
.menu :deep(.el-menu-item.is-active)::before {
  content: '';
  position: absolute;
  left: -8px;
  top: 9px;
  bottom: 9px;
  width: 2px;
  border-radius: 0 2px 2px 0;
  background: var(--ys-daiqing);
}

.menu :deep(.el-menu-item.is-active .el-icon) {
  color: var(--ys-daiqing);
}

.menu :deep(.el-sub-menu.is-active > .el-sub-menu__title) {
  color: var(--ys-daiqing-deep);
}

/* 折叠态下 Element 会把标题藏起来，这里只保留居中 */
.menu:deep(.el-menu--collapse) {
  padding: 8px 6px;
}

/* 折叠态的子菜单浮层被 teleport 到 body，作用域样式覆盖不到，只能 :global + popper-class。 */
:global(.erp-menu-popup) {
  border-radius: 10px;
  border-color: var(--ys-line);
  box-shadow: var(--ys-shadow-card);
}

:global(.erp-menu-popup .el-menu-item) {
  height: 34px;
  line-height: 34px;
  margin: 1px 6px;
  border-radius: 8px;
  font-size: 13px;
  color: var(--ys-mo-2);
}

:global(.erp-menu-popup .el-menu-item:hover) {
  background: var(--ys-hover);
}

:global(.erp-menu-popup .el-menu-item.is-active) {
  color: var(--ys-daiqing-deep);
  background-color: var(--ys-daiqing-wash);
  font-weight: 500;
}

.aside-foot {
  flex: none;
  padding: 10px;
  border-top: 1px solid var(--ys-line-soft);
}

.collapse-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  width: 100%;
  height: 32px;
  border: 0;
  border-radius: 9px;
  background: transparent;
  font-size: 12px;
  color: var(--ys-mo-3);
  cursor: pointer;
  transition: background-color 0.16s ease, color 0.16s ease;
}

.collapse-btn:hover {
  background: var(--ys-hover);
  color: var(--ys-mo-2);
}

/* ══ 顶栏 ════════════════════════════════════════════════════════════ */
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 56px;
  padding: 0 24px;
  background: var(--ys-header-bg);
  backdrop-filter: blur(8px);
  border-bottom: 1px solid var(--ys-line);
}

.header-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.header-title {
  font-size: 13px;
  font-weight: 400;
}

.header-title :deep(.el-breadcrumb__inner) {
  color: var(--ys-mo-3);
  font-weight: 400;
}

.header-title :deep(.el-breadcrumb__item:last-child .el-breadcrumb__inner) {
  color: var(--ys-mo);
  font-weight: 500;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

/* ── 深浅色切换 ──
   做成"细边小圆钮"而不是 el-switch：
   开关控件暗示"某个设置项"，而这是随时可用的视图开关，形态应该更轻。 */
.theme-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  padding: 0;
  flex: none;
  border: 1px solid var(--ys-line);
  border-radius: 9px;
  background: transparent;
  color: var(--ys-mo-2);
  cursor: pointer;
  transition:
    background-color 0.18s ease,
    border-color 0.18s ease,
    color 0.18s ease,
    transform 0.18s cubic-bezier(0.22, 0.58, 0.32, 1);
}

.theme-toggle:hover {
  background: var(--ys-hover);
  border-color: var(--ys-line-strong);
  color: var(--ys-daiqing);
}

.theme-toggle:active {
  transform: scale(0.94);
}

.theme-icon {
  font-size: 15px;
  transition: transform 0.42s cubic-bezier(0.34, 1.4, 0.64, 1);
}

.theme-toggle:hover .theme-icon {
  transform: rotate(28deg);
}

@media (max-width: 768px) {
  .theme-toggle {
    width: 28px;
    height: 28px;
  }
}

/* 租户标签：不用大块高亮底色，做成可点的细边胶囊 */
.tenant-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 10px;
  border: 1px solid var(--ys-line);
  border-radius: 999px;
  background: var(--ys-cibai);
  color: var(--ys-mo-2);
  font-size: 12px;
  line-height: 22px;
  cursor: pointer;
  transition: border-color 0.16s ease, background-color 0.16s ease;
}

.tenant-chip:hover {
  border-color: var(--ys-line-strong);
  background: var(--ys-warm-50);
}

.tenant-chip.static {
  cursor: default;
}

.tenant-name {
  max-width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tenant-code {
  color: var(--ys-mo-4);
  font-size: 11px;
}

/* ══ 全局搜索 ════════════════════════════════════════════════════════ */
.global-search {
  position: relative;
}

.search-input {
  width: 260px;
}

.search-input :deep(.el-input__wrapper) {
  height: 32px;
  border-radius: 9px;
  background: var(--ys-cibai);
  box-shadow: 0 0 0 1px var(--ys-line) inset;
}

.search-overlay {
  position: fixed;
  inset: 0;
  z-index: 1999;
}

.search-panel {
  position: absolute;
  top: 40px;
  right: 0;
  z-index: 2000;
  width: 340px;
  max-height: 60vh;
  overflow-y: auto;
  padding: 8px;
  background: var(--ys-cibai);
  border: 1px solid var(--ys-line);
  border-radius: 12px;
  box-shadow: var(--ys-shadow-raise, var(--ys-shadow-card));
}

.search-loading,
.search-empty {
  padding: 16px 8px;
  text-align: center;
  color: var(--ys-mo-4);
  font-size: 13px;
}

.search-group {
  margin-bottom: 6px;
}

.search-group-title {
  padding: 6px 8px 4px;
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.04em;
  color: var(--ys-mo-4);
}

.search-hit {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  padding: 7px 8px;
  border-radius: 8px;
  cursor: pointer;
  transition: background-color 0.14s ease;
}

.search-hit:hover {
  background: var(--ys-daiqing-wash);
}

.search-hit-no {
  font-size: 13px;
  color: var(--ys-mo);
}

.search-hit-sub {
  font-family: var(--ys-font-num);
  font-size: 12px;
  color: var(--ys-mo-4);
}

.ml-8 {
  margin-left: 8px;
}

.main {
  padding: 0;
  overflow-y: auto;
  background-color: var(--ys-nuanbai);
}

/* 备案号是备案通过后的展示义务；登录前看不到，所以常驻布局页脚 */
.beian {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  font-size: 12px;
  line-height: 34px;
  color: var(--ys-mo-4);
  border-top: 1px solid var(--ys-line);
}

.beian a {
  color: inherit;
  text-decoration: none;
}

.beian a:hover {
  color: var(--ys-daiqing);
  text-decoration: underline;
}

.beian-sep {
  opacity: 0.55;
}

@media (max-width: 768px) {
  .header {
    padding: 0 12px;
  }

  .header-title {
    font-size: 13px;
  }

  /* 窄屏优先保证"退出"可点，用户名/角色让位给内容区 */
  .user-label {
    display: none;
  }

  .search-input {
    width: 150px;
  }

  .tenant-chip {
    display: none;
  }
}
</style>
