import { readFileSync } from 'node:fs'
import { adminStorageFile } from './paths'

interface StorageState {
  origins?: { origin: string; localStorage: { name: string; value: string }[] }[]
}

/** 从登录 setup 产出的 storageState 里取 token，供测试安排数据使用（不在用例里硬编码）。 */
export function readAdminToken(): string {
  const state = JSON.parse(readFileSync(adminStorageFile, 'utf8')) as StorageState
  for (const origin of state.origins ?? []) {
    const token = origin.localStorage?.find((item) => item.name === 'erp.token')?.value
    if (token) return token
  }
  throw new Error(`storageState 中没有 erp.token（${adminStorageFile}）；auth.setup 可能未成功`)
}
