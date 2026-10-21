<script setup lang="ts">
import Placeholder from '@/views/_Placeholder.vue'
</script>

<template>
  <Placeholder
    title="供应商"
    milestone="M3 第二步"
    description="供应商档案管理，进货单选择供应商时需要。"
    :features="[
      '供应商列表（按编码/名称搜索）',
      '新建 / 编辑供应商（编码唯一）',
      '联系电话、地址等联系方式维护',
      '停用不删：历史进货单仍需可追溯',
    ]"
  />
</template>
