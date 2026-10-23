import type { Locator, Page } from '@playwright/test'
import type { ApiClient, ReturnOrderDto, SalesOrderDto } from './helpers/api'
import { expect, test } from './fixtures'
import { runTag } from './helpers/run'
import { seedBase, shipOut } from './helpers/seed'
import {
  confirmBox,
  dataRow,
  fillNumber,
  formSelect,
  lineRow,
  selectOption,
  visibleDialog,
} from './helpers/ui'

/** UI 建完退货单后轮询列表拿单据：列表刷新是异步的，不 sleep。 */
async function waitForReturn(api: ApiClient, originSalesOrderId: number): Promise<ReturnOrderDto> {
  let order: ReturnOrderDto | undefined
  await expect
    .poll(async () => {
      const data = await api.listReturns({ originSalesOrderId })
      order = data.items[0]
      return data.total
    })
    .toBe(1)
  return order!
}

/**
 * 在「新建退货单」弹窗里选中原出货单，并等到可退明细出现。
 *
 * 明细是选中后**另发一次请求**拉回来的（要读原单每行的 shippedQty / returnedQty），
 * 所以点完下拉不能立刻填数字。选项文案是「单号 · 客户名 · 可退 N 件」。
 */
async function pickOrigin(page: Page, dialog: Locator, order: SalesOrderDto, customerName: string) {
  await selectOption(page, formSelect(dialog, /原出货单/), `${order.orderNo} · ${customerName}`)
  await expect(dialog.getByRole('table').getByRole('row').nth(1)).toBeVisible()
}

/** 按批次取库存行（退货会造出新批次，所以不能只看第一行）。 */
function batchQty(rows: { batchNo: string; onHandQty: number }[], batchNo: string): number {
  return rows.find((r) => r.batchNo === batchNo)?.onHandQty ?? 0
}

test('E2E-15 出货 → 退货单 → 审核 → 退货入库 → 库存回补', async ({ page, api, run }) => {
  const tag = runTag(run, '15')
  const seed = await seedBase(api, tag, { stockQty: 10 })
  const shipped = await shipOut(api, seed, { qty: 6 })
  expect(shipped.status).toBe('COMPLETED')

  // 出货后只剩 4 件（备货走的是空批次）
  expect(await api.balanceOf(seed.skus[0].id, seed.warehouse.id)).toMatchObject({
    onHandQty: 4,
    reservedQty: 0,
    availableQty: 4,
  })

  await page.goto('/return-orders')
  await page.getByRole('button', { name: '新建退货单' }).click()
  const dialog = visibleDialog(page, '新建退货单')
  await pickOrigin(page, dialog, shipped, seed.customer.name)

  const line = lineRow(dialog, 0)
  await fillNumber(line.getByRole('spinbutton'), 2)
  await line.getByPlaceholder('可选').first().fill('RT-E2E15')
  // 预览金额 = round(2 × 12900 × 9000 / 10000) = 23220 分；单价与折扣在页面上只读
  await expect(dialog.getByText(/本次合计 2 件/)).toBeVisible()
  await expect(dialog.getByText('¥ 232.20')).toBeVisible()

  await dialog.getByRole('button', { name: '保存草稿' }).click()

  const returned = await waitForReturn(api, shipped.id)
  expect(returned.status).toBe('DRAFT')
  expect(returned.totalQty).toBe(2)
  expect(returned.totalAmount).toBe(23220)

  let row = dataRow(page, returned.orderNo)
  await expect(row.getByText('草稿')).toBeVisible()

  await row.getByRole('button', { name: '提交' }).click()
  row = dataRow(page, returned.orderNo)
  await expect(row.getByText('已提交')).toBeVisible()

  await row.getByRole('button', { name: '审核通过' }).click()
  row = dataRow(page, returned.orderNo)
  await expect(row.getByText('已审核')).toBeVisible()

  // 入库的二次确认必须写清后果：整单一次、写 RETURN_IN 流水、不可分批
  await row.getByRole('button', { name: '退货入库' }).click()
  await confirmBox(page, /RETURN_IN/, '退货入库')

  row = dataRow(page, returned.orderNo)
  await expect(row.getByText('已完成')).toBeVisible()

  const detail = await api.getReturn(returned.id)
  expect(detail.status).toBe('COMPLETED')
  expect(detail.lines[0]).toMatchObject({ qty: 2, batchNo: 'RT-E2E15', amount: 23220 })

  // 库存回补：原批次仍是 4（出货扣掉的那 6 件不会凭空回来），退货批次 +2，总量 6 = 10 − 6 + 2
  const rows = await api.inventory({ skuId: seed.skus[0].id, warehouseId: seed.warehouse.id })
  expect(batchQty(rows, '')).toBe(4)
  expect(batchQty(rows, 'RT-E2E15')).toBe(2)
  expect(rows.reduce((sum, r) => sum + r.onHandQty, 0)).toBe(6)

  const ledger = await api.ledger({ bizRefNo: detail.orderNo, skuId: seed.skus[0].id })
  expect(ledger.total).toBe(1)
  expect(ledger.items[0]).toMatchObject({ changeQty: 2, bizType: 'RETURN_IN', batchNo: 'RT-E2E15' })

  // 原出货行的累计已退数量跟着涨 —— 它就是下一次超退判定与日清退货金额的数据基础
  expect((await api.getSales(shipped.id)).lines[0].returnedQty).toBe(2)
})

test('E2E-16 超量退货被后端拒绝，页面逐字显示消息且不留单据', async ({ page, api, run }) => {
  const tag = runTag(run, '16')
  const seed = await seedBase(api, tag, { stockQty: 10 })
  const shipped = await shipOut(api, seed, { qty: 6 })

  await page.goto('/return-orders')
  await page.getByRole('button', { name: '新建退货单' }).click()
  const dialog = visibleDialog(page, '新建退货单')
  await pickOrigin(page, dialog, shipped, seed.customer.name)

  // 剩余可退只有 6，故意填 99：数量框刻意不设上限，判定权在后端
  const line = lineRow(dialog, 0)
  await fillNumber(line.getByRole('spinbutton'), 99)
  await expect(dialog.getByText(/第 1 行填写量超过剩余可退数量/)).toBeVisible()
  await expect(dialog.getByText(/仍可保存，是否超退由后端判定/)).toBeVisible()

  await dialog.getByRole('button', { name: '保存草稿' }).click()

  // 后端消息原样显示（含原单号、行号与"已出货 / 已退货 / 本次 / 最多还能退"四个数）
  const alert = dialog.getByRole('alert').filter({ hasText: /超出可退数量/ })
  await expect(alert).toBeVisible()
  await expect(alert.getByText(/已出货 6，已退货 0，本次 99，最多还能退 6/)).toBeVisible()
  await expect(alert.getByText(/整事务回滚/)).toBeVisible()
  await expect(dialog).toBeVisible()

  // 无副作用：退货单 0 张、库存仍是 4 件、原行已退数量仍是 0
  expect((await api.listReturns({ originSalesOrderId: shipped.id })).total).toBe(0)
  const rows = await api.inventory({ skuId: seed.skus[0].id, warehouseId: seed.warehouse.id })
  expect(rows).toHaveLength(1)
  expect(rows[0]).toMatchObject({ batchNo: '', onHandQty: 4, reservedQty: 0 })
  expect((await api.getSales(shipped.id)).lines[0].returnedQty).toBe(0)

  await dialog.getByRole('button', { name: '取消' }).click()
  await expect(dialog).toBeHidden()
})
