import path from 'node:path'
import { fileURLToPath } from 'node:url'

/** frontend/web（helpers 上两级） */
const webRoot = fileURLToPath(new URL('../../', import.meta.url))

/** E2E 运行期产物：storageState、run.json、trace、报告；已被 .gitignore 忽略。 */
export const artifactsDir = path.join(webRoot, '.e2e-artifacts')
export const runFile = path.join(artifactsDir, 'run.json')
export const cleanupFile = path.join(artifactsDir, 'cleanup.json')
export const adminStorageFile = path.join(artifactsDir, 'admin-storage.json')
