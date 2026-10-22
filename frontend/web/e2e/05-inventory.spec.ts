import { expect, test } from './fixtures'
import { runTag } from './helpers/run'
import { seedBase } from './helpers/seed'

test('E2E-13 库存页三个数字正确（实存 / 预占 / 可用）', async ({ page, api, run }) => {
  const tag = runTag(run, '13')
  const seed = await seedBase(api, tag, { stockQty: 15 })

  // 建一张未出货的出货单，制造 4 件预占：15 / 4 / 11
  const order = await api.createSalesOrder({
    customerId: seed.customer.id,
    warehouseId: seed.warehouse.id,
    lines: [{ skuId: seed.skus[0].id, qty: 4, unitPriceAmount: 12900, discountRate: 9000 }],
  })
  expect(order.status).toBe('DRAFT')

  await page.goto('/inventory')

  const skuText = `${seed.product.name} · 白色-L`
  const row = page.getByRole('row').filter({ hasText: skuText }).first()
  await expect(row).toBeVisible()

  const cells = row.getByRole('cell')
  await expect(cells).toHaveCount(8)
  await expect(cells.nth(0)).toContainText(skuText)
  await expect(cells.nth(1)).toContainText(seed.warehouse.name)
  await expect(cells.nth(4)).toHaveText('15')
  await expect(cells.nth(5)).toHaveText('4')
  await expect(cells.nth(6)).toHaveText('11')
})
