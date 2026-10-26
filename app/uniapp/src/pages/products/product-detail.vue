<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { mdmApi, type Product, type Sku } from '@/api/client'
import { hasPermission } from '@/stores/session'
import { errorMessage } from '@/utils/error'
import { formatFen } from '@/utils/money'
import { ensureLoggedIn } from '@/utils/routeGuard'
import { safe } from '@/utils/safe'

/**
 * 商品详情：商品基本信息 + 该商品的 SKU 列表（只读）。
 * SKU 矩阵生成、商品编辑/停用都在 Web，移动端不提供入口。
 */

const SKU_LIMIT = 200

const allowed = computed(() => hasPermission('mdm:read'))

const productId = ref(0)
const product = ref<Product | null>(null)
const skus = ref<Sku[]>([])
const skuTotal = ref(0)
const skuTruncated = computed(() => skuTotal.value > skus.value.length)

const loading = ref(false)
const loaded = ref(false)
const error = ref('')
const skuError = ref('')

async function load(): Promise<void> {
  if (!productId.value) {
    error.value = '缺少商品 ID，无法打开详情。'
    loaded.value = true
    return
  }
  if (!allowed.value || loading.value) return

  loading.value = true
  error.value = ''
  skuError.value = ''

  const [productResult, skuResult] = await Promise.all([
    safe(mdmApi.getProduct(productId.value)),
    safe(mdmApi.listSkus({ productId: productId.value, page: 1, size: SKU_LIMIT })),
  ])

  if (productResult.ok) {
    product.value = productResult.value
  } else {
    product.value = null
    error.value = errorMessage(productResult.error)
  }

  if (skuResult.ok) {
    skus.value = skuResult.value.items
    skuTotal.value = skuResult.value.total
  } else {
    skus.value = []
    skuTotal.value = 0
    skuError.value = errorMessage(skuResult.error)
  }

  loaded.value = true
  loading.value = false
}

function openSku(sku: Sku): void {
  uni.navigateTo({ url: `/pages/products/sku-detail?id=${sku.id}` })
}

onLoad((options: Record<string, string> | undefined) => {
  productId.value = Number(options?.id ?? 0)
})

onShow(() => {
  if (!ensureLoggedIn()) return
  if (!allowed.value) {
    loaded.value = true
    return
  }
  load()
})

onPullDownRefresh(() => {
  void (async () => {
    await load()
    uni.stopPullDownRefresh()
  })()
})
</script>

<template>
  <view class="page detail">
    <view v-if="!allowed" class="card notice">
      <text class="notice-title">当前账号没有主数据读权限</text>
      <text class="notice-text">本页依赖 mdm:read；请联系管理员分配权限。</text>
    </view>

    <template v-else>
      <view v-if="loading && !loaded" class="card loading-block">正在读取商品…</view>

      <view v-else-if="error && !product" class="card error-block">
        <text class="block-text">{{ error }}</text>
        <button class="btn-ghost block-btn" hover-class="none" @click="load">重试</button>
      </view>

      <template v-else-if="product">
        <view class="card hero-card">
          <view class="row-between">
            <text class="product-name">{{ product.name }}</text>
            <text class="tag" :class="{ 'tag-off': !product.enabled }">
              {{ product.enabled ? '在用' : '已停用' }}
            </text>
          </view>
          <text class="product-code">款号 {{ product.code }}</text>

          <view class="kv"><text class="kv-label">分类</text><text class="kv-value">{{ product.category || '未分类' }}</text></view>
          <view class="kv"><text class="kv-label">季节</text><text class="kv-value">{{ product.season || '未设置' }}</text></view>
          <view class="kv"><text class="kv-label">商品 ID</text><text class="kv-value">#{{ product.id }}</text></view>
        </view>

        <view class="section-head row-between">
          <text class="section-title">SKU（{{ skus.length }} / {{ skuTotal }}）</text>
          <text class="section-hint">只读</text>
        </view>

        <view v-if="skuError" class="card error-block">
          <text class="block-text">{{ skuError }}</text>
          <button class="btn-ghost block-btn" hover-class="none" @click="load">重试</button>
        </view>

        <view v-else-if="!skus.length" class="card empty-block">
          这个商品还没有 SKU。SKU 矩阵生成请使用 Web 端。
        </view>

        <view v-else class="list">
          <view v-for="sku in skus" :key="sku.id" class="card sku-card" @click="openSku(sku)">
            <view class="row-between">
              <text class="sku-title">{{ sku.color }}-{{ sku.size }}</text>
              <text class="price">{{ formatFen(sku.retailPriceAmount) }}</text>
            </view>
            <view class="row-between sku-foot">
              <text class="meta-text">条码 {{ sku.barcode || '未设置' }}</text>
              <text class="tag" :class="{ 'tag-off': !sku.enabled }">
                {{ sku.enabled ? '在用' : '已停用' }}
              </text>
            </view>
          </view>
        </view>

        <view v-if="skuTruncated" class="truncate-note">
          该商品 SKU 共 {{ skuTotal }} 条，当前只显示前 {{ skus.length }} 条。
        </view>
      </template>
    </template>
  </view>
</template>

<style scoped>
.detail {
  padding-top: 20rpx;
}

.notice-title {
  display: block;
  font-size: 29rpx;
  font-weight: 600;
  color: #9c3a26;
}

.notice-text {
  display: block;
  margin-top: 10rpx;
  font-size: 25rpx;
  color: #6e7585;
  line-height: 1.55;
}

.hero-card {
  padding: 28rpx;
}

.product-name {
  flex: 1;
  min-width: 0;
  font-size: 36rpx;
  font-weight: 700;
  color: #1c2230;
}

.product-code {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
  color: #6e7585;
}

.kv {
  display: flex;
  align-items: flex-start;
  margin-top: 14rpx;
}

.kv-label {
  width: 120rpx;
  flex-shrink: 0;
  font-size: 24rpx;
  color: #8a90a2;
}

.kv-value {
  flex: 1;
  font-size: 25rpx;
  color: #1c2230;
  line-height: 1.45;
}

.section-head {
  margin: 28rpx 4rpx 14rpx;
}

.section-hint {
  font-size: 21rpx;
  color: #a2a8b6;
}

.list {
  display: flex;
  flex-direction: column;
  gap: 14rpx;
}

.sku-card {
  padding: 24rpx;
}

.sku-title {
  flex: 1;
  min-width: 0;
  font-size: 27rpx;
  font-weight: 600;
  color: #1c2230;
}

.price {
  flex-shrink: 0;
  margin-left: 14rpx;
  font-size: 27rpx;
  font-weight: 700;
  color: #2c4066;
}

.sku-foot {
  margin-top: 10rpx;
}

.meta-text {
  font-size: 22rpx;
  color: #8a90a2;
}

.tag-off {
  color: #9c3a26;
  background: #fdf0ec;
}

.truncate-note {
  display: block;
  margin-top: 18rpx;
  padding: 16rpx 20rpx;
  background: #fbf1e3;
  border-radius: 12rpx;
  font-size: 21rpx;
  line-height: 1.5;
  color: #a86a1f;
}

.block-text {
  display: block;
  line-height: 1.5;
}

.block-btn {
  width: 240rpx;
  margin: 24rpx auto 0;
}
</style>
