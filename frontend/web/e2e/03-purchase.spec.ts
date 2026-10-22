import type { ApiClient, PurchaseOrderDto } from './helpers/api'
import { expect, test } from './fixtures'
import { runTag } from './helpers/run'
import { seedBase } from './helpers/seed'
import { dataRow, fillNumber, formSelect, lineRow, selectOption, visibleDialog } from './helpers/ui'

async function waitForOrder(api: ApiClient, supplierId: number): Promise<PurchaseOrderDto> {
  let order: PurchaseOrderDto | undefined
  await expect
    .poll(async () => {
      const data = await api.listPurchases({ supplierId })
      order = data.items[0]
      return data.total
    })
    .toBe(1)
  return order!
}

test('E2E-06 建进货单 → 提交 → 审核 → 入库 → 已完成', async ({ page, api, run }) => {
  const tag = runTag(run, '06')
  const seed = await seedBase(api, tag)

  await page.goto('/purchase-orders')
  await page.getByRole('button', { name: '新建进货单' }).click()
  const dialog = visibleDialog(page, '新建进货单')

  await selectOption(page, formSelect(dialog, /供应商/), seed.supplier.name)
  await selectOption(page, formSelect(dialog, /入库仓库/), seed.warehouse.name)
  const line = lineRow(dialog, 0)
  await selectOption(page, line.getByRole('combobox'), `${seed.product.name} · 白色-L`)
  await fillNumber(line.getByRole('spinbutton').first(), 10)
  await dialog.getByPlaceholder('如 88.00').fill('88.00')
  await expect(dialog.getByText(/合计 10 件 ·/)).toBeVisible()
  await expect(dialog.getByText('¥ 880.00')).toBeVisible()

  await dialog.getByRole('button', { name: '保存草稿' }).click()

  const order = await waitForOrder(api, seed.supplier.id)
  expect(order.status).toBe('DRAFT')
  expect(order.totalQty).toBe(10)
  expect(order.totalAmount).toBe(88000)

  let row = dataRow(page, order.orderNo)
  await expect(row.getByText('草稿')).toBeVisible()

  await row.getByRole('button', { name: '提交' }).click()
  await expect(row.getByText('已提交')).toBeVisible()

  await row.getByRole('button', { name: '审核通过' }).click()
  await expect(row.getByText('已审核')).toBeVisible()

  await row.getByRole('button', { name: '入库' }).click()
  const receive = visibleDialog(page, '入库确认')
  await fillNumber(receive.getByRole('spinbutton').first(), 10)
  await receive.getByRole('button', { name: '确认入库' }).click()
  await expect(receive).toBeHidden()

  row = dataRow(page, order.orderNo)
  await expect(row.getByText('已完成')).toBeVisible()
  await expect(row.getByText(/已入 10\/10 · 未入 0/)).toBeVisible()

  const balance = await api.balanceOf(seed.skus[0].id, seed.warehouse.id)
  expect(balance).toMatchObject({ onHandQty: 10, reservedQty: 0, availableQty: 10 })
  expect((await api.getPurchase(order.id)).status).toBe('COMPLETED')
})

test('E2E-07 分批入库：先 4 再 6', async ({ page, api, run }) => {
  const tag = runTag(run, '07')
  const seed = await seedBase(api, tag)

  let order = await api.createPurchaseOrder({
    supplierId: seed.supplier.id,
    warehouseId: seed.warehouse.id,
    lines: [{ skuId: seed.skus[0].id, qty: 10, unitPriceAmount: 8800 }],
  })
  order = await api.submitPurchase(order.id)
  order = await api.approvePurchase(order.id)

  await page.goto('/purchase-orders')
  let row = dataRow(page, order.orderNo)
  await expect(row.getByText('已审核')).toBeVisible()

  await row.getByRole('button', { name: '入库' }).click()
  let receive = visibleDialog(page, '入库确认')
  await fillNumber(receive.getByRole('spinbutton').first(), 4)
  await receive.getByRole('button', { name: '确认入库' }).click()
  await expect(receive).toBeHidden()

  row = dataRow(page, order.orderNo)
  await expect(row.getByText('部分执行')).toBeVisible()
  await expect(row.getByText(/已入 4\/10 · 未入 6/)).toBeVisible()
  expect((await api.balanceOf(seed.skus[0].id, seed.warehouse.id)).onHandQty).toBe(4)

  await row.getByRole('button', { name: '入库' }).click()
  receive = visibleDialog(page, '入库确认')
  await fillNumber(receive.getByRole('spinbutton').first(), 6)
  await receive.getByRole('button', { name: '确认入库' }).click()
  await expect(receive).toBeHidden()

  row = dataRow(page, order.orderNo)
  await expect(row.getByText('已完成')).toBeVisible()
  await expect(row.getByText(/已入 10\/10 · 未入 0/)).toBeVisible()
  expect((await api.balanceOf(seed.skus[0].id, seed.warehouse.id)).onHandQty).toBe(10)
})

test('E2E-08 超量入库被拒，库存与单据都不变', async ({ page, api, run }) => {
  const tag = runTag(run, '08')
  const seed = await seedBase(api, tag)

  let order = await api.createPurchaseOrder({
    supplierId: seed.supplier.id,
    warehouseId: seed.warehouse.id,
    lines: [{ skuId: seed.skus[0].id, qty: 10, unitPriceAmount: 8800 }],
  })
  order = await api.submitPurchase(order.id)
  order = await api.approvePurchase(order.id)
  order = await api.receivePurchase(order.id, [{ lineId: order.lines[0].id, qty: 4 }])
  expect(order.status).toBe('PARTIAL')

  await page.goto('/purchase-orders')
  const row = dataRow(page, order.orderNo)
  await expect(row.getByText('部分执行')).toBeVisible()
  await expect(row.getByText(/已入 4\/10 · 未入 6/)).toBeVisible()

  await row.getByRole('button', { name: '入库' }).click()
  const receive = visibleDialog(page, '入库确认')
  await fillNumber(receive.getByRole('spinbutton').first(), 10)

  await expect(receive.getByText(/第 1 行填写量超过未入库数量/)).toBeVisible()
  await expect(receive.getByText(/仍可提交，是否超量由后端判定/)).toBeVisible()

  await receive.getByRole('button', { name: '确认入库' }).click()
  await expect(receive.getByText(/超量入库/)).toBeVisible()
  await expect(receive.getByText(/本次 10/)).toBeVisible()
  await expect(receive.getByText(/超出 4/)).toBeVisible()
  await expect(receive).toBeVisible()

  expect((await api.balanceOf(seed.skus[0].id, seed.warehouse.id)).onHandQty).toBe(4)
  expect((await api.getPurchase(order.id)).status).toBe('PARTIAL')

  await receive.getByRole('button', { name: '取消' }).click()
  await expect(receive).toBeHidden()
  await expect(dataRow(page, order.orderNo).getByText(/已入 4\/10 · 未入 6/)).toBeVisible()
})
