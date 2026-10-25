<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { skeletonApi, type NumberRule } from '@/api/skeleton'

/**
 * 单号规则（FR-V3-25）。
 *
 * ★ 编辑 prefix / 流水位长 / 启停；改完马上能"预览下一张单号"——
 *   预览只读 document_sequences 当前流水 +1，不会真的消费号（不会跳号）。
 * ★ 实际取号恒用 yyyyMMdd 日期键，预览也用今天的日期键，避免预览号与真实号错位。
 */

const rules = ref<NumberRule[]>([])
const loading = ref(false)
const error = ref('')

const dialog = reactive({
  visible: false,
  id: 0,
  docType: '',
  prefix: '',
  seqLength: 4,
  enabled: true,
})

const preview = reactive({
  visible: false,
  docType: '',
  sample: '',
  dayKey: '',
  loading: false,
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = await skeletonApi.numberRules()
    rules.value = res.items
  } catch (cause: unknown) {
    const e = cause as { response?: { data?: { message?: string } }; message?: string }
    error.value = e?.response?.data?.message || e?.message || '单号规则加载失败'
  } finally {
    loading.value = false
  }
}

function openEdit(row: NumberRule) {
  dialog.id = row.id
  dialog.docType = row.docType
  dialog.prefix = row.prefix
  dialog.seqLength = row.seqLength
  dialog.enabled = row.enabled
  dialog.visible = true
}

async function submit() {
  try {
    await skeletonApi.updateNumberRule(dialog.id, {
      prefix: dialog.prefix,
      seqLength: dialog.seqLength,
      enabled: dialog.enabled,
    })
    ElMessage.success('规则已保存')
    dialog.visible = false
    await load()
  } catch (cause: unknown) {
    const e = cause as { response?: { data?: { message?: string } }; message?: string }
    ElMessage.error(e?.response?.data?.message || e?.message || '保存失败')
  }
}

async function previewRule(row: NumberRule) {
  preview.visible = true
  preview.docType = row.docType
  preview.sample = ''
  preview.dayKey = ''
  preview.loading = true
  try {
    const r = await skeletonApi.previewNumberRule(row.docType)
    preview.sample = r.sample
    preview.dayKey = r.dayKey
  } catch (cause: unknown) {
    const e = cause as { response?: { data?: { message?: string } }; message?: string }
    ElMessage.error(e?.response?.data?.message || e?.message || '预览失败')
  } finally {
    preview.loading = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2 class="page-title">单号规则</h2>
      <el-button @click="load">刷新</el-button>
    </div>
    <p class="page-hint">单据类型的前缀、流水位长与启停可改；预览只展示下一张单号样式，不消耗流水。</p>

    <el-alert
      v-if="error"
      type="error"
      show-icon
      :closable="false"
      class="mb-16"
      :title="`加载失败：${error}`"
    >
      <el-button size="small" @click="load">重试</el-button>
    </el-alert>

    <el-table v-loading="loading" :data="rules" border size="small">
      <el-table-column label="单据类型" prop="docType" min-width="160" />
      <el-table-column label="前缀" prop="prefix" width="120" />
      <el-table-column label="日期格式" prop="datePattern" width="120" />
      <el-table-column label="流水位长" prop="seqLength" width="100" />
      <el-table-column label="重置周期" prop="resetCycle" width="100" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
            {{ row.enabled ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="primary" @click="previewRule(row)">预览</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialog.visible" title="编辑单号规则" width="420px">
      <el-form label-width="90px">
        <el-form-item label="单据类型">
          <el-input :model-value="dialog.docType" disabled />
        </el-form-item>
        <el-form-item label="前缀" required>
          <el-input v-model="dialog.prefix" />
        </el-form-item>
        <el-form-item label="流水位长" required>
          <el-input-number v-model="dialog.seqLength" :min="2" :max="12" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="dialog.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" @click="submit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="preview.visible" title="预览下一张单号" width="360px">
      <el-skeleton v-if="preview.loading" :rows="2" animated />
      <template v-else>
        <p>单据类型：{{ preview.docType }}</p>
        <p>日期键：{{ preview.dayKey }}</p>
        <p class="sample">示例：<strong>{{ preview.sample }}</strong></p>
        <p class="hint">仅预览，不会占用此单号。</p>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-hint {
  margin: 6px 0 12px;
}

.sample {
  font-size: 15px;
  color: #303133;
}

.hint {
  font-size: 12px;
  color: #909399;
}
</style>
