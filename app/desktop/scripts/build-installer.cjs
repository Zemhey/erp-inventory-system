'use strict'
/**
 * 打 Windows 便携包，并把「后端地址」烘进安装包。
 *
 * 为什么要有这一步：终端用户那边没有仓库、没有环境变量，
 * 所以 apiTarget 必须在构建时写进 config/desktop.config.json 一起打包。
 *
 * 用法：
 *   npm run dist -- --api http://1.2.3.4:18080
 *   DESKTOP_API_TARGET=http://1.2.3.4:18080 npm run dist
 *   不传则默认 http://127.0.0.1:8080（只适合本机自测）
 *
 * 生成的 config 是临时的：构建结束会恢复原状，避免影响日常 npm start。
 */
const { spawnSync } = require('node:child_process')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')

const DESKTOP_DIR = path.resolve(__dirname, '..')
const WEB_DIST = path.resolve(DESKTOP_DIR, '..', '..', 'frontend', 'web', 'dist')
const CONFIG_DIR = path.join(DESKTOP_DIR, 'config')
const CONFIG_FILE = path.join(CONFIG_DIR, 'desktop.config.json')
// ★ 构建目录放在工作区外：编辑器/索引器会盯住工作区里新出现的大文件，
//   解压 Electron 时的 rename/unlink 会被它拦住（EBUSY/EPERM）。
const BUILD_OUT = path.join(os.tmpdir(), 'erp-desktop-build')
const RELEASE_DIR = path.join(DESKTOP_DIR, 'release')

function argValue(name) {
  const i = process.argv.indexOf(name)
  return i >= 0 ? process.argv[i + 1] : undefined
}

const apiTarget = (argValue('--api') || process.env.DESKTOP_API_TARGET || 'http://127.0.0.1:8080')
  .trim()
  .replace(/\/+$/, '')

if (!/^https?:\/\/[^\s/]+/i.test(apiTarget)) {
  console.error(`[dist] 后端地址不合法：${apiTarget}\n  例：npm run dist -- --api http://1.2.3.4:18080`)
  process.exit(1)
}

if (!fs.existsSync(path.join(WEB_DIST, 'index.html'))) {
  console.error(`[dist] 缺前端产物：${WEB_DIST}\n  先跑 npm run build:web`)
  process.exit(1)
}

if (/^http:\/\/(127\.0\.0\.1|localhost)(:|\/)/i.test(apiTarget)) {
  console.warn('[dist] 注意：后端地址是本机回环，这个包装到别的电脑上会连不上。')
}

const configBody = JSON.stringify({
  apiTarget,
  // /readyz 必须在代理清单里：工作台的状态面板探的就是它（容器与宿主 nginx 都放行这条），
  // 少了它会被壳当成前端路由回落到 index.html，面板于是恒定报"后端不可达"。
  apiPrefixes: ['/api', '/actuator', '/readyz'],
}, null, 2) + '\n'

const hadConfig = fs.existsSync(CONFIG_FILE)
const previousConfig = hadConfig ? fs.readFileSync(CONFIG_FILE) : null

fs.mkdirSync(CONFIG_DIR, { recursive: true })
fs.writeFileSync(CONFIG_FILE, configBody, 'utf8')
console.log(`[dist] 已写入后端地址：${apiTarget}`)

const env = {
  ...process.env,
  ELECTRON_MIRROR: process.env.ELECTRON_MIRROR || 'https://npmmirror.com/mirrors/electron/',
  ELECTRON_BUILDER_BINARIES_MIRROR:
    process.env.ELECTRON_BUILDER_BINARIES_MIRROR || 'https://npmmirror.com/mirrors/electron-builder-binaries/',
}

const cli = path.join(DESKTOP_DIR, 'node_modules', 'electron-builder', 'cli.js')
fs.rmSync(BUILD_OUT, { recursive: true, force: true })
const result = spawnSync(process.execPath, [cli, '--win', '--x64', `--config.directories.output=${BUILD_OUT}`], {
  cwd: DESKTOP_DIR,
  stdio: 'inherit',
  env,
})

try {
  if (hadConfig) fs.writeFileSync(CONFIG_FILE, previousConfig)
  else fs.rmSync(CONFIG_FILE, { force: true })
} catch (err) {
  console.warn(`[dist] 恢复 config/desktop.config.json 失败：${err.message}`)
}

if (result.status !== 0) {
  console.error(`[dist] 打包失败（exit ${result.status}）`)
  process.exit(result.status ?? 1)
}

const artifacts = fs.existsSync(BUILD_OUT)
  ? fs.readdirSync(BUILD_OUT).filter((f) => f.toLowerCase().endsWith('.exe'))
  : []
if (artifacts.length === 0) {
  console.error(`[dist] 构建目录里没有找到 exe：${BUILD_OUT}`)
  process.exit(1)
}

fs.mkdirSync(RELEASE_DIR, { recursive: true })
for (const name of artifacts) {
  fs.copyFileSync(path.join(BUILD_OUT, name), path.join(RELEASE_DIR, name))
  console.log(`\n[dist] 成品：${path.join(RELEASE_DIR, name)}`)
}
