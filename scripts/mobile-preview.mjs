// 手机 H5 预览：托管 app/uniapp 的 H5 产物，并把 /api 反代到本机后端。
//
// 为什么需要它：uni-app 的 H5 产物默认走相对路径 /api/v1，直接把 dist 丢给
// 静态服务器会 404；而后端刻意不开 CORS，所以也不能让页面直连 8080。
// 这里在同一个端口上同时做静态托管和反代，浏览器/WebView 看到的永远是同源。
//
// 用法：
//   node scripts/mobile-preview.mjs
//   node scripts/mobile-preview.mjs --port 3008 --api http://127.0.0.1:8080
//   node scripts/mobile-preview.mjs --host 0.0.0.0   # 局域网手机访问
import http from 'node:http'
import fs from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const repoRoot = path.resolve(__dirname, '..')

function arg(name, fallback) {
  const i = process.argv.indexOf(`--${name}`)
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback
}

const port = Number(arg('port', process.env.MOBILE_PORT ?? 3008))
const host = arg('host', process.env.MOBILE_HOST ?? '0.0.0.0')
const apiTarget = new URL(arg('api', process.env.MOBILE_API ?? 'http://127.0.0.1:8080'))
const dist = path.resolve(arg('dist', process.env.MOBILE_DIST ?? path.join(repoRoot, 'app/uniapp/dist/build/h5')))

// 电脑浏览器里看移动端时用的手机框；iframe 与主站同源，登录态/接口都正常。
const DEMO_HTML = `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>云裳进销存 · 手机预览</title>
<style>
  * { box-sizing: border-box; }
  body {
    margin: 0; min-height: 100vh; display: flex; flex-direction: column;
    align-items: center; justify-content: center; gap: 14px;
    background: radial-gradient(circle at 50% 20%, #253d5c 0%, #101b2d 70%);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "Microsoft YaHei", sans-serif;
    color: #e8edf5; padding: 24px;
  }
  h1 { font-size: 18px; font-weight: 600; margin: 0; letter-spacing: .5px; }
  p { margin: 0; font-size: 12px; color: #9fb0c7; }
  .phone {
    width: min(390px, 92vw);
    height: min(844px, calc(100vh - 130px));
    aspect-ratio: 390 / 844;
    padding: 10px; border-radius: 42px;
    background: #0b1220;
    border: 1px solid rgba(255,255,255,.14);
    box-shadow: 0 30px 80px rgba(0,0,0,.55), inset 0 0 0 1px rgba(255,255,255,.04);
    position: relative;
  }
  .phone::before {
    content: ""; position: absolute; top: 14px; left: 50%; transform: translateX(-50%);
    width: 92px; height: 22px; border-radius: 999px; background: #0b1220; z-index: 2;
  }
  iframe {
    width: 100%; height: 100%; border: 0; border-radius: 32px;
    background: #fff; display: block;
  }
  .hint { font-size: 12px; color: #9fb0c7; text-align: center; line-height: 1.7; }
  .hint b { color: #d4a64a; font-weight: 600; }
</style>
</head>
<body>
  <h1>云裳进销存 · 手机预览</h1>
  <div class="phone"><iframe src="/#/pages/login/login" title="云裳进销存移动端"></iframe></div>
  <div class="hint">账号 <b>admin</b> / 密码 <b>Admin@12345</b><br />手机访问请直接用 <b>http://电脑局域网IP:3008/</b></div>
</body>
</html>`

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
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

if (!fs.existsSync(path.join(dist, 'index.html'))) {
  console.error(`[mobile-preview] H5 产物不存在：${dist}`)
  console.error('  先构建：cd app/uniapp && npm run build:h5')
  process.exit(1)
}

function proxy(req, res) {
  const upstream = http.request(
    {
      hostname: apiTarget.hostname,
      port: apiTarget.port || 80,
      method: req.method,
      path: req.url,
      headers: { ...req.headers, host: apiTarget.host },
    },
    (up) => {
      res.writeHead(up.statusCode ?? 502, up.headers)
      up.pipe(res)
    },
  )
  upstream.on('error', (err) => {
    res.writeHead(502, { 'content-type': 'application/json; charset=utf-8' })
    res.end(JSON.stringify({ code: 'PREVIEW_UPSTREAM_UNREACHABLE', message: `后端不可达：${err.message}` }))
  })
  req.pipe(upstream)
}

function serveStatic(req, res) {
  const url = new URL(req.url ?? '/', 'http://preview.local')
  let pathname = decodeURIComponent(url.pathname)

  if (pathname === '/__demo__' || pathname === '/__demo__/') {
    res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' })
    res.end(DEMO_HTML)
    return
  }

  if (pathname === '/') pathname = '/index.html'

  const file = path.resolve(dist, `.${pathname}`)
  if (file !== dist && !file.startsWith(dist + path.sep)) {
    res.writeHead(403, { 'content-type': 'text/plain; charset=utf-8' })
    res.end('403')
    return
  }

  fs.stat(file, (err, stat) => {
    if (err || !stat.isFile()) {
      // SPA/hash 路由兜底：找不到文件就回 index.html
      const index = path.join(dist, 'index.html')
      fs.readFile(index, (readErr, data) => {
        if (readErr) {
          res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' })
          res.end('404')
          return
        }
        res.writeHead(200, { 'content-type': MIME['.html'], 'cache-control': 'no-store' })
        res.end(data)
      })
      return
    }

    const ext = path.extname(file).toLowerCase()
    res.writeHead(200, {
      'content-type': MIME[ext] ?? 'application/octet-stream',
      'cache-control': ext === '.html' ? 'no-store' : 'public, max-age=3600',
    })
    fs.createReadStream(file).pipe(res)
  })
}

const server = http.createServer((req, res) => {
  if ((req.url ?? '').startsWith('/api')) {
    proxy(req, res)
    return
  }
  serveStatic(req, res)
})

server.listen(port, host, () => {
  const lan = Object.values(os.networkInterfaces())
    .flat()
    .filter((n) => n && n.family === 'IPv4' && !n.internal)
    .map((n) => n.address)

  console.log(`[mobile-preview] H5: ${dist}`)
  console.log(`[mobile-preview] API: ${apiTarget.href} -> /api`)
  console.log(`[mobile-preview] 本机: http://127.0.0.1:${port}/`)
  for (const ip of lan) console.log(`[mobile-preview] 局域网: http://${ip}:${port}/`)
  console.log('[mobile-preview] 手机/模拟器请用局域网地址；后端不可达时接口会返回 502。')
})

server.on('error', (err) => {
  if (err.code === 'EADDRINUSE') {
    console.error(`[mobile-preview] 端口 ${port} 已被占用；换一个：--port 3009`)
  } else {
    console.error(`[mobile-preview] ${err.message}`)
  }
  process.exit(1)
})
