import { expect, test } from './fixtures'
import { readAdminToken } from './helpers/auth'
import { runTag } from './helpers/run'
import { seedBase } from './helpers/seed'
import { SysApi, loginForToken } from './helpers/sys-api'

/**
 * E1 泳道 · 审计与字典的接口级用例（TC-API-115~124）。
 *
 * 界面级用例证明"能看见"，这里证明"契约承诺本身成立"：
 * 非法条件 400 而不是 500、时间区间倒置不静默返空、写方法 405、
 * 快照脱敏、分页钳制、字典 404 与排序。
 *
 * 数据隔离同 09：审计表不清理，断言只认本次 run 的单据号 / 固定种子。
 */
const PASSWORD = process.env.E2E_PASSWORD ?? 'Admin@12345'

test('TC-API-115 时间区间倒置 → 400 SYS_AUDIT_QUERY_INVALID（不静默返空）', async ({ page }) => {
  const sys = new SysApi(page.request, readAdminToken())
  const res = await sys.audits({
    from: '2026-10-24T12:00:00+08:00',
    to: '2026-10-24T11:00:00+08:00',
  })
  expect(res.status).toBe(400)
  expect(res.code).toBe('SYS_AUDIT_QUERY_INVALID')
  expect(res.message).toContain('时间区间非法')
})

test('TC-API-116 非法 action → 400 而不是 500', async ({ page }) => {
  const sys = new SysApi(page.request, readAdminToken())
  const res = await sys.audits({ action: 'NOT_A_REAL_ACTION' })
  expect(res.status).toBe(400)
  expect(res.code).toBe('SYS_AUDIT_QUERY_INVALID')
  expect(res.message).toContain('非法 action')
})

test('TC-API-117 非法 result → 400 而不是 500', async ({ page }) => {
  const sys = new SysApi(page.request, readAdminToken())
  const res = await sys.audits({ result: 'MAYBE' })
  expect(res.status).toBe(400)
  expect(res.code).toBe('SYS_AUDIT_QUERY_INVALID')
  expect(res.message).toContain('非法 result')
})

test('TC-API-118 同一单据：列表倒序、轨迹正序', async ({ page, api, run }) => {
  const tag = runTag(run, 'API118')
  // ★ 造数前取时间戳，用它把断言收窄到本轮：teardown 会回收当日取号序列
  //   （当天已无单据时删 document_sequences 行），同一个单号会在历史轮次复现；
  //   而 audit_logs 是 append-only，历史记录不会被清理，也不会被我们删。
  const startedAt = new Date().toISOString()
  const seed = await seedBase(api, tag, { stockQty: 3 })
  const orderNo = seed.stockOrder!.orderNo
  const sys = new SysApi(page.request, readAdminToken())

  const list = await sys.audits({ bizRefNo: orderNo, from: startedAt, page: 1, size: 20 })
  expect(list.status).toBe(200)
  expect(list.data!.total).toBe(4)
  expect(list.data!.items.map((r) => r.action)).toEqual([
    'RECEIVE',
    'APPROVE',
    'SUBMIT',
    'CREATE',
  ])
  expect(list.data!.items.every((r) => r.bizRefNo === orderNo)).toBe(true)

  // 轨迹端点没有时间参数（契约如此）→ 取正序末尾四条，同样证明"正序"
  const timeline = await sys.timeline(orderNo)
  expect(timeline.status).toBe(200)
  expect(timeline.data!.every((r) => r.bizRefNo === orderNo)).toBe(true)
  expect(timeline.data!.slice(-4).map((r) => r.action)).toEqual([
    'CREATE',
    'SUBMIT',
    'APPROVE',
    'RECEIVE',
  ])
  // 正序：时间不倒退（同一毫秒内由 id 兜底，后端 ORDER BY occurred_at, id）
  const times = timeline.data!.map((r) => new Date(r.occurredAt).getTime())
  expect([...times].sort((a, b) => a - b)).toEqual(times)
  // 两个端点顺序相反：列表最上（最新）就是轨迹最后一条
  expect(list.data!.items[0].id).toBe(timeline.data!.at(-1)!.id)
})

test('TC-API-119 by-trace：只返回该 traceId 的审计且逐条同值', async ({ page, api, run }) => {
  const tag = runTag(run, 'API119')
  const seed = await seedBase(api, tag, { stockQty: 2 })
  const orderNo = seed.stockOrder!.orderNo
  const sys = new SysApi(page.request, readAdminToken())

  // 只取本轮末尾四条（历史轮次可能复用同一单号）；traceId 本身全局唯一
  const timeline = await sys.timeline(orderNo)
  const lastFour = timeline.data!.slice(-4)
  const traceIds = [...new Set(lastFour.map((r) => r.traceId).filter(Boolean))] as string[]
  expect(traceIds.length, '本轮四次写操作应有各自的 traceId').toBe(4)

  // 一次请求恰好一条审计，且每条都只属于自己那个 traceId
  for (const traceId of traceIds) {
    const res = await sys.byTrace(traceId)
    expect(res.status).toBe(200)
    expect(res.data!.length).toBe(1)
    expect(res.data![0].traceId).toBe(traceId)
  }

  // 与轨迹接口互相印证：最后一条就是入库那一刻
  const receiveRows = await sys.byTrace(lastFour.at(-1)!.traceId!)
  expect(receiveRows.data!.map((r) => r.action)).toEqual(['RECEIVE'])
})

test('TC-API-120 审计详情：可取单条，查不到时 404 SYS_AUDIT_NOT_FOUND', async ({
  page,
  api,
  run,
}) => {
  const tag = runTag(run, 'API120')
  const seed = await seedBase(api, tag)
  const sys = new SysApi(page.request, readAdminToken())

  // 用一条真实存在的审计验证单条读路径
  const created = await sys.audits({ resourceType: 'SUPPLIER', resourceId: String(seed.supplier.id) })
  expect(created.status).toBe(200)
  expect(created.data!.total).toBe(1)
  const row = created.data!.items[0]
  const detail = await sys.audit(row.id)
  expect(detail.status).toBe(200)
  expect(detail.data).toMatchObject({ id: row.id, action: 'CREATE', resourceType: 'SUPPLIER' })
  expect(detail.data!.afterJson).toContain(seed.supplier.code)

  const missing = await sys.audit(999_999_999)
  expect(missing.status).toBe(404)
  expect(missing.code).toBe('SYS_AUDIT_NOT_FOUND')
})

test('TC-API-121 审计只读：对 /audits 的任何写方法返回 405', async ({ page }) => {
  const sys = new SysApi(page.request, readAdminToken())
  for (const method of ['POST', 'PUT', 'DELETE'] as const) {
    const res = await sys.attempt(method, '/audits')
    expect(res.status, `${method} /audits 必须是 405（契约：审计只有 GET）`).toBe(405)
  }
})

test('TC-API-122 审计响应里的敏感字段已脱敏：token 只可能是 ***', async ({ page, request }) => {
  const sys = new SysApi(page.request, readAdminToken())
  // 先产生一次登录审计（登录本身就是被审计的写操作 LOGIN）
  await loginForToken(request, process.env.E2E_USERNAME ?? 'admin', PASSWORD)

  const res = await sys.audits({ action: 'LOGIN', page: 1, size: 5 })
  expect(res.status).toBe(200)
  expect(res.data!.total, '本次运行至少有刚发生的登录审计').toBeGreaterThan(0)
  for (const row of res.data!.items) {
    expect(row.afterJson, `审计 #${row.id} 应带快照`).toBeTruthy()
    expect(row.afterJson).toMatch(/"token":\s*"\*\*\*"/)
    expect(row.afterJson, `审计 #${row.id} 不得回吐 JWT`).not.toMatch(/eyJ[A-Za-z0-9_-]{10,}/)
  }
})

test('TC-API-123 分页参数钳制：size 上限 200、页码下限 1，越界不报错', async ({ page }) => {
  const sys = new SysApi(page.request, readAdminToken())
  const big = await sys.audits({ page: 1, size: 100_000 })
  expect(big.status).toBe(200)
  expect(big.data!.size).toBe(200)
  expect(big.data!.items.length).toBeLessThanOrEqual(200)

  const zero = await sys.audits({ page: 0, size: 5 })
  expect(zero.status).toBe(200)
  expect(zero.data!.page).toBe(1)
})

test('TC-API-124 字典：四类种子、项按 sortNo 升序、未知类型 404', async ({ page }) => {
  const sys = new SysApi(page.request, readAdminToken())

  const types = await sys.dictTypes()
  expect(types.status).toBe(200)
  expect(types.data!.map((t) => t.code).sort()).toEqual(['COLOR', 'SEASON', 'SIZE', 'UNIT'])
  expect(types.data!.every((t) => t.enabled)).toBe(true)

  const items = await sys.dictItems('COLOR')
  expect(items.status).toBe(200)
  expect(items.data!.map((i) => i.code)).toEqual(['BLACK', 'WHITE', 'RED', 'BLUE'])
  expect(items.data!.map((i) => i.sortNo)).toEqual([10, 20, 30, 40])
  expect(items.data!.every((i) => i.typeCode === 'COLOR')).toBe(true)

  // onlyEnabled=false 也必须是合法读路径（类型与项各一次）
  const allTypes = await sys.dictTypes({ onlyEnabled: false })
  expect(allTypes.status).toBe(200)
  const allItems = await sys.dictItems('COLOR', { onlyEnabled: false })
  expect(allItems.status).toBe(200)

  const missing = await sys.dictItems('NO_SUCH_TYPE')
  expect(missing.status).toBe(404)
  expect(missing.code).toBe('SYS_DICT_TYPE_NOT_FOUND')
})
