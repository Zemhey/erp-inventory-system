<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fenToYuan,
  mdmApi,
  setApi,
  type DailySettlementResult,
  type MonthlySettlementResult,
  type ReconcileResult,
  type SettlementTotals,
  type Warehouse,
} from '@/api/client'
import { useSessionStore } from '@/stores/session'

/**
 * 结算（FR-SET-01/02/03，计划书 §3.4 / §3.8）。
 *
 * ★ 这一页与其余页面最大的不同：**没有任何"列表查询"可复用**。
 *   日清、月结是写操作（POST），对账虽是 GET 也要 `set:reconcile`。
 *   所以入口权限 `set:read` 只决定"能不能进这一页"，
 *   三个执行按钮各问各的动作码 —— FINANCE 三个都有，VIEWER 一个都没有，
 *   这是 §3.6 职责分离（"财务能结算不能开单"）在前端的唯一落点。
 *
 * ★ 月结是不可逆的：契约 §3.4 明写不提供解锁接口。
 *   所以确认框必须把后果写全（锁账后该期间的单据不能新增/执行/作废），
 *   而不是一个"确定要结算吗？"。文案在 `MONTHLY_CONFIRM`。
 *
 * ★ 数字全部来自后端，前端一个算式都不写：
 *   `netAmount`、`diff` 是后端返回的字段，不在这里相加相减 ——
 *   两处算法迟早分叉，而分叉的后果是"对账永远调不平"。
 *   失败消息（`SET_PERIOD_CLOSED` / `SET_ALREADY_SETTLED` / `SET_RECONCILE_MISMATCH`）
 *   一律原样透出，不改写、不吞掉。
 */

const session = useSessionStore()

const canDaily = computed(() => session.hasPermission('set:daily'))
const canMonthly = computed(() => session.hasPermission('set:monthly'))
const canReconcile = computed(() => session.hasPermission('set:reconcile'))
const readonlyShell = computed(() => !canDaily.value && !canMonthly.value && !canReconcile.value)

const activeTab = ref<'daily' | 'monthly' | 'reconcile'>('daily')

// ─────────────────────────── 仓库字典（只为显示名称） ───────────────────────────

const warehouses = ref<Warehouse[]>([])
/**
 * 名称字典走的是主数据接口（`/warehouses` 要 `mdm:read`），而 V5 给 FINANCE 的只有
 * `rma/set/rpt` 三组码 —— 财务进得来这一页却读不到仓库名。
 * 这里先问权限再决定发不发请求：请求照发只会换来一个 403 红条，
 * 页面反而说不清"为什么没有名字"。（缺口已登记计划书 §6，修法留给 S7 定夺）
 */
const canReadMaster = computed(() => session.hasPermission('mdm:read'))

/** 停用仓库也要能显示名字：这里刻意用 onlyEnabled=false。 */
async function loadWarehouses() {
  if (!canReadMaster.value) {
    warehouses.value = []
    return
  }
  try {
    // 日清结果里的仓库可能是已停用的仓，过滤掉就查不出名字了
    warehouses.value = await mdmApi.listWarehouses(false)
  } catch {
    // 拦截器已弹出后端消息；字典缺失只影响名称显示，退回"仓库#编号"
    warehouses.value = []
  }
}

const warehouseNames = computed(
  () => new Map(warehouses.value.map((w) => [w.id, w.enabled ? w.name : `${w.name}（已停用）`])),
)

function warehouseText(id: number): string {
  return warehouseNames.value.get(id) ?? `仓库#${id}`
}

// ─────────────────────────── 错误处理（原样透出） ───────────────────────────

interface ErrorInfo {
  code: string
  message: string
  details?: Record<string, unknown>
}

function errorInfo(error: unknown): ErrorInfo {
  const e = error as {
    response?: { data?: { code?: string; message?: string; details?: Record<string, unknown> } }
    message?: string
  }
  return {
    code: e?.response?.data?.code ?? '',
    message: e?.response?.data?.message || e?.message || '请求失败',
    details: e?.response?.data?.details,
  }
}

// ─────────────────────────── 日期与账期默认值 ───────────────────────────

function pad(n: number): string {
  return String(n).padStart(2, '0')
}

/** 用本地日期而不是 toISOString：后者按 UTC 取日，东八区早上 8 点前会差一天。 */
function todayText(): string {
  const d = new Date()
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

function thisPeriodText(): string {
  const d = new Date()
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}`
}

function dateTimeText(iso: string | null | undefined): string {
  if (!iso) return '-'
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString()
}

// ─────────────────────────── 汇总字段（日清 items/totals 与月结结果共用） ───────────────────────────

interface MetricField {
  key: keyof SettlementTotals
  label: string
  kind: 'qty' | 'amount'
}

/** 顺序照契约 `SettlementTotals`，净额放最后（它是派生值）。 */
const METRIC_FIELDS: MetricField[] = [
  { key: 'purchaseQty', label: '采购入库量', kind: 'qty' },
  { key: 'purchaseAmount', label: '采购金额', kind: 'amount' },
  { key: 'salesQty', label: '销售出库量', kind: 'qty' },
  { key: 'salesAmount', label: '销售金额', kind: 'amount' },
  { key: 'returnQty', label: '退货入库量', kind: 'qty' },
  { key: 'returnAmount', label: '退货金额', kind: 'amount' },
  { key: 'netAmount', label: '净销售额', kind: 'amount' },
]

function metricText(field: MetricField, row: SettlementTotals): string {
  const value = row[field.key]
  return field.kind === 'amount' ? `¥ ${fenToYuan(value)}` : String(value)
}

// ─────────────────────────── 日清 ───────────────────────────

const dailyForm = reactive({
  bizDate: todayText(),
  warehouseId: undefined as number | undefined,
})
const dailyLoading = ref(false)
const dailyError = ref('')
const dailyErrorCode = ref('')
const dailyResult = ref<DailySettlementResult | null>(null)

async function runDaily() {
  if (!dailyForm.bizDate) {
    ElMessage.warning('请先选择业务日期')
    return
  }
  dailyLoading.value = true
  dailyError.value = ''
  dailyErrorCode.value = ''
  try {
    dailyResult.value = await setApi.daily({
      bizDate: dailyForm.bizDate,
      warehouseId: dailyForm.warehouseId,
    })
    ElMessage.success(
      `日清完成：${dailyResult.value.bizDate} · ${dailyResult.value.items.length} 个仓库（同日重复执行结果一致）`,
    )
  } catch (error) {
    const info = errorInfo(error)
    dailyError.value = info.message
    dailyErrorCode.value = info.code
  } finally {
    dailyLoading.value = false
  }
}

/**
 * 合计是否为"全 0"（实测：无单据的日期后端仍按仓库返回 0 值行并落库）。
 * 只用于决定是否给一句解释，不参与任何判断 —— 数字本身一律照后端显示。
 */
const dailyTotalsAllZero = computed(
  () => !!dailyResult.value && Object.values(dailyResult.value.totals).every((v) => v === 0),
)

// ─────────────────────────── 月结（锁账，不可逆） ───────────────────────────
/**
 * 二次确认文案。
 *
 * ★ 这句是验收项（§5.4 交接第 6 条）：必须写明"锁账后该期间的单据不能新增/执行/作废，不可逆"。
 *   月结没有解锁接口，误点一次就把整个月的单据冻住 —— 靠事后道歉没有意义。
 */
const MONTHLY_CONFIRM =
  '锁账后该期间的单据不能新增/执行/作废，不可逆（系统不提供解锁）。月结会按单据业务日期重算该期每天的日清并写入期间汇总。'

const monthlyForm = reactive({ period: thisPeriodText() })
const monthlyLoading = ref(false)
const monthlyError = ref('')
const monthlyErrorCode = ref('')
const monthlyResult = ref<MonthlySettlementResult | null>(null)

async function runMonthly() {
  if (!/^\d{4}-\d{2}$/.test(monthlyForm.period)) {
    ElMessage.warning('账期格式要求 yyyy-MM，例如 2026-10')
    return
  }
  try {
    await ElMessageBox.confirm(
      `${MONTHLY_CONFIRM}即将锁定的账期：${monthlyForm.period}。`,
      '月结锁账确认',
      {
        type: 'warning',
        confirmButtonText: '确认锁账',
        cancelButtonText: '取消',
      },
    )
  } catch {
    return
  }

  monthlyLoading.value = true
  monthlyError.value = ''
  monthlyErrorCode.value = ''
  try {
    monthlyResult.value = await setApi.monthly({ period: monthlyForm.period })
    ElMessage.success(
      monthlyResult.value.duplicate
        ? `账期 ${monthlyResult.value.period} 早已锁账且重算一致，本次为幂等命中，未重复结算`
        : `账期 ${monthlyResult.value.period} 已锁账：净销售额 ¥${fenToYuan(monthlyResult.value.netAmount)}`,
    )
  } catch (error) {
    const info = errorInfo(error)
    monthlyError.value = info.message
    monthlyErrorCode.value = info.code
  } finally {
    monthlyLoading.value = false
  }
}

// ─────────────────────────── 对账 ───────────────────────────

const reconcileForm = reactive({ period: thisPeriodText() })
const reconcileLoading = ref(false)
const reconcileError = ref('')
const reconcileErrorCode = ref('')
const reconcileErrorDetails = ref<Record<string, unknown> | undefined>(undefined)
const reconcileResult = ref<ReconcileResult | null>(null)

async function runReconcile() {
  if (!/^\d{4}-\d{2}$/.test(reconcileForm.period)) {
    ElMessage.warning('账期格式要求 yyyy-MM，例如 2026-10')
    return
  }
  reconcileLoading.value = true
  reconcileError.value = ''
  reconcileErrorCode.value = ''
  reconcileErrorDetails.value = undefined
  try {
    reconcileResult.value = await setApi.reconcile(reconcileForm.period)
  } catch (error) {
    const info = errorInfo(error)
    reconcileResult.value = null
    reconcileError.value = info.message
    reconcileErrorCode.value = info.code
    reconcileErrorDetails.value = info.details
  } finally {
    reconcileLoading.value = false
  }
}

/** details 里的分要做金额展示，但键名与含义一律照后端原样列出，不重排不改名。 */
const detailEntries = computed(() =>
  Object.entries(reconcileErrorDetails.value ?? {}).map(([key, value]) => ({
    key,
    text: ['generalTotal', 'detailTotal', 'diff'].includes(key) && typeof value === 'number'
      ? `¥ ${fenToYuan(value)}`
      : String(value),
  })),
)

onMounted(loadWarehouses)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2 class="page-title">结算</h2>
      <div>
        <el-button v-if="canReadMaster" @click="loadWarehouses">刷新仓库</el-button>
      </div>
    </div>
    <p class="page-hint">
      口径：日清与月结都按<b>单据业务日期 biz_date</b> 汇总（进货入库量 / 出货已出量 / 退货已完成量），
      不是按执行时间。同日重复执行结果一致；月结会锁定整个账期。
    </p>

    <el-alert
      v-if="!canReadMaster"
      type="info"
      show-icon
      :closable="false"
      class="mb-16"
      title="当前账号读不到仓库名称字典（需要 mdm:read）"
      description="日清卡片会显示「仓库#编号」而不是仓库名，仓库下拉也会是空的（此时只能不选仓库，对全部仓库执行日清）；数据本身不受影响，名称与候选都来自 /warehouses 接口。"
    />

    <el-alert
      v-if="readonlyShell"
      type="info"
      show-icon
      :closable="false"
      class="mb-16"
      title="当前账号只有结算页的查看权限（set:read）"
      description="本页三项结果都要执行动作才能取到：日清 set:daily、月结 set:monthly、对账 set:reconcile。后端没有账期状态的只读查询接口，因此无执行权限时页面没有数据可显示，这不代表系统里没有账期。"
    />

    <el-tabs v-model="activeTab" class="set-tabs">
      <!-- ─────────── 日清 ─────────── -->
      <el-tab-pane label="日清" name="daily">
        <div class="filters">
          <el-date-picker
            v-model="dailyForm.bizDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="业务日期"
            class="filter-date"
          />
          <el-select
            v-model="dailyForm.warehouseId"
            placeholder="全部仓库"
            clearable
            class="filter-select"
          >
            <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
          </el-select>
          <el-button
            v-if="canDaily"
            type="primary"
            :loading="dailyLoading"
            @click="runDaily"
          >
            执行日清
          </el-button>
          <span v-else class="text-muted">当前账号无「执行日清」权限（set:daily）</span>
        </div>

        <el-alert
          v-if="dailyError"
          type="error"
          show-icon
          :closable="false"
          class="mb-16"
          :title="`日清失败${dailyErrorCode ? `（${dailyErrorCode}）` : ''}：${dailyError}`"
        >
          <el-button v-if="canDaily" size="small" @click="runDaily">重试</el-button>
        </el-alert>

        <template v-if="dailyResult">
          <div class="result-head">
            <span class="result-title">业务日期 {{ dailyResult.bizDate }}</span>
            <span class="text-muted">按仓库 {{ dailyResult.items.length }} 行 + 全部仓库合计</span>
          </div>

          <div class="totals-card">
            <div class="totals-card-title">全部仓库合计（totals）</div>
            <div class="metric-grid">
              <div v-for="f in METRIC_FIELDS" :key="`t-${f.key}`" class="metric">
                <div class="metric-label">{{ f.label }}</div>
                <div :class="['metric-value', f.kind === 'amount' ? 'amount' : '']">
                  {{ metricText(f, dailyResult.totals) }}
                </div>
              </div>
            </div>
          </div>

          <div class="item-grid">
            <div v-for="item in dailyResult.items" :key="item.warehouseId" class="totals-card">
              <div class="totals-card-title">{{ warehouseText(item.warehouseId) }}</div>
              <div class="metric-grid">
                <div v-for="f in METRIC_FIELDS" :key="`${item.warehouseId}-${f.key}`" class="metric">
                  <div class="metric-label">{{ f.label }}</div>
                  <div :class="['metric-value', f.kind === 'amount' ? 'amount' : '']">
                    {{ metricText(f, item) }}
                  </div>
                </div>
              </div>
            </div>
          </div>

          <p v-if="dailyTotalsAllZero" class="empty-note">
            这个业务日期没有已执行的单据（进货按 部分入库/已完成、出货按 部分出货/已完成、退货按 已完成 计入），
            所以每个仓库的合计都是 0。日清按仓库逐条 upsert，<b>0 值行同样会写入</b> <code>daily_settlements</code>；
            同日重跑结果一致，不会累加、也不会新增行。
          </p>

          <p class="page-hint mt-12">
            净销售额 = 销售金额 − 退货金额，由后端算好返回（页面不再减一次）。
            已锁账的日期再执行日清会被拒绝（SET_PERIOD_CLOSED），消息原样显示在上方。
          </p>
        </template>

        <el-empty v-else-if="!dailyError && !dailyLoading" description="还没有执行过日清" />
      </el-tab-pane>

      <!-- ─────────── 月结 ─────────── -->
      <el-tab-pane label="月结" name="monthly">
        <el-alert
          type="warning"
          show-icon
          :closable="false"
          class="mb-16"
          title="月结 = 锁账，不可逆"
          :description="`${MONTHLY_CONFIRM}月结会自动补齐该期缺失的日清行；已有日清行与重算不一致时报 SET_ALREADY_SETTLED，不静默覆盖。`"
        />

        <div class="filters">
          <el-date-picker
            v-model="monthlyForm.period"
            type="month"
            value-format="YYYY-MM"
            placeholder="账期（yyyy-MM）"
            class="filter-date"
          />
          <el-button v-if="canMonthly" type="primary" :loading="monthlyLoading" @click="runMonthly">
            执行月结（锁账）
          </el-button>
          <span v-else class="text-muted">当前账号无「执行月结」权限（set:monthly）</span>
        </div>

        <el-alert
          v-if="monthlyError"
          type="error"
          show-icon
          :closable="false"
          class="mb-16"
          :title="`月结失败${monthlyErrorCode ? `（${monthlyErrorCode}）` : ''}：${monthlyError}`"
        >
          <el-button v-if="canMonthly" size="small" @click="runMonthly">重试</el-button>
        </el-alert>

        <template v-if="monthlyResult">
          <div class="result-head">
            <span class="result-title">账期 {{ monthlyResult.period }}</span>
            <el-tag :type="monthlyResult.status === 'CLOSED' ? 'danger' : 'success'" disable-transitions>
              {{ monthlyResult.status === 'CLOSED' ? '已锁账' : '未锁账' }}
            </el-tag>
            <span class="text-muted">锁账时间 {{ dateTimeText(monthlyResult.closedAt) }}</span>
            <el-tag v-if="monthlyResult.duplicate" type="info" disable-transitions>幂等命中</el-tag>
          </div>

          <div class="totals-card">
            <div class="totals-card-title">期间汇总</div>
            <div class="metric-grid">
              <div v-for="f in METRIC_FIELDS" :key="`m-${f.key}`" class="metric">
                <div class="metric-label">{{ f.label }}</div>
                <div :class="['metric-value', f.kind === 'amount' ? 'amount' : '']">
                  {{ metricText(f, monthlyResult) }}
                </div>
              </div>
            </div>
          </div>

          <el-alert
            v-if="monthlyResult.status === 'CLOSED'"
            type="warning"
            show-icon
            :closable="false"
            class="mt-12"
            :title="`账期 ${monthlyResult.period} 已锁账`"
            description="该期间的进货、出货、退货单据不能再新增、提交、审核、入库、出货、驳回或作废；后端会返回 SET_PERIOD_CLOSED。系统不提供解锁。"
          />
        </template>

        <el-empty v-else-if="!monthlyError && !monthlyLoading" description="还没有执行过月结" />
      </el-tab-pane>

      <!-- ─────────── 对账 ─────────── -->
      <el-tab-pane label="对账" name="reconcile">
        <div class="filters">
          <el-date-picker
            v-model="reconcileForm.period"
            type="month"
            value-format="YYYY-MM"
            placeholder="账期（yyyy-MM）"
            class="filter-date"
          />
          <el-button v-if="canReconcile" type="primary" :loading="reconcileLoading" @click="runReconcile">
            执行对账
          </el-button>
          <span v-else class="text-muted">当前账号无「执行对账」权限（set:reconcile）</span>
          <span v-if="canReconcile" class="text-muted">
            总账 = 期间已存汇总；明细 = 按单据实时重算。差额必须为 0。
          </span>
        </div>

        <el-alert
          v-if="reconcileError"
          type="error"
          show-icon
          :closable="false"
          class="mb-16"
          :title="`对账未通过${reconcileErrorCode ? `（${reconcileErrorCode}）` : ''}：${reconcileError}`"
        >
          <div v-if="detailEntries.length" class="detail-list">
            <span v-for="d in detailEntries" :key="d.key" class="detail-item">
              {{ d.key }}：{{ d.text }}
            </span>
          </div>
          <el-button v-if="canReconcile" size="small" @click="runReconcile">重试</el-button>
        </el-alert>

        <template v-if="reconcileResult">
          <div class="result-head">
            <span class="result-title">账期 {{ reconcileResult.period }}</span>
            <el-tag :type="reconcileResult.balanced ? 'success' : 'danger'" disable-transitions>
              {{ reconcileResult.balanced ? '平衡' : '不平' }}
            </el-tag>
          </div>

          <div class="totals-card">
            <div class="totals-card-title">总账与明细</div>
            <div class="metric-grid">
              <div class="metric">
                <div class="metric-label">总账（期间汇总）</div>
                <div class="metric-value amount">¥ {{ fenToYuan(reconcileResult.generalTotal) }}</div>
              </div>
              <div class="metric">
                <div class="metric-label">明细（实时重算）</div>
                <div class="metric-value amount">¥ {{ fenToYuan(reconcileResult.detailTotal) }}</div>
              </div>
              <div class="metric">
                <div class="metric-label">差额（总账 − 明细）</div>
                <div :class="['metric-value', 'amount', reconcileResult.diff === 0 ? '' : 'text-danger']">
                  ¥ {{ fenToYuan(reconcileResult.diff) }}
                </div>
              </div>
            </div>
          </div>

          <p class="page-hint mt-12">
            对账比较的是净额口径（销售金额 − 退货金额），与后端契约 ReconcileResult 只有一对 total 一致。
            账期尚未月结时对账会被拒绝（SET_PERIOD_NOT_CLOSED）。
          </p>
        </template>

        <el-empty
          v-else-if="!reconcileError && !reconcileLoading"
          description="还没有执行过对账"
        />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.set-tabs {
  margin-top: 4px;
}

.filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
}

.filter-date {
  width: 180px;
}

.filter-select {
  width: 200px;
}

.result-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.result-title {
  font-size: 15px;
  font-weight: 600;
}

.totals-card {
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  background: #fff;
  padding: 12px;
}

.totals-card-title {
  font-weight: 600;
  margin-bottom: 10px;
}

.item-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 12px;
  margin-top: 12px;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
  gap: 10px;
}

.metric-label {
  font-size: 12px;
  color: #909399;
}

.metric-value {
  margin-top: 2px;
  font-size: 16px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.mt-12 {
  margin-top: 12px;
}

.empty-note {
  margin: 12px 0 0;
  padding: 12px;
  border: 1px dashed #dcdfe6;
  border-radius: 4px;
  background: #fff;
  font-size: 13px;
  color: #909399;
}

.detail-list {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 8px;
  font-size: 12px;
}

@media (max-width: 768px) {
  .filters {
    flex-direction: column;
    align-items: stretch;
  }

  .filter-date,
  .filter-select {
    width: 100%;
  }

  .item-grid {
    grid-template-columns: 1fr;
  }
}
</style>
