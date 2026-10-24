import { expect, test, type Page } from '@playwright/test'
import { loginAs } from './helpers/identity'
import { readSeed } from './helpers/load'
import { shot } from './helpers/shot'

/**
 * 发货扫码作业（FR-SAL-06）。
 *
 * 三条用例是一条**连续作业链**，中间夹着两个负向断言：
 * - TC-MOB-005：生成计划 + 扫错条码被后端逐字拒 + 扫对累加；
 * - TC-MOB-006：只读身份看同一张在拣的发货单，扫码与确认控件一个都没有；
 * - TC-MOB-007：作业人回来扫完剩下的行 → 拣货完成 → 确认发货。
 *
 * ★ 本用例跑在 H5 上，而 H5 没有摄像头 —— 页面必须自动落到「手录条码」分支，
 *   这正是扫码降级要验证的性质：降级不是让页面在开发工具里变成砖。
 *   条码是否有效仍由后端判定，前端不放宽。
 */

/** 三张用例共享的发货单 id：作业链跨用例推进，不重复造单。 */
let shipmentId = 0

const modalConfirm = (page: Page) => page.locator('.uni-modal__btn_primary')
const barcodeInput = (page: Page) => page.locator('.scan-barcode-input input')
const qtyInput = (page: Page) => page.locator('.scan-qty-input input')
const lineCards = (page: Page) => page.locator('.line-card')

/**
 * 从行卡片上取该行的条码与行 id，避免把「第 1 行是哪个 SKU」写死进用例。
 *
 * ★ 条码来自主数据字典（异步加载），页面初绘那一拍 data-barcode 还是 'none'。
 *   先等它变成真条码再取值，否则用例拿到 'none' 当条码去扫，失败原因会让人一头雾水。
 */
async function lineFacts(page: Page, index: number): Promise<{ lineId: string; barcode: string }> {
  const card = lineCards(page).nth(index)
  await expect(card).not.toHaveAttribute('data-barcode', 'none', { timeout: 15_000 })
  return {
    lineId: String(await card.getAttribute('data-line-id')),
    barcode: String(await card.getAttribute('data-barcode')),
  }
}

async function scan(page: Page, barcode: string, qty: string): Promise<void> {
  await barcodeInput(page).fill(barcode)
  await qtyInput(page).fill(qty)
  await page.locator('.btn-scan-submit').click()
}

/**
 * 从当前地址里取 ?id=。
 * ★ uni-app H5 是 **hash 路由**：地址长这样 `/#/pages/shipments/detail?id=12`，
 *   query 挂在 hash 后面，`new URL(url).searchParams` 读到的是空的 —— 必须直接匹配。
 */
function currentDocId(page: Page): number {
  const match = page.url().match(/[?&]id=(\d+)/)
  return match ? Number(match[1]) : 0
}

test('TC-MOB-005 发货作业：生成计划、扫错条码被拒、扫对才累加', async ({ page }) => {
  const seed = readSeed()

  await page.goto(`/#/pages/sales/detail?id=${seed.soPick.id}`)
  await expect(page.locator('.order-no')).toHaveText(seed.soPick.orderNo)
  const planButton = page.locator('[data-action="create-shipment-plan"]')
  await expect(planButton).toBeVisible()
  await planButton.click()
  await modalConfirm(page).click()

  // 单仓足够时只拆出一张，页面直接把它送到扫码页
  await page.waitForURL(/pages\/shipments\/detail\?id=\d+/)
  shipmentId = currentDocId(page)
  expect(shipmentId, '应从跳转地址里拿到发货单 id').toBeGreaterThan(0)

  await expect(page.locator('.status-tag')).toHaveText('待拣货')
  // ★ H5 降级：扫码卡必须已经处在手录模式，而不是一个点了没反应的摄像头按钮
  await expect(page.locator('.scan-card')).toHaveAttribute('data-mode', 'manual')
  await expect(page.locator('.manual-note-text')).toContainText('H5 浏览器不能调起摄像头')
  await expect(page.locator('.pick-text')).toHaveText(`已扫 0/${seed.expected.soPickQty}`)
  await shot(page, 'shipment-detail-manual-mode')

  const first = await lineFacts(page, 0)
  const second = await lineFacts(page, 1)
  expect(first.barcode).not.toBe('none')
  expect(second.barcode).not.toBe('none')

  // 拿第二行的条码去扫第一行：后端逐字比对，必须原样报出「条码不匹配」
  await scan(page, second.barcode, '2')
  await expect(page.locator('.scan-error')).toBeVisible()
  await expect(page.locator('.scan-error')).toContainText('条码不匹配')
  await expect(page.locator('.pick-text')).toHaveText(`已扫 0/${seed.expected.soPickQty}`)
  await shot(page, 'shipment-scan-barcode-mismatch')

  await scan(page, first.barcode, '2')
  await expect(page.locator('.scan-error')).toHaveCount(0)
  await expect(page.locator('.pick-text')).toHaveText(`已扫 2/${seed.expected.soPickQty}`)
  // 扫满一行后焦点自动落到下一张还差量的行
  await expect(page.locator('.line-focused')).toHaveAttribute('data-line-id', second.lineId)
  await expect(page.locator('.status-tag')).toHaveText('拣货中')
  await shot(page, 'shipment-scan-picking')
})

test('TC-MOB-006 发货作业：只读身份看不到扫码与确认控件', async ({ page }) => {
  const seed = readSeed()

  await loginAs(page, seed.viewer.username, seed.viewer.password)

  await page.goto(`/#/pages/shipments/detail?id=${shipmentId}`)
  await expect(page.locator('.status-tag')).toHaveText('拣货中')
  // 有 sal:read 才看得到进度，但没有 sal:pick / sal:ship：控件必须整套收起
  await expect(page.locator('.scan-disabled')).toContainText('没有 sal:pick 权限')
  await expect(page.locator('.btn-scan-submit')).toHaveCount(0)
  await expect(page.locator('.scan-barcode-input')).toHaveCount(0)
  await expect(page.locator('.action-btn')).toHaveCount(0)
  await shot(page, 'shipment-detail-viewer')
})

test('TC-MOB-007 发货作业：扫满全部行后确认发货，状态推进到已发货', async ({ page }) => {
  const seed = readSeed()

  await page.goto(`/#/pages/shipments/detail?id=${shipmentId}`)
  await expect(page.locator('.scan-card')).toHaveAttribute('data-mode', 'manual')

  const remaining = await lineFacts(page, 1)
  // 焦点停在还差量的那一行，数量默认给「本行还需扫」
  await expect(page.locator('.line-focused')).toHaveAttribute('data-line-id', remaining.lineId)
  await expect(qtyInput(page)).toHaveValue('2')

  await barcodeInput(page).fill(remaining.barcode)
  await page.locator('.btn-scan-submit').click()

  await expect(page.locator('.pick-text')).toHaveText(
    `已扫 ${seed.expected.soPickQty}/${seed.expected.soPickQty}`,
  )
  await expect(page.locator('.status-tag')).toHaveText('拣货完成')
  // 未扫满时不给确认按钮；扫满才出现
  const confirmButton = page.locator('.action-btn')
  await expect(confirmButton).toHaveText('确认发货')
  await shot(page, 'shipment-detail-picked')

  await confirmButton.click()
  await modalConfirm(page).click()

  await expect(page.locator('.status-tag')).toHaveText('已发货')
  await expect(page.locator('.action-btn')).toHaveCount(0)
  await expect(page.locator('.scan-disabled')).toContainText('当前状态「已发货」不可继续扫码')
  await shot(page, 'shipment-detail-completed')
})
