<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import PageTable from '@/components/PageTable.vue'
import {
  invOpsApi,
  mdmApi,
  type AlertType,
  type InventoryAlertRow,
  type Warehouse,
} from '@/api/client'

/**
 * 库存预警（FR-V3-18 / FR-INV-12）。
 *
 * 数据源是既有 GET /inventory/alerts（后端一次返回全部类型，这里按 Tab 分组）。
 * 三种类型：SAFETY 安全库存 / SLOW_MOVING 滞销 / EXPIRING 临期。
 * 阈值来自 skus.min_level/max_level（V9 已有列）——
 * ★ 本期 mdm 的 SKU 更新接口尚未暴露这两个字段，调整阈值暂需 DBA 改库，
 *   已记入报告的缺口清单。
 */

const router = useRouter()
const tab = ref<AlertType>('SAFETY')
const all = ref<InventoryAlertRow[]>([])
const loading = ref(false)
const error = ref('')
const warehouses = ref<Warehouse[]>([])
const warehouseId = ref<number | undefined>(undefined)
const skuNames = ref<Map<number, string>>(new Map())

const typeLabels: Record<AlertType, string> = {
  SAFETY: '安全库存',
  SLOW_MOVING: '滞销',
  EXPIRING: '临期',
}

function errorMessage(cause: unknown): string {
  const e = cause as { response?: { data?: { message?: string } }; message?: string }
  return e?.response?.data?.message || e?.message || '请求失败'
}

const grouped = computed<Record<AlertType, InventoryAlertRow[]>>(() => ({
  SAFETY: all.value.filter((a) => a.type === 'SAFETY'),
  SLOW_MOVING: all.value.filter((a) => a.type === 'SLOW_MOVING'),
  EXPIRING: all.value.filter((a) => a.type === 'EXPIRING'),
}))

const rows = computed(() => grouped.value[tab.value] ?? [])

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await invOpsApi.alerts({ warehouseId: warehouseId.value })
    all.value = data.items
  } catch (cause) {
    all.value = []
    error.value = errorMessage(cause)
  } finally {
    loading.value = false
  }
}

function skuLabel(id: number): string {
  return skuNames.value.get(id) ?? `#${id}`
}

onMounted(async () => {
  void load()
  try {
    const [whs, skuPage] = await Promise.all([
      mdmApi.listWarehouses(false),
      mdmApi.listSkus({ page: 1, pageSize: 500 }),
    ])
    warehouses.value = whs
    const products = await mdmApi.listProducts({ page: 1, size: 200 })
    const nameByProduct = new Map(products.items.map((p) => [p.id, p.name]))
    skuNames.value = new Map(
      skuPage.items.map((s) => [
        s.id,
        `${nameByProduct.get(s.productId) ?? '商品#' + s.productId} ${s.color}/${s.size}`,
      ]),
    )
  } catch (cause) {
    ElMessage.warning(`辅助数据加载失败：${errorMessage(cause)}`)
  }
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2 class="page-title">库存预警</h2>
      <div>
        <el-select v-model="warehouseId" clearable placeholder="全部仓库" style="width: 160px" @change="load">
          <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
        </el-select>
        <el-button @click="load">刷新</el-button>
        <el-button type="primary" @click="router.push('/replenishment')">去补货建议</el-button>
      </div>
    </div>
    <p class="page-hint">
      安全库存：可用量 &lt; skus.min_level；滞销：超过阈值天数无出库；临期：批次效期临近（含过期）。
      列表按仓库优先级降序。
    </p>
    <el-alert v-if="error" type="error" show-icon :closable="false" class="mb-16" :title="`加载失败：${error}`" />

    <el-tabs v-model="tab">
      <el-tab-pane v-for="key in (['SAFETY', 'SLOW_MOVING', 'EXPIRING'] as AlertType[])" :key="key" :name="key">
        <template #label>
          {{ typeLabels[key] }}
          <el-badge :value="grouped[key].length" :hidden="grouped[key].length === 0" type="warning" class="tab-badge" />
        </template>
      </el-tab-pane>
    </el-tabs>

    <PageTable
      :rows="rows"
      :loading="loading"
      :paginated="false"
      :load-failed="!!error"
      :empty-text="`当前没有「${typeLabels[tab]}」预警`"
      empty-hint="说明所有 SKU 都在阈值内 / 都有动销 / 效期充裕。"
    >
      <el-table-column label="SKU" min-width="200">
        <template #default="{ row }">{{ skuLabel(row.skuId) }}</template>
      </el-table-column>
      <el-table-column label="仓库" width="90">
        <template #default="{ row }">#{{ row.warehouseId }}（优先级{{ row.warehousePriority }}）</template>
      </el-table-column>
      <el-table-column label="批次" width="130">
        <template #default="{ row }">{{ row.batchNo ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="在手" prop="onHandQty" width="80" />
      <el-table-column label="可用" width="80">
        <template #default="{ row }">
          <span class="danger-qty">{{ row.availableQty }}</span>
        </template>
      </el-table-column>
      <el-table-column label="阈值" width="90">
        <template #default="{ row }">
          {{ tab === 'SAFETY' ? `下限 ${row.threshold}` : tab === 'SLOW_MOVING' ? `${row.threshold} 天` : `${row.threshold} 天内` }}
        </template>
      </el-table-column>
      <el-table-column label="效期" width="110">
        <template #default="{ row }">{{ row.expiryDate ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="最后动销" width="150">
        <template #default="{ row }">{{ row.lastMovementAt ?? '—' }}</template>
      </el-table-column>
    </PageTable>
  </div>
</template>

<style scoped>
.tab-badge {
  margin-left: 6px;
}
.danger-qty {
  color: var(--el-color-danger);
  font-weight: 600;
}
</style>
