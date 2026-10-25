<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  finApi,
  money,
  type BalanceSheetView,
  type IncomeStatementView,
} from '@/api/client'
import { useSessionStore } from '@/stores/session'

/**
 * 财务报表（FR-V3-12）：资产负债表 + 利润表，两张表切换 + 期间选择 + 打印。
 *
 * ★ 配平口径（后端算，前端只展示）：
 *   资产 = 负债 + 权益 + 截至本期累计利润；diff ≠ 0 时页面直接显示差额并标红。
 *   （简报验收："资产负债表差额显示 0"。）
 */

const session = useSessionStore()
const canReport = session.hasPermission('fin:report')

function pad(n: number): string {
  return String(n).padStart(2, '0')
}

function currentPeriod(): string {
  const now = new Date()
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}`
}

const period = ref(currentPeriod())
const which = ref<'balance' | 'income'>('balance')
const balance = ref<BalanceSheetView | null>(null)
const income = ref<IncomeStatementView | null>(null)
const loading = ref(false)
const error = ref('')

async function load() {
  if (!canReport) return
  loading.value = true
  error.value = ''
  try {
    if (which.value === 'balance') {
      balance.value = await finApi.balanceSheet(period.value)
    } else {
      income.value = await finApi.incomeStatement(period.value)
    }
  } catch (e) {
    const err = e as { response?: { data?: { message?: string } }; message?: string }
    error.value = err?.response?.data?.message || err?.message || '报表加载失败'
  } finally {
    loading.value = false
  }
}

function switchTo(next: 'balance' | 'income') {
  which.value = next
  load()
}

function printReport() {
  window.print()
}

onMounted(load)
</script>

<template>
  <div class="report-page">
    <div class="toolbar">
      <el-radio-group :model-value="which" @change="switchTo(which === 'balance' ? 'income' : 'balance')">
        <el-radio-button value="balance">资产负债表</el-radio-button>
        <el-radio-button value="income">利润表</el-radio-button>
      </el-radio-group>
      <el-date-picker
        v-model="period"
        type="month"
        value-format="YYYY-MM"
        placeholder="账期"
        class="w-140"
        @change="load"
      />
      <el-button type="primary" :loading="loading" @click="load">查询</el-button>
      <el-button :disabled="!balance && !income" @click="printReport">打印</el-button>
    </div>

    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="mb-12" />

    <template v-if="which === 'balance' && balance">
      <el-alert
        :title="balance.balanced
          ? `配平：资产 ${money(balance.assetTotal)} = 负债+权益+累计利润 ${money(balance.liabilityEquityTotal)}，差额 0.00`
          : `不平！差额 ${money(balance.diff)} 元（只统计已过账凭证；请核查期初余额与凭证）`"
        :type="balance.balanced ? 'success' : 'error'"
        show-icon
        :closable="false"
        class="mb-12"
      />
      <el-row :gutter="12">
        <el-col :xs="24" :md="12">
          <el-card shadow="never" class="print-block">
            <template #header><b>资产</b>（截至 {{ balance.period }}）</template>
            <table class="report-table">
              <tr v-for="r in balance.assets" :key="r.code">
                <td>{{ r.code }} {{ r.name }}</td>
                <td class="num">{{ money(r.amount) }}</td>
              </tr>
              <tr class="total">
                <td>资产总计</td>
                <td class="num">{{ money(balance.assetTotal) }}</td>
              </tr>
            </table>
          </el-card>
        </el-col>
        <el-col :xs="24" :md="12">
          <el-card shadow="never" class="print-block">
            <template #header><b>负债与所有者权益</b></template>
            <table class="report-table">
              <tr v-for="r in balance.liabilities" :key="r.code">
                <td>{{ r.code }} {{ r.name }}</td>
                <td class="num">{{ money(r.amount) }}</td>
              </tr>
              <tr class="sub">
                <td>负债合计</td>
                <td class="num">{{ money(balance.liabilityTotal) }}</td>
              </tr>
              <tr v-for="r in balance.equity" :key="r.code">
                <td>{{ r.code }} {{ r.name }}</td>
                <td class="num">{{ money(r.amount) }}</td>
              </tr>
              <tr>
                <td>累计利润（未结转，平衡项）</td>
                <td class="num">{{ money(balance.accumulatedProfit) }}</td>
              </tr>
              <tr class="total">
                <td>负债与权益总计</td>
                <td class="num">{{ money(balance.liabilityEquityTotal) }}</td>
              </tr>
            </table>
          </el-card>
        </el-col>
      </el-row>
    </template>

    <template v-if="which === 'income' && income">
      <el-card shadow="never" class="print-block">
        <template #header><b>利润表</b> · {{ income.period }}（仅已过账凭证）</template>
        <table class="report-table wide">
          <tr class="section"><td colspan="2">一、营业收入</td></tr>
          <tr v-for="r in income.revenue" :key="r.code">
            <td class="indent">{{ r.code }} {{ r.name }}</td>
            <td class="num">{{ money(r.amount) }}</td>
          </tr>
          <tr class="sub"><td>收入合计</td><td class="num">{{ money(income.revenueTotal) }}</td></tr>
          <tr class="section"><td colspan="2">二、营业成本</td></tr>
          <tr v-for="r in income.cost" :key="r.code">
            <td class="indent">{{ r.code }} {{ r.name }}</td>
            <td class="num">{{ money(r.amount) }}</td>
          </tr>
          <tr class="sub"><td>成本合计</td><td class="num">{{ money(income.costTotal) }}</td></tr>
          <tr class="total"><td>毛利（收入 − 成本）</td><td class="num">{{ money(income.grossProfit) }}</td></tr>
          <tr class="section"><td colspan="2">三、期间费用</td></tr>
          <tr v-for="r in income.expense" :key="r.code">
            <td class="indent">{{ r.code }} {{ r.name }}</td>
            <td class="num">{{ money(r.amount) }}</td>
          </tr>
          <tr class="sub"><td>费用合计</td><td class="num">{{ money(income.expenseTotal) }}</td></tr>
          <tr class="total"><td>净利润（毛利 − 费用）</td><td class="num">{{ money(income.netProfit) }}</td></tr>
        </table>
      </el-card>
    </template>

    <el-empty
      v-if="!canReport"
      description="当前账号没有报表权限（fin:report）：能进财务中心首页，但看不到完整报表"
    />
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;
}

.w-140 {
  width: 140px;
}

.mb-12 {
  margin-bottom: 12px;
}

.report-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
}

.report-table td {
  padding: 6px 8px;
  border-bottom: 1px solid #f0f0f0;
}

.report-table .num {
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.report-table .indent {
  padding-left: 24px;
  color: #606266;
}

.report-table .section td {
  font-weight: 600;
  background: #f5f7fa;
}

.report-table .sub td {
  font-weight: 600;
}

.report-table .total td {
  font-weight: 700;
  border-top: 2px solid #303133;
}

@media print {
  .toolbar,
  .el-alert {
    display: none !important;
  }

  .print-block {
    border: none;
    box-shadow: none;
  }
}
</style>
