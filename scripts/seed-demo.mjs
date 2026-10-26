// 端到端演示数据 seeder：走真实业务接口，不走 SQL 直插。
//
// 为什么必须走接口：库存余额只能由进货入库产生，出货会扣减并写流水，
// 应收/应付与会计凭证是业务服务里的钩子自动生成的。SQL 直插只能造出"有行、
// 无账"的数据——报表能出数但和工作台/凭证对不上，那种数据在答辩上一问就穿。
//
// 口径（全项目一致，别改）：金额单位=分，折扣=万分比（9000 表示 90%）。
// 注意不要调 /settlements/daily|monthly —— 那是日结/月结，会锁账期，
// 锁完之后本脚本和界面都无法再写入。
//
// 用法：
//   node scripts/seed-demo.mjs                    # 幂等：已有 DM- 前缀主数据则退出
//   node scripts/seed-demo.mjs --force            # 跳过幂等检查再来一遍
//   ERP_API=http://127.0.0.1:8111/api/v1 node scripts/seed-demo.mjs
//   ERP_USER=admin ERP_PASS=Admin@12345 node scripts/seed-demo.mjs

import { randomUUID } from 'node:crypto'

const BASE = (process.env.ERP_API ?? 'http://127.0.0.1:8080/api/v1').replace(/\/$/, '')
const USER = process.env.ERP_USER ?? 'admin'
const PASS = process.env.ERP_PASS ?? 'Admin@12345'
const FORCE = process.argv.includes('--force')
/** 只补主数据 + 审批流 + 中间态单据，不再灌一整波进出货。 */
const PENDING_ONLY = process.argv.includes('--pending')

/** 演示规模：商品线数 × 每线的进货单数与销售单数。改这里就能放大/缩小。 */
const PLAN = {
  warehouses: [
    { code: 'DM-WH-SH', name: '上海主仓', priority: 10 },
    { code: 'DM-WH-GZ', name: '广州分仓', priority: 5 },
  ],
  suppliers: ['华阳纺织', '锦绣针织厂', '三和制衣', '远帆鞋业', '瑞祥辅料', '明德包装'],
  customers: ['优品服饰店', '城南百货', '季风电商', '西岸买手店', '北辰团购', '海雾直播工作室', '长街档口', '青柠集合店'],
  // [商品名, 颜色, 尺码, 零售价(分), 进货成本(分)]
  products: [
    ['纯棉短袖T恤', '白色', 'L', 7900, 4200],
    ['牛津纺长袖衬衫', '浅蓝', 'M', 16900, 9200],
    ['直筒牛仔裤', '靛蓝', '30', 19900, 11500],
    ['帆布休闲鞋', '米白', '41', 15900, 8800],
    ['羊毛混纺围巾', '驼色', '均码', 12900, 6400],
    ['防晒轻薄外套', '雾灰', 'XL', 21900, 12800],
  ],
  stockRounds: 4,
  salesRounds: 6,
}

let token = ''
const failed = []

function day(offsetDays) {
  const d = new Date(Date.now() - offsetDays * 86400000)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/** 确定性伪随机：同一次参数重复跑得到同一批数字，方便对比截图。 */
let seedState = 20261025
function rnd(bound) {
  seedState = (seedState * 1103515245 + 12345) & 0x7fffffff
  return (seedState % bound) + 1
}

async function call(method, path, body) {
  const headers = { 'Content-Type': 'application/json' }
  if (token) headers.Authorization = `Bearer ${token}`
  // 写操作必须带 X-Request-Id：后端用它做幂等键，缺了会被拦。
  if (method !== 'GET') headers['X-Request-Id'] = randomUUID()
  const res = await fetch(`${BASE}${path}`, { method, headers, body: body ? JSON.stringify(body) : undefined })
  let payload = null
  try {
    payload = await res.json()
  } catch {
    /* 非 JSON 响应，走下面的状态码分支 */
  }
  if (!res.ok) {
    const code = payload?.code ?? `HTTP ${res.status}`
    const err = new Error(`${method} ${path} -> ${code}: ${payload?.message ?? res.statusText}`)
    err.detail = { code, status: res.status, path }
    throw err
  }
  return payload?.data
}

const yuan = (fen) => (fen / 100).toFixed(2)

const unwrap = (d) => (Array.isArray(d) ? d : (d?.items ?? []))

/**
 * 建主数据；编码已存在就回库里那条。
 * 这样 seeder 可以反复执行来追加单据，而不是第二次就撞 uq_*_code 全部失败。
 */
async function findOrCreate(createPath, body, listPath) {
  try {
    return await call('POST', createPath, body)
  } catch (e) {
    if (!/409|DUPL|已存在|重复/.test(e.message)) throw e
    const rows = unwrap(await call('GET', listPath))
    const hit = rows.find((r) => r.code === body.code)
    if (!hit) throw e
    return hit
  }
}

async function main() {
  const login = await call('POST', '/auth/login', { username: USER, password: PASS })
  token = login.token
  console.log(`登录 ${login.username}（租户 ${login.tenantCode}），权限 ${login.permissions.length} 项`)

  // 幂等闸门：主数据 code 前缀 DM- 已存在就说明造过了。
  if (!FORCE && !PENDING_ONLY) {
    const probe = await call('GET', '/products?page=1&size=200').catch(() => ({ items: [] }))
    const hits = (probe.items ?? probe ?? []).filter((p) => String(p.code).startsWith('DM-'))
    if (hits.length > 0) {
      console.log(`已存在 ${hits.length} 条 DM- 前缀商品，未做任何改动。要再来一遍请加 --force`)
      return
    }
  }

  const warehouses = []
  for (const w of PLAN.warehouses) {
    warehouses.push(await findOrCreate('/warehouses', w, '/warehouses?page=1&size=200'))
  }
  const suppliers = []
  for (const [i, name] of PLAN.suppliers.entries()) {
    suppliers.push(await findOrCreate('/suppliers', { code: `DM-SUP-${i + 1}`, name }, '/suppliers?page=1&size=200'))
  }
  const customers = []
  for (const [i, name] of PLAN.customers.entries()) {
    // discountRate=9000 即该客户默认 9 折，会体现在销售单折扣上
    customers.push(await findOrCreate('/customers', { code: `DM-CUS-${i + 1}`, name, discountRate: 9000 }, '/customers?page=1&size=200'))
  }
  console.log(`主数据：${warehouses.length} 仓 / ${suppliers.length} 供应商 / ${customers.length} 客户`)

  // 银行账户：财务首页"资金余额"与现金日记账的载体。initBalance 单位是元。
  const banks = [
    { code: 'DM-BANK-CMB', name: '招商银行基本户', bankName: '招商银行上海分行', accountNo: '6214****8801', currency: 'CNY', initBalance: 500000, isDefault: true, enabled: true },
    { code: 'DM-BANK-ICBC', name: '工商银行一般户', bankName: '工商银行广州分行', accountNo: '6222****3307', currency: 'CNY', initBalance: 120000, isDefault: false, enabled: true },
  ]
  for (const b of banks) {
    await findOrCreate('/fin/bank-accounts', b, '/fin/bank-accounts?page=1&size=50')
      .catch((e) => note('银行账户', e))
  }

  /** 一条演示商品线 = 1 商品 + 1 SKU + 固定供应商 + 固定仓 */
  const lines = []
  for (const [i, [name, color, size, retail, cost]] of PLAN.products.entries()) {
    const product = await findOrCreate('/products', { code: `DM-P-${i + 1}`, name }, '/products?page=1&size=200')
    // SKU 没有 code，按 商品+颜色+尺码 找已有的，避免重跑撞唯一约束
    const skuList = unwrap(await call('GET', '/skus?page=1&size=400'))
    let sku = skuList.find((s) => Number(s.productId) === product.id && s.color === color && s.size === size)
    if (!sku) {
      sku = await call('POST', '/skus', { productId: product.id, color, size, retailPriceAmount: retail })
    }
    lines.push({ name, product, sku, cost, retail, supplier: suppliers[i % suppliers.length], warehouse: warehouses[i % warehouses.length] })
    console.log(`商品 ${name}（${color}/${size}）零售 ${yuan(retail)} 元 / 成本 ${yuan(cost)} 元`)
  }

  // 审批流：V14 种子建的三条流**都没有节点**（steps=0）。实测打开后 submit 不生成审批
  // 实例、approve 又被闸门拦成 APS_APPROVAL_REQUIRED，单据永久卡在 SUBMITTED —— 整条
  // 进货→入库→凭证链停摆。所以这里只启用真有节点的流，没节点就跳过并说明原因。
  const flows = unwrap(await call('GET', '/approvals/flows'))
  for (const f of flows) {
    if (f.enabled) continue
    if ((f.steps ?? []).length === 0) {
      console.log(`跳过审批流 ${f.code}：没有审批节点，启用会让单据卡在 SUBMITTED（需先补节点）`)
      continue
    }
    await call('POST', `/approvals/flows/${f.id}/enabled?enabled=true`)
      .then(() => console.log(`启用审批流 ${f.code}`))
      .catch((e) => note(`启用审批流 ${f.code}`, e))
  }

  let purchaseCount = 0
  let salesCount = 0
  if (!PENDING_ONLY) {
  // 先备货再出货：每个 SKU 在其仓里必须有足够实存，否则后端按可用量拦截。
  for (const line of lines) {
    for (let r = 0; r < PLAN.stockRounds; r++) {
      // 覆盖 4 个波次，最后一波必须落在当月：备货全排在 42~84 天前的话，
      // 大屏的「本月进货金额」和进货趋势图就永远是 0，headline 空一格很难看。
      const offset = 82 - r * 24 - rnd(6)
      const qty = 40 + rnd(60)
      await stockIn(line, qty, day(offset)).then(
        () => purchaseCount++,
        (e) => note(`进货 ${line.name} @${day(offset)}`, e),
      )
    }
  }

  for (const line of lines) {
    for (let r = 0; r < PLAN.salesRounds; r++) {
      const offset = 56 - r * 9 - rnd(5)
      const customer = customers[(r + lines.indexOf(line)) % customers.length]
      const qty = 2 + rnd(8)
      // 售价用零售价、折扣按客户等级；成本由后端按移动加权算，所以毛利分析有真实差值
      await shipOut(line, customer, qty, day(offset)).then(
        () => salesCount++,
        (e) => note(`出货 ${line.name} @${day(offset)}`, e),
      )
    }
  }

  }

  // 核销一批应收/应付：收付款才会写现金日记账、才会生成 SAL_RCV / PUR_PAY 凭证，
  // 财务首页的"资金余额"和账龄报表也才有已收与未收的对比。故意只核一部分（约六成），
  // 剩下的留着当挂账 —— 全部结清的账龄报表同样是零信息。
  const settle = await settleWave()
  console.log(`核销：收款 ${settle.receipts} 笔 ${yuan(settle.receivedFen)} 元，付款 ${settle.payments} 笔 ${yuan(settle.paidFen)} 元`)

  // 留几张停在中间态的单：待办中心、审批工作台、消息通知才有内容可看。
  // 全推到 COMPLETED 的系统看起来像"跑完的批处理"，不像正在被用的系统。
  let pendingCount = 0
  for (const [i, line] of lines.entries()) {
    if (i % 2 === 0) {
      // 已提交待审批的进货单
      await stockPending(line, 12 + rnd(20), day(1 + rnd(3)), 'submit').then(
        () => pendingCount++, (e) => note(`待审进货 ${line.name}`, e))
    }
    // 已审核待收货的进货单
    await stockPending(line, 8 + rnd(15), day(1 + rnd(4)), 'approve').then(
      () => pendingCount++, (e) => note(`待收进货 ${line.name}`, e))
  }
  for (const [i, line] of lines.entries()) {
    // 已审核待发货的出货单
    await salesPending(line, customers[i % customers.length], 2 + rnd(3), day(1 + rnd(5))).then(
      () => pendingCount++, (e) => note(`待发出货 ${line.name}`, e))
  }

  console.log(`\n建单完成：进货入库 ${purchaseCount} 张，销售出货 ${salesCount} 张，停留在中间态 ${pendingCount} 张`)

  // 成本引擎两步：不跑这两步的话 cost_layers 是空的 —— 毛利分析整页无行，
  // 周转/呆滞报表也拿不到金额。（自动凭证只带收入侧分录：凭证在出货那一刻生成，
  // 而 cost_layers 要等这里的重算才落库，两者顺序不可颠倒，属既有设计而非缺陷。）
  const rc = await call('POST', '/cost/recalculate', {}).catch((e) => note('成本重算', e))
  if (rc) {
    console.log(`成本重算：${rc.layerCount} 个成本层，期末成本 ${yuan(rc.endingCostAmount)} 元，配平=${rc.balanced}`)
  }
  for (const period of periods(2)) {
    const cm = await call('POST', '/cost/monthly', { period }).catch((e) => note(`成本月结 ${period}`, e))
    if (cm) console.log(`成本月结 ${period}：${cm.items.length} 个 SKU×仓，毛利记录 ${cm.marginRecordCount} 条`)
  }

  await verify()
}

/** 最近 n 个自然月（yyyy-MM），倒序。 */
function periods(n) {
  const out = []
  const d = new Date()
  for (let i = 0; i < n; i++) {
    out.push(`${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`)
    d.setMonth(d.getMonth() - 1)
  }
  return out
}

async function stockIn(line, qty, bizDate) {
  let order = await call('POST', '/purchase-orders', {
    supplierId: line.supplier.id,
    warehouseId: line.warehouse.id,
    remark: `演示备货 ${line.name}`,
    bizDate,
    lines: [{ skuId: line.sku.id, qty, unitPriceAmount: line.cost }],
  })
  order = await call('POST', `/purchase-orders/${order.id}/submit`)
  order = await call('POST', `/purchase-orders/${order.id}/approve`)
  // 入库是独立动作：审核通过只代表"可收货"，实收才写库存流水并生成 PUR_IN 凭证
  return call('POST', `/purchase-orders/${order.id}/receive`, {
    lines: [{ lineId: order.lines[0].id, qty }],
  })
}

async function shipOut(line, customer, qty, bizDate) {
  let order = await call('POST', '/sales-orders', {
    customerId: customer.id,
    warehouseId: line.warehouse.id,
    remark: `演示销售 ${line.name}`,
    bizDate,
    lines: [{ skuId: line.sku.id, qty, unitPriceAmount: line.retail, discountRate: 9000 }],
  })
  order = await call('POST', `/sales-orders/${order.id}/submit`)
  order = await call('POST', `/sales-orders/${order.id}/approve`)
  return call('POST', `/sales-orders/${order.id}/ship`)
}

/** 造一张停在指定状态的进货单：submit=待审批，approve=已审待收。 */
async function stockPending(line, qty, bizDate, until) {
  let order = await call('POST', '/purchase-orders', {
    supplierId: line.supplier.id,
    warehouseId: line.warehouse.id,
    remark: `演示待办（${until === 'submit' ? '待审批' : '待收货'}）${line.name}`,
    bizDate,
    lines: [{ skuId: line.sku.id, qty, unitPriceAmount: line.cost }],
  })
  order = await call('POST', `/purchase-orders/${order.id}/submit`)
  if (until === 'approve') order = await call('POST', `/purchase-orders/${order.id}/approve`)
  return order
}

/** 造一张已审核待发货的出货单（预占已经产生，所以补货/发货台都有东西可点）。 */
async function salesPending(line, customer, qty, bizDate) {
  let order = await call('POST', '/sales-orders', {
    customerId: customer.id,
    warehouseId: line.warehouse.id,
    remark: `演示待办（待发货）${line.name}`,
    bizDate,
    lines: [{ skuId: line.sku.id, qty, unitPriceAmount: line.retail, discountRate: 9000 }],
  })
  order = await call('POST', `/sales-orders/${order.id}/submit`)
  return call('POST', `/sales-orders/${order.id}/approve`)
}

/**
 * 收付款核销一波。
 *
 * 分配口径：按 balance 的约六成核销，且每人只核前两条 —— 故意留尾差和挂账，
 * 这样应收/应付账龄报表同时有"已收"和"未收"两种行，财务首页资金余额也才非零。
 */
async function settleWave() {
  let receipts = 0
  let payments = 0
  let receivedFen = 0
  let paidFen = 0

  const recv = unwrap(await call('GET', '/receivables?page=1&size=200'))
    .filter((r) => Number(r.balance) > 0 && r.status !== 'SETTLED')
  const byCustomer = new Map()
  for (const r of recv) {
    const k = Number(r.customerId)
    if (!byCustomer.has(k)) byCustomer.set(k, [])
    byCustomer.get(k).push(r)
  }
  let ci = 0
  for (const [customerId, rows] of byCustomer) {
    if (ci++ >= 12) break
    const allocations = rows.slice(0, 2)
      .map((r) => ({ receivableId: Number(r.id), amount: Math.round(Number(r.balance) * 0.6) }))
      .filter((a) => a.amount > 0)
    if (allocations.length === 0) continue
    const amount = allocations.reduce((s, a) => s + a.amount, 0)
    await call('POST', '/customer-receipts', {
      customerId, amount, bizDate: day(1 + rnd(3)), remark: '演示回款（部分核销）', allocations,
    }).then(() => { receipts++; receivedFen += amount }, (e) => note(`收款 客户 ${customerId}`, e))
  }

  const pay = unwrap(await call('GET', '/supplier-payables?page=1&size=200'))
    .filter((p) => Number(p.balance) > 0 && p.status !== 'PAID' && p.status !== 'SETTLED')
  const bySupplier = new Map()
  for (const p of pay) {
    const k = Number(p.supplierId)
    if (!bySupplier.has(k)) bySupplier.set(k, [])
    bySupplier.get(k).push(p)
  }
  let si = 0
  for (const [supplierId, rows] of bySupplier) {
    if (si++ >= 10) break
    const allocations = rows.slice(0, 2)
      .map((p) => ({ payableId: Number(p.id), amount: Math.round(Number(p.balance) * 0.6) }))
      .filter((a) => a.amount > 0)
    if (allocations.length === 0) continue
    const amount = allocations.reduce((s, a) => s + a.amount, 0)
    await call('POST', '/supplier-payments', {
      supplierId, amount, bizDate: day(1 + rnd(3)), remark: '演示付款（部分核销）', allocations,
    }).then(() => { payments++; paidFen += amount }, (e) => note(`付款 供应商 ${supplierId}`, e))
  }

  return { receipts, payments, receivedFen, paidFen }
}

function note(where, err) {
  // 单笔失败不中断整批：让其余单据继续，最后统一列出来
  failed.push(`${where} -> ${err.message}`)
}

/** 造完自己报数：这些正是界面上会被看的数字，对不上就是 seed 的问题。 */
async function verify() {
  console.log('\n── 与界面对账 ──')

  const dash = await call('GET', '/dashboard/summary').catch(() => null)
  if (dash?.kpis) {
    // kpis 字段集以后还会变，这里直接印实际返回的键，不预先假设字段名
    const pairs = Object.entries(dash.kpis).map(([k, v]) => `${k}=${v}`)
    console.log(`工作台 KPI：${pairs.join('  ')}`)
  }

  // 财务侧金额口径是"元"（numeric(18,2)），与销售/采购接口的"分"不同，不能再除 100
  const period = day(0).slice(0, 7)
  const bs = await call('GET', `/fin/reports/balance-sheet?period=${period}`).catch(() => null)
  if (bs) {
    console.log(
      `资产负债表 ${period}：资产 ${fix(bs.assetTotal)} / 负债 ${fix(bs.liabilityTotal)} / 权益 ${fix(bs.equityTotal)} ` +
        `→ 差额 ${fix(bs.diff)}，配平=${bs.balanced}`,
    )
  }

  const vouchers = await call('GET', '/fin/vouchers?page=1&size=1').catch(() => null)
  console.log(`自动生成凭证：${vouchers?.total ?? 0} 张`)

  // 收付款核销的下游效果：现金日记账与资金余额。没核销时这两处恒为 0，
  // 财务首页四个卡片就只有一格有数，看起来像半成品。
  const ov = await call('GET', '/fin/overview').catch(() => null)
  if (ov) {
    // 真实结构是 cards.{fundsBalance,receivableTotal,payableTotal,monthInflow,monthOutflow}
    // —— 字段名以实测为准，不要预先猜
    const c = ov.cards ?? {}
    console.log(`财务首页：资金余额 ${fix(c.fundsBalance)} / 应收 ${fix(c.receivableTotal)} / 应付 ${fix(c.payableTotal)} / 本月收 ${fix(c.monthInflow)} 支 ${fix(c.monthOutflow)}`)
    console.log(`过账凭证 ${ov.postedVoucherCount} 张，其中本期 ${ov.periodVoucherCount} 张`)
  }
  const bySource = await call('GET', '/fin/vouchers?page=1&size=200').catch(() => null)
  if (bySource?.items) {
    const tally = {}
    for (const v of bySource.items) tally[v.sourceType] = (tally[v.sourceType] ?? 0) + 1
    console.log(`凭证来源分布：${Object.entries(tally).map(([k, n]) => `${k}=${n}`).join('  ')}` +
      `（凭证总数 ${bySource.total ?? '?'}，此处只统计前 200 条）`)
  }
  const journals = unwrap(await call('GET', '/fin/cash-journal?page=1&size=5').catch(() => []))
  console.log(`现金日记账流水：${journals.length} 条`)

  const trend = await call('GET', `/reports/purchase?from=${day(84)}&to=${day(0)}`).catch(() => null)
  const sales = await call('GET', `/reports/sales?from=${day(84)}&to=${day(0)}`).catch(() => null)
  console.log(`进货报表行数 ${(trend?.items ?? []).length} / 出货报表行数 ${(sales?.items ?? []).length}`)

  const profit = await call('GET', `/reports/profit?from=${day(84).slice(0, 7)}&to=${day(0).slice(0, 7)}`).catch(() => null)
  const costRows = await call('GET', `/cost/monthly?period=${day(0).slice(0, 7)}`).catch(() => null)
  console.log(`毛利报表行数 ${(profit?.items ?? []).length} / 当月成本池 ${(costRows?.items ?? costRows ?? []).length}`)

  if (failed.length > 0) {
    console.log(`\n失败 ${failed.length} 笔（不影响其余数据）：`)
    for (const f of failed.slice(0, 12)) console.log('  ' + f)
    if (failed.length > 12) console.log(`  ...另 ${failed.length - 12} 条`)
  }
  const host = (process.env.ERP_WEB ?? 'http://127.0.0.1:3030').replace(/\/$/, '')
  console.log(`\n打开 ${host}/ 看工作台 / 报表中心 / 数据大屏 / 财务中心。` +
    `（3000 若被 Windows 保留段 2904-3003 占了，dev server 就在 3030）`)
}

const fix = (v) => Number(v ?? 0).toFixed(2)

main().catch((e) => {
  console.error('中止：', e.message)
  process.exitCode = 1
})
