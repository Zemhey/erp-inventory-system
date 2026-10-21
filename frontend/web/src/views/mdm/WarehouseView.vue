<script setup lang="ts">
import Placeholder from '@/views/_Placeholder.vue'
</script>

<template>
  <Placeholder
    title="仓库"
    milestone="M3 第二步"
    description="多仓管理。库存余额的维度是 (SKU, 仓库, 批次)，因此仓库是一切库存操作的前提。"
    :features="[
      '仓库列表（含启用/停用状态）',
      '新建 / 编辑仓库（编码唯一）',
      '停用后不可再建单，但库存仍可查可出库',
    ]"
  />
</template>
