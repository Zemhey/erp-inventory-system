<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  fenToYuan,
  purApi,
  requisitionApi,
  type PurchaseOrder,
  type PurchaseRequisition,
} from '@/api/client'

/**
 * 打印模板（FR-V3-26，本期为纯前端渲染方案）。
 *
 * ★ 诚实说明：没有 sys_print_template 表与 /sys/print-templates 后端 ——
 *   sys 域后端包不在本泳道所有权内。模板 = HTML + {{占位符}}，
 *   自定义模板存浏览器 localStorage（本机持久），数据来自现有单据详情接口，
 *   打印走 iframe + window.print()（@media print 由浏览器处理）。
 *   合并后若 W1 提供模板 CRUD 端点，只需把 localStorage 换成接口读写。
 */

type DocKind = 'purchase-order' | 'purchase-requisition' | 'receipt'

const docKind = ref<DocKind>('purchase-order')
const docs = ref<{ id: number; label: string }[]>([])
const pickedId = ref<number | null>(null)
const docData = ref<Record<string, unknown> | null>(null)
const lines = ref<{ skuId: number; qty: number; unitPriceAmount?: number; amount?: number; batchNo?: string; lineNo?: number }[]>([])
const loading = ref(false)

const TEMPLATE_KEY = 'v33.print.templates.v1'

const builtinTemplates: Record<string, string> = {
  '进货单（标准）': `
<h2 style="text-align:center">{{title}}</h2>
<p>单号：<b>{{doc.orderNo}}</b>　状态：{{doc.status}}　业务日期：{{doc.bizDate}}</p>
<p>供应商：#{{doc.supplierId}}　仓库：#{{doc.warehouseId}}　总金额：¥{{doc.totalAmountYuan}}</p>
<table border="1" cellspacing="0" cellpadding="6" style="border-collapse:collapse;width:100%">
<tr><th>行</th><th>SKU</th><th>数量</th><th>单价(元)</th><th>金额(元)</th></tr>
{{#lines}}
<tr><td>{{lineNo}}</td><td>#{{skuId}}</td><td>{{qty}}</td><td>{{unitPriceYuan}}</td><td>{{amountYuan}}</td></tr>
{{/lines}}
</table>
<p style="margin-top:16px">备注：{{doc.remark}}</p>
<p style="text-align:right">打印日期：{{printDate}}</p>`,
  '进货单（简易）': `
<h3>{{title}} {{doc.orderNo}}</h3>
<p>{{doc.bizDate}} · 供应商 #{{doc.supplierId}} · 合计 ¥{{doc.totalAmountYuan}} × {{doc.totalQty}} 件</p>
<ul>{{#lines}}<li>#{{skuId}} × {{qty}}（¥{{amountYuan}}）</li>{{/lines}}</ul>`,
}

const templateNames = ref<string[]>(Object.keys(builtinTemplates))
const templateName = ref(templateNames.value[0])
const custom = ref('')
const isCustom = computed(() => templateName.value === '__custom__')
const html = ref('')
const phText =
  '输入 HTML 模板，可用占位符：{title} {doc.orderNo} {doc.bizDate} {doc.status} ' +
  '{doc.supplierId} {doc.warehouseId} {doc.totalAmountYuan} {doc.totalQty} {doc.remark} {printDate}，' +
  '行区块 {#lines}…{/lines}（行内：lineNo skuId qty unitPriceYuan amountYuan batchNo）。' +
  '书写时把花括号写成双层的 {{ }}。'

function errorMessage(cause: unknown): string {
  const e = cause as { response?: { data?: { message?: string } }; message?: string }
  return e?.response?.data?.message || e?.message || '请求失败'
}

async function loadDocs() {
  docs.value = []
  pickedId.value = null
  docData.value = null
  lines.value = []
  try {
    if (docKind.value === 'purchase-order') {
      const data = await purApi.list({ page: 1, size: 100 })
      docs.value = data.items.map((o: PurchaseOrder) => ({ id: o.id, label: `${o.orderNo} · ${o.status}` }))
    } else {
      const data = await requisitionApi.list({ page: 1, size: 100 })
      docs.value = data.items.map((o: PurchaseRequisition) => ({ id: o.id, label: `${o.orderNo} · ${o.status}` }))
    }
  } catch (cause) {
    ElMessage.error(errorMessage(cause))
  }
}

async function loadDoc(id: number) {
  loading.value = true
  try {
    if (docKind.value === 'purchase-order') {
      const o = await purApi.get(id)
      docData.value = { ...o, totalAmountYuan: fenToYuan(o.totalAmount) }
      lines.value = (o.lines ?? []).map((l) => ({
        skuId: l.skuId, qty: l.qty, lineNo: l.lineNo,
        unitPriceAmount: l.unitPriceAmount, amount: l.amount,
      }))
    } else {
      const r = await requisitionApi.get(id)
      docData.value = { ...(r as unknown as Record<string, unknown>) }
      lines.value = (((r as unknown as { lines?: { skuId: number; qty: number; lineNo?: number }[] }).lines) ?? [])
        .map((l) => ({ skuId: l.skuId, qty: l.qty, lineNo: l.lineNo }))
    }
    render()
  } catch (cause) {
    ElMessage.error(errorMessage(cause))
  } finally {
    loading.value = false
  }
}

function render() {
  const tpl = isCustom.value ? custom.value : builtinTemplates[templateName.value] ?? ''
  html.value = fill(tpl)
}

function fill(tpl: string): string {
  const d = docData.value ?? {}
  let out = tpl
  out = out.replace(/\{\{title\}\}/g, docKind.value === 'purchase-order' ? '进货单' : '请购单')
  out = out.replace(/\{\{printDate\}\}/g, new Date().toLocaleString())
  out = out.replace(/\{\{doc\.(\w+)\}\}/g, (_m, key: string) => String((d as Record<string, unknown>)[key] ?? '—'))
  const block = /\{\{#lines\}\}([\s\S]*?)\{\{\/lines\}\}/
  const m = out.match(block)
  if (m) {
    const rowTpl = m[1]
    const rows = lines.value
      .map((l) =>
        rowTpl
          .replace(/\{\{lineNo\}\}/g, String(l.lineNo ?? ''))
          .replace(/\{\{skuId\}\}/g, String(l.skuId))
          .replace(/\{\{qty\}\}/g, String(l.qty))
          .replace(/\{\{batchNo\}\}/g, l.batchNo ?? '')
          .replace(/\{\{unitPriceYuan\}\}/g, l.unitPriceAmount != null ? fenToYuan(l.unitPriceAmount) : '—')
          .replace(/\{\{amountYuan\}\}/g, l.amount != null ? fenToYuan(l.amount) : '—'),
      )
      .join('')
    out = out.replace(block, rows)
  } else {
    out = out.replace(/\{\{#lines\}\}[\s\S]*?\{\{\/lines\}\}/g, '')
  }
  return out
}

function saveCustom() {
  const store = JSON.parse(localStorage.getItem(TEMPLATE_KEY) ?? '{}')
  store[templateName.value === '__custom__' ? '自定义' : templateName.value] = custom.value
  localStorage.setItem(TEMPLATE_KEY, JSON.stringify(store))
  ElMessage.success('模板已保存到本机（localStorage）')
}

function printIt() {
  const frame = document.createElement('iframe')
  frame.style.position = 'fixed'
  frame.style.right = '0'
  frame.style.bottom = '0'
  frame.style.width = '0'
  frame.style.height = '0'
  frame.style.border = '0'
  document.body.appendChild(frame)
  const doc = frame.contentDocument
  if (!doc) {
    ElMessage.error('无法创建打印视图')
    document.body.removeChild(frame)
    return
  }
  doc.open()
  doc.write(`<html><head><title>打印</title></head><body onload="window.print()">${html.value}</body></html>`)
  doc.close()
  setTimeout(() => {
    frame.contentWindow?.print()
    setTimeout(() => document.body.removeChild(frame), 800)
  }, 120)
}

watch(docKind, loadDocs)
watch(templateName, render)
watch(custom, render)

onMounted(async () => {
  const store = JSON.parse(localStorage.getItem(TEMPLATE_KEY) ?? '{}')
  const names = Object.keys(store)
  if (names.length) {
    templateNames.value = [...templateNames.value, ...names]
    custom.value = store[names[0]] ?? ''
    templateName.value = names[0]
  }
  await loadDocs()
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2 class="page-title">打印模板</h2>
      <el-button type="primary" :disabled="!html" @click="printIt">打印 / 另存 PDF</el-button>
    </div>
    <p class="page-hint">
      模板 = HTML + 双花括号占位符（doc.字段 / lines 区块，详见编辑框提示）。
      本期模板存本机（sys 模板表不在本泳道后端所有权内），数据取自真实单据接口。
    </p>

    <div class="picker-row">
      <el-select v-model="docKind" style="width: 140px">
        <el-option label="进货单" value="purchase-order" />
        <el-option label="请购单" value="purchase-requisition" />
      </el-select>
      <el-select v-model="pickedId" filterable placeholder="选择单据" style="width: 260px" @change="loadDoc">
        <el-option v-for="d in docs" :key="d.id" :label="d.label" :value="d.id" />
      </el-select>
      <el-select v-model="templateName" style="width: 190px">
        <el-option v-for="t in templateNames" :key="t" :label="t" :value="t" />
        <el-option label="✎ 自定义编辑" value="__custom__" />
      </el-select>
      <el-button v-if="isCustom" size="small" @click="saveCustom">保存模板到本机</el-button>
    </div>

    <el-input
      v-if="isCustom"
      v-model="custom"
      type="textarea"
      :rows="10"
      :placeholder="phText"
    />

    <div v-loading="loading" class="preview">
      <div class="preview-title">预览</div>
      <iframe v-if="html" :srcdoc="html" sandbox=""></iframe>
      <el-empty v-else description="选择一张单据以预览" />
    </div>
  </div>
</template>

<style scoped>
.picker-row {
  display: flex;
  gap: 8px;
  margin: 10px 0;
}
.preview {
  margin-top: 8px;
}
.preview iframe {
  width: 100%;
  height: 480px;
  border: 1px solid var(--el-border-color);
  background: #fff;
}
.preview-title {
  font-weight: 600;
  margin-bottom: 6px;
}
</style>
