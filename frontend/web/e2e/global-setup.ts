import { randomBytes } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { artifactsDir, runFile } from './helpers/paths'
import type { RunInfo } from './helpers/run'
import { normalizeRun } from './helpers/run'
import {
  addTenantMembership,
  cleanupRun,
  createTempUser,
  readBaseline,
  resetReservedPeriods,
} from './helpers/db-ops'

const STALE_AFTER_MS = 30 * 60 * 1000

/**
 * 生成一次运行的唯一前缀，并创建两个临时账号（密码哈希复制自管理员）：
 * VIEWER 用于"只能看"，FINANCE 用于"能结算但不能开单"。所有造数都带 runId 前缀，
 * teardown 只按这个前缀清理（保留账期除外，见 helpers/sql.ts 顶部说明）。
 */
export default async function globalSetup(): Promise<void> {
  mkdirSync(artifactsDir, { recursive: true })

  // 上一次运行若被强杀，run.json 会留下；超过 30 分钟视为遗留并清掉。
  if (existsSync(runFile)) {
    const previous = normalizeRun(JSON.parse(readFileSync(runFile, 'utf8')) as Partial<RunInfo>)
    const age = Date.now() - Date.parse(previous.startedAt)
    if (Number.isFinite(age) && age > STALE_AFTER_MS) {
      try {
        cleanupRun(previous)
        console.log(`[e2e] 已清理遗留运行 ${previous.runId}`)
      } catch (error) {
        throw new Error(
          `清理上一次遗留运行 ${previous.runId} 失败，继续运行会污染基线。` +
            `请先修复后重试：${(error as Error).message}`,
        )
      }
    }
  }

  // 先清保留账期再取基线：上一轮（或别的泳道）残留在 2031 的锁账记录如果进基线，
  // 会让"计数回到基线"这条硬校验变成"必须留下别人的垃圾"。
  const wiped = resetReservedPeriods()
  console.log(
    `[e2e] 保留账期已清空：日清行 ${wiped.daily ?? 0} 条、账期行 ${wiped.periods ?? 0} 个`,
  )

  const runId = `E2E-${Date.now().toString(36).toUpperCase()}-${randomBytes(2).toString('hex').toUpperCase()}`
  const viewerUsername = `viewer-${runId.toLowerCase()}`
  const financeUsername = `finance-${runId.toLowerCase()}`
  const baseline = readBaseline()
  const info: RunInfo = {
    runId,
    viewerUsername,
    financeUsername,
    startedAt: new Date().toISOString(),
    baseline,
  }
  // 先落盘再建账号：即使建账号中途失败，teardown 也知道要清理哪些用户名。
  writeFileSync(runFile, JSON.stringify(info, null, 2), 'utf8')

  const admin = process.env.E2E_USERNAME ?? 'admin'
  try {
    createTempUser(viewerUsername, 'VIEWER', admin)
    createTempUser(financeUsername, 'FINANCE', admin)
    // B1：VIEWER 同时是 demo 租户的 VIEWER —— 只有真的多租户成员，
    // 顶栏才应出现切换器；单租户的 FINANCE 用作"不出现切换器"的对照。
    addTenantMembership(viewerUsername, 'demo', 'VIEWER')
  } catch (error) {
    try {
      cleanupRun(info)
    } catch (cleanupError) {
      console.error('[e2e] 建号失败后的回滚也失败了：', cleanupError)
    }
    throw error
  }

  console.log(`[e2e] runId=${runId}  VIEWER=${viewerUsername}  FINANCE=${financeUsername}`)
}
