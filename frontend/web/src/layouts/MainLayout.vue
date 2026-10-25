<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, OfficeBuilding, SwitchButton } from '@element-plus/icons-vue'
import { navGroups } from '@/router/registry'
import { useSessionStore } from '@/stores/session'

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
          <el-button text :icon="SwitchButton" @click="handleLogout">退出</el-button>
        </div>
      </el-header>

      <!-- 内容区：以 tenantId 为 key，切换租户后强制重挂当前页，避免残留旧租户数据 -->
      <el-main class="main">
        <router-view :key="session.tenantId ?? 'no-tenant'" />
      </el-main>
    </el-container>
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
