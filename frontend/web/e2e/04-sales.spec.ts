import type { ApiClient, SalesOrderDto } from './helpers/api'
import { queryScalar } from './helpers/db'
import { expect, test } from './fixtures'
import { runTag } from './helpers/run'
import { seedBase } from './helpers/seed'
import { confirmBox, dataRow, fillNumber, formSelect, lineRow, selectOption, visibleDialog } from './helpers/ui'

async function waitForSales(api: ApiClient, customerId: number): Promise<SalesOrderDto> {
  let order: SalesOrderDto | undefined
  await expect
    .poll(async () => {
      const data = await api.listSales({ customerId })
      order = data.items[0]
      return data.total
    })
    .toBe(1)
  return order!
}

test('E2E-09 建出货单（预占成功）→ 可用量减少', async ({ page, api, run }) => {
  const tag = runTag(run, '09')
  const seed = await seedBase(api, tag, { stockQty: 20 })

  await page.goto('/sales-orders')
  await page.getByRole('button', { name: '新建出货单' }).click()
  const dialog = visibleDialog(page, '新建出货单')

  await selectOption(page, formSelect(dialog, /客户/), seed.customer.name)
  await selectOption(page, formSelect(dialog, /出库仓库/), seed.warehouse.name)
  const line = lineRow(dialog, 0)
  await selectOption(page, line.getByRole('combobox'), `${seed.product.name} · 白色-L`)
  await fillNumber(line.getByRole('spinbutton').nth(0), 8)

  // 选 SKU 带出零售价；客户折扣 9000 自动套用
  await expect(dialog.getByPlaceholder('如 129.00')).toHaveValue('129.00')
  await expect(line.getByRole('spinbutton').nth(1)).toHaveValue('9000')
  await expect(dialog.getByText(/合计 8 件 ·/)).toBeVisible()
  await expect(dialog.getByText('¥ 928.80')).toBeVisible()

  await dialog.getByRole('button', { name: '保存并预占' }).click()

  const order = await waitForSales(api, seed.customer.id)
  expect(order.status).toBe('DRAFT')
  expect(order.totalQty).toBe(8)
  expect(order.totalAmount).toBe(92880)

  const row = dataRow(page, order.orderNo)
  await expect(row.getByText('草稿')).toBeVisible()
  await expect(row.getByText(/已出 0\/8 · 未出 8/)).toBeVisible()

  const balance = await api.balanceOf(seed.skus[0].id, seed.warehouse.id)
  expect(balance).toMatchObject({ onHandQty: 20, reservedQty: 8, availableQty: 12 })
})

test('E2E-10 库存不足 → 整单失败，库存与预占都不变', async ({ page, api, run }) => {
  const tag = runTag(run, '10')
  const seed = await seedBase(api, tag, { skuCount: 2, stockQty: 5 })
  const [first, second] = seed.skus

  await page.goto('/sales-orders')
  await page.getByRole('button', { name: '新建出货单' }).click()
  const dialog = visibleDialog(page, '新建出货单')

  await selectOption(page, formSelect(dialog, /客户/), seed.customer.name)
  await selectOption(page, formSelect(dialog, /出库仓库/), seed.warehouse.name)

  // 第 1 行：有库存，数量 3（合法）
  let line = lineRow(dialog, 0)
  await selectOption(page, line.getByRole('combobox'), `${seed.product.name} · 白色-L`)
  await fillNumber(line.getByRole('spinbutton').nth(0), 3)

  // 第 2 行：无库存，数量 1（应导致整单回滚）
  await dialog.getByRole('button', { name: '添加一行' }).click()
  line = lineRow(dialog, 1)
  await selectOption(page, line.getByRole('combobox'), `${seed.product.name} · 黑色-L`)
  await fillNumber(line.getByRole('spinbutton').nth(0), 1)

  await dialog.getByRole('button', { name: '保存并预占' }).click()

  await expect(dialog.getByText(/库存不足/)).toBeVisible()
  await expect(dialog.getByText(/辅助定位：本单第 2 行/)).toBeVisible()
  await expect(
    dialog.getByText(new RegExp(`辅助定位：本单第 2 行.*${seed.product.name} · 黑色-L`)),
  ).toBeVisible()
  await expect(dialog).toBeVisible()

  expect((await api.listSales({ customerId: seed.customer.id })).total).toBe(0)
  expect(await api.balanceOf(first.id, seed.warehouse.id)).toMatchObject({
    onHandQty: 5,
    reservedQty: 0,
    availableQty: 5,
  })
  expect(await api.balanceOf(second.id, seed.warehouse.id)).toMatchObject({
    onHandQty: 0,
    reservedQty: 0,
    availableQty: 0,
  })
})

test('E2E-11 出货确认 → 实存扣减，状态已完成', async ({ page, api, run }) => {
  const tag = runTag(run, '11')
  const seed = await seedBase(api, tag, { stockQty: 20 })

  let order = await api.createSalesOrder({
    customerId: seed.customer.id,
    warehouseId: seed.warehouse.id,
    lines: [
      { skuId: seed.skus[0].id, qty: 6, unitPriceAmount: 12900, discountRate: 9000 },
    ],
  })
  order = await api.submitSales(order.id)
  order = await api.approveSales(order.id)

  await page.goto('/sales-orders')
  const row = dataRow(page, order.orderNo)
  await expect(row.getByText('已审核')).toBeVisible()

  await row.getByRole('button', { name: '出货' }).click()
  await confirmBox(page, /按 FEFO 拆批次/, '出货')

  await expect(row.getByText('已完成')).toBeVisible()
  await expect(row.getByText(/已出 6\/6 · 未出 0/)).toBeVisible()

  expect(await api.balanceOf(seed.skus[0].id, seed.warehouse.id)).toMatchObject({
    onHandQty: 14,
    reservedQty: 0,
    availableQty: 14,
  })

  const ledger = await api.ledger({ bizRefNo: order.orderNo, skuId: seed.skus[0].id })
  expect(ledger.total).toBe(1)
  expect(ledger.items[0]).toMatchObject({ changeQty: -6, bizType: 'SALES_OUT' })
})

test('E2E-12 作废未出货单据 → 预占释放', async ({ page, api, run }) => {
  const tag = runTag(run, '12')
  const seed = await seedBase(api, tag, { stockQty: 20 })

  const order = await api.createSalesOrder({
    customerId: seed.customer.id,
    warehouseId: seed.warehouse.id,
    lines: [
      { skuId: seed.skus[0].id, qty: 5, unitPriceAmount: 12900, discountRate: 9000 },
    ],
  })
  expect(order.status).toBe('DRAFT')
  expect(await api.balanceOf(seed.skus[0].id, seed.warehouse.id)).toMatchObject({
    onHandQty: 20,
    reservedQty: 5,
    availableQty: 15,
  })

  await page.goto('/sales-orders')
  const row = dataRow(page, order.orderNo)
  await expect(row.getByText('草稿')).toBeVisible()

  await row.getByRole('button', { name: '作废' }).click()
  await confirmBox(page, /释放本单占用的库存（预占）/, '作废')

  await expect(row.getByText('已作废')).toBeVisible()
  expect(await api.balanceOf(seed.skus[0].id, seed.warehouse.id)).toMatchObject({
    onHandQty: 20,
    reservedQty: 0,
    availableQty: 20,
  })

  const reservationIds = order.lines.flatMap((line) => line.reservationIds ?? [])
  expect(reservationIds.length).toBeGreaterThan(0)
  const statuses = queryScalar(
    `SELECT string_agg(status, ',') FROM inventory_reservations WHERE id IN (${reservationIds.join(',')})`,
  )
  expect(statuses).toBeTruthy()
  expect(statuses.split(',')).toEqual(reservationIds.map(() => 'RELEASED'))
})
