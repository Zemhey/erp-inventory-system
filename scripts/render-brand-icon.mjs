#!/usr/bin/env node
/**
 * 从品牌母图 SVG 渲染各端需要的 PNG 尺寸。
 *
 * 母图就是工作台侧栏那个「黛青织纹方块 + 衣架标记」，四端（Web / 桌面 / 小程序 / 安卓）共用一张。
 * 渲染走 Playwright（复用 frontend/web 已装的依赖），不引新库 —— 这台机器装不了什么都要
 * 先过一遍镜像源，脚本能零依赖就零依赖。
 *
 * 用法（Git Bash）：
 *   node scripts/render-brand-icon.mjs out.png=1024 favicon-32.png=32
 *   node scripts/render-brand-icon.mjs --list            # 看可用尺寸预设
 *
 * 产物路径相对当前工作目录；图标母图：design/icon-master.svg（等价副本在
 * frontend/web/public/favicon.svg，后者随 web 构建进 dist）。
 */
import { createRequire } from 'node:module';
import path from 'node:path';
import process from 'node:process';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
// design/ 是不入库的设计参考目录，所以母图以已入库的 favicon.svg 为兜底来源，
// 否则干净克隆一份仓库就跑不了这个脚本。
const MASTER_SVG = [path.join(ROOT, 'design', 'icon-master.svg'),
  path.join(ROOT, 'frontend', 'web', 'public', 'favicon.svg'),
].find((p) => existsSync(p)) ?? path.join(ROOT, 'design', 'icon-master.svg');

/** 各端预设：名字 → 尺寸，避免手敲漏一个。 */
const PRESETS = {
  'icon-master': 1024,
  'favicon-32': 32,
  'favicon-16': 16,
  'apple-touch-icon': 180,
  'android-xxxhdpi': 192,
  'android-xxhdpi': 144,
  'android-xhdpi': 96,
  'ico-256': 256,
  'ico-48': 48,
};

const require = createRequire(path.join(ROOT, 'frontend', 'web', 'package.json'));

if (process.argv.includes('--list')) {
  console.log(Object.entries(PRESETS).map(([k, v]) => `  ${k} = ${v}x${v}`).join('\n'));
  process.exit(0);
}

const jobs = process.argv.slice(2).map((spec) => {
  const [out, size] = spec.split('=');
  if (!out || !size) throw new Error(`参数格式应为 输出文件=尺寸，收到：${spec}`);
  const n = Number(size);
  if (!Number.isInteger(n) || n <= 0) throw new Error(`尺寸必须是正整数：${spec}`);
  return { out: path.resolve(out), size: n };
});

if (jobs.length === 0) {
  console.error('用法：node scripts/render-brand-icon.mjs 输出.png=尺寸 [更多...]');
  console.error('例如：node scripts/render-brand-icon.mjs master.png=1024 favicon-32.png=32');
  process.exit(2);
}

const { chromium } = require('playwright');
const browser = await chromium.launch();
for (const job of jobs) {
  const page = await browser.newPage({ viewport: { width: job.size, height: job.size } });
  await page.goto(`file:///${MASTER_SVG.replace(/\\/g, '/')}`);
  await page.waitForTimeout(250);
  await page.screenshot({ path: job.out });
  await page.close();
  console.log(`  ${job.size}x${job.size} → ${path.relative(process.cwd(), job.out)}`);
}
await browser.close();
