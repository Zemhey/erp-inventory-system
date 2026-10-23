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

let cached: RunInfo | null = null

export function readRunInfo(): RunInfo {
  if (cached) return cached
  try {
    cached = JSON.parse(readFileSync(runFile, 'utf8')) as RunInfo
    return cached
  } catch (error) {
    throw new Error(`未找到 E2E 运行信息（${runFile}）。请通过 npm run e2e 启动。`, { cause: error })
  }
}

/** 本次运行的唯一数据前缀，如 E2E-MF3K2P-AB12-06。 */
export function runTag(run: RunInfo, caseId: string): string {
  return `${run.runId}-${caseId}`
}
