<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import {
  Box,
  Coin,
  DataAnalysis,
  Files,
  Goods,
  House,
  Menu as MenuIcon,
  Money,
  OfficeBuilding,
  RefreshLeft,
  Sell,
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

const allMenus: MenuItem[] = [
  { path: '/dashboard', title: '工作台', icon: House },
  { path: '/products', title: '商品与 SKU', icon: Goods, permission: 'mdm:read' },
  { path: '/suppliers', title: '供应商', icon: OfficeBuilding, permission: 'mdm:read' },
  { path: '/customers', title: '客户 / 会员', icon: User, permission: 'mdm:read' },
  { path: '/warehouses', title: '仓库', icon: Box, permission: 'mdm:read' },
  { path: '/inventory', title: '库存查询', icon: MenuIcon, permission: 'inv:read' },
  { path: '/purchase-orders', title: '进货单', icon: ShoppingCart, permission: 'pur:read' },
  { path: '/sales-orders', title: '出货单', icon: Sell, permission: 'sal:read' },
  // 追加式新增：本数组只往后加，不重排（S5b 的结算页 / 报表页接在后面，见 M2 计划书 §5.6）
  { path: '/return-orders', title: '退货单', icon: RefreshLeft, permission: 'rma:read' },
  { path: '/settlements', title: '结算', icon: Coin, permission: 'set:read' },
  { path: '/reports', title: '报表', icon: DataAnalysis, permission: 'rpt:read' },
  // V2-2 采购闭环：继续追加，不重排既有菜单
  { path: '/purchase-requisitions', title: '请购与质检', icon: Tickets, permission: 'pur:read' },
  { path: '/supplier-payables', title: '应付与付款', icon: Wallet, permission: 'pur:payable' },
  // 追加式新增（V2-1）：库存作业两页，接在报表之后，不重排既有条目
  { path: '/inventory-transfers', title: '调拨作业', icon: Van, permission: 'inv:read' },
  { path: '/inventory-stock-ops', title: '盘点与调整', icon: Files, permission: 'inv:read' },
  // V2-4 追加：毛利分析，只往后加，不重排既有菜单
  { path: '/margin', title: '毛利分析', icon: TrendCharts, permission: 'cst:read' },
  // V2-3 销售闭环：继续往后追加，不重排
  { path: '/sales-quotes', title: '报价单', icon: Tickets, permission: 'sal:read' },
  { path: '/shipments', title: '拣货发货', icon: Van, permission: 'sal:read' },
  { path: '/receivables', title: '应收 / 收款', icon: Money, permission: 'sal:read' },
]

const visibleMenus = computed(() =>
  allMenus.filter((m) => !m.permission || session.hasPermission(m.permission)),
)

const activeMenu = computed(() => route.path)
const currentTitle = computed(() => (route.meta.title as string) || '')

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
      <el-menu :default-active="activeMenu" :collapse="menuCollapsed" router class="menu">
        <el-menu-item v-for="m in visibleMenus" :key="m.path" :index="m.path">
          <el-icon><component :is="m.icon" /></el-icon>
          <template #title>{{ m.title }}</template>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <!-- 顶栏 -->
      <el-header class="header">
        <div class="header-left">
          <el-button v-if="!isMobile" text @click="collapsed = !collapsed">
            {{ collapsed ? '»' : '«' }}
          </el-button>
          <span class="header-title">{{ currentTitle }}</span>
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

.menu :deep(.el-menu-item) {
  color: #bfcbd9;
}

.menu :deep(.el-menu-item.is-active) {
  color: #409eff;
  background-color: #263445;
}

.menu :deep(.el-menu-item:hover) {
  background-color: #263445;
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
