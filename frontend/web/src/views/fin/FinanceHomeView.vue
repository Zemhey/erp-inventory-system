<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { finApi, money, type FinanceOverviewView } from '@/api/client'

/**
 * 财务中心首页（FR-V3-13）。
 * 4 卡片（资金/应收/应付/本月收支）+ 近 6 个月现金流图（CSS 条形，零依赖）+ 快捷入口。
 * 数字与明细页同源同口径：卡片全部来自 /fin/overview，前端不再聚合。
 */

const router = useRouter()
const data = ref<FinanceOverviewView | null>(null)
const loading = ref(false)
const error = ref('')

const maxFlow = ref(1)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const d = await finApi.overview()
    data.value = d
    maxFlow.value = Math.max(1, ...d.cashFlow.map((r) => Math.max(r.inflow, r.outflow)))
  } catch (e) {
    const err = e as { response?: { data?: { message?: string } }; message?: string }
    error.value = err?.response?.data?.message || err?.message || '加载失败'
  } finally {
    loading.value = false
  }
}

function go(path: string) {
  router.push(path)
}

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="mb-12" />

    <el-row v-if="data" :gutter="12" class="mb-12">
      <el-col :xs="12" :sm="6">
        <el-card shadow="never" class="kpi">
          <div class="kpi-label">资金余额（启用账户合计）</div>
          <div class="kpi-value">{{ money(data.cards.fundsBalance) }}</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="never" class="kpi">
          <div class="kpi-label">应收总额</div>
          <div class="kpi-value">{{ money(data.cards.receivableTotal) }}</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="never" class="kpi">
          <div class="kpi-label">应付总额</div>
          <div class="kpi-value">{{ money(data.cards.payableTotal) }}</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="never" class="kpi">
          <div class="kpi-label">本月收 / 支（{{ data.period }}）</div>
          <div class="kpi-value">
            <span class="in">{{ money(data.cards.monthInflow) }}</span>
            <span class="sep">/</span>
            <span class="out">{{ money(data.cards.monthOutflow) }}</span>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="12">
      <el-col :xs="24" :md="16">
        <el-card shadow="never">
          <template #header>
            <span>现金流（近 6 个月：收 vs 支，来自银行日记账按月合计）</span>
          </template>
          <div v-if="data && data.cashFlow.length" class="flow">
            <div v-for="row in data.cashFlow" :key="row.period" class="flow-row">
              <div class="flow-period">{{ row.period }}</div>
              <div class="flow-bars">
                <div class="bar in-bar" :style="{ width: `${(row.inflow / maxFlow) * 100}%` }">
                  <span v-if="row.inflow > 0">{{ money(row.inflow) }}</span>
                </div>
                <div class="bar out-bar" :style="{ width: `${(row.outflow / maxFlow) * 100}%` }">
                  <span v-if="row.outflow > 0">{{ money(row.outflow) }}</span>
                </div>
              </div>
            </div>
            <div class="legend">
              <span class="dot in-dot" /> 收入（借）
              <span class="dot out-dot" /> 支出（贷）
            </div>
          </div>
          <el-empty v-else description="暂无现金流：日记账还没有流水（收付款核销后自动出现）" />
        </el-card>
      </el-col>
      <el-col :xs="24" :md="8">
        <el-card shadow="never">
          <template #header><span>快捷入口</span></template>
          <div class="quick">
            <el-button class="quick-btn" type="primary" plain @click="go('/fin/vouchers')">录凭证</el-button>
            <el-button class="quick-btn" plain @click="go('/fin/expenses')">报销</el-button>
            <el-button class="quick-btn" plain @click="go('/fin/invoices')">开发票（登记）</el-button>
            <el-button class="quick-btn" plain @click="go('/fin/reports')">看报表</el-button>
          </div>
          <div v-if="data" class="foot-note">
            已过账凭证 {{ data.postedVoucherCount }} 张；本期 {{ data.periodVoucherCount }} 张。
            凭证列表里业务来源（入库/出库/收付款）的凭证由系统自动生成。
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.mb-12 {
  margin-bottom: 12px;
}

.kpi-label {
  font-size: 12px;
  color: #909399;
}

.kpi-value {
  margin-top: 6px;
  font-size: 22px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.kpi-value .sep {
  margin: 0 6px;
  color: #c0c4cc;
}

.kpi-value .in {
  color: #3a8f5a;
}

.kpi-value .out {
  color: #c45656;
}

.flow-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.flow-period {
  width: 64px;
  font-size: 12px;
  color: #606266;
}

.flow-bars {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.bar {
  height: 18px;
  border-radius: 2px;
  display: flex;
  align-items: center;
  padding: 0 6px;
  min-width: 2px;
}

.bar span {
  font-size: 11px;
  color: #fff;
  white-space: nowrap;
}

.in-bar {
  background: #4fa976;
}

.out-bar {
  background: #d8857c;
}

.legend {
  margin-top: 8px;
  font-size: 12px;
  color: #909399;
  display: flex;
  align-items: center;
  gap: 6px;
}

.dot {
  width: 10px;
  height: 10px;
  border-radius: 2px;
  display: inline-block;
}

.in-dot {
  background: #4fa976;
}

.out-dot {
  background: #d8857c;
}

.quick {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.quick-btn {
  width: 100%;
  margin-left: 0 !important;
}

.foot-note {
  margin-top: 12px;
  font-size: 12px;
  color: #909399;
  line-height: 1.7;
}
</style>
