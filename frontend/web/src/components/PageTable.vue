<script setup lang="ts">
import { computed } from 'vue'

/**
 * 列表页骨架：筛选区 + 表格 + 分页 + 空状态。
 *
 * ★ 为什么抽成一个组件而不是每个页面复制一遍（计划书 §3.1）：
 *   7 个列表页的骨架完全一样。复制粘贴的结果不是"省事"，而是七份略有差异的表：
 *   有的分页条在左、有的在右，有的空状态写"暂无数据"、有的写"查询无结果"。
 *   用户每换一页都要重新找"新建按钮在哪"，这在一个每天用 8 小时的系统里是真实成本。
 *   抽出来之后，改一次分页行为、改一次空状态文案，七页同时生效。
 *
 * ★ 三个容易做错、这里刻意处理掉的点：
 *
 *   1. **"没有数据"和"筛选后没有数据"是两件事**
 *      后者要提示用户"换个条件试试"，而不是"还没有数据" ——
 *      后者会让人以为数据丢了。〔filtered 属性〕
 *
 *   2. **分页参数不能叫 size**
 *      服装业务里 size 指尺码（S/M/L）。分页条数若也叫 size，
 *      同一份代码里两个 size 含义不同，改到最后必有一处传错。
 *      统一叫 pageSize（计划书 §6 记过这个坑）。
 *
 *   3. **空状态必须保留"新建"入口**
 *      一个刚上线的系统，用户第一次进来看到的必然是空表。
 *      如果空状态下只剩一句"暂无数据"而没有新建按钮，新用户会卡在这里。
 *      这里的做法：空状态里直接给一个"新建第一条"按钮（hasCreate 时显示）。
 *
 * ★ 刻意**不**做的事：
 *   - 不内置"新建/编辑"弹窗：各页表单差异太大，硬抽象会得到一个全是 if 的怪物；
 *   - 不内置请求逻辑：有的分页（分页接口）、有的不分页（仓库返回数组），
 *     请求应在页面里写，骨架只负责展示与"用户想翻页了"的事件。
 */

interface Props {
  /** 表格数据 */
  rows: readonly unknown[]
  /** 加载中（表格遮罩） */
  loading?: boolean
  /** 总条数（分页用） */
  total?: number
  /** 当前页，从 1 开始（v-model:page） */
  page?: number
  /** 每页条数（v-model:pageSize，刻意不叫 size） */
  pageSize?: number
  /** 每页条数候选 */
  pageSizes?: number[]
  /** 是否显示分页（仓库列表返回数组，不分页） */
  paginated?: boolean
  /** 行主键字段名 */
  rowKey?: string
  /** 表格没有默认插槽行时的兜底空文案 */
  emptyText?: string
  /** 空状态的第二行说明 */
  emptyHint?: string
  /** 当前是否带着筛选条件（决定空状态说"没有数据"还是"换个条件"） */
  filtered?: boolean
  /** 是否显示"新建第一条"按钮（空状态里） */
  canCreate?: boolean
  /** 新建按钮文案 */
  createText?: string
  /** 表格尺寸 */
  tableSize?: 'large' | 'default' | 'small'
  /** 是否显示斑马纹 */
  stripe?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  loading: false,
  total: 0,
  page: 1,
  pageSize: 20,
  pageSizes: () => [10, 20, 50, 100],
  paginated: true,
  rowKey: 'id',
  emptyText: '暂无数据',
  filtered: false,
  canCreate: false,
  createText: '新建',
  tableSize: 'default',
  stripe: true,
})

const emit = defineEmits<{
  'update:page': [value: number]
  'update:pageSize': [value: number]
  /** 页码或条数变化后触发，页面在此拉数据 */
  change: [page: number, pageSize: number]
  /** 点击空状态里的"新建第一条" */
  create: []
}>()

/**
 * 空状态文案。
 *
 * 三档而不是一档：
 *   - 有筛选条件 → 引导换条件（并提醒条件可能写错了）
 *   - 无筛选条件 → 引导建第一条
 *   - 有筛选且总数为 0 但 data 为空 → 同上，靠 filtered 区分
 */
const emptyTitle = computed(() => (props.filtered ? '没有符合条件的数据' : props.emptyText))
const emptySubtitle = computed(() =>
  props.filtered
    ? '试着放宽或清空筛选条件；如果确认数据应该存在，请联系管理员核对。'
    : (props.emptyHint ?? ''),
)

function handleSizeChange(nextSize: number) {
  // 条数变化时回到第 1 页：留在第 5 页而条数从 20 变 100，
  // 大概率直接超出范围，用户会看到一张空表 —— 看起来像"数据没了"。
  emit('update:pageSize', nextSize)
  emit('update:page', 1)
  emit('change', 1, nextSize)
}

function handlePageChange(nextPage: number) {
  emit('update:page', nextPage)
  emit('change', nextPage, props.pageSize)
}

/**
 * 供页面调用的重置页码入口（改筛选条件后回到第 1 页）。
 * 页面在筛选变更时自己调 `emit('change', 1, pageSize)`，这里只导出类型给 TS 用。
 */
defineExpose({})
</script>

<template>
  <div class="page-table">
    <!-- 筛选区：由页面通过 #filters 插槽提供 -->
    <div v-if="$slots.filters" class="filters">
      <slot name="filters" />
    </div>

    <el-card shadow="never" class="table-card">
      <template v-if="$slots.header" #header>
        <div class="table-card-header">
          <slot name="header" />
        </div>
      </template>

      <el-table
        v-loading="loading"
        :data="rows as never[]"
        :row-key="rowKey"
        :size="tableSize"
        :stripe="stripe"
        border
        class="table"
      >
        <!-- 列定义由页面提供 -->
        <slot />

        <!-- 空状态：三档文案 + 可选的"新建第一条" -->
        <template #empty>
          <div class="empty-block">
            <slot name="empty">
              <div class="empty-title">{{ emptyTitle }}</div>
              <div v-if="emptySubtitle" class="empty-subtitle">{{ emptySubtitle }}</div>
              <el-button
                v-if="canCreate && !filtered"
                type="primary"
                class="empty-create"
                @click="emit('create')"
              >
                {{ createText }}
              </el-button>
            </slot>
          </div>
        </template>
      </el-table>

      <!--
        分页只在"有分页"且"至少一页"时显示。
        总数 ≤ 条数时显示分页条没有信息量，只会占一行高度。
      -->
      <div v-if="paginated && total > 0" class="pagination">
        <el-pagination
          :current-page="page"
          :page-size="pageSize"
          :page-sizes="pageSizes"
          :total="total"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.table-card {
  border-radius: 4px;
}

.table-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.table {
  width: 100%;
}

.pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.empty-title {
  font-size: 15px;
  color: #606266;
}

.empty-subtitle {
  margin-top: 6px;
  font-size: 12px;
  color: #909399;
}

.empty-create {
  margin-top: 16px;
}

/* 窄屏（≤768px）：筛选控件换行，避免把表格挤成一条缝 */
@media (max-width: 768px) {
  .filters {
    flex-direction: column;
    align-items: stretch;
  }

  .pagination {
    justify-content: center;
  }
}
</style>
