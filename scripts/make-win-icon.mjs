#!/usr/bin/env node
/**
 * 把多尺寸 PNG 打成一个 Windows .ico（PNG 载荷，Vista+ 支持）。
 *
 * 为什么不用 ImageMagick：这台机器上没有，而且为一个小容器引一个外部依赖不值当 ——
 * ICO 的容器格式很简单，一个头部 + 每尺寸一条 16 字节目录项 + 原始 PNG 字节。
 *
 * 用法（配合 render-brand-icon.mjs）：
 *   node scripts/render-brand-icon.mjs \
 *     app/desktop/assets/icon-src/16.png=16 app/desktop/assets/icon-src/24.png=24 ...
 *   node scripts/make-win-icon.mjs app/desktop/assets/icon-src app/desktop/assets/icon.ico
 *
 * ★ 尺寸必须齐：electron-builder 缺尺寸时会自己降采样，但那是"能跑就行"的质量；
 *   16/24 这种小尺寸由我们自己的矢量母图重绘，比从 256 缩下来清楚得多。
 */
import { readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import process from 'node:process';

const SIZES = [16, 24, 32, 48, 64, 128, 256];

const [dir, out] = process.argv.slice(2);
if (!dir || !out) {
  console.error('用法：node scripts/make-win-icon.mjs <含 N.png 的目录> <输出 .ico>');
  process.exit(2);
}

const images = SIZES.map((size) => {
  const file = path.join(dir, `${size}.png`);
  const buf = readFileSync(file);
  const w = buf.readUInt32BE(16);
  const h = buf.readUInt32BE(20);
  if (w !== size || h !== size) {
    throw new Error(`${file} 实际是 ${w}x${h}，与要求的 ${size}x${size} 不符`);
  }
  return { size, buf };
});

const header = Buffer.alloc(6);
header.writeUInt16LE(0, 0);          // reserved
header.writeUInt16LE(1, 2);          // type = icon
header.writeUInt16LE(images.length, 4);

let offset = 6 + 16 * images.length;
const entries = images.map(({ size, buf }) => {
  const e = Buffer.alloc(16);
  // 256 在目录项里用 0 表示，这是 ICO 的历史包袱
  e.writeUInt8(size >= 256 ? 0 : size, 0);
  e.writeUInt8(size >= 256 ? 0 : size, 1);
  e.writeUInt8(0, 2);                // 调色板色数
  e.writeUInt8(0, 3);
  e.writeUInt16LE(1, 4);             // planes
  e.writeUInt16LE(32, 6);            // bitCount
  e.writeUInt32LE(buf.length, 8);
  e.writeUInt32LE(offset, 12);
  offset += buf.length;
  return e;
});

const ico = Buffer.concat([header, ...entries, ...images.map((i) => i.buf)]);
writeFileSync(out, ico);

const check = readFileSync(out);
const listed = [];
for (let i = 0; i < check.readUInt16LE(4); i += 1) {
  const p = 6 + 16 * i;
  listed.push((check.readUInt8(p) || 256));
}
console.log(`  ${out}：${check.length} 字节，尺寸 ${listed.join(' / ')}`);
