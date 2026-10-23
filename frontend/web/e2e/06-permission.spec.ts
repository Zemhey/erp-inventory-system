import { ApiClient, asApiFailure } from './helpers/api'
import { expect, test } from './fixtures'
import { runTag } from './helpers/run'
import { seedBase } from './helpers/seed'
import {
  dataRow,
  loginAsAnother,
  loginViaUi,
  readPermissions,
  readToken,
  switchTab,
  visiblePanel,
} from './helpers/ui'

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

/**
 * E2E-19 结算/报表的职责分离（§7 决策 4）。
 *
 * 只断**权限边界**与**入口可达性**：
 *   VIEWER —— 进得了结算页与报表页，但三个执行按钮一个都没有；
 *   FINANCE —— 三个执行按钮都在，但没有开单入口（进货/出货页连进都进不去，
 *              因为路由守卫按 pur:read / sal:read 拦；退货页进得去却没有「新建退货单」）。
 * 不断"名称显示降级"那类表现：它取决于 FINANCE 是否另外拿到 mdm:read，
 * 那是另一条泳道正在改的权限种子（§6 坑 16），会变。
 */
test('E2E-19 VIEWER 只能看结算/报表，FINANCE 能结算不能开单', async ({ page, request, run }) => {
  const password = process.env.E2E_PASSWORD ?? 'Admin@12345'

  // ── VIEWER：能看，不能执行 ──
  await loginAsAnother(page, run.viewerUsername, password)
  await expect(page).toHaveURL(/\/dashboard$/)
  const viewerPermissions = await readPermissions(page)
  for (const code of ['set:read', 'rpt:read', 'rma:read']) {
    expect(viewerPermissions, `VIEWER 应有 ${code} 入口权限`).toContain(code)
  }
  for (const code of ['set:daily', 'set:monthly', 'set:reconcile', 'rma:create', 'pur:create']) {
    expect(viewerPermissions, `VIEWER 不应有 ${code}`).not.toContain(code)
  }

  await page.goto('/settlements')
  await expect(page).toHaveURL(/\/settlements$/)
  let panel = visiblePanel(page)
  for (const action of ['执行日清', '执行月结（锁账）', '执行对账']) {
    await expect(panel.getByRole('button', { name: action })).toHaveCount(0)
  }
  await switchTab(page, '月结')
  await expect(visiblePanel(page).getByRole('button', { name: '执行月结（锁账）' })).toHaveCount(0)
  await switchTab(page, '对账')
  await expect(visiblePanel(page).getByRole('button', { name: '执行对账' })).toHaveCount(0)

  await page.goto('/reports')
  await expect(page).toHaveURL(/\/reports$/)
  panel = visiblePanel(page)
  await expect(panel.getByRole('button', { name: '查询' })).toBeVisible()
  for (const tab of ['进货统计', '出货统计', '进销存汇总']) {
    await expect(page.getByRole('tab', { name: tab })).toBeVisible()
  }

  // 后端才是边界：前端不渲染按钮不等于拦不住请求，这里用 VIEWER 自己的会话直接调写接口
  const asViewer = new ApiClient(request, await readToken(page))
  const viewerDaily = await asViewer
    .settleDaily({ bizDate: '2031-01-05' })
    .then(() => null, (error: unknown) => asApiFailure(error))
  expect(viewerDaily, 'VIEWER 执行日清必须被后端拒绝').toMatchObject({
    status: 403,
    code: 'IAM_FORBIDDEN',
  })

  // ── FINANCE：能结算，不能开单 ──
  await loginAsAnother(page, run.financeUsername, password)
  await expect(page).toHaveURL(/\/dashboard$/)
  await expect(page.getByText('FINANCE', { exact: true })).toBeVisible()
  const financePermissions = await readPermissions(page)
  for (const code of ['set:daily', 'set:monthly', 'set:reconcile', 'rpt:read', 'rma:read']) {
    expect(financePermissions, `FINANCE 应有 ${code}`).toContain(code)
  }
  for (const code of ['pur:create', 'sal:create', 'rma:create']) {
    expect(financePermissions, `FINANCE 不应有开单权限 ${code}`).not.toContain(code)
  }

  await page.goto('/settlements')
  await expect(visiblePanel(page).getByRole('button', { name: '执行日清' })).toBeVisible()
  await switchTab(page, '月结')
  await expect(visiblePanel(page).getByRole('button', { name: '执行月结（锁账）' })).toBeVisible()
  await switchTab(page, '对账')
  await expect(visiblePanel(page).getByRole('button', { name: '执行对账' })).toBeVisible()

  await page.goto('/reports')
  await expect(visiblePanel(page).getByRole('button', { name: '查询' })).toBeVisible()

  // 不能开单：进货/出货页被路由守卫弹回工作台（没有 pur:read / sal:read）
  await page.goto('/purchase-orders')
  await expect(page).toHaveURL(/\/dashboard$/)
  await expect(page.getByRole('menuitem', { name: '进货单' })).toHaveCount(0)

  // 退货页进得去（rma:read），但没有建单入口（没有 rma:create）
  await page.goto('/return-orders')
  await expect(page).toHaveURL(/\/return-orders$/)
  await expect(page.getByRole('button', { name: '新建退货单' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '新建第一张退货单' })).toHaveCount(0)

  const asFinance = new ApiClient(request, await readToken(page))
  const financeRead = await asFinance.listPurchases().then(() => null, (error: unknown) => asApiFailure(error))
  expect(financeRead, 'FINANCE 读进货单必须被后端拒绝').toMatchObject({
    status: 403,
    code: 'IAM_FORBIDDEN',
  })
})
