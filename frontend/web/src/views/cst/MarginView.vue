<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  cstApi,
  fenToYuan,
  type MarginGroupBy,
  type MarginSummaryRow,
} from '@/api/client'
import PageTable from '@/components/PageTable.vue'
import { useSessionStore } from '@/stores/session'

/**
 * 毛利分析（FR-CST-03）。
 *
 * ★ 口径（后端写死，页面只展示，不重算）：
 *   收入 = 已出货行金额 − 退货行金额；
 *   成本 = SALES_OUT 成本 − RETURN_IN 成本（移动加权）；
 *   数量 = 已出货 − 已退货；毛利率按「分组汇总额」重算，不是行平均。
 *
 * ★ 数据来自成本月结生成的 margin_records：
 *   未执行月结的期间查询为空。页面把这一点写在空状态与说明里，
 *   并提供「执行成本月结」入口（需要 cst:monthly），避免用户误以为数据丢了。
 *
 * ★ 金额一律整数分（ADR-0005），只在展示层用 fenToYuan 转元；
 *   毛利率是万分比，展示时 ÷100，不回传后端。
 */

const session = useSessionStore()
const canCloseMonthly = computed(() => session.hasPermission('cst:monthly'))

const dims: { value: MarginGroupBy; label: string }[] = [
  { value: 'product', label: '商品 / SKU' },
  { value: 'customer', label: '客户' },
  { value: 'period', label: '账期' },
]

function pad(n: number): string {
  return String(n).padStart(2, '0')
}

/** 用本地年月而不是 toISOString：避免东八区月初/月末被 UTC 挪一天。 */
function periodOf(date: Date): string {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}`
}

function currentPeriod(): string {
  return periodOf(new Date())
}

/** 默认查询最近三个月，覆盖“本月还没结账、看上月毛利”的常见场景。 */
function defaultRange(): [string, string] {
  const now = new Date()
  const start = new Date(now.getFullYear(), now.getMonth() - 2, 1)
  return [periodOf(start), periodOf(now)]
}

const range = ref<[string, string] | null>(defaultRange())
const groupBy = ref<MarginGroupBy>('product')
const rows = ref<MarginSummaryRow[]>([])
const loading = ref(false)
const loaded = ref(false)
const error = ref('')

/** 拦截器已弹过一次消息，这里把原文留在页面上（刷新后仍可回看）。 */
function errorMessage(error: unknown): string {
  const e = error as { response?: { data?: { message?: string } }; message?: string }
  return e?.response?.data?.message || e?.message || '请求失败'
}

async function load() {
  if (!range.value || range.value.length !== 2) {
    ElMessage.warning('请先选择账期区间')
    return
  }
  loading.value = true
  error.value = ''
  try {
    const data = await cstApi.profit({
      from: range.value[0],
      to: range.value[1],
      groupBy: groupBy.value,
    })
    rows.value = data.items
  } catch (e) {
    rows.value = []
    error.value = errorMessage(e)
  } finally {
    loading.value = false
    loaded.value = true
  }
}

function reset() {
  range.value = defaultRange()
  groupBy.value = 'product'
  load()
}

/**
 * 执行成本月结：使用区间结束月作为账期。
 * 后端对同账期重复执行是幂等的，但月结会重建该期间的毛利快照，
 * 所以仍然二次确认，避免用户误以为“点一下只是刷新”。
 */
async function closeMonthly() {
  const period = range.value?.[1] ?? currentPeriod()
  try {
    await ElMessageBox.confirm(
      `对账期 ${period} 执行成本月结？\n`
        + '系统会按当前流水重算成本层，并重新生成该期间的商品/客户毛利快照。\n'
        + '同一账期重复执行是幂等的；账期已锁时会被拒绝（SET_PERIOD_CLOSED）。',
      '执行成本月结',
      { confirmButtonText: '执行', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    const result = await cstApi.closeMonthly({ period })
    ElMessage.success(
      result.duplicate
        ? `账期 ${period} 已结过且数据一致，本次未改动账目`
        : `账期 ${period} 成本月结完成，生成毛利记录 ${result.marginRecordCount} 条`,
    )
    await load()
  } catch (e) {
    error.value = errorMessage(e)
  }
}

/** 合计只做展示：费率不求和（行平均会失真），需要时看各行毛利率。 */
const totals = computed(() => {
  const sum = { qty: 0, revenue: 0, cost: 0, gross: 0 }
  for (const row of rows.value) {
    sum.qty += row.qty
    sum.revenue += row.revenueAmount
    sum.cost += row.costAmount
    sum.gross += row.grossProfitAmount
  }
  return sum
})

function rateText(bp: number): string {
  return `${(bp / 100).toFixed(2)}%`
}

function profitClass(value: number): string {
  return value < 0 ? 'negative' : 'positive'
}

onMounted(load)
</script>

<template>
  <div>
    <PageTable
      :rows="rows"
      :loading="loading"
      :paginated="false"
      :filtered="loaded"
      row-key="key"
      empty-text="暂无毛利记录"
    >
      <template #header>
        <div class="header-left">
          <span class="title">毛利分析</span>
          <span class="subtitle">净额口径：已出货 − 已退货；成本按移动加权结转</span>
        </div>
        <el-button
          v-if="canCloseMonthly"
          type="primary"
          plain
          :loading="loading"
          @click="closeMonthly"
        >
          执行成本月结
        </el-button>
      </template>

      <template #filters>
        <el-date-picker
          v-model="range"
          type="monthrange"
          value-format="YYYY-MM"
          range-separator="至"
          start-placeholder="起始账期"
          end-placeholder="结束账期"
          class="filter-range"
          @change="load"
        />
        <el-select v-model="groupBy" class="filter-dim" @change="load">
          <el-option v-for="d in dims" :key="d.value" :label="d.label" :value="d.value" />
        </el-select>
        <el-button type="primary" :loading="loading" @click="load">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </template>

      <el-alert
        v-if="error"
        :title="error"
        type="error"
        show-icon
        :closable="false"
        class="mb-12"
      />

      <el-alert
        type="info"
        :closable="false"
        show-icon
        class="mb-12"
        title="毛利数据来自成本月结快照；未执行月结的期间查询为空，请先执行成本月结。"
      />

      <el-row :gutter="12" class="summary-row">
        <el-col :xs="12" :sm="6">
          <el-card shadow="never" class="summary-card">
            <div class="summary-label">销售数量</div>
            <div class="summary-value">{{ totals.qty }}</div>
          </el-card>
        </el-col>
        <el-col :xs="12" :sm="6">
          <el-card shadow="never" class="summary-card">
            <div class="summary-label">收入</div>
            <div class="summary-value">¥{{ fenToYuan(totals.revenue) }}</div>
          </el-card>
        </el-col>
        <el-col :xs="12" :sm="6">
          <el-card shadow="never" class="summary-card">
            <div class="summary-label">成本</div>
            <div class="summary-value">¥{{ fenToYuan(totals.cost) }}</div>
          </el-card>
        </el-col>
        <el-col :xs="12" :sm="6">
          <el-card shadow="never" class="summary-card">
            <div class="summary-label">毛利</div>
            <div class="summary-value" :class="profitClass(totals.gross)">
              ¥{{ fenToYuan(totals.gross) }}
            </div>
          </el-card>
        </el-col>
      </el-row>

      <el-table-column prop="label" label="维度" min-width="200" />
      <el-table-column prop="qty" label="数量" width="100" align="right" />
      <el-table-column label="收入（元）" width="140" align="right">
        <template #default="{ row }">¥{{ fenToYuan(row.revenueAmount) }}</template>
      </el-table-column>
      <el-table-column label="成本（元）" width="140" align="right">
        <template #default="{ row }">¥{{ fenToYuan(row.costAmount) }}</template>
      </el-table-column>
      <el-table-column label="毛利（元）" width="140" align="right">
        <template #default="{ row }">
          <span :class="profitClass(row.grossProfitAmount)">
            ¥{{ fenToYuan(row.grossProfitAmount) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="毛利率" width="110" align="right">
        <template #default="{ row }">{{ rateText(row.grossMarginRateBp) }}</template>
      </el-table-column>
      <el-table-column prop="orderCount" label="出货单数" width="110" align="right" />

      <template #empty>
        <div class="empty-title">
          {{ loaded ? '该账期暂无毛利记录' : '选择账期后查询' }}
        </div>
        <div class="empty-subtitle">
          {{ loaded
            ? '请确认该期间已有出货，并先执行成本月结；完全退货的记录不会出现在毛利表中。'
            : '毛利数据由成本月结生成，默认查询最近三个月。' }}
        </div>
      </template>
    </PageTable>
  </div>
</template>

<style scoped>
.header-left {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.title {
  font-size: 16px;
  font-weight: 600;
}

.subtitle {
  font-size: 12px;
  color: #909399;
}

.filter-range {
  width: 280px;
}

.filter-dim {
  width: 140px;
}

.mb-12 {
  margin-bottom: 12px;
}

.summary-row {
  margin-bottom: 12px;
}

.summary-card {
  border-radius: 4px;
}

.summary-label {
  font-size: 12px;
  color: #909399;
}

.summary-value {
  margin-top: 6px;
  font-size: 20px;
  font-weight: 600;
}

.positive {
  color: #3a8f5a;
}

.negative {
  color: #c45656;
}

.empty-title {
  font-size: 15px;
  color: #606266;
}

.empty-subtitle {
  margin-top: 6px;
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}
</style>
