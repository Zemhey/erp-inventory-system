<script setup lang="ts">
import Placeholder from '@/views/_Placeholder.vue'
</script>

<template>
  <Placeholder
    title="出货单"
    milestone="M3 第三步"
    description="出货全流程：建单即预占 → 审核 → 出货。建单会锁定库存，库存不足则整单回滚。"
    :features="[
      '出货单列表（按状态/客户筛选）',
      '新建出货单：选客户、仓库、多行明细与折扣',
      '建单即预占库存（可用量不足会整单失败）',
      '出货确认：按 FEFO 扣减最早效期批次',
      '作废未出货单据会自动释放预占',
    ]"
  />
</template>
