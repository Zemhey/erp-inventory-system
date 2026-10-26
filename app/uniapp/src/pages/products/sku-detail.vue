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
 * SKU 详情（只读）：条码、颜色/尺码、零售价与所属商品。
 * 商品名称读不到时退回 productId，不把"字典缺失"伪造成"没有商品"。
 */

const allowed = computed(() => hasPermission('mdm:read'))

const skuId = ref(0)
const sku = ref<Sku | null>(null)
const product = ref<Product | null>(null)
const productError = ref('')

const loading = ref(false)
const loaded = ref(false)
const error = ref('')

async function load(): Promise<void> {
  if (!skuId.value) {
    error.value = '缺少 SKU ID，无法打开详情。'
    loaded.value = true
    return
  }
  if (!allowed.value || loading.value) return

  loading.value = true
  error.value = ''
  productError.value = ''
  product.value = null

  const skuResult = await safe(mdmApi.getSku(skuId.value))
  if (!skuResult.ok) {
    sku.value = null
    error.value = errorMessage(skuResult.error)
    loaded.value = true
    loading.value = false
    return
  }
  sku.value = skuResult.value

  const productResult = await safe(mdmApi.getProduct(skuResult.value.productId))
  if (productResult.ok) {
    product.value = productResult.value
  } else {
    productError.value = errorMessage(productResult.error)
  }

  loaded.value = true
  loading.value = false
}

function openProduct(): void {
  if (!sku.value) return
  uni.navigateTo({ url: `/pages/products/product-detail?id=${sku.value.productId}` })
}

onLoad((options: Record<string, string> | undefined) => {
  skuId.value = Number(options?.id ?? 0)
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
      <view v-if="loading && !loaded" class="card loading-block">正在读取 SKU…</view>

      <view v-else-if="error && !sku" class="card error-block">
        <text class="block-text">{{ error }}</text>
        <button class="btn-ghost block-btn" hover-class="none" @click="load">重试</button>
      </view>

      <template v-else-if="sku">
        <view class="card hero-card">
          <view class="row-between">
            <text class="sku-title">{{ sku.color }}-{{ sku.size }}</text>
            <text class="tag" :class="{ 'tag-off': !sku.enabled }">
              {{ sku.enabled ? '在用' : '已停用' }}
            </text>
          </view>
          <text class="sku-code">SKU #{{ sku.id }}</text>

          <view class="price-block">
            <text class="price-label">零售价</text>
            <text class="price-value">{{ formatFen(sku.retailPriceAmount) }}</text>
          </view>

          <view class="kv"><text class="kv-label">条码</text><text class="kv-value">{{ sku.barcode || '未设置' }}</text></view>
          <view class="kv"><text class="kv-label">颜色</text><text class="kv-value">{{ sku.color }}</text></view>
          <view class="kv"><text class="kv-label">尺码</text><text class="kv-value">{{ sku.size }}</text></view>
          <view class="kv">
            <text class="kv-label">所属商品</text>
            <text class="kv-value link" @click="openProduct">
              {{ product ? `${product.name}（${product.code}）` : `商品#${sku.productId}` }} ›
            </text>
          </view>
        </view>

        <view v-if="productError" class="card partial-error">
          <text class="partial-error-text">
            商品信息读取失败：{{ productError }}。SKU 本身的数据仍然有效。
          </text>
          <text class="inline-retry" @click="load">重试</text>
        </view>

        <view class="card read-only-note">
          <text class="read-only-text">
            移动端只读：SKU 的新增、改价、停用与矩阵生成请使用 Web 端。
          </text>
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

.sku-title {
  flex: 1;
  min-width: 0;
  font-size: 36rpx;
  font-weight: 700;
  color: #1c2230;
}

.sku-code {
  display: block;
  margin-top: 8rpx;
  font-size: 23rpx;
  color: #8a90a2;
}

.price-block {
  margin-top: 24rpx;
  padding: 20rpx 24rpx;
  background: #f7f9fc;
  border-radius: 16rpx;
}

.price-label {
  display: block;
  font-size: 21rpx;
  color: #8a90a2;
}

.price-value {
  display: block;
  margin-top: 4rpx;
  font-size: 42rpx;
  font-weight: 700;
  color: #2c4066;
}

.kv {
  display: flex;
  align-items: flex-start;
  margin-top: 14rpx;
}

.kv-label {
  width: 130rpx;
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

.link {
  color: #2c4066;
  font-weight: 600;
}

.tag-off {
  color: #9c3a26;
  background: #fdf0ec;
}

.partial-error {
  margin-top: 18rpx;
  padding: 20rpx 24rpx;
  background: #fdf1ee;
}

.partial-error-text {
  font-size: 22rpx;
  line-height: 1.5;
  color: #9c3a26;
}

.inline-retry {
  display: inline-block;
  margin-top: 10rpx;
  font-size: 23rpx;
  font-weight: 600;
  color: #9c3a26;
  text-decoration: underline;
}

.read-only-note {
  margin-top: 18rpx;
  padding: 20rpx 24rpx;
}

.read-only-text {
  font-size: 22rpx;
  line-height: 1.5;
  color: #8a90a2;
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
