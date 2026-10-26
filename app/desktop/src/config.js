'use strict'
/**
 * 桌面端运行时配置。
 *
 * 优先级：环境变量 > 配置文件（config/desktop.config.json）> 内置默认值。
 * 之所以留三档：CI/多泳道并行用环境变量最省事；本机长期偏好写配置文件；
 * 默认值是"什么都不配也能跑起来连本地后端"。
 *
 * ★ 端口默认 8788，且**不允许随机**。原因见 README「为什么端口必须固定」一节：
 *   token / 权限快照 / 当前租户都在 localStorage 里，而 localStorage 按 origin 隔离，
 *   origin = scheme + host + **port**。端口变了就等于换了个站点，登录态"凭空消失"。
 */
const fs = require('node:fs')
const path = require('node:path')

/** app/desktop 目录（本文件在 src/ 下） */
const DESKTOP_DIR = path.resolve(__dirname, '..')
/** 仓库根，frontend/web 的构建产物在这里 */
const REPO_ROOT = path.resolve(DESKTOP_DIR, '..', '..')

const DEFAULTS = {
  host: '127.0.0.1',
  port: 8788,
  /** 后端基址：与 frontend/web/vite.config.ts 的 proxy target 同一口径 */
  apiTarget: 'http://127.0.0.1:8080',
  /** 要反代的路径前缀；与 web 端的 fetch('/api/...') 保持一致 */
  apiPrefixes: ['/api'],
  /** SPA 构建产物目录（静态模式） */
  webDist: path.join(REPO_ROOT, 'frontend', 'web', 'dist'),
  /**
   * 开发直连模式：填了它就不再读 webDist，而是把**所有**请求（含 HMR 的 ws）
   * 透传给 vite dev server。这样 `npm run dev` 改代码能热更新，且仍然同源。
   */
  devServerUrl: null,
  /** 关闭窗口时隐藏到托盘而不是退出 */
  closeToTray: true,
  /** 深链协议名，如 erp-desktop://products/P-001 */
  protocol: 'erp-desktop',
}

function readConfigFile() {
  const p = process.env.DESKTOP_CONFIG
    ? path.resolve(process.env.DESKTOP_CONFIG)
    : path.join(DESKTOP_DIR, 'config', 'desktop.config.json')
  if (!fs.existsSync(p)) return { __file: null }
  try {
    return { ...JSON.parse(fs.readFileSync(p, 'utf8')), __file: p }
  } catch (err) {
    throw new Error(`桌面端配置文件解析失败：${p}\n${err.message}`)
  }
}

function envInt(name) {
  const raw = process.env[name]
  if (raw === undefined || raw === '') return undefined
  const n = Number(raw)
  if (!Number.isInteger(n) || n <= 0 || n > 65535) {
    throw new Error(`${name}=${raw} 不是合法端口号`)
  }
  return n
}

function load() {
  const file = readConfigFile()
  const list = (envName, key, fallback) => {
    const raw = process.env[envName]
    if (raw !== undefined && raw !== '') return raw.split(',').map((s) => s.trim()).filter(Boolean)
    if (Array.isArray(file[key])) return file[key]
    return fallback
  }
  const pick = (envName, key, fallback) => {
    if (process.env[envName] !== undefined && process.env[envName] !== '') return process.env[envName]
    if (file[key] !== undefined && file[key] !== null && file[key] !== '') return file[key]
    return fallback
  }

  const host = pick('DESKTOP_HOST', 'host', DEFAULTS.host)
  const port = envInt('DESKTOP_PORT') ?? (file.port !== undefined ? Number(file.port) : DEFAULTS.port)
  const apiTarget = pick('DESKTOP_API_TARGET', 'apiTarget', DEFAULTS.apiTarget)
  const devServerUrl = pick('DESKTOP_DEV_SERVER_URL', 'devServerUrl', null)

  let webDist = pick('DESKTOP_WEB_DIST', 'webDist', DEFAULTS.webDist)
  if (!path.isAbsolute(webDist)) webDist = path.resolve(DESKTOP_DIR, webDist)

  return {
    host,
    port,
    origin: `http://${host}:${port}`,
    apiTarget,
    apiPrefixes: list('DESKTOP_API_PREFIXES', 'apiPrefixes', DEFAULTS.apiPrefixes),
    webDist,
    devServerUrl,
    closeToTray:
      process.env.DESKTOP_CLOSE_TO_TRAY !== undefined
        ? process.env.DESKTOP_CLOSE_TO_TRAY !== '0' && process.env.DESKTOP_CLOSE_TO_TRAY !== 'false'
        : file.closeToTray !== undefined
          ? Boolean(file.closeToTray)
          : DEFAULTS.closeToTray,
    protocol: pick('DESKTOP_PROTOCOL', 'protocol', DEFAULTS.protocol),
    configFile: file.__file || null,
    repoRoot: REPO_ROOT,
    desktopDir: DESKTOP_DIR,
  }
}

/** 启动前把形状校一遍：配置写错要在起窗口之前响亮失败 */
function assertShape(cfg) {
  const problems = []
  if (!Number.isInteger(cfg.port) || cfg.port < 1 || cfg.port > 65535) problems.push(`port=${cfg.port} 非法`)
  if (!Array.isArray(cfg.apiPrefixes) || cfg.apiPrefixes.length === 0 || cfg.apiPrefixes.some((p) => typeof p !== 'string' || !p.startsWith('/'))) {
    problems.push(`apiPrefixes=${JSON.stringify(cfg.apiPrefixes)} 必须是形如 ["/api"] 的数组`)
  }
  if (!/^https?:\/\//.test(cfg.apiTarget)) problems.push(`apiTarget=${cfg.apiTarget} 必须是 http(s):// 绝对地址`)
  if (problems.length) throw new Error(`桌面端配置不合法：\n  - ${problems.join('\n  - ')}`)
  return cfg
}

function loadChecked() {
  return assertShape(load())
}

module.exports = { load, loadChecked, assertShape, REPO_ROOT, DESKTOP_DIR }
