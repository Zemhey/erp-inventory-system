<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageTable from '@/components/PageTable.vue'
import {
  fenToYuan,
  purApi,
  purchaseReceiptApi,
  purchaseReturnApi,
  type PurchaseOrder,
  type PurchaseReceipt,
  type PurchaseReturn,
} from '@/api/client'

/**
 * 采购退货（FR-V3-14 / FR-PUR-06）。
 *
 * 后端契约：建单只收「原到货行 id + 数量」，批次/单价/金额全部由后端从
 * 到货记录派生；执行（approve）同时扣库存并冲应付，是采购闭环最危险的写动作。
 * 因此入口放在这里、但按钮语义保守：DRAFT 才能执行或作废。
 */

const rows = ref<PurchaseReturn[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const loading = ref(false)
const loadError = ref('')

const createOpen = ref(false)
const orders = ref<PurchaseOrder[]>([])
const pickedOrderId = ref<number | null>(null)
const receipts = ref<PurchaseReceipt[]>([])
const pickQty = ref<Record<string, number>>({})
const picking = ref(false)
const remark = ref('')

function errorMessage(cause: unknown): string {
  const e = cause as { response?: { data?: { message?: string } }; message?: string }
  return e?.response?.data?.message || e?.message || '请求失败'
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const data = await purchaseReturnApi.list({ page: page.value, size: pageSize.value })
    rows.value = data.items
    total.value = data.total
  } catch (cause) {
    rows.value = []
    loadError.value = errorMessage(cause)
  } finally {
    loading.value = false
  }
}

async function openCreate() {
  createOpen.value = true
  receipts.value = []
  pickedOrderId.value = null
  pickQty.value = {}
  remark.value = ''
  try {
    // 只有已审核/部分入库的单才可能有到货行
    const [approved, partial] = await Promise.all([
      purApi.list({ status: 'APPROVED', page: 1, size: 100 }),
      purApi.list({ status: 'PARTIAL', page: 1, size: 100 }),
    ])
    orders.value = [...approved.items, ...partial.items]
  } catch (cause) {
    ElMessage.error(errorMessage(cause))
  }
}

async function onPickOrder(id: number) {
  pickedOrderId.value = id
  picking.value = true
  pickQty.value = {}
  try {
    receipts.value = await purchaseReceiptApi.listByOrder(id)
  } catch (cause) {
    receipts.value = []
    ElMessage.error(errorMessage(cause))
  } finally {
    picking.value = false
  }
}

async function submitCreate() {
  if (!pickedOrderId.value) {
    ElMessage.warning('先选择一张进货单')
    return
  }
  const lines = Object.entries(pickQty.value)
    .filter(([, qty]) => qty > 0)
    .map(([key, qty]) => ({ receiptLineId: Number(key), qty }))
  if (lines.length === 0) {
    ElMessage.warning('至少给一条到货行填退货数量（退货原因填在备注）')
    return
  }
  try {
    await purchaseReturnApi.create({
      purchaseOrderId: pickedOrderId.value,
      remark: remark.value || undefined,
      lines,
    })
    createOpen.value = false
    ElMessage.success('退货单已创建（DRAFT），执行才会扣库存冲应付')
    void load()
  } catch (cause) {
    ElMessage.error(errorMessage(cause))
  }
}

async function act(row: PurchaseReturn, kind: 'approve' | 'void') {
  const word = kind === 'approve' ? '执行（扣库存并冲应付）' : '作废'
  await ElMessageBox.confirm(`确认${word}退货单 ${row.returnNo}？`, `${word}确认`, {
    type: 'warning',
  })
  try {
    if (kind === 'approve') await purchaseReturnApi.approve(row.id)
    else await purchaseReturnApi.voidReturn(row.id)
    ElMessage.success(`已${word}`)
    void load()
  } catch (cause) {
    ElMessage.error(errorMessage(cause))
  }
}

const statusMeta: Record<string, { label: string; type: 'info' | 'success' | 'danger' }> = {
  DRAFT: { label: '草稿', type: 'info' },
  COMPLETED: { label: '已执行', type: 'success' },
  VOIDED: { label: '已作废', type: 'danger' },
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2 class="page-title">采购退货</h2>
      <div>
        <el-button @click="load">刷新</el-button>
        <el-button type="primary" @click="openCreate">从进货单发起退货</el-button>
      </div>
    </div>
    <p class="page-hint">
      建单只选「到货行 + 数量」：批次、单价、金额由后端从到货记录派生；
      <b>执行</b>动作同时扣库存与冲应付，请确认后操作。退货原因写在备注（必填建议自行遵守）。
    </p>

    <el-alert v-if="loadError" type="error" show-icon :closable="false" class="mb-16" :title="`加载失败：${loadError}`" />

    <PageTable
      :rows="rows"
      :loading="loading"
      :total="total"
      v-model:page="page"
      v-model:page-size="pageSize"
      :load-failed="!!loadError"
      empty-text="还没有退货单"
      empty-hint="从右上角「从进货单发起退货」开始：只有已审核/部分入库的进货单才有到货行可退。"
      @change="load"
    >
      <el-table-column label="退货单号" prop="returnNo" width="170" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusMeta[row.status]?.type ?? 'info'" size="small">
            {{ statusMeta[row.status]?.label ?? row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="进货单" prop="purchaseOrderId" width="90" />
      <el-table-column label="供应商" prop="supplierId" width="90" />
      <el-table-column label="业务日期" prop="bizDate" width="110" />
      <el-table-column label="数量" prop="totalQty" width="80" />
      <el-table-column label="金额" width="120">
        <template #default="{ row }">¥ {{ fenToYuan(row.totalAmount) }}</template>
      </el-table-column>
      <el-table-column label="备注" min-width="140" prop="remark" />
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <template v-if="row.status === 'DRAFT'">
            <el-button link type="success" @click="act(row, 'approve')">执行</el-button>
            <el-button link type="danger" @click="act(row, 'void')">作废</el-button>
          </template>
          <span v-else class="text-muted">终态</span>
        </template>
      </el-table-column>
    </PageTable>

    <el-dialog v-model="createOpen" title="发起采购退货" width="760px">
      <el-select
        placeholder="选择进货单（已审核 / 部分入库）"
        filterable
        style="width: 100%"
        @change="onPickOrder"
      >
        <el-option
          v-for="o in orders"
          :key="o.id"
          :label="`${o.orderNo} · 供应商#${o.supplierId} · ¥${fenToYuan(o.totalAmount)}`"
          :value="o.id"
        />
      </el-select>

      <div v-loading="picking" class="receipt-box">
        <div v-for="r in receipts" :key="r.id" class="receipt-block">
          <div class="receipt-title">
            到货 {{ r.receiptNo }} · {{ r.bizDate }} · 合格 {{ r.acceptedQty }} / 拒收 {{ r.rejectedQty }}
          </div>
          <el-table :data="r.lines" size="small">
            <el-table-column label="行" prop="lineNo" width="55" />
            <el-table-column label="SKU" width="80">
              <template #default="{ row }">#{{ row.skuId }}</template>
            </el-table-column>
            <el-table-column label="批次" prop="batchNo" width="130" />
            <el-table-column label="到货" prop="receivedQty" width="70" />
            <el-table-column label="单价" width="100">
              <template #default="{ row }">¥{{ fenToYuan(row.unitPriceAmount) }}</template>
            </el-table-column>
            <el-table-column label="退货数量" min-width="140">
              <template #default="{ row }">
                <el-input-number
                  v-model="pickQty[String(row.id)]"
                  :min="0"
                  :max="row.receivedQty"
                  size="small"
                />
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>

      <el-input v-model="remark" placeholder="退货原因（写入备注）" maxlength="500" class="mt-8" />
      <template #footer>
        <el-button @click="createOpen = false">取消</el-button>
        <el-button type="primary" :disabled="!pickedOrderId" @click="submitCreate">
          创建退货单
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.receipt-box {
  margin-top: 12px;
  max-height: 320px;
  overflow: auto;
}
.receipt-block {
  margin-bottom: 12px;
}
.receipt-title {
  font-weight: 600;
  margin-bottom: 4px;
}
.mt-8 {
  margin-top: 8px;
}
</style>
