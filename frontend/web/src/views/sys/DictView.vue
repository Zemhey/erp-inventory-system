<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import PageTable from '@/components/PageTable.vue'
import { dictApi, type DictItem, type DictType } from '@/api/auditDict'

/**
 * 数据字典浏览（V2-5 读模型）。
 *
 * ★ 这一页刻意**只做浏览**，而且要把"为什么没有编辑"写在页面上：
 *   后端只有 `GET /dicts` 与 `GET /dicts/{typeCode}/items`，没有任何写端点。
 *   界面如果摆一个能改、点下去必然 405 的表单，比不给入口更糟 ——
 *   用户会以为自己的修改生效了。缺口已按流程登记在 .plan/E1-契约申请.md。
 */

/** 含停用项：默认关闭，与后端 `onlyEnabled=true` 的默认值一致。 */
const includeDisabled = ref(false)
const onlyEnabled = computed(() => !includeDisabled.value)

const types = ref<DictType[]>([])
const typesLoading = ref(false)
const typesError = ref('')

const selected = ref<DictType | null>(null)
const items = ref<DictItem[]>([])
const itemsLoading = ref(false)
const itemsError = ref('')
const itemsLoaded = ref(false)

function errorMessage(cause: unknown): string {
  const e = cause as { response?: { data?: { message?: string } }; message?: string }
  return e?.response?.data?.message || e?.message || '请求失败'
}

async function loadTypes() {
  typesLoading.value = true
  typesError.value = ''
  try {
    types.value = await dictApi.types(onlyEnabled.value)
    // 选中项被"只看启用"过滤掉时，右侧同步失效，不能继续显示已经不存在的类型
    if (selected.value && !types.value.some((t) => t.code === selected.value?.code)) {
      selected.value = null
      items.value = []
      itemsLoaded.value = false
    }
  } catch (cause) {
    types.value = []
    typesError.value = errorMessage(cause)
  } finally {
    typesLoading.value = false
  }
}

async function loadItems(type: DictType) {
  selected.value = type
  itemsLoading.value = true
  itemsError.value = ''
  items.value = []
  try {
    items.value = await dictApi.items(type.code, onlyEnabled.value)
  } catch (cause) {
    itemsError.value = errorMessage(cause)
  } finally {
    itemsLoading.value = false
    itemsLoaded.value = true
  }
}

function onIncludeDisabledChange() {
  // 类型与项是两次读，开关必须同时作用于两侧，否则左列显示停用类型、右列却漏掉停用项
  void loadTypes()
  if (selected.value) void loadItems(selected.value)
}

onMounted(loadTypes)

/** 后端按 sortNo 升序返回；这里不再排序，排序规则属于数据本身，界面不重定义。 */
const itemsTitle = computed(() =>
  selected.value ? `${selected.value.name}（${selected.value.code}）的字典项` : '字典项',
)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2 class="page-title">数据字典</h2>
      <el-button @click="loadTypes">刷新</el-button>
    </div>
    <p class="page-hint">
      只读页面：这里浏览系统预置的业务维度取值（颜色 / 尺码 / 季节 / 单位）。
      字典内容来自数据库迁移的预置种子，本页不提供新增、改名、停用与排序。
    </p>
    <el-alert
      type="info"
      show-icon
      :closable="false"
      class="mb-16"
      title="当前为只读：后端没有字典写接口，因此这里不放编辑入口"
      description="维护字典需要写端点、独立权限码与审计三样齐全，本期只有读接口；先放一个点了必然报错的表单，会让人以为自己的修改生效了。缺口已登记为契约申请。另需注意：这些字典值目前只是可读的取值清单，SKU 录入页还没有把它们变成下拉约束，输入任意颜色/尺码仍然会被接受。"
    />

    <el-checkbox v-model="includeDisabled" @change="onIncludeDisabledChange">
      包含已停用的类型与字典项
    </el-checkbox>

    <el-alert
      v-if="typesError"
      type="error"
      show-icon
      :closable="false"
      class="mb-16"
      :title="`字典类型加载失败：${typesError}`"
    >
      <el-button size="small" @click="loadTypes">重试</el-button>
    </el-alert>

    <div class="dict-layout">
      <PageTable
        :rows="types"
        :loading="typesLoading"
        :paginated="false"
        :load-failed="!!typesError"
        empty-text="没有字典类型"
        empty-hint="系统预置了颜色 / 尺码 / 季节 / 单位四类；勾上「包含已停用的类型与字典项」可看到被停用的。"
        table-size="small"
        class="dict-types"
      >
        <template #header>
          <div class="table-title">
            字典类型
            <span class="text-muted">共 {{ types.length }} 类</span>
          </div>
        </template>

        <el-table-column label="编码" width="110" prop="code" />
        <el-table-column label="名称" width="100" prop="name" />
        <el-table-column label="说明" min-width="180">
          <template #default="{ row }">
            <span v-if="row.description">{{ row.description }}</span>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
              {{ row.enabled ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="loadItems(row)">查看项</el-button>
          </template>
        </el-table-column>
      </PageTable>

      <div class="dict-items">
        <el-alert
          v-if="itemsError"
          type="error"
          show-icon
          :closable="false"
          class="mb-16"
          :title="`字典项加载失败：${itemsError}`"
        >
          <el-button size="small" @click="selected && loadItems(selected)">重试</el-button>
        </el-alert>

        <PageTable
          v-if="selected"
          :rows="items"
          :loading="itemsLoading"
          :paginated="false"
          :load-failed="!!itemsError"
          empty-text="这个类型没有字典项"
          empty-hint="该类型只有定义、没有预置项；也可能全部项已被停用（勾上「包含已停用」再试）。"
          table-size="small"
        >
          <template #header>
            <div class="table-title">
              {{ itemsTitle }}
              <span class="text-muted">共 {{ items.length }} 项</span>
            </div>
            <div class="text-muted">按后端返回的 sortNo 升序显示，界面不重新排序。</div>
          </template>

          <el-table-column label="排序" width="80" prop="sortNo" />
          <el-table-column label="编码" width="120" prop="code" />
          <el-table-column label="显示名" min-width="140" prop="label" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
                {{ row.enabled ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
        </PageTable>

        <el-empty
          v-else-if="!itemsLoaded"
          description="从左边选一个字典类型，查看它的取值"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.dict-layout {
  display: flex;
  align-items: flex-start;
  gap: 16px;
  margin-top: 12px;
}

.dict-types {
  flex: 0 0 46%;
  min-width: 0;
}

.dict-items {
  flex: 1 1 auto;
  min-width: 0;
}

.table-title {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-weight: 600;
}

@media (max-width: 768px) {
  .dict-layout {
    flex-direction: column;
  }

  .dict-types,
  .dict-items {
    width: 100%;
    flex-basis: auto;
  }
}
</style>
