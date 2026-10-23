<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  approvalApi,
  type ApprovalFlowView,
  type ApprovalInstanceView,
  type ApprovalStepView,
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
        <el-table-column prop="bizType" label="单据类型" width="150" />
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
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <template v-if="canApprove && (row.status === 'PENDING' || row.status === 'TRANSFERRED')">
              <el-button size="small" type="primary" @click="decide(row, 'approve')">同意</el-button>
              <el-button size="small" type="danger" @click="decide(row, 'reject')">驳回</el-button>
            </template>
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
</style>
