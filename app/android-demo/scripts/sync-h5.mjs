#!/usr/bin/env node
/**
 * 把 uni-app 的 H5 构建产物同步进安卓工程的 assets/www（APK 内置界面的唯一来源）。
 *
 * 为什么要有这一步：安卓包内的界面必须与仓库里那份源码一致，否则"内置包缺失→回源线上"
 * 会成为常态，冷启动仍然依赖服务器；所以每次改完 uni-app 都要重新同步并提交 assets/www。
 *
 * 用法（在 app/android-demo 目录下）：
 *   node scripts/sync-h5.mjs
 * 构建 H5 时必须让 API 地址留空（相对路径 /api/v1），界面才能与文档同源、免 CORS：
 *   cd ../uniapp && VITE_API_BASE_URL= npm run build:h5
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const projectRoot = path.resolve(here, '..');
const repoRoot = path.resolve(projectRoot, '..', '..');
const src = path.join(repoRoot, 'app', 'uniapp', 'dist', 'build', 'h5');
const dest = path.join(projectRoot, 'app', 'src', 'main', 'assets', 'www');

if (!fs.existsSync(path.join(src, 'index.html'))) {
  console.error(`找不到 H5 产物：${src}\n先在 app/uniapp 执行 VITE_API_BASE_URL= npm run build:h5`);
  process.exit(1);
}

// 先校验产物是"相对接口地址"的移动端包，别把绝对域名或桌面端包打进去
const html = fs.readFileSync(path.join(src, 'index.html'), 'utf8');
if (/<base href="[a-z]+:/i.test(html)) {
  console.error('index.html 里有绝对 <base>，不适合内置，请检查构建方式');
  process.exit(1);
}
// 只认真主机的绝对地址；config.ts 的提示语里也有 "https://域名/api/v1"，那是给人看的，不算写死
const ABSOLUTE_API = /https?:\/\/[a-zA-Z0-9._-]+\.[a-zA-Z]{2,}(?::\d+)?\/api\/v1/;
const bundles = collectBundleFiles(src);
const withAbsoluteApi = bundles.filter((f) => ABSOLUTE_API.test(fs.readFileSync(f, 'utf8')));
if (withAbsoluteApi.length > 0) {
  console.error(`这些产物把后端域名写死了，内置后接口会跨域：\n  ${withAbsoluteApi.join('\n  ')}`);
  process.exit(1);
}

fs.rmSync(dest, { recursive: true, force: true });
fs.cpSync(src, dest, { recursive: true });

const files = collectBundleFiles(dest);
const bytes = files.reduce((sum, f) => sum + fs.statSync(f).size, 0);
console.log(`已内置 ${files.length} 个文件，${(bytes / 1024).toFixed(0)} KB → ${path.relative(repoRoot, dest)}`);
console.log(`入口：${path.relative(dest, path.join(dest, 'index.html'))}（APK 启动即加载，不联网也能打开）`);

function collectBundleFiles(dir) {
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) out.push(...collectBundleFiles(full));
    else if (/\.(js|css|html)$/i.test(entry.name)) out.push(full);
  }
  return out;
}
