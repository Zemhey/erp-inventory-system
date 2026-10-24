import type { Page } from '@playwright/test'
import { expect, test } from './fixtures'
import { readAdminToken } from './helpers/auth'
import { querySql, sqlString } from './helpers/db'
import { createTempUser } from './helpers/db-ops'
import { e1Shot } from './helpers/lane-shot'
import { runTag } from './helpers/run'
import { seedBase } from './helpers/seed'
import { SysApi } from './helpers/sys-api'
import {
  dataRow,
  loginViaUi,
  readPermissions,
  readToken,
  selectOption,
  visibleDialog,
} from './helpers/ui'

/**
 * E1 泳道 · 审计查询与数据字典的界面级用例（TC-SYS-091 起）。
 *
 * 这一组必须自己登录（不复用 admin 的 storageState）：
 * 权限边界用例要换身份，而且"入口显隐 + 直接输 URL"这两条只有在真实会话里才成立。
 *
 * 数据隔离口径：审计表是 append-only，**清理脚本不删 audit_logs**。
 * 所以断言一律按本次 run 的唯一单据号 / 资源 id / 临时账号名收窄，
 * 不看"全表第几行"，也不假设总量 —— 审计自然增长是设计行为，不是残留。
 */
test.use({ storageState: { cookies: [], origins: [] } })

const PASSWORD = process.env.E2E_PASSWORD ?? 'Admin@12345'
const ADMIN = process.env.E2E_USERNAME ?? 'admin'

/**
 * 登录并**等它真的落在工作台**。
 *
 * 不能 loginViaUi 完就直接 goto：点「登录」后立刻导航会把还在飞的登录请求打断，
 * 结果是 token 没写进 localStorage，下一跳被路由守卫弹回登录页 ——
 * 看起来像"页面坏了"，实际是用例抢跑（本泳道第一轮实测就踩了这个）。
 */
async function loginAndWaitDashboard(page: Page, username: string): Promise<void> {
  await loginViaUi(page, username, PASSWORD)
  await expect(page).toHaveURL(/\/dashboard$/)
}

test('TC-SYS-091 审计页：按单据号筛出该单全部留痕，倒序且只读', async ({ page, api, run }) => {
  const tag = runTag(run, 'SYS091')
  const seed = await seedBase(api, tag, { stockQty: 10 })
  const orderNo = seed.stockOrder!.orderNo

  await loginAndWaitDashboard(page, ADMIN)
  await page.goto('/audits')
  await expect(page.getByRole('heading', { name: '审计查询' })).toBeVisible()
  // 页面必须自曝边界：sys:read 能看全公司，且没有"改/删"入口
  await expect(page.getByText('能看到全部操作记录，当前不按仓库或本人过滤')).toBeVisible()
  await expect(page.getByText(/只读页面：这里只能查，不能改也改不了/)).toBeVisible()

  const rows = page.getByRole('row').filter({ hasText: orderNo })
  await page.getByPlaceholder('业务单据号（精确匹配）').fill(orderNo)
  await page.getByRole('button', { name: '查询', exact: true }).click()

  // 建单 → 提交 → 审核 → 入库：四次写操作各一条。
  // ★ 不能直接断"恰好 4 条"：清理会回收当日的取号序列（当天已无单据时删 sequence 行），
  //   同一个 PO 号会在历史轮次里出现过，而审计表 append-only、不清理。
  //   所以先用单据号确认"至少本轮四条"，再用本轮单据的 resourceId（BIGSERIAL，不复用）收窄。
  await expect(rows.first()).toBeVisible()
  expect(await rows.count(), '按单据号至少应查到本轮 4 条').toBeGreaterThanOrEqual(4)

  await page.getByPlaceholder('资源 ID').fill(String(seed.stockOrder!.id))
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await expect(rows).toHaveCount(4)
  await expect(page.getByText('共 4 条').first()).toBeVisible()

  // 列表默认倒序：最后一笔（入库）在最上，建单在最下
  const texts = await rows.allInnerTexts()
  expect(texts[0]).toContain('入库')
  expect(texts[1]).toContain('审核通过')
  expect(texts[2]).toContain('提交')
  expect(texts[3]).toContain('新建')
  for (const label of ['新建', '提交', '审核通过', '入库']) {
    expect(texts.some((t) => t.includes(label)), `列表缺少动作「${label}」`).toBe(true)
  }
  // 四次都成功；单据流程动作没有变更前快照（路径末段是动作词，后端取不到上一份快照）
  expect(texts.every((t) => t.includes('成功'))).toBe(true)
  expect(texts.every((t) => t.includes('无变更前快照'))).toBe(true)

  await e1Shot(page, 'TC-SYS-091-audit-list')
})

test('TC-SYS-092 审计详情：before/after 并排 diff 指得出改了什么', async ({ page, api, run }) => {
  const tag = runTag(run, 'SYS092')
  const seed = await seedBase(api, tag)
  const oldName = seed.supplier.name
  const newName = `${tag} 改名后供应商`

  // 造真实差异：只有 PUT /x/{id} 这种路径带资源 id 的写操作才会取上一次快照
  const sys = new SysApi(page.request, readAdminToken())
  await sys.updateSupplier(seed.supplier.id, { name: newName })

  await loginAndWaitDashboard(page, ADMIN)
  await page.goto('/audits')
  // 主数据审计没有 bizRefNo（后端刻意不把编码当单据号），只能按资源维度定位
  await page.getByPlaceholder('资源类型（如 SUPPLIER）').fill('SUPPLIER')
  await page.getByPlaceholder('资源 ID').fill(String(seed.supplier.id))
  await page.getByRole('button', { name: '查询', exact: true }).click()

  const rows = page.getByRole('row').filter({ hasText: `#${seed.supplier.id}` })
  await expect(rows).toHaveCount(2) // 新建 + 修改
  const updateRow = dataRow(page, '修改')
  await expect(updateRow).toContainText('SUPPLIER')
  await updateRow.getByRole('button', { name: '详情' }).first().click()

  const drawer = visibleDialog(page, /审计详情/)
  await expect(drawer).toBeVisible()
  await expect(drawer.getByText('变更前 / 变更后对照')).toBeVisible()
  await expect(drawer.getByText('修改 1')).toBeVisible()

  // 字段级对照：name 这一行左边旧名、右边新名
  const nameRow = drawer
    .locator('tr')
    .filter({ has: page.locator('code:text-is("name")') })
  await expect(nameRow.locator('td').nth(1)).toContainText(oldName)
  await expect(nameRow.locator('td').nth(2)).toContainText(newName)

  // 默认只看差异：未变字段不出现
  const onlyDiff = drawer.getByRole('checkbox', { name: '只看差异字段' })
  await expect(onlyDiff).toBeChecked()
  await expect(drawer.getByText('contactName', { exact: true })).toHaveCount(0)
  await e1Shot(page, 'TC-SYS-092-audit-diff')

  // 关掉"只看差异" → 未变字段补齐（证明"没显示"是因为过滤，不是因为没数据）
  await drawer.getByText('只看差异字段', { exact: true }).click()
  await expect(drawer.getByText('contactName', { exact: true })).toBeVisible()
  await expect(drawer.getByText('字段无变化')).toHaveCount(0)
  await e1Shot(page, 'TC-SYS-092-audit-diff-all')
})

test('TC-SYS-093 单据轨迹正序 + 按 traceId 看同请求审计', async ({ page, api, run }) => {
  const tag = runTag(run, 'SYS093')
  const seed = await seedBase(api, tag, { stockQty: 4 })
  const orderNo = seed.stockOrder!.orderNo

  const sys = new SysApi(page.request, readAdminToken())
  const timeline = await sys.timeline(orderNo)
  expect(timeline.status, '轨迹接口应可用（造数前提）').toBe(200)
  const traceId = timeline.data!.at(-1)!.traceId!
  expect(traceId, '最后一次写操作必须带 traceId').toBeTruthy()

  await loginAndWaitDashboard(page, ADMIN)
  await page.goto('/audits')
  await page.getByPlaceholder('业务单据号（精确匹配）').fill(orderNo)
  // 与 091 同口径：回收取号会让同一单号跨轮复现，用本轮单据 id 收窄到恰好 4 条
  await page.getByPlaceholder('资源 ID').fill(String(seed.stockOrder!.id))
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await expect(page.getByRole('row').filter({ hasText: orderNo })).toHaveCount(4)

  // ① 点「单据号」→ 该单据轨迹（正序：建单在前）
  await page.getByRole('button', { name: orderNo, exact: true }).first().click()
  const timelineDialog = visibleDialog(page, /的审计轨迹/)
  await expect(timelineDialog).toBeVisible()
  await expect(timelineDialog.getByText(/按发生时间正序/)).toBeVisible()
  const traceRows = timelineDialog.getByRole('row')
  // 弹窗刚打开时表格行可能还没渲染，先等第一行出现再数（count() 不会自动等待）
  await expect(traceRows.first()).toBeVisible()
  const bodyCount = (await traceRows.count()) - 1
  expect(bodyCount, '轨迹至少包含本轮的 4 条').toBeGreaterThanOrEqual(4)
  const timelineTexts = await traceRows.allInnerTexts()
  // 正序：末尾四条是本轮的 建单 → 提交 → 审核 → 入库（历史轮同号记录排在前面）
  expect(timelineTexts.at(-4)).toContain('新建')
  expect(timelineTexts.at(-3)).toContain('提交')
  expect(timelineTexts.at(-2)).toContain('审核通过')
  expect(timelineTexts.at(-1)).toContain('入库')
  await e1Shot(page, 'TC-SYS-093-audit-timeline')

  await page.keyboard.press('Escape')
  await expect(timelineDialog).toBeHidden()

  // ② 点「traceId」→ 同一次请求产生的全部审计
  await page.getByRole('button', { name: traceId, exact: true }).first().click()
  const traceDialog = visibleDialog(page, traceId)
  await expect(traceDialog).toBeVisible()
  await expect(traceDialog.getByText(/同一次请求产生的全部审计/)).toBeVisible()
  await expect(traceDialog.getByRole('row').first()).toBeVisible()
  const traceCount = await traceDialog.getByRole('row').count()
  expect(traceCount - 1, '该 traceId 至少应有一条审计').toBeGreaterThanOrEqual(1)
  // 该 traceId 来自最后一次写操作（入库），对话框里应当就是那一条
  await expect(traceDialog.getByRole('row').nth(1)).toContainText('入库')
  await e1Shot(page, 'TC-SYS-093-audit-by-trace')
})

test('TC-SYS-094 字典页：只读浏览类型与字典项，界面上没有写入口', async ({ page }) => {
  await loginAndWaitDashboard(page, ADMIN)
  await page.goto('/dicts')
  await expect(page.getByRole('heading', { name: '数据字典' })).toBeVisible()
  // "只读"必须写出原因，而不是做一个看起来能改的假界面
  await expect(page.getByText('当前为只读：后端没有字典写接口，因此这里不放编辑入口')).toBeVisible()
  await expect(page.getByText('共 4 类')).toBeVisible()

  for (const code of ['COLOR', 'SIZE', 'SEASON', 'UNIT']) {
    await expect(page.getByRole('cell', { name: code, exact: true })).toBeVisible()
  }
  // 写入口一律不存在（后端没有写端点，界面上就不该有按钮）
  for (const name of ['新建', '新增', '编辑', '保存', '删除', '停用']) {
    await expect(page.getByRole('button', { name }), `不该出现写按钮「${name}」`).toHaveCount(0)
  }

  // 选 COLOR → 4 项，按后端返回的 sortNo 升序
  const colorRow = dataRow(page, 'COLOR')
  await colorRow.getByRole('button', { name: '查看项' }).first().click()
  await expect(page.getByText('颜色（COLOR）的字典项')).toBeVisible()
  await expect(page.getByText('共 4 项')).toBeVisible()
  const itemRows = page.getByRole('row').filter({ hasText: /黑色|白色|红色|蓝色/ })
  await expect(itemRows).toHaveCount(4)
  const labels = await itemRows.allInnerTexts()
  expect(labels[0]).toContain('黑色')
  expect(labels[3]).toContain('蓝色')
  expect(labels.map((t) => Number(t.trim().split(/\s+/)[0]))).toEqual([10, 20, 30, 40])

  await e1Shot(page, 'TC-SYS-094-dict-readonly')

  // "含停用项"开关必须真的把 onlyEnabled=false 发给后端，而不是只翻转前端状态
  const requestPromise = page.waitForRequest(
    (r) => r.url().includes('/api/v1/dicts') && r.url().includes('onlyEnabled=false'),
  )
  await page.getByText('包含已停用的类型与字典项').click()
  const request = await requestPromise
  expect(decodeURIComponent(request.url())).toContain('onlyEnabled=false')
})

test('TC-SYS-095 权限：无 sys:read 看不到入口，直接输 URL 与调接口都被拦', async ({
  page,
  request,
  run,
}) => {
  const purchaser = `purchaser-${run.runId.toLowerCase()}-095`
  // 用既有 PURCHASER 角色（能开进货单、但没有 sys:read）：证明"能写 ≠ 能看审计"
  createTempUser(purchaser, 'PURCHASER', ADMIN)
  try {
    await loginAndWaitDashboard(page, purchaser)

    const permissions = await readPermissions(page)
    expect(permissions, 'PURCHASER 不应有 sys:read').not.toContain('sys:read')
    expect(permissions).not.toContain('sys:*')
    expect(permissions, '前提校验：这个身份确实有写权限').toContain('pur:create')

    // ① 菜单入口不渲染
    await expect(page.getByRole('menuitem', { name: '审计查询' })).toHaveCount(0)
    await expect(page.getByRole('menuitem', { name: '数据字典' })).toHaveCount(0)

    // ② 直接输 URL 被路由守卫弹回工作台
    await page.goto('/audits')
    await expect(page).toHaveURL(/\/dashboard$/)
    await page.goto('/dicts')
    await expect(page).toHaveURL(/\/dashboard$/)
    await e1Shot(page, 'TC-SYS-095-permission-blocked')

    // ③ 后端才是边界：前端不渲染 ≠ 接口能拦
    const sys = new SysApi(request, await readToken(page))
    const audits = await sys.audits({ page: 1, size: 1 })
    expect(audits.status, '无 sys:read 调 /audits 必须 403').toBe(403)
    expect(audits.code).toBe('IAM_FORBIDDEN')

    const dicts = await sys.dictTypes()
    expect(dicts.status, '无 sys:read 调 /dicts 必须 403').toBe(403)
    expect(dicts.code).toBe('IAM_FORBIDDEN')
  } finally {
    // 临时身份由本用例自建自清：teardown 的 "users 计数回到基线" 才是真的
    const username = sqlString(purchaser)
    querySql(`
DELETE FROM app_user_roles WHERE user_id IN (SELECT id FROM app_users WHERE username = ${username});
DELETE FROM app_users WHERE username = ${username};
`)
  }
})

test('TC-SYS-096 筛选条件装配：动作 / 结果 / 时间区间都进查询参数', async ({ page, api, run }) => {
  const tag = runTag(run, 'SYS096')
  const seed = await seedBase(api, tag, { stockQty: 2 })
  const orderNo = seed.stockOrder!.orderNo

  await loginAndWaitDashboard(page, ADMIN)
  await page.goto('/audits')
  await page.getByPlaceholder('业务单据号（精确匹配）').fill(orderNo)
  // 同号会跨轮复现，再用本轮单据 id 收窄，末尾的"只剩 1 条"才是可断言的
  await page.getByPlaceholder('资源 ID').fill(String(seed.stockOrder!.id))

  // 动作：只留「入库」
  const actionRequest = page.waitForRequest(
    (r) => r.url().includes('/api/v1/audits') && r.url().includes('action=RECEIVE'),
  )
  await selectOption(page, page.getByText('全部操作类型'), '入库（RECEIVE）')
  await actionRequest

  // 结果：只留「成功」
  const resultRequest = page.waitForRequest(
    (r) =>
      r.url().includes('/api/v1/audits') &&
      r.url().includes('result=SUCCESS') &&
      r.url().includes('action=RECEIVE'),
  )
  await selectOption(page, page.getByText('全部结果'), '成功')
  await resultRequest

  // 时间区间：必须是本地零点 → 次日零点的半开区间，且以 ISO-8601 带时区发出
  const pad = (n: number) => String(n).padStart(2, '0')
  const now = new Date()
  const day = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
  const next = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1)
  const nextDay = `${next.getFullYear()}-${pad(next.getMonth() + 1)}-${pad(next.getDate())}`

  const rangeRequest = page.waitForRequest(
    (r) =>
      r.url().includes('/api/v1/audits') &&
      r.url().includes('action=RECEIVE') &&
      r.url().includes('from='),
  )
  // 日期面板弹出时 popper 里也有同 placeholder 的输入框，必须限定在筛选区里
  const rangeEditor = page.locator('.filters .el-range-editor')
  await rangeEditor.getByPlaceholder('开始时间').fill(`${day} 00:00`)
  await rangeEditor.getByPlaceholder('结束时间（不含）').fill(`${nextDay} 00:00`)
  await rangeEditor.getByPlaceholder('结束时间（不含）').press('Enter')
  const range = new URL((await rangeRequest).url())
  expect(new Date(range.searchParams.get('from')!).getTime()).toBe(
    new Date(`${day}T00:00:00`).getTime(),
  )
  expect(new Date(range.searchParams.get('to')!).getTime()).toBe(
    new Date(`${nextDay}T00:00:00`).getTime(),
  )
  expect(range.searchParams.get('result')).toBe('SUCCESS')

  // 三个条件下只剩那一条入库
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await expect(page.getByRole('row').filter({ hasText: orderNo })).toHaveCount(1)
  await expect(page.getByText('共 1 条').first()).toBeVisible()
})
