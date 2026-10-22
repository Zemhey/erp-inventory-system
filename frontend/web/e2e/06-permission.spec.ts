import { expect, test } from './fixtures'
import { runTag } from './helpers/run'
import { seedBase } from './helpers/seed'
import { dataRow, loginViaUi } from './helpers/ui'

// 这一条必须真正用 VIEWER 账号登录，不能复用 admin 的 storageState
test.use({ storageState: { cookies: [], origins: [] } })

test('E2E-14 权限：VIEWER 看不到任何写按钮', async ({ page, api, run }) => {
  const tag = runTag(run, '14')
  const seed = await seedBase(api, tag)
  const po = await api.createPurchaseOrder({
    supplierId: seed.supplier.id,
    warehouseId: seed.warehouse.id,
    lines: [{ skuId: seed.skus[0].id, qty: 2, unitPriceAmount: 8800 }],
  })

  await loginViaUi(
    page,
    run.viewerUsername,
    process.env.E2E_PASSWORD ?? 'Admin@12345',
  )
  await expect(page).toHaveURL(/\/dashboard$/)
  await expect(page.getByText('VIEWER', { exact: true })).toBeVisible()

  const permissions = await page.evaluate(
    () => JSON.parse(localStorage.getItem('erp.permissions') ?? '[]') as string[],
  )
  expect(permissions).toContain('mdm:read')
  expect(permissions).toContain('pur:read')
  expect(permissions).not.toContain('mdm:write')
  expect(permissions).not.toContain('pur:create')

  // 商品：可进 SKU 管理（读），但没有新建/编辑/停用
  await page.goto('/products')
  await expect(page.getByRole('button', { name: '新建商品' })).toHaveCount(0)
  const productRow = dataRow(page, seed.product.code)
  await expect(productRow.getByRole('button', { name: 'SKU 管理' })).toBeVisible()
  await expect(productRow.getByRole('button', { name: '编辑' })).toHaveCount(0)
  await expect(productRow.getByRole('button', { name: '停用' })).toHaveCount(0)

  // 供应商：操作列整体不渲染
  await page.goto('/suppliers')
  await expect(page.getByRole('button', { name: '新建供应商' })).toHaveCount(0)
  const supplierRow = dataRow(page, seed.supplier.code)
  await expect(supplierRow.getByRole('button', { name: '编辑' })).toHaveCount(0)
  await expect(supplierRow.getByRole('button', { name: '停用' })).toHaveCount(0)

  // 进货单：能看单据，但没有新建与状态动作
  await page.goto('/purchase-orders')
  await expect(page.getByRole('button', { name: '新建进货单' })).toHaveCount(0)
  const poRow = dataRow(page, po.orderNo)
  await expect(poRow.getByText('草稿')).toBeVisible()
  await expect(poRow.getByText('无可用动作')).toBeVisible()
  for (const action of ['提交', '作废', '审核通过']) {
    await expect(poRow.getByRole('button', { name: action })).toHaveCount(0)
  }

  // 出货单：没有新建入口
  await page.goto('/sales-orders')
  await expect(page.getByRole('button', { name: '新建出货单' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '新建第一张出货单' })).toHaveCount(0)

  await expect(page.getByRole('menuitem', { name: '进货单' })).toBeVisible()
})
