import type { NavGroup } from './nav'

/**
 * 业务导航模块注册表。
 *
 * ★ 为什么用 import.meta.glob 而不是手写 import：
 *   以前新增页面要同时改 router/index.ts（加路由）和 MainLayout.vue（加菜单项），
 *   多人并行开发时这两个文件是固定冲突点。改成自动装载后，
 *   新增业务模块 = 新建 src/router/modules/xxx.ts，不改任何既有文件。
 *
 * ★ eager: true 是必要的：路由表要在 createRouter 之前就完整，
 *   侧边栏也要能同步过滤权限；用懒加载会变成异步，首屏路由匹配会漏。
 *   这里 eager 的只是「模块定义」，页面组件本身仍然由 routes 里的
 *   () => import(...) 懒加载，不会进首屏包。
 *
 * ★ 排序由各模块的 order 决定，不依赖文件名或文件系统顺序。
 */
const modules = import.meta.glob<{ default: NavGroup }>('./modules/*.ts', { eager: true })

export const navGroups: NavGroup[] = Object.values(modules)
  .map((mod) => mod?.default)
  .filter((group): group is NavGroup => Boolean(group && group.key))
  .sort((a, b) => a.order - b.order)
