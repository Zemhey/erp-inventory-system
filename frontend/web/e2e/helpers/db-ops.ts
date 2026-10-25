import { querySql } from './db'
import {
  addTenantMembershipSql,
  baselineSql,
  cleanupSql,
  createUserSql,
  remainingSql,
  reservedPeriodResetSql,
} from './sql'
import type { RunInfo } from './run'

/**
 * psql 的 `-t -A -F <TAB>` 输出解析成 key → 值。
 *
 * 值一律保持字符串：基线里除了行数还有 `..._reservation_state` 这种文本指纹
 * （形如 `ACTIVE:5`），硬转成数字会因为 NaN 被丢弃而**静默少一个基线键**，
 * 那条校验就形同不存在了。
 */
function parseCounts(raw: string): Record<string, string> {
  const result: Record<string, string> = {}
  for (const line of raw.split('\n')) {
    const [key, value] = line.split('\t')
    if (!key || value === undefined) continue
    result[key.trim()] = value.trim()
  }
  return result
}

export function readBaseline(): Record<string, string> {
  return parseCounts(querySql(baselineSql()))
}

function readRemaining(run: RunInfo): Record<string, string> {
  return parseCounts(querySql(remainingSql(run)))
}

/** 清空保留测试账期（幂等），返回删掉的行数；必须在读取基线之前调用。 */
export function resetReservedPeriods(): Record<string, string> {
  return parseCounts(querySql(reservedPeriodResetSql()))
}

/**
 * 创建临时账号（VIEWER / FINANCE）：复制管理员密码哈希，角色取自既有种子。
 * 同一口令，所以用例里仍用 E2E_PASSWORD 登录，不出现任何 token。
 */
export function createTempUser(username: string, roleCode: string, adminUsername: string): void {
  const raw = querySql(createUserSql(username, roleCode, adminUsername))
  const last = raw.split('\n').filter(Boolean).pop() ?? ''
  const [users, roles] = last.split('\t').map(Number)
  if (!users) {
    throw new Error(
      `创建临时 ${roleCode} 账号失败：找不到用于复制密码哈希的账号「${adminUsername}」。` +
        '请先确认开发账号已由 scripts/seed-dev-user.ps1 创建。',
    )
  }
  if (!roles) {
    throw new Error(`临时账号「${username}」未获得 ${roleCode} 角色，请检查 app_roles 种子数据。`)
  }
}

/**
 * 给临时账号追加第二个租户的成员关系与角色（B1）。
 *
 * 只改本 run 的临时账号；清理仍走 cleanupSql 的用户名清单（删用户时成员关系级联删除）。
 */
export function addTenantMembership(
  username: string,
  tenantCode: string,
  roleCode: string,
): void {
  const raw = querySql(addTenantMembershipSql(username, tenantCode, roleCode))
  const last = raw.split('\n').filter(Boolean).pop() ?? ''
  const [memberships, roles] = last.split('\t').map(Number)
  if (!memberships) {
    throw new Error(
      `追加成员关系失败：账号「${username}」不在租户「${tenantCode}」中（租户种子缺失？）`,
    )
  }
  if (!roles) {
    throw new Error(
      `追加跨租户角色失败：租户「${tenantCode}」缺少角色「${roleCode}」` +
        '（V16 应把演示租户角色复制成与默认租户同构）。',
    )
  }
}

/** 删除本次 runId 的全部数据，返回清理前各类行数（供执行记录引用）。 */
export function cleanupRun(run: RunInfo): { summary: string; remaining: Record<string, string> } {
  const summary = querySql(cleanupSql(run))
  return { summary, remaining: readRemaining(run) }
}
