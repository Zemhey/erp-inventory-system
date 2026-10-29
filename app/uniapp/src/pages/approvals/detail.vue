<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import {
  approvalApi,
  iamApi,
  type ApprovalInstance,
  type ApprovalStepInfo,
  type IamUser,
} from '@/api/client'
import { session, hasPermission } from '@/stores/session'
import { useTheme } from '@/composables/useTheme'
import {
  approvalStatusMeta,
  canWithdraw,
  isPendingForMe,
  stepStatusMeta,
} from '@/utils/approvalStatus'
import { BRAND_CONFIRM, DANGER_CONFIRM } from '@/utils/dialog'
import { errorMessage } from '@/utils/error'
import { formatDateTime } from '@/utils/format'
import { formatFen } from '@/utils/money'
import { ensureLoggedIn } from '@/utils/routeGuard'
import { safe } from '@/utils/safe'

/**
 * 审批详情与流转（FR-APS-02）：同意 / 驳回 / 撤回 / 转办 / 接办。
 *
 * ★ 五个动作各用各的权限码（ApprovalController 的类注释是这么写的）：
 *   approve / reject / transfer / accept-transfer 要 aps:approve，
 *   withdraw 要 aps:withdraw 且**只有发起人本人**才走得通（权限码是必要不充分条件）。
 *   而 aps:approve 只是第一道门：能不能审**这一节点**，还要看节点的 approverPermission，
 *   否则一个「能审批」的人就能审所有节点，金额阈值触发上级就失去意义。
 *   —— 页面照同一口径显示按钮，最终仍由后端拒。
 */

const { themeClass } = useTheme()

const BIZ_TYPE_LABELS: Record<string, string> = {
  PURCHASE_ORDER: '进货单',
  SALES_ORDER: '出货单',
  RETURN_ORDER: '退货单',
}

const instanceId = ref(0)
const instance = ref<ApprovalInstance | null>(null)
const loaded = ref(false)
const loading = ref(false)
const error = ref('')

const remark = ref('')
const actionLoading = ref(false)
const actionError = ref('')

const transferTarget = ref('')
const showTransferPicker = ref(false)
const transferCandidates = ref<IamUser[]>([])
const transferLoadError = ref('')

const myUserId = ref<number | null>(null)

const statusMeta = computed(() => approvalStatusMeta(instance.value?.status))

const currentStep = computed<ApprovalStepInfo | null>(() => {
  const current = instance.value
  if (!current) return null
  return current.steps.find((step) => step.stepNo === current.currentStep) ?? null
})

/** 当前节点的资格：这是「我能不能点同意」的真正判据，不是 aps:approve 一个码 */
const holdsNodePermission = computed(
  () => Boolean(currentStep.value) && hasPermission(currentStep.value?.approverPermission ?? ''),
)

const inFlight = computed(() => Boolean(instance.value) && statusMeta.value.inFlight)

const canDecide = computed(
  () =>
    Boolean(instance.value) &&
    (instance.value?.status === 'PENDING' || instance.value?.status === 'TRANSFERRED') &&
    hasPermission('aps:approve') &&
    holdsNodePermission.value,
)

const canAccept = computed(
  () => Boolean(instance.value) && instance.value?.status === 'TRANSFERRED' && isPendingForMe(instance.value, hasPermission),
)

const canSelfWithdraw = computed(() =>
  instance.value ? canWithdraw(instance.value, myUserId.value, hasPermission) : false,
)

const permissionHint = computed(() => {
  const current = instance.value
  if (!current) return ''
  if (!hasPermission('aps:approve')) {
    return '当前账号没有 aps:approve，只能查看审批进度。'
  }
  if (current.status !== 'PENDING' && current.status !== 'TRANSFERRED') return ''
  if (currentStep.value && !holdsNodePermission.value) {
    return `当前节点「${currentStep.value.name}」要求权限 ${currentStep.value.approverPermission}，你的账号不具备，不能由你审批。`
  }
  return ''
})

function bizTypeLabel(bizType: string): string {
  return BIZ_TYPE_LABELS[bizType] ?? bizType
}

async function load(): Promise<void> {
  if (!instanceId.value) {
    error.value = '缺少审批实例 ID，请从待办列表进入。'
    loaded.value = true
    return
  }
  if (loading.value) return

  loading.value = true
  error.value = ''
  const result = await safe(approvalApi.get(instanceId.value))
  if (result.ok) instance.value = result.value
  else {
    instance.value = null
    error.value = errorMessage(result.error)
  }
  loaded.value = true
  loading.value = false
}

async function resolveMyUserId(): Promise<void> {
  if (!hasPermission('iam:read') || !session.username) return
  const result = await safe(iamApi.listUsers({ keyword: session.username, page: 1, size: 20 }))
  if (!result.ok) return
  const match = result.value.items.find((user) => user.username === session.username)
  if (match) myUserId.value = match.id
}

function runDecision(kind: 'approve' | 'reject' | 'withdraw'): void {
  const current = instance.value
  if (!current || actionLoading.value) return

  const copy =
    kind === 'approve'
      ? { title: '同意审批', content: '同意后本节点通过；若还有下一节点会继续流转。确定同意吗？', color: BRAND_CONFIRM }
      : kind === 'reject'
        ? { title: '驳回审批', content: '驳回是终态：这张单据要改内容重走，会生成一条新的审批记录。确定驳回吗？', color: DANGER_CONFIRM }
        : { title: '撤回审批', content: '撤回是发起人收回自己提交的审批，终态。确定撤回吗？', color: DANGER_CONFIRM }

  uni.showModal({
    title: copy.title,
    content: copy.content,
    confirmColor: copy.color,
    success: (modal) => {
      if (modal.confirm) void doDecision(kind)
    },
  })
}

async function doDecision(kind: 'approve' | 'reject' | 'withdraw'): Promise<void> {
  const current = instance.value
  if (!current || actionLoading.value) return

  actionLoading.value = true
  actionError.value = ''

  const trimmed = remark.value.trim() || undefined
  const call =
    kind === 'approve'
      ? approvalApi.approve(current.id, trimmed)
      : kind === 'reject'
        ? approvalApi.reject(current.id, trimmed)
        : approvalApi.withdraw(current.id, trimmed)

  const result = await safe(call)
  if (result.ok) {
    instance.value = result.value
    remark.value = ''
    uni.showToast({
      title: kind === 'approve' ? '已同意' : kind === 'reject' ? '已驳回' : '已撤回',
      icon: 'success',
    })
  } else {
    actionError.value = errorMessage(result.error)
  }
  actionLoading.value = false
}

function acceptTransfer(): void {
  const current = instance.value
  if (!current || actionLoading.value) return
  uni.showModal({
    title: '接办审批',
    content: '接办后这条审批回到你的待办，由你给出结论。确定接办吗？',
    success: (modal) => {
      if (modal.confirm) void doAccept()
    },
  })
}

async function doAccept(): Promise<void> {
  const current = instance.value
  if (!current || actionLoading.value) return
  actionLoading.value = true
  actionError.value = ''
  const result = await safe(approvalApi.acceptTransfer(current.id))
  if (result.ok) {
    instance.value = result.value
    uni.showToast({ title: '已接办', icon: 'success' })
  } else {
    actionError.value = errorMessage(result.error)
  }
  actionLoading.value = false
}

/** 转办目标必须自己具备 aps:approve，否则等于把审批甩给没有资格的人、绕开 RBAC。 */
async function openTransferPicker(): Promise<void> {
  actionError.value = ''
  transferLoadError.value = ''

  if (!hasPermission('iam:read')) {
    showTransferPicker.value = true
    transferLoadError.value = '当前账号没有 iam:read，拿不到候选清单，请直接填写对方用户 ID。'
    return
  }
  if (transferCandidates.value.length) {
    showTransferPicker.value = true
    return
  }

  actionLoading.value = true
  const result = await safe(iamApi.listUsers({ page: 1, size: 200 }))
  actionLoading.value = false
  if (result.ok) {
    transferCandidates.value = result.value.items.filter(
      (user) =>
        user.enabled &&
        user.id !== myUserId.value &&
        (user.permissions.includes('aps:approve') || user.permissions.includes('aps:*')),
    )
    if (result.value.total > result.value.items.length) {
      transferLoadError.value = `账号超过 ${result.value.items.length} 个，清单不完整；可直接填 ID。`
    }
  } else {
    transferLoadError.value = errorMessage(result.error)
  }
  showTransferPicker.value = true
}

function chooseTarget(user: IamUser): void {
  transferTarget.value = String(user.id)
  showTransferPicker.value = false
}

function onTargetChange(event: unknown): void {
  transferTarget.value = String((event as { detail?: { value?: unknown } })?.detail?.value ?? '')
}

function confirmTransfer(): void {
  const current = instance.value
  if (!current || actionLoading.value) return
  const targetId = Number(transferTarget.value)
  if (!Number.isInteger(targetId) || targetId <= 0) {
    actionError.value = `转办目标用户 ID 无效：「${transferTarget.value}」。`
    return
  }
  uni.showModal({
    title: '转办审批',
    content: `把当前节点转给用户 #${targetId}，对方需具备 aps:approve，接办后才回到待办。确定转办吗？`,
    success: (modal) => {
      if (modal.confirm) void doTransfer(targetId)
    },
  })
}

async function doTransfer(targetId: number): Promise<void> {
  const current = instance.value
  if (!current || actionLoading.value) return

  actionLoading.value = true
  actionError.value = ''
  const result = await safe(
    approvalApi.transfer(current.id, {
      targetUserId: targetId,
      remark: remark.value.trim() || undefined,
    }),
  )
  if (result.ok) {
    instance.value = result.value
    transferTarget.value = ''
    remark.value = ''
    uni.showToast({ title: '已转办', icon: 'success' })
  } else {
    actionError.value = errorMessage(result.error)
  }
  actionLoading.value = false
}

function onRemarkChange(event: unknown): void {
  remark.value = String((event as { detail?: { value?: unknown } })?.detail?.value ?? '')
}

function openDocument(): void {
  const current = instance.value
  if (!current) return
  if (current.bizType === 'SALES_ORDER') {
    uni.navigateTo({ url: `/pages/sales/detail?id=${current.bizId}` })
    return
  }
  if (current.bizType === 'PURCHASE_ORDER') {
    uni.navigateTo({ url: `/pages/purchase/detail?id=${current.bizId}` })
    return
  }
  uni.showToast({ title: '退货单移动端暂不可查看', icon: 'none' })
}

onLoad((options: Record<string, string> | undefined) => {
  instanceId.value = Number(options?.id ?? 0)
})

onShow(() => {
  if (!ensureLoggedIn()) return
  void resolveMyUserId()
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
  <view class="page detail" :class="themeClass">
    <view v-if="loading && !loaded" class="card loading-block">正在读取审批实例…</view>

    <view v-else-if="error && !instance" class="card error-block">
      <text class="block-text">{{ error }}</text>
      <button class="btn-ghost block-btn" hover-class="none" @click="load">重试</button>
    </view>

    <template v-else-if="instance">
      <view class="card hero-card">
        <view class="row-between">
          <text class="order-no">{{ bizTypeLabel(instance.bizType) }} {{ instance.bizRefNo }}</text>
          <text class="status-tag" :class="`atone-${statusMeta.tone}`">{{ statusMeta.label }}</text>
        </view>
        <text class="status-desc">{{ statusMeta.description }}</text>

        <view class="hero-grid">
          <view class="hero-cell">
            <text class="hero-label">审批金额</text>
            <text class="hero-value amount">{{ formatFen(instance.amount) }}</text>
          </view>
          <view class="hero-cell">
            <text class="hero-label">当前节点</text>
            <text class="hero-value">{{ instance.currentStep }}/{{ instance.steps.length }}</text>
          </view>
        </view>

        <view class="kv">
          <text class="kv-label">审批实例</text>
          <text class="kv-value">#{{ instance.id }} · 流程 #{{ instance.flowId }}</text>
        </view>
        <view class="kv">
          <text class="kv-label">发起人</text>
          <text class="kv-value">
            用户 #{{ instance.initiatorId }}
            <text v-if="myUserId !== null && instance.initiatorId === myUserId" class="me-tag">（我）</text>
          </text>
        </view>
        <view class="kv">
          <text class="kv-label">来源单据</text>
          <text class="kv-value link" @click="openDocument">#{{ instance.bizId }} ›</text>
        </view>
      </view>

      <view class="section-head row-between">
        <text class="section-title">节点轨迹（{{ instance.steps.length }}）</text>
        <text class="section-hint">阈值 0 表示必经</text>
      </view>

      <view class="timeline">
        <view
          v-for="step in instance.steps"
          :key="step.stepNo"
          class="card step-card"
          :class="{ 'step-current': step.stepNo === instance.currentStep }"
          :data-step-no="step.stepNo"
        >
          <view class="row-between step-head">
            <text class="step-name">{{ step.stepNo }}. {{ step.name }}</text>
            <text class="step-tag" :class="`atone-${stepStatusMeta(step.status).tone}`">
              {{ stepStatusMeta(step.status).label }}
            </text>
          </view>
          <view class="row-between step-meta">
            <text class="meta-text">需要权限 {{ step.approverPermission }}</text>
            <text class="meta-text">{{ step.approveMode }} · {{ step.requiredApprovals }} 人</text>
          </view>
          <view class="row-between step-meta">
            <text class="meta-text">阈值 {{ formatFen(step.thresholdAmount) }}</text>
            <text class="meta-text">
              已同意 {{ (step.approvedBy ?? []).length }} 人
            </text>
          </view>
          <view v-if="step.decidedAt" class="step-decided">
            <text class="meta-text">
              结论人 #{{ step.decisionBy }} · {{ formatDateTime(step.decidedAt) }}
            </text>
          </view>
          <text v-if="step.remark" class="step-remark">意见：{{ step.remark }}</text>
        </view>
      </view>

      <view v-if="actionError" class="inline-error approval-error" data-testid="approval-error">
        <text class="block-text">{{ actionError }}</text>
      </view>

      <view v-if="canDecide || canSelfWithdraw || canAccept" class="card form-card">
        <text class="form-title">处理审批</text>
        <text class="form-desc">
          意见可选，最长 200 字，会留在节点轨迹里；驳回时它是关键信息。
        </text>

        <view class="form-row">
          <text class="form-label">审批意见</text>
          <input
            class="form-input remark-input"
            :value="remark"
            maxlength="200"
            placeholder="可留空"
            :disabled="actionLoading"
            @input="onRemarkChange"
          />
        </view>

        <button
          v-if="canAccept"
          class="btn-primary act-btn act-accept"
          hover-class="none"
          data-action="accept-transfer"
          :disabled="actionLoading"
          @click="acceptTransfer"
        >
          接办这条转办
        </button>

        <view v-if="canDecide" class="btn-row">
          <button
            class="btn-primary act-btn act-approve"
            hover-class="none"
            data-action="approve"
            :disabled="actionLoading"
            @click="runDecision('approve')"
          >
            同意
          </button>
          <button
            class="act-btn act-reject"
            hover-class="none"
            data-action="reject"
            :disabled="actionLoading"
            @click="runDecision('reject')"
          >
            驳回
          </button>
        </view>

        <view v-if="canDecide" class="transfer-block">
          <text class="transfer-title">转办</text>
          <view class="form-row">
            <text class="form-label">目标用户</text>
            <input
              class="form-input transfer-target"
              type="number"
              :value="transferTarget"
              placeholder="填用户 ID，或点右侧选人"
              :disabled="actionLoading"
              @input="onTargetChange"
            />
            <text class="picker-link" @click="openTransferPicker">选人</text>
          </view>
          <text v-if="transferLoadError" class="transfer-hint">{{ transferLoadError }}</text>
          <button
            class="act-btn act-transfer"
            hover-class="none"
            data-action="transfer"
            :disabled="actionLoading || !transferTarget"
            @click="confirmTransfer"
          >
            转办给该用户
          </button>
        </view>

        <button
          v-if="canSelfWithdraw"
          class="act-btn act-withdraw"
          hover-class="none"
          data-action="withdraw"
          :disabled="actionLoading"
          @click="runDecision('withdraw')"
        >
          撤回我发起的审批
        </button>
      </view>

      <view v-else-if="permissionHint" class="card action-note">
        <text class="action-note-text">{{ permissionHint }}</text>
      </view>
      <view v-else-if="statusMeta.terminal" class="card action-note" data-testid="approval-terminal">
        <text class="action-note-text">
          这条审批已是「{{ statusMeta.label }}」终态，历史不可改写。
          {{
            instance.status === 'REJECTED'
              ? '单据退回修改后重新提交，会生成一条新的审批实例。'
              : ''
          }}
        </text>
      </view>

      <view v-if="showTransferPicker" class="mask" @click="showTransferPicker = false">
        <view class="sheet" @click.stop>
          <text class="sheet-title">可接办的账号（需 aps:approve）</text>
          <view v-if="!transferCandidates.length" class="sheet-empty">
            {{ transferLoadError || '没有查到具备 aps:approve 的账号，请直接填用户 ID。' }}
          </view>
          <scroll-view v-else scroll-y class="sheet-scroll">
            <view
              v-for="user in transferCandidates"
              :key="user.id"
              class="sheet-item"
              @click="chooseTarget(user)"
            >
              <text class="sheet-name">{{ user.displayName }}（{{ user.username }}）</text>
              <text class="sheet-id">#{{ user.id }}</text>
            </view>
          </scroll-view>
          <button class="btn-ghost sheet-close" hover-class="none" @click="showTransferPicker = false">
            关闭
          </button>
        </view>
      </view>
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
  font-size: 30rpx;
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

/* ★ 审批专用色板 atone-*（与单据页 tone-* 不共用），见 utils/approvalStatus.ts */
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
  font-size: 28rpx;
  font-weight: 700;
  color: var(--c-text);
}

.hero-value.amount {
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

.me-tag {
  color: var(--c-info-text);
  font-weight: 600;
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

.timeline {
  display: flex;
  flex-direction: column;
  gap: 14rpx;
}

.step-card {
  padding: 22rpx 24rpx;
  border-left: 6rpx solid var(--c-line-strong);
}

.step-current {
  border-left-color: var(--c-brand);
  background: var(--c-fill);
}

.step-head {
  margin-bottom: 10rpx;
}

.step-name {
  flex: 1;
  min-width: 0;
  font-size: 26rpx;
  font-weight: 600;
  color: var(--c-text);
}

.step-tag {
  flex-shrink: 0;
  margin-left: 14rpx;
  padding: 4rpx 16rpx;
  border-radius: 999rpx;
  font-size: 20rpx;
  font-weight: 600;
}

.step-meta {
  margin-top: 4rpx;
}

.meta-text {
  font-size: 22rpx;
  color: var(--c-text-2);
}

.step-decided {
  margin-top: 8rpx;
}

.step-remark {
  display: block;
  margin-top: 10rpx;
  padding-top: 10rpx;
  border-top: 1rpx solid var(--c-line);
  font-size: 22rpx;
  color: var(--c-text-2);
}

.inline-error {
  margin-top: 16rpx;
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

.form-card {
  margin-top: 22rpx;
  padding: 26rpx;
}

.form-title {
  display: block;
  font-size: 28rpx;
  font-weight: 700;
  color: var(--c-text);
}

.form-desc {
  display: block;
  margin-top: 10rpx;
  font-size: 23rpx;
  color: var(--c-text-2);
  line-height: 1.55;
}

.form-row {
  display: flex;
  align-items: center;
  margin-top: 18rpx;
}

.form-label {
  width: 160rpx;
  font-size: 24rpx;
  color: var(--c-text-2);
}

.form-input {
  flex: 1;
  min-width: 0;
  height: 74rpx;
  padding: 0 20rpx;
  background: var(--c-fill);
  border-radius: 12rpx;
  font-size: 25rpx;
  color: var(--c-text);
}

.btn-row {
  display: flex;
  gap: 14rpx;
  margin-top: 22rpx;
}

.act-btn {
  flex: 1;
  height: 80rpx;
  line-height: 80rpx;
  margin: 0;
  padding: 0 16rpx;
  border-radius: 12rpx;
  font-size: 26rpx;
  font-weight: 600;
}

.act-approve {
  background: var(--c-brand);
  color: var(--c-on-brand);
}

.act-reject {
  background: var(--c-surface);
  color: var(--c-danger);
  border: 1rpx solid var(--c-danger-line);
}

.act-accept {
  width: 100%;
  margin-top: 22rpx;
  background: var(--c-success);
  color: var(--c-on-brand);
}

.act-withdraw {
  width: 100%;
  margin-top: 18rpx;
  background: var(--c-surface);
  color: var(--c-text-2);
  border: 1rpx solid var(--c-line-strong);
}

.transfer-block {
  margin-top: 22rpx;
  padding-top: 18rpx;
  border-top: 1rpx solid var(--c-line);
}

.transfer-title {
  display: block;
  font-size: 25rpx;
  font-weight: 700;
  color: var(--c-text);
}

.picker-link {
  margin-left: 16rpx;
  font-size: 23rpx;
  color: var(--c-brand-text);
}

.transfer-hint {
  display: block;
  margin-top: 10rpx;
  font-size: 21rpx;
  color: var(--c-warn);
  line-height: 1.5;
}

.act-transfer {
  width: 100%;
  margin-top: 16rpx;
  background: var(--c-accent-wash);
  color: var(--c-accent-text);
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

.mask {
  position: fixed;
  left: 0;
  right: 0;
  top: 0;
  bottom: 0;
  background: var(--c-scrim);
  display: flex;
  align-items: flex-end;
  z-index: 90;
}

.sheet {
  width: 100%;
  padding: 28rpx;
  background: var(--c-surface);
  border-radius: 28rpx 28rpx 0 0;
}

.sheet-title {
  display: block;
  font-size: 27rpx;
  font-weight: 700;
  color: var(--c-text);
}

.sheet-empty {
  margin-top: 18rpx;
  font-size: 23rpx;
  color: var(--c-text-3);
  line-height: 1.5;
}

.sheet-scroll {
  max-height: 520rpx;
  margin-top: 12rpx;
}

.sheet-item {
  display: flex;
  justify-content: space-between;
  padding: 20rpx 4rpx;
  border-bottom: 1rpx solid var(--c-line);
}

.sheet-name {
  font-size: 25rpx;
  color: var(--c-text);
}

.sheet-id {
  font-size: 23rpx;
  color: var(--c-text-3);
}

.sheet-close {
  width: 100%;
  height: 78rpx;
  margin-top: 20rpx;
  font-size: 25rpx;
}
</style>
