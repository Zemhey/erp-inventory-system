#!/usr/bin/env node
/**
 * 状态机镜像校验：前端 `utils/documentStatus.ts` ↔ 后端 Java 源码。
 *
 * ★ 为什么需要它（不是"锦上添花的脚本"）：
 *   M3 计划书 §3.2 的正确做法是"前端照抄后端的状态→动作表"。但照抄是一次性动作：
 *   后端三个月后加一个动作/改一个权限码，前端这张表不会自己跟着变，
 *   而症状是**用户点了按钮收到 403/409** —— 界面上明明有按钮，就是不work。
 *   这类漂移靠 code review 抓不住（两份文件在同一个仓库但从不同时被打开）。
 *
 *   所以这里把它变成一条**可执行断言**：直接解析 Java 源码，逐项比对。
 *   与 M1 的 ArchUnit 规则自检同一个思路 —— 门禁本身也要被验证。
 *
 * 校验四件事：
 *   ① 七态转移表与 `DocumentStatus.java` 完全一致；
 *   ② 终态 / 可执行标记与 Java 的 isTerminal() / isExecutable() 一致；
 *   ③ 每个动作的权限码与 Controller 上的 @RequirePermission 逐字一致；
 *   ④ 界面允许的动作都真实存在：有对应的 HTTP 子路径，且转换在状态机里合法。
 *
 * 用法：
 *   node scripts/check-status-parity.mjs        # 校验
 *   node scripts/check-status-parity.mjs --json # 机器可读结果（CI 用）
 */

import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const webRoot = join(here, '..')
const repoRoot = join(webRoot, '..', '..')
const javaRoot = join(repoRoot, 'backend', 'server', 'src', 'main', 'java', 'com', 'erp')

const results = []
function check(name, ok, detail) {
  results.push({ name, ok, detail })
}

function read(p) {
  try {
    return readFileSync(p, 'utf8')
  } catch (err) {
    throw new Error(`读不到文件：${p}（${err.code}）\n请确认在仓库内运行；本脚本依赖后端源码作为事实源。`)
  }
}

// ─────────────────────────── ① 解析 Java 状态机 ───────────────────────────

const statusJava = read(join(javaRoot, 'shared', 'document', 'DocumentStatus.java'))

function parseJavaTransitions(src) {
  const out = {}
  const re = /m\.put\(\s*([A-Z_]+)\s*,\s*(?:EnumSet\.of\(([^)]*)\)|EnumSet\.noneOf\()/g
  let m
  while ((m = re.exec(src)) !== null) {
    const from = m[1]
    const list = (m[2] ?? '')
      .split(',')
      .map((s) => s.trim())
      .filter((s) => /^[A-Z_]+$/.test(s))
    out[from] = list
  }
  return out
}

const javaTransitions = parseJavaTransitions(statusJava)
const javaStatuses = Object.keys(javaTransitions)

// isExecutable() 的 Java 实现里写了哪几个状态（解析 `this == X || this == Y`）
const execMatch = statusJava.match(/isExecutable\(\)\s*\{\s*return([^;]+);/)
const javaExecutable = (execMatch?.[1] ?? '')
  .split('||')
  .map((s) => s.replace(/this\s*==\s*/, '').trim())
  .filter((s) => /^[A-Z_]+$/.test(s))
const javaTerminal = javaStatuses.filter((s) => javaTransitions[s].length === 0)

// ─────────────────────────── ② 解析 Controller 的动作与权限 ───────────────────────────

function parseControllerActions(file, kind) {
  const src = read(file)
  const out = {}
  // @PostMapping("/{id}/submit") 紧跟着 @RequirePermission("pur:submit")
  const re = /@PostMapping\("([^"]+)"\)\s*@RequirePermission\("([^"]+)"\)/g
  let m
  while ((m = re.exec(src)) !== null) {
    const path = m[1]
    const permission = m[2]
    const seg = path.split('/').pop()
    if (!seg || seg.startsWith('{')) continue
    out[seg] = { path: `/api/v1/${kind === 'PURCHASE' ? 'purchase-orders' : 'sales-orders'}${path}`, permission }
  }
  return out
}

const controllerActions = {
  PURCHASE: parseControllerActions(join(javaRoot, 'pur', 'api', 'PurchaseOrderController.java'), 'PURCHASE'),
  SALES: parseControllerActions(join(javaRoot, 'sal', 'api', 'SalesOrderController.java'), 'SALES'),
}

// ─────────────────────────── ③ 加载前端镜像表 ───────────────────────────

const tsUrl = pathToFileURL(join(webRoot, 'src', 'utils', 'documentStatus.ts')).href
const ts = await import(tsUrl)

// ─────────────────────────── ④ 逐项比对 ───────────────────────────

// ① 状态集合
check(
  '状态集合一致',
  JSON.stringify([...ts.DOCUMENT_STATUSES].sort()) === JSON.stringify([...javaStatuses].sort()),
  `Java: ${javaStatuses.join(', ')} ｜ TS: ${ts.DOCUMENT_STATUSES.join(', ')}`,
)

// ② 转移表
for (const status of javaStatuses) {
  const java = [...(javaTransitions[status] ?? [])].sort()
  const front = [...((ts.ALLOWED_TRANSITIONS[status] ?? []))].sort()
  check(
    `转移表 ${status}`,
    JSON.stringify(java) === JSON.stringify(front),
    `Java: [${java.join(', ')}] ｜ TS: [${front.join(', ')}]`,
  )
}

// ③ 终态 / 可执行
for (const status of javaStatuses) {
  const meta = ts.STATUS_META[status]
  const shouldTerminal = javaTerminal.includes(status)
  const shouldExecutable = javaExecutable.includes(status)
  check(
    `终态标记 ${status}`,
    meta ? meta.terminal === shouldTerminal : false,
    `Java isTerminal=${shouldTerminal} ｜ TS terminal=${meta?.terminal}`,
  )
  check(
    `可执行标记 ${status}`,
    meta ? meta.executable === shouldExecutable : false,
    `Java isExecutable=${shouldExecutable} ｜ TS executable=${meta?.executable}`,
  )
}

// ④ 动作的权限码与接口存在性
for (const kind of ts.DOCUMENT_KINDS) {
  const ctrl = controllerActions[kind]
  for (const [key, meta] of Object.entries(ts.ACTION_META)) {
    if (!key.startsWith(`${kind}:`)) continue
    const action = key.split(':')[1]
    const backend = ctrl[action]
    if (!backend) {
      check(`接口存在 ${key}`, false, `后端 ${kind} Controller 没有 /${action} 子路径`)
      continue
    }
    check(`权限码 ${key}`, backend.permission === meta.permission, `Java: ${backend.permission} ｜ TS: ${meta.permission}`)
  }
}

// ⑤ 界面允许的动作：必须存在接口，且目标状态在状态机里合法
const ACTION_TARGET = {
  submit: ['SUBMITTED'],
  approve: ['APPROVED'],
  reject: ['REJECTED'],
  void: ['VOIDED'],
  receive: ['PARTIAL', 'COMPLETED'],
  ship: ['PARTIAL', 'COMPLETED'],
}

for (const kind of ts.DOCUMENT_KINDS) {
  for (const status of ts.DOCUMENT_STATUSES) {
    for (const action of ts.ALLOWED_ACTIONS[kind][status]) {
      const targets = ACTION_TARGET[action] ?? []
      const legal = targets.some((t) => (javaTransitions[status] ?? []).includes(t))
      check(
        `动作合法 ${kind}/${status}/${action}`,
        legal,
        `目标 [${targets.join('/')}] 不在 ${status} 的后继 [${javaTransitions[status].join(', ')}] 中`,
      )
    }
  }
}

// ⑥ 反向：Java 允许的每个转移，是否都至少有一个界面动作覆盖（否则用户只能等）
//     COMPLETED / VOIDED 是终态，允许为空。
//
//     ★ 有些转移**状态机允许、但业务层会拒绝**，或**后端没暴露 HTTP 动作**。
//       这类缺口必须显式登记在 KNOWN_GAPS 里并写明理由 ——
//       而不是把整个检查注释掉。区别在于：
//       登记过的缺口会在下面的 ⑦ 里反向校验，一旦后端补了接口（缺口消失），
//       脚本会报"登记已过期"，逼迫人回来删掉这条豁免。
//       一条会过期的豁免清单，才是可信的；一条永远绿的豁免清单等于没有检查。
const KNOWN_GAPS = [
  {
    key: 'PURCHASE/PARTIAL->VOIDED',
    reason:
      '状态机允许 PARTIAL→VOIDED，但 PurchaseOrderApplicationService#voidOrder 对 anyReceived() 的单据一律拒绝'
      + '（要求先红冲，FR-PUR-03 属 M2）。PARTIAL 必然意味着已入库，因此该转移在实际业务上不可达，界面不给按钮。',
  },
  {
    key: 'SALES/PARTIAL->VOIDED',
    reason:
      '同进货单：PARTIAL 必然意味着已出货，SalesOrderApplicationService#voidOrder 对 anyShipped() 一律拒绝（需红冲，FR-SAL-04 属 M2）。',
  },
  {
    key: 'PURCHASE/REJECTED->DRAFT',
    reason:
      '状态机允许 REJECTED→DRAFT，但两个 Controller 都没有暴露 reopen/backToDraft 接口。'
      + '没有接口就不能有按钮（否则点了必然 405）。已登记为待办：要么后端补接口，要么界面文案改为"作废后重开"。',
  },
  {
    key: 'SALES/REJECTED->DRAFT',
    reason: '同上，出货单 Controller 同样没有 reopen 接口。',
  },
]

const gapKeys = new Set()
for (const kind of ts.DOCUMENT_KINDS) {
  for (const status of javaStatuses) {
    const next = javaTransitions[status]
    if (next.length === 0) continue
    const covered = new Set(
      ts.ALLOWED_ACTIONS[kind][status].flatMap((a) => ACTION_TARGET[a] ?? []),
    )
    // PARTIAL 自环由 receive/ship 覆盖；DRAFT 由 submit/void 覆盖
    const missing = next.filter((t) => !covered.has(t) && t !== status)
    const unknown = missing.filter((t) => !KNOWN_GAPS.some((g) => g.key === `${kind}/${status}->${t}`))
    for (const t of missing) gapKeys.add(`${kind}/${status}->${t}`)
    check(
      `转移可达 ${kind}/${status}`,
      unknown.length === 0,
      unknown.length ? `状态机允许到 [${unknown.join(', ')}]，但界面没有对应动作，且未登记为已知缺口` : '',
    )
  }
}

// ⑦ 豁免清单不得过期：登记了但缺口已不存在（后端补了接口）→ 必须删掉这条豁免
for (const gap of KNOWN_GAPS) {
  check(
    `豁免仍有效 ${gap.key}`,
    gapKeys.has(gap.key),
    '该缺口已不存在（后端可能补了接口或改了规则）—— 请删除 KNOWN_GAPS 里的这条豁免，并考虑补上按钮',
  )
}

// ─────────────────────────── 输出 ───────────────────────────

const failed = results.filter((r) => !r.ok)

if (process.argv.includes('--json')) {
  console.log(JSON.stringify({ ok: failed.length === 0, total: results.length, failed }, null, 2))
} else {
  for (const r of results) {
    console.log(`${r.ok ? '✓' : '✗'} ${r.name}${r.ok || !r.detail ? '' : `\n    ${r.detail}`}`)
  }
  console.log('')
  if (failed.length) {
    console.log(`✗ 状态机镜像漂移：${failed.length}/${results.length} 项不一致`)
    console.log('  修法：改前端 src/utils/documentStatus.ts 使其与后端一致；')
    console.log('        若确实是后端先改了规则，请同时更新计划书 §3.2 的表。')
  } else {
    console.log(`✓ 状态机镜像一致（${results.length} 项检查全部通过）`)
  }
}

process.exit(failed.length ? 1 : 0)
