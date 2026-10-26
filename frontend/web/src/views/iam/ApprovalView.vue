<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  approvalApi,
  iamApi,
  type ApprovalFlowView,
  type ApprovalInstanceView,
  type ApprovalStepView,
  type UserView,
} from '@/api/client'
import { useSessionStore } from '@/stores/session'

/**
 * 审批工作台（FR-APS-02）。**只做"单据审批"这一种流程**，不做通用 BPMN。
 *
 * ★ 三个必须在前端体现的语义（都是后端已经强制、这里只是让用户看得见）：
 *
 * 1. **审批通过 ≠ 单据状态被改**。审批通过只把实例置 APPROVED；
 *    单据还要由单据页（进货/出货/退货）自己走一次"审核"。
 *    因此本页在通过后提示的是「审批已通过，请回到单据页完成审核」，
 *    而不是「已完成」。这条提示是两套状态机边界在 UI 上的投影。
 *
 * 2. **金额阈值决定走几级**。小额单据只有 1 个节点，大额会多出一个财务节点。
 *    页面上把被跳过的节点也列出来（灰色 SKIPPED），
 *    用户才知道"这次为什么没走财务"，而不是以为流程丢了。
 *
 * 3. **当前节点要告诉你需要什么权限**。审批人不是按角色名选的，是按权限码
 *    （approverPermission）判定的。把权限码显示出来，
 *    用户看到"无权处理"时能直接找管理员要对应的权限，而不是猜。
 */

const session = useSessionStore()

const loading = ref(false)
const instances = ref<ApprovalInstanceView[]>([])
const flows = ref<ApprovalFlowView[]>([])
const statusFilter = ref('')

const canApprove = computed(() => session.hasPermission('aps:approve'))

const statusLabels: Record<string, string> = {
  DRAFT: '草稿',
  PENDING: '待审批',
  TRANSFERRED: '已转办',
  APPROVED: '已通过',
  REJECTED: '已驳回',
  WITHDRAWN: '已撤回',
  CANCELLED: '已取消',
}

const statusTags: Record<string, string> = {
  DRAFT: 'info',
  PENDING: 'warning',
  TRANSFERRED: 'warning',
  APPROVED: 'success',
  REJECTED: 'danger',
  WITHDRAWN: 'info',
  CANCELLED: 'info',
}

function statusTag(s: string): 'success' | 'warning' | 'danger' | 'info' {
  return (statusTags[s] ?? 'info') as 'success' | 'warning' | 'danger' | 'info'
}

function stepTag(s: string): 'success' | 'warning' | 'danger' | 'info' {
  if (s === 'APPROVED') return 'success'
  if (s === 'PENDING') return 'warning'
  if (s === 'REJECTED') return 'danger'
  return 'info'
}

function stepLabel(s: string): string {
  return (
    { DEFINED: '未启用', PENDING: '待处理', APPROVED: '已同意', REJECTED: '已驳回', SKIPPED: '已跳过（未达阈值）' }[
      s
    ] ?? s
  )
}

/** 当前停在哪个节点（终态时没有）。 */
function currentNode(row: ApprovalInstanceView): ApprovalStepView | undefined {
  return row.steps.find((s) => s.stepNo === row.currentStep && s.status === 'PENDING')
}

async function load() {
  loading.value = true
  try {
    const [list, flowList] = await Promise.all([
      approvalApi.list(statusFilter.value || undefined),
      approvalApi.flows(),
    ])
    instances.value = list.items
    flows.value = flowList.items
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载审批列表失败')
  } finally {
    loading.value = false
  }
}

async function decide(row: ApprovalInstanceView, action: 'approve' | 'reject') {
  const verb = action === 'approve' ? '同意' : '驳回'
  let remark = ''
  try {
    const input = await ElMessageBox.prompt(
      `${verb}「${row.bizRefNo}」的第 ${row.currentStep} 级审批，可填审批意见：`,
      `${verb}审批`,
      { confirmButtonText: verb, cancelButtonText: '取消', inputPlaceholder: '选填' },
    )
    remark = input.value ?? ''
  } catch {
    return
  }
  try {
    await approvalApi.decide(row.id, action, remark)
    ElMessage.success(
      action === 'approve'
        ? '审批已通过。注意：这不会改单据状态，请回到单据页完成「审核」。'
        : '已驳回，单据需退回修改后重新发起审批。',
    )
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  }
}

async function withdraw(row: ApprovalInstanceView) {
  try {
    await ElMessageBox.confirm(`确定撤回「${row.bizRefNo}」的审批吗？`, '撤回审批', {
      confirmButtonText: '撤回',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await approvalApi.withdraw(row.id)
    ElMessage.success('已撤回')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '撤回失败')
  }
}

async function toggleFlow(flow: ApprovalFlowView) {
  try {
    await approvalApi.setFlowEnabled(flow.id, !flow.enabled)
    ElMessage.success(flow.enabled ? '流程已停用' : '流程已启用')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  }
}

// ─────────────────────────── 详情抽屉（GET /approvals/{id}） ───────────────────────────

/**
 * 审批详情。
 *
 * ★ 走 `approvalApi.detail()` 重新取一次，而不是复用列表里的行：
 *   实例的节点状态在别人同意/转办后就会变，列表是拉取那一刻的快照。
 *   审批是个多人动作，"看着旧状态点按钮"是这里最容易出事的地方。
 */
const drawerVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<ApprovalInstanceView | null>(null)

async function openDetail(row: ApprovalInstanceView) {
  detail.value = null
  drawerVisible.value = true
  await loadDetail(row.id)
}

async function loadDetail(id: number) {
  detailLoading.value = true
  try {
    detail.value = await approvalApi.detail(id)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载审批详情失败')
    drawerVisible.value = false
  } finally {
    detailLoading.value = false
  }
}

const bizTypeLabels: Record<string, string> = {
  PURCHASE_ORDER: '进货单',
  SALES_ORDER: '出货单',
  RETURN_ORDER: '退货单',
}

function bizTypeLabel(code: string | undefined): string {
  return (bizTypeLabels[code ?? ''] ?? code ?? '') as string
}

/** 详情里"我现在能做什么"：接办只看状态，同意/驳回还要后端按节点权限判。 */
const detailCanApprove = computed(
  () => canApprove.value && detail.value?.status === 'PENDING',
)
const detailCanAccept = computed(
  () => canApprove.value && detail.value?.status === 'TRANSFERRED',
)

// ─────────────────────────── 转办 / 接受转办 ───────────────────────────

/**
 * 转办（POST /approvals/{id}/transfer，体 `{targetUserId, remark}`）。
 *
 * 后端两道校验（ApprovalController + ApprovalApplicationService），界面上都得提前解释清楚：
 * - 发起人当前账号必须有**该节点**的 approverPermission，否则 APS_NOT_APPROVER；
 * - 目标账号必须有 `aps:approve`（控制器用 ActorContext 校验），否则同样拒绝 ——
 *   这条是为了堵住"把审批转给完全没审批权限的人"绕开 RBAC。
 * 所以选人时尽量让用户看得见对方的角色，而不只是一个 id。
 */
const transferVisible = ref(false)
const transferBusy = ref(false)
const transferTarget = reactive<{ instance: ApprovalInstanceView | null; userId: number | null; remark: string }>(
  { instance: null, userId: null, remark: '' },
)
const userOptions = ref<UserView[]>([])
const userLoading = ref(false)

/** 选人要靠 /iam/users（权限码 iam:read）。没有该权限时退回手填用户 id。 */
const canPickUser = computed(() => session.hasPermission('iam:read'))

async function searchUsers(keyword: string) {
  if (!canPickUser.value) return
  userLoading.value = true
  try {
    const data = await iamApi.users(keyword || undefined, 1, 20)
    userOptions.value = data.items
  } catch {
    userOptions.value = []
  } finally {
    userLoading.value = false
  }
}

function openTransfer(row: ApprovalInstanceView) {
  transferTarget.instance = row
  transferTarget.userId = null
  transferTarget.remark = ''
  transferVisible.value = true
  void searchUsers('')
}

/** 抽屉里的「转办」：detail 可能为 null（加载失败/关闭中），这里收窄一次类型。 */
function openTransferFromDetail() {
  if (detail.value) openTransfer(detail.value)
}

/** 抽屉里的同意/驳回：decide() 需要一行实例，同样先收窄。 */
async function decideFromDetail(action: 'approve' | 'reject') {
  if (!detail.value) return
  const id = detail.value.id
  await decide(detail.value, action)
  // decide() 内部会刷新列表；抽屉里那份是旧快照，重新拉一次才看得见新节点状态
  if (drawerVisible.value) await loadDetail(id)
}

/** 接办可能从列表行或详情抽屉发起。 */
async function acceptTransfer(row: ApprovalInstanceView) {
  try {
    await approvalApi.acceptTransfer(row.id)
    ElMessage.success('已接办，该节点现在由你处理')
    await load()
    if (detail.value?.id === row.id) await loadDetail(row.id)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '接办失败')
  }
}

function acceptTransferFromDetail() {
  if (detail.value) void acceptTransfer(detail.value)
}

async function doTransfer() {
  const instance = transferTarget.instance
  if (!instance || !transferTarget.userId) {
    ElMessage.warning('请选择接收转办的账号')
    return
  }
  transferBusy.value = true
  try {
    await approvalApi.transfer(instance.id, transferTarget.userId, transferTarget.remark || undefined)
    ElMessage.success('已转办。对方账号需在审批工作台点「接办」后该节点才回到待处理。')
    transferVisible.value = false
    await load()
    if (detail.value?.id === instance.id) await loadDetail(instance.id)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '转办失败')
  } finally {
    transferBusy.value = false
  }
}

// ─────────────────────────── 发起审批（POST /approvals） ───────────────────────────

/**
 * 手工发起审批。
 *
 * ★ 为什么这个入口必须存在：单据侧只在 `approve()` 前调
 *   {@code ApprovalGate.requireApproved(...)} 做**校验**，并不会替你创建审批实例。
 *   也就是说没有这个入口，一张需要审批的单据根本走不到"有实例可批"的状态。
 *
 * ★★ 金额单位：后端 `amount` 是**分**（与业务域一致，列表按 amount/100 显示为元）。
 *   界面按元录入，提交前 ×100 取整。这里绝不能用财务域的「元」口径直接提交 ——
 *   漏乘 100 会让小额单据跳过审批阈值，是实打实的越权路径。
 */
const submitVisible = ref(false)
const submitBusy = ref(false)
const submitForm = reactive<{ bizType: string; bizId: number | null; bizRefNo: string; amountYuan: number | null }>(
  { bizType: 'PURCHASE_ORDER', bizId: null, bizRefNo: '', amountYuan: null },
)
const canSubmit = computed(() => session.hasPermission('aps:submit'))

function openSubmit() {
  submitForm.bizType = 'PURCHASE_ORDER'
  submitForm.bizId = null
  submitForm.bizRefNo = ''
  submitForm.amountYuan = null
  submitVisible.value = true
}

async function doSubmit() {
  if (!submitForm.bizId || !submitForm.bizRefNo.trim()) {
    ElMessage.warning('请填写单据 id 与单据号')
    return
  }
  if (submitForm.amountYuan === null || Number.isNaN(submitForm.amountYuan)) {
    ElMessage.warning('请填写单据金额（元）')
    return
  }
  submitBusy.value = true
  try {
    const created = await approvalApi.submit({
      bizType: submitForm.bizType,
      bizId: submitForm.bizId,
      bizRefNo: submitForm.bizRefNo.trim(),
      // 元 → 分：整数分，四舍五入兜掉浮点尾差（19.99 元 ≠ 1998.9999 分）
      amount: Math.round(submitForm.amountYuan * 100),
    })
    ElMessage.success(`已发起审批（实例 ${created.id}）`)
    submitVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '发起审批失败')
  } finally {
    submitBusy.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="approval-view">
    <el-card shadow="never">
      <template #header>
        <div class="header">
          <span>审批工作台</span>
          <div class="actions">
            <el-select
              v-model="statusFilter"
              placeholder="全部状态"
              clearable
              style="width: 150px"
              @change="load"
            >
              <el-option
                v-for="(label, key) in statusLabels"
                :key="key"
                :label="label"
                :value="key"
              />
            </el-select>
            <el-button v-if="canSubmit" type="primary" @click="openSubmit">发起审批</el-button>
            <el-button :loading="loading" @click="load">刷新</el-button>
          </div>
        </div>
      </template>

      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="审批通过不会直接改单据状态"
        description="审批只管「这次审批走完了没有」。单据仍需在进货/出货/退货页点「审核」，
由单据侧自己走状态机（那里还有账期锁与红冲规则）。这是两条独立的状态线。"
        style="margin-bottom: 16px"
      />

      <el-table :data="instances" v-loading="loading" border>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="bizRefNo" label="单据号" width="150" />
        <el-table-column prop="bizType" label="单据类型" width="150">
          <template #default="{ row }">{{ bizTypeLabel(row.bizType) }}</template>
        </el-table-column>
        <el-table-column label="金额（元）" width="120">
          <template #default="{ row }">{{ (row.amount / 100).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)">{{ statusLabels[row.status] ?? row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="当前节点" min-width="240">
          <template #default="{ row }">
            <span v-if="currentNode(row)">
              {{ currentNode(row)!.name }}
              <el-tag size="small" type="info">
                需 {{ currentNode(row)!.approverPermission }}
              </el-tag>
            </span>
            <span v-else class="muted">—（已终结）</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="300" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="openDetail(row)">详情</el-button>
            <template v-if="canApprove && (row.status === 'PENDING' || row.status === 'TRANSFERRED')">
              <el-button size="small" type="primary" @click="decide(row, 'approve')">同意</el-button>
              <el-button size="small" type="danger" @click="decide(row, 'reject')">驳回</el-button>
            </template>
            <el-button
              v-if="canApprove && row.status === 'PENDING'"
              size="small"
              @click="openTransfer(row)"
            >
              转办
            </el-button>
            <el-button
              v-if="canApprove && row.status === 'TRANSFERRED'"
              size="small"
              type="primary"
              plain
              @click="acceptTransfer(row)"
            >
              接办
            </el-button>
            <el-button
              v-if="row.status === 'PENDING' || row.status === 'DRAFT'"
              size="small"
              @click="withdraw(row)"
            >
              撤回
            </el-button>
          </template>
        </el-table-column>
        <el-table-column type="expand" width="40">
          <template #default="{ row }">
            <div class="steps">
              <div v-for="s in row.steps" :key="s.stepNo" class="step-row">
                <el-tag :type="stepTag(s.status)" size="small">{{ stepLabel(s.status) }}</el-tag>
                <strong>第 {{ s.stepNo }} 级 · {{ s.name }}</strong>
                <span class="muted">
                  权限 {{ s.approverPermission }} · {{ s.approveMode }}
                  <template v-if="s.requiredApprovals > 1">（需 {{ s.requiredApprovals }} 人）</template>
                  · 阈值 ≥ {{ (s.thresholdAmount / 100).toFixed(2) }} 元
                </span>
                <span class="muted">已同意：{{ s.approvedBy.length }} 人</span>
                <span v-if="s.remark" class="muted">意见：{{ s.remark }}</span>
              </div>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never" style="margin-top: 16px">
      <template #header>审批流定义（只读 · 由迁移种子维护，不在界面改节点）</template>
      <el-table :data="flows" border>
        <el-table-column prop="code" label="编码" width="120" />
        <el-table-column prop="name" label="名称" min-width="180" />
        <el-table-column prop="bizType" label="单据类型" width="160" />
        <el-table-column label="节点" min-width="260">
          <template #default="{ row }">
            <div v-for="s in row.steps" :key="s.stepNo" class="muted">
              第 {{ s.stepNo }} 级 {{ s.name }}（{{ s.approverPermission }}，阈值 ≥
              {{ (s.thresholdAmount / 100).toFixed(2) }} 元）
            </div>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="100">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button size="small" @click="toggleFlow(row)">
              {{ row.enabled ? '停用' : '启用' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 审批详情抽屉：GET /approvals/{id}，节点级信息与动作都在这一处 -->
    <el-drawer v-model="drawerVisible" title="审批详情" size="560px">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="实例">#{{ detail.id }}</el-descriptions-item>
            <el-descriptions-item label="单据">
              {{ bizTypeLabel(detail.bizType) }} {{ detail.bizRefNo }}（bizId {{ detail.bizId }}）
            </el-descriptions-item>
            <el-descriptions-item label="金额">
              {{ (detail.amount / 100).toFixed(2) }} 元
              <span class="muted">（后端按分存储：{{ detail.amount }}）</span>
            </el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag :type="statusTag(detail.status)">
                {{ statusLabels[detail.status] ?? detail.status }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="当前级">
              {{ detail.currentStep || '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="发起人">
              user #{{ detail.initiatorId }}
            </el-descriptions-item>
          </el-descriptions>

          <el-alert
            v-if="detail.status === 'TRANSFERRED'"
            type="warning"
            show-icon
            :closable="false"
            class="mt-12"
            title="该审批已转办：需要你点「接办」，节点才会回到待处理状态"
          />

          <h4 class="detail-subtitle">审批节点</h4>
          <div v-for="s in detail.steps" :key="s.stepNo" class="detail-step">
            <div class="step-row">
              <el-tag :type="stepTag(s.status)" size="small">{{ stepLabel(s.status) }}</el-tag>
              <strong>第 {{ s.stepNo }} 级 · {{ s.name }}</strong>
            </div>
            <div class="muted">
              需权限 {{ s.approverPermission }} · {{ s.approveMode }}
              <template v-if="s.requiredApprovals > 1">（需 {{ s.requiredApprovals }} 人）</template>
              · 阈值 ≥ {{ (s.thresholdAmount / 100).toFixed(2) }} 元
            </div>
            <div class="muted">
              已同意 {{ s.approvedBy.length }} 人
              <template v-if="s.approvedBy.length">（user {{ s.approvedBy.join('、') }}）</template>
              <template v-if="s.decidedAt">
                · 决定人 user {{ s.decisionBy ?? '—' }} @ {{ s.decidedAt.slice(0, 19).replace('T', ' ') }}
              </template>
            </div>
            <div v-if="s.remark" class="step-remark">意见：{{ s.remark }}</div>
          </div>

          <div class="detail-actions">
            <el-button
              v-if="detailCanApprove"
              type="primary"
              @click="decideFromDetail('approve')"
            >
              同意
            </el-button>
            <el-button v-if="detailCanApprove" type="danger" @click="decideFromDetail('reject')">
              驳回
            </el-button>
            <el-button v-if="detailCanApprove" @click="openTransferFromDetail()">转办</el-button>
            <el-button v-if="detailCanAccept" type="primary" plain @click="acceptTransferFromDetail()">
              接办
            </el-button>
            <el-button @click="loadDetail(detail.id)">刷新</el-button>
          </div>
        </template>
      </div>
    </el-drawer>

    <!-- 转办：POST /approvals/{id}/transfer，体 { targetUserId, remark } -->
    <el-dialog v-model="transferVisible" title="转办审批" width="460px">
      <el-alert
        type="info"
        show-icon
        :closable="false"
        class="mb-12"
        title="转办不改变节点要求的权限，只换处理人"
        description="后端会校验：你本人必须有该节点的权限，接收方必须有 aps:approve，
否则一律 APS_NOT_APPROVER 拒绝 —— 这是为了防止把审批转给没有审批权的人来绕开 RBAC。
接收方需在工作台点「接办」后，该节点才回到待处理。"
      />
      <el-form label-position="top">
        <el-form-item label="单据">
          <el-input
            :model-value="`${transferTarget.instance ? bizTypeLabel(transferTarget.instance.bizType) : ''} ${transferTarget.instance?.bizRefNo ?? ''}（第 ${transferTarget.instance?.currentStep ?? ''} 级）`"
            readonly
          />
        </el-form-item>
        <el-form-item label="接收账号">
          <el-select
            v-if="canPickUser"
            v-model="transferTarget.userId"
            filterable
            remote
            :remote-method="searchUsers"
            :loading="userLoading"
            placeholder="按用户名/姓名搜索"
            style="width: 100%"
          >
            <el-option
              v-for="u in userOptions"
              :key="u.id"
              :label="`${u.displayName || u.username}（${u.username}）`"
              :value="u.id"
            />
          </el-select>
          <el-input-number
            v-else
            v-model="transferTarget.userId"
            :min="1"
            :controls="false"
            placeholder="用户 id"
            style="width: 100%"
          />
          <div v-if="!canPickUser" class="muted mt-6">
            当前账号没有 iam:read 权限，不能浏览账号列表，请直接填对方用户 id。
          </div>
        </el-form-item>
        <el-form-item label="转办原因">
          <el-input
            v-model="transferTarget.remark"
            type="textarea"
            :rows="2"
            maxlength="200"
            show-word-limit
            placeholder="选填，最多 200 字"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="transferVisible = false">取消</el-button>
        <el-button type="primary" :loading="transferBusy" @click="doTransfer">确定转办</el-button>
      </template>
    </el-dialog>

    <!-- 发起审批：POST /approvals（单据侧只校验审批是否通过，不会替你建实例） -->
    <el-dialog v-model="submitVisible" title="发起审批" width="460px">
      <el-alert
        type="warning"
        show-icon
        :closable="false"
        class="mb-12"
        title="金额决定走几级，请按单据上的实际金额填写"
        description="审批金额低于节点阈值时该节点会被跳过（SKIPPED）。这里按「元」录入，
提交时换算为分；单据审核时后端会比对本单据是否存在已通过的审批实例。"
      />
      <el-form label-position="top">
        <el-form-item label="单据类型">
          <el-select v-model="submitForm.bizType" style="width: 100%">
            <el-option
              v-for="(label, code) in bizTypeLabels"
              :key="code"
              :label="label"
              :value="code"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="单据 id">
          <el-input-number v-model="submitForm.bizId" :min="1" :controls="false" style="width: 100%" />
        </el-form-item>
        <el-form-item label="单据号">
          <el-input v-model="submitForm.bizRefNo" maxlength="64" placeholder="如 PO202610010001" />
        </el-form-item>
        <el-form-item label="金额（元）">
          <el-input-number v-model="submitForm.amountYuan" :min="0" :precision="2" :controls="false" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="submitVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitBusy" @click="doSubmit">提交审批</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.actions {
  display: flex;
  gap: 8px;
}
.steps {
  padding: 8px 16px;
}
.step-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 4px 0;
  flex-wrap: wrap;
}
.muted {
  color: #909399;
  font-size: 13px;
}
.mb-12 {
  margin-bottom: 12px;
}
.mt-6 {
  margin-top: 6px;
}
.mt-12 {
  margin-top: 12px;
}
.detail-subtitle {
  margin: 18px 0 8px;
  font-size: 14px;
}
.detail-step {
  padding: 8px 0;
  border-bottom: 1px solid #ebeef5;
}
.step-remark {
  margin-top: 2px;
  font-size: 13px;
  color: #303133;
}
.detail-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 16px;
}
</style>
