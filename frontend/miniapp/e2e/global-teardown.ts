import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { cleanupFile, runFile } from './helpers/paths'
import { cleanupRun, readBaseline } from './helpers/db'
import type { RunInfo } from './helpers/run'

/**
 * 按本轮前缀清理，并做两条硬校验：
 * 1. 前缀残留必须为 0；
 * 2. 业务表计数必须回到 run.baseline。
 * 任一不满足就让整次 E2E 变红 —— "测试全绿但库里一堆垃圾"不算通过。
 */
export default async function globalTeardown(): Promise<void> {
  if (!existsSync(runFile)) return
  if (process.env.E2E_KEEP_DATA === '1') {
    console.warn('[e2e] E2E_KEEP_DATA=1：跳过清理，下次运行开始时会先清理该 runId')
    return
  }

  const run = JSON.parse(readFileSync(runFile, 'utf8')) as RunInfo
  let failure: Error | null = null
  let summary = ''
  let remaining: Record<string, number> = {}
  let after: Record<string, number> = {}

  try {
    ;({ summary, remaining } = cleanupRun(run))
    after = readBaseline()
  } catch (error) {
    failure = error as Error
  }

  writeFileSync(
    cleanupFile,
    JSON.stringify(
      {
        runId: run.runId,
        cleanedAt: new Date().toISOString(),
        ok: !failure,
        summary,
        remaining,
        baseline: run.baseline,
        after,
        error: failure?.message ?? null,
      },
      null,
      2,
    ),
    'utf8',
  )

  if (failure) throw failure

  const leftovers = Object.entries(remaining).filter(([, count]) => count > 0)
  if (leftovers.length) {
    throw new Error(
      `[e2e] 清理后仍有前缀残留：${leftovers.map(([key, count]) => `${key}=${count}`).join('，')}`,
    )
  }

  const changed = Object.keys(run.baseline).filter((key) => after[key] !== run.baseline[key])
  if (changed.length) {
    throw new Error(
      `[e2e] 清理后业务表计数未回到基线：` +
        changed
          .map((key) => `${key} ${run.baseline[key]} -> ${after[key] ?? 'missing'}`)
          .join('，'),
    )
  }

  console.log(`[e2e] 本轮删除：${summary}`)
  console.log(`[e2e] 清理完成 ${run.runId}：前缀残留 0，业务表计数已回到基线`)
}
