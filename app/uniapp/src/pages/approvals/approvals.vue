<script setup lang="ts">
import { computed, ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { approvalApi, iamApi, skeletonApi, type ApprovalInstance, type IamUser, type TodoItem, type TodoType } from '@/api/client'
import { useTheme } from '@/composables/useTheme'
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
 *
 * ★ 分页：关掉「待我审批」按状态看全量时，一页 20 条翻页；
 *   开着「待我审批」是一次取一批（MINE_BATCH）再本地筛，没有后端分页，
 *   所以那一类不挂翻页条，只在超出批量时用文案说清楚。
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

const { themeClass } = useTheme()

const allowed = computed(() => hasPermission('aps:read'))

// ─────────────────────────── 待办中心：四类 tab ───────────────────────────
// 待审批沿用原有的"按节点权限本地筛 + 详情页"逻辑；其余三类直接走 /todos?type=。
// 这样 Web 端的四类待办在移动端一一对应，不会让人看出"只同步了一半"。

const TABS: { value: TodoType; label: string }[] = [
  { value: 'approval', label: '待审批' },
  { value: 'purchase-receipt', label: '待收货' },
  { value: 'sales-ship', label: '待发货' },
  { value: 'low-stock', label: '库存预警' },
]

const activeTab = ref<TodoType>('approval')

const todoItems = ref<TodoItem[]>([])
const todoLoaded = ref(false)
const todoLoading = ref(false)
const todoError = ref('')

async function loadTodos(): Promise<void> {
  if (activeTab.value === 'approval') return
  todoLoading.value = true
  todoError.value = ''
  const result = await safe(skeletonApi.todos(activeTab.value, 50))
  if (result.ok) {
    todoItems.value = result.value.items
  } else {
    todoItems.value = []
    todoError.value = errorMessage(result.error)
  }
  todoLoaded.value = true
  todoLoading.value = false
}

/** 切 tab：待审批回到原逻辑（回第 1 页），其余三类拉对应待办。 */
function selectTodoTab(value: TodoType): void {
  if (activeTab.value === value) return
  activeTab.value = value
  if (value === 'approval') {
    reloadApprovalList()
  } else {
    void loadTodos()
  }
}

/**
 * 待办卡片点击：只有目标详情页真实存在才跳，否则只读。
 * 待收货 → 进货单详情、待发货 → 出货单详情（pages.json 里都有）；
 * 库存预警没有对应详情页，卡片只读，不造死链。
 */
function openTodo(item: TodoItem): void {
  if (activeTab.value === 'purchase-receipt') {
    uni.navigateTo({ url: `/pages/purchase/detail?id=${item.id}` })
  } else if (activeTab.value === 'sales-ship') {
    uni.navigateTo({ url: `/pages/sales/detail?id=${item.id}` })
  }
}

const mineOnly = ref(true)
const statusFilter = ref('PENDING')

const instances = ref<ApprovalInstance[]>([])
const total = ref(0)
const page = ref(1)
const loaded = ref(false)
const loading = ref(false)
const error = ref('')
const capped = ref(false)

const myUserId = ref<number | null>(null)

let queued = false
/** 上一次真正加载成功的页码：请求失败时把翻页条退回到这里，不停在没数据的页上。 */
let goodPage = 1

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

async function loadPage(): Promise<void> {
  loading.value = true
  error.value = ''

  const result = await safe(
    approvalApi.list({
      status: statusFilter.value || undefined,
      page: page.value,
      size: PAGE_SIZE,
    }),
  )

  if (result.ok) {
    instances.value = result.value.items
    total.value = result.value.total
    // 以后端回的值为准：状态换了以后，请求的那一页可能已经不存在
    page.value = result.value.page
    goodPage = result.value.page
  } else {
    instances.value = []
    page.value = goodPage
    error.value = errorMessage(result.error)
  }

  loaded.value = true
  loading.value = false
}

/**
 * 审批 tab 的入口：按当前模式取「我正在看的这一页」。
 * 「待我审批」那一路是本地筛一批，没有后端分页，翻页条不挂在那一侧。
 */
async function loadApprovalList(): Promise<void> {
  if (loading.value) {
    // 筛选/开关在请求飞行中被再次修改：记住"还欠一次"，本次结束后补跑
    queued = true
    return
  }

  capped.value = false
  if (mineOnly.value) {
    await loadMine()
  } else {
    await loadPage()
  }

  if (queued) {
    queued = false
    void loadApprovalList()
  }
}

function goApprovalPage(next: number): void {
  page.value = next
  void loadApprovalList()
}

/** 换筛选条件/换标签一律回到第 1 页：停在第 7 页换状态，多半只会看到空列表。 */
function reloadApprovalList(): void {
  page.value = 1
  void loadApprovalList()
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
  reloadApprovalList()
}

function toggleMine(): void {
  mineOnly.value = !mineOnly.value
  if (mineOnly.value) statusFilter.value = 'PENDING'
  reloadApprovalList()
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
  // 停在原来那一页刷新：从审批详情页退回来不该被弹回第 1 页
  if (activeTab.value === 'approval') {
    void loadApprovalList()
  } else {
    void loadTodos()
  }
})

onPullDownRefresh(() => {
  void (async () => {
    if (activeTab.value === 'approval') {
      // 下拉是"刷新我正在看的这一页"，翻页请走下面的翻页条
      await loadApprovalList()
    } else {
      await loadTodos()
    }
    uni.stopPullDownRefresh()
  })()
})
</script>

<template>
  <view class="page approvals" :class="themeClass">
    <view v-if="!listAllowed" class="card notice">
      <text class="notice-title">当前账号没有审批读权限</text>
      <text class="notice-text">
        本页依赖 aps:read。请联系管理员分配权限；前端判断只影响入口展示，后端仍会做最终校验。
      </text>
    </view>

    <view v-else class="approvals-body">
      <view class="tabs todo-tabs">
        <view
          v-for="t in TABS"
          :key="t.value"
          class="tab"
          :class="{ active: activeTab === t.value }"
          @click="selectTodoTab(t.value)"
        >{{ t.label }}</view>
      </view>

      <template v-if="activeTab === 'approval'">
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
          {{
            mineOnly
              ? `待我处理 ${instances.length} 条`
              : `${statusFilter ? approvalStatusMeta(statusFilter).label : '全部'}审批 ${total} 条 · 本页 ${instances.length} 条`
          }}
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
        <button class="btn-ghost block-btn" hover-class="none" @click="loadApprovalList">重试</button>
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

      <view v-if="loaded && !mineOnly && total" class="pager-wrap">
        <list-pager
          :page="page"
          :size="PAGE_SIZE"
          :total="total"
          :loading="loading"
          unit="条"
          @change="goApprovalPage"
        />
        <view v-if="error && instances.length" class="inline-error">
          <text class="block-text">{{ error }}</text>
          <text class="inline-retry" @click="loadApprovalList">重试</text>
        </view>
      </view>
      </template>

      <template v-else>
        <view v-if="todoLoading && !todoLoaded" class="card loading-block">正在读取待办…</view>

        <view v-else-if="todoError && !todoItems.length" class="card error-block">
          <text class="block-text">{{ todoError }}</text>
          <button class="btn-ghost block-btn" hover-class="none" @click="loadTodos">重试</button>
        </view>

        <view v-else-if="!todoItems.length" class="card empty-block">
          {{ activeTab === 'low-stock' ? '没有低于下限的库存，所有 SKU 都在安全水位之上。' : '这一类暂时没有待办。' }}
        </view>

        <view v-else class="list">
          <view
            v-for="item in todoItems"
            :key="item.id"
            class="card todo-card"
            :class="{ clickable: activeTab === 'purchase-receipt' || activeTab === 'sales-ship' }"
            @click="openTodo(item)"
          >
            <!-- 待审批：保留跳详情；待收货/待发货：跳对应单据详情；库存预警：只读 -->
            <view class="row-between todo-head">
              <text class="todo-title">
                {{ activeTab === 'low-stock' ? (item.productName || item.productCode || `SKU#${item.skuId}`) : (item.no || '单号缺失') }}
              </text>
              <text v-if="activeTab === 'low-stock'" class="status-tag atone-quiet">预警</text>
              <text v-else class="status-tag atone-quiet">{{ item.status }}</text>
            </view>

            <view class="row-between todo-meta">
              <text class="meta-text">{{ item.partyName || item.warehouseName || '-' }}</text>
              <text v-if="item.bizDate" class="meta-text">{{ item.bizDate }}</text>
            </view>

            <view v-if="activeTab === 'low-stock'" class="todo-lowstock">
              <text class="meta-text">下限 {{ item.minLevel ?? '-' }} · 可用 {{ item.availableQty ?? 0 }} · 实存 {{ item.onHandQty ?? 0 }}</text>
              <text class="meta-text">{{ item.variant || '' }} · {{ item.warehouseName || '' }}</text>
            </view>
            <view v-else class="row-between todo-meta">
              <text class="meta-text">{{ item.warehouseName || '-' }}</text>
              <text class="meta-text amount">{{ formatFen(item.totalAmount ?? 0) }} · {{ item.totalQty ?? 0 }} 件</text>
            </view>

            <text v-if="activeTab === 'purchase-receipt' || activeTab === 'sales-ship'" class="arrow">查看单据 ›</text>
          </view>
        </view>
      </template>
    </view>
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
  color: var(--c-danger);
}

.notice-text {
  display: block;
  margin-top: 10rpx;
  font-size: 25rpx;
  color: var(--c-text-2);
  line-height: 1.55;
}

.mode-card {
  padding: 24rpx;
}

.mode-title {
  display: block;
  font-size: 28rpx;
  font-weight: 700;
  color: var(--c-text);
}

.mode-sub {
  display: block;
  margin-top: 6rpx;
  font-size: 21rpx;
  color: var(--c-text-3);
}

.mode-note {
  display: block;
  margin-top: 14rpx;
  padding-top: 14rpx;
  border-top: 1rpx solid var(--c-line);
  font-size: 21rpx;
  color: var(--c-text-2);
  line-height: 1.55;
}

.switch {
  width: 84rpx;
  height: 44rpx;
  border-radius: 999rpx;
  background: var(--c-fill-strong);
  padding: 4rpx;
  display: flex;
  flex-shrink: 0;
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

.cap-note {
  padding: 20rpx 24rpx;
  margin-bottom: 16rpx;
}

.cap-text {
  font-size: 22rpx;
  color: var(--c-warn);
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
  color: var(--c-text);
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
  background: var(--c-info-wash);
  color: var(--c-info-text);
}

.atone-handover {
  background: var(--c-accent-wash);
  color: var(--c-accent-text);
}

.atone-pass {
  background: var(--c-ok-wash);
  color: var(--c-success);
}

.atone-refuse {
  background: var(--c-danger-wash);
  color: var(--c-danger);
}

.atone-quiet {
  background: var(--c-fill-strong);
  color: var(--c-text-3);
}

.approval-meta {
  margin-top: 4rpx;
}

.meta-text {
  font-size: 23rpx;
  color: var(--c-text-2);
}

.meta-text.amount {
  font-weight: 700;
  color: var(--c-warn);
}

.step-line {
  display: flex;
  align-items: center;
  margin-top: 14rpx;
  padding: 14rpx 18rpx;
  background: var(--c-fill);
  border-radius: 12rpx;
}

.step-label {
  font-size: 21rpx;
  color: var(--c-text-3);
}

.step-name {
  flex: 1;
  min-width: 0;
  margin-left: 14rpx;
  font-size: 23rpx;
  color: var(--c-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.step-index {
  font-size: 21rpx;
  color: var(--c-text-2);
}

.approval-foot {
  margin-top: 16rpx;
  padding-top: 14rpx;
  border-top: 1rpx solid var(--c-line);
}

.link {
  font-size: 23rpx;
  color: var(--c-brand-text);
}

.accept-flag {
  font-size: 22rpx;
  font-weight: 700;
  color: var(--c-accent-text);
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

/* ── 待办中心四 tab（与库存页双 tab 同一套视觉语言，但这里是横向四个）── */
.todo-tabs {
  display: flex;
  padding: 6rpx;
  margin-bottom: 20rpx;
  background: var(--c-fill-strong);
  border-radius: 18rpx;
}

.todo-tabs .tab {
  flex: 1;
  height: 72rpx;
  line-height: 72rpx;
  text-align: center;
  border-radius: 14rpx;
  font-size: 25rpx;
  color: var(--c-text-2);
}

.todo-tabs .tab.active {
  background: var(--c-surface);
  color: var(--c-brand-text);
  font-weight: 600;
  box-shadow: 0 4rpx 14rpx var(--c-shadow);
}

.todo-card {
  padding: 24rpx;
}

.todo-card.clickable:active {
  background: var(--c-fill);
}

.todo-head {
  margin-bottom: 12rpx;
}

.todo-title {
  flex: 1;
  min-width: 0;
  font-size: 27rpx;
  font-weight: 700;
  color: var(--c-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.todo-meta {
  margin-top: 6rpx;
}

.todo-lowstock {
  display: flex;
  flex-direction: column;
  gap: 4rpx;
  margin-top: 12rpx;
  padding-top: 12rpx;
  border-top: 1rpx solid var(--c-line);
}

.amount {
  font-weight: 700;
  color: var(--c-warn);
}
</style>
