'use strict'
/**
 * 只起本地服务，不起 Electron 窗口。
 *
 * 用途有两个：
 *   1. 没有 GUI 会话（CI / 远程 / 本泳道这类环境）时，用它拿 HTTP 层证据；
 *   2. 已经用浏览器开发时，把它当"和桌面端完全一致的同源入口"，
 *      直接开 http://127.0.0.1:8788/ 调试，行为与 Electron 窗口里一致。
 *
 * 用法：npm run serve   （环境变量/配置文件同 main.js）
 */
const { loadChecked } = require('./config')
const { createServer } = require('./server')

const config = loadChecked()
const srv = createServer(config)

srv.listen().then((addr) => {
  console.log(`[desktop:serve] 入口      ${config.origin}/`)
  console.log(`[desktop:serve] 模式      ${config.devServerUrl ? 'dev-server 透传 → ' + config.devServerUrl : '静态托管 ' + config.webDist}`)
  console.log(`[desktop:serve] /api 转发 ${config.apiTarget}`)
  console.log(`[desktop:serve] 自检      ${config.origin}/__desktop__/status`)
  console.log(`[desktop:serve] 监听      ${addr.address}:${addr.port}（按 Ctrl+C 停止）`)
}).catch((err) => {
  console.error(`[desktop:serve] 启动失败：${err.code || ''} ${err.message}`)
  process.exit(1)
})

process.on('SIGINT', () => srv.close().then(() => process.exit(0)))
