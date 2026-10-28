<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { quoteApi, type SalesQuote } from '@/api/client'
import { useDictionary } from '@/composables/useDictionary'
import { hasPermission } from '@/stores/session'
import { isDocumentStatus, statusMeta, type DocumentStatus } from '@/utils/documentStatus'
import { errorMessage } from '@/utils/error'
import { formatDate } from '@/utils/format'
import { formatFen } from '@/utils/money'
import { ensureLoggedIn } from '@/utils/routeGuard'
import { safe } from '@/utils/safe'

/**
 * 报价单详情与流转（FR-SAL-05）：提交 / 审核 / 驳回 / 作废 / 转出货单。
 *
 * ★ 动作表是后端 `DocumentStatus` 转移表的镜像（shared/document/DocumentStatus.java:22-30），
 *   不是移动端自创：DRAFT→SUBMITTED/VOIDED、SUBMITTED→APPROVED/REJECTED/VOIDED、
 *   APPROVED→…VOIDED 且只有 APPROVED 能转化（SalesQuoteApplicationService:196）。
 *   按钮显隐只看 [状态, 权限码 sal:quote]；最终能否执行仍由后端拒（消息原样显示）。
 *
 * 金额一律整数分，只有 formatFen() 这一处除 100。
 */

type QuoteAction = 'submit' | 'approve' | 'reject' | 'void' | 'convert'

const ALLOWED_ACTIONS: Record<DocumentStatus, QuoteAction[]> = {
  DRAFT: ['submit', 'void'],
  SUBMITTED: ['approve', 'reject', 'void'],
  APPROVED: ['convert', 'void'],
  PARTIAL: ['void'],
  COMPLETED: [],
  VOIDED: [],
  REJECTED: ['void'],
}

const ACTION_META: Record<QuoteAction, { label: string; confirm: string; danger: boolean }> = {
  submit: { label: '提交审核', confirm: '提交后内容锁定，等待审核。确定提交吗？', danger: false },
  approve: { label: '审核通过', confirm: '通过后这张报价单才能转为出货单。确定通过吗？', danger: false },
  reject: { label: '驳回', confirm: '驳回后报价单退回草稿态，需改单重提（Web 端）。确定驳回吗？', danger: true },
  void: { label: '作废', confirm: '作废是终态，这张报价单不再参与任何统计。确定作废吗？', danger: true },
  convert: {
    label: '转为出货单',
    confirm: '将按报价明细生成出货单并占用库存；库存不足或信用超额会整单回滚。确定转化吗？',
    danger: false,
  },
}

const quoteId = ref(0)
const quote = ref<SalesQuote | null>(null)
const loaded = ref(false)
const loading = ref(false)
const error = ref('')

const actionLoading = ref(false)
const actionError = ref('')

/** 转化目标仓库：留空 = 让后端按仓库 priority 自动选仓/拆单。 */
const convertWarehouseId = ref('')

const {
  loadDictionary,
  warehouses,
  customerText,
  skuText,
  warehouseText,
  warning: dictWarning,
} = useDictionary()

const canOperate = computed(() => hasPermission('sal:quote'))

const availableActions = computed<QuoteAction[]>(() => {
  const status = quote.value?.status
  if (!status || !isDocumentStatus(status)) return []
  if (!canOperate.value) return []
  return ALLOWED_ACTIONS[status]
})

/** 转化判据抄后端 converted()（COMPLETED 且有 convertedOrderId），不是只看状态。 */
const convertedOrderId = computed(() => {
  const current = quote.value
  if (!current) return null
  return current.status === 'COMPLETED' && current.convertedOrderId ? current.convertedOrderId : null
})

const actionHint = computed(() => {
  const current = quote.value
  if (!current) return ''
  if (convertedOrderId.value) {
    return `这张报价单已转为出货单（#${convertedOrderId.value}），不能重复转化。`
  }
  if (!canOperate.value) return '当前账号没有 sal:quote 权限，只能查看报价单。'
  if (current.status === 'VOIDED') return '已作废的报价单不可再流转，终态。'
  if (current.status === 'COMPLETED') return '已完成转化的报价单不再提供动作。'
  return ''
})

const warehouseOptions = computed(() => [
  { id: '', name: '自动分仓（按仓库优先级）' },
  ...warehouses.value
    .filter((warehouse) => warehouse.enabled)
    .map((warehouse) => ({ id: String(warehouse.id), name: warehouse.name })),
])

function warehouseName(index: number): string {
  const option = warehouseOptions.value[index]
  return option ? option.name : '自动分仓（按仓库优先级）'
}

const warehouseIndex = computed(() => {
  const index = warehouseOptions.value.findIndex(
    (option) => option.id === convertWarehouseId.value,
  )
  return index >= 0 ? index : 0
})

function onWarehouseChange(event: unknown): void {
  const detail = (event as { detail?: { value?: unknown } })?.detail
  const raw = detail?.value
  const index = typeof raw === 'string' ? Number.parseInt(raw, 10) : Number(raw)
  const option = warehouseOptions.value[Number.isNaN(index) ? 0 : index]
  convertWarehouseId.value = option ? option.id : ''
}

async function load(): Promise<void> {
  if (!quoteId.value) {
    error.value = '缺少报价单 ID，请从列表进入。'
    loaded.value = true
    return
  }
  if (loading.value) return

  loading.value = true
  error.value = ''
  const result = await safe(quoteApi.get(quoteId.value))
  if (result.ok) {
    quote.value = result.value
  } else {
    quote.value = null
    error.value = errorMessage(result.error)
  }
  loaded.value = true
  loading.value = false
}

function runAction(action: QuoteAction): void {
  if (!quote.value || actionLoading.value) return
  uni.showModal({
    title: ACTION_META[action].label,
    content: ACTION_META[action].confirm,
    confirmColor: ACTION_META[action].danger ? '#b4462f' : '#1f3a63',
    success: (modal) => {
      if (modal.confirm) void doAction(action)
    },
  })
}

async function doAction(action: QuoteAction): Promise<void> {
  const current = quote.value
  if (!current || actionLoading.value) return

  actionLoading.value = true
  actionError.value = ''

  // 转化返回的是**出货单**，与其余四个动作（返回报价单）不是同一个类型，必须分支处理。
  if (action === 'convert') {
    // X-Request-Id 由 api/client.ts 注入；转化与建单都靠它做幂等，页面不手写。
    const convertResult = await safe(
      quoteApi.convert(current.id, {
        warehouseId: convertWarehouseId.value ? Number(convertWarehouseId.value) : undefined,
      }),
    )
    if (convertResult.ok) {
      // 重读报价单才能拿到 convertedOrderId（转化后它已置为 COMPLETED）
      const refreshed = await safe(quoteApi.get(current.id))
      if (refreshed.ok) quote.value = refreshed.value
      uni.showToast({ title: `已转为 ${convertResult.value.orderNo}`, icon: 'success' })
    } else {
      actionError.value = errorMessage(convertResult.error)
    }
    actionLoading.value = false
    return
  }

  const result = await safe(
    action === 'submit'
      ? quoteApi.submit(current.id)
      : action === 'approve'
        ? quoteApi.approve(current.id)
        : action === 'reject'
          ? quoteApi.reject(current.id)
          : quoteApi.voidQuote(current.id),
  )

  if (result.ok) {
    quote.value = result.value
    uni.showToast({ title: `${ACTION_META[action].label}成功`, icon: 'success' })
  } else {
    actionError.value = errorMessage(result.error)
  }

  actionLoading.value = false
}

function openConvertedOrder(): void {
  const id = convertedOrderId.value
  if (!id) return
  uni.navigateTo({ url: `/pages/sales/detail?id=${id}` })
}

function openLineSku(skuId: number): void {
  uni.navigateTo({ url: `/pages/products/sku-detail?id=${skuId}` })
}

onLoad((options: Record<string, string> | undefined) => {
  quoteId.value = Number(options?.id ?? 0)
})

onShow(() => {
  if (!ensureLoggedIn()) return
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
  <view class="page detail">
    <view v-if="loading && !loaded" class="card loading-block">正在读取报价单…</view>

    <view v-else-if="error && !quote" class="card error-block">
      <text class="block-text">{{ error }}</text>
      <button class="btn-ghost block-btn" hover-class="none" @click="load">重试</button>
    </view>

    <template v-else-if="quote">
      <view class="card hero-card">
        <view class="row-between">
          <text class="order-no">{{ quote.quoteNo }}</text>
          <text class="status-tag" :class="`tone-${statusMeta(quote.status).tone}`">
            {{ statusMeta(quote.status).label }}
          </text>
        </view>
        <text class="status-desc">{{ statusMeta(quote.status).description }}</text>

        <view class="hero-grid">
          <view class="hero-cell">
            <text class="hero-label">数量</text>
            <text class="hero-value">{{ quote.totalQty }}</text>
          </view>
          <view class="hero-cell">
            <text class="hero-label">行数</text>
            <text class="hero-value">{{ (quote.lines ?? []).length }}</text>
          </view>
          <view class="hero-cell">
            <text class="hero-label">金额</text>
            <text class="hero-value amount">{{ formatFen(quote.totalAmount) }}</text>
          </view>
        </view>

        <view class="kv">
          <text class="kv-label">客户</text>
          <text class="kv-value">{{ customerText(quote.customerId) }}</text>
        </view>
        <view class="kv">
          <text class="kv-label">报价日期</text>
          <text class="kv-value">{{ formatDate(quote.bizDate) }}</text>
        </view>
        <view class="kv">
          <text class="kv-label">有效期至</text>
          <text class="kv-value">
            {{ formatDate(quote.validUntil) }}
            <text v-if="quote.status === 'APPROVED'" class="kv-hint">（过期后不可转化）</text>
          </text>
        </view>
        <view v-if="quote.remark" class="kv">
          <text class="kv-label">备注</text>
          <text class="kv-value">{{ quote.remark }}</text>
        </view>
        <view v-if="convertedOrderId" class="kv">
          <text class="kv-label">转化结果</text>
          <text class="kv-value link" @click="openConvertedOrder">
            出货单 #{{ convertedOrderId }} ›
          </text>
        </view>
      </view>

      <view class="section-head row-between">
        <text class="section-title">明细（{{ (quote.lines ?? []).length }} 行）</text>
        <text class="section-hint">点 SKU 看商品</text>
      </view>

      <view v-if="!(quote.lines ?? []).length" class="card empty-block">
        这张报价单没有明细行。请回到 Web 端检查单据数据。
      </view>

      <view v-else class="list">
        <view
          v-for="line in quote.lines"
          :key="line.id"
          class="card line-card"
          :data-line-no="line.lineNo"
          @click="openLineSku(line.skuId)"
        >
          <view class="row-between line-head">
            <text class="line-sku">{{ line.lineNo }}. {{ skuText(line.skuId) }}</text>
            <text class="line-amount">{{ formatFen(line.amount) }}</text>
          </view>
          <view class="row-between line-meta">
            <text class="meta-text">数量 {{ line.qty }}</text>
            <text class="meta-text">{{ formatFen(line.unitPriceAmount) }} / 件</text>
          </view>
          <view class="row-between line-meta">
            <text class="meta-text">折扣 {{ (line.discountRate / 100).toFixed(0) }}% </text>
            <text class="meta-text">小计 {{ formatFen(line.amount) }}</text>
          </view>
        </view>
      </view>

      <view v-if="actionError" class="inline-error" data-testid="quote-action-error">
        <text class="block-text">{{ actionError }}</text>
      </view>

      <view v-if="availableActions.length" class="action-card card">
        <text class="action-title">报价单流转</text>
        <text class="action-desc">
          动作按单据状态机给出；「转为出货单」只有已审核且未过期的报价单可用，
          转化会新建出货单并把报价单置为已完成。
        </text>

        <view v-if="availableActions.includes('convert')" class="form-row">
          <text class="form-label">转化仓库</text>
          <picker
            class="picker-flex"
            mode="selector"
            :range="warehouseOptions"
            range-key="name"
            :value="warehouseIndex"
            :disabled="actionLoading"
            @change="onWarehouseChange"
          >
            <view class="picker-value">
              <text>{{ warehouseName(warehouseIndex) }}</text>
              <text class="picker-arrow">⌄</text>
            </view>
          </picker>
        </view>
        <view v-if="availableActions.includes('convert')" class="form-hint">
          选具体仓库则该仓出库；选「自动分仓」时按仓库优先级选仓、必要时跨仓拆单。
          当前仓库：{{ convertWarehouseId ? warehouseText(Number(convertWarehouseId)) : '自动' }}
        </view>

        <view class="action-row">
          <button
            v-for="action in availableActions"
            :key="action"
            class="action-btn"
            :class="{ 'action-danger': ACTION_META[action].danger }"
            hover-class="none"
            :disabled="actionLoading"
            :data-action="action"
            @click="runAction(action)"
          >
            {{ actionLoading ? '处理中…' : ACTION_META[action].label }}
          </button>
        </view>
      </view>
      <view v-else-if="actionHint" class="card action-note">
        <text class="action-note-text">{{ actionHint }}</text>
      </view>

      <text v-if="dictWarning" class="dict-warning">{{ dictWarning }}</text>
    </template>
  </view>
</template>

<style scoped>
.detail {
  padding-top: 20rpx;
}

.hero-card {
  padding: 28rpx;
}

.order-no {
  flex: 1;
  min-width: 0;
  font-size: 32rpx;
  font-weight: 700;
  color: var(--c-text);
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
  margin-top: 12rpx;
  font-size: 23rpx;
  color: var(--c-text-2);
}

.hero-grid {
  display: flex;
  margin-top: 22rpx;
  padding: 18rpx 0;
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
  margin-top: 8rpx;
  font-size: 32rpx;
  font-weight: 700;
  color: var(--c-text);
}

.hero-value.amount {
  font-size: 26rpx;
  color: var(--c-warn);
}

.kv {
  display: flex;
  justify-content: space-between;
  margin-top: 14rpx;
  font-size: 24rpx;
}

.kv-label {
  color: var(--c-text-3);
}

.kv-value {
  flex: 1;
  min-width: 0;
  margin-left: 20rpx;
  text-align: right;
  color: var(--c-text);
}

.kv-hint {
  color: var(--c-warn);
  font-size: 21rpx;
}

.link {
  color: var(--c-brand-text);
  font-weight: 600;
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
  padding: 22rpx 24rpx;
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
  font-size: 25rpx;
  font-weight: 700;
  color: var(--c-warn);
}

.line-meta {
  margin-top: 4rpx;
}

.meta-text {
  font-size: 22rpx;
  color: var(--c-text-2);
}

.inline-error {
  margin-top: 18rpx;
  padding: 20rpx 24rpx;
  background: var(--c-danger-wash);
  border-radius: 14rpx;
  font-size: 23rpx;
  color: var(--c-danger);
}

.block-text {
  display: block;
  line-height: 1.5;
}

.block-btn {
  width: 240rpx;
  margin: 24rpx auto 0;
}

.action-card {
  margin-top: 22rpx;
  padding: 26rpx;
}

.action-title {
  display: block;
  font-size: 28rpx;
  font-weight: 700;
  color: var(--c-text);
}

.action-desc {
  display: block;
  margin-top: 10rpx;
  font-size: 23rpx;
  color: var(--c-text-2);
  line-height: 1.55;
}

.form-row {
  display: flex;
  align-items: center;
  margin-top: 20rpx;
}

.form-label {
  width: 160rpx;
  font-size: 24rpx;
  color: var(--c-text-2);
}

.picker-flex {
  flex: 1;
  min-width: 0;
}

.picker-value {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14rpx 20rpx;
  background: var(--c-fill);
  border-radius: 12rpx;
  font-size: 24rpx;
  color: var(--c-text);
}

.picker-arrow {
  color: var(--c-text-4);
}

.form-hint {
  display: block;
  margin-top: 12rpx;
  font-size: 21rpx;
  color: var(--c-text-3);
  line-height: 1.5;
}

.action-row {
  display: flex;
  flex-wrap: wrap;
  gap: 14rpx;
  margin-top: 24rpx;
}

.action-btn {
  flex: 1;
  min-width: 200rpx;
  height: 78rpx;
  line-height: 78rpx;
  margin: 0;
  padding: 0 16rpx;
  border-radius: 12rpx;
  background: var(--c-brand);
  color: #ffffff;
  font-size: 25rpx;
  font-weight: 600;
}

.action-btn[disabled] {
  opacity: 0.6;
}

.action-danger {
  background: var(--c-surface);
  color: var(--c-danger);
  border: 1rpx solid var(--c-danger-line);
}

.action-note {
  margin-top: 22rpx;
  padding: 24rpx;
}

.action-note-text {
  font-size: 23rpx;
  color: var(--c-text-2);
  line-height: 1.55;
}

.dict-warning {
  display: block;
  margin-top: 22rpx;
  text-align: center;
  font-size: 21rpx;
  color: var(--c-text-4);
}
</style>
