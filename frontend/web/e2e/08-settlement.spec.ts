import type { Locator } from '@playwright/test'
import { queryScalar } from './helpers/db'
import { asApiFailure } from './helpers/api'
import { expect, test } from './fixtures'
import { runTag } from './helpers/run'
import { completeReturn, seedBase, shipOut } from './helpers/seed'
import {
  confirmBox,
  dataRow,
  fillDate,
  fillNumber,
  formSelect,
  lineRow,
  selectOption,
  switchTab,
  visibleDialog,
  visiblePanel,
} from './helpers/ui'

/**
 * 结算与锁账（E2E-17 / E2E-18）。
 *
 * ★ 两条用例都只用**保留账期 2031**（计划书 §6 坑 9）：日清行按 (业务日期, 仓库)、
 *   账期行按 period，都不带 runId 前缀，只能靠"整年独占"来清理与复原基线。
 *   绝不锁真实当前月 2026-10 —— 那会连带拒掉同一轮里其它用例的建单。
 * ★ 选择器一律先 `[role="tabpanel"]:visible` 收窄（§5.5 S5b 第 ① 条）：
 *   el-tabs 三个 pane 同时在 DOM 里，只有一个月结/日清/对账的内容可见。
 */

/** 保留账期：本轮写死在这一天，日清与建单都挂它 */
const PERIOD_17 = '2031-01'
const DATE_17 = '2031-01-20'
const PERIOD_18 = '2031-02'
const DATE_18 = '2031-02-10'
const DATE_18_NEW = '2031-02-20'
/** 相邻未锁账期：用来证明"锁的是账期，不是全局" */
const DATE_OTHER = '2031-03-15'

/** 结果卡里的一个指标值（`.metric-label` + `.metric-value` 一对）。 */
function metric(card: Locator, label: string): Locator {
  return card.locator('.metric').filter({ hasText: label }).locator('.metric-value')
}

function totalsCard(panel: Locator, title: string): Locator {
  return panel.locator('.totals-card').filter({ hasText: title }).first()
}

/**
 * 保留账期独占性守卫：日清/月结的期间汇总是**跨仓库全月**算的，
 * 别的泳道若在 2031-01 留了单据，金额断言就会对不上。
 * 先把"只有本轮这三张单"查出来，失败时能说清是数据被污染而不是结算算错。
 */
function countDocsInPeriod(period: string): number {
  return Number(
    queryScalar(`
      SELECT (SELECT count(*) FROM purchase_orders WHERE biz_date BETWEEN DATE '${period}-01' AND DATE '${period}-31')
           + (SELECT count(*) FROM sales_orders     WHERE biz_date BETWEEN DATE '${period}-01' AND DATE '${period}-31')
           + (SELECT count(*) FROM return_orders    WHERE biz_date BETWEEN DATE '${period}-01' AND DATE '${period}-31')
    `),
  )
}

test('E2E-17 日清 → 月结 → 对账 → 报表页显示合计', async ({ page, api, run }) => {
  const tag = runTag(run, '17')
  // 备货 10 件 × ¥88 = 88000 分；出货 6 件 → round(6×12900×9000/10000)=69660；退货 2 件 → 23220
  const seed = await seedBase(api, tag, {
    stockQty: 10,
    stockPriceFen: 8800,
    stockBizDate: DATE_17,
  })
  const shipped = await shipOut(api, seed, { qty: 6, bizDate: DATE_17 })
  const returned = await completeReturn(api, shipped, {
    originLineId: shipped.lines[0].id,
    qty: 2,
    bizDate: DATE_17,
  })
  expect(returned.status).toBe('COMPLETED')

  // 保留账期独占：2031-01 里只有本轮的 1 进货 + 1 出货 + 1 退货
  expect(countDocsInPeriod(PERIOD_17), `保留账期 ${PERIOD_17} 应只有本轮 3 张单`).toBe(3)

  // ── 日清 ──
  await page.goto('/settlements')
  let panel = visiblePanel(page)
  await fillDate(panel.getByPlaceholder('业务日期'), DATE_17)
  await panel.getByRole('button', { name: '执行日清' }).click()

  await expect(panel.locator('.result-title')).toHaveText(`业务日期 ${DATE_17}`)
  const daily = totalsCard(panel, '全部仓库合计（totals）')
  await expect(metric(daily, '采购入库量')).toHaveText('10')
  await expect(metric(daily, '采购金额')).toHaveText('¥ 880.00')
  await expect(metric(daily, '销售出库量')).toHaveText('6')
  await expect(metric(daily, '销售金额')).toHaveText('¥ 696.60')
  await expect(metric(daily, '退货入库量')).toHaveText('2')
  await expect(metric(daily, '退货金额')).toHaveText('¥ 232.20')
  await expect(metric(daily, '净销售额')).toHaveText('¥ 464.40')

  // 同日重跑结果一致（幂等由 uq_daily_settlement 保证，不是应用层"先查再插"）
  await panel.getByRole('button', { name: '执行日清' }).click()
  await expect(metric(totalsCard(panel, '全部仓库合计（totals）'), '净销售额')).toHaveText('¥ 464.40')

  // ── 月结（锁账，不可逆）──
  await switchTab(page, '月结')
  panel = visiblePanel(page)
  await fillDate(panel.getByPlaceholder('账期（yyyy-MM）'), PERIOD_17)
  await panel.getByRole('button', { name: '执行月结（锁账）' }).click()
  // 文案是一整段，必须用子串/正则断（§5.5 S5b 第 ⑤ 条）
  await confirmBox(page, /锁账后该期间的单据不能新增\/执行\/作废，不可逆/, '确认锁账')

  panel = visiblePanel(page)
  await expect(panel.getByText('已锁账', { exact: true })).toBeVisible()
  const monthly = totalsCard(panel, '期间汇总')
  await expect(metric(monthly, '采购金额')).toHaveText('¥ 880.00')
  await expect(metric(monthly, '销售金额')).toHaveText('¥ 696.60')
  await expect(metric(monthly, '退货金额')).toHaveText('¥ 232.20')
  await expect(metric(monthly, '净销售额')).toHaveText('¥ 464.40')

  // ── 账期列表（set:read 只读接口）随月结刷新：2031-01 已锁账 + 期间净额 ──
  const periodRow = page
    .locator('.totals-card')
    .filter({ hasText: '账期列表' })
    .locator('tbody tr')
    .filter({ hasText: PERIOD_17 })
  await expect(periodRow).toContainText('已锁账')
  await expect(periodRow).toContainText('¥ 464.40')

  // ── 对账：总账 = 明细，差额 0 ──
  await switchTab(page, '对账')
  panel = visiblePanel(page)
  await fillDate(panel.getByPlaceholder('账期（yyyy-MM）'), PERIOD_17)
  await panel.getByRole('button', { name: '执行对账' }).click()
  await expect(panel.getByText('平衡', { exact: true })).toBeVisible()
  const recon = totalsCard(panel, '总账与明细')
  await expect(metric(recon, '总账（期间汇总）')).toHaveText('¥ 464.40')
  await expect(metric(recon, '明细（实时重算）')).toHaveText('¥ 464.40')
  await expect(metric(recon, '差额')).toHaveText('¥ 0.00')

  // 后端事实同步核对（页面显示的是分→元，这里断整数分）
  const reconciled = await api.reconcile(PERIOD_17)
  expect(reconciled).toMatchObject({ generalTotal: 46440, detailTotal: 46440, diff: 0, balanced: true })

  // ── 报表页显示合计（口径：单据 biz_date）──
  await page.goto('/reports')
  panel = visiblePanel(page)
  await fillDate(panel.getByPlaceholder('开始日期'), `${PERIOD_17}-01`)
  await fillDate(panel.getByPlaceholder('结束日期'), `${PERIOD_17}-31`)
  await panel.getByRole('button', { name: '查询' }).click()
  await expect(panel.locator('.totals-line')).toContainText('¥ 880.00')
  await expect(panel.locator('.totals-line')).toContainText('10 件')
  await expect(dataRow(page, seed.supplier.name)).toContainText('¥ 880.00')

  await switchTab(page, '出货统计')
  panel = visiblePanel(page)
  await fillDate(panel.getByPlaceholder('开始日期'), `${PERIOD_17}-01`)
  await fillDate(panel.getByPlaceholder('结束日期'), `${PERIOD_17}-31`)
  await panel.getByRole('button', { name: '查询' }).click()
  // 出货统计是**毛出货**，不扣退货：净额只在结算页看（§7 决策 5）
  await expect(panel.locator('.totals-line')).toContainText('¥ 696.60')
  await expect(panel.locator('.totals-line')).toContainText('6 件')
})

test('E2E-18 锁账后再建单/入库/日清都被拒，且没有副作用', async ({ page, api, run }) => {
  const tag = runTag(run, '18')
  const seed = await seedBase(api, tag)

  // 先造一张"已审核但还没入库"的单，业务日期落在待锁账期里
  let pending = await api.createPurchaseOrder({
    supplierId: seed.supplier.id,
    warehouseId: seed.warehouse.id,
    bizDate: DATE_18,
    lines: [{ skuId: seed.skus[0].id, qty: 10, unitPriceAmount: 8800 }],
  })
  pending = await api.submitPurchase(pending.id)
  pending = await api.approvePurchase(pending.id)
  expect(pending.status).toBe('APPROVED')

  // ── 月结锁账 2031-02 ──
  await page.goto('/settlements')
  await switchTab(page, '月结')
  let panel = visiblePanel(page)
  await fillDate(panel.getByPlaceholder('账期（yyyy-MM）'), PERIOD_18)
  await panel.getByRole('button', { name: '执行月结（锁账）' }).click()
  await confirmBox(page, /即将锁定的账期：2031-02/, '确认锁账')
  panel = visiblePanel(page)
  await expect(panel.getByText('已锁账', { exact: true })).toBeVisible()

  // ── 锁账后「入库」被拒：单据停在已审核，库存一件没进 ──
  await page.goto('/purchase-orders')
  const row = dataRow(page, pending.orderNo)
  await expect(row.getByText('已审核')).toBeVisible()
  await row.getByRole('button', { name: '入库' }).click()
  const receive = visibleDialog(page, '入库确认')
  await fillNumber(receive.getByRole('spinbutton').first(), 10)
  await receive.getByRole('button', { name: '确认入库' }).click()

  const lockedMessage = new RegExp(`账期 ${PERIOD_18} 已月结锁账.*${DATE_18}`)
  await expect(receive.getByRole('alert').getByText(lockedMessage)).toBeVisible()
  await receive.getByRole('button', { name: '取消' }).click()
  await expect(receive).toBeHidden()

  expect((await api.getPurchase(pending.id)).status).toBe('APPROVED')
  expect(await api.balanceOf(seed.skus[0].id, seed.warehouse.id)).toMatchObject({
    onHandQty: 0,
    reservedQty: 0,
    availableQty: 0,
  })

  // ── 锁账后「再建单」被拒：页面原样显示后端消息，库里没有该日期的单据 ──
  await page.getByRole('button', { name: '新建进货单' }).click()
  const create = visibleDialog(page, '新建进货单')
  await selectOption(page, formSelect(create, /供应商/), seed.supplier.name)
  await selectOption(page, formSelect(create, /入库仓库/), seed.warehouse.name)
  const line = lineRow(create, 0)
  await selectOption(page, line.getByRole('combobox'), `${seed.product.name} · 白色-L`)
  await fillNumber(line.getByRole('spinbutton').first(), 3)
  await create.getByPlaceholder('如 88.00').fill('88.00')
  await fillDate(create.getByPlaceholder('默认今天'), DATE_18_NEW)
  await create.getByRole('button', { name: '保存草稿' }).click()

  await expect(
    create.getByRole('alert').getByText(new RegExp(`账期 ${PERIOD_18} 已月结锁账.*${DATE_18_NEW}`)),
  ).toBeVisible()
  expect(queryScalar(`SELECT count(*) FROM purchase_orders WHERE biz_date = DATE '${DATE_18_NEW}'`)).toBe('0')

  await create.getByRole('button', { name: '取消' }).click()
  await expect(create).toBeHidden()

  // 错误码由后端定音：页面显示的是中文消息，这里断的是同一件事的代码标识
  const failure = await api
    .createPurchaseOrder({
      supplierId: seed.supplier.id,
      warehouseId: seed.warehouse.id,
      bizDate: DATE_18_NEW,
      lines: [{ skuId: seed.skus[0].id, qty: 1, unitPriceAmount: 8800 }],
    })
    .then(() => null, (error: unknown) => asApiFailure(error))
  expect(failure).toMatchObject({ status: 409, code: 'SET_PERIOD_CLOSED' })

  // ── 锁账后对该期间执行日清也被拒，且页面标题带错误码 ──
  await page.goto('/settlements')
  panel = visiblePanel(page)
  await fillDate(panel.getByPlaceholder('业务日期'), DATE_18)
  await panel.getByRole('button', { name: '执行日清' }).click()
  await expect(
    panel.getByRole('alert').getByText(
      `日清失败（SET_PERIOD_CLOSED）：账期 ${PERIOD_18} 已月结锁账，不能再执行 ${DATE_18} 的日清`,
    ),
  ).toBeVisible()

  // ── 锁的是账期不是全局：相邻未锁账期照常能建单 ──
  const other = await api.createPurchaseOrder({
    supplierId: seed.supplier.id,
    warehouseId: seed.warehouse.id,
    bizDate: DATE_OTHER,
    lines: [{ skuId: seed.skus[0].id, qty: 4, unitPriceAmount: 8800 }],
  })
  expect(other.status).toBe('DRAFT')
  expect(countDocsInPeriod('2031-03')).toBe(1)
})
