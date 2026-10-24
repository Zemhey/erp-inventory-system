/**
 * 审计快照相向对照（before_json ↔ after_json）。
 *
 * ★ 为什么审计页的价值在这里而不是列表页：
 *   审计要回答的是"他把它从什么改成了什么"。把两段 JSON 原样贴在屏幕上，
 *   用户得自己逐字符比对 —— 一份出货单响应有几十个字段、明细行还嵌数组，
 *   人眼比对既慢又不可靠，等于没做这个功能。所以这里把两份快照摊平成
 *   字段路径，逐个标注 新增 / 删除 / 修改 / 未变，让差异自己跳出来。
 *
 * ★ 三条不容妥协的口径：
 *   1. **解析失败不静默**：不是合法 JSON 时按原文显示（rawText），
 *      而不是返回"无差异"。快照是审计证据，"我读不懂"绝不能显示成"没变化"。
 *   2. **缺失要说明，但不能替后端下结论**：before 为空有两种成因 —— 该资源此前不存在
 *      （首次创建），或动作路径不带资源 id（`/submit`、`/approve` 这类末段是动作词，
 *      取不到上一次快照）。界面只说"无变更前快照"，具体是哪一种交给 traceId 与动作码判断；
 *      把它写成"首次创建"就会把单据流程的正常留痕说成缺陷。
 *   3. **值只转字符串，不做类型推断**：`1` 与 `'1'` 在 JSON 文本里都是 1，
 *      界面不参与业务判断（计划书 §0：前端不算数、不推断）。
 *
 * 纯函数、零依赖：便于界面单测，也让 E2E 能直接对着 DOM 断言分类结果。
 */

export type SnapshotFieldState = 'added' | 'removed' | 'changed' | 'unchanged'

export interface SnapshotField {
  /** 字段路径，如 `lines[0].qty`；根级字段就是字段名 */
  path: string
  /** 变更前的值（该路径在 before 里不存在时为 null） */
  before: string | null
  /** 变更后的值（该路径在 after 里不存在时为 null） */
  after: string | null
  state: SnapshotFieldState
}

export interface SnapshotDiff {
  /** 按路径顺序（before 的键序在前，after 独有的追加在后） */
  fields: SnapshotField[]
  counts: Record<SnapshotFieldState, number>
  /**
   * before 侧没有可对照的快照（原文为空）。
   * 两种成因界面分不出来：① 该资源此前不存在（首次创建）；
   * ② 动作路径不带资源 id（如 `/submit`、`/approve`），后端取不到上一次快照。
   */
  beforeMissing: boolean
  /** after 侧没有快照片段：超大响应不记录，或这次是失败请求 */
  afterMissing: boolean
  /** 任一侧不是可摊平的 JSON 对象/数组时，给出原文，避免"看起来没变更" */
  rawText: { before: string | null; after: string | null }
  /** 是否存在差异（只有 unchanged 或两侧都缺时是 false） */
  hasDiff: boolean
}

const EMPTY_COUNTS: Record<SnapshotFieldState, number> = {
  added: 0,
  removed: 0,
  changed: 0,
  unchanged: 0,
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function childPath(prefix: string, key: string): string {
  return prefix ? `${prefix}.${key}` : key
}

/** 标量 → 显示文本。null/undefined 都显示成 null，与 JSON 语义一致。 */
function scalarText(value: unknown): string {
  if (value === null || value === undefined) return 'null'
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  return JSON.stringify(value)
}

/**
 * 把一份快照摊平成 `路径 → 值` 表（保留出现顺序，Map 天然满足）。
 *
 * 空对象/空数组必须自己占一行：否则 `{"ext": {}}` 与 `{"ext": {"a":1}}`
 * 的差异会退化成"少了一个 a"，而真相是 ext 从空变成了有内容。
 */
function flatten(value: unknown, prefix: string, out: Map<string, string>): void {
  if (Array.isArray(value)) {
    if (value.length === 0) out.set(prefix, '[]')
    value.forEach((item, index) => flatten(item, `${prefix}[${index}]`, out))
    return
  }
  if (isRecord(value)) {
    const keys = Object.keys(value)
    if (keys.length === 0) out.set(prefix, '{}')
    for (const key of keys) flatten(value[key], childPath(prefix, key), out)
    return
  }
  out.set(prefix, scalarText(value))
}

/**
 * 解析并摊平。返回 null 表示"这一侧没有可摊平的快照"：
 * 原文（若有）交给调用方按 rawText 显示，不假装成空差异。
 */
function flattenSide(raw: string | null): { fields: Map<string, string> | null; rawText: string | null } {
  if (raw === null || raw === undefined || raw.trim() === '') {
    return { fields: null, rawText: null }
  }
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return { fields: null, rawText: raw }
  }
  if (!isRecord(parsed) && !Array.isArray(parsed)) {
    // 标量快照：不是审计关心的形态（后端只给对象拍快照），但仍要能看见原文
    return { fields: null, rawText: raw }
  }
  const fields = new Map<string, string>()
  flatten(parsed, '', fields)
  return { fields, rawText: null }
}

/**
 * 两个快照的字段级对照。
 *
 * @param beforeJson 审计的 before_json 原文
 * @param afterJson 审计的 after_json 原文
 */
export function diffSnapshots(beforeJson: string | null, afterJson: string | null): SnapshotDiff {
  const before = flattenSide(beforeJson)
  const after = flattenSide(afterJson)
  const beforeMissing = before.fields === null && before.rawText === null
  const afterMissing = after.fields === null && after.rawText === null

  const paths: string[] = []
  const seen = new Set<string>()
  for (const side of [before.fields, after.fields]) {
    if (!side) continue
    for (const key of side.keys()) {
      if (seen.has(key)) continue
      seen.add(key)
      paths.push(key)
    }
  }

  const fields: SnapshotField[] = paths.map((path) => {
    const from = before.fields?.get(path) ?? null
    const to = after.fields?.get(path) ?? null
    let state: SnapshotFieldState
    if (from === null && to !== null) state = 'added'
    else if (from !== null && to === null) state = 'removed'
    else if (from !== to) state = 'changed'
    else state = 'unchanged'
    return { path, before: from, after: to, state }
  })

  const counts: Record<SnapshotFieldState, number> = { ...EMPTY_COUNTS }
  for (const field of fields) counts[field.state] += 1

  const hasDiff = counts.added + counts.removed + counts.changed > 0

  return {
    fields,
    counts,
    beforeMissing,
    afterMissing,
    rawText: { before: before.rawText, after: after.rawText },
    hasDiff,
  }
}

/** 差异概览文本，用于列表行的"变更"列与抽屉标题。 */
export function diffSummary(diff: SnapshotDiff): string {
  if (diff.beforeMissing && diff.afterMissing) return '两侧都没有快照片段'
  // 只说"没有快照"，不说"首次创建"：before 为空有两种成因（首次创建 / 动作路径不带资源 id），
  // 界面分不出来，就不能替后端下结论。
  if (diff.beforeMissing) return '无变更前快照'
  if (diff.afterMissing) return '无变更后快照'
  if (!diff.hasDiff) return '字段无变化'
  const parts: string[] = []
  if (diff.counts.changed) parts.push(`修改 ${diff.counts.changed}`)
  if (diff.counts.added) parts.push(`新增 ${diff.counts.added}`)
  if (diff.counts.removed) parts.push(`移除 ${diff.counts.removed}`)
  return parts.join(' · ')
}

/** 原文美化：解析失败时原样返回，不做"看起来像 JSON"的猜测。 */
export function prettyJson(raw: string | null): string {
  if (raw === null || raw === undefined || raw.trim() === '') return ''
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}
