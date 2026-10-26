'use strict'
/**
 * 桌面端的本地服务：静态托管 SPA 构建产物 + 反向代理 /api 到后端。
 *
 * ★ 为什么要有这一层（这是整个 PC 端方案的支点）
 *
 * frontend/web 是 Vue SPA：`createWebHistory()` + 开发时靠 vite 的 /api proxy 转发后端 8080。
 * Electron 若直接 `loadFile('…/dist/index.html')`：
 *   1. 页面 origin 是 `file://`，history 路由的子路径 / 刷新 会去文件系统里找
 *      `<dist>/inventory/list` → 白屏；
 *   2. `fetch('/api/...')` 解析成 `file:///api/...` → 所有接口挂；
 *   3. 改而成之"构建时注入绝对后端地址 http://127.0.0.1:8080" → origin 变成
 *      `file://`（null）跨到 8080，**撞上 CORS**，而后端是刻意不配 CORS 的
 *      （见 frontend/web/vite.config.ts 顶部注释）。
 *
 * 本文件把这三件事一次解决：窗口加载 `http://127.0.0.1:<固定端口>/`。
 *   - 同源成立 → 不需要 CORS，后端一行不改；
 *   - history 路由成立 → 任意子路径/刷新都回落到 index.html；
 *   - `fetch('/api/...')` 打到本地端口 → 由这里转发到后端。
 * 结论：**frontend/web 零改动**。
 *
 * 实现只用 Node 内置模块，不引入 http-proxy/express 之类依赖：
 * 桌面端连 electron 都是可选安装，业务代码不该再多拉一串依赖。
 */
const http = require('node:http')
const fs = require('node:fs')
const path = require('node:path')
const { URL } = require('node:url')

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webp': 'image/webp',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
  '.map': 'application/json; charset=utf-8',
}

/** 带 hash 的构建产物可以长期缓存；index.html 必须每次验证 */
const HASHED_ASSET_RE = /^\/assets\/.+-[A-Za-z0-9_]{8,}\.[a-z0-9]+$/

function mimeOf(file) {
  return MIME[path.extname(file).toLowerCase()] || 'application/octet-stream'
}

function send(res, status, body, headers = {}) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', ...headers })
  res.end(body)
}

function sendFile(req, res, absFile, { immutable }) {
  const stat = fs.statSync(absFile)
  const headers = {
    'Content-Type': mimeOf(absFile),
    'Content-Length': stat.size,
    'Cache-Control': immutable && (req.method === 'GET' || req.method === 'HEAD')
      ? 'public, max-age=31536000, immutable'
      : 'no-cache',
    Vary: 'Accept-Encoding',
  }
  // Range 与 gzip 交给浏览器与本地磁盘，这里不做二次编码：本机回环不是瓶颈，
  // 少一层实现就少一处能出 bug 的地方。
  res.writeHead(200, headers)
  if (req.method === 'HEAD') return res.end()
  fs.createReadStream(absFile).pipe(res)
}

/** 反代一个请求到上游；上游不可达时回 502 + 结构化原因，而不是让渲染进程收一个含糊的网络错误 */
function proxyTo(res, upstreamBase, reqUrl, req, opts = {}) {
  const target = new URL(upstreamBase)
  const isHttps = target.protocol === 'https:'
  const port = target.port || (isHttps ? 443 : 80)
  const headers = { ...req.headers }
  delete headers.host
  delete headers.connection
  headers.host = target.host
  if (opts.rewriteOrigin) headers.origin = target.origin

  const upstream = http.request(
    {
      hostname: target.hostname,
      port,
      path: reqUrl.pathname + (reqUrl.search || ''),
      method: req.method,
      headers,
      timeout: opts.timeout ?? 30000,
    },
    (up) => {
      res.writeHead(up.statusCode || 502, up.headers)
      up.pipe(res)
    },
  )
  upstream.on('timeout', () => upstream.destroy(new Error('上游超时')))
  upstream.on('error', (err) => {
    if (res.headersSent) return res.destroy()
    send(res, 502, JSON.stringify({
      code: 'DESKTOP_UPSTREAM_UNREACHABLE',
      message: `桌面端无法连接后端 ${upstreamBase}（${err.code || err.message}）。`
        + ' 桌面壳与页面本身仍可打开，接口类操作需后端在跑。',
      upstream: upstreamBase,
    }), { 'Cache-Control': 'no-store' })
  })
  req.pipe(upstream)
}

function createServer(config) {
  const prefixes = config.apiPrefixes.map((p) => (p.startsWith('/') ? p : `/${p}`))

  const handler = (req, res) => {
    let reqUrl
    try {
      reqUrl = new URL(req.url, config.origin)
    } catch {
      return send(res, 400, JSON.stringify({ code: 'BAD_URL' }))
    }
    let pathname
    try {
      pathname = decodeURIComponent(reqUrl.pathname)
    } catch {
      return send(res, 400, JSON.stringify({ code: 'BAD_PATH' }))
    }
    if (!pathname.startsWith('/')) return send(res, 400, JSON.stringify({ code: 'BAD_PATH' }))

    // 桌面壳自检端点（不属于业务 API，故不与 apiPrefixes 冲突）
    if (pathname === '/__desktop__/status') {
      return send(res, 200, JSON.stringify({
        desktop: true,
        platform: process.platform,
        arch: process.arch,
        node: process.versions.node,
        port: state.port,
        mode: config.devServerUrl ? 'dev-server' : 'static',
        webDist: config.webDist,
        apiTarget: config.apiTarget,
        apiPrefixes: prefixes,
        origin: config.origin,
        // index.html 的 mtime 当作"当前前端构建时间"，够用于"我跑的是哪一版"
        buildTime: safeMtime(config.webDist),
      }), { 'Cache-Control': 'no-store' })
    }

    // 1) 业务 API → 反代到后端（同源，所以后端不需要 CORS）
    if (prefixes.some((p) => pathname === p || pathname.startsWith(p + '/'))) {
      return proxyTo(res, config.apiTarget, reqUrl, req)
    }

    // 2) 开发直连模式：其余全部透传给 vite dev server（含 HMR 的 ws，见 upgrade 分支）
    if (config.devServerUrl) {
      return proxyTo(res, config.devServerUrl, reqUrl, req, { rewriteOrigin: true })
    }

    // 3) 静态模式：托管 SPA 产物 + history 回落
    if (path.sep === '\\' && /[\\]/.test(pathname)) {
      return send(res, 400, JSON.stringify({ code: 'BAD_PATH' }))
    }
    const rel = pathname.replace(/^\/+/, '')
    // ★ 两边都必须过 path.resolve：Windows 下 path.join 会把 webDist 里的正斜杠
    //   归一成反斜杠，而配置/环境变量传进来的常常是 "D:/xxx/dist" —— 实测会让
    //   所有子路径误判成穿越（403），首页反而正常，非常难猜。
    const root = path.resolve(config.webDist)
    const abs = rel === '' ? path.join(root, 'index.html') : path.resolve(root, rel)
    if (abs !== path.join(root, 'index.html') && !abs.startsWith(root + path.sep)) {
      return send(res, 403, JSON.stringify({ code: 'PATH_ESCAPE' }))
    }

    const isFileLike = path.extname(abs) !== ''
    if (fs.existsSync(abs) && fs.statSync(abs).isFile()) {
      return sendFile(req, res, abs, { immutable: HASHED_ASSET_RE.test(pathname) })
    }
    if (isFileLike) {
      return send(res, 404, JSON.stringify({ code: 'NOT_FOUND', path: pathname }))
    }
    // createWebHistory 的子路径 / F5 刷新：一律回落 index.html，由前端路由决定看哪个页面
    const index = path.join(root, 'index.html')
    if (!fs.existsSync(index)) {
      return send(res, 500, JSON.stringify({
        code: 'WEB_DIST_MISSING',
        message: `找不到 SPA 构建产物 ${index}。先跑 npm run build:web，或用 DESKTOP_WEB_DIST 指到已有产物。`,
      }))
    }
    return sendFile(req, res, index, { immutable: false })
  }

  const server = http.createServer(handler)

  // vite HMR / 任何 ws 升级：原样接给上游（仅开发直连模式有意义）
  server.on('upgrade', (req, socket) => {
    if (!config.devServerUrl) return socket.destroy()
    const target = new URL(config.devServerUrl)
    const headers = { ...req.headers, host: target.host }
    const upstream = target.protocol === 'https:'
      ? require('node:https').request({ hostname: target.hostname, port: target.port || 443, path: req.url, headers, method: 'GET' })
      : http.request({ hostname: target.hostname, port: target.port || 80, path: req.url, headers, method: 'GET' })
    upstream.on('upgrade', (upReq, upSocket, head) => {
      socket.write(
        `HTTP/1.1 101 Switching Protocols\r\n` +
        `Upgrade: ${upReq.headers.upgrade}\r\n` +
        `Connection: Upgrade\r\n\r\n`,
      )
      if (head.length) socket.write(head)
      upSocket.pipe(socket)
      socket.pipe(upSocket)
    })
    upstream.on('error', () => socket.destroy())
    upstream.end()
  })

  const onListenError = (err) => {
    if (err.code === 'EADDRINUSE') {
      // ★ 绝不"悄悄换个端口"。端口一变 origin 就变，localStorage 里的登录态与
      //   当前租户会被当成另一个站点而"凭空丢失"。宁可响亮报错。
      console.error(
        `\n[desktop] 端口 ${config.port} 已被占用（EADDRINUSE）。\n` +
        `  为什么不让它自动换端口：token/权限快照/当前租户存在 localStorage，而 localStorage 按 origin\n` +
        `  （含端口）隔离；端口一换等于换了站点，表现为"每次启动都要重新登录"的诡异 bug。\n` +
        `  请这样做其一：\n` +
        `    1) 关掉占用 ${config.port} 的进程（多半是另一个桌面实例）：\n` +
        `       Windows:  netstat -ano | findstr :${config.port}\n` +
        `                 然后结束那个 PID（确认它不是后端/别人的服务）\n` +
        `    2) 固定改用别的端口，并且此后一直用它（同一端口才有稳定登录态）：\n` +
        `       DESKTOP_PORT=8789 npm start\n` +
        `       或写进 app/desktop/config/desktop.config.json 的 "port"\n`,
      )
    }
    rejectListen(err)
  }

  const state = { server, port: config.port, closing: false }
  state.listen = () => new Promise((resolve, reject) => {
    rejectListen = reject
    server.once('listening', () => {
      state.port = server.address().port
      server.removeListener('error', onListenError)
      resolve(server.address())
    })
    server.once('error', onListenError)
    server.listen(config.port, config.host)
  })
  state.close = () => new Promise((resolve) => {
    if (state.closing) return resolve()
    state.closing = true
    server.close(() => resolve())
  })
  return state
}

/** listen() 之前没有 reject 可用，这里挂个模块级引用给 onListenError 用 */
let rejectListen = (err) => console.error('[desktop] 本地服务启动失败', err)

function safeMtime(distDir) {
  try {
    return fs.statSync(path.join(distDir, 'index.html')).mtime.toISOString()
  } catch {
    return null
  }
}

module.exports = { createServer }
