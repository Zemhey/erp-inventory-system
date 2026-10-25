<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  arApi,
  type CustomerReceivable,
} from '@/api/salesV23'
import { fenToYuan, invOpsApi, type InventoryAlertRow } from '@/api/client'

/**
 * 消息通知中心（FR-V3-27，本期为"聚合待办"形态）。
 *
 * ★ 诚实说明：没有 sys_notification 持久化表与已读状态 —— 后端 sys 域包不在
 *   本泳道文件所有权内（W1 地盘），顶栏 MainLayout.vue 同样不可改。
 *   这里把三类真实信号实时聚合成"未处理事项"，每次刷新都是最新事实，
 *   不存在"通知发了但事情已处理"的失真；铃铛挂件与已读表建议由 W1 合并时接入。
 */

const router = useRouter()
const loading = ref(false)
const errors = ref<string[]>([])

const alerts = ref<InventoryAlertRow[]>([])
const overdue = ref<CustomerReceivable[]>([])

interface Item {
  kind: '库存预警' | '应收逾期'
  level: 'danger' | 'warning'
  title: string
  detail: string
  path: string
}

async function load() {
  loading.value = true
  errors.value = []
  const [a, o] = await Promise.allSettled([
    invOpsApi.alerts({}),
    arApi.receivables({ overdue: true, page: 1, size: 20 }),
  ])
  if (a.status === 'fulfilled') alerts.value = a.value.items
  else errors.value.push(`库存预警：${a.reason instanceof Error ? a.reason.message : '加载失败'}`)
  if (o.status === 'fulfilled') overdue.value = o.value.items
  else errors.value.push(`应收逾期：${o.reason instanceof Error ? o.reason.message : '加载失败'}`)
  loading.value = false
}

const items = computed<Item[]>(() => {
  const out: Item[] = []
  for (const a of alerts.value.slice(0, 30)) {
    out.push({
      kind: '库存预警',
      level: a.type === 'EXPIRING' ? 'danger' : 'warning',
      title:
        a.type === 'SAFETY'
          ? `SKU #${a.skuId} 可用 ${a.availableQty} 低于下限 ${a.threshold}`
          : a.type === 'SLOW_MOVING'
            ? `SKU #${a.skuId} 滞销（阈值 ${a.threshold} 天）`
            : `SKU #${a.skuId} 批次 ${a.batchNo ?? '-'} 临期（${a.expiryDate ?? '?'}）`,
      detail: `仓库 #${a.warehouseId} · 在手 ${a.onHandQty}`,
      path: '/inventory-alerts',
    })
  }
  for (const r of overdue.value) {
    out.push({
      kind: '应收逾期',
      level: 'danger',
      title: `客户 #${r.customerId} 应收 ¥${fenToYuan(r.balance)} 已逾期`,
      detail: `单 ${r.id} · 到期 ${r.dueDate ?? '—'}`,
      path: '/receivables',
    })
  }
  return out
})

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2 class="page-title">消息通知（待办中心）</h2>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </div>
    <p class="page-hint">
      实时聚合三类信号里的两类已接入：库存预警（低于安全库存/滞销/临期）与应收逾期；
      「单据待审」入口在审批工作台。点击行可跳转到处理页。
    </p>

    <el-alert
      v-for="e in errors"
      :key="e"
      type="error"
      show-icon
      :closable="false"
      class="mb-16"
      :title="e + '（其余来源仍正常展示）'"
    >
      <el-button size="small" @click="load">重试</el-button>
    </el-alert>

    <div class="badge-row">
      <el-tag type="warning">库存预警 {{ alerts.length }}</el-tag>
      <el-tag type="danger">应收逾期 {{ overdue.length }}</el-tag>
      <el-tag>合计待办 {{ items.length }}</el-tag>
    </div>

    <el-table v-loading="loading" :data="items" @row-click="(row: Item) => router.push(row.path)">
      <el-table-column label="类型" width="110">
        <template #default="{ row }">
          <el-tag :type="row.level" size="small">{{ row.kind }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="事项" prop="title" min-width="280" />
      <el-table-column label="补充" prop="detail" min-width="180" />
      <el-table-column label="去向" width="160">
        <template #default="{ row }">
          <el-button link type="primary" @click.stop="router.push(row.path)">{{ row.path }}</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && items.length === 0" description="当前没有待办 —— 库存与应收都健康" />
  </div>
</template>

<style scoped>
.badge-row {
  display: flex;
  gap: 8px;
  margin: 10px 0;
}
</style>
