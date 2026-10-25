<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageTable from '@/components/PageTable.vue'
import { fenToYuan, mdmApi, type Customer, type Sku } from '@/api/client'
import {
  pricePlanApi,
  type PricePlan,
  type PricePlanCreate,
  type PriceResolution,
} from '@/api/prmV33'

/**
 * 价格方案（FR-V3-20）。
 *
 * ★ 取价优先级（后端 PricePlanApplicationService 同一口径）：
 *   客户协议价 AGREEMENT > 客户专属价 CUSTOMER > 客户等级价 CUSTOMER_LEVEL
 *   > 商品标准售价 skus.retail_price_amount
 * 金额输入按「元」，提交 ×100 转「分」；折扣按「折」（8.5 折 → 8500 万分比）。
 */

const rows = ref<PricePlan[]>([])
const loading = ref(false)
const error = ref('')

const skus = ref<Sku[]>([])
const customers = ref<Customer[]>([])

const editOpen = ref(false)
const editId = ref<number | null>(null)
const form = ref(blankForm())

const toolOpen = ref(false)
const tool = ref<{ skuId: number | null; customerId: number | undefined; qty: number }>({
  skuId: null,
  customerId: undefined,
  qty: 1,
})
const resolution = ref<PriceResolution | null>(null)
const toolLoading = ref(false)

function blankForm() {
  return {
    code: '',
    name: '',
    planType: 'CUSTOMER_LEVEL' as PricePlan['planType'],
    customerId: null as number | null,
    customerLevel: '',
    priority: 0,
    startDate: null as string | null,
    endDate: null as string | null,
    enabled: true,
    remark: '',
    items: [{ skuId: null as number | null, priceYuan: 0, discountZhe: 10, minQty: 0 }],
  }
}

function errorMessage(cause: unknown): string {
  const e = cause as { response?: { data?: { message?: string } }; message?: string }
  return e?.response?.data?.message || e?.message || '请求失败'
}

const typeLabel: Record<PricePlan['planType'], string> = {
  AGREEMENT: '协议价',
  CUSTOMER: '客户专属',
  CUSTOMER_LEVEL: '等级价',
}

const sourceLabel: Record<PriceResolution['source'], string> = {
  AGREEMENT_PRICE: '客户协议价',
  CUSTOMER_PRICE: '客户专属价',
  CUSTOMER_LEVEL_PRICE: '客户等级价',
  STANDARD_PRICE: '商品标准售价',
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    rows.value = (await pricePlanApi.list()).items
  } catch (cause) {
    rows.value = []
    error.value = errorMessage(cause)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editId.value = null
  form.value = blankForm()
  editOpen.value = true
}

function openEdit(row: PricePlan) {
  editId.value = row.id
  form.value = {
    code: row.code,
    name: row.name,
    planType: row.planType,
    customerId: row.customerId,
    customerLevel: row.customerLevel ?? '',
    priority: row.priority,
    startDate: row.startDate,
    endDate: row.endDate,
    enabled: row.enabled,
    remark: row.remark ?? '',
    items: row.items.map((i) => ({
      skuId: i.skuId,
      priceYuan: i.priceAmount / 100,
      discountZhe: i.discountBp / 1000,
      minQty: i.minQty,
    })),
  }
  editOpen.value = true
}

async function save() {
  const items = form.value.items
    .filter((i) => i.skuId != null)
    .map((i) => ({
      skuId: i.skuId as number,
      priceAmount: Math.round(i.priceYuan * 100),
      discountBp: Math.round(i.discountZhe * 1000),
      minQty: i.minQty,
    }))
  const body: PricePlanCreate = {
    code: form.value.code,
    name: form.value.name,
    planType: form.value.planType,
    customerId: form.value.customerId,
    customerLevel: form.value.customerLevel || null,
    priority: form.value.priority,
    startDate: form.value.startDate,
    endDate: form.value.endDate,
    enabled: form.value.enabled,
    remark: form.value.remark || undefined,
    items,
  }
  try {
    if (editId.value == null) await pricePlanApi.create(body)
    else await pricePlanApi.update(editId.value, body)
    editOpen.value = false
    ElMessage.success('方案已保存')
    void load()
  } catch (cause) {
    ElMessage.error(errorMessage(cause))
  }
}

async function disable(row: PricePlan) {
  try {
    await pricePlanApi.disable(row.id)
    ElMessage.success('已停用')
    void load()
  } catch (cause) {
    ElMessage.error(errorMessage(cause))
  }
}

async function runTool() {
  if (tool.value.skuId == null) {
    ElMessage.warning('选择 SKU')
    return
  }
  toolLoading.value = true
  try {
    resolution.value = await pricePlanApi.price({
      skuId: tool.value.skuId,
      customerId: tool.value.customerId,
      qty: tool.value.qty,
    })
  } catch (cause) {
    resolution.value = null
    ElMessage.error(errorMessage(cause))
  } finally {
    toolLoading.value = false
  }
}

onMounted(async () => {
  void load()
  try {
    const [skuPage, custPage] = await Promise.all([
      mdmApi.listSkus({ page: 1, pageSize: 500 }),
      mdmApi.listCustomers({ page: 1, size: 200 }),
    ])
    skus.value = skuPage.items
    customers.value = custPage.items
  } catch (cause) {
    ElMessage.warning(`基础数据加载失败：${errorMessage(cause)}`)
  }
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2 class="page-title">价格方案</h2>
      <div>
        <el-button @click="load">刷新</el-button>
        <el-button @click="toolOpen = true">取价试算</el-button>
        <el-button type="primary" @click="openCreate">新建方案</el-button>
      </div>
    </div>
    <p class="page-hint">
      取价优先级：协议价 &gt; 客户专属价 &gt; 等级价 &gt; 商品标准售价；同类型内 priority 大者优先。
      本期取价是独立试算端点，<b>未接入报价/出货算价链路</b>（见报告）。
    </p>
    <el-alert v-if="error" type="error" show-icon :closable="false" class="mb-16" :title="`加载失败：${error}`" />

    <PageTable :rows="rows" :loading="loading" :paginated="false" :load-failed="!!error" empty-text="还没有价格方案" empty-hint="新建一个等级价方案，然后用「取价试算」验证优先级。">
      <el-table-column label="编码" prop="code" width="120" />
      <el-table-column label="名称" min-width="140" prop="name" />
      <el-table-column label="类型" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.planType === 'AGREEMENT' ? 'danger' : row.planType === 'CUSTOMER' ? 'warning' : 'primary'">
            {{ typeLabel[row.planType as PricePlan['planType']] }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="绑定" width="140">
        <template #default="{ row }">
          <span v-if="row.customerId">#客户 {{ row.customerId }}</span>
          <span v-else-if="row.customerLevel">等级 {{ row.customerLevel }}</span>
          <span v-else class="text-muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="优先级" prop="priority" width="80" />
      <el-table-column label="有效期" width="190">
        <template #default="{ row }">{{ row.startDate ?? '不限' }} ~ {{ row.endDate ?? '不限' }}</template>
      </el-table-column>
      <el-table-column label="明细" min-width="180">
        <template #default="{ row }">
          <el-tag v-for="i in row.items" :key="i.id ?? i.skuId" size="small" class="mr-4">
            #{{ i.skuId }} ¥{{ fenToYuan(i.effectivePrice ?? i.priceAmount) }}
            <template v-if="i.minQty > 0">（≥{{ i.minQty }}）</template>
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="row.enabled" link type="danger" @click="disable(row)">停用</el-button>
        </template>
      </el-table-column>
    </PageTable>

    <el-dialog v-model="editOpen" :title="editId == null ? '新建价格方案' : '编辑价格方案'" width="720px">
      <el-form label-width="100px">
        <el-form-item label="编码">
          <el-input v-model="form.code" :disabled="editId != null" placeholder="2-32 位唯一" style="width: 220px" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="form.name" style="width: 300px" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.planType" style="width: 200px">
            <el-option label="客户协议价" value="AGREEMENT" />
            <el-option label="客户专属价" value="CUSTOMER" />
            <el-option label="客户等级价" value="CUSTOMER_LEVEL" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.planType !== 'CUSTOMER_LEVEL'" label="客户">
          <el-select v-model="form.customerId" filterable style="width: 300px">
            <el-option v-for="c in customers" :key="c.id" :label="`${c.code} ${c.name}（等级 ${c.level}）`" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-else label="客户等级">
          <el-input v-model="form.customerLevel" placeholder="如 NORMAL / VIP（对齐客户资料的 level）" style="width: 240px" />
        </el-form-item>
        <el-form-item label="优先级">
          <el-input-number v-model="form.priority" :min="0" />
        </el-form-item>
        <el-form-item label="有效期">
          <el-date-picker v-model="form.startDate" type="date" value-format="YYYY-MM-DD" placeholder="开始" />
          <el-date-picker v-model="form.endDate" type="date" value-format="YYYY-MM-DD" placeholder="结束" class="ml-8" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
        <el-form-item label="明细行">
          <div style="width: 100%">
            <div v-for="(item, idx) in form.items" :key="idx" class="item-row">
              <el-select v-model="item.skuId" filterable placeholder="SKU" style="flex: 1">
                <el-option v-for="s in skus" :key="s.id" :label="`#${s.id} ${s.color}/${s.size} 零售¥${fenToYuan(s.retailPriceAmount)}`" :value="s.id" />
              </el-select>
              <el-input-number v-model="item.priceYuan" :min="0" :precision="2" size="small" placeholder="价(元)" />
              <el-input-number v-model="item.discountZhe" :min="0" :max="10" :precision="1" :step="0.5" size="small" placeholder="折" />
              <el-input-number v-model="item.minQty" :min="0" size="small" placeholder="起订" />
              <el-button link type="danger" :disabled="form.items.length === 1" @click="form.items.splice(idx, 1)">删</el-button>
            </div>
            <el-button size="small" @click="form.items.push({ skuId: null, priceYuan: 0, discountZhe: 10, minQty: 0 })">
              添加明细（价=元，折=10 折即不打折，起订=数量阶梯）
            </el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editOpen = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="toolOpen" title="取价试算（协议 > 专属 > 等级 > 标准售价）" width="520px">
      <el-form label-width="80px">
        <el-form-item label="SKU">
          <el-select v-model="tool.skuId" filterable style="width: 100%">
            <el-option v-for="s in skus" :key="s.id" :label="`#${s.id} ${s.color}/${s.size}`" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="客户">
          <el-select v-model="tool.customerId" clearable filterable style="width: 100%">
            <el-option v-for="c in customers" :key="c.id" :label="`${c.code} ${c.name}（${c.level}）`" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="数量">
          <el-input-number v-model="tool.qty" :min="1" />
        </el-form-item>
      </el-form>
      <el-button type="primary" :loading="toolLoading" @click="runTool">试算</el-button>
      <el-alert v-if="resolution" class="mt-8" type="success" show-icon :closable="false">
        <template #title>
          {{ sourceLabel[resolution.source] }}
          <template v-if="resolution.planCode">（方案 {{ resolution.planCode }}）</template>
          ：单价 ¥{{ fenToYuan(resolution.priceAmount) }} × {{ resolution.qty }} 件
        </template>
      </el-alert>
    </el-dialog>
  </div>
</template>

<style scoped>
.item-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
}
.mr-4 {
  margin-right: 4px;
}
.ml-8 {
  margin-left: 8px;
}
.mt-8 {
  margin-top: 8px;
}
</style>
