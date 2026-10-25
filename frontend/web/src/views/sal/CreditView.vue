<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageTable from '@/components/PageTable.vue'
import { fenToYuan, mdmApi, type Customer } from '@/api/client'
import { arApi, type CustomerCredit } from '@/api/salesV23'

/**
 * 客户信用控制（FR-V3-16 / FR-SAL-09）。
 *
 * 后端 CreditController 挂在 /customers/{id}/credit 上（GET 视图 + PUT 调整），
 * 口径：额度与金额全部是「分」；creditLimit=0 表示不设限（availableCredit 返回 -1）。
 * 敞口 = Σ应收余额 + Σ未出货订单未出金额；本页只查看与调整额度，建单时的
 * 授信校验在销售单链路里自动发生，不在此页。
 */

const customers = ref<Customer[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const keyword = ref('')
const loading = ref(false)
const error = ref('')

const selected = ref<Customer | null>(null)
const credit = ref<CustomerCredit | null>(null)
const creditLoading = ref(false)
const editOpen = ref(false)
const form = ref<{ limitYuan: number; days: number }>({ limitYuan: 0, days: 0 })

function errorMessage(cause: unknown): string {
  const e = cause as { response?: { data?: { message?: string } }; message?: string }
  return e?.response?.data?.message || e?.message || '请求失败'
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await mdmApi.listCustomers({ keyword: keyword.value || undefined, page: page.value, size: pageSize.value })
    customers.value = data.items
    total.value = data.total
  } catch (cause) {
    customers.value = []
    error.value = errorMessage(cause)
  } finally {
    loading.value = false
  }
}

async function selectCustomer(row: Customer) {
  selected.value = row
  creditLoading.value = true
  credit.value = null
  try {
    credit.value = await arApi.credit(row.id)
  } catch (cause) {
    ElMessage.error(errorMessage(cause))
  } finally {
    creditLoading.value = false
  }
}

function openEdit() {
  if (!selected.value) return
  form.value = {
    limitYuan: (credit.value?.creditLimit ?? selected.value.creditLimit) / 100,
    days: credit.value?.creditDays ?? selected.value.creditDays,
  }
  editOpen.value = true
}

async function saveCredit() {
  if (!selected.value) return
  try {
    credit.value = await arApi.updateCredit(selected.value.id, {
      creditLimit: Math.round(form.value.limitYuan * 100),
      creditDays: form.value.days,
    })
    editOpen.value = false
    ElMessage.success('信用额度已更新')
    void load()
  } catch (cause) {
    ElMessage.error(errorMessage(cause))
  }
}

function yuan(v: number | null | undefined): string {
  if (v == null) return '—'
  return `¥ ${fenToYuan(v)}`
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2 class="page-title">客户信用控制</h2>
      <div>
        <el-input v-model="keyword" placeholder="编码/名称" clearable style="width: 180px" @keyup.enter="load" />
        <el-button @click="load">刷新</el-button>
      </div>
    </div>
    <p class="page-hint">
      额度 0 = 不设限；敞口 = 应收余额 + 未出货订单金额，建单时由销售链路自动校验。
      本页查看额度/敞口/逾期并调整额度（金额单位：元，提交自动转分）。
    </p>

    <el-alert v-if="error" type="error" show-icon :closable="false" class="mb-16" :title="`客户加载失败：${error}`" />

    <div class="credit-layout">
      <PageTable
        :rows="customers"
        :loading="loading"
        :total="total"
        v-model:page="page"
        v-model:page-size="pageSize"
        :load-failed="!!error"
        empty-text="没有客户"
        table-size="small"
        class="customer-side"
        @change="load"
      >
        <template #header>
          <div class="table-title">客户 <span class="text-muted">点一行看信用视图</span></div>
        </template>
        <el-table-column label="编码" prop="code" width="100" />
        <el-table-column label="名称" min-width="130" prop="name" />
        <el-table-column label="等级" width="80" prop="level" />
        <el-table-column label="额度" width="110">
          <template #default="{ row }">{{ row.creditLimit === 0 ? '不设限' : yuan(row.creditLimit) }}</template>
        </el-table-column>
        <el-table-column label="账期" width="70">
          <template #default="{ row }">{{ row.creditDays }} 天</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="selectCustomer(row)">查看</el-button>
          </template>
        </el-table-column>
      </PageTable>

      <div class="detail-side" v-loading="creditLoading">
        <el-empty v-if="!selected" description="从左边选择一个客户" />
        <template v-else-if="credit">
          <div class="page-header">
            <h3 class="table-title">{{ selected.name }}（{{ selected.code }}）的信用视图</h3>
            <el-button type="primary" size="small" @click="openEdit">调整额度 / 账期</el-button>
          </div>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="信用额度">
              {{ credit.creditLimit === 0 ? '不设限（0）' : yuan(credit.creditLimit) }}
            </el-descriptions-item>
            <el-descriptions-item label="账期">{{ credit.creditDays }} 天</el-descriptions-item>
            <el-descriptions-item label="当前敞口">{{ yuan(credit.exposureAmount) }}</el-descriptions-item>
            <el-descriptions-item label="可用额度">
              <el-tag v-if="credit.availableCredit === -1" type="success">不设限</el-tag>
              <el-tag v-else :type="credit.availableCredit > 0 ? 'success' : 'danger'">
                {{ yuan(credit.availableCredit) }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="逾期金额">
              <el-tag :type="credit.overdueAmount > 0 ? 'danger' : 'info'">
                {{ yuan(credit.overdueAmount) }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="快照日期">{{ credit.snapshotDate ?? '—' }}</el-descriptions-item>
          </el-descriptions>
        </template>
      </div>
    </div>

    <el-dialog v-model="editOpen" title="调整信用额度与账期" width="420px">
      <el-form label-width="110px">
        <el-form-item label="额度（元）">
          <el-input-number v-model="form.limitYuan" :min="0" :precision="2" :step="1000" />
          <div class="text-muted">0 = 不设限</div>
        </el-form-item>
        <el-form-item label="账期（天）">
          <el-input-number v-model="form.days" :min="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editOpen = false">取消</el-button>
        <el-button type="primary" @click="saveCredit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.credit-layout {
  display: flex;
  gap: 16px;
  align-items: flex-start;
  margin-top: 12px;
}
.customer-side {
  flex: 0 0 52%;
  min-width: 0;
}
.detail-side {
  flex: 1 1 auto;
  min-width: 0;
  min-height: 220px;
}
.table-title {
  font-weight: 600;
}
</style>
