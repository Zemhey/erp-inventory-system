#!/usr/bin/env node
/**
 * set-appid.mjs —— 一条命令把正式 appid 写进 src/manifest.json
 *
 *   node scripts/set-appid.mjs wx1234567890abcdef              # 微信小程序 appid（默认目标）
 *   node scripts/set-appid.mjs __UNI__ABCDE12 --target dcloud  # DCloud / HBuilderX appid
 *   node scripts/set-appid.mjs com.yunshang.erp --target packagename
 *   node scripts/set-appid.mjs --show                          # 只看当前三个值
 *
 * ★ 为什么不用 JSON.parse + JSON.stringify 回写：
 *   manifest.json 是 JSONC（允许 // 与 /* *\/ 注释），里面已经写了"包名是占位值"这类注释。
 *   整体序列化会把注释、空行、键顺序全抹掉，diff 变成整文件重写。
 *   这里改成**只 splice 目标 value 的那几个字节**，其余逐字节不变。
 *
 * 只用 Node 内置模块（fs/path）—— node_modules 是指向主构建区的 junction，不能装 jsonc-parser。
 */

import fs from 'node:fs'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'
import { getNode, lineAt, parseJsonc, quoteLike, replaceValue, toPlain } from './jsonc.mjs'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

const TARGETS = {
  'mp-weixin': {
    segs: ['mp-weixin', 'appid'],
    label: '微信小程序 AppID',
    re: /^wx[0-9a-f]{16}$/,
    hint: '小程序后台 → 开发管理 → 开发设置 → AppID（wx + 16 位小写十六进制，整段复制别手敲）',
    where: 'src/manifest.json 的 mp-weixin.appid',
  },
  dcloud: {
    segs: ['appid'],
    label: 'DCloud（HBuilderX）AppID',
    re: /^__UNI__[A-Z0-9]{6,}$/,
    hint: 'HBuilderX 登录 DCloud 账号 → 应用 → 重新获取 appid；包名/证书要归属该账号才能云打包',
    where: 'src/manifest.json 顶层 appid',
  },
  packagename: {
    segs: ['app-plus', 'distribute', 'android', 'packagename'],
    label: '安卓包名',
    re: /^[a-zA-Z_][a-zA-Z0-9_]*(\.[a-zA-Z_][a-zA-Z0-9_]+)+$/,
    hint: '反域名形式（com.<实际主体>.erp）；**上架后改不了**，同包名互相覆盖',
    where: 'src/manifest.json 的 app-plus.distribute.android.packagename',
  },
}

const HELP = `用法：node scripts/set-appid.mjs <新值> [--target ${Object.keys(TARGETS).join('|')}] [--dry-run] [--force]
      node scripts/set-appid.mjs --show

  --target   写哪个字段，默认 mp-weixin（微信小程序 appid）
  --dry-run  只打印将要做的改动，不落盘
  --force    跳过格式校验（例如想在演练里写一个假 appid）
  --manifest 指定别的 manifest 路径（默认 src/manifest.json），演练用
`

function main () {
  const argv = process.argv.slice(2)
  if (argv.includes('--help') || argv.includes('-h')) {
    process.stdout.write(HELP)
    return 0
  }
  const getOpt = (name) => {
    const i = argv.indexOf(name)
    return i > -1 ? argv[i + 1] : undefined
  }
  const flags = new Set(argv.filter((a) => a.startsWith('--')))
  const targetName = getOpt('--target') || 'mp-weixin'
  const target = TARGETS[targetName]
  if (!target) {
    process.stderr.write(`✖ 不认识 --target ${targetName}，可选：${Object.keys(TARGETS).join(' / ')}\n${HELP}`)
    return 2
  }
  const manifestPath = getOpt('--manifest') ? path.resolve(ROOT, getOpt('--manifest')) : path.join(ROOT, 'src', 'manifest.json')
  if (!fs.existsSync(manifestPath)) {
    process.stderr.write(`✖ 找不到文件：${manifestPath}\n`)
    return 2
  }
  const text = fs.readFileSync(manifestPath, 'utf8')
  let root
  try {
    root = parseJsonc(text)
  } catch (err) {
    process.stderr.write(`✖ manifest 解析失败（JSONC）：${err.message}\n`)
    return 2
  }
  const node = getNode(root, target.segs)

  if (flags.has('--show')) {
    for (const [name, t] of Object.entries(TARGETS)) {
      const n = getNode(root, t.segs)
      process.stdout.write(`${name.padEnd(12)} ${t.formatPath ?? t.segs.join('.')} = ${n ? JSON.stringify(toPlain(n)) : '（字段不存在）'}\n`)
    }
    return 0
  }

  const positional = argv.filter((a, i) => !a.startsWith('--') && argv[i - 1] !== '--target' && argv[i - 1] !== '--manifest')
  const next = new Set(['--target', '--manifest'])
  const values = []
  argv.forEach((a, i) => {
    if (a.startsWith('--')) return
    if (i > 0 && next.has(argv[i - 1])) return
    values.push(a)
  })
  const newValue = values[0]
  if (!newValue) {
    process.stderr.write(`✖ 没给新值。${HELP}`)
    return 2
  }
  if (!node) {
    process.stderr.write(`✖ ${target.where} 这个字段不存在 —— 先在 manifest 里补上键再跑本脚本（避免脚本盲插破坏结构）。\n`)
    return 2
  }
  const oldValue = String(toPlain(node) ?? '')
  if (flags.has('--force')) {
    process.stdout.write(`⚠ --force：跳过格式校验（写入的值微信/安卓侧可能不认）\n`)
  } else if (!target.re.test(newValue)) {
    process.stderr.write(
      `✖ 格式不符：${JSON.stringify(newValue)}\n` +
      `  要求：${target.label}（${target.re}）\n` +
      `  从哪拿：${target.hint}\n` +
      `  确实要写非规范值（演练/占位）就加 --force\n`
    )
    return 1
  }

  const patch = quoteLike(text.slice(node.start, node.end), newValue)
  const patched = replaceValue(text, node, patch)
  const line = lineAt(text, node.start)
  process.stdout.write(
    `▍${target.label} 写入\n` +
    `  文件：${path.relative(ROOT, manifestPath).replace(/\\/g, '/')}（第 ${line} 行，字段 ${target.segs.join('.')}）\n` +
    `  ${JSON.stringify(oldValue)} → ${JSON.stringify(newValue)}\n` +
    `  除这一处 value 外，注释/键顺序/缩进逐字节不变${flags.has('--dry-run') ? '（--dry-run：未落盘）' : ''}\n`
  )
  if (flags.has('--dry-run')) return 0
  fs.writeFileSync(manifestPath, patched, 'utf8')
  process.stdout.write(`  已落盘。下一步：npm run build:mp-weixin && node scripts/preflight.mjs mp-weixin\n`)
  if (/^wx/.test(newValue) && !/^[0-9a-f]{16}$/.test(newValue.slice(2))) {
    process.stdout.write('  ⚠ 这个 appid 看着像演练用的假号 —— 它只证明脚本可用，**不等于能发布**；发布要用后台复制的真 AppID。\n')
  }
  return 0
}

process.exitCode = main()
