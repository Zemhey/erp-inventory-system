<script setup lang="ts">
import Placeholder from '@/views/_Placeholder.vue'
</script>

<template>
  <Placeholder
    title="进货单"
    milestone="M3 第三步"
    description="进货全流程：建单 → 提交 → 审核 → 入库。入库会真实调用库存模块，是本系统第一条业务闭环。"
    :features="[
      '进货单列表（按状态/供应商筛选）',
      '新建进货单：选供应商、仓库、多行明细',
      '状态流转：提交 → 审核 → 入库确认',
      '分批入库：显示每行已入库量与本单剩余未入量',
      '入库时指定批次与效期（FEFO 的依据）',
    ]"
  />
</template>
