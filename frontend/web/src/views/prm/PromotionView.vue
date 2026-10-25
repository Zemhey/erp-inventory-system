<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageTable from '@/components/PageTable.vue'
import { promotionApi, type Promotion, type PromotionCreate } from '@/api/prmV33'

/**
 * 促销活动（FR-V3-21）。
 *
 * 台账式管理：规则的条件/利益以 JSON 字符串录入与回显（作业口径），
 * 本期不接入出货算价链路 —— 页面上如实标注，避免"看起来生效其实没生效"。
 */

const rows = ref<Promotion[]>([])
const loading = ref(false)
const error = ref('')
const statusFilter = ref<string | undefined>(undefined)

const editOpen = ref(false)
const editId = ref<number | null>(null)
const form = ref(blankForm())

function blankForm() {
  return {
    code: '',
    name: '',
    promoType: 'FULL_REDUCE' as Promotion['promoType'],
    startTime: null as string | null,
    endTime: null as string | null,
    priority: 0,
    remark: '',
    rules: [{ conditionJson: '{"amountGte":100000}', benefitJson: '{"reduce":10000}' }],
  }
}

function errorMessage(cause: unknown): string {
  const e = cause as { response?: { data?: { message?: string } }; message?: string }
  return e?.response?.data?.message || e?.message || '请求失败'
}

const typeLabel: Record<Promotion['promoType'], string> = {
  TIME_DISCOUNT: '限时折扣',
  FULL_REDUCE: '满减',
  QTY_DISCOUNT: '数量折扣',
}
const statusLabel: Record<Promotion['status'], { text: string; type: 'info' | 'success' | 'warning' }> = {
  DRAFT: { text: '草稿', type: 'info' },
  ACTIVE: { text: '生效中', type: 'success' },
  STOPPED: { text: '已停用', type: 'warning' },
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    rows.value = (await promotionApi.list({ status: statusFilter.value })).items
  } catch (cause) {
    rows.value = []
    error.value = errorMessage(cause)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editId.value = null
  form.value = blankForm()
  editOpen.value = true
}

function openEdit(row: Promotion) {
  editId.value = row.id
  form.value = {
    code: row.code,
    name: row.name,
    promoType: row.promoType,
    startTime: row.startTime,
    endTime: row.endTime,
    priority: row.priority,
    remark: row.remark ?? '',
    rules: row.rules.length
      ? row.rules.map((r) => ({ conditionJson: r.conditionJson ?? '', benefitJson: r.benefitJson ?? '' }))
      : [{ conditionJson: '', benefitJson: '' }],
  }
  editOpen.value = true
}

async function save() {
  const rules = form.value.rules
    .map((r) => ({ conditionJson: r.conditionJson, benefitJson: r.benefitJson }))
    .filter((r) => r.conditionJson || r.benefitJson)
  for (const r of rules) {
    try {
      JSON.parse(r.conditionJson || 'null')
      JSON.parse(r.benefitJson || 'null')
    } catch {
      ElMessage.error('规则 JSON 不合法，请先修正（条件/利益都必须是合法 JSON）')
      return
    }
  }
  const body: PromotionCreate = {
    code: form.value.code,
    name: form.value.name,
    promoType: form.value.promoType,
    startTime: form.value.startTime,
    endTime: form.value.endTime,
    priority: form.value.priority,
    remark: form.value.remark || undefined,
    rules,
  }
  try {
    if (editId.value == null) await promotionApi.create(body)
    else await promotionApi.update(editId.value, body)
    editOpen.value = false
    ElMessage.success('活动已保存')
    void load()
  } catch (cause) {
    ElMessage.error(errorMessage(cause))
  }
}

async function transition(row: Promotion, activate: boolean) {
  await ElMessageBox.confirm(
    activate ? `激活活动 ${row.code}？（台账生效，算价链路本期未接入）` : `停用活动 ${row.code}？`,
    '状态确认',
    { type: 'warning' },
  )
  try {
    if (activate) await promotionApi.activate(row.id)
    else await promotionApi.stop(row.id)
    ElMessage.success('状态已更新')
    void load()
  } catch (cause) {
    ElMessage.error(errorMessage(cause))
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2 class="page-title">促销活动</h2>
      <div>
        <el-select v-model="statusFilter" clearable placeholder="全部状态" style="width: 130px" @change="load">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="生效中" value="ACTIVE" />
          <el-option label="已停用" value="STOPPED" />
        </el-select>
        <el-button @click="load">刷新</el-button>
        <el-button type="primary" @click="openCreate">新建活动</el-button>
      </div>
    </div>
    <el-alert
      type="info"
      show-icon
      :closable="false"
      class="mb-16"
      title="台账口径：活动的创建/激活/停用在这里管理；出货单算价本期未接入促销规则（详见 V3-3 报告），JSON 规则原样存储与回显。"
    />

    <el-alert v-if="error" type="error" show-icon :closable="false" class="mb-16" :title="`加载失败：${error}`" />

    <PageTable :rows="rows" :loading="loading" :paginated="false" :load-failed="!!error" empty-text="还没有促销活动" empty-hint="示例：满 1000 减 100 → 条件 {&quot;amountGte&quot;:100000}，利益 {&quot;reduce&quot;:10000}（金额单位：分）。">
      <el-table-column label="编码" prop="code" width="120" />
      <el-table-column label="名称" min-width="140" prop="name" />
      <el-table-column label="类型" width="100">
        <template #default="{ row }">{{ typeLabel[row.promoType as Promotion['promoType']] }}</template>
      </el-table-column>
      <el-table-column label="起止" width="300">
        <template #default="{ row }">{{ row.startTime ?? '不限' }} ~ {{ row.endTime ?? '不限' }}</template>
      </el-table-column>
      <el-table-column label="优先级" prop="priority" width="80" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusLabel[row.status as Promotion['status']].type" size="small">
            {{ statusLabel[row.status as Promotion['status']].text }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="规则" min-width="200">
        <template #default="{ row }">
          <div v-for="r in row.rules" :key="r.id" class="rule-line">
            {{ r.conditionJson }} ⇒ {{ r.benefitJson }}
          </div>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="row.status !== 'ACTIVE'" link type="success" @click="transition(row, true)">激活</el-button>
          <el-button v-else link type="warning" @click="transition(row, false)">停用</el-button>
        </template>
      </el-table-column>
    </PageTable>

    <el-dialog v-model="editOpen" :title="editId == null ? '新建促销活动' : '编辑促销活动'" width="640px">
      <el-form label-width="90px">
        <el-form-item label="编码">
          <el-input v-model="form.code" :disabled="editId != null" style="width: 220px" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="form.name" style="width: 300px" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.promoType" style="width: 200px">
            <el-option label="限时折扣" value="TIME_DISCOUNT" />
            <el-option label="满减" value="FULL_REDUCE" />
            <el-option label="数量折扣" value="QTY_DISCOUNT" />
          </el-select>
        </el-form-item>
        <el-form-item label="开始">
          <el-date-picker v-model="form.startTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" />
        </el-form-item>
        <el-form-item label="结束">
          <el-date-picker v-model="form.endTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" />
        </el-form-item>
        <el-form-item label="优先级">
          <el-input-number v-model="form.priority" :min="0" />
        </el-form-item>
        <el-form-item label="规则">
          <div style="width: 100%">
            <div v-for="(r, idx) in form.rules" :key="idx" class="rule-edit">
              <el-input v-model="r.conditionJson" placeholder="条件 JSON，如 {&quot;amountGte&quot;:100000}" />
              <span>⇒</span>
              <el-input v-model="r.benefitJson" placeholder="利益 JSON，如 {&quot;reduce&quot;:10000}" />
              <el-button link type="danger" :disabled="form.rules.length === 1" @click="form.rules.splice(idx, 1)">删</el-button>
            </div>
            <el-button size="small" @click="form.rules.push({ conditionJson: '', benefitJson: '' })">添加规则</el-button>
            <div class="text-muted">金额类字段单位为「分」（100000 = ¥1000）。</div>
          </div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" maxlength="500" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editOpen = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.rule-line {
  font-family: monospace;
  font-size: 12px;
}
.rule-edit {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
}
</style>
