#!/usr/bin/env node
/**
 * make-icons.mjs —— 由一张主图产出 HBuilderX 云打包要的各尺寸应用图标
 *
 *   node scripts/make-icons.mjs                        # 用默认主图 src/uni-app-icons/source/master.png
 *   node scripts/make-icons.mjs --master ../logo.png --write-manifest
 *   node scripts/make-icons.mjs --check                # 只校验主图与产物，不写文件
 *
 * ★ 设计前提（不接受"生成个纯色方块凑数"）：
 *   没有主图就 exit 1。图标是设计资产，不是配置项 —— 脚本能算尺寸，算不出品牌。
 *   没图标**不影响出包**（HBuilderX 会用它的默认图标），所以 preflight 里这条是 ⚠ 不是 ✖。
 *
 * 只用 Node 内置模块（fs/path/zlib）：node_modules 是指向主构建区的 junction，装不了 pngjs/sharp。
 * PNG 解码只支持非隔行、位深 8（16 取高位）与调色板，覆盖设计导出的常规情况；
 * 其它格式（interlaced / JPEG / WebP）会明确报错让你回导出工具改，而不是硬猜像素。
 */

import fs from 'node:fs'
import path from 'node:path'
import process from 'node:process'
import zlib from 'node:zlib'
import { fileURLToPath } from 'node:url'
import { getNode, insertKeyIntoObject, parseJsonc, toPlain } from './jsonc.mjs'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const OUT_DIR = path.join(ROOT, 'src', 'uni-app-icons')
const DEFAULT_MASTER = path.join(OUT_DIR, 'source', 'master.png')
const MIN_MASTER = 1024

/** HBuilderX / Android 启动图标密度（严格尺寸，差一个像素云打包会挑出来） */
const ANDROID = [
  ['ldpi', 36], ['mdpi', 48], ['hdpi', 72], ['xhdpi', 96], ['xxhdpi', 144], ['xxxhdpi', 192],
]
/** iOS 用方形集（本次不出 iOS 包，先把文件备着；写清单只写 android 段） */
const IOS = [
  ['iphone@2x', 120], ['iphone@3x', 180], ['ipad', 76], ['ipad@2x', 152], ['appstore', 1024],
]

function fail (msg, help) {
  process.stderr.write(`✖ ${msg}\n${help ? `  ${help}\n` : ''}`)
  return 1
}

function main () {
  const argv = process.argv.slice(2)
  if (argv.includes('--help') || argv.includes('-h')) {
    process.stdout.write('用法：node scripts/make-icons.mjs [--master <png>] [--write-manifest] [--check] [--force]\n')
    return 0
  }
  const opt = (name) => {
    const i = argv.indexOf(name)
    return i > -1 ? argv[i + 1] : undefined
  }
  const has = (name) => argv.includes(name)
  const masterPath = opt('--master') ? path.resolve(ROOT, opt('--master')) : DEFAULT_MASTER

  if (!fs.existsSync(masterPath)) {
    return fail(
      `主图不存在：${rel(masterPath)}`,
      '本脚本**不会**凭空造图标 —— 纯色方块糊出来的图标比默认图标更难看，也不满足 HBuilderX 的尺寸/视觉要求。\n' +
      '  1) 让设计给一张 ≥' + MIN_MASTER + '×' + MIN_MASTER + ' 的方形 PNG（导出时把主体缩到中心 ~80% 安全区，四边留透明）\n' +
      `  2) 存成 ${rel(DEFAULT_MASTER)}，或 --master 指到实际路径\n` +
      '  3) node scripts/make-icons.mjs --write-manifest && node scripts/preflight.mjs app --verbose\n' +
      '  现在没图标也照样能出包：HBuilderX 用默认图标，只是图标不是我们的（见 README P1 一节）'
    )
  }

  let img
  try {
    img = decodePng(fs.readFileSync(masterPath))
  } catch (err) {
    return fail(`主图读不了：${err.message}`, `换一张普通 PNG（非隔行、位深 8 或调色板）再跑：${rel(masterPath)}`)
  }
  process.stdout.write(`▍主图 ${rel(masterPath)}  ${img.width}×${img.height}\n`)
  if (img.width !== img.height) {
    process.stdout.write(`  ⚠ 主图不是正方形 —— 按短边**居中裁方**再缩放（会裁掉边缘，确认主体在中心）\n`)
  }
  if (Math.min(img.width, img.height) < MIN_MASTER) {
    const msg = `主图短边只有 ${Math.min(img.width, img.height)}px，小于要求的 ${MIN_MASTER}px —— 放大出来的 1024 图是糊的（appstore/上架图会被打回）。加 --force 可以继续（只出安卓小尺寸）`
    if (!has('--force')) return fail(msg, '重新导一张 ≥1024×1024 的原图')
    process.stdout.write(`  ⚠ ${msg}\n`)
  }
  const square = centerCropSquare(img)

  if (has('--check')) {
    process.stdout.write(`  ✓ 主图可用（${square.width}×${square.height}，将产出 ${ANDROID.length + IOS.length} 个尺寸）\n`)
    return 0
  }

  const written = []
  for (const [group, sizes] of [['android', ANDROID], ['ios', IOS]]) {
    const dir = path.join(OUT_DIR, group)
    fs.mkdirSync(dir, { recursive: true })
    for (const [name, size] of sizes) {
      const file = path.join(dir, `${name}.png`)
      const out = encodePng(resizeArea(square, size, size))
      fs.writeFileSync(file, out)
      const back = decodePng(out)
      if (back.width !== size || back.height !== size) throw new Error(`产物自检失败：${rel(file)} 尺寸不对`)
      written.push({ group, name, size, file })
    }
  }
  process.stdout.write(`  已产出 ${written.length} 个尺寸（每个都回读 IHDR 验过尺寸）：\n`)
  for (const w of written) process.stdout.write(`    ${w.group}/${path.basename(w.file)}  ${w.size}×${w.size}  ${fs.statSync(w.file).size}B\n`)

  if (has('--write-manifest')) {
    const file = path.join(ROOT, 'src', 'manifest.json')
    const text = fs.readFileSync(file, 'utf8')
    const root = parseJsonc(text)
    const dist = getNode(root, ['app-plus', 'distribute'])
    if (!dist) return fail('app-plus.distribute 不存在，无法写入 icons', '先在 manifest.json 里补 distribute 对象')
    if (getNode(root, ['app-plus', 'distribute', 'icons'])) {
      process.stdout.write('  ℹ app-plus.distribute.icons 已存在，未覆盖（要改尺寸路径请手工编辑，脚本不动带注释的行）\n')
    } else {
      const block = { android: {} }
      for (const [name] of ANDROID) block.android[name] = rel(path.join(OUT_DIR, 'android', `${name}.png`))
      const next = insertKeyIntoObject(text, dist, 'icons', block)
      fs.writeFileSync(file, next, 'utf8')
      process.stdout.write(`  ✓ 已写入 src/manifest.json 的 app-plus.distribute.icons（只插了 icons 键，注释与其余键序未动）\n`)
      process.stdout.write('    iOS 段没写：本次不出 iOS 包，且要 Apple 账号 + 证书才有意义。文件已在 src/uni-app-icons/ios/ 备着。\n')
    }
  } else {
    process.stdout.write('  ℹ 没写 manifest：加 --write-manifest 才会把 app-plus.distribute.icons 指过来\n')
  }
  process.stdout.write('  下一步：node scripts/preflight.mjs app --verbose\n')
  return 0
}

// ═════════════════ PNG 解码（够用的子集） ═════════════════

const CHANNELS = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }

function decodePng (buf) {
  const SIG = [137, 80, 78, 71, 13, 10, 26, 10]
  for (let i = 0; i < 8; i++) if (buf[i] !== SIG[i]) throw new Error('不是 PNG 文件（签名不匹配）；请导出 PNG-24/PNG-32，别交 JPEG/WebP')
  let o = 8
  let ihdr = null
  const idat = []
  let palette = null
  let trns = null
  while (o < buf.length) {
    const len = buf.readUInt32BE(o)
    const type = buf.toString('ascii', o + 4, o + 8)
    const data = buf.subarray(o + 8, o + 8 + len)
    if (type === 'IHDR') {
      ihdr = { width: data.readUInt32BE(0), height: data.readUInt32BE(4), bitDepth: data[8], colorType: data[9], compression: data[10], filter: data[11], interlace: data[12] }
      if (ihdr.compression !== 0 || ihdr.filter !== 0) throw new Error('只支持标准 deflate + 自适应滤波的 PNG')
      if (ihdr.interlace !== 0) throw new Error('不支持隔行（Adam7）PNG；导出时选"非隔行/基线"')
    } else if (type === 'PLTE') palette = data
    else if (type === 'tRNS') trns = data
    else if (type === 'IDAT') idat.push(Buffer.from(data))
    else if (type === 'IEND') break
    o += 12 + len
  }
  if (!ihdr) throw new Error('PNG 里没有 IHDR')
  if (ihdr.bitDepth !== 8 && ihdr.bitDepth !== 16 && ihdr.colorType !== 3) throw new Error(`位深 ${ihdr.bitDepth} 不支持（要 8 位，或 16 位的 RGB/灰度）`)
  if (![0, 2, 3, 4, 6].includes(ihdr.colorType)) throw new Error(`颜色类型 ${ihdr.colorType}（带 alpha 的灰度除外）不支持`)
  const { width, height, bitDepth, colorType } = ihdr
  const srcCh = CHANNELS[colorType]
  const raw = zlib.inflateSync(Buffer.concat(idat))
  const bppBits = srcCh * bitDepth
  const bpp = Math.max(1, Math.ceil(bppBits / 8))
  const stride = Math.ceil((width * bppBits) / 8)
  const recon = Buffer.alloc(height * stride)
  let pos = 0
  for (let y = 0; y < height; y++) {
    const ft = raw[pos++]
    const line = raw.subarray(pos, pos + stride)
    pos += stride
    const cur = recon.subarray(y * stride, y * stride + stride)
    const prev = y > 0 ? recon.subarray((y - 1) * stride, (y - 1) * stride + stride) : null
    for (let x = 0; x < stride; x++) {
      const a = x >= bpp ? cur[x - bpp] : 0
      const b = prev ? prev[x] : 0
      const c = prev && x >= bpp ? prev[x - bpp] : 0
      let v
      switch (ft) {
        case 0: v = line[x]; break
        case 1: v = line[x] + a; break
        case 2: v = line[x] + b; break
        case 3: v = line[x] + ((a + b) >> 1); break
        case 4: v = line[x] + paeth(a, b, c); break
        default: throw new Error(`未知滤波类型 ${ft}（文件可能损坏）`)
      }
      cur[x] = v & 0xff
    }
  }
  // → RGBA8
  const rgba = new Uint8Array(width * height * 4)
  const smp = (rowStart, x, ch) => readSample(recon, rowStart, x, bitDepth, srcCh, ch)
  for (let y = 0; y < height; y++) {
    const rowStart = y * stride
    for (let x = 0; x < width; x++) {
      const i = (y * width + x) * 4
      let r, g, b, a
      switch (colorType) {
        case 0: r = g = b = smp(rowStart, x, 0); a = 255; break
        case 4: r = g = b = smp(rowStart, x, 0); a = smp(rowStart, x, 1); break
        case 2: r = smp(rowStart, x, 0); g = smp(rowStart, x, 1); b = smp(rowStart, x, 2); a = 255; break
        case 6: r = smp(rowStart, x, 0); g = smp(rowStart, x, 1); b = smp(rowStart, x, 2); a = smp(rowStart, x, 3); break
        case 3: {
          const idx = smp(rowStart, x, 0)
          if (!palette || idx * 3 + 2 >= palette.length) throw new Error('调色板 PNG 的 PLTE 不完整')
          r = palette[idx * 3]; g = palette[idx * 3 + 1]; b = palette[idx * 3 + 2]
          a = trns && idx < trns.length ? trns[idx] : 255
          break
        }
      }
      rgba[i] = r; rgba[i + 1] = g; rgba[i + 2] = b; rgba[i + 3] = a
    }
  }
  return { width, height, data: rgba }
}

function readSample (recon, rowStart, x, bitDepth, channels, ch) {
  if (bitDepth === 8) return recon[rowStart + x * channels + ch]
  if (bitDepth === 16) return recon.readUInt16BE(rowStart + (x * channels + ch) * 2) >> 8
  const bits = bitDepth
  const max = (1 << bits) - 1
  const bitPos = (x * channels + ch) * bits
  const byte = recon[rowStart + (bitPos >> 3)]
  const shift = 8 - bits - (bitPos & 7)
  return ((byte >> shift) & max) * Math.round(255 / max)
}

function paeth (a, b, c) {
  const p = a + b - c
  const pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c)
  return pa <= pb && pa <= pc ? a : pb <= pc ? b : c
}

function centerCropSquare (img) {
  const side = Math.min(img.width, img.height)
  const x0 = Math.floor((img.width - side) / 2)
  const y0 = Math.floor((img.height - side) / 2)
  const data = new Uint8Array(side * side * 4)
  for (let y = 0; y < side; y++) {
    for (let x = 0; x < side; x++) {
      const s = ((y + y0) * img.width + (x + x0)) * 4
      const d = (y * side + x) * 4
      data[d] = img.data[s]; data[d + 1] = img.data[s + 1]; data[d + 2] = img.data[s + 2]; data[d + 3] = img.data[s + 3]
    }
  }
  return { width: side, height: side, data }
}

/** 面积平均（盒式）缩放 —— 图标缩小足够干净，且不引入锯齿毛刺 */
function resizeArea (src, tw, th) {
  const out = new Uint8Array(tw * th * 4)
  for (let y = 0; y < th; y++) {
    const sy0 = Math.floor((y * src.height) / th)
    const sy1 = Math.max(sy0 + 1, Math.floor(((y + 1) * src.height) / th))
    for (let x = 0; x < tw; x++) {
      const sx0 = Math.floor((x * src.width) / tw)
      const sx1 = Math.max(sx0 + 1, Math.floor(((x + 1) * src.width) / tw))
      let r = 0, g = 0, b = 0, a = 0, n = 0
      for (let sy = sy0; sy < sy1; sy++) {
        for (let sx = sx0; sx < sx1; sx++) {
          const i = (sy * src.width + sx) * 4
          r += src.data[i]; g += src.data[i + 1]; b += src.data[i + 2]; a += src.data[i + 3]
          n++
        }
      }
      const d = (y * tw + x) * 4
      out[d] = Math.round(r / n); out[d + 1] = Math.round(g / n); out[d + 2] = Math.round(b / n); out[d + 3] = Math.round(a / n)
    }
  }
  return { width: tw, height: th, data: out }
}

// ═════════════════ PNG 编码（固定 8 位 RGBA、滤波 0） ═════════════════

const CRC_TABLE = (() => {
  const t = new Int32Array(256)
  for (let n = 0; n < 256; n++) {
    let c = n
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    t[n] = c
  }
  return t
})()

function crc32 (buf) {
  let c = ~0
  for (let i = 0; i < buf.length; i++) c = (c >>> 8) ^ CRC_TABLE[(c ^ buf[i]) & 0xff]
  return (c ^ ~0) >>> 0
}

function chunk (type, data) {
  const head = Buffer.alloc(8)
  head.writeUInt32BE(data.length, 0)
  head.write(type, 4, 'ascii')
  const crc = Buffer.alloc(4)
  crc.writeUInt32BE(crc32(Buffer.concat([head.subarray(4), data])), 0)
  return Buffer.concat([head, data, crc])
}

function encodePng (img) {
  const { width, height, data } = img
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(width, 0)
  ihdr.writeUInt32BE(height, 4)
  ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0
  const stride = width * 4
  const raw = Buffer.alloc((stride + 1) * height)
  for (let y = 0; y < height; y++) {
    raw[y * (stride + 1)] = 0
    Buffer.from(data.buffer, data.byteOffset + y * stride, stride).copy(raw, y * (stride + 1) + 1)
  }
  return Buffer.concat([
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ])
}

function rel (p) {
  return path.relative(ROOT, p).replace(/\\/g, '/')
}

process.exitCode = main()
