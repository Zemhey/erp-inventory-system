<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import PageTable from '@/components/PageTable.vue'
import {
  invOpsApi,
  mdmApi,
  type AlertType,
  type InventoryAlertRow,
  type Sku,
  type Warehouse,
} from '@/api/client'
import { useSessionStore } from '@/stores/session'

/**
 * 库存预警（FR-V3-18 / FR-INV-12）。
 *
 * 数据源是既有 GET /inventory/alerts（后端一次返回全部类型，这里按 Tab 分组）。
 * 三种类型：SAFETY 安全库存 / SLOW_MOVING 滞销 / EXPIRING 临期。
 * 阈值来自 skus.min_level/max_level（V9 已有列）——
 * V4-2 起 mdm 的 SKU 创建/更新接口已接收这两个字段，本页 SAFETY 行上直接给"调阈值"入口：
 * 看到"下限 N"不合理，当场改掉、当场重算，不用再回主数据页翻 SKU。
 */

const session = useSessionStore()
const canWrite = computed(() => session.hasPermission('mdm:write'))

const router = useRouter()
const tab = ref<AlertType>('SAFETY')
const all = ref<InventoryAlertRow[]>([])
const loading = ref(false)
const error = ref('')
const warehouses = ref<Warehouse[]>([])
const warehouseId = ref<number | undefined>(undefined)
const skuNames = ref<Map<number, string>>(new Map())
/** skuId -> 整行 SKU：调阈值时要拿原值一起回传（PUT 是全量覆盖，见下） */
const skusById = ref<Map<number, Sku>>(new Map())

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

/** 列表里顺带显示补货上限：下限触发预警、上限决定要不要出补货建议，两个数得一起看。 */
function maxLevelOf(skuId: number): number {
  return toLevel(skusById.value.get(skuId)?.maxLevel)
}

// ─────────────────────────── 就地调阈值（V4-2） ───────────────────────────

const levelDialog = ref(false)
const levelSaving = ref(false)
const levelError = ref('')
const levelFormRef = ref<FormInstance>()
const editingSku = ref<Sku | null>(null)
const levelForm = reactive({ minLevel: 0, maxLevel: 0 })

function toLevel(value: unknown): number {
  const n = Number(value ?? 0)
  return Number.isFinite(n) && n > 0 ? Math.trunc(n) : 0
}

/** 与后端 MDM_SKU_LEVEL_INVALID / 库里 ck_sku_level_order 同口径：0 = 未设上限是合法的。 */
function validateMaxLevel(
  _rule: unknown,
  value: unknown,
  callback: (error?: string | Error) => void,
) {
  const n = Number(value ?? 0)
  if (!Number.isFinite(n) || n < 0) {
    callback(new Error('补货上限必须是非负整数（件）'))
    return
  }
  if (n !== 0 && n < toLevel(levelForm.minLevel)) {
    callback(new Error('补货上限要不小于安全库存下限；填 0 表示只预警、不生成补货建议'))
    return
  }
  callback()
}

function validateMinLevel(
  _rule: unknown,
  value: unknown,
  callback: (error?: string | Error) => void,
) {
  const n = Number(value ?? 0)
  if (!Number.isFinite(n) || n < 0) {
    callback(new Error('安全库存下限必须是非负整数（件）'))
    return
  }
  callback()
}

const levelRules: FormRules = {
  minLevel: [{ validator: validateMinLevel, trigger: 'blur' }],
  maxLevel: [{ validator: validateMaxLevel, trigger: 'blur' }],
}

function openLevelEditor(row: InventoryAlertRow) {
  const sku = skusById.value.get(row.skuId)
  if (!sku) {
    ElMessage.warning('没找到该 SKU 的主数据行（可能已翻页或停用），请到 主数据-商品 里调整')
    return
  }
  editingSku.value = sku
  levelError.value = ''
  Object.assign(levelForm, { minLevel: toLevel(sku.minLevel), maxLevel: toLevel(sku.maxLevel) })
  levelDialog.value = true
}

async function saveLevels() {
  const sku = editingSku.value
  if (!sku || !levelFormRef.value) return
  const valid = await levelFormRef.value.validate().catch(() => false)
  if (!valid) return

  levelSaving.value = true
  levelError.value = ''
  try {
    await mdmApi.updateSku(sku.id, {
      // ★ PUT 对 barcode / enabled 是全量覆盖语义（barcode 省略即清空、enabled 省略即 true），
      //   只调阈值也必须把原值带上，否则会顺手清条码、把停用的 SKU 重新启用。
      barcode: sku.barcode ?? undefined,
      retailPriceAmount: sku.retailPriceAmount,
      enabled: sku.enabled,
      minLevel: toLevel(levelForm.minLevel),
      maxLevel: toLevel(levelForm.maxLevel),
    })
    ElMessage.success(`已更新 ${skuLabel(sku.id)} 的阈值`)
    levelDialog.value = false
    await load()
  } catch (cause) {
    levelError.value = errorMessage(cause)
  } finally {
    levelSaving.value = false
  }
}

onMounted(async () => {
  void load()
  try {
    const [whs, skuPage] = await Promise.all([
      mdmApi.listWarehouses(false),
      mdmApi.listSkus({ page: 1, pageSize: 500 }),
    ])
    warehouses.value = whs
    skusById.value = new Map(skuPage.items.map((s) => [s.id, s]))
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
      <b>下限为 0 的 SKU 不进预警</b> —— 这个列表空着，通常不是"库存都健康"，而是没人设过阈值；
      安全库存页每行的「调阈值」可以直接改（同一组字段也在 主数据-商品 的 SKU 编辑里）。
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
      empty-hint="安全库存 Tab 空着时先确认阈值：min_level = 0 的 SKU 不参与计算，没设过阈值就永远不会出现在这里。"
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
      <el-table-column label="阈值" width="130">
        <template #default="{ row }">
          <template v-if="tab === 'SAFETY'">
            下限 {{ row.threshold }} 件
            <div class="threshold-sub">上限 {{ maxLevelOf(row.skuId) || '未设' }}</div>
          </template>
          <template v-else-if="tab === 'SLOW_MOVING'">{{ row.threshold }} 天</template>
          <template v-else>{{ row.threshold }} 天内</template>
        </template>
      </el-table-column>
      <el-table-column label="效期" width="110">
        <template #default="{ row }">{{ row.expiryDate ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="最后动销" width="150">
        <template #default="{ row }">{{ row.lastMovementAt ?? '—' }}</template>
      </el-table-column>
      <el-table-column
        v-if="canWrite"
        label="操作"
        width="90"
        fixed="right"
      >
        <template #default="{ row }">
          <el-button v-if="tab === 'SAFETY'" link type="primary" @click="openLevelEditor(row)">
            调阈值
          </el-button>
          <span v-else class="threshold-sub">—</span>
        </template>
      </el-table-column>
    </PageTable>

    <!-- 就地调阈值：写回 PUT /skus/{id} 的 minLevel/maxLevel（V4-2） -->
    <el-dialog v-model="levelDialog" title="调整安全库存阈值" width="520px" :close-on-click-modal="false">
      <el-form ref="levelFormRef" :model="levelForm" :rules="levelRules" label-width="110px">
        <el-form-item label="SKU">
          <span>{{ editingSku ? skuLabel(editingSku.id) : '' }}</span>
        </el-form-item>
        <el-form-item label="安全库存下限" prop="minLevel">
          <el-input-number v-model="levelForm.minLevel" :min="0" :step="10" :precision="0" />
          <span class="threshold-sub">件 · 可用量低于它才预警；0 = 不预警</span>
        </el-form-item>
        <el-form-item label="补货上限" prop="maxLevel">
          <el-input-number v-model="levelForm.maxLevel" :min="0" :step="10" :precision="0" />
          <span class="threshold-sub">件 · 补货建议 = 上限 − 可用量；0 = 只预警不建议</span>
        </el-form-item>
      </el-form>
      <el-alert v-if="levelError" type="error" show-icon :closable="false" :title="levelError" />
      <template #footer>
        <el-button @click="levelDialog = false">取消</el-button>
        <el-button type="primary" :loading="levelSaving" @click="saveLevels">保存</el-button>
      </template>
    </el-dialog>
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
.threshold-sub {
  color: #909399;
  font-size: 12px;
  margin-left: 8px;
}
</style>
