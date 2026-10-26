<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  mdmApi,
  fenToYuan,
  yuanToFen,
  type Product,
  type ProductAttribute,
  type ProductCreate,
  type ProductUpdate,
  type Sku,
} from '@/api/client'
import PageTable from '@/components/PageTable.vue'
import SkuMatrixDialog from '@/components/SkuMatrixDialog.vue'
import { useSessionStore } from '@/stores/session'

/**
 * 商品与 SKU（FR-MDM-01）。
 *
 * 页面分两层：
 *   1. 商品列表：编码/名称搜索、分页、新建/编辑/停用；
 *   2. 单个商品的 SKU 抽屉：按颜色/尺码筛选、分页、编辑，以及矩阵批量生成。
 *
 * ★ 为什么 SKU 不直接铺在同一张表里：
 *   一个商品动辄几十个 SKU，混排会让"商品"这个层级消失，
 *   用户分不清自己看到的是款还是色码。抽屉保留了从款到 SKU 的层级关系，
 *   也让矩阵生成有明确的归属对象。
 *
 * ★ 前端只做展示与触发：编码唯一、矩阵去重、库存等规则全部以后端为准。
 *   这里唯一的"计算"是金额元/分转换（ADR-0005）。
 */

const session = useSessionStore()
const canWrite = computed(() => session.hasPermission('mdm:write'))

// ─────────────────────────── 商品列表 ───────────────────────────

const products = ref<Product[]>([])
const productTotal = ref(0)
const productPage = ref(1)
const productPageSize = ref(20)
const keyword = ref('')
const productLoading = ref(false)
const productError = ref('')
const productFiltered = computed(() => keyword.value.trim() !== '')

/** 拦截器已经弹过统一 toast；这里把消息留在页面上，便于刷新后回看与重试。 */
function errorMessage(error: unknown): string {
  const e = error as { response?: { data?: { message?: string } }; message?: string }
  return e?.response?.data?.message || e?.message || '请求失败'
}

async function loadProducts() {
  productLoading.value = true
  productError.value = ''
  try {
    const data = await mdmApi.listProducts({
      keyword: keyword.value.trim() || undefined,
      page: productPage.value,
      size: productPageSize.value,
    })
    products.value = data.items
    productTotal.value = data.total
  } catch (error) {
    products.value = []
    productTotal.value = 0
    productError.value = errorMessage(error)
  } finally {
    productLoading.value = false
  }
}

function onProductPageChange(page: number, pageSize: number) {
  productPage.value = page
  productPageSize.value = pageSize
  loadProducts()
}

function searchProducts() {
  productPage.value = 1
  loadProducts()
}

function resetProductFilters() {
  keyword.value = ''
  productPage.value = 1
  loadProducts()
}

onMounted(loadProducts)

// ─────────────────────────── 商品新建 / 编辑 ───────────────────────────

const productDialog = ref(false)
const editingProduct = ref<Product | null>(null)
const productFormRef = ref<FormInstance>()
const savingProduct = ref(false)
const productFormError = ref('')
const productForm = reactive({
  code: '',
  name: '',
  category: '',
  season: '',
  enabled: true,
})

const productRules: FormRules = {
  code: [
    { required: true, message: '请输入商品编码', trigger: 'blur' },
    { min: 2, max: 32, message: '编码长度 2~32 个字符', trigger: 'blur' },
  ],
  name: [{ required: true, message: '请输入商品名称', trigger: 'blur' }],
  category: [{ max: 64, message: '最多 64 个字符', trigger: 'blur' }],
  season: [{ max: 32, message: '最多 32 个字符', trigger: 'blur' }],
}

function clearProductValidate() {
  productFormRef.value?.clearValidate()
}

function openCreateProduct() {
  editingProduct.value = null
  productFormError.value = ''
  Object.assign(productForm, {
    code: '',
    name: '',
    category: '',
    season: '',
    enabled: true,
  })
  productDialog.value = true
}

function openEditProduct(row: Product) {
  editingProduct.value = row
  productFormError.value = ''
  Object.assign(productForm, {
    code: row.code,
    name: row.name,
    category: row.category ?? '',
    season: row.season ?? '',
    enabled: row.enabled,
  })
  productDialog.value = true
}

async function saveProduct() {
  if (!productFormRef.value) return
  const valid = await productFormRef.value.validate().catch(() => false)
  if (!valid) return

  savingProduct.value = true
  productFormError.value = ''
  try {
    const common: ProductUpdate = {
      name: productForm.name.trim(),
      category: productForm.category.trim() || undefined,
      season: productForm.season.trim() || undefined,
      enabled: productForm.enabled,
    }
    if (editingProduct.value) {
      await mdmApi.updateProduct(editingProduct.value.id, common)
      ElMessage.success('商品已更新')
    } else {
      const createBody: ProductCreate = { ...common, code: productForm.code.trim() }
      await mdmApi.createProduct(createBody)
      ElMessage.success('商品已创建')
    }
    productDialog.value = false
    await loadProducts()
  } catch (error) {
    productFormError.value = errorMessage(error)
  } finally {
    savingProduct.value = false
  }
}

async function disableProduct(row: Product) {
  try {
    await ElMessageBox.confirm(
      `确定停用商品「${row.name}」吗？停用后该商品不会出现在新单据里，历史单据与库存不受影响。`,
      '停用商品',
      { type: 'warning', confirmButtonText: '停用', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await mdmApi.disableProduct(row.id)
    ElMessage.success('商品已停用')
    await loadProducts()
  } catch {
    // 拦截器已提示；catch 只为避免未处理的 Promise 拒绝
  }
}

async function enableProduct(row: Product) {
  try {
    await mdmApi.updateProduct(row.id, {
      name: row.name,
      category: row.category ?? undefined,
      season: row.season ?? undefined,
      enabled: true,
    })
    ElMessage.success('商品已启用')
    await loadProducts()
  } catch {
    // 同上
  }
}

// ─────────────────────────── SKU 抽屉 ───────────────────────────

const skuDrawer = ref(false)
const currentProduct = ref<Product | null>(null)
const skus = ref<Sku[]>([])
const skuTotal = ref(0)
const skuPage = ref(1)
const skuPageSize = ref(20)
const skuColor = ref('')
const skuSize = ref('')
const skuLoading = ref(false)
const skuError = ref('')
const skuFiltered = computed(() => skuColor.value.trim() !== '' || skuSize.value.trim() !== '')
const matrixDialog = ref(false)

async function openSkus(row: Product) {
  currentProduct.value = row
  skuDrawer.value = true
  skuColor.value = ''
  skuSize.value = ''
  skuPage.value = 1
  await loadSkus()
}

async function loadSkus() {
  if (!currentProduct.value) {
    skus.value = []
    skuTotal.value = 0
    return
  }
  skuLoading.value = true
  skuError.value = ''
  try {
    const data = await mdmApi.listSkus({
      productId: currentProduct.value.id,
      color: skuColor.value.trim() || undefined,
      // 尺码筛选叫 skuSize；size 是分页条数（G3：两者曾同名，分页被静默忽略）
      skuSize: skuSize.value.trim() || undefined,
      page: skuPage.value,
      pageSize: skuPageSize.value,
    })
    skus.value = data.items
    skuTotal.value = data.total
  } catch (error) {
    skus.value = []
    skuTotal.value = 0
    skuError.value = errorMessage(error)
  } finally {
    skuLoading.value = false
  }
}

function onSkuPageChange(page: number, pageSize: number) {
  skuPage.value = page
  skuPageSize.value = pageSize
  loadSkus()
}

function searchSkus() {
  skuPage.value = 1
  loadSkus()
}

function resetSkuFilters() {
  skuColor.value = ''
  skuSize.value = ''
  skuPage.value = 1
  loadSkus()
}

function openMatrix() {
  matrixDialog.value = true
}

async function onMatrixGenerated() {
  // 生成后刷新列表：用户马上能看到新增的 SKU，而不是对着一张旧表
  await loadSkus()
}

// ─────────────────────────── SKU 编辑 ───────────────────────────

const skuDialog = ref(false)
const editingSku = ref<Sku | null>(null)
const skuFormRef = ref<FormInstance>()
const savingSku = ref(false)
const skuFormError = ref('')
const skuForm = reactive({
  barcode: '',
  priceYuan: '',
  enabled: true,
  /** 安全库存阈值，单位是件（不是分）；0 = 未设 */
  minLevel: 0,
  maxLevel: 0,
})

function validatePrice(
  _rule: unknown,
  value: unknown,
  callback: (error?: string | Error) => void,
) {
  const raw = String(value ?? '').trim()
  const n = Number(raw)
  if (raw === '' || Number.isNaN(n) || n < 0) {
    callback(new Error('请输入非负金额'))
    return
  }
  callback()
}

const skuRules: FormRules = {
  barcode: [{ max: 64, message: '条码最多 64 个字符', trigger: 'blur' }],
  priceYuan: [{ validator: validatePrice, trigger: 'blur' }],
  minLevel: [{ validator: validateMinLevel, trigger: 'blur' }],
  maxLevel: [{ validator: validateMaxLevel, trigger: 'blur' }],
}

/** 阈值输入归一：清空/非法一律按 0（未设）处理，与库里 default 0 同口径。 */
function toLevel(value: unknown): number {
  const n = Number(value ?? 0)
  return Number.isFinite(n) && n > 0 ? Math.trunc(n) : 0
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

/**
 * 上限校验与后端 {@code MDM_SKU_LEVEL_INVALID} / 库里 {@code ck_sku_level_order} 同一口径：
 * {@code 0 = 未设上限}（只预警、不建议补货），是合法值，不能按"上限低于下限"拦掉。
 */
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
  if (n !== 0 && n < toLevel(skuForm.minLevel)) {
    callback(new Error('补货上限要不小于安全库存下限；留空(0)表示只预警、不生成补货建议'))
    return
  }
  callback()
}

function openEditSku(row: Sku) {
  editingSku.value = row
  skuFormError.value = ''
  Object.assign(skuForm, {
    barcode: row.barcode ?? '',
    priceYuan: fenToYuan(row.retailPriceAmount),
    enabled: row.enabled,
    minLevel: toLevel(row.minLevel),
    maxLevel: toLevel(row.maxLevel),
  })
  skuDialog.value = true
}

async function saveSku() {
  if (!skuFormRef.value || !editingSku.value) return
  const valid = await skuFormRef.value.validate().catch(() => false)
  if (!valid) return

  savingSku.value = true
  skuFormError.value = ''
  try {
    await mdmApi.updateSku(editingSku.value.id, {
      barcode: skuForm.barcode.trim() || undefined,
      retailPriceAmount: yuanToFen(skuForm.priceYuan),
      enabled: skuForm.enabled,
      minLevel: toLevel(skuForm.minLevel),
      maxLevel: toLevel(skuForm.maxLevel),
    })
    ElMessage.success('SKU 已更新')
    skuDialog.value = false
    await loadSkus()
  } catch (error) {
    skuFormError.value = errorMessage(error)
  } finally {
    savingSku.value = false
  }
}

// ─────────────────────────── 商品规格属性（V18 · B2） ───────────────────────────

interface AttributeRow {
  key: string
  value: string
}

const attributeDialog = ref(false)
const attributeProduct = ref<Product | null>(null)
const attributeLoading = ref(false)
const attributeError = ref('')
const attributeSaving = ref(false)
const attributeRows = ref<AttributeRow[]>([])

async function openAttributes(row: Product) {
  attributeProduct.value = row
  attributeDialog.value = true
  await loadAttributes()
}

async function loadAttributes() {
  if (!attributeProduct.value) return
  attributeLoading.value = true
  attributeError.value = ''
  try {
    const data = await mdmApi.listProductAttributes(attributeProduct.value.id)
    attributeRows.value = data.items.map((item) => ({ key: item.key, value: item.value }))
  } catch (error) {
    attributeRows.value = []
    attributeError.value = errorMessage(error)
  } finally {
    attributeLoading.value = false
  }
}

function addAttributeRow() {
  if (attributeRows.value.length >= 50) {
    ElMessage.warning('规格属性最多 50 条')
    return
  }
  attributeRows.value.push({ key: '', value: '' })
}

function removeAttributeRow(index: number) {
  attributeRows.value.splice(index, 1)
}

async function saveAttributes() {
  if (!attributeProduct.value) return
  const cleaned: ProductAttribute[] = attributeRows.value.map((row) => ({
    key: row.key.trim(),
    value: row.value.trim(),
  }))
  if (cleaned.some((row) => !row.key || !row.value)) {
    ElMessage.warning('属性键和属性值都不能为空')
    return
  }
  const keys = new Set<string>()
  for (const row of cleaned) {
    if (keys.has(row.key)) {
      ElMessage.warning(`属性键重复：${row.key}`)
      return
    }
    keys.add(row.key)
  }

  attributeSaving.value = true
  attributeError.value = ''
  try {
    await mdmApi.replaceProductAttributes(attributeProduct.value.id, cleaned)
    ElMessage.success('规格属性已保存（全量替换）')
    await loadAttributes()
  } catch (error) {
    attributeError.value = errorMessage(error)
  } finally {
    attributeSaving.value = false
  }
}
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2 class="page-title">商品与 SKU</h2>
      <el-button v-if="canWrite" type="primary" @click="openCreateProduct">新建商品</el-button>
    </div>
    <p class="page-hint">
      先建商品，再按颜色 × 尺码生成 SKU；停用不删除，历史单据与库存不受影响。
    </p>

    <el-alert
      v-if="productError"
      type="error"
      show-icon
      :closable="false"
      class="mb-16"
      :title="`商品查询失败：${productError}`"
    >
      <el-button size="small" @click="loadProducts">重试</el-button>
    </el-alert>

    <PageTable
      :rows="products"
      :loading="productLoading"
      :total="productTotal"
      :page="productPage"
      :page-size="productPageSize"
      :filtered="productFiltered"
      :load-failed="!!productError"
      :can-create="canWrite"
      create-text="新建第一个商品"
      empty-text="还没有商品"
      empty-hint="新建商品后，就可以按颜色和尺码批量生成 SKU。"
      @update:page="productPage = $event"
      @update:page-size="productPageSize = $event"
      @change="onProductPageChange"
      @create="openCreateProduct"
    >
      <template #filters>
        <el-input
          v-model="keyword"
          placeholder="按商品编码或名称搜索"
          clearable
          class="filter-input"
          @keyup.enter="searchProducts"
          @clear="searchProducts"
        />
        <el-button type="primary" @click="searchProducts">查询</el-button>
        <el-button @click="resetProductFilters">重置</el-button>
      </template>

      <el-table-column prop="code" label="商品编码" width="150" />
      <el-table-column prop="name" label="商品名称" min-width="180" />
      <el-table-column label="品类" width="110">
        <template #default="{ row }">{{ row.category || '-' }}</template>
      </el-table-column>
      <el-table-column label="季节" width="100">
        <template #default="{ row }">{{ row.season || '-' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
            {{ row.enabled ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="320" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openSkus(row)">SKU 管理</el-button>
          <el-button link type="primary" @click="openAttributes(row)">规格属性</el-button>
          <el-button v-if="canWrite" link type="primary" @click="openEditProduct(row)">
            编辑
          </el-button>
          <el-button
            v-if="canWrite && row.enabled"
            link
            type="danger"
            @click="disableProduct(row)"
          >
            停用
          </el-button>
          <el-button
            v-if="canWrite && !row.enabled"
            link
            type="success"
            @click="enableProduct(row)"
          >
            启用
          </el-button>
        </template>
      </el-table-column>
    </PageTable>

    <!-- 商品新建 / 编辑 -->
    <el-dialog
      v-model="productDialog"
      :title="editingProduct ? '编辑商品' : '新建商品'"
      width="520px"
      :close-on-click-modal="false"
      @open="clearProductValidate"
    >
      <el-form
        ref="productFormRef"
        :model="productForm"
        :rules="productRules"
        label-width="100px"
      >
        <el-form-item label="商品编码" prop="code">
          <el-input
            v-model="productForm.code"
            :disabled="!!editingProduct"
            maxlength="32"
            show-word-limit
            placeholder="如 P-TS-001"
          />
          <div v-if="editingProduct" class="field-hint">编码创建后不可修改</div>
        </el-form-item>
        <el-form-item label="商品名称" prop="name">
          <el-input v-model="productForm.name" maxlength="128" placeholder="如 圆领T恤" />
        </el-form-item>
        <el-form-item label="品类" prop="category">
          <el-input v-model="productForm.category" maxlength="64" placeholder="如 上装" />
        </el-form-item>
        <el-form-item label="季节" prop="season">
          <el-input v-model="productForm.season" maxlength="32" placeholder="如 2026夏" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="productForm.enabled" />
        </el-form-item>
      </el-form>
      <el-alert v-if="productFormError" type="error" show-icon :closable="false" :title="productFormError" />
      <template #footer>
        <el-button @click="productDialog = false">取消</el-button>
        <el-button type="primary" :loading="savingProduct" @click="saveProduct">
          保存
        </el-button>
      </template>
    </el-dialog>

    <!-- 单个商品的 SKU 抽屉 -->
    <el-drawer v-model="skuDrawer" size="72%" :with-header="true">
      <template #header>
        <div class="drawer-header">
          <div>
            <div class="drawer-title">
              {{ currentProduct?.code }} · {{ currentProduct?.name }}
            </div>
            <div class="text-muted">共 {{ skuTotal }} 个 SKU</div>
          </div>
          <el-button v-if="canWrite" type="primary" @click="openMatrix">
            生成 SKU
          </el-button>
        </div>
      </template>

      <el-alert
        v-if="skuError"
        type="error"
        show-icon
        :closable="false"
        class="mb-16"
        :title="`SKU 查询失败：${skuError}`"
      >
        <el-button size="small" @click="loadSkus">重试</el-button>
      </el-alert>

      <PageTable
        :rows="skus"
        :loading="skuLoading"
        :total="skuTotal"
        :page="skuPage"
        :page-size="skuPageSize"
        :filtered="skuFiltered"
        :load-failed="!!skuError"
        :can-create="canWrite"
        create-text="生成 SKU"
        empty-text="该商品还没有 SKU"
        empty-hint="用颜色 × 尺码矩阵一次生成全部组合，不必逐个录入。"
        @update:page="skuPage = $event"
        @update:page-size="skuPageSize = $event"
        @change="onSkuPageChange"
        @create="openMatrix"
      >
        <template #filters>
          <el-input
            v-model="skuColor"
            placeholder="按颜色筛选"
            clearable
            class="filter-input-sm"
            @keyup.enter="searchSkus"
            @clear="searchSkus"
          />
          <el-input
            v-model="skuSize"
            placeholder="按尺码筛选（S/M/L）"
            clearable
            class="filter-input-sm"
            @keyup.enter="searchSkus"
            @clear="searchSkus"
          />
          <el-button type="primary" @click="searchSkus">查询</el-button>
          <el-button @click="resetSkuFilters">重置</el-button>
        </template>

        <el-table-column prop="color" label="颜色" width="110" />
        <el-table-column prop="size" label="尺码" width="80" />
        <el-table-column label="条码" min-width="160">
          <template #default="{ row }">{{ row.barcode || '-' }}</template>
        </el-table-column>
        <el-table-column label="零售价" width="120" align="right">
          <template #default="{ row }">
            <span class="amount">¥ {{ fenToYuan(row.retailPriceAmount) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="安全库存" width="120" align="right">
          <template #default="{ row }">
            <span v-if="toLevel(row.minLevel) > 0">
              {{ toLevel(row.minLevel) }}
              <span class="level-unit">件</span>
            </span>
            <span v-else class="level-unset">未设</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
              {{ row.enabled ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="canWrite" label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEditSku(row)">编辑</el-button>
          </template>
        </el-table-column>
      </PageTable>
    </el-drawer>

    <!-- 矩阵生成 -->
    <SkuMatrixDialog
      v-model="matrixDialog"
      :product="currentProduct"
      @generated="onMatrixGenerated"
    />

    <!-- SKU 编辑 -->
    <el-dialog v-model="skuDialog" title="编辑 SKU" width="560px" :close-on-click-modal="false">
      <el-form ref="skuFormRef" :model="skuForm" :rules="skuRules" label-width="110px">
        <el-form-item label="颜色 / 尺码">
          <span>{{ editingSku?.color }} / {{ editingSku?.size }}</span>
        </el-form-item>
        <el-form-item label="条码" prop="barcode">
          <el-input v-model="skuForm.barcode" maxlength="64" placeholder="可选" />
        </el-form-item>
        <el-form-item label="零售价" prop="priceYuan">
          <el-input v-model="skuForm.priceYuan" placeholder="129.00">
            <template #append>元</template>
          </el-input>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="skuForm.enabled" />
        </el-form-item>
        <el-form-item label="安全库存下限" prop="minLevel">
          <el-input-number v-model="skuForm.minLevel" :min="0" :step="10" :precision="0" />
          <span class="unit-hint">件 · 可用量低于它就会出现在「库存预警」；0 = 不预警</span>
        </el-form-item>
        <el-form-item label="补货上限" prop="maxLevel">
          <el-input-number v-model="skuForm.maxLevel" :min="0" :step="10" :precision="0" />
          <span class="unit-hint">件 · 补货建议按"补到这个数"算；0 = 只预警不建议</span>
        </el-form-item>
      </el-form>
      <el-alert v-if="skuFormError" type="error" show-icon :closable="false" :title="skuFormError" />
      <template #footer>
        <el-button @click="skuDialog = false">取消</el-button>
        <el-button type="primary" :loading="savingSku" @click="saveSku">保存</el-button>
      </template>
    </el-dialog>

    <!-- 商品规格属性（键值；与 SKU 矩阵正交） -->
    <el-dialog
      v-model="attributeDialog"
      :title="attributeProduct ? `${attributeProduct.code} · 规格属性` : '规格属性'"
      width="640px"
      :close-on-click-modal="false"
    >
      <el-alert
        type="info"
        show-icon
        :closable="false"
        class="mb-16"
        title="规格属性是款级键值（面料/版型/执行标准等），不参与 SKU 颜色×尺码唯一键；保存为全量替换。"
      />
      <el-alert
        v-if="attributeError"
        type="error"
        show-icon
        :closable="false"
        class="mb-16"
        :title="`规格属性加载失败：${attributeError}`"
      >
        <el-button size="small" @click="loadAttributes">重试</el-button>
      </el-alert>

      <div v-loading="attributeLoading">
        <el-empty
          v-if="!attributeRows.length"
          description="还没有规格属性，可添加面料、版型、执行标准等"
        />
        <div v-for="(row, index) in attributeRows" :key="index" class="attribute-row">
          <el-input v-model="row.key" maxlength="32" placeholder="如 面料" />
          <el-input v-model="row.value" maxlength="256" placeholder="如 纯棉" />
          <el-button v-if="canWrite" link type="danger" @click="removeAttributeRow(index)">
            删除
          </el-button>
        </div>
      </div>
      <el-button v-if="canWrite" class="add-attribute" @click="addAttributeRow">
        添加属性
      </el-button>

      <template #footer>
        <el-button @click="attributeDialog = false">关闭</el-button>
        <el-button
          v-if="canWrite"
          type="primary"
          :loading="attributeSaving"
          @click="saveAttributes"
        >
          保存
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.filter-input {
  width: 260px;
}

.filter-input-sm {
  width: 160px;
}

.field-hint {
  margin-top: 2px;
  font-size: 12px;
  color: #909399;
}

.drawer-header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
}

.drawer-title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

.attribute-row {
  display: grid;
  grid-template-columns: 1fr 2fr auto;
  gap: 8px;
  margin-bottom: 8px;
  align-items: center;
}

.add-attribute {
  margin-top: 4px;
}

/* 阈值输入右侧的口径提示：阈值是"件"，与零售价的"元"必须一眼分得开 */
.unit-hint {
  margin-left: 10px;
  color: #909399;
  font-size: 12px;
}

.level-unit {
  margin-left: 2px;
  color: #909399;
  font-size: 12px;
}

.level-unset {
  color: #c0c4cc;
}

@media (max-width: 768px) {
  .filter-input,
  .filter-input-sm {
    width: 100%;
  }
}
</style>
