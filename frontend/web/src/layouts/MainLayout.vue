<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import {
  Box,
  Checked,
  Coin,
  DataAnalysis,
  Files,
  Goods,
  Grid,
  House,
  Key,
  Menu as MenuIcon,
  Money,
  OfficeBuilding,
  RefreshLeft,
  Sell,
  Setting,
  ShoppingCart,
  SwitchButton,
  Tickets,
  TrendCharts,
  User,
  Van,
  Wallet,
} from '@element-plus/icons-vue'
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
})

onBeforeUnmount(() => {
  mediaQuery?.removeEventListener('change', syncMobile)
})

const asideWidth = computed(() => (collapsed.value || isMobile.value ? '64px' : '210px'))
const menuCollapsed = computed(() => collapsed.value || isMobile.value)

interface MenuItem {
  path: string
  title: string
  icon: unknown
  permission?: string
}

interface MenuGroup {
  key: string
  title: string
  icon: unknown
  /** 单入口组（工作台）直接渲染成菜单项，不套二级菜单。 */
  flat?: boolean
  items: MenuItem[]
}

/**
 * 菜单分组：8 组、21 个入口。
 *
 * ★ 新增入口只改这里对应的组，不再往一条长列表里追加 ——
 *   之前"只往后加"的约定保证了不冲突，但也把菜单堆成了 21 项流水账。
 * ★ 权限过滤仍然逐个入口做（visibleGroups），空组自动隐藏。
 */
const menuGroups: MenuGroup[] = [
  {
    key: 'workbench',
    title: '工作台',
    icon: House,
    flat: true,
    items: [{ path: '/dashboard', title: '工作台', icon: House }],
  },
  {
    key: 'master-data',
    title: '主数据',
    icon: Grid,
    items: [
      { path: '/products', title: '商品与 SKU', icon: Goods, permission: 'mdm:read' },
      { path: '/suppliers', title: '供应商', icon: OfficeBuilding, permission: 'mdm:read' },
      { path: '/customers', title: '客户 / 会员', icon: User, permission: 'mdm:read' },
      { path: '/warehouses', title: '仓库', icon: Box, permission: 'mdm:read' },
    ],
  },
  {
    key: 'inventory',
    title: '库存',
    icon: Box,
    items: [
      { path: '/inventory', title: '库存查询', icon: MenuIcon, permission: 'inv:read' },
      { path: '/inventory-transfers', title: '调拨作业', icon: Van, permission: 'inv:read' },
      { path: '/inventory-stock-ops', title: '盘点与调整', icon: Files, permission: 'inv:read' },
    ],
  },
  {
    key: 'purchase',
    title: '采购',
    icon: ShoppingCart,
    items: [
      { path: '/purchase-requisitions', title: '请购与质检', icon: Tickets, permission: 'pur:read' },
      { path: '/purchase-orders', title: '进货单', icon: ShoppingCart, permission: 'pur:read' },
      { path: '/supplier-payables', title: '应付与付款', icon: Wallet, permission: 'pur:payable' },
    ],
  },
  {
    key: 'sales',
    title: '销售',
    icon: Sell,
    items: [
      { path: '/sales-quotes', title: '报价单', icon: Tickets, permission: 'sal:read' },
      { path: '/sales-orders', title: '出货单', icon: Sell, permission: 'sal:read' },
      { path: '/shipments', title: '拣货发货', icon: Van, permission: 'sal:read' },
      { path: '/return-orders', title: '退货单', icon: RefreshLeft, permission: 'rma:read' },
      { path: '/receivables', title: '应收 / 收款', icon: Money, permission: 'sal:read' },
    ],
  },
  {
    key: 'finance',
    title: '财务',
    icon: Coin,
    items: [
      { path: '/settlements', title: '结算', icon: Coin, permission: 'set:read' },
      { path: '/margin', title: '毛利分析', icon: TrendCharts, permission: 'cst:read' },
    ],
  },
  {
    key: 'reports',
    title: '报表',
    icon: DataAnalysis,
    items: [{ path: '/reports', title: '报表', icon: DataAnalysis, permission: 'rpt:read' }],
  },
  {
    key: 'system',
    title: '系统',
    icon: Setting,
    items: [
      { path: '/approvals', title: '审批工作台', icon: Checked, permission: 'aps:read' },
      { path: '/iam', title: '用户与角色', icon: Key, permission: 'iam:read' },
      // E1 合并时在本数组末尾追加（不重排、不动其它组）：
      // { path: '/audits', title: '审计查询', icon: Search, permission: 'sys:read' },
      // { path: '/dicts', title: '数据字典', icon: Notebook, permission: 'sys:read' },
    ],
  },
]

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
          <span class="text-muted mr-8 user-label">
            {{ session.displayName || session.username }}
            <template v-if="session.roles.length">（{{ session.roles.join(' / ') }}）</template>
          </span>
          <el-button text :icon="SwitchButton" @click="handleLogout">退出</el-button>
        </div>
      </el-header>

      <!-- 内容区 -->
      <el-main class="main">
        <router-view />
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
