import type { RouteRecordRaw } from 'vue-router'

/**
 * 导航模块的公共类型。
 *
 * 一个「导航模块」= 侧边栏里的一个分组 + 该分组下的菜单项 + 这些菜单项对应的路由。
 * 三者写在同一个文件里（src/router/modules/*.ts），避免路由表和侧边栏两处各维护一份、
 * 加页面时漏改其中一处。
 */
export interface NavItem {
  /** 与路由 path 完全一致，侧边栏高亮依赖它 */
  path: string
  title: string
  /** Element Plus 图标组件 */
  icon: unknown
  /** 所需权限码；与后端 @RequirePermission 同一套（mdm:read / pur:create ...） */
  permission?: string
}

export interface NavGroup {
  /** 分组唯一键，展开状态与激活态判断都用它 */
  key: string
  title: string
  icon: unknown
  /** 侧边栏排序，小的在上 */
  order: number
  /** 单入口组（工作台）直接渲染成菜单项，不套二级菜单 */
  flat?: boolean
  items: NavItem[]
  /** 挂在 MainLayout 下的子路由，path 写相对路径 */
  routes: RouteRecordRaw[]
}
