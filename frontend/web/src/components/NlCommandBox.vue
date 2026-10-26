<script setup lang="ts">
import { computed, ref } from 'vue'
import { nlApi, type NlCommandResult } from '@/api/client'

/**
 * 工作台的自然语言操作入口（FR-SYS-04）。
 *
 * 组件只负责输入、调用与展示：
 * - 权限、幂等键、业务规则全部在后端（NL 层只解析 + 调度 ApplicationService）；
 * - 写意图（建进货/出货草稿）同样走这个入口，但示例 chips 只放只读指令，
 *   避免用户误点产生单据。
 */

interface FlatRow {
  [key: string]: unknown
}

const command = ref('')
const loading = ref(false)
const error = ref('')
const result = ref<NlCommandResult | null>(null)

const period = (() => {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
})()

/**
 * 示例指令 —— 只能放 `NlIntent` 里真实存在、且 stub 解析器认得的写法。
 *
 * 后端当前支持 8 个意图：help / query_inventory / list_documents / get_document /
 * inventory_summary / query_master_data（只读）+ create_purchase_order /
 * create_sales_order（写，仅建草稿）。这里刻意不放写意图的示例，
 * 避免用户误点产生单据；想知道完整能力就点「帮助」，它直接问后端要意图清单。
 */
const examples = ['帮助', '查询库存', '进货单列表 第1页', `账期 ${period} 进销存汇总`, '查询商品 关键字 T恤']

const items = computed<FlatRow[]>(() => {
  const data = result.value?.data
  if (data && typeof data === 'object') {
    // 读意图返回 data.items；help 返回 data.intents（意图清单），两者都是列表，同样用表格渲染。
    for (const key of ['items', 'intents']) {
      const list = (data as Record<string, unknown>)[key]
      if (Array.isArray(list)) {
        return list.filter((item): item is FlatRow => !!item && typeof item === 'object')
      }
    }
  }
  return []
})

/** 动态列只取标量字段；嵌套的 lines 等对象/数组留给原始 JSON。 */
const columns = computed(() => {
  const first = items.value[0]
  if (!first) return []
  return Object.keys(first).filter((key) => {
    const value = first[key]
    return value === null || typeof value !== 'object'
  })
})

const pretty = computed(() =>
  result.value ? JSON.stringify(result.value.data, null, 2) : '',
)

function useExample(text: string): void {
  command.value = text
}

function display(value: unknown): string {
  if (value === null || value === undefined) return '-'
  return String(value)
}

async function run(): Promise<void> {
  const text = command.value.trim()
  if (!text) {
    error.value = '请输入一条指令，例如「查询库存」'
    return
  }
  loading.value = true
  error.value = ''
  result.value = null
  try {
    result.value = await nlApi.command(text)
  } catch (e) {
    // client.ts 已经弹过统一 toast；这里保留行内错误，便于对照指令修改
    error.value = (e as Error).message || '指令执行失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <el-card shadow="never" class="mb-16 nl-card">
    <template #header>
      <div class="nl-header">
        <span>自然语言操作</span>
        <span class="nl-hint">
          支持 查询库存 / 单据列表与详情 / 进销存汇总 / 主数据查询，以及建进货、出货**草稿**；
          点「帮助」可让后端列出当前真实可用的意图
        </span>
      </div>
    </template>

    <div class="nl-input">
      <el-input
        v-model="command"
        type="textarea"
        :rows="2"
        maxlength="500"
        show-word-limit
        resize="none"
        placeholder="用一句话操作，例如：查询 SKU 12 在仓库 3 的库存"
        @keydown.enter.exact.prevent="run"
      />
      <el-button type="primary" :loading="loading" class="nl-run" @click="run">
        执行
      </el-button>
    </div>

    <div class="nl-examples">
      <span class="text-muted mr-8">示例：</span>
      <el-tag
        v-for="example in examples"
        :key="example"
        class="mr-8 mb-8 nl-example"
        type="info"
        size="small"
        @click="useExample(example)"
      >
        {{ example }}
      </el-tag>
    </div>

    <el-alert
      v-if="error"
      type="error"
      show-icon
      :closable="false"
      class="mt-8"
      :title="error"
    />

    <div v-if="result" class="nl-result">
      <div class="nl-result-head">
        <el-tag :type="result.write ? 'warning' : 'success'" size="small">
          {{ result.intent }}
        </el-tag>
        <el-tag v-if="result.write" type="warning" size="small" effect="plain">写操作</el-tag>
        <span class="nl-description">{{ result.description }}</span>
      </div>

      <el-table
        v-if="items.length && columns.length"
        :data="items"
        size="small"
        border
        class="mt-8"
        max-height="320"
      >
        <el-table-column
          v-for="column in columns"
          :key="column"
          :prop="column"
          :label="column"
          min-width="110"
        >
          <template #default="{ row }">{{ display(row[column]) }}</template>
        </el-table-column>
      </el-table>

      <el-collapse class="mt-8">
        <el-collapse-item title="查看原始结果（JSON）" name="raw">
          <pre class="nl-json">{{ pretty }}</pre>
        </el-collapse-item>
      </el-collapse>
    </div>
  </el-card>
</template>

<style scoped>
.nl-header {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 12px;
  flex-wrap: wrap;
}

.nl-hint {
  font-size: 12px;
  color: #909399;
}

.nl-input {
  display: flex;
  gap: 12px;
  align-items: flex-end;
}

.nl-run {
  flex: 0 0 auto;
}

.nl-examples {
  margin-top: 8px;
}

.nl-example {
  cursor: pointer;
}

.mb-8 {
  margin-bottom: 8px;
}

.mt-8 {
  margin-top: 8px;
}

.nl-result {
  margin-top: 12px;
  border-top: 1px solid #ebeef5;
  padding-top: 12px;
}

.nl-result-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.nl-description {
  font-size: 13px;
  color: #303133;
}

.nl-json {
  margin: 0;
  max-height: 320px;
  overflow: auto;
  font-size: 12px;
  line-height: 1.5;
  background: #f5f7fa;
  padding: 8px;
  border-radius: 4px;
}

@media (max-width: 768px) {
  .nl-input {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
