<script setup lang="ts">
/**
 * 数字滚动（大屏 KPI 用）。
 *
 * ★ 不引动画库：一个 rAF + 缓动就够，8 行代码换掉一个依赖，
 *   在本泳道「node_modules 是共享 junction、装不了包」的约束下这不是妥协，是唯一解。
 */
import { onBeforeUnmount, ref, watch } from 'vue'

const props = defineProps<{
  value: number
  /** 显示格式化（金额传分，这里只按数值滚动，单位由父层决定） */
  format?: (n: number) => string
  duration?: number
}>()

const shown = ref(props.value ?? 0)
let raf = 0

function tween(from: number, to: number, duration: number): void {
  cancelAnimationFrame(raf)
  if (duration <= 0 || from === to) {
    shown.value = to
    return
  }
  const start = performance.now()
  const step = (now: number) => {
    const p = Math.min(1, (now - start) / duration)
    // easeOutCubic：起步快、收尾稳，滚动数字看起来像计数而不是抽搐
    const eased = 1 - (1 - p) ** 3
    shown.value = from + (to - from) * eased
    if (p < 1) raf = requestAnimationFrame(step)
  }
  raf = requestAnimationFrame(step)
}

watch(
  () => props.value,
  (next, prev) => tween(prev ?? 0, next ?? 0, props.duration ?? 900),
  { immediate: true },
)

onBeforeUnmount(() => cancelAnimationFrame(raf))
</script>

<template>
  <span class="rolling">{{ format ? format(shown) : Math.round(shown).toLocaleString('zh-CN') }}</span>
</template>

<style scoped>
.rolling {
  font-variant-numeric: tabular-nums;
}
</style>
