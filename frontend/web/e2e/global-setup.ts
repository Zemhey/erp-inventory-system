import { randomBytes } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { artifactsDir, runFile } from './helpers/paths'
import type { RunInfo } from './helpers/run'
import { cleanupRun, createViewerUser, readBaseline } from './helpers/db-ops'

const STALE_AFTER_MS = 30 * 60 * 1000

/**
 * 生成一次运行的唯一前缀，并创建一个临时 VIEWER 账号（密码哈希复制自管理员）。
 * 所有造数都带 runId 前缀，teardown 只按这个前缀清理。
 */
export default async function globalSetup(): Promise<void> {
  mkdirSync(artifactsDir, { recursive: true })

  // 上一次运行若被强杀，run.json 会留下；超过 30 分钟视为遗留并清掉。
  if (existsSync(runFile)) {
    const previous = JSON.parse(readFileSync(runFile, 'utf8')) as RunInfo
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

  const runId = `E2E-${Date.now().toString(36).toUpperCase()}-${randomBytes(2).toString('hex').toUpperCase()}`
  const viewerUsername = `viewer-${runId.toLowerCase()}`
  const baseline = readBaseline()
  const info: RunInfo = {
    runId,
    viewerUsername,
    startedAt: new Date().toISOString(),
    baseline,
  }
  // 先落盘再建账号：即使建账号中途失败，teardown 也知道要清理哪个用户名。
  writeFileSync(runFile, JSON.stringify(info, null, 2), 'utf8')

  try {
    createViewerUser(viewerUsername, process.env.E2E_USERNAME ?? 'admin')
  } catch (error) {
    try {
      cleanupRun(info)
    } catch (cleanupError) {
      console.error('[e2e] 建号失败后的回滚也失败了：', cleanupError)
    }
    throw error
  }

  console.log(`[e2e] runId=${runId}  VIEWER=${viewerUsername}`)
}
