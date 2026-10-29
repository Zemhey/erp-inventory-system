<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { mdmApi, type Product, type Sku } from '@/api/client'
import { useDictionary } from '@/composables/useDictionary'
import { useTheme } from '@/composables/useTheme'
import { hasPermission } from '@/stores/session'
import { errorMessage } from '@/utils/error'
import { formatFen } from '@/utils/money'
import { hideNativeTabBar } from '@/utils/navigation'
import { ensureLoggedIn } from '@/utils/routeGuard'
import { safe } from '@/utils/safe'

/**
 * 商品 / SKU 只读。
 *
 * 移动端用于仓库现场查款号、条码、零售价；不做商品或 SKU 的增改、停用、矩阵生成。
 * 商品搜索走后端 keyword，并按后端分页一页 20 个翻页看；
 * SKU 搜索在后端没有 keyword 参数，这里对已加载的
 * 前 200 条做本地过滤，并把"只覆盖前 200 条"如实提示，不假装是全量搜索 ——
 * 所以 SKU 那一侧的翻页是在这批已加载数据里翻，不是让后端换一页。
 */

const PAGE_SIZE = 20
const SKU_LIMIT = 200

type TabKey = 'product' | 'sku'

const { themeClass } = useTheme()

const tab = ref<TabKey>('product')
const allowed = computed(() => hasPermission('mdm:read'))
const { loadDictionary, productText, warning: dictWarning } = useDictionary()

// ─────────────────────────── 商品（服务端分页 · 一页 20 个） ───────────────────────────

const productKeyword = ref('')
const products = ref<Product[]>([])
const productTotal = ref(0)
const productPage = ref(1)
const productLoaded = ref(false)
const productLoading = ref(false)
const productError = ref('')

let productQueued = false
/** 上一次真正加载成功的页码：请求失败时把翻页条退回到这里，不停在没数据的页上。 */
let productGoodPage = 1

async function loadProducts(): Promise<void> {
  if (!allowed.value) return
  if (productLoading.value) {
    // 关键字在请求飞行中被再次修改：记住"还欠一次"，本次结束后补跑
    productQueued = true
    return
  }

  productLoading.value = true
  productError.value = ''

  const result = await safe(
    mdmApi.listProducts({
      keyword: productKeyword.value.trim() || undefined,
      page: productPage.value,
      size: PAGE_SIZE,
    }),
  )

  if (result.ok) {
    products.value = result.value.items
    productTotal.value = result.value.total
    // 以后端回的值为准：关键字变了以后，请求的那一页可能已经不存在
    productPage.value = result.value.page
    productGoodPage = result.value.page
  } else {
    products.value = []
    productPage.value = productGoodPage
    productError.value = errorMessage(result.error)
  }

  productLoaded.value = true
  productLoading.value = false

  if (productQueued) {
    productQueued = false
    void loadProducts()
  }
}

function goProductPage(next: number): void {
  productPage.value = next
  void loadProducts()
}

/** 换关键字/换标签一律回到第 1 页：停在第 7 页再搜，多半只会看到空列表。 */
function reloadProducts(): void {
  productPage.value = 1
  void loadProducts()
}

// ─────────────────────────── SKU（后端无关键字参数：本地过滤 + 本地翻页） ───────────────────────────

const skuKeyword = ref('')
const skus = ref<Sku[]>([])
const skuTotal = ref(0)
const skuLoaded = ref(false)
const skuLoading = ref(false)
const skuError = ref('')
const skuPage = ref(1)

const skuTruncated = computed(() => skuTotal.value > skus.value.length)

const filteredSkus = computed(() => {
  const query = skuKeyword.value.trim().toLowerCase()
  if (!query) return skus.value
  return skus.value.filter((sku) => {
    const haystack = [
      sku.barcode ?? '',
      sku.color,
      sku.size,
      productText(sku.productId),
      `${sku.color}-${sku.size}`,
    ]
      .join(' ')
      .toLowerCase()
    return haystack.includes(query)
  })
})

/** 过滤结果变少时把页码夹回最后一页，避免翻页条停在空页上。 */
const skuPageCount = computed(() =>
  Math.max(1, Math.ceil(filteredSkus.value.length / PAGE_SIZE)),
)
const skuCurrentPage = computed(() => Math.min(Math.max(1, skuPage.value), skuPageCount.value))

const pagedSkus = computed(() => {
  const start = (skuCurrentPage.value - 1) * PAGE_SIZE
  return filteredSkus.value.slice(start, start + PAGE_SIZE)
})

// 关键字是即时过滤：一变就回第 1 页，否则停在第 3 页搜一个冷门条码只会看到空列表
watch(skuKeyword, () => {
  skuPage.value = 1
})

async function loadSkus(): Promise<void> {
  if (!allowed.value || skuLoading.value) return
  skuLoading.value = true
  skuError.value = ''
  const result = await safe(mdmApi.listSkus({ page: 1, size: SKU_LIMIT }))
  if (result.ok) {
    skus.value = result.value.items
    skuTotal.value = result.value.total
  } else {
    skus.value = []
    skuTotal.value = 0
    skuError.value = errorMessage(result.error)
  }
  skuLoaded.value = true
  skuLoading.value = false
}

function goSkuPage(next: number): void {
  skuPage.value = next
}

/** SKU 这批是一次取回的，翻页只动本地页码；换标签只需把页码归 1。 */
function reloadSkus(): void {
  skuPage.value = 1
  if (!skuLoaded.value) void loadSkus()
}

// ─────────────────────────── 交互 ───────────────────────────

function selectTab(next: TabKey): void {
  if (tab.value === next) return
  tab.value = next
  // 切标签回到第 1 页：两个标签的总数与页数不同，沿用旧页码多半落在空页上
  if (next === 'sku') {
    reloadSkus()
  } else {
    reloadProducts()
  }
}

function searchProducts(): void {
  reloadProducts()
}

function resetProducts(): void {
  productKeyword.value = ''
  reloadProducts()
}

function clearSkuKeyword(): void {
  skuKeyword.value = ''
}

function openProduct(product: Product): void {
  uni.navigateTo({ url: `/pages/products/product-detail?id=${product.id}` })
}

function openSku(sku: Sku): void {
  uni.navigateTo({ url: `/pages/products/sku-detail?id=${sku.id}` })
}

function skuTitle(sku: Sku): string {
  return `${productText(sku.productId)} · ${sku.color}-${sku.size}`
}

onShow(() => {
  if (!ensureLoggedIn()) return
  // 底栏自己画（原生那条最多 5 项、高度也改不了），每次显示都要重新藏一次
  hideNativeTabBar()

  if (!allowed.value) return
  loadDictionary()
  // 停在原来那一页刷新：从详情页退回来不该被弹回第 1 页
  // （SKU 那一侧的页码是本地页，重新取回这批后由 skuCurrentPage 夹回有效页）
  if (tab.value === 'sku') {
    void loadSkus()
  } else {
    void loadProducts()
  }
})

onPullDownRefresh(() => {
  void (async () => {
    await loadDictionary(true)
    if (tab.value === 'sku') {
      // 下拉刷新这一批 SKU；翻页请走下面的翻页条
      await loadSkus()
    } else {
      await loadProducts()
    }
    uni.stopPullDownRefresh()
  })()
})
</script>

<template>
  <view class="page products page--tabbar" :class="themeClass">
    <view v-if="!allowed" class="card notice">
      <text class="notice-title">当前账号没有主数据读权限</text>
      <text class="notice-text">
        本页依赖 mdm:read。请联系管理员分配权限；前端判断只影响入口展示，后端仍会做最终校验。
      </text>
    </view>

    <template v-else>
      <view class="tabs">
        <view class="tab" :class="{ active: tab === 'product' }" @click="selectTab('product')">
          商品
        </view>
        <view class="tab" :class="{ active: tab === 'sku' }" @click="selectTab('sku')">
          SKU
        </view>
      </view>

      <!-- ───────────── 商品 ───────────── -->
      <template v-if="tab === 'product'">
        <view class="card filter-card">
          <view class="search-row">
            <input
              v-model="productKeyword"
              class="search-input"
              placeholder="款号或商品名关键字"
              confirm-type="search"
              @confirm="searchProducts"
            />
            <text v-if="productKeyword" class="clear-link" @click="resetProducts">清除</text>
          </view>
          <view class="filter-actions">
            <button class="btn-ghost filter-btn" hover-class="none" @click="resetProducts">重置</button>
            <button class="btn-primary filter-btn" hover-class="none" @click="searchProducts">查询</button>
          </view>
        </view>

        <view v-if="productLoaded" class="summary row-between">
          <text class="summary-text">共 {{ productTotal }} 个商品</text>
          <text class="summary-text">本页 {{ products.length }} 个</text>
        </view>

        <view v-if="productLoading && !productLoaded" class="card loading-block">
          正在读取商品…
        </view>

        <view v-else-if="productError && !products.length" class="card error-block">
          <text class="block-text">{{ productError }}</text>
          <button class="btn-ghost block-btn" hover-class="none" @click="loadProducts">重试</button>
        </view>

        <view v-else-if="!products.length" class="card empty-block">
          {{
            productKeyword.trim()
              ? '没有匹配的商品。可以换一个款号/商品名关键字。'
              : '还没有商品。商品与 SKU 的创建请使用 Web 端。'
          }}
        </view>

        <view v-else class="list">
          <view v-for="product in products" :key="product.id" class="card item-card" @click="openProduct(product)">
            <view class="row-between item-head">
              <text class="item-title">{{ product.name }}</text>
              <text class="tag" :class="{ 'tag-off': !product.enabled }">
                {{ product.enabled ? '在用' : '已停用' }}
              </text>
            </view>
            <text class="item-sub">款号 {{ product.code }}</text>
            <view class="row-between item-foot">
              <text class="meta-text">
                {{ product.category || '未分类' }} · {{ product.season || '未设置季节' }}
              </text>
              <text class="arrow">商品详情 ›</text>
            </view>
          </view>
        </view>

        <view v-if="productLoaded && productTotal" class="pager-wrap">
          <list-pager
            :page="productPage"
            :size="PAGE_SIZE"
            :total="productTotal"
            :loading="productLoading"
            unit="个"
            @change="goProductPage"
          />
          <view v-if="productError && products.length" class="inline-error">
            <text class="block-text">{{ productError }}</text>
            <text class="inline-retry" @click="loadProducts">重试</text>
          </view>
        </view>
      </template>

      <!-- ───────────── SKU ───────────── -->
      <template v-else>
        <view class="card filter-card">
          <view class="search-row">
            <input
              v-model="skuKeyword"
              class="search-input"
              placeholder="条码 / 款号 / 颜色 / 尺码"
              confirm-type="search"
            />
            <text v-if="skuKeyword" class="clear-link" @click="clearSkuKeyword">清除</text>
          </view>
          <text class="search-hint">
            SKU 接口没有关键字参数：这里对已加载的前 {{ SKU_LIMIT }} 条做本地过滤。
          </text>
        </view>

        <view v-if="skuLoaded" class="summary row-between">
          <text class="summary-text">
            {{ skuKeyword.trim() ? `匹配 ${filteredSkus.length} 条` : `共 ${skuTotal} 个 SKU` }}
          </text>
          <text class="summary-text">本页 {{ pagedSkus.length }} 个</text>
        </view>

        <view v-if="skuLoading && !skuLoaded" class="card loading-block">正在读取 SKU…</view>

        <view v-else-if="skuError" class="card error-block">
          <text class="block-text">{{ skuError }}</text>
          <button class="btn-ghost block-btn" hover-class="none" @click="loadSkus">重试</button>
        </view>

        <view v-else-if="!filteredSkus.length" class="card empty-block">
          {{
            skuKeyword.trim()
              ? '已加载的 SKU 中没有匹配项。可以换条码、款号、颜色或尺码再试。'
              : '还没有 SKU。SKU 矩阵生成请使用 Web 端。'
          }}
        </view>

        <view v-else class="list">
          <view v-for="sku in pagedSkus" :key="sku.id" class="card item-card" @click="openSku(sku)">
            <view class="row-between item-head">
              <text class="item-title">{{ skuTitle(sku) }}</text>
              <text class="price">{{ formatFen(sku.retailPriceAmount) }}</text>
            </view>
            <view class="row-between item-foot">
              <text class="meta-text">条码 {{ sku.barcode || '未设置' }}</text>
              <text class="tag" :class="{ 'tag-off': !sku.enabled }">
                {{ sku.enabled ? '在用' : '已停用' }}
              </text>
            </view>
          </view>
        </view>

        <view v-if="skuLoaded && filteredSkus.length" class="pager-wrap">
          <list-pager
            :page="skuCurrentPage"
            :size="PAGE_SIZE"
            :total="filteredSkus.length"
            :loading="skuLoading"
            unit="个"
            @change="goSkuPage"
          />
        </view>

        <view v-if="skuLoaded && skuTruncated" class="truncate-note">
          SKU 共 {{ skuTotal }} 条，当前只加载前 {{ skus.length }} 条并在这一批里翻页；
          未覆盖的 SKU 请用 Web 端查询。
        </view>
      </template>

      <text v-if="dictWarning" class="dict-warning">{{ dictWarning }}</text>
    </template>

    <app-tab-bar active="products" />
  </view>
</template>

<style scoped>
.products {
  padding-top: 20rpx;
}

.notice-title {
  display: block;
  font-size: 29rpx;
  font-weight: 600;
  color: var(--c-danger);
}

.notice-text {
  display: block;
  margin-top: 10rpx;
  font-size: 25rpx;
  color: var(--c-text-2);
  line-height: 1.55;
}

.tabs {
  display: flex;
  padding: 6rpx;
  margin-bottom: 20rpx;
  background: var(--c-fill-strong);
  border-radius: 18rpx;
}

.tab {
  flex: 1;
  height: 72rpx;
  line-height: 72rpx;
  text-align: center;
  border-radius: 14rpx;
  font-size: 27rpx;
  color: var(--c-text-2);
}

.tab.active {
  background: var(--c-surface);
  color: var(--c-brand-text);
  font-weight: 600;
  box-shadow: 0 4rpx 14rpx var(--c-shadow);
}

.filter-card {
  padding: 22rpx 26rpx;
}

.search-row {
  display: flex;
  align-items: center;
  height: 76rpx;
  padding: 0 20rpx;
  background: var(--c-fill);
  border: 1rpx solid var(--c-line-strong);
  border-radius: 14rpx;
}

.search-input {
  flex: 1;
  height: 70rpx;
  font-size: 26rpx;
  color: var(--c-text);
}

.clear-link {
  margin-left: 16rpx;
  font-size: 23rpx;
  color: var(--c-brand-text);
}

.search-hint {
  display: block;
  margin-top: 14rpx;
  font-size: 21rpx;
  color: var(--c-text-4);
  line-height: 1.45;
}

.filter-actions {
  display: flex;
  gap: 18rpx;
  margin-top: 18rpx;
}

.filter-btn {
  flex: 1;
  height: 72rpx;
  font-size: 25rpx;
}

.summary {
  margin: 22rpx 4rpx 14rpx;
}

.summary-text {
  font-size: 23rpx;
  color: var(--c-text-3);
}

.list {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}

.item-card {
  padding: 24rpx;
}

.item-head {
  margin-bottom: 8rpx;
}

.item-title {
  flex: 1;
  min-width: 0;
  font-size: 28rpx;
  font-weight: 600;
  color: var(--c-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.price {
  flex-shrink: 0;
  margin-left: 14rpx;
  font-size: 28rpx;
  font-weight: 700;
  color: var(--c-brand-text);
}

.item-sub {
  display: block;
  font-size: 23rpx;
  color: var(--c-text-2);
}

.item-foot {
  margin-top: 12rpx;
}

.meta-text {
  font-size: 22rpx;
  color: var(--c-text-3);
}

.tag-off {
  color: var(--c-danger);
  background: var(--c-danger-wash);
}

.arrow {
  font-size: 22rpx;
  color: var(--c-brand-text);
}

.pager-wrap {
  margin-top: 20rpx;
}

.truncate-note {
  display: block;
  margin-top: 18rpx;
  padding: 16rpx 20rpx;
  background: var(--c-warn-wash);
  border-radius: 12rpx;
  font-size: 21rpx;
  line-height: 1.5;
  color: var(--c-warn);
}

.block-text {
  display: block;
  line-height: 1.5;
}

.block-btn {
  width: 240rpx;
  margin: 24rpx auto 0;
}

.inline-error {
  margin-top: 16rpx;
  padding: 18rpx 22rpx;
  background: var(--c-danger-wash);
  border-radius: 14rpx;
  text-align: center;
  font-size: 23rpx;
  color: var(--c-danger);
}

.inline-retry {
  margin-left: 12rpx;
  font-weight: 600;
  text-decoration: underline;
}

.dict-warning {
  display: block;
  margin-top: 22rpx;
  text-align: center;
  font-size: 21rpx;
  color: var(--c-text-4);
}
</style>
