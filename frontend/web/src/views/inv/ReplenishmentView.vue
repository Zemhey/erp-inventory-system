<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  fenToYuan,
  invOpsApi,
  mdmApi,
  requisitionApi,
  type ReplenishmentSuggestion,
  type Supplier,
  type Warehouse,
} from '@/api/client'

/**
 * 补货建议（FR-V3-19 / FR-INV-13）。
 *
 * 公式（后端口径，已抽成 ReplenishmentFormula 纯函数并配单测）：
 *   可用量 < min_level 且 无在途（已下未到 >0 直接跳过） → 建议量 = max_level − 可用量
 * 纯查询、不落库：重复调用结果一致。
 * 「一键生成请购单」按仓库分组调用现有 POST /purchase-requisitions（DRAFT），
 * 单价取 SKU 零售价（分），后续走正常请购→转订单审批链路。
 */

const router = useRouter()
const rows = ref<ReplenishmentSuggestion[]>([])
const selected = ref<ReplenishmentSuggestion[]>([])
const loading = ref(false)
const generating = ref(false)
const error = ref('')

const warehouses = ref<Warehouse[]>([])
const warehouseId = ref<number | undefined>(undefined)
const suppliers = ref<Supplier[]>([])
const supplierId = ref<number | undefined>(undefined)
const skuPrices = ref<Map<number, number>>(new Map())
const skuNames = ref<Map<number, string>>(new Map())

function errorMessage(cause: unknown): string {
  const e = cause as { response?: { data?: { message?: string } }; message?: string }
  return e?.response?.data?.message || e?.message || '请求失败'
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await invOpsApi.replenishment({ warehouseId: warehouseId.value })
    rows.value = data.items
    selected.value = []
  } catch (cause) {
    rows.value = []
    error.value = errorMessage(cause)
  } finally {
    loading.value = false
  }
}

async function generateRequisitions() {
  if (selected.value.length === 0) {
    ElMessage.warning('先勾选要补货的 SKU 行')
    return
  }
  if (!supplierId.value) {
    ElMessage.warning('请选择供应商（请购单必填）')
    return
  }
  generating.value = true
  // 按仓库分组：请购单头是单仓的，跨仓建议必须拆成多张请购单
  const byWarehouse = new Map<number, ReplenishmentSuggestion[]>()
  for (const s of selected.value) {
    byWarehouse.set(s.warehouseId, [...(byWarehouse.get(s.warehouseId) ?? []), s])
  }
  const created: string[] = []
  try {
    for (const [whId, list] of byWarehouse) {
      const r = await requisitionApi.create({
        supplierId: supplierId.value,
        warehouseId: whId,
        remark: '补货建议一键生成（V3-3）',
        lines: list.map((s) => ({
          skuId: s.skuId,
          qty: s.suggestedQty,
          unitPriceAmount: skuPrices.value.get(s.skuId) ?? 0,
        })),
      })
      created.push(r.orderNo)
    }
    ElMessage.success(`已生成 ${created.length} 张请购单：${created.join('、')}`)
    void load()
  } catch (cause) {
    ElMessage.error(errorMessage(cause))
  } finally {
    generating.value = false
  }
}

function skuLabel(id: number): string {
  return skuNames.value.get(id) ?? `#${id}`
}

onMounted(async () => {
  void load()
  try {
    const [whs, sups, skuPage, products] = await Promise.all([
      mdmApi.listWarehouses(true),
      mdmApi.listSuppliers({ page: 1, size: 100 }),
      mdmApi.listSkus({ page: 1, pageSize: 500 }),
      mdmApi.listProducts({ page: 1, size: 200 }),
    ])
    warehouses.value = whs
    suppliers.value = sups.items
    const nameByProduct = new Map(products.items.map((p) => [p.id, p.name]))
    skuPrices.value = new Map(skuPage.items.map((s) => [s.id, s.retailPriceAmount]))
    skuNames.value = new Map(
      skuPage.items.map((s) => [s.id, `${nameByProduct.get(s.productId) ?? ''} ${s.color}/${s.size}`]),
    )
  } catch (cause) {
    ElMessage.warning(`辅助数据加载失败：${errorMessage(cause)}`)
  }
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2 class="page-title">补货建议</h2>
      <div>
        <el-select v-model="warehouseId" clearable placeholder="全部仓库" style="width: 150px" @change="load">
          <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
        </el-select>
        <el-button @click="load">刷新</el-button>
        <el-button @click="router.push('/inventory-alerts')">看预警</el-button>
      </div>
    </div>
    <p class="page-hint">
      建议量 = max_level − 可用量（仅当 可用量 &lt; min_level 且同仓同 SKU 无在途采购）。
      纯查询不落库，重复点击结果一致。勾选后按仓库分组生成请购单（DRAFT，走正常审批）。
    </p>
    <el-alert v-if="error" type="error" show-icon :closable="false" class="mb-16" :title="`加载失败：${error}`" />

    <div class="action-bar">
      <el-select v-model="supplierId" filterable placeholder="请购供应商" style="width: 220px">
        <el-option v-for="s in suppliers" :key="s.id" :label="`${s.code} ${s.name}`" :value="s.id" />
      </el-select>
      <el-button type="primary" :loading="generating" :disabled="selected.length === 0" @click="generateRequisitions">
        一键生成请购单（已选 {{ selected.length }} 行）
      </el-button>
      <span class="text-muted">单价取 SKU 零售价：¥{{ fenToYuan(0) }} 起，可在请购页修改</span>
    </div>

    <el-table v-loading="loading" :data="rows" @selection-change="(v: ReplenishmentSuggestion[]) => (selected = v)">
      <el-table-column type="selection" width="45" />
      <el-table-column label="SKU" min-width="200">
        <template #default="{ row }">{{ skuLabel(row.skuId) }}</template>
      </el-table-column>
      <el-table-column label="仓库" width="80">
        <template #default="{ row }">#{{ row.warehouseId }}</template>
      </el-table-column>
      <el-table-column label="可用" prop="availableQty" width="80" />
      <el-table-column label="下限(min)" prop="minLevel" width="90" />
      <el-table-column label="上限(max)" prop="maxLevel" width="90" />
      <el-table-column label="建议采购量" width="110">
        <template #default="{ row }">
          <b class="suggest">{{ row.suggestedQty }}</b>
        </template>
      </el-table-column>
      <el-table-column label="参考单价" width="110">
        <template #default="{ row }">¥{{ fenToYuan(skuPrices.get(row.skuId) ?? 0) }}</template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && rows.length === 0" description="没有需要补货的 SKU（都高于 min_level 或有在途）" />
  </div>
</template>

<style scoped>
.action-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 12px 0;
}
.suggest {
  color: var(--el-color-primary);
}
</style>
