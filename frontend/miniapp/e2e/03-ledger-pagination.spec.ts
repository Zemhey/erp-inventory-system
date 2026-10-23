import { expect, test } from '@playwright/test'
import { readSeed } from './helpers/load'
import { shot } from './helpers/shot'

/**
 * 冒烟 3：流水分页。
 * 按本轮备货单号反查：共 25 条 → 首屏 20 条 → 加载更多 → 25 条，
 * 并断言 25 个 data-entry-id 无重复（分页不重不漏的不变量）。
 */
test('流水分页：单据号反查 25 条，加载更多后无重复', async ({ page }) => {
  const seed = readSeed()

  await page.goto('/#/pages/inventory/inventory')
  await page.locator('.tab', { hasText: '库存流水' }).click()

  // uni-app H5 的 placeholder 不是 input 属性，用 .filter-input 里的 input 定位
  const orderInput = page.locator('.filter-input input')
  await expect(orderInput).toBeVisible()
  await orderInput.fill(seed.poStock.orderNo)
  await page.locator('.filter-actions').getByText('查询').click()

  await expect(page.getByText(`共 ${seed.expected.stockLedgerRows} 条流水`)).toBeVisible()

  const cards = page.locator('.ledger-card')
  await expect(cards).toHaveCount(20)
  await expect(page.locator('.load-more')).toContainText('20 / 25')
  await shot(page, 'ledger-page1')

  await page.locator('.load-more').click()
  await expect(cards).toHaveCount(seed.expected.stockLedgerRows)
  await expect(page.getByText(`已全部加载，共 ${seed.expected.stockLedgerRows} 条`)).toBeVisible()
  await shot(page, 'ledger-page2')

  const ids = await cards.evaluateAll((nodes) =>
    nodes.map((node) => node.getAttribute('data-entry-id')),
  )
  expect(ids).toHaveLength(seed.expected.stockLedgerRows)
  expect(new Set(ids).size).toBe(seed.expected.stockLedgerRows)

  // 每条流水的单据号都是本轮备货单号
  await expect(cards.first()).toContainText(seed.poStock.orderNo)
})