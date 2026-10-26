'use strict'
/**
 * `npm run dev`：开发模式启动 Electron —— 加载**正在跑的 vite dev server**（带 HMR），
 * 而不是构建产物；/api 仍由 vite 自己的 proxy 转发到后端。
 *
 * 桌面端在这条路上只做两件事：把窗口指到 dev server 的固定端口、把桌面增量能力挂上。
 * 同源与代理链路仍然由桌面端这层保证，所以 dev 与打包后是同一套 fetch('/api/...')。
 *
 * 探测顺序（都是实测过的坑：忘记先跑 dev server 时，报错要直接告诉你下一步做什么）：
 *   1. dev server 在跑 → 透传模式；
 *   2. 不在跑但有 frontend/web/dist → 自动退回静态模式，并说明原因；
 *   3. 两个都没有 → 响亮失败，给出两条可执行命令。
 */
const { spawn } = require('node:child_process')
const fs = require('node:fs')
const http = require('node:http')
const path = require('node:path')
const { load } = require('./config')

const config = load()
const devUrl = config.devServerUrl || 'http://127.0.0.1:3000'

function probe(url) {
  return new Promise((resolve) => {
    const req = http.get(url, { timeout: 1200 }, (res) => {
      res.resume()
      resolve(res.statusCode >= 200 && res.statusCode < 500)
    })
    req.on('timeout', () => req.destroy())
    req.on('error', () => resolve(false))
  })
}

async function main() {
  const hasDist = fs.existsSync(path.join(config.webDist, 'index.html'))
  let env = { ...process.env }

  if (await probe(devUrl)) {
    console.log(`[dev] 透传 vite dev server ${devUrl}（HMR 可用；/api 由 vite proxy 转发）`)
    env.DESKTOP_DEV_SERVER_URL = devUrl
  } else if (hasDist) {
    console.log(`[dev] ${devUrl} 未在跑 → 退回静态模式，托管 ${config.webDist}`)
  } else {
    console.error(
      `[dev] 既没有跑着的 dev server（${devUrl}），也没有构建产物（${config.webDist}\\index.html）。\n` +
      '  二选一：\n' +
      '    A. 另开终端跑 web 开发服务器：cd frontend/web && npm run dev\n' +
      '    B. 先构建：cd app/desktop && npm run build:web',
    )
    process.exit(1)
  }

  const electronBin = require('electron') // 在 Node 里 require('electron') 返回二进制路径
  if (typeof electronBin !== 'string' || !fs.existsSync(electronBin)) {
    console.error('[dev] 未找到 electron 可执行文件。先在本目录 npm install（见 README「离线安装」）。')
    process.exit(1)
  }
  const child = spawn(electronBin, [path.join(__dirname, '..'), ...process.argv.slice(2)], {
    stdio: 'inherit',
    env,
  })
  child.on('exit', (code) => process.exit(code ?? 0))
}

main()
