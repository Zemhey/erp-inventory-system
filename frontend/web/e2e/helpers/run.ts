import { readFileSync } from 'node:fs'
import { runFile } from './paths'

export interface RunInfo {
  runId: string
  viewerUsername: string
  /** FINANCE 临时账号（结算/报表的权限边界用例需要"能结算但不能开单"的账号） */
  financeUsername: string
  startedAt: string
  baseline: Record<string, string>
}

/**
 * 把磁盘上的 run.json 归一化成当前 schema。
 *
 * ★ 为什么必须做：`financeUsername` 是 M2-S6 才加的字段，而上一轮被强杀的运行
 * 会留下**旧格式**的 run.json（字段缺失 ≠ 空串）。缺省直接喂给清理 SQL 会在
 * `undefined.replaceAll(...)` 上崩，症状是"改了 harness 之后 E2E 再也跑不起来"，
 * 而且只有被强杀过的那台机器会复现。空串的语义 harness 早已定义：
 * "这一轮没有这个账号"，所以缺字段补成空串即可，非法名字仍由 assertSafeRun 拦下。
 */
export function normalizeRun(raw: Partial<RunInfo>): RunInfo {
  return {
    runId: raw.runId ?? '',
    viewerUsername: typeof raw.viewerUsername === 'string' ? raw.viewerUsername : '',
    financeUsername: typeof raw.financeUsername === 'string' ? raw.financeUsername : '',
    startedAt: raw.startedAt ?? '',
    baseline: raw.baseline ?? {},
  }
}

let cached: RunInfo | null = null

export function readRunInfo(): RunInfo {
  if (cached) return cached
  try {
    cached = normalizeRun(JSON.parse(readFileSync(runFile, 'utf8')) as Partial<RunInfo>)
    return cached
  } catch (error) {
    throw new Error(`未找到 E2E 运行信息（${runFile}）。请通过 npm run e2e 启动。`, { cause: error })
  }
}

/** 本次运行的唯一数据前缀，如 E2E-MF3K2P-AB12-06。 */
export function runTag(run: RunInfo, caseId: string): string {
  return `${run.runId}-${caseId}`
}
