<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import {
  Box,
  Goods,
  House,
  Menu as MenuIcon,
  OfficeBuilding,
  Sell,
  ShoppingCart,
  SwitchButton,
  User,
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
]

const visibleMenus = computed(() =>
  allMenus.filter((m) => !m.permission || session.hasPermission(m.permission)),
)

const activeMenu = computed(() => route.path)
const currentTitle = computed(() => (route.meta.title as string) || '')

async function handleLogout() {
  await ElMessageBox.confirm('确定要退出登录吗？', '提示', {
    confirmButtonText: '退出',
    cancelButtonText: '取消',
    type: 'warning',
  })
  session.logout()
  router.push({ name: 'login' })
}
</script>

<template>
  <el-container class="layout">
    <!-- 侧边栏 -->
    <el-aside :width="collapsed ? '64px' : '210px'" class="aside">
      <div class="logo">
        <span v-if="!collapsed">进销存</span>
        <span v-else>ERP</span>
      </div>
      <el-menu :default-active="activeMenu" :collapse="collapsed" router class="menu">
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
          <el-button text :icon="collapsed ? undefined : undefined" @click="collapsed = !collapsed">
            {{ collapsed ? '»' : '«' }}
          </el-button>
          <span class="header-title">{{ currentTitle }}</span>
        </div>
        <div class="header-right">
          <span class="text-muted mr-8">
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
</style>
