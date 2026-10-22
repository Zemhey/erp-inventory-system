import { querySql } from './db'
import { baselineSql, cleanupSql, createViewerSql, remainingSql } from './sql'
import type { RunInfo } from './run'

function parseCounts(raw: string): Record<string, number> {
  const result: Record<string, number> = {}
  for (const line of raw.split('\n')) {
    const [key, value] = line.split('\t')
    if (!key || value === undefined) continue
    const n = Number(value)
    if (Number.isFinite(n)) result[key.trim()] = n
  }
  return result
}

export function readBaseline(): Record<string, number> {
  return parseCounts(querySql(baselineSql()))
}

function readRemaining(run: RunInfo): Record<string, number> {
  return parseCounts(querySql(remainingSql(run)))
}

export function createViewerUser(viewerUsername: string, adminUsername: string): void {
  const raw = querySql(createViewerSql(viewerUsername, adminUsername))
  const last = raw.split('\n').filter(Boolean).pop() ?? ''
  const [users, roles] = last.split('\t').map(Number)
  if (!users) {
    throw new Error(
      `创建临时 VIEWER 失败：找不到用于复制密码哈希的账号「${adminUsername}」。` +
        '请先确认开发账号已由 scripts/seed-dev-user.ps1 创建。',
    )
  }
  if (!roles) {
    throw new Error(`临时 VIEWER「${viewerUsername}」未获得 VIEWER 角色，请检查 app_roles 种子数据。`)
  }
}

/** 删除本次 runId 的全部数据，返回清理前各类行数（供执行记录引用）。 */
export function cleanupRun(run: RunInfo): { summary: string; remaining: Record<string, number> } {
  const summary = querySql(cleanupSql(run))
  return { summary, remaining: readRemaining(run) }
}
