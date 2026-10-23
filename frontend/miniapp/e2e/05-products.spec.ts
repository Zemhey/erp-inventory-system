import { expect, test } from '@playwright/test'
import { readSeed } from './helpers/load'
import { shot } from './helpers/shot'

/**
 * 商品 / SKU 只读冒烟：
 * 商品 keyword 搜索 → 详情 25 个 SKU → SKU 详情；
 * SKU 页用本轮唯一 barcode 前缀做本地过滤，验证只读搜索可用。
 */
test('商品/SKU 只读：搜索、详情、SKU 本地过滤', async ({ page }) => {
  const seed = readSeed()

  await page.goto('/#/pages/products/products')
  // uni-app H5 的 placeholder 不是 input 属性
  await page.locator('.search-input input').fill(seed.product.code)
  await page.locator('.filter-actions').getByText('查询').click()

  const productCard = page.locator('.item-card', { hasText: seed.product.name })
  await expect(productCard).toBeVisible()
  await productCard.click()

  await expect(page).toHaveURL(new RegExp(`pages/products/product-detail\\?id=${seed.product.id}`))
  await expect(page.locator('.product-name')).toHaveText(seed.product.name)
  await expect(page.getByText(`款号 ${seed.product.code}`)).toBeVisible()

  const skuCards = page.locator('.sku-card')
  await expect(skuCards).toHaveCount(seed.expected.productSkuCount)
  await shot(page, 'product-detail')

  await skuCards.first().click()
  await expect(page).toHaveURL(/pages\/products\/sku-detail/)
  await expect(page.getByText('零售价')).toBeVisible()
  await expect(page.locator('.price-value')).toHaveText('¥199.00')
  await expect(page.getByText(seed.runId, { exact: false }).first()).toBeVisible()
  await shot(page, 'sku-detail')

  await page.goto('/#/pages/products/products')
  await page.locator('.tab', { hasText: 'SKU' }).click()
  await page.locator('.search-input input').fill(seed.runId)

  await expect(page.getByText(`匹配 ${seed.expected.productSkuCount} 条`)).toBeVisible()
  const filtered = page.locator('.item-card')
  await expect(filtered).toHaveCount(seed.expected.productSkuCount)
  await expect(filtered.first()).toContainText(seed.runId)
  await shot(page, 'sku-filtered')
})