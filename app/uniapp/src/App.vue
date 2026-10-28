<script setup lang="ts">
import { onLaunch } from '@dcloudio/uni-app'
import { installRouteGuard } from '@/utils/routeGuard'

onLaunch(() => {
  installRouteGuard()
})
</script>

<style>
/* ══════════════════════════════════════════════════════════════════════════
   主题变量
   --------------------------------------------------------------------------
   整套移动端只认这一组语义色，页面里不再出现硬编码色值（23 个页面共 737 处已迁完）。

   ★ 为什么用 page 选择器而不是 :root：
     微信小程序没有 :root，WXSS 的根选择器就是 page；H5 端两者都要兜（弹层挂在 page 之外）。
   ★ 为什么深色跟随系统而不是应用内开关：
     移动端没有"钻进设置里找主题"的习惯，跟随系统是最不容易出错的做法 ——
     系统切深色后用户不用再回应用里点一次。Web 端有开关是因为它要跨平台演示，
     两端策略不同是有意的。
   ★ 深色不是把浅色反过来：底用 #0f1116 的冷墨（与移动端原有的冷灰系一致），
     卡片抬一档到 #1b1e25，文字用 #e9edf4 不用纯白（纯白在深底上会"发光"）。
   ══════════════════════════════════════════════════════════════════════════ */
page,
:root {
  --c-page: #f4f5f7;
  --c-surface: #ffffff;
  --c-fill: #f8f9fb;
  --c-fill-strong: #eef1f6;
  --c-text: #1c2230;
  --c-text-2: #6e7585;
  --c-text-3: #8a90a2;
  --c-text-4: #a2a8b6;
  --c-line: #f0f2f6;
  --c-line-strong: #d7dce6;
  --c-brand: #1f3a63;
  --c-brand-text: #1f3a63;
  --c-brand-wash: #eef4fb;
  --c-danger: #9c3a26;
  --c-danger-line: #f3d3ca;
  --c-danger-wash: #fdf1ee;
  --c-ok-wash: #e9f5ef;
  --c-warn-wash: #fbf1e3;
  --c-info-text: #2c4f8a;
  --c-info-wash: #e8eefb;
  --c-purple-text: #5b4a91;
  --c-purple-wash: #f0ecfb;
  --c-success: #2f7d5b;
  --c-warn: #b4702f;
  --c-shadow: rgba(20, 28, 45, 0.06);
}

@media (prefers-color-scheme: dark) {
  page,
  :root {
    --c-page: #0f1116;
    --c-surface: #1b1e25;
    --c-fill: #22262e;
    --c-fill-strong: #2a2f38;
    --c-text: #e9edf4;
    --c-text-2: #b3bac7;
    --c-text-3: #949bab;
    --c-text-4: #767d8b;
    --c-line: #2a2f38;
    --c-line-strong: #3a414d;
    /* 品牌色分两个：--c-brand 给"底"（白字压在上面），--c-brand-text 给"字"（深底上要亮） */
    --c-brand: #2f4d78;
    --c-brand-text: #9ec0ea;
    --c-brand-wash: #16233a;
    --c-danger: #e8826a;
    --c-danger-line: #4a2c25;
    --c-danger-wash: #2a1c19;
    --c-ok-wash: #16281f;
    --c-warn-wash: #2b2114;
    --c-info-text: #8fb6e8;
    --c-info-wash: #16233a;
    --c-purple-text: #b6a4e0;
    --c-purple-wash: #211d33;
    --c-success: #5fbf93;
    --c-warn: #dda45a;
    --c-shadow: rgba(0, 0, 0, 0.5);
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
  color: #ffffff;
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

/* ── H5 端的标签栏 ────────────────────────────────────────────────────────
   小程序的原生标签栏由 theme.json 管；H5 没有原生层，uni-app 自己画了一条 DOM，
   颜色取自 pages.json 的静态值，读不到 theme.json —— 深色系统下会留一条白条。
   这里只针对 uni-app 的类名覆盖，小程序端不会命中（那边根本没有这些节点）。 */
@media (prefers-color-scheme: dark) {
  uni-tabbar .uni-tabbar {
    background-color: var(--c-surface) !important;
    border-top: 1rpx solid var(--c-line-strong);
  }

  uni-tabbar .uni-tabbar__label {
    color: var(--c-text-3) !important;
  }

  uni-tabbar .uni-tabbar__label-active {
    color: var(--c-brand-text) !important;
  }

  uni-tabbar .uni-tabbar__icon {
    color: var(--c-text-3) !important;
  }

  uni-tabbar .uni-tabbar__icon-active {
    color: var(--c-brand-text) !important;
  }
}
</style>
