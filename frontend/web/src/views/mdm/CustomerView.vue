<script setup lang="ts">
import Placeholder from '@/views/_Placeholder.vue'
</script>

<template>
  <Placeholder
    title="客户 / 会员"
    milestone="M3 第二步"
    description="客户档案与会员折扣等级。折扣以整数万分比存储（9000 = 9 折），M2 促销时参与售价计算。"
    :features="[
      '客户列表（按编码/名称/电话搜索）',
      '新建 / 编辑客户，设置折扣等级',
      '折扣率校验：1 ~ 10000 的整数万分比',
      '停用不删：历史出货单仍需可追溯',
    ]"
  />
</template>
