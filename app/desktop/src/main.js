'use strict'
/**
 * Electron 主进程：只做"只有桌面能做"的事，业务界面 100% 复用 frontend/web 的产物。
 *
 * 启动顺序（顺序有讲究）：
 *   1. 抢单实例锁 —— 端口固定，第二个实例必然 EADDRINUSE，与其报一堆错不如早退；
 *   2. 起本地服务（静态托管 + /api 反代），见 src/server.js 顶部那段"为什么"；
 *   3. 建窗口，加载 http://127.0.0.1:<固定端口>/  ← 关键：**不是** loadFile()；
 *   4. 装托盘、菜单、打印、深链这些桌面增量能力。
 */
const { app, BrowserWindow, Menu, Tray, ipcMain, dialog, shell, nativeImage, Notification } = require('electron')
const http = require('node:http')
const fs = require('node:fs')
const path = require('node:path')
const { URL } = require('node:url')

const { loadChecked } = require('./config')
const { createServer } = require('./server')

// ★ 本机实测坑：这台机器的 shell 里带着 ELECTRON_RUN_AS_NODE=1，此时 electron.exe 会退化成
//   普通 Node，`require('electron')` 返回的是二进制路径字符串而不是主进程 API，app 直接 undefined。
//   不做这道闸，报错会是一句看不懂的 "Cannot read properties of undefined"。
if (!app || typeof app.whenReady !== 'function') {
  console.error(
    '[desktop] 当前不是 Electron 主进程环境（require("electron") 没给出 app）。\n' +
    '  多半是环境变量 ELECTRON_RUN_AS_NODE 被设上了。去掉它再启动：\n' +
    '    env -u ELECTRON_RUN_AS_NODE npm start\n' +
    '  只想验证本地服务与 /api 代理（不需要窗口）：npm run serve',
  )
  process.exit(1)
}

const config = loadChecked()
const PKG = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'package.json'), 'utf8'))

/** @type {BrowserWindow|null} */
let win = null
/** @type {Tray|null} */
let tray = null
/** 用户点关闭→隐藏到托盘；只有托盘/菜单里的"退出"才真的退出 */
let isQuitting = false
/** 首次隐藏时提示一次，之后不再唠叨 */
let trayHintShown = false

// ───────────────────────────── 单实例 ─────────────────────────────
// Windows/Linux 的深链是通过"再启动一次 exe 并把 URL 塞进 argv"送达的，
// 所以 second-instance 事件同时承担深链入口。
const gotTheLock = app.requestSingleInstanceLock()
if (!gotTheLock) {
  console.error(`[desktop] 已有一个桌面实例在跑（端口 ${config.port}）。本次启动退出。`)
  app.quit()
} else {
  app.on('second-instance', (_event, argv) => {
    focusWindow()
    const link = argv.find((a) => a.startsWith(`${config.protocol}://`))
    if (link) handleDeepLink(link)
  })
}

// ───────────────────────────── 窗口 ─────────────────────────────
function createWindow() {
  win = new BrowserWindow({
    width: 1440,
    height: 900,
    minWidth: 1024,
    minHeight: 640,
    title: 'ERP 进销存',
    backgroundColor: '#f5f7fa',
    show: false,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      // ★ 三件套：渲染进程拿不到 Node。桌面能力全走 preload 里点名的几个 channel。
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      additionalArguments: [`--erp-app-version=${PKG.version}`],
      spellcheck: false,
      devTools: !app.isPackaged || process.env.DESKTOP_FORCE_DEVTOOLS === '1',
    },
  })

  win.once('ready-to-show', () => win.show())

  win.loadURL(`${config.origin}/`)

  // 关闭 → 隐藏到托盘（托盘常驻是这端的存在理由之一）
  win.on('close', (event) => {
    if (isQuitting || !config.closeToTray) return
    event.preventDefault()
    win.hide()
    if (!trayHintShown && tray) {
      trayHintShown = true
      tray.displayBalloon({
        title: 'ERP 仍在后台运行',
        content: '已最小化到托盘，点托盘图标可恢复窗口；要真正退出请用托盘菜单的"退出"。',
      })
    }
  })
  win.on('minimize', () => { /* 保留默认任务栏行为，不抢 */ })

  // 不把渲染进程变成浏览器：新窗口一律拒绝，外部链接交系统浏览器
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:\/\//i.test(url)) void shell.openExternal(url)
    return { action: 'deny' }
  })

  // 导航只允许本机同源入口（防止页面被引导到 file:// 或外部站点）
  win.webContents.on('will-navigate', (event, target) => {
    if (target.startsWith(config.origin)) return
    if (target.startsWith('devtools://')) return
    event.preventDefault()
  })

  win.webContents.on('did-fail-load', (_e, code, desc, failingUrl) => {
    if (code === -3) return // 用户取消，不是故障
    console.error(`[desktop] 加载失败 ${code} ${desc} ${failingUrl}`)
  })

  buildMenu()

  // 自测钩子：没有 GUI 时，用主进程 executeJavaScript 把"渲染进程里到底有什么"打到 stdout。
  // DESKTOP_SELFTEST=1 开；DESKTOP_SELFTEST_QUIT=1 跑完就退（CI 里别留僵尸进程）。
  if (process.env.DESKTOP_SELFTEST === '1') {
    win.webContents.once('did-finish-load', async () => {      const probe = await win.webContents.executeJavaScript(`(async () => {
        const d = window.desktop || {}
        let statusError = null
        let status = null
        try { status = await d.getStatus() } catch (e) { statusError = String(e) }
        return {
          isDesktop: d.isDesktop === true,
          methods: Object.keys(d),
          meta: d.meta || null,
          statusOk: !!(status && status.desktop === true),
          statusError,
          // 安全面自检：这三项必须全是 false，否则说明注入面开了
          leakedRequire: typeof window.require === 'function',
          leakedProcess: typeof window.process === 'object' && !!window.process?.versions?.node,
          leakedIpcRenderer: !!window.ipcRenderer,
        }
      })()`)
      // 打印链路的可验证部分：printToPDF 出字节 + 系统打印机队列列举。
      // 真正落到某台物理打印机需要 GUI 与设备，本环境验不了，README 里标"未验"。
      let pdf = null
      try {
        const data = await win.webContents.printToPDF({ printBackground: true })
        pdf = { bytes: data.length }
        if (process.env.DESKTOP_SELFTEST_PDF === '1') {
          fs.writeFileSync(path.join(__dirname, '..', '.tmp', 'selftest.pdf'), data)
        }
      } catch (err) {
        pdf = { error: err.message }
      }
      const printers = await win.webContents
        .getPrintersAsync()
        .then((list) => list.map((p) => ({ name: p.name, default: p.isDefault })))
        .catch((err) => String(err.message || err))

      console.log('[desktop:selftest] ' + JSON.stringify({
        ...probe,
        pdf,
        printers,
        // 主进程侧事实：托盘是否真的建起来了、窗口加载的是哪个 URL
        trayAlive: !!tray && !tray.isDestroyed(),
        windowVisible: !!win && win.isVisible(),
        loadedURL: win ? win.webContents.getURL() : null,
        title: win ? win.getTitle() : null,
      }))
      if (process.env.DESKTOP_SELFTEST_QUIT === '1') setTimeout(quit, 200)
    })
  }
}

function focusWindow() {
  if (!win) return
  if (win.isMinimized()) win.restore()
  if (!win.isVisible()) win.show()
  win.focus()
}

// ───────────────────────────── 托盘 ─────────────────────────────
/** 16x16 图标：有 build/icon.png 就用它，否则现画一块纯色位图（省掉一个二进制资产入库） */
function trayIcon() {
  const file = path.join(__dirname, '..', 'build', 'icon.png')
  if (fs.existsSync(file)) return nativeImage.createFromPath(file).resize({ width: 16, height: 16 })
  const size = 16
  const buf = Buffer.alloc(size * size * 4)
  for (let i = 0; i < size * size; i++) {
    buf[i * 4] = 0x2f // B
    buf[i * 4 + 1] = 0x74 // G
    buf[i * 4 + 2] = 0xe0 // R  —— 与 Element Plus 主色接近
    buf[i * 4 + 3] = 0xff // A
  }
  return nativeImage.createFromBitmap(buf, { width: size, height: size })
}

function createTray() {
  if (tray) return
  tray = new Tray(trayIcon())
  tray.setToolTip('ERP 进销存（桌面端）')
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: '显示主窗口', click: focusWindow },
    { type: 'separator' },
    { label: '打印当前页  Ctrl+P', click: () => printCurrentPage({}) },
    { label: '另存为 PDF  Ctrl+Shift+S', click: () => savePdf() },
    { type: 'separator' },
    { label: '退出', click: () => quit() },
  ]))
  // 单击切显隐：托盘常驻的常见期望；双击回到窗口
  tray.on('click', () => (win && win.isVisible() ? win.hide() : focusWindow()))
  tray.on('double-click', focusWindow)
}

function quit() {
  isQuitting = true
  app.quit()
}

// ───────────────────────────── 菜单 ─────────────────────────────
function buildMenu() {
  const template = [
    {
      label: '文件',
      submenu: [
        { label: '打印当前页', accelerator: 'CmdOrCtrl+P', click: () => printCurrentPage({}) },
        { label: '另存为 PDF', accelerator: 'CmdOrCtrl+Shift+S', click: () => savePdf() },
        { type: 'separator' },
        { label: '退出', accelerator: process.platform === 'darwin' ? 'Cmd+Q' : 'Ctrl+Q', click: quit },
      ],
    },
    {
      label: '视图',
      submenu: [
        { role: 'reload' },
        { role: 'forceReload' },
        { role: 'togglezoom' },
        { type: 'separator' },
        { role: 'toggledevtools' },
      ],
    },
    {
      label: '帮助',
      submenu: [
        {
          label: '桌面端诊断',
          click: async () => {
            const status = await buildStatus()
            dialog.showMessageBox(win, {
              type: 'info',
              title: '桌面端诊断',
              message: `入口 ${status.origin}`,
              detail: [
                `模式：${status.mode}`,
                `产物目录：${status.webDist}`,
                `后端：${status.apiTarget} → ${status.apiReachable ? `可达（HTTP ${status.apiStatusCode}）` : `不可达：${status.apiError}`}`,
                `Electron ${status.electron} / Chromium ${status.chrome} / Node ${status.node}`,
              ].join('\n'),
            })
          },
        },
        {
          label: '关于',
          click: () => dialog.showMessageBox(win, {
            type: 'info',
            title: '关于',
            message: `ERP 桌面端 v${PKG.version}`,
            detail: '本端只是外壳：界面与接口全部复用 frontend/web 的构建产物，\n这里只加托盘、打印、深链、离线可开这些桌面独有能力。',
          }),
        },
      ],
    },
  ]
  Menu.setApplicationMenu(Menu.buildFromTemplate(template))
}

// ───────────────────────────── 桌面增量能力 ─────────────────────────────
/**
 * 本地打印当前单据。
 *
 * 为什么算"桌面独有"：浏览器 print 只能走系统对话框、拿不到打印机名、无法静默；
 * 这里能列队列、能按配置直打某台针式打印机（单据套打场景）。
 * 实现层面是"打印当前已渲染的页面"——单据的排版仍归 frontend/web 管，本端不掺和。
 */
async function printCurrentPage(rawOptions) {
  if (!win) return { ok: false, error: '窗口尚未就绪' }
  const options = sanitizePrintOptions(rawOptions)
  return new Promise((resolve) => {
    win.webContents.print({ silent: options.silent, deviceName: options.deviceName, copies: options.copies }, (success, failureType) => {
      if (!success || failureType) {
        const msg = `打印失败：${failureType || 'unknown'}`
        console.error(`[desktop] ${msg}`)
        return resolve({ ok: false, error: msg })
      }
      resolve({ ok: true, silent: options.silent, deviceName: options.deviceName || '系统默认' })
    })
  })
}

/** 渲染进程传来的对象一律白名单化：绝不把任意 key 交给 print */
function sanitizePrintOptions(input) {
  const src = input && typeof input === 'object' ? input : {}
  return {
    silent: src.silent === true,
    deviceName: typeof src.deviceName === 'string' ? src.deviceName.slice(0, 200) : undefined,
    copies: Number.isInteger(src.copies) && src.copies > 0 && src.copies <= 10 ? src.copies : 1,
  }
}

async function savePdf(rawOptions) {
  if (!win) return { ok: false, error: '窗口尚未就绪' }
  const src = rawOptions && typeof rawOptions === 'object' ? rawOptions : {}
  const suggested = typeof src.fileName === 'string' ? path.basename(src.fileName).replace(/[^\w.\-一-龥]/g, '_') : 'erp-document.pdf'
  const { canceled, filePath } = await dialog.showSaveDialog(win, {
    title: '另存为 PDF',
    defaultPath: path.join(app.getPath('documents'), suggested),
    filters: [{ name: 'PDF', extensions: ['pdf'] }],
  })
  if (canceled || !filePath) return { ok: false, canceled: true }
  try {
    const data = await win.webContents.printToPDF({ printBackground: true, landscape: src.landscape === true })
    fs.writeFileSync(filePath, data)
    if (src.reveal !== false) void shell.showItemInFolder(filePath)
    return { ok: true, filePath }
  } catch (err) {
    return { ok: false, error: err.message }
  }
}

/** 后端可达性探测：只打一个轻量 GET，超时 1200ms，不碰业务写接口 */
function probeUpstream() {
  return new Promise((resolve) => {
    const target = new URL(config.apiTarget)
    const req = http.request({
      hostname: target.hostname,
      port: target.port || 80,
      path: `${target.pathname.replace(/\/$/, '')}/actuator/health`,
      method: 'GET',
      timeout: 1200,
    }, (res) => {
      res.resume()
      resolve({ reachable: true, statusCode: res.statusCode })
    })
    req.on('timeout', () => req.destroy(new Error('探测超时（1200ms）')))
    req.on('error', (err) => resolve({ reachable: false, error: err.code || err.message }))
    req.end()
  })
}

async function buildStatus() {
  const probe = await probeUpstream()
  return {
    desktop: true,
    origin: config.origin,
    port: config.port,
    mode: config.devServerUrl ? 'dev-server' : 'static',
    webDist: config.webDist,
    apiTarget: config.apiTarget,
    apiReachable: probe.reachable,
    apiStatusCode: probe.statusCode,
    apiError: probe.error,
    appVersion: PKG.version,
    electron: process.versions.electron,
    chrome: process.versions.chrome,
    node: process.versions.node,
    platform: process.platform,
    configFile: config.configFile,
  }
}

// ───────────────────────────── 深链 ─────────────────────────────
/**
 * erp-desktop://<路由路径> → http://127.0.0.1:<端口>/<路由路径>
 * 例：erp-desktop:///inventory/list?kw=螺丝 → 打开库存列表页。
 * 目标路径仍限定在本机同源入口内（与 will-navigate 同一道闸）。
 */
function handleDeepLink(raw) {
  try {
    const u = new URL(raw)
    const p = (u.host ? `/${u.host}${u.pathname}` : u.pathname).replace(/\/{2,}/g, '/')
    if (!p.startsWith('/')) return
    const dest = `${config.origin}${p}${u.search}`
    if (win) win.loadURL(dest)
    focusWindow()
    console.log(`[desktop] 深链 ${raw} → ${dest}`)
  } catch (err) {
    console.error(`[desktop] 深链解析失败：${raw} (${err.message})`)
  }
}

function registerProtocol() {
  // 开发态（electron . 启动）要把 electron 自身路径传进去，注册表才指得对
  const ok = process.defaultApp
    ? app.setAsDefaultProtocolClient(config.protocol, process.execPath, [path.resolve(process.argv[1])])
    : app.setAsDefaultProtocolClient(config.protocol)
  if (!ok) console.warn(`[desktop] 协议 ${config.protocol}:// 注册失败（Windows 需写 HKCU，安装包场景由打包器负责）`)
  app.on('open-url', (event, url) => {
    event.preventDefault()
    handleDeepLink(url)
  })
}

// ───────────────────────────── IPC（全部点名，不开放通用 send/invoke） ─────────────────────────────
function registerIpc() {
  ipcMain.handle('desktop:get-status', () => buildStatus())
  ipcMain.handle('desktop:list-printers', async () => {
    if (!win) return []
    const printers = await win.webContents.getPrintersAsync()
    return printers.map((p) => ({ name: p.name, default: p.isDefault, status: p.status }))
  })
  ipcMain.handle('desktop:print', (_e, options) => printCurrentPage(options))
  ipcMain.handle('desktop:save-pdf', (_e, options) => savePdf(options))
  ipcMain.handle('desktop:notify', (_e, { title, body }) => {
    if (!Notification.isSupported()) return { ok: false, error: '系统通知不可用' }
    new Notification({ title: String(title || 'ERP').slice(0, 120), body: String(body || '').slice(0, 500) }).show()
    return { ok: true }
  })
}

// ───────────────────────────── 生命周期 ─────────────────────────────
let server = null

app.whenReady().then(async () => {
  // 没抢到锁就不能继续往下：app.quit() 是异步的，不拦的话这里会照样起服务、
  // 抢同一个端口，然后在退出时把已 listen 的端口又拆掉（实测表现为"端口没人监听"）。
  if (!gotTheLock) return

  server = createServer(config)
  try {
    await server.listen()
  } catch (err) {
    // 端口冲突的具体处置办法已由 server.js 打印；这里只负责不留下半个窗口
    console.error(`[desktop] 本地服务起不来（${err.code || err.message}），退出。`)
    app.quit()
    return
  }
  console.log(`[desktop] 本地服务 ${config.origin}/  模式=${config.devServerUrl ? 'dev-server' : 'static'}  后端=${config.apiTarget}`)

  registerIpc()
  registerProtocol()
  createWindow()
  if (config.closeToTray) createTray()

  // 冷启动就带深链参数（从"打开方式"进来）
  const bootLink = process.argv.find((a) => a.startsWith(`${config.protocol}://`))
  if (bootLink) handleDeepLink(bootLink)

  app.on('activate', () => (BrowserWindow.getAllWindows().length === 0 ? createWindow() : focusWindow()))
}).catch((err) => {
  // 主进程起不来时给一条人能读懂的话，而不是 Electron 的 UnhandledPromiseRejectionWarning
  console.error(`[desktop] 启动失败：${err.message}`)
  app.quit()
})

process.on('unhandledRejection', (reason) => {
  console.error('[desktop] 未处理的 Promise 异常：', reason)
})

app.on('window-all-closed', () => {
  // 托盘常驻时不因窗口关闭而退出；macOS 亦按惯例留在后台
  if (!config.closeToTray && process.platform !== 'darwin') quit()
})

app.on('before-quit', () => {
  isQuitting = true
  if (tray) {
    tray.destroy()
    tray = null
  }
})

app.on('will-quit', async () => {
  if (server) await server.close()
})
