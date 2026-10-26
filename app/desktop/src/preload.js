'use strict'
/**
 * preload：渲染进程能拿到的**全部**桌面能力面。
 *
 * 安全边界（不要图省事改这里）：
 *   - 不暴露 ipcRenderer，也不暴露 ipcRenderer.send/on 的通用包装。
 *     一旦开了"任意 channel 收发"，等于给网页（也就是任何被 XSS 注入的脚本）
 *     开了一条通往主进程的注入口——主进程有文件系统与进程权限，那就是 RCE。
 *   - 因此这里只逐个点名方法，channel 名写死，参数由主进程侧再校验一次。
 *   - 只暴露"只有桌面能做"的能力；业务页面长什么样仍然完全由 frontend/web 决定。
 *
 * ★ frontend/web 目前**没有**引用 window.desktop（那是业务侧改动，本泳道不动它）。
 *   这一层是给未来的：web 端想用桌面能力时，按 `window.desktop?.isDesktop` 渐进增强即可，
 *   现在由本端菜单/托盘快捷键（Ctrl+P / Ctrl+Shift+S）驱动同样的实现。
 */
const { contextBridge, ipcRenderer } = require('electron')

/**
 * 版本号由主进程用 additionalArguments 注入。
 * 这里**不** require 本地模块：sandbox 化的 preload 运行在封闭环境里，
 * 只能 require 少数 Node 内建模块与 electron，require 相对路径会直接失败。
 */
const versionArg = process.argv.find((a) => a.startsWith('--erp-app-version='))
const appVersion = versionArg ? versionArg.split('=')[1] : 'unknown'

/** 允许的"主进程 → 渲染进程"事件通道（新增要写明理由） */
const SHELL_EVENT_CHANNELS = ['desktop:shell-action']

contextBridge.exposeInMainWorld('desktop', {
  isDesktop: true,
  meta: {
    platform: process.platform,
    arch: process.arch,
    electron: process.versions.electron,
    appVersion,
  },

  /** 桌面壳自检：端口/模式/后端可达性；给诊断面板或"离线提示"用 */
  getStatus: () => ipcRenderer.invoke('desktop:get-status'),

  /** 系统打印队列里的打印机列表（Web 端拿不到 deviceName，这是桌面独有的） */
  listPrinters: () => ipcRenderer.invoke('desktop:list-printers'),

  /** 打印当前已渲染页面；silent=true 时直打 config.printDevice，不弹系统对话框 */
  printCurrentPage: (options) => ipcRenderer.invoke('desktop:print', options || {}),

  /** 当前页面另存为 PDF（主进程弹保存对话框，返回落盘路径） */
  saveCurrentPageAsPdf: (options) => ipcRenderer.invoke('desktop:save-pdf', options || {}),

  /** 系统通知：任务在后台跑完时用，比抢焦点友好 */
  notify: (title, body) => ipcRenderer.invoke('desktop:notify', { title, body }),

  /** 订阅壳层动作（托盘菜单点击等），回调只收 {action}，不含任何主进程对象 */
  onShellAction: (callback) => {
    if (typeof callback !== 'function') return () => {}
    const listeners = SHELL_EVENT_CHANNELS.map((channel) => {
      const wrapped = (_event, payload) => callback(payload)
      ipcRenderer.on(channel, wrapped)
      return () => ipcRenderer.removeListener(channel, wrapped)
    })
    return () => listeners.forEach((off) => off())
  },
})
