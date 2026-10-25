import { expect, test } from './fixtures'
import { confirmBox, dataRow, fillNumber, visibleDialog } from './helpers/ui'
import { runTag } from './helpers/run'

/**
 * Batch B · B2 主数据字段 E2E。
 *
 * 三条硬验收：地址默认唯一、库位容量守恒核对、规格属性不影响 SKU 查询/矩阵。
 * 造数优先走 API（fixtures.api），被验收的交互全部走浏览器。
 */

test('E2E-11 客户地址：首条默认 → 第二条 → 切换默认，默认始终唯一', async ({
  page,
  api,
  run,
}) => {
  const tag = runTag(run, '11A')
  const customer = await api.createCustomer({
    code: `${tag}-CUS`,
    name: `${tag} 客户`,
    discountRate: 10000,
  })

  await page.goto('/customers')
  await dataRow(page, customer.code).getByRole('button', { name: '地址' }).click()

  const drawer = visibleDialog(page, `${customer.code} · 收货地址`)
  await expect(drawer.getByText('共 0 条')).toBeVisible()
  await drawer.getByRole('button', { name: '新建地址' }).click()

  let dialog = visibleDialog(page, '新建收货地址')
  await dialog.getByPlaceholder('如 HOME').fill('HOME')
  await dialog.getByPlaceholder('如 张三').fill('张三')
  await dialog.getByPlaceholder('如 13800000000').fill('13800000000')
  await dialog.getByPlaceholder('如 浙江省').fill('浙江省')
  await dialog.getByPlaceholder('如 杭州市').fill('杭州市')
  await dialog.getByPlaceholder('如 西湖区（可选）').fill('西湖区')
  await dialog.getByPlaceholder('如 文三路 1 号 2 幢 301').fill('文三路 1 号')
  await dialog.getByRole('button', { name: '保存' }).click()

  const homeRow = drawer.getByRole('row').filter({ hasText: 'HOME' }).first()
  await expect(homeRow).toContainText('默认')
  await expect(drawer.getByText('共 1 条')).toBeVisible()

  await drawer.getByRole('button', { name: '新建地址' }).click()
  dialog = visibleDialog(page, '新建收货地址')
  await dialog.getByPlaceholder('如 HOME').fill('OFFICE')
  await dialog.getByPlaceholder('如 张三').fill('李四')
  await dialog.getByPlaceholder('如 13800000000').fill('13900000000')
  await dialog.getByPlaceholder('如 浙江省').fill('浙江省')
  await dialog.getByPlaceholder('如 杭州市').fill('杭州市')
  await dialog.getByPlaceholder('如 西湖区（可选）').fill('滨江区')
  await dialog.getByPlaceholder('如 文三路 1 号 2 幢 301').fill('江南大道 2 号')
  await dialog.getByRole('button', { name: '保存' }).click()

  const officeRow = drawer.getByRole('row').filter({ hasText: 'OFFICE' }).first()
  await expect(officeRow.getByText('默认', { exact: true })).toHaveCount(0)
  await officeRow.getByRole('button', { name: '设为默认' }).click()

  await expect(officeRow.getByText('默认', { exact: true })).toBeVisible()
  await expect(homeRow.getByText('默认', { exact: true })).toHaveCount(0)

  const addresses = await api.listCustomerAddresses(customer.id)
  expect(addresses).toHaveLength(2)
  expect(addresses.filter((a) => a.isDefault)).toHaveLength(1)
  expect(addresses.find((a) => a.isDefault)?.addressKey).toBe('OFFICE')
})

test('E2E-12 仓库库位：40 + 60 = 仓库容量 100，核对一致；停用后报差额', async ({
  page,
  api,
  run,
}) => {
  const tag = runTag(run, '12')
  const warehouse = await api.createWarehouse({
    code: `${tag}-WH`,
    name: `${tag} 仓库`,
  })

  await page.goto('/warehouses')
  await dataRow(page, warehouse.code).getByRole('button', { name: '库位' }).click()

  const drawer = visibleDialog(page, `${warehouse.code} · 库位`)
  await expect(drawer.getByText('共 0 个库位')).toBeVisible()
  await expect(drawer.getByText('仓库容量：未设定')).toBeVisible()

  await drawer.getByRole('button', { name: '设置容量' }).click()
  let dialog = visibleDialog(page, '设置仓库容量')
  await fillNumber(dialog.getByRole('spinbutton'), 100)
  await dialog.getByRole('button', { name: '保存' }).click()
  await expect(drawer.getByText('仓库容量：100 件')).toBeVisible()

  for (const [code, capacity] of [
    ['A-01', 40],
    ['A-02', 60],
  ] as const) {
    await drawer.getByRole('button', { name: '新建库位' }).click()
    dialog = visibleDialog(page, '新建库位')
    await dialog.getByPlaceholder('如 A-01').fill(code)
    await dialog.getByPlaceholder('如 A 区 01').fill(`${code} 区`)
    await fillNumber(dialog.getByRole('spinbutton'), capacity)
    await dialog.getByRole('button', { name: '保存' }).click()
    await expect(drawer.getByRole('row').filter({ hasText: code }).first()).toBeVisible()
  }

  await expect(drawer.getByText('容量核对：一致')).toBeVisible()
  await expect(
    drawer.getByText(/仓库容量 100 \/ 启用库位合计 100 \/ 差额 0/),
  ).toBeVisible()

  // 超配被后端拒绝：第三个 1 件库位不能保存，守恒状态保持不变
  await drawer.getByRole('button', { name: '新建库位' }).click()
  dialog = visibleDialog(page, '新建库位')
  await dialog.getByPlaceholder('如 A-01').fill('A-03')
  await fillNumber(dialog.getByRole('spinbutton'), 1)
  await dialog.getByRole('button', { name: '保存' }).click()
  await expect(dialog.getByText(/超过仓库容量/)).toBeVisible()
  await dialog.getByRole('button', { name: '取消', exact: true }).click()
  await expect(drawer.getByText('容量核对：一致')).toBeVisible()

  // 停用 60 件库位 → 核对如实报 40/60 的差额
  const row60 = drawer.getByRole('row').filter({ hasText: 'A-02' }).first()
  await row60.getByRole('button', { name: '停用' }).click()
  await confirmBox(page, '停用库位', '停用')
  await expect(drawer.getByText('容量核对：不一致')).toBeVisible()
  await expect(
    drawer.getByText(/仓库容量 100 \/ 启用库位合计 40 \/ 差额 60/),
  ).toBeVisible()

  const reconcile = await api.reconcileWarehouseLocations(warehouse.id)
  expect(reconcile.warehouseCapacityQty).toBe(100)
  expect(reconcile.locationCapacityQty).toBe(40)
  expect(reconcile.consistent).toBe(false)
})

test('E2E-13 商品规格属性：保存后 SKU 列表与颜色筛选不受影响', async ({
  page,
  api,
  run,
}) => {
  const tag = runTag(run, '13')
  const product = await api.createProduct({
    code: `${tag}-P`,
    name: `${tag} 商品`,
  })
  for (const [color, size] of [
    ['白色', 'S'],
    ['白色', 'M'],
    ['黑色', 'S'],
    ['黑色', 'M'],
  ] as const) {
    await api.createSku({
      productId: product.id,
      color,
      size,
      retailPriceAmount: 12900,
    })
  }

  await page.goto('/products')
  const row = dataRow(page, product.code)
  await row.getByRole('button', { name: '规格属性' }).click()

  const dialog = visibleDialog(page, `${product.code} · 规格属性`)
  await expect(dialog.getByText('还没有规格属性，可添加面料、版型、执行标准等')).toBeVisible()
  await dialog.getByRole('button', { name: '添加属性' }).click()
  await dialog.getByPlaceholder('如 面料').fill('面料')
  await dialog.getByPlaceholder('如 纯棉').fill('纯棉')
  await dialog.getByRole('button', { name: '保存' }).click()
  await expect(page.getByText('规格属性已保存（全量替换）')).toBeVisible()
  await expect(dialog.getByPlaceholder('如 面料')).toHaveValue('面料')
  await expect(dialog.getByPlaceholder('如 纯棉')).toHaveValue('纯棉')
  // 精确匹配：el-dialog 右上角的关闭按钮 aria-label 是"关闭此对话框"，也会命中 '关闭'
  await dialog.getByRole('button', { name: '关闭', exact: true }).click()

  const saved = await api.listProductAttributes(product.id)
  expect(saved.items).toEqual([{ key: '面料', value: '纯棉' }])

  // 属性保存不改变 SKU 数量与颜色筛选
  await row.getByRole('button', { name: 'SKU 管理' }).click()
  const skuDrawer = visibleDialog(page, `${product.code} · ${product.name}`)
  await expect(skuDrawer.getByText('共 4 个 SKU')).toBeVisible()
  await skuDrawer.getByPlaceholder('按颜色筛选').fill('白色')
  await skuDrawer.getByRole('button', { name: '查询' }).click()
  await expect(skuDrawer.getByText('共 2 个 SKU')).toBeVisible()
})
