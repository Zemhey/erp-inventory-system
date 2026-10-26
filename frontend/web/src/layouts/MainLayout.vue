<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, Key, OfficeBuilding, Search, SwitchButton } from '@element-plus/icons-vue'
import { navGroups } from '@/router/registry'
import { useSessionStore } from '@/stores/session'
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

/** 首屏与前进/后退时，确保当前路由所在的二级菜单是展开的。 */
const menuRef = ref<{ open: (index: string) => void } | null>(null)
const defaultOpeneds = computed(() =>
  currentGroup.value && !currentGroup.value.flat ? [currentGroup.value.key] : [],
)
watch(
  () => route.path,
  () => {
    const group = currentGroup.value
    if (!group || group.flat || menuCollapsed.value) return
    nextTick(() => menuRef.value?.open(group.key))
  },
)

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
        <span v-if="!menuCollapsed">进销存</span>
        <span v-else>ERP</span>
      </div>
      <el-menu
        ref="menuRef"
        :default-active="activeMenu"
        :default-openeds="defaultOpeneds"
        :collapse="menuCollapsed"
        unique-opened
        router
        class="menu"
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
          <el-button text :icon="SwitchButton" @click="handleLogout">退出</el-button>
        </div>
      </el-header>

      <!-- 内容区：以 tenantId 为 key，切换租户后强制重挂当前页，避免残留旧租户数据 -->
      <el-main class="main">
        <router-view :key="session.tenantId ?? 'no-tenant'" />
      </el-main>
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
  background-color: #304156;
  transition: width 0.2s;
  overflow-x: hidden;
}

.logo {
  height: 56px;
  line-height: 56px;
  text-align: center;
  color: #fff;
  font-size: 17px;
  font-weight: 600;
  letter-spacing: 1px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.menu {
  border-right: none;
  background-color: #304156;
}

.menu :deep(.el-menu-item),
.menu :deep(.el-sub-menu__title) {
  color: #bfcbd9;
}

.menu :deep(.el-menu-item.is-active) {
  color: #409eff;
  background-color: #263445;
}

.menu :deep(.el-menu-item:hover),
.menu :deep(.el-sub-menu__title:hover) {
  background-color: #263445;
}

.menu :deep(.el-sub-menu.is-active > .el-sub-menu__title) {
  color: #409eff;
}

/* 折叠态的子菜单浮层被 teleport 到 body，作用域样式覆盖不到，只能 :global + popper-class。 */
:global(.erp-menu-popup .el-menu-item) {
  color: #303133;
}

:global(.erp-menu-popup .el-menu-item.is-active) {
  color: #409eff;
  background-color: #ecf5ff;
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  height: 56px;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.header-title {
  font-size: 15px;
  font-weight: 500;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.tenant-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 10px;
  border-radius: 12px;
  background: #ecf5ff;
  color: #409eff;
  font-size: 13px;
  line-height: 22px;
  cursor: pointer;
}

.tenant-chip.static {
  cursor: default;
}

.global-search {
  position: relative;
}

.search-input {
  width: 240px;
}

.search-overlay {
  position: fixed;
  inset: 0;
  z-index: 1999;
}

.search-panel {
  position: absolute;
  top: 38px;
  right: 0;
  z-index: 2000;
  width: 320px;
  max-height: 60vh;
  overflow-y: auto;
  padding: 8px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.12);
}

.search-loading,
.search-empty {
  padding: 16px 8px;
  text-align: center;
  color: #909399;
  font-size: 13px;
}

.search-group {
  margin-bottom: 6px;
}

.search-group-title {
  padding: 4px 8px;
  font-size: 12px;
  font-weight: 600;
  color: #909399;
}

.search-hit {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 4px;
  cursor: pointer;
}

.search-hit:hover {
  background: #f5f7fa;
}

.search-hit-no {
  font-size: 13px;
  color: #303133;
}

.search-hit-sub {
  font-size: 12px;
  color: #909399;
}

.tenant-name {
  max-width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tenant-code {
  color: #909399;
  font-size: 12px;
}

.ml-8 {
  margin-left: 8px;
}

.main {
  padding: 16px;
  overflow-y: auto;
  background-color: #f5f7fa;
}

@media (max-width: 768px) {
  .header {
    padding: 0 12px;
  }

  .header-title {
    font-size: 14px;
  }

  /* 窄屏优先保证"退出"可点，用户名/角色让位给内容区 */
  .user-label {
    display: none;
  }

  .main {
    padding: 8px;
  }
}
</style>
