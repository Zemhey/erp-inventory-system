import { expect, test } from '@playwright/test'
import { readSeed } from './helpers/load'
import { shot } from './helpers/shot'

/**
 * 三态冒烟：loading 由其余用例的首屏覆盖，这里补 empty 与 error。
 * error 用**真实后端 404**（不存在的单据 ID）而不是假数据 —— 消息来自后端，
 * 能同时验证"错误优先展示后端 message"这条纪律。
 */
test('错误态：不存在的出货单 ID 显示后端消息并可重试', async ({ page }) => {
  await page.goto('/#/pages/sales/detail?id=999999999')
  await expect(page.locator('.error-block')).toBeVisible()
  await expect(page.locator('.error-block')).toContainText('出货单不存在')
  await expect(page.locator('.error-block .btn-ghost')).toHaveText('重试')
  await shot(page, 'sales-detail-error')
})

test('空态：商品搜索与库存批次筛选无结果时给下一步提示', async ({ page }) => {
  const seed = readSeed()

  await page.goto('/#/pages/products/products')
  await page.locator('.search-input input').fill('E2B-绝对不存在的款号')
  await page.locator('.filter-actions').getByText('查询').click()
  await expect(page.locator('.empty-block')).toContainText('没有匹配的商品')
  await shot(page, 'products-empty')

  await page.goto('/#/pages/inventory/inventory')
  await page.locator('.filter-input input').fill(`${seed.batchStock}-NOPE`)
  await page.locator('.filter-actions').getByText('查询').click()
  await expect(page.locator('.empty-block')).toContainText('当前筛选没有匹配的库存行')
})
