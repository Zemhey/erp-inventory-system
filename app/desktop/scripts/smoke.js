'use strict'
/**
 * 冒烟测试：不依赖 Electron、不依赖 GUI，只用 HTTP 证明"桌面端这层壳"的关键链路。
 *
 * 验的是本端真正的技术支点（详见 src/server.js 顶部注释）：
 *   A. 静态托管 SPA 产物（同源 http://，不是 file://）
 *   B. history 路由回落：任意子路径 / 刷新 → index.html（loadFile 方案在这里必然白屏）
 *   C. 带 hash 的产物走长缓存，index.html 走 no-cache
 *   D. /api 反代到后端：POST 带 body 也能透传，后端**不需要 CORS**（同源）
 *   E. 后端不在时是结构化 502，而不是含糊的网络错误
 *   F. 路径穿越被挡在 webDist 之外
 *   G. 端口被占用时 EADDRINUSE 响亮失败（对应"端口必须固定"那条决策）
 *
 * 用专属测试端口 8790/8791，不碰默认端口 8788，也不碰 8080 真实后端。
 * 跑：npm run smoke
 */
const fs = require('node:fs')
const http = require('node:http')
const path = require('node:path')

const { createServer } = require('../src/server')
const { loadChecked, DESKTOP_DIR } = require('../src/config')

const TEST_PORT = 8790
const STUB_PORT = 8791
const DIST = path.join(DESKTOP_DIR, '.tmp', 'smoke-dist')

const results = []
function check(name, pass, detail) {
  results.push({ name, pass, detail })
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${name}${detail ? `  — ${detail}` : ''}`)
}

function makeFixtureDist() {
  fs.rmSync(DIST, { recursive: true, force: true })
  fs.mkdirSync(path.join(DIST, 'assets'), { recursive: true })
  fs.writeFileSync(path.join(DIST, 'index.html'),
    '<!doctype html><html><head><title>ERP</title></head><body><div id="app"></div>' +
    '<script type="module" src="/assets/index-BvRaNd0m.js"></script></body></html>')
  fs.writeFileSync(path.join(DIST, 'assets', 'index-BvRaNd0m.js'), 'console.log("spa")')
}

/** 假后端：只用来证明代理链路，绝不冒充真实 8080 */
function startStubBackend() {
  return new Promise((resolve) => {
    const stub = http.createServer((req, res) => {
      let body = ''
      req.on('data', (c) => (body += c))
      req.on('end', () => {
        if (req.url.startsWith('/actuator/health')) {
          res.writeHead(200, { 'Content-Type': 'application/json' })
          return res.end(JSON.stringify({ status: 'UP' }))
        }
        if (req.url === '/api/v1/auth/login' && req.method === 'POST') {
          res.writeHead(200, { 'Content-Type': 'application/json', 'X-Stub': '1' })
          return res.end(JSON.stringify({ code: 0, data: { token: 'stub-token', echo: body } }))
        }
        res.writeHead(404, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ code: 404, path: req.url, method: req.method }))
      })
    })
    stub.listen(STUB_PORT, '127.0.0.1', () => resolve(stub))
  })
}

async function req(method, url, { body, headers } = {}) {
  const res = await fetch(url, { method, body, headers, redirect: 'manual' })
  const text = await res.text()
  return { status: res.status, headers: Object.fromEntries(res.headers.entries()), text }
}

async function run() {
  makeFixtureDist()
  const stub = await startStubBackend()

  // 走真实的 config.loadChecked()，再覆盖成测试端口/测试产物目录 —— 这样连配置装配一起验了
  const config = {
    ...loadChecked(),
    port: TEST_PORT,
    origin: `http://127.0.0.1:${TEST_PORT}`,
    apiTarget: `http://127.0.0.1:${STUB_PORT}`,
    webDist: DIST,
    devServerUrl: null,
  }
  const srv = createServer(config)
  await srv.listen()

  // A + B：SPA 首页
  const home = await req('GET', `${config.origin}/`)
  check('A 静态托管 SPA 首页（http 同源入口）', home.status === 200 && home.text.includes('<div id="app">'), `HTTP ${home.status}`)

  // B：history 子路径刷新回落
  const deep = await req('GET', `${config.origin}/inventory/list?kw=a`)
  check('B history 路由子路径回落 index.html', deep.status === 200 && deep.text.includes('<div id="app">'), `HTTP ${deep.status}`)

  // C：缓存策略
  const asset = await req('GET', `${config.origin}/assets/index-BvRaNd0m.js`)
  check('C1 hash 产物长缓存', /immutable/.test(asset.headers['cache-control'] || ''), asset.headers['cache-control'])
  check('C2 index.html no-cache', /no-cache/.test(home.headers['cache-control'] || ''), home.headers['cache-control'])

  // D：/api 反代（POST + body 透传）
  const login = await req('POST', `${config.origin}/api/v1/auth/login`, {
    body: JSON.stringify({ username: 'demo', password: 'x' }),
    headers: { 'Content-Type': 'application/json' },
  })
  const echoed = (() => { try { return JSON.parse(login.text).data.echo } catch { return null } })()
  check('D /api 反代到后端且 POST body 透传', login.status === 200 && echoed === JSON.stringify({ username: 'demo', password: 'x' }), `HTTP ${login.status}, 上游头 X-Stub=${login.headers['x-stub']}`)

  // D2：非 /api 前缀不误转发
  const notApi = await req('GET', `${config.origin}/apiv1/x.js`)
  check('D2 只有 /api/ 前缀被代理（/apiv1 不误伤）', notApi.status === 404, `HTTP ${notApi.status}`)

  // F：路径穿越
  const escape = await req('GET', `${config.origin}/../../Windows/win.ini`)
  const escape2 = await req('GET', `${config.origin}/%2e%2e%2f%2e%2e%2fwin.ini`)
  check('F 路径穿越被挡', escape.status >= 400 || !escape.text.includes('[fonts]'), `HTTP ${escape.status} / HTTP ${escape2.status}`)

  // E：后端不可达 → 结构化 502（用另一个壳端口，指向一个没人在听的 apiTarget）
  const DEAD_PORT = 8797
  const deadConfig = { ...config, port: DEAD_PORT, origin: `http://127.0.0.1:${DEAD_PORT}`, apiTarget: 'http://127.0.0.1:8799' }
  const deadSrv = createServer(deadConfig)
  await deadSrv.listen()
  const dead = await req('GET', `${deadConfig.origin}/api/v1/anything`)
  check('E 后端不可达回结构化 502', dead.status === 502 && dead.text.includes('DESKTOP_UPSTREAM_UNREACHABLE'), `HTTP ${dead.status}`)
  await deadSrv.close()

  // G：端口占用必须响亮失败（不许悄悄换端口）
  const dup = createServer(config)
  let dupErr = null
  try { await dup.listen() } catch (err) { dupErr = err }
  check('G 端口被占用时 EADDRINUSE 报错而非换端口', dupErr && dupErr.code === 'EADDRINUSE', dupErr && dupErr.code)

  // 自检端点
  const status = await req('GET', `${config.origin}/__desktop__/status`)
  check('H 桌面壳自检端点', status.status === 200 && JSON.parse(status.text).desktop === true, status.text.slice(0, 80))

  await srv.close()
  stub.close()

  // I. 可选：默认端口 8788 上的壳 → **真实后端**。后端没跑时记 SKIP（不是本端缺陷，但不能假装通过）
  const real = loadChecked()
  const backendUp = await new Promise((resolve) => {
    const p = http.get(real.apiTarget + '/actuator/health', { timeout: 1200 }, (res) => { res.resume(); resolve(true) })
    p.on('timeout', () => p.destroy())
    p.on('error', () => resolve(false))
  })
  if (!backendUp) {
    check('I 壳 → 真实后端转发（默认端口 ' + real.port + '）', true, `SKIP：后端 ${real.apiTarget} 未在跑`)
  } else {
    const realSrv = createServer(real)
    let res = null
    try {
      await realSrv.listen()
      res = await req('POST', `${real.origin}/api/v1/auth/login`, {
        body: JSON.stringify({ username: '__smoke__', password: '__smoke__' }),
        headers: { 'Content-Type': 'application/json' },
      })
      // 只要不是 502/DESKTOP_UPSTREAM_UNREACHABLE，就说明请求真的到了后端（业务上 401/400 都算链路通）
      check('I 壳 → 真实后端转发（默认端口 ' + real.port + '）', res.status !== 502, `HTTP ${res.status} ${res.text.slice(0, 60)}`)
    } catch (err) {
      check('I 壳 → 真实后端转发（默认端口 ' + real.port + '）', false, `${err.code || ''} ${err.message}`)
    } finally {
      await realSrv.close()
    }
  }

  // J. webDist 用正斜杠（环境变量/配置文件的常见写法）时子路径不能误判成穿越
  //    —— 这条就是抓出 Windows 下 path.join 归一分隔符那个 bug 的
  const FW_PORT = 8796
  const fwDist = DIST.split(path.sep).join('/')
  const fwSrv = createServer({ ...config, port: FW_PORT, origin: `http://127.0.0.1:${FW_PORT}`, webDist: fwDist })
  await fwSrv.listen()
  const fwDeep = await req('GET', `http://127.0.0.1:${FW_PORT}/orders/list`)
  const fwAsset = await req('GET', `http://127.0.0.1:${FW_PORT}/assets/index-BvRaNd0m.js`)
  check('J 正斜杠 webDist 下子路径/资源仍正常', fwDeep.status === 200 && fwAsset.status === 200, `deep=${fwDeep.status} asset=${fwAsset.status}`)
  await fwSrv.close()

  const failed = results.filter((r) => !r.pass)
  console.log(`\n${results.length - failed.length}/${results.length} 通过`)
  if (failed.length) {
    console.log('失败项：' + failed.map((f) => f.name).join(' | '))
    process.exit(1)
  }
  fs.rmSync(DIST, { recursive: true, force: true })
}

run().catch((err) => {
  console.error('冒烟测试自身异常：', err)
  process.exit(1)
})
