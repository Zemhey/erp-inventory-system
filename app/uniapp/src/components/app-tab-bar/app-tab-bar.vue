<script setup lang="ts">
import { onMounted } from 'vue'
import { goTab, hideNativeTabBar, TAB_ITEMS, type TabItem } from '@/utils/navigation'

/**
 * 自定义底部导航栏。
 *
 * 微信原生 tabBar 最多 5 项、高度字号都改不了，所以「我的」进导航栏 + 底栏做大
 * 这两件事只能自己画一条。原生声明仍留在 pages.json 里（switchTab 的前提），
 * 由页面在 onShow 里 hideNativeTabBar() 藏掉。
 *
 * 配色全走语义变量，深浅两档自动跟着主题走，这里不出现任何硬编码色值。
 */
const props = defineProps<{ active: string }>()

// 首次挂载时先藏一次，页面自己的 onShow 负责之后每次显示（这里只压首屏那一闪）
onMounted(() => {
  hideNativeTabBar()
})

function onTap(item: TabItem): void {
  goTab(item, props.active)
}
</script>

<template>
  <view class="tabbar">
    <view class="tabbar-inner">
      <view
        v-for="item in TAB_ITEMS"
        :key="item.key"
        class="tabbar-item"
        :class="{ 'is-active': item.key === active }"
        hover-class="tabbar-item--hover"
        :hover-stay-time="80"
        @click="onTap(item)"
      >
        <view class="tabbar-pill">
          <text class="tabbar-label">{{ item.label }}</text>
        </view>
      </view>
    </view>
  </view>
</template>

<style>
.tabbar {
  position: fixed;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 900;
  padding-bottom: env(safe-area-inset-bottom);
  background: var(--c-surface);
  border-top: 1rpx solid var(--c-line);
  box-shadow: 0 -10rpx 30rpx var(--c-shadow);
}

.tabbar-inner {
  display: flex;
  align-items: center;
  height: 116rpx;
  padding: 0 8rpx;
}

.tabbar-item {
  display: flex;
  flex: 1;
  align-items: center;
  justify-content: center;
  height: 100%;
}

.tabbar-pill {
  display: flex;
  align-items: center;
  justify-content: center;
  min-width: 92rpx;
  height: 68rpx;
  padding: 0 18rpx;
  border-radius: 999rpx;
  transition: background-color 0.16s ease;
}

.tabbar-label {
  font-size: 26rpx;
  line-height: 1;
  color: var(--c-text-3);
}

.tabbar-item--hover .tabbar-pill {
  background: var(--c-fill);
}

.tabbar-item.is-active .tabbar-pill {
  background: var(--c-brand-wash);
}

.tabbar-item.is-active .tabbar-label {
  font-weight: 600;
  color: var(--c-brand-text);
}
</style>
