'use strict'
/**
 * 调 frontend/web 的构建，产物落在 frontend/web/dist（由本端本地服务托管）。
 *
 * 本端**不复制一份前端源码、不重写任何业务页面** —— 构建的就是 Web 端本身，
 * 所以业务界面改动会自动带到 PC 端。这是 app/desktop/README.md 的第 1 条硬约束。
 *
 * 用法：
 *   npm run build:web                 # = vue-tsc --noEmit && vite build（与 web 端同口径）
 *   npm run build:web -- --fast       # 只跑 vite build，跳过类型检查（打包冒烟时快很多）
 *   npm run build:web -- --out <dir>  # 换个产物目录（多泳道并行时避免互相覆盖）
 */
const { spawnSync } = require('node:child_process')
const fs = require('node:fs')
const path = require('node:path')
const { REPO_ROOT } = require('../src/config')

const WEB_DIR = path.join(REPO_ROOT, 'frontend', 'web')
const args = process.argv.slice(2)
const fast = args.includes('--fast')
const outIdx = args.indexOf('--out')
const outDir = outIdx >= 0 ? args[outIdx + 1] : null

if (!fs.existsSync(path.join(WEB_DIR, 'package.json'))) {
  console.error(`[build:web] 找不到 ${WEB_DIR}/package.json —— 本脚本假设 app/desktop 仍在仓库内`)
  process.exit(1)
}

// 本仓库的坑：node_modules 是**目录联接（junction）**，在主工作区共享。
// 缺它说明这台机器还没装过 web 依赖 —— 而这**不是**本端该装的地方。
const hasModules = fs.existsSync(path.join(WEB_DIR, 'node_modules'))
if (!hasModules) {
  console.error(
    `[build:web] ${WEB_DIR}\\node_modules 不存在。\n` +
    '  它是主工作区共享的 junction，**不要在 frontend/web 里 npm install**（会破坏别的端）。\n' +
    '  请在主工作区（有 junction 的那个 checkout）装好依赖后，再回来跑本命令；\n' +
    '  或者把别处已经构建好的 dist 指过来：DESKTOP_WEB_DIST=<路径> npm start',
  )
  process.exit(1)
}

const npm = process.platform === 'win32' ? 'npm.cmd' : 'npm'
// 约束：Windows 下把 TEMP 收进仓库内，避免沙箱/中文路径导致的写临时文件失败
const tmp = path.join(WEB_DIR, '.tmp')
fs.mkdirSync(tmp, { recursive: true })

const cmdArgs = fast
  ? ['run', 'build', '--', ...(outDir ? ['--outDir', outDir] : [])]
  : ['run', 'build', ...(outDir ? [] : [])]
// vite build 的参数直接跟在 build 后：`npm run build -- --outDir x`
if (!fast) {
  console.error('[build:web] 注意：完整模式会先跑 vue-tsc --noEmit（慢，但与 web 端 CI 同口径）')
}

const script = fast ? 'vite' : 'build'
const result = fast
  ? spawnSync(npm, ['exec', '--', 'vite', 'build', ...(outDir ? ['--outDir', outDir] : [])], {
      cwd: WEB_DIR,
      stdio: 'inherit',
      env: { ...process.env, TEMP: tmp, TMP: tmp },
      shell: process.platform === 'win32',
    })
  : spawnSync(npm, [script], {
      cwd: WEB_DIR,
      stdio: 'inherit',
      env: { ...process.env, TEMP: tmp, TMP: tmp },
      shell: process.platform === 'win32',
    })

void cmdArgs
if (result.error) {
  console.error('[build:web] 启动 npm 失败：', result.error.message)
  process.exit(1)
}
if (result.status !== 0) {
  console.error(`[build:web] web 端构建退出码 ${result.status} —— 这是 web 端自己的问题，本端不代修`)
  process.exit(result.status || 1)
}
console.log(`[build:web] 完成 → ${outDir ? path.resolve(WEB_DIR, outDir) : path.join(WEB_DIR, 'dist')}`)
