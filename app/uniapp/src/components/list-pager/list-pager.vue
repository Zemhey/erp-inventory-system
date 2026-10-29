<script setup lang="ts">
import { computed } from 'vue'

/**
 * 列表翻页条。
 *
 * 替掉原来的"一直往下滚、滚到底接着加载"：一页固定 20 条，要后面的翻下一页。
 * 移动端单列卡片一屏也就 4~5 张，无限滚动往下滑 200 多条既累又找不到位置，
 * 而且列表在变（别人审核了新单）时无限滚动的"已加载 N / 共 M"会一直跳。
 *
 * ★ 页码从 1 起算，和后端分页参数一致，组件内部不做 0/1 换算。
 */
const props = defineProps<{
  page: number
  size: number
  total: number
  loading?: boolean
  /** 单位文案：库存那边是"行"，单据那边是"张" */
  unit?: string
}>()

const emit = defineEmits<{ (e: 'change', page: number): void }>()

const pageCount = computed(() => Math.max(1, Math.ceil((props.total || 0) / (props.size || 20))))
const hasPrev = computed(() => props.page > 1)
const hasNext = computed(() => props.page < pageCount.value)
const busy = computed(() => Boolean(props.loading))

function go(next: number): void {
  if (busy.value) return
  if (next < 1 || next > pageCount.value || next === props.page) return
  emit('change', next)
}
</script>

<template>
  <view class="pager">
    <text class="pager-sum">
      共 {{ total }} {{ unit || '条' }} · 第 {{ page }} / {{ pageCount }} 页
    </text>

    <view v-if="pageCount > 1" class="pager-btns">
      <view
        class="pager-btn"
        :class="{ 'is-off': !hasPrev || busy }"
        hover-class="pager-btn--hover"
        :hover-stay-time="80"
        @click="go(page - 1)"
      >
        上一页
      </view>
      <view
        class="pager-btn"
        :class="{ 'is-off': !hasNext || busy }"
        hover-class="pager-btn--hover"
        :hover-stay-time="80"
        @click="go(page + 1)"
      >
        下一页
      </view>
    </view>
  </view>
</template>

<style>
.pager {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20rpx 4rpx 4rpx;
}

.pager-sum {
  font-size: 24rpx;
  color: var(--c-text-3);
}

.pager-btns {
  display: flex;
  align-items: center;
}

.pager-btn {
  margin-left: 16rpx;
  padding: 0 26rpx;
  height: 60rpx;
  display: flex;
  align-items: center;
  border-radius: 999rpx;
  border: 1rpx solid var(--c-line-strong);
  background: var(--c-surface);
  font-size: 24rpx;
  color: var(--c-brand-text);
}

.pager-btn--hover {
  background: var(--c-fill);
}

.pager-btn.is-off {
  color: var(--c-text-4);
  border-color: var(--c-line);
  opacity: 0.55;
}
</style>
