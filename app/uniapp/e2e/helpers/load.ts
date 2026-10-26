import { readFileSync } from 'node:fs'
import { seedFile } from './paths'
import type { SeedData } from './run'

/** 读取 setup project 写入的本轮造数结果。 */
export function readSeed(): SeedData {
  return JSON.parse(readFileSync(seedFile, 'utf8')) as SeedData
}