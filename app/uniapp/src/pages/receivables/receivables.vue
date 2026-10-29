<script setup lang="ts">
import { computed, ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { receivableApi, type Receivable, type ReceivableSummary } from '@/api/client'
import { useDictionary } from '@/composables/useDictionary'
import { useTheme } from '@/composables/useTheme'
import { hasPermission } from '@/stores/session'
import { errorMessage } from '@/utils/error'
import { formatDate } from '@/utils/format'
import { formatFen } from '@/utils/money'
import { ensureLoggedIn } from '@/utils/routeGuard'
import { safe } from '@/utils/safe'

/**
 * 客户应收列表（FR-SAL-07）。
 *
 * 应收是「发货确认」的产物，移动端不手工建应收 —— 只查、只在其上登记收款。
 * 恒等式 `balance = shipped − returned − received` 由数据库 CHECK 兜底，
 * 页面**复述**后端给的四个数，不自己算余额（算出来的那份不是账上的那份）。
 *
 * ★ 列表是领域记录：有 version、**没有** overdue（overdue 只在详情里按当天判定），
 *   所以列表的逾期标记用 dueDate 与今天比，只作提示、不当结论。
 *
 * 分页：一页 20 笔、翻页看下一页。
 */

const PAGE_SIZE = 20

/** status 取值就是 receivable.status 的三个字符串，不另发明 */
const STATUS_OPTIONS = [
  { value: '', label: '全部' },
  { value: 'OPEN', label: '未结清' },
  { value: 'SETTLED', label: '已结清' },
  { value: 'VOIDED', label: '已作废' },
]

const { themeClass } = useTheme()

const allowed = computed(() => hasPermission('sal:read'))
const { loadDictionary, customers, customerText, warning: dictWarning } = useDictionary()

const customerId = ref('')
const statusFilter = ref('OPEN')
const overdueOnly = ref(false)

const rows = ref<Receivable[]>([])
const total = ref(0)
const page = ref(1)
const loaded = ref(false)
const loading = ref(false)
const error = ref('')

const summary = ref<ReceivableSummary | null>(null)
const summaryError = ref('')

let queued = false
/** 上一次真正加载成功的页码：请求失败时把翻页条退回到这里，不停在没数据的页上。 */
let goodPage = 1

const customerOptions = computed(() => [
  { id: '', name: '全部客户' },
  ...customers.value
    .filter((customer) => customer.enabled)
    .map((customer) => ({ id: String(customer.id), name: customer.name })),
])

const customerIndex = computed(() => {
  const index = customerOptions.value.findIndex((option) => option.id === customerId.value)
  return index >= 0 ? index : 0
})

function onCustomerChange(event: unknown): void {
  const detail = (event as { detail?: { value?: unknown } })?.detail
  const raw = detail?.value
  const index = typeof raw === 'string' ? Number.parseInt(raw, 10) : Number(raw)
  const option = customerOptions.value[Number.isNaN(index) ? 0 : index]
  customerId.value = option ? option.id : ''
  reloadRows()
  void loadSummary()
}

function statusLabel(status: string): string {
  if (status === 'OPEN') return '未结清'
  if (status === 'SETTLED') return '已结清'
  if (status === 'VOIDED') return '已作废'
  return status
}

/** 只是提示性判定：列表没有后端给的 overdue，别把它当结论（结论在详情页）。 */
function looksOverdue(row: Receivable): boolean {
  if (row.status !== 'OPEN' || row.balance <= 0 || !row.dueDate) return false
  const due = new Date(row.dueDate)
  if (Number.isNaN(due.getTime())) return false
  return due.getTime() < Date.now()
}

async function loadSummary(): Promise<void> {
  summary.value = null
  summaryError.value = ''
  if (!customerId.value) return

  const result = await safe(receivableApi.summary(Number(customerId.value)))
  if (result.ok) summary.value = result.value
  else summaryError.value = errorMessage(result.error)
}

async function loadRows(): Promise<void> {
  if (!allowed.value) return
  if (loading.value) {
    // 筛选在请求飞行中被再次修改：记住"还欠一次"，本次结束后补跑，避免界面与筛选不一致
    queued = true
    return
  }

  loading.value = true
  error.value = ''

  const result = await safe(
    receivableApi.list({
      customerId: customerId.value ? Number(customerId.value) : undefined,
      status: statusFilter.value || undefined,
      overdue: overdueOnly.value ? true : undefined,
      page: page.value,
      size: PAGE_SIZE,
    }),
  )

  if (result.ok) {
    rows.value = result.value.items
    total.value = result.value.total
    // 以后端回的值为准：筛选条件变了以后，请求的那一页可能已经不存在
    page.value = result.value.page
    goodPage = result.value.page
  } else {
    rows.value = []
    page.value = goodPage
    error.value = errorMessage(result.error)
  }

  loaded.value = true
  loading.value = false

  if (queued) {
    queued = false
    void loadRows()
  }
}

function goRowsPage(next: number): void {
  page.value = next
  void loadRows()
}

/** 换筛选条件一律回到第 1 页：停在第 7 页换客户，多半只会看到空列表。 */
function reloadRows(): void {
  page.value = 1
  void loadRows()
}

function selectStatus(value: string): void {
  if (statusFilter.value === value) return
  statusFilter.value = value
  reloadRows()
}

function toggleOverdue(): void {
  overdueOnly.value = !overdueOnly.value
  reloadRows()
}

function openDetail(row: Receivable): void {
  uni.navigateTo({ url: `/pages/receivables/detail?id=${row.id}` })
}

/**
 * 点客户名 = 把筛选收到这个客户身上，并拉一次汇总。
 * 这是给客户对账用的快捷入口，也避开在手机上驱动下拉选择器。
 */
function pickCustomer(id: number): void {
  customerId.value = String(id)
  reloadRows()
  void loadSummary()
}

onShow(() => {
  if (!ensureLoggedIn()) return
  if (!allowed.value) return
  loadDictionary()
  // 停在原来那一页刷新：从详情页退回来不该被弹回第 1 页
  void loadRows()
  void loadSummary()
})

onPullDownRefresh(() => {
  void (async () => {
    await loadDictionary(true)
    // 下拉是"刷新我正在看的这一页"，翻页请走下面的翻页条
    await loadRows()
    await loadSummary()
    uni.stopPullDownRefresh()
  })()
})
</script>

<template>
  <view class="page receivables" :class="themeClass">
    <view v-if="!allowed" class="card notice">
      <text class="notice-title">当前账号没有应收读权限</text>
      <text class="notice-text">
        本页依赖 sal:read。请联系管理员分配权限；前端判断只影响入口展示，后端仍会做最终校验。
      </text>
    </view>

    <template v-else>
      <view class="card filter-card">
        <view class="form-row">
          <text class="form-label">客户</text>
          <picker
            class="picker-flex"
            mode="selector"
            :range="customerOptions"
            range-key="name"
            :value="customerIndex"
            @change="onCustomerChange"
          >
            <view class="picker-value">
              <text>{{ customerOptions[customerIndex] ? customerOptions[customerIndex].name : '全部客户' }}</text>
              <text class="picker-arrow">⌄</text>
            </view>
          </picker>
        </view>

        <view class="row-between switch-row">
          <text class="form-label">只看逾期</text>
          <view class="switch" :class="{ on: overdueOnly }" @click="toggleOverdue">
            <view class="switch-dot" />
          </view>
        </view>
      </view>

      <view v-if="summary" class="card summary-card" data-testid="customer-summary">
        <view class="row-between">
          <text class="summary-title">{{ customerText(summary.customerId) }}</text>
          <text class="summary-note">发货 − 退货 − 回款 = 余额</text>
        </view>
        <view class="summary-grid">
          <view class="summary-cell">
            <text class="cell-label">出货</text>
            <text class="cell-value">{{ formatFen(summary.shippedAmount) }}</text>
          </view>
          <view class="summary-cell">
            <text class="cell-label">退货</text>
            <text class="cell-value">{{ formatFen(summary.returnedAmount) }}</text>
          </view>
          <view class="summary-cell">
            <text class="cell-label">回款</text>
            <text class="cell-value">{{ formatFen(summary.receivedAmount) }}</text>
          </view>
          <view class="summary-cell">
            <text class="cell-label">余额</text>
            <text class="cell-value balance">{{ formatFen(summary.balance) }}</text>
          </view>
        </view>
        <text class="summary-overdue">其中逾期 {{ formatFen(summary.overdueAmount) }}</text>
      </view>
      <view v-else-if="summaryError" class="card inline-error">{{ summaryError }}</view>

      <view class="chips">
        <view
          v-for="option in STATUS_OPTIONS"
          :key="option.value || 'ALL'"
          class="chip"
          :class="{ active: statusFilter === option.value }"
          @click="selectStatus(option.value)"
        >
          {{ option.label }}
        </view>
      </view>

      <view v-if="loaded" class="summary row-between">
        <text class="summary-text">应收 {{ total }} 笔</text>
        <text class="summary-text">本页 {{ rows.length }} 笔</text>
      </view>

      <view v-if="loading && !loaded" class="card loading-block">正在读取客户应收…</view>

      <view v-else-if="error && !rows.length" class="card error-block">
        <text class="block-text">{{ error }}</text>
        <button class="btn-ghost block-btn" hover-class="none" @click="loadRows">重试</button>
      </view>

      <view v-else-if="!rows.length" class="card empty-block">
        {{
          statusFilter
            ? `没有${statusLabel(statusFilter)}的应收。发货确认后才会产生应收，可切换状态再试。`
            : '还没有应收。发货确认（或出货单直接出货）之后，这里会出现应收记录。'
        }}
      </view>

      <view v-else class="list">
        <view
          v-for="row in rows"
          :key="row.id"
          class="card debt-card"
          :data-receivable-id="row.id"
          :data-shipped="row.shippedAmount"
          :data-balance="row.balance"
          @click="openDetail(row)"
        >
          <view class="row-between debt-head">
            <text class="debt-order">出货单 #{{ row.salesOrderId }}</text>
            <text
              class="status-tag"
              :class="row.status === 'OPEN' ? 'tone-open' : row.status === 'SETTLED' ? 'tone-settled' : 'tone-void'"
            >
              {{ statusLabel(row.status) }}
            </text>
          </view>

          <view class="row-between debt-meta">
            <text class="meta-text link" @click.stop="pickCustomer(row.customerId)">
              {{ customerText(row.customerId) }} ›
            </text>
            <text class="meta-text">业务 {{ formatDate(row.bizDate) }}</text>
          </view>

          <view class="row-between debt-amounts">
            <text class="meta-text">出货 {{ formatFen(row.shippedAmount) }}</text>
            <text class="meta-text">回款 {{ formatFen(row.receivedAmount) }}</text>
          </view>

          <view class="row-between debt-foot">
            <text class="debt-balance">应收余额 {{ formatFen(row.balance) }}</text>
            <text v-if="looksOverdue(row)" class="debt-overdue">到期 {{ formatDate(row.dueDate) }}</text>
            <text v-else class="meta-text">到期 {{ formatDate(row.dueDate) }}</text>
          </view>
        </view>
      </view>

      <view v-if="loaded && total" class="pager-wrap">
        <list-pager
          :page="page"
          :size="PAGE_SIZE"
          :total="total"
          :loading="loading"
          unit="笔"
          @change="goRowsPage"
        />
        <view v-if="error && rows.length" class="inline-error">
          <text class="block-text">{{ error }}</text>
          <text class="inline-retry" @click="loadRows">重试</text>
        </view>
      </view>

      <text v-if="dictWarning" class="dict-warning">{{ dictWarning }}</text>
    </template>
  </view>
</template>

<style scoped>
.receivables {
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

.filter-card {
  padding: 22rpx 24rpx;
}

.form-row {
  display: flex;
  align-items: center;
}

.form-label {
  width: 120rpx;
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

.switch-row {
  margin-top: 18rpx;
}

.switch {
  width: 84rpx;
  height: 44rpx;
  border-radius: 999rpx;
  background: var(--c-fill-strong);
  padding: 4rpx;
  display: flex;
}

.switch.on {
  background: var(--c-brand);
  justify-content: flex-end;
}

.switch-dot {
  width: 36rpx;
  height: 36rpx;
  border-radius: 50%;
  background: var(--c-surface);
}

.summary-card {
  margin-top: 18rpx;
  padding: 24rpx;
}

.summary-title {
  font-size: 27rpx;
  font-weight: 700;
  color: var(--c-text);
}

.summary-note {
  font-size: 21rpx;
  color: var(--c-text-3);
}

.summary-grid {
  display: flex;
  margin-top: 18rpx;
}

.summary-cell {
  flex: 1;
  text-align: center;
}

.cell-label {
  display: block;
  font-size: 21rpx;
  color: var(--c-text-3);
}

.cell-value {
  display: block;
  margin-top: 6rpx;
  font-size: 24rpx;
  font-weight: 600;
  color: var(--c-text);
}

.cell-value.balance {
  color: var(--c-warn);
}

.summary-overdue {
  display: block;
  margin-top: 14rpx;
  font-size: 22rpx;
  color: var(--c-danger);
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin: 20rpx 0;
}

.chip {
  padding: 12rpx 26rpx;
  border-radius: 999rpx;
  background: var(--c-fill-strong);
  color: var(--c-text-2);
  font-size: 24rpx;
}

.chip.active {
  background: var(--c-brand);
  color: var(--c-on-brand);
  font-weight: 600;
}

.summary {
  margin: 6rpx 4rpx 14rpx;
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

.debt-card {
  padding: 24rpx;
}

.debt-head {
  margin-bottom: 12rpx;
}

.debt-order {
  flex: 1;
  min-width: 0;
  font-size: 28rpx;
  font-weight: 700;
  color: var(--c-text);
}

.status-tag {
  flex-shrink: 0;
  margin-left: 14rpx;
  padding: 4rpx 16rpx;
  border-radius: 999rpx;
  font-size: 21rpx;
  font-weight: 600;
}

.tone-open {
  background: var(--c-warn-wash);
  color: var(--c-warn);
}

.tone-settled {
  background: var(--c-ok-wash);
  color: var(--c-success);
}

.tone-void {
  background: var(--c-fill-strong);
  color: var(--c-text-3);
}

.debt-meta,
.debt-amounts {
  margin-top: 6rpx;
}

.meta-text {
  font-size: 23rpx;
  color: var(--c-text-2);
}

.meta-text.link {
  color: var(--c-brand-text);
  font-weight: 600;
}

.debt-foot {
  margin-top: 16rpx;
  padding-top: 14rpx;
  border-top: 1rpx solid var(--c-line);
}

.debt-balance {
  font-size: 25rpx;
  font-weight: 700;
  color: var(--c-warn);
}

.debt-overdue {
  font-size: 22rpx;
  color: var(--c-danger);
  font-weight: 600;
}

.pager-wrap {
  margin-top: 20rpx;
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
