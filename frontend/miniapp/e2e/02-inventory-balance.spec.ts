import { expect, test } from '@playwright/test'
import { readSeed } from './helpers/load'
import { shot } from './helpers/shot'

/**
 * 冒烟 2：库存余额（真实后端 + 自有 PG）。
 * 用本轮唯一批次号过滤，断言 25 行、每行三量 = 2/0/2、可用合计 50 件。
 * 三量只做展示断言 —— availableQty 由后端生成列保证，前端不重算。
 */
test('库存余额：批次过滤后 25 行、三量与合计正确', async ({ page }) => {
  const seed = readSeed()

  await page.goto('/#/pages/inventory/inventory')
  await expect(page.locator('.tab', { hasText: '库存余额' })).toBeVisible()

  // uni-app H5 的 placeholder 不是 input 属性，用 .filter-input 里的 input 定位
  await page.locator('.filter-input input').fill(seed.batchStock)
  await page.locator('.filter-actions').getByText('查询').click()

  const cards = page.locator('.balance-card')
  await expect(cards).toHaveCount(seed.expected.stockBalanceRows)
  await expect(page.locator('.summary')).toContainText(
    `当前筛选 ${seed.expected.stockBalanceRows} 行`,
  )
  await expect(page.locator('.summary-strong')).toHaveText(String(seed.expected.stockAvailable))

  const quantities = await cards.evaluateAll((nodes) =>
    nodes.map((node) =>
      Array.from(node.querySelectorAll('.qty-value')).map((el) => el.textContent?.trim() ?? ''),
    ),
  )
  expect(quantities).toHaveLength(seed.expected.stockBalanceRows)
  for (const triple of quantities) {
    expect(triple).toEqual(['2', '0', '2'])
  }

  // 每张卡片都显示本轮仓库名（名称字典来自 /warehouses）
  await expect(cards.first()).toContainText(seed.stockWarehouse.name)
  await shot(page, 'inventory-balance-filtered')
})