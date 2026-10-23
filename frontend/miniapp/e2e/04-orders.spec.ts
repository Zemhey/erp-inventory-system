import { expect, test } from '@playwright/test'
import { readSeed } from './helpers/load'
import { shot } from './helpers/shot'

/**
 * 新页面冒烟：出货列表/详情/出货、进货列表/详情/入库。
 * 两处动作都要求：
 * - 按钮只在后端状态镜像允许时出现（APPROVED）；
 * - 点击后弹二次确认，经 api/client.ts 自动注入 X-Request-Id；
 * - 成功后状态变 COMPLETED，按钮消失。
 */
test('出货单：列表 → 详情 → 出货 → 已完成', async ({ page }) => {
  const seed = readSeed()

  await page.goto('/#/pages/sales/sales')
  await expect(page.locator('.chip').first()).toBeVisible()
  await page.locator('.chip', { hasText: '已审核' }).click()

  const card = page.locator(`.order-card[data-order-no="${seed.soAction.orderNo}"]`)
  await expect(card).toBeVisible()
  await expect(card).toContainText(seed.customer.name)
  await expect(card).toContainText(`已出 0/${seed.expected.soActionQty}`)
  await shot(page, 'sales-list-filtered')

  await card.click()
  await expect(page).toHaveURL(new RegExp(`pages/sales/detail\\?id=${seed.soAction.id}`))
  await expect(page.locator('.order-no')).toHaveText(seed.soAction.orderNo)
  await expect(page.locator('.status-tag')).toHaveText('已审核')

  const shipButton = page.locator('.action-btn')
  await expect(shipButton).toHaveText('确认出货')
  await shot(page, 'sales-detail-approved')
  await shipButton.click()
  await expect(page.locator('.uni-modal__btn_primary')).toBeVisible()
  await page.locator('.uni-modal__btn_primary').click()

  await expect(page.locator('.status-tag')).toHaveText('已完成')
  await expect(page.locator('.hero-value').nth(1)).toHaveText(String(seed.expected.soActionQty))
  await expect(page.locator('.action-btn')).toHaveCount(0)
  await expect(page.locator('.action-note')).toContainText('当前状态「已完成」不可出货')
  await shot(page, 'sales-detail-completed')
})

test('进货单：列表 → 详情 → 入库 → 已完成', async ({ page }) => {
  const seed = readSeed()

  await page.goto('/#/pages/purchase/purchase')
  await expect(page.locator('.chip').first()).toBeVisible()
  await page.locator('.chip', { hasText: '已审核' }).click()

  const card = page.locator(`.order-card[data-order-no="${seed.poReceive.orderNo}"]`)
  await expect(card).toBeVisible()
  await expect(card).toContainText(seed.supplier.name)
  await expect(card).toContainText(`已入 0/${seed.expected.poReceiveOpenQty}`)

  await card.click()
  await expect(page).toHaveURL(new RegExp(`pages/purchase/detail\\?id=${seed.poReceive.id}`))
  await expect(page.locator('.order-no')).toHaveText(seed.poReceive.orderNo)
  await expect(page.locator('.status-tag')).toHaveText('已审核')
  await expect(page.locator('.action-desc')).toContainText(
    `全部未入库数量（${seed.expected.poReceiveOpenQty} 件）`,
  )

  const receiveButton = page.locator('.action-btn')
  await expect(receiveButton).toHaveText('确认入库')
  await shot(page, 'purchase-detail-approved')
  await receiveButton.click()
  await expect(page.locator('.uni-modal__btn_primary')).toBeVisible()
  await page.locator('.uni-modal__btn_primary').click()

  await expect(page.locator('.status-tag')).toHaveText('已完成')
  await expect(page.locator('.hero-value').nth(1)).toHaveText(
    String(seed.expected.poReceiveOpenQty),
  )
  await expect(page.locator('.action-btn')).toHaveCount(0)
  await expect(page.locator('.action-note')).toContainText('当前状态「已完成」不可入库')
  await shot(page, 'purchase-detail-completed')
})