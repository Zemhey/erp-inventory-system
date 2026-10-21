<script setup lang="ts">
import Placeholder from '@/views/_Placeholder.vue'
</script>

<template>
  <Placeholder
    title="商品与 SKU"
    milestone="M3 第二步"
    description="商品的增删改查，以及服装行业关键能力：按颜色 × 尺码矩阵批量生成 SKU。"
    :features="[
      '商品列表（按编码/名称搜索、分页）',
      '新建 / 编辑商品，停用不删',
      'SKU 矩阵生成：选颜色和尺码，一键生成全部组合',
      'SKU 列表：零售价、条码、停用状态',
    ]"
  />
</template>
