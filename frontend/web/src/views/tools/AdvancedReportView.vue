<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageTable from '@/components/PageTable.vue'
import { fenToYuan } from '@/api/client'
import {
  reportApi,
  type AgingRow,
  type CustomerContributionRow,
  type InventoryTurnoverRow,
  type OperatingReportRow,
} from '@/api/report'

/**
 * 高级报表（FR-V3-17 之 ReportAdvancedController 的独立入口）。
 *
 * 后端是 /api/v1/reports/{operating|inventory-turnover|customer-contribution|aging}
 * 四个只读 GET；报表金额口径为「分」，展示统一 fenToYuan。
 * 与「报表中心」（rpt）不冲突：这里只挂 V2-8 的四张高级报表，作为供应链泳道
 * 的直达入口，页面本身零后端。
 */

const tab = ref('operating')
const loading = ref(false)
const error = ref('')

const today = new Date()
const iso = (d: Date) => d.toISOString().slice(0, 10)
const monthAgo = new Date(today.getTime() - 30 * 24 * 3600 * 1000)

const range = ref<{ from: string; to: string }>({ from: iso(monthAgo), to: iso(today) })
const aging = ref<{ type: 'AR' | 'AP'; asOf: string }>({ type: 'AR', asOf: iso(today) })

const operating = ref<OperatingReportRow[]>([])
const turnover = ref<InventoryTurnoverRow[]>([])
const contribution = ref<CustomerContributionRow[]>([])
const contributionSummary = ref<Record<string, unknown> | null>(null)
const agingRows = ref<AgingRow[]>([])

function errorMessage(cause: unknown): string {
  const e = cause as { response?: { data?: { message?: string } }; message?: string }
  return e?.response?.data?.message || e?.message || '请求失败'
}

async function run() {
  loading.value = true
  error.value = ''
  try {
    if (tab.value === 'operating') {
      operating.value = (await reportApi.operating({ from: range.value.from, to: range.value.to })).items
    } else if (tab.value === 'turnover') {
      turnover.value = (await reportApi.turnover({ from: range.value.from, to: range.value.to, deadDays: 30 })).items
    } else if (tab.value === 'contribution') {
      const data = await reportApi.contribution({ from: range.value.from, to: range.value.to, limit: 20 })
      contribution.value = data.items
      contributionSummary.value = data.summary as unknown as Record<string, unknown>
    } else {
      const data = await reportApi.aging({ type: aging.value.type, asOf: aging.value.asOf })
      agingRows.value = data.items
    }
  } catch (cause) {
    error.value = errorMessage(cause)
    ElMessage.error(error.value)
  } finally {
    loading.value = false
  }
}

onMounted(run)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2 class="page-title">高级报表</h2>
      <el-button type="primary" :loading="loading" @click="run">查询</el-button>
    </div>
    <p class="page-hint">经营日报/月报、库存周转与呆滞、客户贡献与复购、应收/应付账龄（只读）。</p>
    <el-alert v-if="error" type="error" show-icon :closable="false" class="mb-16" :title="`查询失败：${error}`" />

    <el-tabs v-model="tab" @tab-change="run">
      <el-tab-pane label="经营报表" name="operating">
        <el-date-picker v-model="range.from" type="date" value-format="YYYY-MM-DD" class="mr-8" />
        <el-date-picker v-model="range.to" type="date" value-format="YYYY-MM-DD" class="mr-8" />
        <PageTable :rows="operating" :loading="loading" :paginated="false" empty-text="区间内没有出入库/单据数据" table-size="small">
          <el-table-column label="期间" prop="period" width="100" />
          <el-table-column label="期初" prop="openingQty" width="80" />
          <el-table-column label="入" prop="inboundQty" width="80" />
          <el-table-column label="出" prop="outboundQty" width="80" />
          <el-table-column label="期末" prop="closingQty" width="80" />
          <el-table-column label="进货量" prop="purchaseQty" width="80" />
          <el-table-column label="进货额" width="120">
            <template #default="{ row }">¥{{ fenToYuan(row.purchaseAmount) }}</template>
          </el-table-column>
          <el-table-column label="销量" prop="salesQty" width="80" />
          <el-table-column label="销售额" width="120">
            <template #default="{ row }">¥{{ fenToYuan(row.salesAmount) }}</template>
          </el-table-column>
        </PageTable>
      </el-tab-pane>

      <el-tab-pane label="周转与呆滞" name="turnover">
        <el-date-picker v-model="range.from" type="date" value-format="YYYY-MM-DD" class="mr-8" />
        <el-date-picker v-model="range.to" type="date" value-format="YYYY-MM-DD" class="mr-8" />
        <PageTable :rows="turnover" :loading="loading" :paginated="false" empty-text="区间内没有周转数据" table-size="small">
          <el-table-column label="SKU编码" prop="skuCode" width="130" />
          <el-table-column label="名称" prop="skuName" min-width="140" />
          <el-table-column label="仓库" prop="warehouseName" width="110" />
          <el-table-column label="期初" prop="openingQty" width="70" />
          <el-table-column label="期末" prop="closingQty" width="70" />
          <el-table-column label="出库" prop="outboundQty" width="70" />
          <el-table-column label="周转天数" width="90">
            <template #default="{ row }">{{ row.turnoverDays ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="呆滞天数" prop="idleDays" width="90" />
          <el-table-column label="呆滞" width="80">
            <template #default="{ row }">
              <el-tag :type="row.stale ? 'danger' : 'success'" size="small">{{ row.stale ? '是' : '否' }}</el-tag>
            </template>
          </el-table-column>
        </PageTable>
      </el-tab-pane>

      <el-tab-pane label="客户贡献" name="contribution">
        <el-date-picker v-model="range.from" type="date" value-format="YYYY-MM-DD" class="mr-8" />
        <el-date-picker v-model="range.to" type="date" value-format="YYYY-MM-DD" class="mr-8" />
        <div v-if="contributionSummary" class="summary-bar">
          <el-tag>客户数 {{ contributionSummary.customerCount }}</el-tag>
          <el-tag type="success">复购率(万分比) {{ contributionSummary.repurchaseRateBp }}</el-tag>
          <el-tag type="warning">新客 {{ contributionSummary.newCustomerCount }}</el-tag>
          <el-tag type="info">总金额 ¥{{ fenToYuan(Number(contributionSummary.totalAmount ?? 0)) }}</el-tag>
        </div>
        <PageTable :rows="contribution" :loading="loading" :paginated="false" empty-text="区间内没有成交客户" table-size="small">
          <el-table-column label="客户编码" prop="customerCode" width="120" />
          <el-table-column label="名称" prop="customerName" min-width="140" />
          <el-table-column label="订单数" prop="orderCount" width="80" />
          <el-table-column label="数量" prop="qty" width="80" />
          <el-table-column label="金额" width="120">
            <template #default="{ row }">¥{{ fenToYuan(row.amount) }}</template>
          </el-table-column>
          <el-table-column label="复购" width="70">
            <template #default="{ row }">{{ row.repurchase ? '✓' : '' }}</template>
          </el-table-column>
          <el-table-column label="新客" width="70">
            <template #default="{ row }">{{ row.newCustomer ? '✓' : '' }}</template>
          </el-table-column>
        </PageTable>
      </el-tab-pane>

      <el-tab-pane label="账龄" name="aging">
        <el-radio-group v-model="aging.type" class="mr-8">
          <el-radio-button value="AR">应收</el-radio-button>
          <el-radio-button value="AP">应付</el-radio-button>
        </el-radio-group>
        <el-date-picker v-model="aging.asOf" type="date" value-format="YYYY-MM-DD" />
        <PageTable :rows="agingRows" :loading="loading" :paginated="false" empty-text="没有未平余额" table-size="small">
          <el-table-column label="往来单位" prop="partnerName" min-width="140" />
          <el-table-column label="编码" prop="partnerCode" width="110" />
          <el-table-column label="余额" width="120">
            <template #default="{ row }">¥{{ fenToYuan(row.balanceAmount) }}</template>
          </el-table-column>
          <el-table-column label="0-30" width="110">
            <template #default="{ row }">¥{{ fenToYuan(row.bucket0) }}</template>
          </el-table-column>
          <el-table-column label="31-60" width="110">
            <template #default="{ row }">¥{{ fenToYuan(row.bucket1) }}</template>
          </el-table-column>
          <el-table-column label="61-90" width="110">
            <template #default="{ row }">¥{{ fenToYuan(row.bucket2) }}</template>
          </el-table-column>
          <el-table-column label="90+" width="110">
            <template #default="{ row }">¥{{ fenToYuan(row.bucket3) }}</template>
          </el-table-column>
        </PageTable>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.summary-bar {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}
.mr-8 {
  margin-right: 8px;
}
</style>
