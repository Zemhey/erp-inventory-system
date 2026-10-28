<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { fenToYuan } from '@/api/client'
import {
  skeletonApi,
  type TodoItem,
  type TodoType,
} from '@/api/skeleton'
import DocumentDrawer from '@/components/DocumentDrawer.vue'

/**
 * 待办中心（FR-V3-03）：四类待办收敛到一个页面，按 Tab 切换。
 *
 * ★ 四种待办：待审批（approval）/ 待收货（purchase-receipt）/ 待发货（sales-ship）/
 *   库存预警（low-stock）。后端 /todos?type= 各自聚合，前端只切参数。
 * ★ 待收货 / 待发货的单据，点"查看单据"调 /documents/{type}/{id}（复用 DocumentDrawer）。
 * ★ 金额单位分，展示层转元。
 */

const router = useRouter()

const TABS: { key: TodoType; label: string }[] = [
  { key: 'approval', label: '待审批' },
  { key: 'purchase-receipt', label: '待收货' },
  { key: 'sales-ship', label: '待发货' },
  { key: 'low-stock', label: '库存预警' },
]

const activeTab = ref<TodoType>('approval')
const loading = ref(false)
const error = ref('')
const items = ref<TodoItem[]>([])

const drawerVisible = ref(false)
const drawerType = ref('')
const drawerId = ref<number | null>(null)

function openDocument(type: string, id: number) {
  drawerType.value = type
  drawerId.value = id
  drawerVisible.value = true
}

/** 后端给的是 UTC 瞬时（`...Z`），直接铺出来会是"凌晨两点建单"这种看不懂的时间。 */
function dateTimeText(iso: string | null | undefined): string {
  if (!iso) return '-'
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString()
}

const BIZ_TYPE_LABELS: Record<string, string> = {
  PURCHASE_ORDER: '进货单',
  SALES_ORDER: '出货单',
  RETURN_ORDER: '退货单',
}

async function load(tab: TodoType) {
  loading.value = true
  error.value = ''
  try {
    const res = await skeletonApi.todos(tab, 200)
    items.value = res.items
  } catch (cause: unknown) {
    const e = cause as { response?: { data?: { message?: string } }; message?: string }
    error.value = e?.response?.data?.message || e?.message || '待办加载失败'
    items.value = []
  } finally {
    loading.value = false
  }
}

watch(activeTab, (t) => void load(t))
onMounted(() => void load(activeTab.value))
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2 class="page-title">待办中心</h2>
      <el-button :loading="loading" @click="load(activeTab)">刷新</el-button>
    </div>
    <p class="page-hint">把散落在各业务页的待办收敛到一处，按类型切换。</p>

    <el-alert
      v-if="error"
      type="error"
      show-icon
      :closable="false"
      class="mb-16"
      :title="`加载失败：${error}`"
    >
      <el-button size="small" @click="load(activeTab)">重试</el-button>
    </el-alert>

    <el-tabs v-model="activeTab" class="mb-16">
      <el-tab-pane v-for="t in TABS" :key="t.key" :name="t.key">
        <template #label>
          {{ t.label }}
          <el-badge
            v-if="t.key === activeTab && !loading && !error"
            :value="items.length"
            :max="999"
            type="warning"
            class="tab-badge"
          />
        </template>
      </el-tab-pane>
    </el-tabs>

    <div v-loading="loading">
      <!-- 待审批 -->
      <el-table v-if="activeTab === 'approval' && items.length" :data="items" border size="small">
        <el-table-column label="单号" prop="bizRefNo" min-width="160" />
        <el-table-column label="类型" width="110">
          <template #default="{ row }">
            <el-tag size="small">{{ BIZ_TYPE_LABELS[row.bizType] ?? row.bizType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="金额(元)" width="120">
          <template #default="{ row }">¥{{ fenToYuan(row.amount) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }"><el-tag size="small" type="warning">{{ row.status }}</el-tag></template>
        </el-table-column>
        <el-table-column label="创建时间" min-width="180">
          <template #default="{ row }">{{ dateTimeText(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default>
            <el-button link type="primary" @click="router.push('/approvals')">去审批</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 待收货 / 待发货（字段相同，type 决定调哪个详情端点） -->
      <el-table
        v-else-if="(activeTab === 'purchase-receipt' || activeTab === 'sales-ship') && items.length"
        :data="items"
        border
        size="small"
      >
        <el-table-column label="单号" prop="no" min-width="160" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }"><el-tag size="small">{{ row.status }}</el-tag></template>
        </el-table-column>
        <el-table-column label="往来单位" prop="partyName" min-width="140" />
        <el-table-column label="仓库" prop="warehouseName" min-width="140" />
        <el-table-column label="数量" prop="totalQty" width="80" />
        <el-table-column label="金额(元)" width="120">
          <template #default="{ row }">¥{{ fenToYuan(row.totalAmount) }}</template>
        </el-table-column>
        <el-table-column label="业务日期" prop="bizDate" width="120" />
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              @click="openDocument(activeTab === 'purchase-receipt' ? 'purchase' : 'sales', row.id)"
            >
              查看单据
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 库存预警：口径与「库存预警」页的 SAFETY 一致 —— 可用量低于安全库存的 SKU+仓库 -->
      <el-table v-else-if="activeTab === 'low-stock' && items.length" :data="items" border size="small">
        <el-table-column label="商品" min-width="180">
          <template #default="{ row }">{{ row.productCode }} {{ row.productName }}</template>
        </el-table-column>
        <el-table-column label="规格" prop="variant" width="120" />
        <el-table-column label="仓库" prop="warehouseName" min-width="120" />
        <el-table-column label="实存" prop="onHandQty" width="80" />
        <el-table-column label="可用" width="80">
          <template #default="{ row }">
            <el-tag size="small" type="danger">{{ row.availableQty }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="安全库存" prop="minLevel" width="90" />
        <el-table-column label="缺口" width="80">
          <template #default="{ row }">{{ row.minLevel - row.availableQty }}</template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default>
            <el-button link type="primary" @click="router.push('/inventory-alerts')">去调阈值</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-else-if="!loading && !error" description="这一类没有待办" />
    </div>

    <DocumentDrawer v-model:visible="drawerVisible" :type="drawerType" :id="drawerId" />
  </div>
</template>

<style scoped>
.page-hint {
  margin: 6px 0 12px;
}

.tab-badge {
  margin-left: 4px;
}
</style>
