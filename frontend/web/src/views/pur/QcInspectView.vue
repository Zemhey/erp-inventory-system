<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageTable from '@/components/PageTable.vue'
import {
  fenToYuan,
  purApi,
  purchaseReceiptApi,
  type PurchaseOrder,
  type PurchaseReceipt,
} from '@/api/client'

/**
 * 到货质检（FR-V3-17 / FR-PUR-05）。
 *
 * ★ 口径说明（与后端真实链路一致，不另造一套质检单）：
 *   本项目质检发生在进货单「收货」动作里（receive 的每条到货行带 qcQty/qcNote），
 *   合格量才入库存与应付；不合格量只留在到货记录上。
 *   这一页是质检结果看板：选单 → 看到货行合格/拒收/状态，
 *   不合格行的处置出口是「采购退货」页（已入库部分红冲）。
 */

const orders = ref<PurchaseOrder[]>([])
const ordersLoading = ref(false)
const pickedId = ref<number | null>(null)
const receipts = ref<PurchaseReceipt[]>([])
const receiptsLoading = ref(false)
const error = ref('')

function errorMessage(cause: unknown): string {
  const e = cause as { response?: { data?: { message?: string } }; message?: string }
  return e?.response?.data?.message || e?.message || '请求失败'
}

async function loadOrders() {
  ordersLoading.value = true
  error.value = ''
  try {
    const [approved, partial, received] = await Promise.all([
      purApi.list({ status: 'APPROVED', page: 1, size: 60 }),
      purApi.list({ status: 'PARTIAL', page: 1, size: 60 }),
      purApi.list({ status: 'RECEIVED', page: 1, size: 60 }),
    ])
    orders.value = [...approved.items, ...partial.items, ...received.items]
  } catch (cause) {
    orders.value = []
    error.value = errorMessage(cause)
  } finally {
    ordersLoading.value = false
  }
}

async function onPick(id: number) {
  pickedId.value = id
  receiptsLoading.value = true
  try {
    receipts.value = await purchaseReceiptApi.listByOrder(id)
  } catch (cause) {
    receipts.value = []
    ElMessage.error(errorMessage(cause))
  } finally {
    receiptsLoading.value = false
  }
}

const qcMeta = (status: string): { label: string; type: 'success' | 'warning' | 'danger' | 'info' } =>
  status === 'PASSED'
    ? { label: '合格', type: 'success' }
    : status === 'PARTIAL'
      ? { label: '部分合格', type: 'warning' }
      : status === 'FAILED'
        ? { label: '不合格', type: 'danger' }
        : { label: '未检', type: 'info' }

const summary = computed(() => {
  let lines = 0
  let rejected = 0
  let pending = 0
  for (const r of receipts.value) {
    for (const l of r.lines) {
      lines += 1
      rejected += l.rejectedQty ?? 0
      // 后端 qcStatus 是字符串枚举；NONE（未检）不在前端联合类型里，用 String 比较兜底
      const st = String(l.qcStatus ?? 'NONE')
      if (st === 'NONE') pending += 1
    }
  }
  return { lines, rejected, pending }
})

onMounted(loadOrders)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2 class="page-title">到货质检</h2>
      <el-button :loading="ordersLoading" @click="loadOrders">刷新单据</el-button>
    </div>
    <p class="page-hint">
      质检结果在收货动作中录入（合格量才入库存/应付）；本页汇总展示各到货行的
      合格 / 拒收 / 未检状态，不合格已入库部分请到「采购退货」页发起红冲。
    </p>

    <el-alert v-if="error" type="error" show-icon :closable="false" class="mb-16" :title="error" />

    <el-select
      placeholder="选择进货单（已审核 / 部分入库 / 已入库）"
      filterable
      style="width: 100%"
      :loading="ordersLoading"
      @change="onPick"
    >
      <el-option
        v-for="o in orders"
        :key="o.id"
        :label="`${o.orderNo} · ${o.status} · ¥${fenToYuan(o.totalAmount)}`"
        :value="o.id"
      />
    </el-select>

    <div v-if="pickedId" class="summary-bar">
      <el-tag type="info">到货行 {{ summary.lines }}</el-tag>
      <el-tag type="warning">待检 {{ summary.pending }}</el-tag>
      <el-tag :type="summary.rejected > 0 ? 'danger' : 'success'">
        拒收数量 {{ summary.rejected }}
      </el-tag>
    </div>

    <div v-loading="receiptsLoading" class="receipt-list">
      <PageTable
        v-for="r in receipts"
        :key="r.id"
        :rows="r.lines"
        :paginated="false"
        table-size="small"
        :empty-text="`${r.receiptNo} 没有到货行`"
        class="mb-16"
      >
        <template #header>
          <div class="table-title">
            到货 {{ r.receiptNo }} · {{ r.bizDate }} · 仓 #{{ r.warehouseId }} ·
            合格 {{ r.acceptedQty }}（¥{{ fenToYuan(r.acceptedAmount) }}）/ 拒收 {{ r.rejectedQty }}
          </div>
        </template>
        <el-table-column label="行" prop="lineNo" width="55" />
        <el-table-column label="SKU" width="80">
          <template #default="{ row }">#{{ row.skuId }}</template>
        </el-table-column>
        <el-table-column label="批次" prop="batchNo" width="140" />
        <el-table-column label="到货" prop="receivedQty" width="70" />
        <el-table-column label="合格" prop="qcQty" width="70" />
        <el-table-column label="拒收" prop="rejectedQty" width="70" />
        <el-table-column label="质检状态" width="110">
          <template #default="{ row }">
            <el-tag :type="qcMeta(row.qcStatus).type" size="small">
              {{ qcMeta(row.qcStatus).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="质检备注" min-width="150" prop="qcNote" />
      </PageTable>

      <el-empty v-if="pickedId && !receipts.length && !receiptsLoading" description="该单还没有到货记录" />
    </div>
  </div>
</template>

<style scoped>
.summary-bar {
  display: flex;
  gap: 8px;
  margin: 12px 0;
}
.receipt-list {
  margin-top: 8px;
}
.table-title {
  font-weight: 600;
}
</style>
