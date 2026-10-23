import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { randomBytes } from 'node:crypto'
import { artifactsDir, runFile } from './helpers/paths'
import { cleanupRun, ensureAdmin, readBaseline } from './helpers/db'
import type { RunInfo } from './helpers/run'

/**
 * 生成一轮唯一前缀、确保 admin 可登录、记录业务表基线。
 *
 * 上一轮若被强杀，run.json 会残留 —— 这里**无条件先清理**再取基线，
 * 否则上一轮的数据会被算进基线，teardown 的"回到基线"就永远不成立。
 */
export default async function globalSetup(): Promise<void> {
  mkdirSync(artifactsDir, { recursive: true })

  ensureAdmin()

  if (existsSync(runFile)) {
    const previous = JSON.parse(readFileSync(runFile, 'utf8')) as RunInfo
    try {
      const { summary, remaining } = cleanupRun(previous)
      console.log(`[e2e] 已清理上一轮残留 ${previous.runId}：${summary}`)
      // 清不干净就不能取基线：否则上一轮的数据会被算进基线，
      // teardown 的"回到基线"从这一刻起已经不可能成立。
      const leftovers = Object.entries(remaining).filter(([, count]) => count > 0)
      if (leftovers.length) {
        throw new Error(
          `上一轮 ${previous.runId} 仍有残留：${leftovers
            .map(([key, count]) => `${key}=${count}`)
            .join('，')}`,
        )
      }
    } catch (error) {
      throw new Error(
        `清理上一轮 ${previous.runId} 失败，继续运行会污染基线：${(error as Error).message}`,
      )
    }
    rmSync(runFile, { force: true })
  }

  const baseline = readBaseline()
  const runId = `M4B${Date.now().toString(36).toUpperCase()}${randomBytes(2)
    .toString('hex')
    .toUpperCase()}`
  const info: RunInfo = {
    runId,
    prefix: `E2B-${runId}`,
    startedAt: new Date().toISOString(),
    baseline,
  }

  // 先落盘再 seed：即使 seed 中途失败，teardown 也知道该清理哪些前缀
  writeFileSync(runFile, JSON.stringify(info, null, 2), 'utf8')
  console.log(`[e2e] runId=${runId}  prefix=${info.prefix}`)
}
