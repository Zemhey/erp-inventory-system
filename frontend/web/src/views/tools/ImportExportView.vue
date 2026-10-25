<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageTable from '@/components/PageTable.vue'
import { mdmApi, type Customer, type Product, type Supplier } from '@/api/client'

/**
 * 导入导出中心（FR-V3-28，本期为纯前端通用 CSV 方案）。
 *
 * 为什么没有 POST /sys/import/{entity}：系统工具的后端包（com.erp.sys 域）
 * 不在本泳道文件所有权内（W1 地盘），为不越界，导入改为
 * 「浏览器解析 CSV → 逐行调用既有 mdm 创建接口」。语义等价：
 *   校验（必填/编码本地查重）→ 只提交合法行 → 逐行成功/失败与原因原样透出。
 * ★ 已知差异：合法行仍可能被后端拒绝（如编码已被占用），失败原因会显示在结果里；
 *   "失败行不入库"由「逐行提交、非法行根本不提交」保证，不会出现半截脏数据。
 * 导出复用各列表接口 + 浏览器端拼 CSV 下载（报表导出仍走 rpt 既有能力）。
 */

type Entity = 'product' | 'customer' | 'supplier'
const entity = ref<Entity>('product')
const csvText = ref('')
const parsed = ref<string[][]>([])
const results = ref<{ line: number; content: string; ok: boolean; reason: string }[]>([])
const running = ref(false)

const templates: Record<Entity, { file: string; text: string }> = {
  product: { file: 'product-template.csv', text: 'code,name,category,season\nP-DEMO-1,示范商品,上衣,春夏\n' },
  customer: { file: 'customer-template.csv', text: 'code,name,phone,level\nC-DEMO-1,示范客户,13800000000,NORMAL\n' },
  supplier: { file: 'supplier-template.csv', text: 'code,name,contactName,contactPhone\nS-DEMO-1,示范供应商,张三,13900000000\n' },
}

const headers: Record<Entity, string[]> = {
  product: ['code', 'name', 'category', 'season'],
  customer: ['code', 'name', 'phone', 'level'],
  supplier: ['code', 'name', 'contactName', 'contactPhone'],
}

function errorMessage(cause: unknown): string {
  const e = cause as { response?: { data?: { message?: string } }; message?: string }
  return e?.response?.data?.message || e?.message || '请求失败'
}

function download(filename: string, content: string) {
  const blob = new Blob(['\uFEFF' + content], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

function parseCsv(text: string): string[][] {
  return text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line.length > 0)
    .map((line) => line.split(',').map((c) => c.trim()))
}

function preview() {
  results.value = []
  parsed.value = parseCsv(csvText.value)
  if (parsed.value.length < 2) {
    ElMessage.warning('至少要有表头 + 1 行数据')
    parsed.value = []
    return
  }
  const head = parsed.value[0].map((h) => h.toLowerCase())
  const want = headers[entity.value].map((h) => h.toLowerCase())
  if (!want.every((w) => head.includes(w))) {
    ElMessage.error(`表头不符，需要列：${headers[entity.value].join(', ')}`)
    parsed.value = []
    return
  }
}

async function runImport() {
  const head = parsed.value[0].map((h) => h.toLowerCase())
  const body = parsed.value.slice(1)
  const codes = new Set<string>()
  running.value = true
  results.value = []
  try {
    for (let i = 0; i < body.length; i++) {
      const lineNo = i + 2 // CSV 里的物理行号（表头是第 1 行）
      const cols = body[i]
      const get = (key: string) => {
        const idx = head.indexOf(key.toLowerCase())
        return idx >= 0 ? (cols[idx] ?? '') : ''
      }
      const code = get('code')
      const name = get('name')
      const content = cols.join(',')
      if (!code || !name) {
        results.value.push({ line: lineNo, content, ok: false, reason: 'code/name 为必填' })
        continue
      }
      if (codes.has(code)) {
        results.value.push({ line: lineNo, content, ok: false, reason: `编码 ${code} 在本次文件内重复` })
        continue
      }
      codes.add(code)
      try {
        if (entity.value === 'product') {
          await mdmApi.createProduct({ code, name, category: get('category') || undefined, season: get('season') || undefined })
        } else if (entity.value === 'customer') {
          await mdmApi.createCustomer({ code, name, phone: get('phone') || undefined, level: get('level') || undefined })
        } else {
          await mdmApi.createSupplier({ code, name, contactName: get('contactname') || undefined, contactPhone: get('contactphone') || undefined })
        }
        results.value.push({ line: lineNo, content, ok: true, reason: '已创建' })
      } catch (cause) {
        results.value.push({ line: lineNo, content, ok: false, reason: errorMessage(cause) })
      }
    }
    const okCount = results.value.filter((r) => r.ok).length
    ElMessage.success(`导入完成：成功 ${okCount} 条、失败 ${results.value.length - okCount} 条`)
  } finally {
    running.value = false
  }
}

async function exportCurrent() {
  try {
    let text = ''
    let file = ''
    if (entity.value === 'product') {
      const data = await mdmApi.listProducts({ page: 1, size: 500 })
      text = ['id,code,name,category,season,enabled', ...data.items.map((p: Product) => `${p.id},${p.code},${p.name},${p.category ?? ''},${p.season ?? ''},${p.enabled}`)].join('\n')
      file = 'products.csv'
    } else if (entity.value === 'customer') {
      const data = await mdmApi.listCustomers({ page: 1, size: 500 })
      text = ['id,code,name,phone,level,enabled', ...data.items.map((c: Customer) => `${c.id},${c.code},${c.name},${c.phone ?? ''},${c.level},${c.enabled}`)].join('\n')
      file = 'customers.csv'
    } else {
      const data = await mdmApi.listSuppliers({ page: 1, size: 500 })
      text = ['id,code,name,contactName,contactPhone,enabled', ...data.items.map((s: Supplier) => `${s.id},${s.code},${s.name},${s.contactName ?? ''},${s.contactPhone ?? ''},${s.enabled}`)].join('\n')
      file = 'suppliers.csv'
    }
    download(file, text)
  } catch (cause) {
    ElMessage.error(errorMessage(cause))
  }
}
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2 class="page-title">导入导出中心</h2>
      <el-button @click="exportCurrent">导出当前对象 CSV</el-button>
    </div>
    <p class="page-hint">
      导入三步：选对象 → 贴 CSV（或下载模板照填）→ 校验并逐行提交；失败行给出原因、不会入库。
      走的是各主数据既有创建接口（本期未新增后端导入端点，原因见页面代码注释）。
    </p>

    <el-radio-group v-model="entity" class="mb-16" @change="() => { parsed = []; results = [] }">
      <el-radio-button value="product">商品</el-radio-button>
      <el-radio-button value="customer">客户</el-radio-button>
      <el-radio-button value="supplier">供应商</el-radio-button>
    </el-radio-group>

    <div class="step-bar">
      <el-button size="small" @click="() => { csvText = templates[entity].text; parsed = []; results = [] }">
        填入模板示例
      </el-button>
      <el-button size="small" @click="download(templates[entity].file, templates[entity].text)">
        下载模板 CSV
      </el-button>
    </div>

    <el-input v-model="csvText" type="textarea" :rows="8" placeholder="粘贴 CSV 文本（第一行必须是表头）" />

    <div class="step-bar">
      <el-button type="primary" @click="preview">① 解析校验</el-button>
      <el-button type="success" :disabled="parsed.length < 2" :loading="running" @click="runImport">
        ② 提交导入（{{ Math.max(parsed.length - 1, 0) }} 行）
      </el-button>
    </div>

    <PageTable
      v-if="results.length"
      :rows="results"
      :loading="running"
      :paginated="false"
      empty-text="没有结果"
      table-size="small"
    >
      <template #header>
        <div class="table-title">
          导入结果
          <span class="text-muted">
            成功 {{ results.filter((r) => r.ok).length }} / 失败 {{ results.filter((r) => !r.ok).length }}
          </span>
        </div>
      </template>
      <el-table-column label="行号" prop="line" width="70" />
      <el-table-column label="内容" prop="content" min-width="220" />
      <el-table-column label="结果" width="90">
        <template #default="{ row }">
          <el-tag :type="row.ok ? 'success' : 'danger'" size="small">{{ row.ok ? '成功' : '失败' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="原因" prop="reason" min-width="200" />
    </PageTable>
  </div>
</template>

<style scoped>
.step-bar {
  display: flex;
  gap: 8px;
  margin: 10px 0;
}
.table-title {
  font-weight: 600;
}
</style>
