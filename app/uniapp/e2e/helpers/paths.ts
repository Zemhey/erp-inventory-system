import path from 'node:path'

/**
 * 本包 package.json 没有 "type": "module"（uni-app 构建依赖 CJS 解析），
 * Playwright 会把 e2e 下的 TS 转成 CommonJS，所以这里只能用 __dirname，不能用 import.meta。
 */
export const helpersRoot = __dirname
export const e2eRoot = path.resolve(helpersRoot, '..')
export const miniappRoot = path.resolve(e2eRoot, '..')
export const repoRoot = path.resolve(miniappRoot, '../..')

/** 测试产物全部在本机、已被根 .gitignore 的 `.e2e-artifacts/` 排除。 */
export const artifactsDir = path.join(miniappRoot, '.e2e-artifacts')
export const runFile = path.join(artifactsDir, 'run.json')
export const seedFile = path.join(artifactsDir, 'seed.json')
export const adminStorage = path.join(artifactsDir, 'admin-storage.json')
export const cleanupFile = path.join(artifactsDir, 'cleanup.json')
