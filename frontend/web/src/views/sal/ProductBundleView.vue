<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageTable from '@/components/PageTable.vue'
import {
  fenToYuan,
  mdmApi,
  type Sku,
  type Warehouse,
} from '@/api/client'
import { bundleApi, type BundleAvailability, type ProductBundle } from '@/api/salesV23'

/**
 * 组合商品 / 套装（FR-V3-15 / FR-SAL-10）。
 *
 * 母件（套装）不落库存行：可用量 = min(各组件可用 ÷ 配比)（INV-16），
 * 拆解预览就是 availability 接口。价格口径是「分」，页面输入按元、提交 ×100。
 */

const rows = ref<ProductBundle[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const loading = ref(false)
const error = ref('')
const keyword = ref('')

const skus = ref<Sku[]>([])
const editOpen = ref(false)
const editId = ref<number | null>(null)
const form = ref<{ code: string; name: string; priceYuan: number; enabled: boolean; items: { componentSkuId: number | null; ratioQty: number }[] }>(
  blankForm(),
)

const availOpen = ref(false)
const availBundle = ref<ProductBundle | null>(null)
const warehouses = ref<Warehouse[]>([])
const availWarehouseId = ref<number | undefined>(undefined)
const availability = ref<BundleAvailability | null>(null)
const availLoading = ref(false)

function blankForm() {
  return {
    code: '',
    name: '',
    priceYuan: 0,
    enabled: true,
    items: [{ componentSkuId: null, ratioQty: 1 }],
  }
}

function errorMessage(cause: unknown): string {
  const e = cause as { response?: { data?: { message?: string } }; message?: string }
  return e?.response?.data?.message || e?.message || '请求失败'
}

function skuLabel(id: number): string {
  const s = skus.value.find((x) => x.id === id)
  return s ? `#${id} ${s.color}/${s.size}` : `#${id}`
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await bundleApi.list({ keyword: keyword.value || undefined, page: page.value, size: pageSize.value })
    rows.value = data.items
    total.value = data.total
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

function openEdit(row: ProductBundle) {
  editId.value = row.id
  form.value = {
    code: row.code,
    name: row.name,
    priceYuan: row.priceAmount / 100,
    enabled: row.enabled,
    items: row.items.map((i) => ({ componentSkuId: i.componentSkuId, ratioQty: i.ratioQty })),
  }
  editOpen.value = true
}

async function save() {
  const items = form.value.items
    .filter((i) => i.componentSkuId != null && i.ratioQty > 0)
    .map((i) => ({ componentSkuId: i.componentSkuId as number, ratioQty: i.ratioQty }))
  if (items.length === 0) {
    ElMessage.warning('至少一个组件与配比')
    return
  }
  const priceAmount = Math.round(form.value.priceYuan * 100)
  try {
    if (editId.value == null) {
      await bundleApi.create({ code: form.value.code, name: form.value.name, priceAmount, enabled: form.value.enabled, items })
    } else {
      await bundleApi.update(editId.value, { name: form.value.name, priceAmount, enabled: form.value.enabled, items })
    }
    editOpen.value = false
    ElMessage.success('套装已保存')
    void load()
  } catch (cause) {
    ElMessage.error(errorMessage(cause))
  }
}

async function disable(row: ProductBundle) {
  await ElMessageBox.confirm(`停用套装 ${row.code}？停用后报价/出货不能再选它。`, '停用确认', { type: 'warning' })
  try {
    await bundleApi.disable(row.id)
    ElMessage.success('已停用')
    void load()
  } catch (cause) {
    ElMessage.error(errorMessage(cause))
  }
}

async function openAvailability(row: ProductBundle) {
  availBundle.value = row
  availability.value = null
  availOpen.value = true
  await loadAvailability()
}

async function loadAvailability() {
  if (!availBundle.value) return
  availLoading.value = true
  try {
    availability.value = await bundleApi.availability(availBundle.value.id, availWarehouseId.value)
  } catch (cause) {
    ElMessage.error(errorMessage(cause))
  } finally {
    availLoading.value = false
  }
}

onMounted(async () => {
  void load()
  try {
    const [skuPage, whs] = await Promise.all([
      mdmApi.listSkus({ page: 1, pageSize: 500 }),
      mdmApi.listWarehouses(true),
    ])
    skus.value = skuPage.items
    warehouses.value = whs
  } catch (cause) {
    ElMessage.warning(`基础数据加载失败：${errorMessage(cause)}`)
  }
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2 class="page-title">组合商品（套装）</h2>
      <div>
        <el-input v-model="keyword" placeholder="编码/名称" clearable style="width: 180px" @keyup.enter="load" />
        <el-button @click="load">刷新</el-button>
        <el-button type="primary" @click="openCreate">新建套装</el-button>
      </div>
    </div>
    <p class="page-hint">
      套装本身不持有库存：可用量 = min(组件可用 ÷ 配比)。「拆解预览」按仓（或跨仓合计）展示
      每个组件能拆出几套。
    </p>

    <el-alert v-if="error" type="error" show-icon :closable="false" class="mb-16" :title="`加载失败：${error}`" />

    <PageTable
      :rows="rows"
      :loading="loading"
      :total="total"
      v-model:page="page"
      v-model:page-size="pageSize"
      :load-failed="!!error"
      empty-text="还没有套装"
      empty-hint="新建一个：母件编码 + 名称 + 售价（元），组件行选 SKU 填配比。"
      @change="load"
    >
      <el-table-column label="编码" prop="code" width="130" />
      <el-table-column label="名称" min-width="160" prop="name" />
      <el-table-column label="售价" width="110">
        <template #default="{ row }">¥ {{ fenToYuan(row.priceAmount) }}</template>
      </el-table-column>
      <el-table-column label="组件" min-width="220">
        <template #default="{ row }">
          <el-tag v-for="i in row.items" :key="i.id" size="small" class="mr-4">
            {{ skuLabel(i.componentSkuId) }} × {{ i.ratioQty }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
            {{ row.enabled ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="210" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="warning" @click="openAvailability(row)">拆解预览</el-button>
          <el-button v-if="row.enabled" link type="danger" @click="disable(row)">停用</el-button>
        </template>
      </el-table-column>
    </PageTable>

    <el-dialog v-model="editOpen" :title="editId == null ? '新建套装' : '编辑套装'" width="620px">
      <el-form label-width="90px">
        <el-form-item label="编码">
          <el-input v-model="form.code" :disabled="editId != null" placeholder="2-32 位，唯一" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="售价（元）">
          <el-input-number v-model="form.priceYuan" :min="0" :precision="2" :step="10" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
        <el-form-item label="组件配比">
          <div style="width: 100%">
            <div v-for="(item, idx) in form.items" :key="idx" class="item-row">
              <el-select v-model="item.componentSkuId" filterable placeholder="组件 SKU" style="flex: 1">
                <el-option
                  v-for="s in skus"
                  :key="s.id"
                  :label="`#${s.id} ${s.color}/${s.size} · 零售 ¥${fenToYuan(s.retailPriceAmount)}`"
                  :value="s.id"
                />
              </el-select>
              <el-input-number v-model="item.ratioQty" :min="1" size="small" />
              <el-button
                link
                type="danger"
                :disabled="form.items.length === 1"
                @click="form.items.splice(idx, 1)"
              >
                删除
              </el-button>
            </div>
            <el-button size="small" @click="form.items.push({ componentSkuId: null, ratioQty: 1 })">
              添加组件
            </el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editOpen = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="availOpen" :title="`拆解预览 · ${availBundle?.name ?? ''}`" width="640px">
      <div v-loading="availLoading">
        <el-select v-model="availWarehouseId" clearable placeholder="仓库（不选=跨仓合计）" style="width: 240px" @change="loadAvailability">
          <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
        </el-select>
        <el-alert
          v-if="availability"
          class="mt-8"
          type="success"
          :closable="false"
          show-icon
          :title="`当前可组装 ${availability.availableQty} 套`"
        />
        <el-table v-if="availability" :data="availability.components" size="small" class="mt-8">
          <el-table-column label="组件" min-width="160">
            <template #default="{ row }">{{ skuLabel(row.componentSkuId) }}</template>
          </el-table-column>
          <el-table-column label="配比" prop="ratioQty" width="80" />
          <el-table-column label="组件可用" prop="availableQty" width="100" />
          <el-table-column label="可成套数" prop="maxBundles" width="100" />
        </el-table>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.item-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.mr-4 {
  margin-right: 4px;
}
.mt-8 {
  margin-top: 8px;
}
</style>
