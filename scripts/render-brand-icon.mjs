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
import { existsSync, readFileSync } from 'node:fs';
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
const svgSource = readFileSync(MASTER_SVG, 'utf8');
const browser = await chromium.launch();
for (const job of jobs) {
  // ★ 两个坑都实测踩过：
  //   ① 直接 goto 到 SVG 再按目标尺寸截图 —— 母图自带 width=1024，视口比它小时
  //      截到的是**左上角裁切**，产出的 favicon 是个纯色方块；
  //   ② 用 <img src="file://..."> 包一层 —— setContent 的页面是 about:blank 源，
  //      Chromium 不允许它加载 file:// 资源，结果截到的是裂图占位符。
  //   所以把 SVG 内联进 HTML，并直接改写根元素的 width/height，让它真正重绘。
  const sized = svgSource
    .replace(/width="1024"/, `width="${job.size}"`)
    .replace(/height="1024"/, `height="${job.size}"`);
  if (!sized.includes(`width="${job.size}"`)) {
    throw new Error(`母图 SVG 里找不到可改写的 width="1024"，请检查 ${MASTER_SVG}`);
  }
  const page = await browser.newPage({ viewport: { width: job.size, height: job.size } });
  await page.setContent(
    `<!doctype html><meta charset="utf-8">` +
      `<style>html,body{margin:0;padding:0;overflow:hidden}svg{display:block}</style>` +
      sized,
    { waitUntil: 'load' },
  );
  await page.waitForTimeout(200);
  await page.screenshot({ path: job.out, clip: { x: 0, y: 0, width: job.size, height: job.size } });
  await page.close();
  console.log(`  ${job.size}x${job.size} → ${path.relative(process.cwd(), job.out)}`);
}
await browser.close();
