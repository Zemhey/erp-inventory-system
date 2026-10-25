<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { skeletonApi, type SystemConfig } from '@/api/skeleton'

/**
 * 系统参数（FR-V3-24）。
 *
 * ★ 系统参数只暴露"改值"这一种写操作：键、类型、分组都是迁移种子锁死的，
 *   前端不提供新增/删除，避免把维护参数变成"随便加字段"。
 * ★ 列表按 configGroup 分组展示，编辑值通过弹窗提交到 PUT /sys-config/{id}。
 */

const configs = ref<SystemConfig[]>([])
const loading = ref(false)
const error = ref('')
const groupFilter = ref<string | null>(null)

const grouped = computed(() => {
  const list = groupFilter.value
    ? configs.value.filter((c) => c.configGroup === groupFilter.value)
    : configs.value
  const map = new Map<string, SystemConfig[]>()
  for (const c of list) {
    const arr = map.get(c.configGroup) ?? []
    arr.push(c)
    map.set(c.configGroup, arr)
  }
  return Array.from(map.entries())
})

const groups = computed(() => Array.from(new Set(configs.value.map((c) => c.configGroup))))

const dialog = reactive({
  visible: false,
  id: 0,
  key: '',
  value: '',
  type: '',
})

function openEdit(row: SystemConfig) {
  dialog.id = row.id
  dialog.key = row.configKey
  dialog.value = row.configValue
  dialog.type = row.valueType
  dialog.visible = true
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = await skeletonApi.configs()
    configs.value = res.items
  } catch (cause: unknown) {
    const e = cause as { response?: { data?: { message?: string } }; message?: string }
    error.value = e?.response?.data?.message || e?.message || '参数加载失败'
  } finally {
    loading.value = false
  }
}

async function submit() {
  try {
    await skeletonApi.updateConfig(dialog.id, dialog.value)
    ElMessage.success('参数值已保存')
    dialog.visible = false
    await load()
  } catch (cause: unknown) {
    const e = cause as { response?: { data?: { message?: string } }; message?: string }
    ElMessage.error(e?.response?.data?.message || e?.message || '保存失败')
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2 class="page-title">系统参数</h2>
      <div>
        <el-select
          v-model="groupFilter"
          placeholder="全部分组"
          clearable
          size="small"
          class="group-select"
        >
          <el-option v-for="g in groups" :key="g" :label="g" :value="g" />
        </el-select>
        <el-button @click="load">刷新</el-button>
      </div>
    </div>
    <p class="page-hint">系统参数由迁移种子预置，仅可修改「值」；键、类型与分组不可变。</p>

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

    <el-skeleton v-if="loading" :rows="6" animated />
    <template v-else>
      <el-card
        v-for="[group, rows] in grouped"
        :key="group"
        shadow="never"
        class="mb-16"
      >
        <template #header><span>{{ group }}（{{ rows.length }} 项）</span></template>
        <el-table :data="rows" border size="small">
          <el-table-column label="键" prop="configKey" min-width="160" />
          <el-table-column label="值" prop="configValue" min-width="160" />
          <el-table-column label="类型" prop="valueType" width="100" />
          <el-table-column label="说明" prop="remark" min-width="180">
            <template #default="{ row }">{{ row.remark || '—' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openEdit(row)">编辑值</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
      <el-empty v-if="!grouped.length" description="没有系统参数" />
    </template>

    <el-dialog v-model="dialog.visible" title="编辑参数值" width="420px">
      <el-form label-width="80px">
        <el-form-item label="键">
          <el-input :model-value="dialog.key" disabled />
        </el-form-item>
        <el-form-item label="类型">
          <el-input :model-value="dialog.type" disabled />
        </el-form-item>
        <el-form-item label="值" required>
          <el-input v-model="dialog.value" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}

.page-hint {
  margin: 6px 0 12px;
}

.group-select {
  width: 180px;
}
</style>
