import { expect, test } from './fixtures'
import { runTag } from './helpers/run'
import { dataRow, visibleDialog } from './helpers/ui'

test('E2E-04 建商品 → SKU 矩阵生成 6 个', async ({ page, run }) => {
  const tag = runTag(run, '04')
  const code = `${tag}-P`
  const name = `${tag} 商品`

  await page.goto('/products')
  await page.getByRole('button', { name: '新建商品' }).click()

  const createDialog = visibleDialog(page, '新建商品')
  await createDialog.getByPlaceholder('如 P-TS-001').fill(code)
  await createDialog.getByPlaceholder('如 圆领T恤').fill(name)
  await createDialog.getByRole('button', { name: '保存' }).click()

  const productRow = dataRow(page, code)
  await expect(productRow).toBeVisible()
  await productRow.getByRole('button', { name: 'SKU 管理' }).click()

  const drawer = visibleDialog(page, `${code} · ${name}`)
  await expect(drawer.getByText('共 0 个 SKU')).toBeVisible()
  await drawer.getByRole('button', { name: '生成 SKU' }).first().click()

  const matrix = visibleDialog(page, '生成 SKU ·')
  const colorInput = matrix.getByPlaceholder('输入后回车').nth(0)
  for (const color of ['白色', '黑色']) {
    await colorInput.fill(color)
    await colorInput.press('Enter')
  }
  const sizeInput = matrix.getByPlaceholder('输入后回车').nth(1)
  for (const size of ['S', 'M', 'L']) {
    await sizeInput.fill(size)
    await sizeInput.press('Enter')
  }
  await matrix.getByPlaceholder('129.00').fill('129.00')

  await expect(matrix.getByText(/将生成\s*2\s*×\s*3\s*=\s*6\s*个 SKU/)).toBeVisible()
  await matrix.getByRole('button', { name: '生成', exact: true }).click()
  await expect(matrix.getByText(/本次新建 6 个，跳过 0 个已存在的组合/)).toBeVisible()

  await matrix.getByRole('button', { name: '关闭', exact: true }).click()
  await expect(matrix).toBeHidden()

  await expect(drawer.getByText('共 6 个 SKU')).toBeVisible()
  // 表头 1 行 + 6 个 SKU 数据行
  await expect(drawer.getByRole('row')).toHaveCount(7)
})

test('E2E-05 建供应商 / 客户 / 仓库', async ({ page, run }) => {
  const tag = runTag(run, '05')
  const supplierCode = `${tag}-SUP`
  const supplierName = `${tag} 供应商`
  const customerCode = `${tag}-CUS`
  const customerName = `${tag} 客户`
  const warehouseCode = `${tag}-WH`
  const warehouseName = `${tag} 仓库`

  // 供应商
  await page.goto('/suppliers')
  await page.getByRole('button', { name: '新建供应商' }).click()
  let dialog = visibleDialog(page, '新建供应商')
  await dialog.getByPlaceholder('如 SUP-001').fill(supplierCode)
  await dialog.getByPlaceholder('如 杭州面料有限公司').fill(supplierName)
  await dialog.getByRole('button', { name: '保存' }).click()
  await expect(dataRow(page, supplierCode)).toBeVisible()

  // 客户（折扣 9000 → 9 折）
  await page.goto('/customers')
  await page.getByRole('button', { name: '新建客户' }).click()
  dialog = visibleDialog(page, '新建客户')
  await dialog.getByPlaceholder('如 CUS-001').fill(customerCode)
  await dialog.getByPlaceholder('如 张女士').fill(customerName)
  await dialog.getByPlaceholder('10000').fill('9000')
  await dialog.getByRole('button', { name: '保存' }).click()
  const customerRow = dataRow(page, customerCode)
  await expect(customerRow).toBeVisible()
  await expect(customerRow.getByText('9 折', { exact: true })).toBeVisible()
  await expect(customerRow.getByText('（9000）')).toBeVisible()

  // 仓库
  await page.goto('/warehouses')
  await page.getByRole('button', { name: '新建仓库' }).click()
  dialog = visibleDialog(page, '新建仓库')
  await dialog.getByPlaceholder('如 WH-SH01').fill(warehouseCode)
  await dialog.getByPlaceholder('如 上海仓').fill(warehouseName)
  await dialog.getByRole('button', { name: '保存' }).click()
  await expect(dataRow(page, warehouseCode)).toBeVisible()
})
