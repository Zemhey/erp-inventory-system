<script setup lang="ts">
import { onLaunch } from '@dcloudio/uni-app'
import { installRouteGuard } from '@/utils/routeGuard'
import { theme } from '@/stores/theme'

onLaunch(() => {
  installRouteGuard()
  theme.watchSystemTheme()
  theme.applyChrome()
})
</script>

<style>
/* ══════════════════════════════════════════════════════════════════════════
   主题变量
   --------------------------------------------------------------------------
   整套移动端只认下面这一组语义色，页面里不再出现硬编码色值。

   ★ 配色照搬 Web 端：--lt-* / --dk-* 两套原料逐条取自
     frontend/web/src/styles/theme.css（浅色 :root）与 theme-dark.css（深色 html.dark）
     —— 暖白底 #f6f2ea、瓷白卡片 #fffdfa、墨黑字 #23201c、黛青主色 #2e5d63。
     深色不是把浅色反过来，是同一块布料换个光照。两端同一套视觉语言是刻意的。

   ★ 为什么分三层：
     1) 默认值那一层：H5 挂 :root、小程序挂 page —— **两者不能写成一条 `page,:root`**。
        WXSS 不认识 :root，而选择器列表里只要有一个无效选择器，整条规则就会被丢弃，
        结果所有 --c-* 全部无值、小程序整页白屏（2026-10-29 在微信开发者工具实测到）。
        H5 只需 :root：uni-app 会把 page 编译成 uni-page-body，它是 html 的后代，
        变量自然继承下来，同时挂在 :root 上还能兜住弹在 page 之外的浮层。
     2) .t-light / .t-dark 由 stores/theme.ts 解析出的档位挂到每个页的根 view 上，
        class 特异度高于 page，所以"系统是深色、用户手动选浅色"也压得住；
     3) @media 那一层只服务 JS 挂上 class 之前的首屏，避免深色系统下先白一下。
   ★ 原生层（导航栏 / 标签栏 / 页面底色）不吃 CSS 变量，由 theme.applyChrome()
     运行时调 setNavigationBarColor / setTabBarStyle / setBackgroundColor 同步。
   ══════════════════════════════════════════════════════════════════════════ */
/* #ifdef H5 */
:root {
/* #endif */
/* #ifndef H5 */
page {
/* #endif */
  /* ── 原料 · 浅色：与 Web 端 --ys-* 同名同值 ───────────────────────────── */
  --lt-page: #f6f2ea; /* 暖白 · 页面底 */
  --lt-surface: #fffdfa; /* 瓷白 · 卡片 */
  --lt-fill: #fbf8f2; /* warm-50 */
  --lt-fill-strong: #f3ede2; /* warm-100 */
  --lt-text: #23201c; /* 墨黑 · 主文字 */
  --lt-text-2: #4a443c;
  --lt-text-3: #6a6255;
  --lt-text-4: #857b6b;
  --lt-line: #e8e0d4;
  --lt-line-strong: #d8ccb9;
  --lt-brand: #2e5d63; /* 黛青 · 主色 */
  --lt-brand-deep: #24494e;
  --lt-brand-wash: #e8efee;
  --lt-on-brand: #fdfbf6; /* 压在品牌色底上的字：暖白，不用纯白 */
  --lt-danger: #a25f6b; /* 胭脂 · 退货 / 异常 / 下架 */
  --lt-danger-line: #e9d5d9;
  --lt-danger-wash: #f7edee;
  --lt-ok-wash: #edf1e9; /* 苔绿 wash */
  --lt-warn-wash: #f8efe6; /* 陶土 wash */
  --lt-info-text: #7c93a6; /* 雾蓝 · 辅助 */
  --lt-info-wash: #edf1f4;
  --lt-accent-text: #3c7078; /* 黛青 soft：状态位第六色，只做语义 */
  --lt-accent-wash: #e6eff0;
  --lt-success: #5c7357; /* 苔绿 · 已完成 / 正常 */
  --lt-warn: #a96436; /* 陶土 · 库存预警 / 待补货 */
  --lt-shadow: rgba(58, 44, 28, 0.08);

  /* ── 原料 · 深色：与 Web 端 html.dark 同名同值 ─────────────────────────── */
  --dk-page: #14120f; /* 暖墨，不用冷灰 #0d1117 */
  --dk-surface: #1d1a16;
  --dk-fill: #232019;
  --dk-fill-strong: #2b2620;
  --dk-text: #efe8db; /* 暖白，纯白在深底上会"发光" */
  --dk-text-2: #d5cbba;
  --dk-text-3: #a99e8c;
  --dk-text-4: #8a7f6e;
  --dk-line: #332d26;
  --dk-line-strong: #4a4238;
  /* 品牌色分两个：--c-brand 给"底"（白字压在上面），--c-brand-text 给"字"（深底上要亮） */
  --dk-brand: #24494e; /* 深底上按钮仍要压得住白字，所以取 deep 那一档 */
  --dk-brand-text: #5aa4ab; /* 黛青提亮：深底上才认得出是青 */
  --dk-brand-wash: rgba(90, 164, 171, 0.16);
  --dk-brand-deep: #16302f;
  --dk-on-brand: #efe8db;
  --dk-danger: #cf8a94;
  --dk-danger-line: rgba(207, 138, 148, 0.3);
  --dk-danger-wash: rgba(207, 138, 148, 0.15);
  --dk-ok-wash: rgba(138, 171, 128, 0.15);
  --dk-warn-wash: rgba(201, 147, 100, 0.15);
  --dk-info-text: #93abc0;
  --dk-info-wash: rgba(147, 171, 192, 0.13);
  --dk-accent-text: #7fb8bd;
  --dk-accent-wash: rgba(127, 184, 189, 0.15);
  --dk-success: #8aab80;
  --dk-warn: #c99364;
  --dk-shadow: rgba(0, 0, 0, 0.45);

  /* ── 语义层：默认走浅色 ───────────────────────────────────────────────── */
  --c-page: var(--lt-page);
  --c-surface: var(--lt-surface);
  --c-fill: var(--lt-fill);
  --c-fill-strong: var(--lt-fill-strong);
  --c-text: var(--lt-text);
  --c-text-2: var(--lt-text-2);
  --c-text-3: var(--lt-text-3);
  --c-text-4: var(--lt-text-4);
  --c-line: var(--lt-line);
  --c-line-strong: var(--lt-line-strong);
  --c-brand: var(--lt-brand);
  --c-brand-text: var(--lt-brand);
  --c-brand-wash: var(--lt-brand-wash);
  --c-brand-deep: var(--lt-brand-deep);
  --c-on-brand: var(--lt-on-brand);
  --c-danger: var(--lt-danger);
  --c-danger-line: var(--lt-danger-line);
  --c-danger-wash: var(--lt-danger-wash);
  --c-ok-wash: var(--lt-ok-wash);
  --c-warn-wash: var(--lt-warn-wash);
  --c-info-text: var(--lt-info-text);
  --c-info-wash: var(--lt-info-wash);
  --c-accent-text: var(--lt-accent-text);
  --c-accent-wash: var(--lt-accent-wash);
  --c-success: var(--lt-success);
  --c-warn: var(--lt-warn);
  --c-shadow: var(--lt-shadow);

  /*
    品牌色面板（工作台头部、登录页）上的字阶层。
    这三条故意只定义一份、不进 .t-light / .t-dark：它们是"暖白 + 透明度"，
    压在浅色的黛青 #2e5d63 还是深色的 #24494e 上都读得清，不需要分档。
  */
  --c-on-brand-2: rgba(253, 251, 246, 0.72);
  --c-on-brand-3: rgba(253, 251, 246, 0.55);
  --c-on-brand-line: rgba(253, 251, 246, 0.16);

  /* 弹层遮罩：取墨黑的半透明，深色档下页面本来就暗，同一值两边都压得住 */
  --c-scrim: rgba(20, 18, 15, 0.5);
}

/* 手动档 / 跟随系统解析出来的档：由 useTheme() 绑在页面根 view 上 */
.t-light {
  --c-page: var(--lt-page);
  --c-surface: var(--lt-surface);
  --c-fill: var(--lt-fill);
  --c-fill-strong: var(--lt-fill-strong);
  --c-text: var(--lt-text);
  --c-text-2: var(--lt-text-2);
  --c-text-3: var(--lt-text-3);
  --c-text-4: var(--lt-text-4);
  --c-line: var(--lt-line);
  --c-line-strong: var(--lt-line-strong);
  --c-brand: var(--lt-brand);
  --c-brand-text: var(--lt-brand);
  --c-brand-wash: var(--lt-brand-wash);
  --c-brand-deep: var(--lt-brand-deep);
  --c-on-brand: var(--lt-on-brand);
  --c-danger: var(--lt-danger);
  --c-danger-line: var(--lt-danger-line);
  --c-danger-wash: var(--lt-danger-wash);
  --c-ok-wash: var(--lt-ok-wash);
  --c-warn-wash: var(--lt-warn-wash);
  --c-info-text: var(--lt-info-text);
  --c-info-wash: var(--lt-info-wash);
  --c-accent-text: var(--lt-accent-text);
  --c-accent-wash: var(--lt-accent-wash);
  --c-success: var(--lt-success);
  --c-warn: var(--lt-warn);
  --c-shadow: var(--lt-shadow);
}

.t-dark {
  --c-page: var(--dk-page);
  --c-surface: var(--dk-surface);
  --c-fill: var(--dk-fill);
  --c-fill-strong: var(--dk-fill-strong);
  --c-text: var(--dk-text);
  --c-text-2: var(--dk-text-2);
  --c-text-3: var(--dk-text-3);
  --c-text-4: var(--dk-text-4);
  --c-line: var(--dk-line);
  --c-line-strong: var(--dk-line-strong);
  --c-brand: var(--dk-brand);
  --c-brand-text: var(--dk-brand-text);
  --c-brand-wash: var(--dk-brand-wash);
  --c-brand-deep: var(--dk-brand-deep);
  --c-on-brand: var(--dk-on-brand);
  --c-danger: var(--dk-danger);
  --c-danger-line: var(--dk-danger-line);
  --c-danger-wash: var(--dk-danger-wash);
  --c-ok-wash: var(--dk-ok-wash);
  --c-warn-wash: var(--dk-warn-wash);
  --c-info-text: var(--dk-info-text);
  --c-info-wash: var(--dk-info-wash);
  --c-accent-text: var(--dk-accent-text);
  --c-accent-wash: var(--dk-accent-wash);
  --c-success: var(--dk-success);
  --c-warn: var(--dk-warn);
  --c-shadow: var(--dk-shadow);
}

/* 首屏兜底：JS 挂 class 之前，跟随系统的用户不该先看到一屏浅色
   ★ 这里的 :root / page 也必须分开写，原因同文件顶部那条 —— 混在一条选择器列表里，
     小程序会把整条规则丢掉。 */
@media (prefers-color-scheme: dark) {
  /* #ifdef H5 */
  :root {
  /* #endif */
  /* #ifndef H5 */
  page {
  /* #endif */
    --c-page: var(--dk-page);
    --c-surface: var(--dk-surface);
    --c-fill: var(--dk-fill);
    --c-fill-strong: var(--dk-fill-strong);
    --c-text: var(--dk-text);
    --c-text-2: var(--dk-text-2);
    --c-text-3: var(--dk-text-3);
    --c-text-4: var(--dk-text-4);
    --c-line: var(--dk-line);
    --c-line-strong: var(--dk-line-strong);
    --c-brand: var(--dk-brand);
    --c-brand-text: var(--dk-brand-text);
    --c-brand-wash: var(--dk-brand-wash);
    --c-brand-deep: var(--dk-brand-deep);
    --c-on-brand: var(--dk-on-brand);
    --c-danger: var(--dk-danger);
    --c-danger-line: var(--dk-danger-line);
    --c-danger-wash: var(--dk-danger-wash);
    --c-ok-wash: var(--dk-ok-wash);
    --c-warn-wash: var(--dk-warn-wash);
    --c-info-text: var(--dk-info-text);
    --c-info-wash: var(--dk-info-wash);
    --c-accent-text: var(--dk-accent-text);
    --c-accent-wash: var(--dk-accent-wash);
    --c-success: var(--dk-success);
    --c-warn: var(--dk-warn);
    --c-shadow: var(--dk-shadow);
  }
}

page {
  background: var(--c-page);
  color: var(--c-text);
  font-family:
    -apple-system, BlinkMacSystemFont, 'Helvetica Neue', 'PingFang SC', 'Microsoft YaHei', sans-serif;
  font-size: 28rpx;
  line-height: 1.5;
}

view,
text,
input,
button,
scroll-view {
  box-sizing: border-box;
}

.page {
  padding: 24rpx 32rpx calc(48rpx + env(safe-area-inset-bottom));
}

/* 挂了自定义底栏的页面要多让出一截，否则列表最后一张卡片被压在栏下面 */
.page--tabbar {
  padding-bottom: calc(148rpx + env(safe-area-inset-bottom));
}

/*
  主题根节点自己把页面底画出来。
  ★ 为什么不能只靠上面那条 page { background: var(--c-page) }：
    CSS 变量是在"用到它的那个元素"上解析的，class 挂在页面的根 view 上，
    管不到 page 元素自己 —— 结果是"系统浅色 + 手动深色"时卡片是黑的、
    卡片下面那截底还是暖白。applyChrome() 里的 setBackgroundColor 只在
    小程序/App 有效，H5 端就得靠这条。
*/
.t-light,
.t-dark {
  min-height: 100vh;
  background: var(--c-page);
}

.card {
  background: var(--c-surface);
  border-radius: 24rpx;
  padding: 28rpx;
  box-shadow: 0 8rpx 30rpx var(--c-shadow);
}

.row {
  display: flex;
  align-items: center;
}

.row-between {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.muted {
  color: var(--c-text-3);
}

.small {
  font-size: 24rpx;
}

.section-title {
  font-size: 30rpx;
  font-weight: 600;
  color: var(--c-text);
}

.tag {
  display: inline-block;
  padding: 4rpx 14rpx;
  border-radius: 999rpx;
  font-size: 22rpx;
  background: var(--c-fill-strong);
  color: var(--c-text-2);
}

.btn-primary {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 92rpx;
  border-radius: 18rpx;
  background: var(--c-brand);
  color: var(--c-on-brand);
  font-size: 30rpx;
  font-weight: 600;
  border: none;
}

.btn-primary[disabled] {
  opacity: 0.55;
}

.btn-ghost {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 76rpx;
  border-radius: 16rpx;
  background: var(--c-surface);
  color: var(--c-brand-text);
  font-size: 26rpx;
  border: 1rpx solid var(--c-line-strong);
}

.empty-block,
.error-block,
.loading-block {
  padding: 64rpx 24rpx;
  text-align: center;
  color: var(--c-text-3);
  font-size: 26rpx;
}

.error-block {
  color: var(--c-danger);
}

/* ── H5 端的标签栏：把 uni-app 自己画的那条 DOM 收掉 ──────────────────────
   底栏由 components/app-tab-bar 自绘（原生那条最多 5 项、高度字号改不了，放不下「我的」）。
   组件里调的 uni.hideTabBar() 只在小程序/App 生效，**H5 端实测不生效**：
   原生那条 z-index 998、自绘的 900，于是自绘的被整条盖在下面，用户看到的就是
   "底栏没有我的"（浏览器与安卓 App 都是 H5 渲染，所以两端一起中；小程序里反而是好的）。
   pages.json 里的 tabBar 声明仍保留：switchTab 保活与 --window-bottom 让位都依赖它，
   这里只把 DOM 藏起来。小程序端不命中这个选择器（那边根本没有这些节点）。 */
/* #ifdef H5 */
uni-tabbar,
uni-tabbar .uni-tabbar {
  display: none !important;
}
/* #endif */
</style>
