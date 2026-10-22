import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { cleanupFile, runFile } from './helpers/paths'
import type { RunInfo } from './helpers/run'
import { cleanupRun, readBaseline } from './helpers/db-ops'

/**
 * 按依赖顺序删除本次 runId 造出的全部数据与临时 VIEWER。
 *
 * 这里刻意做成“硬失败”：清理后只要还有前缀残留、或业务表计数回不到基线，
 * 就让整次 E2E 变红 —— 否则“测试全绿但库里一堆垃圾”会被当成正常。
 */
export default async function globalTeardown(): Promise<void> {
  if (!existsSync(runFile)) return
  if (process.env.E2E_KEEP_DATA === '1') {
    console.warn('[e2e] E2E_KEEP_DATA=1：跳过清理，下次运行 30 分钟后会自动清理该 runId')
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

  const leftovers = Object.entries(remaining).filter(([, n]) => n > 0)
  if (leftovers.length) {
    throw new Error(
      `[e2e] 清理后仍有前缀残留：${leftovers.map(([k, n]) => `${k}=${n}`).join('，')}`,
    )
  }

  if (after.baseline_reservation !== run.baseline.baseline_reservation) {
    throw new Error(
      `[e2e] 既有预占 e2e-reserve-001 数量变化：` +
        `${run.baseline.baseline_reservation} -> ${after.baseline_reservation}`,
    )
  }

  const changed = Object.keys(run.baseline).filter(
    (key) => after[key] !== run.baseline[key],
  )
  if (changed.length) {
    throw new Error(
      `[e2e] 清理后业务表计数未回到基线：` +
        changed.map((key) => `${key} ${run.baseline[key]} -> ${after[key]}`).join('，'),
    )
  }

  console.log(`[e2e] 清理完成 ${run.runId}：前缀残留 0，业务表计数已回到基线`)
}
