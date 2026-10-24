<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  invOpsApi,
  mdmApi,
  type InventoryTransfer,
  type Product,
  type Sku,
  type TransferStatus,
  type Warehouse,
} from '@/api/client'
import { useSessionStore } from '@/stores/session'
import PageTable from '@/components/PageTable.vue'

/**
 * 仓间调拨（FR-INV-07，两段式）。
 *
 * ★ 这一页最重要的信息不是单号，而是"货现在在哪"：
 *   调出后源仓减少、目的仓还没增加，这段数量挂在 `inTransitQty` 上。
 *   所以列表把"在途量"单独成列并高亮，展开行显示每个批次的在途数量；
 *   到货按钮只在 IN_TRANSIT 时出现。
 *
 * ★ 前端不做守恒计算（后端已保证 INV-7）：
 *   页面只展示后端返回的 inTransitQty / status，
 *   到货后由后端返回 conservationDiff=0，前端仅原样提示。
 */

const session = useSessionStore()
const canWrite = computed(() => session.hasPermission('inv:write'))

// ─────────────────────────── 主数据字典 ───────────────────────────

const warehouses = ref<Warehouse[]>([])
const skus = ref<Sku[]>([])
const products = ref<Product[]>([])

async function loadDictionary() {
  const [wh, sku, prod] = await Promise.allSettled([
    mdmApi.listWarehouses(false),
    mdmApi.listSkus({ page: 1, pageSize: 200 }),
    mdmApi.listProducts({ page: 1, size: 200 }),
  ])
  if (wh.status === 'fulfilled') warehouses.value = wh.value
  if (sku.status === 'fulfilled') skus.value = sku.value.items
  if (prod.status === 'fulfilled') products.value = prod.value.items
}

const warehouseNames = computed(() => new Map(warehouses.value.map((w) => [w.id, w.name])))
const productNames = computed(() => new Map(products.value.map((p) => [p.id, p.name])))
const skuNames = computed(() => {
  const map = new Map<number, string>()
  for (const s of skus.value) {
    map.set(s.id, `${productNames.value.get(s.productId) ?? `商品#${s.productId}`} · ${s.color}-${s.size}`)
  }
  return map
})

function warehouseText(id: number): string {
  return warehouseNames.value.get(id) ?? `仓库#${id}`
}
function skuText(id: number): string {
  return skuNames.value.get(id) ?? `SKU#${id}`
}
function dateTimeText(iso: string | null | undefined): string {
  if (!iso) return '-'
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString()
}
function statusText(status: TransferStatus): string {
  return status === 'IN_TRANSIT' ? '在途' : '已到货'
}
function statusType(status: TransferStatus): 'warning' | 'success' {
  return status === 'IN_TRANSIT' ? 'warning' : 'success'
}

function errorMessage(error: unknown): string {
  const e = error as { response?: { data?: { message?: string } }; message?: string }
  return e?.response?.data?.message || e?.message || '请求失败'
}

// ─────────────────────────── 列表 ───────────────────────────

const filters = reactive({
  status: undefined as TransferStatus | undefined,
  fromWarehouseId: undefined as number | undefined,
  toWarehouseId: undefined as number | undefined,
})
const rows = ref<InventoryTransfer[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const loading = ref(false)
const listError = ref('')
const filtered = computed(
  () =>
    filters.status !== undefined ||
    filters.fromWarehouseId !== undefined ||
    filters.toWarehouseId !== undefined,
)

async function load() {
  loading.value = true
  listError.value = ''
  try {
    const data = await invOpsApi.listTransfers({
      status: filters.status,
      fromWarehouseId: filters.fromWarehouseId,
      toWarehouseId: filters.toWarehouseId,
      page: page.value,
      size: pageSize.value,
    })
    rows.value = data.items
    total.value = data.total
  } catch (error) {
    rows.value = []
    total.value = 0
    listError.value = errorMessage(error)
  } finally {
    loading.value = false
  }
}

function onPageChange(nextPage: number, nextSize: number) {
  page.value = nextPage
  pageSize.value = nextSize
  load()
}

function search() {
  page.value = 1
  load()
}

function resetFilters() {
  filters.status = undefined
  filters.fromWarehouseId = undefined
  filters.toWarehouseId = undefined
  search()
}

// ─────────────────────────── 新建调拨 ───────────────────────────

interface CreateLine {
  skuId: number | undefined
  batchNo: string
  qty: number
}

const dialogVisible = ref(false)
const submitting = ref(false)
const form = reactive({
  fromWarehouseId: undefined as number | undefined,
  toWarehouseId: undefined as number | undefined,
  remark: '',
  lines: [] as CreateLine[],
})

function openCreate() {
  form.fromWarehouseId = undefined
  form.toWarehouseId = undefined
  form.remark = ''
  form.lines = [{ skuId: undefined, batchNo: '', qty: 1 }]
  dialogVisible.value = true
}

function addLine() {
  form.lines.push({ skuId: undefined, batchNo: '', qty: 1 })
}

function removeLine(index: number) {
  if (form.lines.length === 1) return
  form.lines.splice(index, 1)
}

async function submitCreate() {
  if (!form.fromWarehouseId || !form.toWarehouseId) {
    ElMessage.warning('请选择调出仓与调入仓')
    return
  }
  if (form.fromWarehouseId === form.toWarehouseId) {
    ElMessage.warning('调出仓与调入仓不能相同')
    return
  }
  const lines = form.lines.filter((l) => l.skuId && l.qty > 0)
  if (lines.length === 0) {
    ElMessage.warning('至少填写一行有效明细（SKU + 正数量）')
    return
  }
  if (lines.length !== form.lines.length) {
    ElMessage.warning('存在未选择 SKU 或数量非正的明细，请补全或删除')
    return
  }
  submitting.value = true
  try {
    await invOpsApi.createTransfer({
      fromWarehouseId: form.fromWarehouseId,
      toWarehouseId: form.toWarehouseId,
      remark: form.remark.trim() || undefined,
      lines: lines.map((l) => ({
        skuId: l.skuId as number,
        batchNo: l.batchNo.trim() || undefined,
        qty: l.qty,
      })),
    })
    ElMessage.success('调拨已发运，库存转入在途')
    dialogVisible.value = false
    search()
  } catch {
    // 拦截器已展示后端消息（含"库存不足：SKU … 实存 … 预占 … 冻结 …"）
  } finally {
    submitting.value = false
  }
}

// ─────────────────────────── 到货 ───────────────────────────

async function receive(row: InventoryTransfer) {
  try {
    await ElMessageBox.confirm(
      `确认 ${row.transferNo} 的货已到达「${warehouseText(row.toWarehouseId)}」？\n` +
        `到货后在途量 ${row.inTransitQty} 将转入目的仓库存，操作不可撤销。`,
      '确认到货',
      { confirmButtonText: '确认到货', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    const result = await invOpsApi.receiveTransfer(row.id)
    ElMessage.success(
      result.conservationDiff === 0
        ? '到货完成，调拨守恒校验通过'
        : `到货完成，但守恒差为 ${result.conservationDiff}，请联系管理员`,
    )
    load()
  } catch {
    // 拦截器已展示后端消息
  }
}

onMounted(async () => {
  await loadDictionary()
  await load()
})
</script>

<template>
  <div class="transfer-page">
    <PageTable
      :rows="rows"
      :loading="loading"
      :total="total"
      :page="page"
      :page-size="pageSize"
      :filtered="filtered"
      :load-failed="!!listError"
      :can-create="canWrite"
      create-text="新建第一张调拨单"
      empty-text="还没有调拨单"
      empty-hint="调拨是两段式：发运后货进入在途，目的仓确认到货后才增加库存。"
      @update:page="page = $event"
      @update:page-size="pageSize = $event"
      @change="onPageChange"
      @create="openCreate"
    >
      <template #filters>
        <el-select
          v-model="filters.fromWarehouseId"
          placeholder="调出仓"
          clearable
          style="width: 160px"
          @change="search"
        >
          <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
        </el-select>
        <el-select
          v-model="filters.toWarehouseId"
          placeholder="调入仓"
          clearable
          style="width: 160px"
          @change="search"
        >
          <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
        </el-select>
        <el-select
          v-model="filters.status"
          placeholder="状态"
          clearable
          style="width: 130px"
          @change="search"
        >
          <el-option label="在途" value="IN_TRANSIT" />
          <el-option label="已到货" value="COMPLETED" />
        </el-select>
        <el-button @click="resetFilters">重置</el-button>
        <el-button type="primary" :disabled="!canWrite" @click="openCreate">新建调拨</el-button>
        <span v-if="listError" class="error-text">{{ listError }}</span>
      </template>

      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="expand-lines">
            <el-table :data="row.lines" size="small" border>
              <el-table-column label="行号" prop="lineNo" width="70" />
              <el-table-column label="SKU" min-width="200">
                <template #default="{ row: line }">{{ skuText(line.skuId) }}</template>
              </el-table-column>
              <el-table-column label="批次" prop="batchNo" width="120" />
              <el-table-column label="调拨量" prop="qty" width="100" />
              <el-table-column label="在途量" width="100">
                <template #default="{ row: line }">
                  <el-tag v-if="line.inTransitQty > 0" type="warning" size="small">
                    {{ line.inTransitQty }}
                  </el-tag>
                  <span v-else>0</span>
                </template>
              </el-table-column>
              <el-table-column label="效期" width="120">
                <template #default="{ row: line }">{{ line.expiryDate || '-' }}</template>
              </el-table-column>
            </el-table>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="调拨单号" prop="transferNo" min-width="170" />
      <el-table-column label="调出仓" min-width="120">
        <template #default="{ row }">{{ warehouseText(row.fromWarehouseId) }}</template>
      </el-table-column>
      <el-table-column label="调入仓" min-width="120">
        <template #default="{ row }">{{ warehouseText(row.toWarehouseId) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="在途量" width="100">
        <template #default="{ row }">
          <el-tag v-if="row.inTransitQty > 0" type="warning" size="small">
            {{ row.inTransitQty }}
          </el-tag>
          <span v-else>0</span>
        </template>
      </el-table-column>
      <el-table-column label="业务日期" prop="bizDate" width="120" />
      <el-table-column label="创建时间" width="170">
        <template #default="{ row }">{{ dateTimeText(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="到货时间" width="170">
        <template #default="{ row }">{{ dateTimeText(row.receivedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="row.status === 'IN_TRANSIT'"
            type="primary"
            link
            :disabled="!canWrite"
            @click="receive(row)"
          >
            确认到货
          </el-button>
          <span v-else class="text-muted">已完成</span>
        </template>
      </el-table-column>
    </PageTable>

    <el-dialog v-model="dialogVisible" title="新建调拨单" width="860px" :close-on-click-modal="false">
      <el-form label-width="90px">
        <el-form-item label="调出仓" required>
          <el-select v-model="form.fromWarehouseId" placeholder="选择调出仓" style="width: 100%">
            <el-option
              v-for="w in warehouses.filter((x) => x.enabled)"
              :key="w.id"
              :label="w.name"
              :value="w.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="调入仓" required>
          <el-select v-model="form.toWarehouseId" placeholder="选择调入仓" style="width: 100%">
            <el-option
              v-for="w in warehouses.filter((x) => x.enabled && x.id !== form.fromWarehouseId)"
              :key="w.id"
              :label="w.name"
              :value="w.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" maxlength="500" show-word-limit />
        </el-form-item>
        <el-form-item label="明细" required>
          <div class="line-editor">
            <div v-for="(line, index) in form.lines" :key="index" class="line-row">
              <el-select
                v-model="line.skuId"
                placeholder="选择 SKU"
                filterable
                style="flex: 1"
              >
                <el-option
                  v-for="s in skus"
                  :key="s.id"
                  :label="skuText(s.id)"
                  :value="s.id"
                  :disabled="!s.enabled"
                />
              </el-select>
              <el-input v-model="line.batchNo" placeholder="批次（可空）" style="width: 140px" />
              <el-input-number v-model="line.qty" :min="1" :precision="0" style="width: 130px" />
              <el-button link type="danger" :disabled="form.lines.length === 1" @click="removeLine(index)">
                删除
              </el-button>
            </div>
            <el-button link type="primary" @click="addLine">+ 添加明细</el-button>
          </div>
        </el-form-item>
      </el-form>
      <div class="dialog-hint">
        发运后源仓立即扣减、数量进入在途；目的仓需在列表中点击「确认到货」才增加库存。
      </div>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitCreate">确认发运</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.transfer-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.expand-lines {
  padding: 8px 24px 12px;
  background: #fafafa;
}
.line-editor {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.line-row {
  display: flex;
  gap: 8px;
  align-items: center;
}
.dialog-hint {
  margin-top: 4px;
  padding: 8px 12px;
  background: #f4f4f5;
  color: #606266;
  font-size: 12px;
  border-radius: 4px;
}
.error-text {
  color: #f56c6c;
  font-size: 13px;
}
</style>
