<script setup lang="ts">
import { computed, ref } from 'vue'
import { onPullDownRefresh, onReachBottom, onShow } from '@dcloudio/uni-app'
import { approvalApi, iamApi, type ApprovalInstance, type IamUser } from '@/api/client'
import { hasPermission, session } from '@/stores/session'
import { approvalStatusMeta, isPendingForMe } from '@/utils/approvalStatus'
import { errorMessage } from '@/utils/error'
import { formatFen } from '@/utils/money'
import { ensureLoggedIn } from '@/utils/routeGuard'
import { safe } from '@/utils/safe'

/**
 * 待我审批（FR-APS-02）。
 *
 * ★★ 后端 `GET /approvals` 只有 status / bizType 两个过滤参数，**没有「按当前人」**
 *   （ApprovalController:96-112；逐节点资格在应用层按节点 approverPermission 判）。
 *   所以「待我审批」只能在客户端这样推：实例在飞（PENDING / TRANSFERRED）
 *   且我有当前节点的 approverPermission。这只决定列表显隐，不是安全边界 ——
 *   真点下去能不能同意，后端 403 说话。
 *
 * ★ 状态色板用的是 approvalStatus.ts 的审批七态，不是单据七态：
 *   同一个 APPROVED 字符串在这里是「审批通过」，在单据侧是「已审核」，两套语义不能串色。
 */

const BIZ_TYPE_LABELS: Record<string, string> = {
  PURCHASE_ORDER: '进货单',
  SALES_ORDER: '出货单',
  RETURN_ORDER: '退货单',
}

/** 后端 status 过滤值；'' = 不带该参数 */
const STATUS_OPTIONS = [
  { value: 'PENDING', label: '等待审批' },
  { value: 'TRANSFERRED', label: '已转办' },
  { value: 'APPROVED', label: '审批通过' },
  { value: 'REJECTED', label: '审批驳回' },
  { value: '', label: '全部' },
]

const PAGE_SIZE = 20
/** 「待我审批」是一次取一批再本地筛，超过就明说，不假装是全量 */
const MINE_BATCH = 50

const allowed = computed(() => hasPermission('aps:read'))

const mineOnly = ref(true)
const statusFilter = ref('PENDING')

const instances = ref<ApprovalInstance[]>([])
const total = ref(0)
const page = ref(0)
const loaded = ref(false)
const loading = ref(false)
const error = ref('')
const capped = ref(false)

const myUserId = ref<number | null>(null)

const hasMore = computed(() => page.value * PAGE_SIZE < total.value)
const listAllowed = computed(() => allowed.value)

function bizTypeLabel(bizType: string): string {
  return BIZ_TYPE_LABELS[bizType] ?? bizType
}

function currentStepName(instance: ApprovalInstance): string {
  const step = instance.steps.find((item) => item.stepNo === instance.currentStep)
  return step ? step.name : `节点 ${instance.currentStep}`
}

/** 转办后谁该接：TRANSFERRED 且我是当前节点资格人时给一个接办入口 */
function needsMyAccept(instance: ApprovalInstance): boolean {
  return instance.status === 'TRANSFERRED' && isPendingForMe(instance, hasPermission)
}

async function loadMine(): Promise<void> {
  loading.value = true
  error.value = ''
  const [pending, transferred] = await Promise.all([
    safe(approvalApi.list({ status: 'PENDING', page: 1, size: MINE_BATCH })),
    safe(approvalApi.list({ status: 'TRANSFERRED', page: 1, size: MINE_BATCH })),
  ])

  if (!pending.ok && !transferred.ok) {
    error.value = errorMessage(pending.error ?? transferred.error)
    instances.value = []
    total.value = 0
    loaded.value = true
    loading.value = false
    return
  }

  const merged: ApprovalInstance[] = []
  const seen = new Set<number>()
  for (const settled of [pending, transferred]) {
    if (!settled.ok) continue
    for (const instance of settled.value.items) {
      if (seen.has(instance.id)) continue
      seen.add(instance.id)
      merged.push(instance)
    }
    capped.value = capped.value || settled.value.total > settled.value.items.length
  }

  instances.value = merged
    .filter((instance) => isPendingForMe(instance, hasPermission))
    // 金额大的先看：待办列表的排序是可用性取舍，不是业务规则
    .sort((left, right) => right.amount - left.amount)
  total.value = instances.value.length
  page.value = 1
  loaded.value = true
  loading.value = false
}

async function loadPage(reset = true): Promise<void> {
  if (loading.value && reset) return
  if (!reset && !hasMore.value) return

  loading.value = true
  if (reset) {
    error.value = ''
    loaded.value = false
  }

  const nextPage = reset ? 1 : page.value + 1
  const result = await safe(
    approvalApi.list({
      status: statusFilter.value || undefined,
      page: nextPage,
      size: PAGE_SIZE,
    }),
  )

  if (result.ok) {
    instances.value = reset
      ? result.value.items
      : [
          ...instances.value,
          ...result.value.items.filter(
            (incoming) => !instances.value.some((existing) => existing.id === incoming.id),
          ),
        ]
    total.value = result.value.total
    page.value = result.value.page
  } else {
    if (reset) {
      instances.value = []
      total.value = 0
      page.value = 0
    }
    error.value = errorMessage(result.error)
  }

  loaded.value = true
  loading.value = false
}

async function load(reset = true): Promise<void> {
  capped.value = false
  if (mineOnly.value) {
    if (!reset) return
    await loadMine()
    return
  }
  await loadPage(reset)
}

/** 自己的 user id：/auth/me 不返回 id，只能拿 iam:read 反查；查不到就交给后端判撤回。 */
async function resolveMyUserId(): Promise<void> {
  if (!hasPermission('iam:read') || !session.username) return
  const result = await safe(iamApi.listUsers({ keyword: session.username, page: 1, size: 20 }))
  if (!result.ok) return
  const match = result.value.items.find((user: IamUser) => user.username === session.username)
  if (match) myUserId.value = match.id
}

function selectStatus(value: string): void {
  if (statusFilter.value === value) return
  statusFilter.value = value
  void load(true)
}

function toggleMine(): void {
  mineOnly.value = !mineOnly.value
  if (mineOnly.value) statusFilter.value = 'PENDING'
  void load(true)
}

function openDetail(instance: ApprovalInstance): void {
  uni.navigateTo({ url: `/pages/approvals/detail?id=${instance.id}` })
}

function openDocument(instance: ApprovalInstance): void {
  if (instance.bizType === 'SALES_ORDER') {
    uni.navigateTo({ url: `/pages/sales/detail?id=${instance.bizId}` })
    return
  }
  if (instance.bizType === 'PURCHASE_ORDER') {
    uni.navigateTo({ url: `/pages/purchase/detail?id=${instance.bizId}` })
    return
  }
  uni.showToast({ title: '退货单移动端暂不可查看', icon: 'none' })
}

onShow(() => {
  if (!ensureLoggedIn()) return
  if (!listAllowed.value) return
  void resolveMyUserId()
  void load(true)
})

onPullDownRefresh(() => {
  void (async () => {
    await load(true)
    uni.stopPullDownRefresh()
  })()
})

onReachBottom(() => {
  if (mineOnly.value) return
  if (!loading.value && hasMore.value) void load(false)
})
</script>

<template>
  <view class="page approvals">
    <view v-if="!listAllowed" class="card notice">
      <text class="notice-title">当前账号没有审批读权限</text>
      <text class="notice-text">
        本页依赖 aps:read。请联系管理员分配权限；前端判断只影响入口展示，后端仍会做最终校验。
      </text>
    </view>

    <template v-else>
      <view class="card mode-card">
        <view class="row-between">
          <view>
            <text class="mode-title">待我审批</text>
            <text class="mode-sub">按当前节点的审批权限筛出，不是后端过滤条件</text>
          </view>
          <view class="switch" :class="{ on: mineOnly }" @click="toggleMine">
            <view class="switch-dot" />
          </view>
        </view>
        <text v-if="mineOnly" class="mode-note">
          后端只提供按状态查询，「待我审批」是把在飞实例按节点权限本地筛出来的；
          自己发起的那条也可以在这里撤回。
        </text>
      </view>

      <view v-if="!mineOnly" class="chips">
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
        <text class="summary-text">
          {{ mineOnly ? `待我处理 ${instances.length} 条` : `${statusFilter ? approvalStatusMeta(statusFilter).label : '全部'}审批 ${total} 条` }}
        </text>
        <text v-if="myUserId" class="summary-text">我的账号 #{{ myUserId }}</text>
        <text v-else class="summary-text">无 iam:read，撤回资格由后端判</text>
      </view>

      <view v-if="capped && mineOnly" class="card cap-note">
        <text class="cap-text">
          在飞审批超过 {{ MINE_BATCH }} 条，本地只筛了前 {{ MINE_BATCH }} 条。
          关掉「待我审批」按状态翻页看全量。
        </text>
      </view>

      <view v-if="loading && !loaded" class="card loading-block">正在读取审批待办…</view>

      <view v-else-if="error && !instances.length" class="card error-block">
        <text class="block-text">{{ error }}</text>
        <button class="btn-ghost block-btn" hover-class="none" @click="load(true)">重试</button>
      </view>

      <view v-else-if="!instances.length" class="card empty-block">
        {{
          mineOnly
            ? '没有需要你处理的审批。当前节点要求你有对应的审批权限码，缺权限的条目不会出现在这里。'
            : '还没有审批实例。审批在单据侧提交后进入这里。'
        }}
      </view>

      <view v-else class="list">
        <view
          v-for="instance in instances"
          :key="instance.id"
          class="card approval-card"
          :data-biz-ref-no="instance.bizRefNo"
          @click="openDetail(instance)"
        >
          <view class="row-between approval-head">
            <text class="approval-doc">{{ bizTypeLabel(instance.bizType) }} {{ instance.bizRefNo }}</text>
            <text class="status-tag" :class="`atone-${approvalStatusMeta(instance.status).tone}`">
              {{ approvalStatusMeta(instance.status).label }}
            </text>
          </view>

          <view class="row-between approval-meta">
            <text class="meta-text">实例 #{{ instance.id }}</text>
            <text class="meta-text amount">{{ formatFen(instance.amount) }}</text>
          </view>

          <view class="step-line">
            <text class="step-label">当前节点</text>
            <text class="step-name">{{ currentStepName(instance) }}</text>
            <text class="step-index">{{ instance.currentStep }}/{{ instance.steps.length }}</text>
          </view>

          <view class="row-between approval-foot">
            <text class="link" @click.stop="openDocument(instance)">看单据 ›</text>
            <text v-if="needsMyAccept(instance)" class="accept-flag">等你接办</text>
            <text class="arrow">进入审批 ›</text>
          </view>
        </view>
      </view>

      <view v-if="loaded && !mineOnly" class="list-footer">
        <button
          v-if="hasMore || loading"
          class="btn-ghost load-more"
          hover-class="none"
          :disabled="loading"
          @click="load(false)"
        >
          {{ loading ? '加载中…' : `加载更多（${instances.length} / ${total}）` }}
        </button>
        <text v-else-if="instances.length" class="footer-note">已全部加载，共 {{ total }} 条</text>

        <view v-if="error && instances.length" class="inline-error">
          <text class="block-text">{{ error }}</text>
          <text class="inline-retry" @click="load(false)">重试</text>
        </view>
      </view>
    </template>
  </view>
</template>

<style scoped>
.approvals {
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

.mode-card {
  padding: 24rpx;
}

.mode-title {
  display: block;
  font-size: 28rpx;
  font-weight: 700;
  color: #1c2230;
}

.mode-sub {
  display: block;
  margin-top: 6rpx;
  font-size: 21rpx;
  color: #8a90a2;
}

.mode-note {
  display: block;
  margin-top: 14rpx;
  padding-top: 14rpx;
  border-top: 1rpx solid #f0f2f6;
  font-size: 21rpx;
  color: #6e7585;
  line-height: 1.55;
}

.switch {
  width: 84rpx;
  height: 44rpx;
  border-radius: 999rpx;
  background: #dfe3ea;
  padding: 4rpx;
  display: flex;
  flex-shrink: 0;
}

.switch.on {
  background: #1f3a63;
  justify-content: flex-end;
}

.switch-dot {
  width: 36rpx;
  height: 36rpx;
  border-radius: 50%;
  background: #ffffff;
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
  background: #e9edf3;
  color: #586176;
  font-size: 24rpx;
}

.chip.active {
  background: #1f3a63;
  color: #ffffff;
  font-weight: 600;
}

.summary {
  margin: 6rpx 4rpx 14rpx;
}

.summary-text {
  font-size: 23rpx;
  color: #8a90a2;
}

.cap-note {
  padding: 20rpx 24rpx;
  margin-bottom: 16rpx;
}

.cap-text {
  font-size: 22rpx;
  color: #a86a1f;
  line-height: 1.5;
}

.list {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}

.approval-card {
  padding: 24rpx;
}

.approval-head {
  margin-bottom: 12rpx;
}

.approval-doc {
  flex: 1;
  min-width: 0;
  font-size: 27rpx;
  font-weight: 700;
  color: #1c2230;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ★ 审批专用色板（atone-*）：与单据页的 tone-* 分开的类名，防止两套状态机串色 */
.status-tag {
  flex-shrink: 0;
  margin-left: 14rpx;
  padding: 4rpx 16rpx;
  border-radius: 999rpx;
  font-size: 21rpx;
  font-weight: 600;
}

.atone-wait {
  background: #e8eefb;
  color: #2c4f8a;
}

.atone-handover {
  background: #f0ecfb;
  color: #5b4a91;
}

.atone-pass {
  background: #e9f5ef;
  color: #2f7d5b;
}

.atone-refuse {
  background: #fdf0ec;
  color: #b4462f;
}

.atone-quiet {
  background: #f1f2f5;
  color: #8a90a2;
}

.approval-meta {
  margin-top: 4rpx;
}

.meta-text {
  font-size: 23rpx;
  color: #6e7585;
}

.meta-text.amount {
  font-weight: 700;
  color: #b4702f;
}

.step-line {
  display: flex;
  align-items: center;
  margin-top: 14rpx;
  padding: 14rpx 18rpx;
  background: #f7f9fc;
  border-radius: 12rpx;
}

.step-label {
  font-size: 21rpx;
  color: #8a90a2;
}

.step-name {
  flex: 1;
  min-width: 0;
  margin-left: 14rpx;
  font-size: 23rpx;
  color: #2b3245;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.step-index {
  font-size: 21rpx;
  color: #586176;
}

.approval-foot {
  margin-top: 16rpx;
  padding-top: 14rpx;
  border-top: 1rpx solid #f0f2f6;
}

.link {
  font-size: 23rpx;
  color: #2c4066;
}

.accept-flag {
  font-size: 22rpx;
  font-weight: 700;
  color: #5b4a91;
}

.arrow {
  font-size: 23rpx;
  color: #2c4066;
}

.list-footer {
  margin-top: 20rpx;
}

.load-more {
  width: 100%;
  height: 80rpx;
  font-size: 26rpx;
}

.footer-note {
  display: block;
  text-align: center;
  font-size: 22rpx;
  color: #a2a8b6;
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
  background: #fdf1ee;
  border-radius: 14rpx;
  text-align: center;
  font-size: 23rpx;
  color: #9c3a26;
}

.inline-retry {
  margin-left: 12rpx;
  font-weight: 600;
  text-decoration: underline;
}
</style>
