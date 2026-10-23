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
