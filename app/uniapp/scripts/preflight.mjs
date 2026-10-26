#!/usr/bin/env node
/**
 * preflight.mjs —— 出包前置检查
 *
 * 目的：把"到设备上一定会坏"的事，在构建/打包之前就报出来，而不是在真机上撞墙后再回查配置。
 * 只读配置，不改任何东西；只用 Node 内置模块（node_modules 是指向主构建区的 junction，装不了依赖）。
 *
 * 用法：
 *   node scripts/preflight.mjs <mp-weixin|app|h5|all> [--warn-only] [--allow-http] [--json]
 *
 * 退出码：
 *   0  没有阻塞项（可能有 ⚠/ℹ）
 *   1  有阻塞项 —— 默认行为，build 会被 preflight 拦下来
 *   2  参数/文件错误（平台名不认识、manifest 读不了）—— 同样会拦住 build
 *
 * 逃生口（CI / 演示 / 内网真机联调时强行出包）：
 *   --warn-only            把阻塞项降级为 ⚠ 后以 0 退出（检查仍然全部跑完、全部打印）
 *   PREFLIGHT_MODE=warn    同上（环境变量形式，便于 CI 与 npm script）
 *   ERP_SKIP_PREFLIGHT=1   完全跳过检查（不建议，除非你已知道自己在干什么）
 *   --allow-http           允许非 H5 端用内网 http 地址（真机联调场景），降级为 ⚠
 */

import fs from 'node:fs'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'
import { getNode, lineAt, parseJsonc, readManifest, toPlain } from './jsonc.mjs'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

const HELP = `用法：node scripts/preflight.mjs <mp-weixin|app|h5|all> [选项]

  --warn-only     阻塞项降级为提醒，退出码 0（强行出包用）
  --allow-http    非 H5 端允许内网 http 基址（真机联调），降级为提醒
  --verbose       连 ℹ 信息一起打印
  --json          机器可读输出（给 CI 收集）
  --help          看这个帮助

环境变量：PREFLIGHT_MODE=warn（等价 --warn-only）、ERP_SKIP_PREFLIGHT=1（完全跳过）
`

const PLACEHOLDER_RE = /<[^\s>]*>|\byour[-_]?(domain|host|company)|example\.com|changeme|TODO|待填|替换|你的/i
const TOURIST_APPID = 'touristappid'
/** 微信小程序 appid：wx + 16 位小写十六进制 */
const WX_APPID_RE = /^wx[0-9a-f]{16}$/
/** DCloud（HBuilderX）appid：__UNI__ + 大写字母数字 */
const DCLLOUD_APPID_RE = /^__UNI__[A-Z0-9]{6,}$/
const ANDROID_PACKAGE_RE = /^[a-zA-Z_][a-zA-Z0-9_]*(\.[a-zA-Z_][a-zA-Z0-9_]+)+$/

function main () {
  const argv = process.argv.slice(2)
  const flags = new Set(argv.filter((a) => a.startsWith('-')))
  if (flags.has('--help') || flags.has('-h')) {
    process.stdout.write(HELP)
    return 0
  }
  const positional = argv.filter((a) => !a.startsWith('-'))
  const platformArg = (positional[0] || process.env.PREFLIGHT_PLATFORM || '').trim()
  if (!platformArg) {
    process.stderr.write(`✖ 没给平台。${HELP}`)
    return 2
  }
  const targets = platformArg === 'all'
    ? ['mp-weixin', 'app', 'h5']
    : [platformArg]
  for (const t of targets) {
    if (!['mp-weixin', 'app', 'h5'].includes(t)) {
      process.stderr.write(`✖ 不认识的平台「${t}」，只支持 mp-weixin / app / h5 / all\n`)
      return 2
    }
  }

  if (process.env.ERP_SKIP_PREFLIGHT === '1') {
    process.stdout.write('⚠ ERP_SKIP_PREFLIGHT=1 —— 本次跳过出包前置检查（配置错了自负后果）\n')
    return 0
  }
  const warnOnly = flags.has('--warn-only') || flags.has('--warn') || /warn/i.test(process.env.PREFLIGHT_MODE || '')
  const allowHttp = flags.has('--allow-http') || process.env.PREFLIGHT_ALLOW_HTTP === '1'
  const verbose = flags.has('--verbose') || flags.has('-v')
  const asJson = flags.has('--json')

  let manifest
  try {
    manifest = readManifest(ROOT)
  } catch (err) {
    process.stderr.write(`✖ manifest 读不了：${err.message}\n  去哪看：app/uniapp/src/manifest.json\n`)
    return 2
  }

  const issues = []
  const add = (level, code, title, detail, fix) =>
    issues.push({ level, code, platform: fix.platform || '-', title, detail, ...fix })

  // ───────────── 后端基址：非 H5 端没有代理，构建时必须注入绝对地址 ─────────────
  const envInfo = resolveViteEnv()
  for (const platform of targets) {
    checkApiBase(platform, envInfo, add)
    if (platform === 'mp-weixin') checkMpWeixin(manifest, add)
    if (platform === 'app') checkApp(manifest, add)
    if (platform === 'h5') checkH5(manifest, add)
  }

  if (asJson) {
    process.stdout.write(JSON.stringify({ platformArg, warnOnly, allowHttp, env: envInfo, issues }, null, 2) + '\n')
    return issues.some((i) => i.level === 'FAIL') && !warnOnly ? 1 : 0
  }

  printReport({ targets, manifest, envInfo, issues, warnOnly, allowHttp, verbose })
  return issues.some((i) => i.level === 'FAIL') && !warnOnly ? 1 : 0
}

// ═══════════════════════════ 检查项 ═══════════════════════════

function checkApiBase (platform, envInfo, add) {
  const raw = envInfo.value
  const field = 'VITE_API_BASE_URL'
  const where = { file: envInfo.file || 'src/config.ts', line: envInfo.line, field }
  if (platform === 'h5') {
    if (!raw || raw.startsWith('/')) {
      add('INFO', 'API_BASE_RELATIVE', 'H5 用相对路径 /api/v1（走 vite.config.ts 代理）', '只影响 H5：dev 有代理、构建产物需要由同域反代接管。', { platform, ...where })
    } else {
      add('INFO', 'API_BASE_ABS', `H5 已注入绝对地址 ${redact(raw)}`, 'H5 绝对地址会绕过代理，注意跨域。', { platform, ...where })
    }
    return
  }
  if (!raw) {
    add('FAIL', 'API_BASE_MISSING', `${platform} 端没有配置 VITE_API_BASE_URL`,
      '后果：src/config.ts 会退化成相对路径 /api/v1，小程序与 App 里没有这层代理 —— 构建能过、装上设备后**所有接口 404/失败**。',
      { platform, ...where, how: '新建 app/uniapp/.env.production.local（模板见 .env.production.local.example），或在构建命令里注入', example: 'cp .env.production.local.example .env.production.local' })
    return
  }
  if (raw.startsWith('/')) {
    add('FAIL', 'API_BASE_RELATIVE_NONH5', `${platform} 端填了相对地址 ${redact(raw)}`,
      '后果：非 H5 端没有 dev 代理，相对路径在设备上必然失败。',
      { platform, ...where, how: '改成 http(s)://主机[:端口]/api/v1 形式的绝对地址', example: 'VITE_API_BASE_URL=https://api.你的域名/api/v1' })
    return
  }
  let url
  try {
    url = new URL(raw)
  } catch {
    add('FAIL', 'API_BASE_NOT_URL', `${platform} 端的 VITE_API_BASE_URL 不是合法 URL：${redact(raw)}`,
      '后果：api/client.ts 拼出来的请求地址不可用。',
      { platform, ...where, how: '填完整 URL（含协议），结尾不要带多余的 /', example: 'VITE_API_BASE_URL=https://api.example.org/api/v1' })
    return
  }
  if (PLACEHOLDER_RE.test(raw)) {
    add('FAIL', 'API_BASE_PLACEHOLDER', `${platform} 端地址仍像占位符：${redact(raw)}`,
      '后果：域名没换成真域名之前，装到设备上必然连不上。',
      { platform, ...where, how: '换成实际后端域名（发布要 HTTPS + 已备案）', example: 'VITE_API_BASE_URL=https://erp.你的已备案域名/api/v1' })
    return
  }
  if (url.protocol === 'http:') {
    const lan = /^(10\.|192\.16\.|172\.(1[6-9]|2\d|3[01])\.|127\.|localhost)/i.test(url.hostname)
    add(allowHttp && lan ? 'WARN' : 'FAIL', 'API_BASE_NOT_HTTPS', `${platform} 端用了 http 明文：${redact(raw)}`,
      lan
        ? '内网/局域网地址可以真机联调，但**上传微信小程序一定不行**：合法域名要求 HTTPS + 已备案。'
        : '微信小程序 request 合法域名必须 HTTPS + 已备案；安卓 http 也过不了应用市场合规。',
      { platform, ...where, how: '发布前换成备案好的 HTTPS 域名；只是内网联调就加 --allow-http（或 PREFLIGHT_ALLOW_HTTP=1）强行出包', example: 'node scripts/preflight.mjs app --allow-http' })
    return
  }
  if (url.protocol !== 'https:') {
    add('FAIL', 'API_BASE_SCHEME', `${platform} 端协议不是 http/https：${redact(raw)}`, '后果：uni.request 只认 http/https。', { platform, ...where, how: '改成 https://', example: 'VITE_API_BASE_URL=https://api.你的域名/api/v1' })
    return
  }
  add('INFO', 'API_BASE_OK', `${platform} 端 HTTPS 绝对地址已注入：${redact(raw)}`,
    '域名备案与"小程序后台合法域名"这两件事本地查不了，见 README「P3 备案与 HTTPS」。', { platform, ...where })
}

function checkMpWeixin (manifest, add) {
  const { file, text, root } = manifest
  const node = getNode(root, ['mp-weixin', 'appid'])
  const appid = node ? String(toPlain(node) ?? '').trim() : ''
  const where = { file: rel(file), line: node ? lineAt(text, node.start) : undefined, field: 'mp-weixin.appid' }
  if (!appid) {
    add('FAIL', 'WX_APPID_MISSING', 'mp-weixin.appid 是空的',
      '后果：微信开发者工具导入后无法预览，上传体验版直接报"appid 不能为空"。',
      { platform: 'mp-weixin', ...where, how: '用微信公众/小程序平台后台的 AppID（wx 开头 18 位）', example: 'node scripts/set-appid.mjs wx1234567890abcdef' })
  } else if (appid.toLowerCase() === TOURIST_APPID) {
    add('FAIL', 'WX_APPID_TOURIST', 'mp-weixin.appid = "touristappid"（微信游客号）',
      '后果：只能本机模拟器看界面 —— **上传不了、真机预览开不了、体验版发不出去**，等于没出包。',
      { platform: 'mp-weixin', ...where, how: '登录小程序后台 → 开发管理 → 开发设置 → AppID，然后一条命令写入', example: 'node scripts/set-appid.mjs wx1234567890abcdef' })
  } else if (!WX_APPID_RE.test(appid)) {
    add('FAIL', 'WX_APPID_FORMAT', `mp-weixin.appid 格式不像正式号：${redact(appid)}`,
      '后果：上传/预览会在微信侧报"appid 无效"。正式 appid 形如 wx + 16 位十六进制。',
      { platform: 'mp-weixin', ...where, how: '从小程序后台原样复制 AppID（别手敲）', example: 'node scripts/set-appid.mjs <从后台复制的 AppID>' })
  } else {
    add('INFO', 'WX_APPID_OK', `mp-weixin.appid = ${redact(appid)}（格式合法）`,
      '格式合法 ≠ 已发布：还要该 appid 对应的小程序完成认证 + 备案 + 配置合法域名。', { platform: 'mp-weixin', ...where })
  }

  const setting = getNode(root, ['mp-weixin', 'setting', 'urlCheck'])
  if (setting && toPlain(setting) === false) {
    add('WARN', 'WX_URLCHECK_OFF', 'mp-weixin.setting.urlCheck = false（关闭合法域名校验）',
      '后果：开发者工具里能勾掉域名校验跑通，**真机不看这个开关** —— 后台白名单没配就全线失败。这是"开发者工具能跑"与"手机能跑"的头号差异来源。',
      { platform: 'mp-weixin', file: rel(file), line: lineAt(text, setting.start), field: 'mp-weixin.setting.urlCheck',
        how: '联调期保持 false 没问题；发布前在小程序后台「开发管理 → 服务器域名」配 request 合法域名（HTTPS + 已备案）', example: 'https://api.你的已备案域名' })
  }

  add('INFO', 'WX_PRIVACY', '小程序后台的《用户隐私保护指引》要覆盖扫码用到的能力',
    '本项目 3 个页面用 uni.scanCode（发货拣货 / 扫码收货 / 库存盘点），未申报隐私接口时线上会提示"api 需要升级/未授权"。这是后台配置，manifest 里查不到。',
    { platform: 'mp-weixin', file: '小程序后台（不是本仓库）', field: '用户隐私保护指引 / 接口权限' })
}

function checkApp (manifest, add) {
  const { file, text, root } = manifest
  const where = (field, node) => ({ file: rel(file), line: node ? lineAt(text, node.start) : undefined, field })

  // DCloud appid（HBuilderX 云打包要登录账号，且 appid 属于该账号）
  const dcloud = getNode(root, ['appid'])
  const dcloudVal = dcloud ? String(toPlain(dcloud) ?? '').trim() : ''
  if (!DCLLOUD_APPID_RE.test(dcloudVal)) {
    add('FAIL', 'DCloud_APPID', `顶层 appid（DCloud）不像合法值：${redact(dcloudVal)}`,
      '后果：HBuilderX 云打包要求登录 DCloud 账号并使用其名下 appid，非法/陌生值会被拒绝或改写。',
      { platform: 'app', ...where('appid', dcloud), how: 'HBuilderX 登录 → 重新获取应用 appid', example: 'node scripts/set-appid.mjs __UNI__XXXXXXX --target dcloud' })
  } else {
    add('WARN', 'DCloud_APPID_ACCOUNT', `顶层 appid = ${redact(dcloudVal)}，但本地无法确认它属于哪个 DCloud 账号`,
      '后果：HBuilderX 云打包会校验登录账号与 appid 的归属；账号没绑这个 appid 时报错打不出包。这是外部资源（HBuilderX 账号）问题，不是代码问题。',
      { platform: 'app', ...where('appid', dcloud), how: '用打包那台机器的 HBuilderX 登录同一账号，或在 HBuilderX 里重新生成 appid', example: 'HBuilderX → 清单文件 → 传统打包 → 基础配置 → 应用 AppID' })
  }

  // 安卓包名
  const pkg = getNode(root, ['app-plus', 'distribute', 'android', 'packagename'])
  const pkgVal = pkg ? String(toPlain(pkg) ?? '').trim() : ''
  if (!pkgVal) {
    add('FAIL', 'PKG_MISSING', 'app-plus.distribute.android.packagename 为空',
      '后果：HBuilderX 云打包的**必填项**，缺了直接打不出 APK。',
      { platform: 'app', ...where('app-plus.distribute.android.packagename', pkg),
        how: '填反域名形式的包名（发布后不能改，必须按实际主体定）', example: '"packagename": "com.yunshang.erp"' })
  } else if (!ANDROID_PACKAGE_RE.test(pkgVal)) {
    add('FAIL', 'PKG_FORMAT', `packagename 不合法：${redact(pkgVal)}`,
      '后果：Android 侧拒绝安装 / 云打包报错。要求形如 com.主体.应用（全小写字母数字下划线，至少两段）。',
      { platform: 'app', ...where('app-plus.distribute.android.packagename', pkg), how: '改成反域名形式', example: 'node scripts/set-appid.mjs com.yunshang.erp --target packagename' })
  } else if (PLACEHOLDER_RE.test(pkgVal) || /\b(example|test|demo|yourcompany|placeholder)\b/i.test(pkgVal)) {
    add('WARN', 'PKG_PLACEHOLDER', `packagename 看起来是占位值：${redact(pkgVal)}`,
      '后果：**能出包**，但包名一旦上架/装机就换不了（同包名互相覆盖、签名与升级链路绑死）。发布前必须按实际主体改定。',
      { platform: 'app', ...where('app-plus.distribute.android.packagename', pkg), how: '确定主体（公司/个人开发者）反域名后替换', example: '"packagename": "com.<你的主体>.erp"' })
  } else {
    add('INFO', 'PKG_OK', `packagename = ${redact(pkgVal)}`, '确认这是**实际主体**的包名：上架后改不了。', { platform: 'app', ...where('app-plus.distribute.android.packagename', pkg) })
  }

  // 版本号
  const vcNode = getNode(root, ['versionCode'])
  const vnNode = getNode(root, ['versionName'])
  const vcRaw = vcNode ? toPlain(vcNode) : undefined
  const vc = Number(String(vcRaw ?? '').trim())
  if (!Number.isInteger(vc) || vc <= 0) {
    add('FAIL', 'VERSION_CODE', `versionCode 不是正整数：${redact(String(vcRaw ?? ''))}`,
      '后果：云打包要求整数 versionCode；同包名安装时 versionCode 低会被拒装/降级。',
      { platform: 'app', ...where('versionCode', vcNode), how: '每次发布递增（整数，不是版本号）', example: '"versionCode": "101"' })
  }
  const vn = String(vnNode ? toPlain(vnNode) ?? '' : '').trim()
  if (!/^\d+(\.\d+){0,2}$/.test(vn)) {
    add('WARN', 'VERSION_NAME', `versionName 不是 x.y.z 形式：${redact(vn)}`,
      '后果：能出包，但应用市场/用户看到的版本串不好比对。',
      { platform: 'app', ...where('versionName', vnNode), how: '与 versionCode 一起递增', example: '"versionName": "0.2.0"' })
  }

  // 图标：缺了能出包（HBuilderX 用默认图标），路径写了但文件不存在才是打包失败
  const icons = getNode(root, ['app-plus', 'distribute', 'icons'])
  if (!icons) {
    const master = findIconMaster()
    add('WARN', 'ICONS_MISSING', 'app-plus.distribute.icons 不存在（没配应用图标）',
      master
        ? '有主图但还没产出尺寸：跑 node scripts/make-icons.mjs 生成，再 --write-manifest 写进清单。'
        : '后果：**不是打不出包** —— HBuilderX 会用它的默认图标出 APK，能装能用，但图标不是你设计的（评审/上架一眼就能看出来）。',
      { platform: 'app', ...where('app-plus.distribute.icons', icons),
        how: '准备一张 ≥1024×1024 的方形 PNG 主图（默认放 src/uni-app-icons/source/master.png），然后产出各尺寸并写入清单',
        example: 'node scripts/make-icons.mjs --master path/to/master.png --write-manifest' })
  } else {
    const missing = []
    walkIconPaths(icons, (p) => {
      if (!p || typeof p !== 'string') return
      const abs = path.resolve(ROOT, p)
      if (!fs.existsSync(abs)) missing.push(p)
    })
    if (missing.length) {
      add('FAIL', 'ICONS_FILES', `icons 里引用了不存在的文件（${missing.length} 个）：${missing.slice(0, 4).join(', ')}${missing.length > 4 ? ' …' : ''}`,
        '后果：云打包直接报"文件不存在"，这次打包白跑。',
        { platform: 'app', ...where('app-plus.distribute.icons', icons), how: '先生成图标再写清单', example: 'node scripts/make-icons.mjs --master src/uni-app-icons/source/master.png --write-manifest' })
    } else {
      add('INFO', 'ICONS_OK', 'app-plus.distribute.icons 已配置且文件都在', '图标路径相对工程根目录（app/uniapp/）。', { platform: 'app', ...where('app-plus.distribute.icons', icons) })
    }
  }

  // 权限：扫码靠 uni.scanCode（真机 = 摄像头），缺 CAMERA 时扫码键必然调不起来
  const permNode = getNode(root, ['app-plus', 'distribute', 'android', 'permissions'])
  const perms = permNode ? JSON.stringify(toPlain(permNode) ?? '') : ''
  const scanner = detectScannerUsage()
  if (scanner.files.length) {
    const has = /android\.permission\.CAMERA/.test(perms)
    if (has) {
      add('INFO', 'PERM_CAMERA', `CAMERA 权限已在清单里（${scanner.files.length} 个页面用 uni.scanCode：${scanner.files.join(' / ')}）`,
        'H5 与微信开发者工具里 uni.scanCode 调不起摄像头，页面已实现降级手录；这条权限只在安卓真机上生效。',
        { platform: 'app', ...where('app-plus.distribute.android.permissions', permNode) })
    } else {
      add('FAIL', 'PERM_CAMERA', `清单里没有 android.permission.CAMERA，但 ${scanner.files.length} 个页面用 uni.scanCode`,
        '后果：真机点「扫码」调不起摄像头 —— 页面会降级成手录，业务不会崩，但 PDA 场景等于废掉这颗键。',
        { platform: 'app', ...where('app-plus.distribute.android.permissions', permNode),
          how: '在 permissions 数组里加一条 uses-permission', example: '"<uses-permission android:name=\\"android.permission.CAMERA\\"/>"' })
    }
  }

  const ios = getNode(root, ['app-plus', 'distribute', 'ios'])
  if (ios && (ios.type !== 'object' || !ios.children.length)) {
    add('INFO', 'IOS_EMPTY', 'app-plus.distribute.ios 是空对象',
      '本次交付只做微信小程序 + 安卓；要出 iOS 需要 Apple 开发者账号 + 证书 + 描述文件（外部资源），届时再补。',
      { platform: 'app', ...where('app-plus.distribute.ios', ios), how: '需要 iOS 时才配：bundleidentifier / dSYMs / 证书', example: '"ios": { "bundleidentifier": "com.yunshang.erp" }' })
  }
  const sdk = getNode(root, ['app-plus', 'distribute', 'sdkConfigs'])
  if (sdk && (sdk.type !== 'object' || !sdk.children.length)) {
    add('INFO', 'SDK_EMPTY', 'app-plus.distribute.sdkConfigs 是空对象',
      '当前移动端没用推送/支付/第三方登录，空配置是符合现状的；哪天要接 uniPush 或微信支付，这里才需要填（涉及各家账号）。',
      { platform: 'app', ...where('app-plus.distribute.sdkConfigs', sdk) })
  }
}

function checkH5 (manifest, add) {
  const { file, text, root } = manifest
  const node = getNode(root, ['h5', 'router', 'mode'])
  add('INFO', 'H5_ROUTER', `h5.router.mode = ${String(toPlain(node) ?? '（未配，默认 hash）')}`,
    'H5 端不需要 appid/包名/图标；hash 模式免服务端 rewrite，部署最省事。',
    { platform: 'h5', file: rel(file), line: node ? lineAt(text, node.start) : undefined, field: 'h5.router.mode' })
}

// ═══════════════════════════ 辅助 ═══════════════════════════

/** Vite 的优先级：进程环境 > .env.production.local > .env.production > .env */
function resolveViteEnv () {
  const files = ['.env.production.local', '.env.production', '.env']
  let value = process.env.VITE_API_BASE_URL ? String(process.env.VITE_API_BASE_URL).trim() : ''
  let file = value ? 'process.env' : ''
  let line
  if (!value) {
    for (const f of files) {
      const abs = path.join(ROOT, f)
      if (!fs.existsSync(abs)) continue
      const parsed = parseDotenv(fs.readFileSync(abs, 'utf8'))
      if (parsed.value) {
        value = parsed.value
        file = f
        line = parsed.line
        break
      }
    }
  }
  return { value, file, line, exists: files.map((f) => [f, fs.existsSync(path.join(ROOT, f))]) }
}

function parseDotenv (text) {
  const lines = text.split(/\r?\n/)
  for (let i = 0; i < lines.length; i++) {
    const m = /^\s*(?:export\s+)?VITE_API_BASE_URL\s*=\s*(.*)$/.exec(lines[i])
    if (!m) continue
    let v = m[1].trim()
    const hash = v.indexOf('#')
    if (hash > -1 && !/^["']/.test(v)) v = v.slice(0, hash).trim()
    if (/^["'].*["']$/.test(v)) v = v.slice(1, -1)
    return { value: v.trim(), line: i + 1 }
  }
  return { value: '', line: undefined }
}

function findIconMaster () {
  const cands = ['src/uni-app-icons/source/master.png', 'src/uni-app-icons/source/master.jpg', 'src/uni-app-icons/source']
  for (const c of cands.slice(0, 2)) if (fs.existsSync(path.join(ROOT, c))) return c
  const dir = path.join(ROOT, cands[2])
  if (fs.existsSync(dir)) {
    const png = fs.readdirSync(dir).filter((f) => /\.png$/i.test(f))
    if (png.length) return `src/uni-app-icons/source/${png[0]}`
  }
  return null
}

function walkIconPaths (node, cb) {
  if (!node) return
  if (node.type === 'object') for (const c of node.children) walkIconPaths(c.node, cb)
  else if (node.type === 'array') for (const it of node.items) walkIconPaths(it, cb)
  else if (node.type === 'string') cb(node.value)
}

/** 从页面源码判定"扫码到底靠什么" —— 不猜页面名 */
function detectScannerUsage () {
  const files = []
  const pagesDir = path.join(ROOT, 'src', 'pages')
  const walk = (dir) => {
    if (!fs.existsSync(dir)) return
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      const abs = path.join(dir, e.name)
      if (e.isDirectory()) { walk(abs); continue }
      if (!/\.(vue|ts|js)$/.test(e.name)) continue
      const src = fs.readFileSync(abs, 'utf8')
      if (/uni\.scanCode\s*\(/.test(src) || /<camera[\s>]/.test(src) || /createCameraContext/.test(src)) {
        files.push(rel(abs))
      }
    }
  }
  walk(pagesDir)
  return { files }
}

function rel (p) {
  return path.relative(ROOT, p).replace(/\\/g, '/')
}

function redact (v) {
  const s = String(v ?? '')
  return s ? JSON.stringify(s.length > 80 ? s.slice(0, 77) + '…' : s) : '""'
}

const MARK = { FAIL: '✖', WARN: '⚠', INFO: 'ℹ' }

function printReport ({ targets, manifest, envInfo, issues, warnOnly, allowHttp, verbose }) {
  const out = []
  out.push('')
  out.push('▍出包前置检查（preflight）')
  out.push(`  平台：${targets.join(' / ')}    模式：${warnOnly ? '⚠ warn-only（不拦构建）' : '严格（有 ✖ 就 exit 1）'}`)
  out.push(`  工程：${ROOT}`)
  out.push(`  清单：${rel(manifest.file)}    name=${JSON.stringify(toPlain(getNode(manifest.root, ['name'])))}  versionName=${JSON.stringify(toPlain(getNode(manifest.root, ['versionName'])))}  versionCode=${JSON.stringify(toPlain(getNode(manifest.root, ['versionCode'])))}`)
  out.push(`  后端基址：${envInfo.value ? `${redact(envInfo.value)}（来源 ${envInfo.file}${envInfo.line ? ':' + envInfo.line : ''}）` : '未设置（三端 .env* 里都没有 VITE_API_BASE_URL）'}`)
  if (allowHttp) out.push('  已开 --allow-http：内网 http 地址按提醒处理')
  out.push('')

  const order = { FAIL: 0, WARN: 1, INFO: 2 }
  const sorted = [...issues].sort((a, b) => order[a.level] - order[b.level])
  const shown = sorted.filter((i) => i.level !== 'INFO' || verbose)
  for (const i of shown) {
    const level = warnOnly && i.level === 'FAIL' ? 'WARN' : i.level
    out.push(`${MARK[level]} [${i.code}] (${i.platform}) ${i.title}`)
    if (i.detail) {
      const lead = level === 'INFO' ? '说明' : '后果'
      out.push(`    ${/^(后果|说明)/.test(i.detail) ? i.detail : `${lead}：${i.detail}`}`)
    }
    const loc = `${i.file || ''}${i.line ? `:${i.line}` : ''}${i.field ? ` → ${i.field}` : ''}`
    if (loc) out.push(`    在哪：${loc}`)
    if (i.how) out.push(`    怎么改：${i.how}`)
    if (i.example) out.push(`    示例：${i.example}`)
    out.push('')
  }

  const n = { FAIL: 0, WARN: 0, INFO: 0 }
  for (const i of issues) n[i.level]++
  if (n.INFO && !verbose) out.push(`  （另有 ${n.INFO} 条 ℹ 信息不拦构建也不建议忽略，加 --verbose 展开）`)
  out.push('─'.repeat(72))
  out.push(`  ✖ 阻塞 ${n.FAIL}    ⚠ 提醒 ${n.WARN}    ℹ 信息 ${n.INFO}`)
  if (n.FAIL && !warnOnly) {
    out.push('  结论：**现在出包一定拿不到能用的东西** —— 先把 ✖ 项清零再构建。')
    out.push('  内网真机联调 / CI 演示要强行出包（检查照样跑、照样打印，只是不拦）：')
    out.push(`    node scripts/preflight.mjs ${targets[0]} --allow-http --warn-only`)
    const buildScript = { 'mp-weixin': 'build:mp-weixin', app: 'build:app', h5: 'build:h5' }[targets[0]] || 'build:mp-weixin'
    out.push(`    PREFLIGHT_MODE=warn npm run ${buildScript}   （或 npm run ${buildScript}:force）`)
  } else if (n.FAIL && warnOnly) {
    out.push(`  结论：warn-only —— 上面 ${n.FAIL} 项阻塞已降级为提醒，构建继续（产物照样能出，但按上面写的坏）。`)
  } else {
    out.push('  结论：没有阻塞项，可以继续构建。')
  }
  out.push('')
  process.stdout.write(out.join('\n') + '\n')
}

process.exitCode = main()
