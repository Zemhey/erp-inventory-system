<script setup lang="ts">
import { computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { API_BASE_HINT, API_BASE_IS_ABSOLUTE, API_BASE_URL, IS_H5 } from '@/config'
import { useTheme } from '@/composables/useTheme'
import type { ThemeMode } from '@/stores/theme'
import { session } from '@/stores/session'
import { ensureLoggedIn } from '@/utils/routeGuard'

/**
 * 设置。
 *
 * ★ 为什么主题开关放这里而不是工作台右上角：主题是个"设一次就不太动"的偏好，
 *   占首屏一个位置不值当；但它又必须找得到，所以「我的」和这里都留了入口。
 * ★ 三档而不是两档：默认「跟随系统」，让没动过设置的人跟着手机走；
 *   一旦手动选过浅色/深色，就不再被系统覆盖（见 stores/theme.ts 的优先级）。
 */

const { themeClass, themeMode, resolvedTheme, setThemeMode } = useTheme()

const MODES: Array<{ value: ThemeMode; label: string; hint: string }> = [
  { value: 'system', label: '跟随系统', hint: '手机深色就深色，手机浅色就浅色' },
  { value: 'light', label: '浅色', hint: '暖白底 · 黛青主色，固定不随系统' },
  { value: 'dark', label: '深色', hint: '暖墨底 · 主色提亮，固定不随系统' },
]

const currentHint = computed(
  () => MODES.find((m) => m.value === themeMode.value)?.hint ?? '',
)

const platformText = computed(() => {
  if (IS_H5) return '移动 H5'
  // #ifdef MP-WEIXIN
  return '微信小程序'
  // #endif
  // #ifdef APP-PLUS
  return 'Android App'
  // #endif
  return '移动端'
})

const apiText = computed(() => API_BASE_URL)
const apiWarn = computed(() => !API_BASE_IS_ABSOLUTE && !IS_H5)

function pick(value: ThemeMode): void {
  if (value === themeMode.value) return
  setThemeMode(value)
  uni.showToast({ title: '已切换主题', icon: 'none', duration: 1200 })
}

function openMe(): void {
  uni.navigateTo({ url: '/pages/me/me' })
}

onShow(() => {
  ensureLoggedIn()
})
</script>

<template>
  <view class="page settings page--tabbar" :class="themeClass">
    <view class="card section">
      <text class="section-title">主题外观</text>
      <text class="section-sub">当前生效：{{ resolvedTheme === 'dark' ? '深色' : '浅色' }}</text>

      <view class="mode-grid">
        <view
          v-for="m in MODES"
          :key="m.value"
          class="mode-cell"
          :class="{ 'is-on': m.value === themeMode }"
          hover-class="mode-cell--hover"
          :hover-stay-time="80"
          @click="pick(m.value)"
        >
          <text class="mode-label">{{ m.label }}</text>
          <text class="mode-dot" :class="{ 'is-on': m.value === themeMode }" />
        </view>
      </view>

      <text class="mode-hint">{{ currentHint }}</text>
    </view>

    <view class="card section">
      <text class="section-title">账号</text>
      <view class="kv" @click="openMe">
        <text class="kv-label">当前用户</text>
        <text class="kv-value">
          {{ session.displayName || session.username || '未登录' }} ›
        </text>
      </view>
      <view class="kv">
        <text class="kv-label">角色</text>
        <text class="kv-value">{{ session.roles.join(' / ') || '未分配' }}</text>
      </view>
    </view>

    <view class="card section">
      <text class="section-title">运行环境</text>
      <view class="kv">
        <text class="kv-label">端</text>
        <text class="kv-value">{{ platformText }}</text>
      </view>
      <view class="kv">
        <text class="kv-label">接口地址</text>
        <text class="kv-value kv-mono">{{ apiText }}</text>
      </view>
      <text v-if="apiWarn" class="env-warn">{{ API_BASE_HINT }}</text>
    </view>

    <text class="footer">配色与 Web 端同一套令牌：暖白 #f6f2ea · 黛青 #2e5d63</text>

    <!-- 从「我的」进来的，底栏继续高亮「我的」，否则用户会以为跳出了主导航 -->
    <app-tab-bar active="me" />
  </view>
</template>

<style scoped>
.settings {
  padding-top: 20rpx;
}

.section {
  margin-bottom: 20rpx;
  padding: 24rpx;
}

.section-title {
  font-size: 28rpx;
  font-weight: 700;
  color: var(--c-text);
}

.section-sub {
  display: block;
  margin-top: 6rpx;
  font-size: 21rpx;
  color: var(--c-text-3);
}

.mode-grid {
  display: flex;
  gap: 16rpx;
  margin-top: 20rpx;
}

.mode-cell {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 22rpx 0 18rpx;
  border-radius: 16rpx;
  border: 1rpx solid var(--c-line);
  background: var(--c-fill);
}

.mode-cell.is-on {
  border-color: var(--c-brand);
  background: var(--c-brand-wash);
}

.mode-cell--hover {
  border-color: var(--c-line-strong);
}

.mode-label {
  font-size: 25rpx;
  color: var(--c-text-2);
}

.mode-cell.is-on .mode-label {
  font-weight: 600;
  color: var(--c-brand-text);
}

.mode-dot {
  width: 12rpx;
  height: 12rpx;
  margin-top: 12rpx;
  border-radius: 999rpx;
  background: var(--c-line-strong);
}

.mode-dot.is-on {
  background: var(--c-brand);
}

.mode-hint {
  display: block;
  margin-top: 18rpx;
  font-size: 21rpx;
  line-height: 1.6;
  color: var(--c-text-3);
}

.kv {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 18rpx;
  padding-bottom: 14rpx;
  border-bottom: 1rpx solid var(--c-line);
}

.kv-label {
  font-size: 24rpx;
  color: var(--c-text-2);
}

.kv-value {
  max-width: 66%;
  font-size: 24rpx;
  text-align: right;
  color: var(--c-text);
}

.kv-mono {
  font-size: 21rpx;
  word-break: break-all;
}

.env-warn {
  display: block;
  margin-top: 16rpx;
  font-size: 21rpx;
  line-height: 1.6;
  color: var(--c-danger);
}

.footer {
  display: block;
  margin: 28rpx 0 10rpx;
  text-align: center;
  font-size: 20rpx;
  color: var(--c-text-4);
}
</style>
