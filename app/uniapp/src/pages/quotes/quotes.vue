<script setup lang="ts">
import { computed, ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { quoteApi, type SalesQuote } from '@/api/client'
import { useDictionary } from '@/composables/useDictionary'
import { useTheme } from '@/composables/useTheme'
import { hasPermission } from '@/stores/session'
import { DOCUMENT_STATUSES, statusMeta } from '@/utils/documentStatus'
import { errorMessage } from '@/utils/error'
import { formatDate } from '@/utils/format'
import { formatFen } from '@/utils/money'
import { ensureLoggedIn } from '@/utils/routeGuard'
import { safe } from '@/utils/safe'

/**
 * 报价单列表（FR-SAL-05）。
 *
 * 状态筛选取值仍来自单据七态镜像：报价单用的就是 DocumentStatus，
 * 移动端不另发明一套状态词。建单留在 Web —— 多行 + SKU 选择 + 折扣是桌面表单。
 *
 * 分页：一页 20 张、翻页看下一页。
 */

const PAGE_SIZE = 20

const { themeClass } = useTheme()

const allowed = computed(() => hasPermission('sal:read'))
const { loadDictionary, customerText, warning: dictWarning } = useDictionary()

const statusFilter = ref('')
const statusFilters = [
  { value: '', label: '全部' },
  ...DOCUMENT_STATUSES.map((value) => ({ value, label: statusMeta(value).label })),
]

const quotes = ref<SalesQuote[]>([])
const total = ref(0)
const page = ref(1)
const loaded = ref(false)
const loading = ref(false)
const error = ref('')

let queued = false
/** 上一次真正加载成功的页码：请求失败时把翻页条退回到这里，不停在没数据的页上。 */
let goodPage = 1

async function loadQuotes(): Promise<void> {
  if (!allowed.value) return
  if (loading.value) {
    // 筛选在请求飞行中被再次修改：记住"还欠一次"，本次结束后补跑，避免界面与筛选不一致
    queued = true
    return
  }

  loading.value = true
  error.value = ''

  const result = await safe(
    quoteApi.list({
      status: statusFilter.value || undefined,
      page: page.value,
      size: PAGE_SIZE,
    }),
  )

  if (result.ok) {
    quotes.value = result.value.items
    total.value = result.value.total
    // 以后端回的值为准：筛选条件变了以后，请求的那一页可能已经不存在
    page.value = result.value.page
    goodPage = result.value.page
  } else {
    quotes.value = []
    page.value = goodPage
    error.value = errorMessage(result.error)
  }

  loaded.value = true
  loading.value = false

  if (queued) {
    queued = false
    void loadQuotes()
  }
}

function goQuotesPage(next: number): void {
  page.value = next
  void loadQuotes()
}

/** 换筛选条件一律回到第 1 页：停在第 7 页换状态，多半只会看到空列表。 */
function reloadQuotes(): void {
  page.value = 1
  void loadQuotes()
}

function selectStatus(value: string): void {
  if (statusFilter.value === value) return
  statusFilter.value = value
  reloadQuotes()
}

function openDetail(quote: SalesQuote): void {
  uni.navigateTo({ url: `/pages/quotes/detail?id=${quote.id}` })
}

/**
 * 是否已转化。判据抄后端 SalesQuote.converted()：COMPLETED 且 convertedOrderId 非空
 * —— 只看状态会把「作废」也算成转化完成。
 */
function converted(quote: SalesQuote): boolean {
  return quote.status === 'COMPLETED' && Boolean(quote.convertedOrderId)
}

/** 只是列表页角标提示；能不能转化的最终判定在后端（含 validUntil 与当天比较）。 */
function expired(quote: SalesQuote): boolean {
  if (!quote.validUntil) return false
  const until = new Date(quote.validUntil)
  if (Number.isNaN(until.getTime())) return false
  return until.getTime() < Date.now()
}

onShow(() => {
  if (!ensureLoggedIn()) return
  if (!allowed.value) return
  loadDictionary()
  // 停在原来那一页刷新：从详情页退回来不该被弹回第 1 页
  void loadQuotes()
})

onPullDownRefresh(() => {
  void (async () => {
    await loadDictionary(true)
    // 下拉是"刷新我正在看的这一页"，翻页请走下面的翻页条
    await loadQuotes()
    uni.stopPullDownRefresh()
  })()
})
</script>

<template>
  <view class="page quotes" :class="themeClass">
    <view v-if="!allowed" class="card notice">
      <text class="notice-title">当前账号没有报价单读权限</text>
      <text class="notice-text">
        本页依赖 sal:read。请联系管理员分配权限；前端判断只影响入口展示，后端仍会做最终校验。
      </text>
    </view>

    <template v-else>
      <view class="chips">
        <view
          v-for="option in statusFilters"
          :key="option.value || 'ALL'"
          class="chip"
          :class="{ active: statusFilter === option.value }"
          @click="selectStatus(option.value)"
        >
          {{ option.label }}
        </view>
      </view>

      <view v-if="loaded" class="summary row-between">
        <text class="summary-text">
          {{ statusFilter ? statusMeta(statusFilter).label : '全部' }}报价单 {{ total }} 张
        </text>
        <text class="summary-text">本页 {{ quotes.length }} 张</text>
      </view>

      <view v-if="loading && !loaded" class="card loading-block">正在读取报价单…</view>

      <view v-else-if="error && !quotes.length" class="card error-block">
        <text class="block-text">{{ error }}</text>
        <button class="btn-ghost block-btn" hover-class="none" @click="loadQuotes">重试</button>
      </view>

      <view v-else-if="!quotes.length" class="card empty-block">
        {{
          statusFilter
            ? `没有${statusMeta(statusFilter).label}的报价单。可以切换状态筛选再试。`
            : '还没有报价单。建单请使用 Web 端，提交与审核后这里会出现流转动作。'
        }}
      </view>

      <view v-else class="list">
        <view
          v-for="quote in quotes"
          :key="quote.id"
          class="card quote-card"
          :data-quote-no="quote.quoteNo"
          @click="openDetail(quote)"
        >
          <view class="row-between quote-head">
            <text class="quote-no">{{ quote.quoteNo }}</text>
            <text class="status-tag" :class="`tone-${statusMeta(quote.status).tone}`">
              {{ statusMeta(quote.status).label }}
            </text>
          </view>

          <view class="row-between quote-meta">
            <text class="meta-text">{{ customerText(quote.customerId) }}</text>
            <text class="meta-text">{{ formatDate(quote.bizDate) }}</text>
          </view>

          <view class="row-between quote-meta">
            <text class="meta-text">有效期至 {{ formatDate(quote.validUntil) }}</text>
            <text class="meta-text">{{ quote.totalQty }} 件 · {{ formatFen(quote.totalAmount) }}</text>
          </view>

          <view class="row-between quote-foot">
            <text v-if="converted(quote)" class="foot-flag foot-done">已转为出货单</text>
            <text v-else-if="expired(quote)" class="foot-flag foot-warn">报价已过期</text>
            <text v-else class="foot-flag">{{ (quote.lines ?? []).length }} 行明细</text>
            <text class="arrow">查看详情 ›</text>
          </view>
        </view>
      </view>

      <view v-if="loaded && total" class="pager-wrap">
        <list-pager
          :page="page"
          :size="PAGE_SIZE"
          :total="total"
          :loading="loading"
          unit="张"
          @change="goQuotesPage"
        />
        <view v-if="error && quotes.length" class="inline-error">
          <text class="block-text">{{ error }}</text>
          <text class="inline-retry" @click="loadQuotes">重试</text>
        </view>
      </view>

      <text v-if="dictWarning" class="dict-warning">{{ dictWarning }}</text>
    </template>
  </view>
</template>

<style scoped>
.quotes {
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

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-bottom: 20rpx;
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

.quote-card {
  padding: 26rpx;
}

.quote-head {
  margin-bottom: 16rpx;
}

.quote-no {
  flex: 1;
  min-width: 0;
  font-size: 29rpx;
  font-weight: 700;
  color: var(--c-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.status-tag {
  flex-shrink: 0;
  margin-left: 14rpx;
  padding: 4rpx 16rpx;
  border-radius: 999rpx;
  font-size: 21rpx;
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

.quote-meta {
  margin-top: 6rpx;
}

.meta-text {
  font-size: 23rpx;
  color: var(--c-text-2);
}

.quote-foot {
  margin-top: 18rpx;
  padding-top: 16rpx;
  border-top: 1rpx solid var(--c-line);
}

.foot-flag {
  font-size: 24rpx;
  color: var(--c-text-2);
}

.foot-done {
  color: var(--c-success);
  font-weight: 600;
}

.foot-warn {
  color: var(--c-warn);
  font-weight: 600;
}

.arrow {
  font-size: 23rpx;
  color: var(--c-brand-text);
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
