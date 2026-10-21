<script setup lang="ts">
import Placeholder from '@/views/_Placeholder.vue'
</script>

<template>
  <Placeholder
    title="库存查询"
    milestone="M3 第三步"
    description="实时库存查询与流水追溯。这是系统的核心视图：实存、预占、可用量三个数字必须一眼看清。"
    :features="[
      '按 SKU / 仓库 / 批次筛选库存',
      '区分显示实存 onHand、预占 reserved、可用 available',
      '库存流水追溯：按来源单据号反查每一笔出入库',
      'FEFO 批次视图：按效期排序，临期预警',
    ]"
  />
</template>
