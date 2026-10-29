export type InventoryTab = 'balance' | 'ledger'

const PENDING_TAB_KEY = 'erp.inventory.pendingTab'

/**
 * switchTab 不能带 query，用一次性 storage 把"打开哪个标签"传给库存页。
 * 库存页在 onShow 里 consume 后立刻删除，避免下次进入仍停在上次请求的标签。
 */
export function openInventory(tab: InventoryTab = 'balance'): void {
  uni.setStorageSync(PENDING_TAB_KEY, tab)
  uni.switchTab({ url: '/pages/inventory/inventory' })
}

export function consumeInventoryTab(): InventoryTab | null {
  const raw = uni.getStorageSync(PENDING_TAB_KEY)
  if (raw !== 'balance' && raw !== 'ledger') return null
  uni.removeStorageSync(PENDING_TAB_KEY)
  return raw
}

const PENDING_BIZ_REF_KEY = 'erp.inventory.pendingBizRefNo'

/** 从盘点/收货页跳到库存流水并按单号过滤（同样走一次性 storage）。 */
export function openInventoryLedger(bizRefNo: string): void {
  uni.setStorageSync(PENDING_TAB_KEY, 'ledger')
  uni.setStorageSync(PENDING_BIZ_REF_KEY, bizRefNo)
  uni.switchTab({ url: '/pages/inventory/inventory' })
}

export function consumeInventoryBizRefNo(): string {
  const raw = uni.getStorageSync(PENDING_BIZ_REF_KEY)
  if (typeof raw !== 'string' || !raw) return ''
  uni.removeStorageSync(PENDING_BIZ_REF_KEY)
  return raw
}

/* ── 自定义底栏 ────────────────────────────────────────────────────────────
   微信原生 tabBar 有两个硬限制：最多 5 个、高度和字号改不了。
   要把「我的」放进导航栏、又要底栏做得大一点，只能自己画一条。
   但 pages.json 里的 tabBar 声明**保留**：它是 switchTab 的前提，
   而 switchTab 决定标签页切回去时不重建（列表滚动位置和已加载数据都还在）。
   所以策略是：声明照旧、进页面时用 hideNativeTabBar() 把原生那条藏起来。
   ──────────────────────────────────────────────────────────────────────── */

export interface TabItem {
  key: string
  label: string
  path: string
  /** true = 原生 tabBar 页，走 switchTab（保活）；false = 普通页，走 navigateTo */
  isTab: boolean
}

export const TAB_ITEMS: TabItem[] = [
  { key: 'dashboard', label: '工作台', path: '/pages/dashboard/dashboard', isTab: true },
  { key: 'inventory', label: '库存', path: '/pages/inventory/inventory', isTab: true },
  { key: 'sales', label: '出货', path: '/pages/sales/sales', isTab: true },
  { key: 'purchase', label: '进货', path: '/pages/purchase/purchase', isTab: true },
  { key: 'products', label: '商品', path: '/pages/products/products', isTab: true },
  { key: 'me', label: '我的', path: '/pages/me/me', isTab: false },
]

/** 点底栏某一项：已经在这一页就不动，避免 switchTab 把自己重载一遍。 */
export function goTab(item: TabItem, currentKey: string): void {
  if (item.key === currentKey) return
  if (item.isTab) {
    uni.switchTab({ url: item.path })
    return
  }
  uni.navigateTo({ url: item.path })
}

/**
 * 藏掉原生那条 tabBar。
 *
 * ★ 必须在页面的 onShow 里调，不能只在组件 mounted 调一次：
 *   原生标签栏是窗口级组件，switchTab 切回来时微信会把它重新显示出来，
 *   只在首次挂载时藏一次的话，切一圈回来就变成"上下两条栏"。
 */
export function hideNativeTabBar(): void {
  const api = uni as unknown as Record<string, unknown>
  if (typeof api.hideTabBar !== 'function') return
  try {
    uni.hideTabBar({ animation: false, fail: () => undefined })
  } catch {
    // 某些端在非标签页会同步抛错，藏栏这件事不值得把页面的 onShow 带崩
  }
}
