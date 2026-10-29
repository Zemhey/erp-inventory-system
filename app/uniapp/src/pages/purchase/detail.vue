<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { purApi, type OrderLine, type PurchaseOrder } from '@/api/client'
import { useDictionary } from '@/composables/useDictionary'
import { useTheme } from '@/composables/useTheme'
import { hasPermission } from '@/stores/session'
import {
  ACTION_META,
  ALLOWED_ACTIONS,
  canExecuteAction,
  isDocumentStatus,
  statusMeta,
} from '@/utils/documentStatus'
import { errorMessage } from '@/utils/error'
import { formatDate } from '@/utils/format'
import { formatFen } from '@/utils/money'
import { ensureLoggedIn } from '@/utils/routeGuard'
import { safe } from '@/utils/safe'

/**
 * 进货单详情 + 单动作 receive。
 *
 * 移动端做仓库现场收货：一次把**全部未入库数量**入库，可附带统一批次/效期。
 * 逐行改量、分批拆分与建单仍留在 Web（§8.5 的"单动作"约束）。
 * 按钮显隐只由后端状态镜像 + pur:receive 决定，不自行推断。
 */

const { themeClass } = useTheme()

const allowed = computed(() => hasPermission('pur:read'))
const { loadDictionary, supplierText, warehouseText, skuText, warning: dictWarning } =
  useDictionary()

const orderId = ref(0)
const order = ref<PurchaseOrder | null>(null)
const loading = ref(false)
const loaded = ref(false)
const error = ref('')
const actionLoading = ref(false)
const actionError = ref('')

const batchNo = ref('')
const expiryDate = ref('')

const statusAllowsReceive = computed(
  () =>
    !!order.value &&
    isDocumentStatus(order.value.status) &&
    ALLOWED_ACTIONS.PURCHASE[order.value.status].includes('receive'),
)

const canReceive = computed(
  () =>
    !!order.value &&
    canExecuteAction('PURCHASE', order.value.status, 'receive', hasPermission),
)

function lineOpen(line: OrderLine): number {
  return line.openQty ?? line.qty - (line.receivedQty ?? 0)
}

const openLines = computed(() => (order.value?.lines ?? []).filter((line) => lineOpen(line) > 0))
const totalOpenQty = computed(() =>
  openLines.value.reduce((sum, line) => sum + lineOpen(line), 0),
)

const actionHint = computed(() => {
  if (!order.value || canReceive.value) return ''
  if (statusAllowsReceive.value) {
    return '当前账号没有入库权限（pur:receive），请联系管理员。'
  }
  return `当前状态「${statusMeta(order.value.status).label}」不可入库；审核与作废请使用 Web 端。`
})

const receivedQty = computed(() =>
  (order.value?.lines ?? []).reduce((sum, line) => sum + (line.receivedQty ?? 0), 0),
)

async function load(): Promise<void> {
  if (!orderId.value) {
    error.value = '缺少单据 ID，无法打开详情。'
    loaded.value = true
    return
  }
  if (!allowed.value) return
  if (loading.value) return

  loading.value = true
  error.value = ''
  const result = await safe(purApi.get(orderId.value))
  if (result.ok) {
    order.value = result.value
  } else {
    order.value = null
    error.value = errorMessage(result.error)
  }
  loaded.value = true
  loading.value = false
}

function onExpiryChange(event: unknown): void {
  const value = (event as { detail?: { value?: string } })?.detail?.value
  expiryDate.value = typeof value === 'string' ? value : ''
}

function clearExpiry(): void {
  expiryDate.value = ''
}

function confirmReceive(): void {
  if (!order.value || actionLoading.value) return
  if (!openLines.value.length) {
    actionError.value = '这张单据没有未入库数量，无需入库。'
    return
  }
  uni.showModal({
    title: '确认入库',
    content: `${ACTION_META['PURCHASE:receive'].confirmText}\n本次入库合计 ${totalOpenQty.value} 件。`,
    success: (result) => {
      if (result.confirm) void doReceive()
    },
  })
}

async function doReceive(): Promise<void> {
  if (!order.value || actionLoading.value) return
  actionLoading.value = true
  actionError.value = ''

  // X-Request-Id 由 api/client.ts 自动注入；页面不手写幂等键
  const result = await safe(
    purApi.receive(order.value.id, {
      lines: openLines.value.map((line) => ({
        lineId: line.id,
        qty: lineOpen(line),
        batchNo: batchNo.value.trim() || undefined,
        expiryDate: expiryDate.value || undefined,
      })),
    }),
  )

  if (result.ok) {
    order.value = result.value
    const partial = result.value.status === 'PARTIAL'
    uni.showToast({ title: partial ? '部分入库成功' : '入库成功', icon: 'success' })
  } else {
    actionError.value = errorMessage(result.error)
  }

  actionLoading.value = false
}

function openLineSku(skuId: number): void {
  uni.navigateTo({ url: `/pages/products/sku-detail?id=${skuId}` })
}

onLoad((options: Record<string, string> | undefined) => {
  orderId.value = Number(options?.id ?? 0)
})

onShow(() => {
  if (!ensureLoggedIn()) return
  if (!allowed.value) {
    loaded.value = true
    return
  }
  loadDictionary()
  load()
})

onPullDownRefresh(() => {
  void (async () => {
    await loadDictionary(true)
    await load()
    uni.stopPullDownRefresh()
  })()
})
</script>

<template>
  <view class="page detail" :class="themeClass">
    <view v-if="!allowed" class="card notice">
      <text class="notice-title">当前账号没有进货单读权限</text>
      <text class="notice-text">本页依赖 pur:read；请联系管理员分配权限。</text>
    </view>

    <template v-else>
      <view v-if="loading && !loaded" class="card loading-block">正在读取进货单…</view>

      <view v-else-if="error && !order" class="card error-block">
        <text class="block-text">{{ error }}</text>
        <button class="btn-ghost block-btn" hover-class="none" @click="load">重试</button>
      </view>

      <template v-else-if="order">
        <view class="card hero-card">
          <view class="row-between">
            <text class="order-no">{{ order.orderNo }}</text>
            <text class="status-tag" :class="`tone-${statusMeta(order.status).tone}`">
              {{ statusMeta(order.status).label }}
            </text>
          </view>
          <text class="status-desc">{{ statusMeta(order.status).description }}</text>

          <view class="hero-grid">
            <view class="hero-cell">
              <text class="hero-label">数量</text>
              <text class="hero-value">{{ order.totalQty }}</text>
            </view>
            <view class="hero-cell">
              <text class="hero-label">已入</text>
              <text class="hero-value">{{ receivedQty }}</text>
            </view>
            <view class="hero-cell">
              <text class="hero-label">金额</text>
              <text class="hero-value amount">{{ formatFen(order.totalAmount) }}</text>
            </view>
          </view>

          <view class="kv"><text class="kv-label">供应商</text><text class="kv-value">{{ supplierText(order.supplierId) }}</text></view>
          <view class="kv"><text class="kv-label">入库仓库</text><text class="kv-value">{{ warehouseText(order.warehouseId) }}</text></view>
          <view class="kv"><text class="kv-label">业务日期</text><text class="kv-value">{{ formatDate(order.bizDate) }}</text></view>
          <view class="kv"><text class="kv-label">账期</text><text class="kv-value">{{ order.period || '—' }}</text></view>
          <view v-if="order.remark" class="kv"><text class="kv-label">备注</text><text class="kv-value">{{ order.remark }}</text></view>
        </view>

        <view class="section-head row-between">
          <text class="section-title">明细（{{ order.lines.length }} 行）</text>
          <text class="section-hint">点 SKU 看商品</text>
        </view>

        <view v-if="!order.lines.length" class="card empty-block">
          这张单据没有明细行。请回到 Web 端检查单据数据。
        </view>

        <view v-else class="list">
          <view v-for="line in order.lines" :key="line.id" class="card line-card" @click="openLineSku(line.skuId)">
            <view class="row-between line-head">
              <text class="line-sku">{{ line.lineNo }}. {{ skuText(line.skuId) }}</text>
              <text class="line-amount">{{ formatFen(line.amount) }}</text>
            </view>
            <view class="row-between line-meta">
              <text class="meta-text">数量 {{ line.qty }} · 已入 {{ line.receivedQty ?? 0 }}</text>
              <text class="meta-text">{{ formatFen(line.unitPriceAmount) }} / 件</text>
            </view>
            <view class="row-between line-meta">
              <text class="meta-text" :class="{ 'open-positive': lineOpen(line) > 0 }">
                未入 {{ lineOpen(line) }}
              </text>
            </view>
          </view>
        </view>

        <view v-if="actionError" class="inline-error">
          <text class="block-text">{{ actionError }}</text>
        </view>

        <view v-if="canReceive" class="action-card card">
          <text class="action-title">现场入库</text>
          <text class="action-desc">
            本次将全部未入库数量（{{ totalOpenQty }} 件）一次入库；移动端不支持逐行改量，
            分批或改数量请使用 Web 端。
          </text>

          <view class="form-row">
            <text class="form-label">批次号</text>
            <input
              v-model="batchNo"
              class="form-input"
              placeholder="可不填；填写后整单使用同一批次"
              :disabled="actionLoading"
            />
          </view>

          <view class="form-row">
            <text class="form-label">效期</text>
            <picker
              class="picker-flex"
              mode="date"
              :value="expiryDate"
              :disabled="actionLoading"
              @change="onExpiryChange"
            >
              <view class="picker-value">
                <text>{{ expiryDate || '不填写' }}</text>
                <text class="picker-arrow">⌄</text>
              </view>
            </picker>
            <text v-if="expiryDate" class="clear-link" @click="clearExpiry">清除</text>
          </view>

          <button
            class="btn-primary action-btn"
            hover-class="none"
            :loading="actionLoading"
            :disabled="actionLoading || !totalOpenQty"
            @click="confirmReceive"
          >
            {{ actionLoading ? '入库中…' : '确认入库' }}
          </button>
        </view>
        <view v-else-if="actionHint" class="card action-note">
          <text class="action-note-text">{{ actionHint }}</text>
        </view>

        <text v-if="dictWarning" class="dict-warning">{{ dictWarning }}</text>
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
  color: var(--c-danger);
}

.notice-text {
  display: block;
  margin-top: 10rpx;
  font-size: 25rpx;
  color: var(--c-text-2);
  line-height: 1.55;
}

.hero-card {
  padding: 28rpx;
}

.order-no {
  flex: 1;
  min-width: 0;
  font-size: 34rpx;
  font-weight: 700;
  color: var(--c-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.status-tag {
  flex-shrink: 0;
  margin-left: 14rpx;
  padding: 6rpx 18rpx;
  border-radius: 999rpx;
  font-size: 22rpx;
  font-weight: 600;
}

.tone-info {
  background: var(--c-fill-strong);
  color: var(--c-text-2);
}

.tone-primary {
  background: var(--c-info-wash);
  color: var(--c-info-text);
}

.tone-success {
  background: var(--c-ok-wash);
  color: var(--c-success);
}

.tone-warning {
  background: var(--c-warn-wash);
  color: var(--c-warn);
}

.tone-danger {
  background: var(--c-danger-wash);
  color: var(--c-danger);
}

.status-desc {
  display: block;
  margin-top: 10rpx;
  font-size: 23rpx;
  color: var(--c-text-3);
}

.hero-grid {
  display: flex;
  margin-top: 24rpx;
  padding: 20rpx 0;
  background: var(--c-fill);
  border-radius: 16rpx;
}

.hero-cell {
  flex: 1;
  text-align: center;
}

.hero-label {
  display: block;
  font-size: 21rpx;
  color: var(--c-text-3);
}

.hero-value {
  display: block;
  margin-top: 4rpx;
  font-size: 36rpx;
  font-weight: 700;
  color: var(--c-text);
}

.hero-value.amount {
  font-size: 30rpx;
  color: var(--c-brand-text);
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
  color: var(--c-text-3);
}

.kv-value {
  flex: 1;
  font-size: 25rpx;
  color: var(--c-text);
  line-height: 1.45;
}

.section-head {
  margin: 28rpx 4rpx 14rpx;
}

.section-hint {
  font-size: 21rpx;
  color: var(--c-text-4);
}

.list {
  display: flex;
  flex-direction: column;
  gap: 14rpx;
}

.line-card {
  padding: 24rpx;
}

.line-head {
  margin-bottom: 10rpx;
}

.line-sku {
  flex: 1;
  min-width: 0;
  font-size: 26rpx;
  font-weight: 600;
  color: var(--c-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.line-amount {
  flex-shrink: 0;
  margin-left: 14rpx;
  font-size: 26rpx;
  font-weight: 700;
  color: var(--c-brand-text);
}

.line-meta {
  margin-top: 6rpx;
}

.meta-text {
  font-size: 22rpx;
  color: var(--c-text-3);
}

.open-positive {
  color: var(--c-warn);
  font-weight: 600;
}

.action-card {
  margin-top: 26rpx;
  padding: 26rpx;
}

.action-title {
  display: block;
  font-size: 29rpx;
  font-weight: 700;
  color: var(--c-text);
}

.action-desc {
  display: block;
  margin-top: 8rpx;
  font-size: 23rpx;
  line-height: 1.5;
  color: var(--c-text-2);
}

.form-row {
  display: flex;
  align-items: center;
  min-height: 84rpx;
  margin-top: 8rpx;
  border-bottom: 1rpx solid var(--c-line);
}

.form-label {
  width: 110rpx;
  flex-shrink: 0;
  font-size: 24rpx;
  color: var(--c-text-2);
}

.form-input {
  flex: 1;
  height: 72rpx;
  font-size: 26rpx;
  color: var(--c-text);
}

.picker-flex {
  flex: 1;
}

.picker-value {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  font-size: 26rpx;
  color: var(--c-text);
}

.picker-arrow {
  margin-left: 10rpx;
  color: var(--c-text-4);
  font-size: 26rpx;
}

.clear-link {
  margin-left: 18rpx;
  font-size: 23rpx;
  color: var(--c-brand-text);
}

.action-btn {
  width: 100%;
  margin-top: 22rpx;
}

.action-note {
  margin-top: 26rpx;
  padding: 22rpx 24rpx;
}

.action-note-text {
  font-size: 23rpx;
  line-height: 1.5;
  color: var(--c-text-3);
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
  margin-top: 20rpx;
  padding: 18rpx 22rpx;
  background: var(--c-danger-wash);
  border-radius: 14rpx;
  font-size: 23rpx;
  color: var(--c-danger);
}

.dict-warning {
  display: block;
  margin-top: 22rpx;
  text-align: center;
  font-size: 21rpx;
  color: var(--c-text-4);
}
</style>
