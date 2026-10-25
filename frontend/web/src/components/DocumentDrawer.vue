<script setup lang="ts">
import { ref, watch } from 'vue'
import { fenToYuan } from '@/api/client'
import { skeletonApi, type DocumentDetail } from '@/api/skeleton'

/**
 * 单据详情抽屉（FR-V3-04）。
 *
 * ★ 通用：工作台"最近单据"与待办中心都复用它，按 type 调 /documents/{type}/{id}。
 *   type 取值：purchase / sales / return / transfer（与后端 DocumentService 一致）。
 * ★ 金额单位分，展示层转元。
 */
const props = defineProps<{
  visible: boolean
  type: string
  id: number | null
}>()

const emit = defineEmits<{ 'update:visible': [value: boolean] }>()

const TYPE_LABELS: Record<string, string> = {
  purchase: '进货单',
  sales: '出货单',
  return: '退货单',
  transfer: '调拨单',
}

const detail = ref<DocumentDetail | null>(null)
const loading = ref(false)
const error = ref('')

const title = ref('单据详情')

watch(
  () => [props.visible, props.type, props.id] as const,
  ([vis, , id]) => {
    if (vis && id != null) {
      void load(id)
    } else {
      detail.value = null
      error.value = ''
    }
  },
  { immediate: true },
)

async function load(id: number) {
  loading.value = true
  error.value = ''
  try {
    detail.value = await skeletonApi.document(props.type, id)
    title.value = `${TYPE_LABELS[props.type] ?? '单据'} ${detail.value.no}`
  } catch (cause: unknown) {
    const e = cause as { response?: { data?: { message?: string } }; message?: string }
    error.value = e?.response?.data?.message || e?.message || '加载失败'
    detail.value = null
  } finally {
    loading.value = false
  }
}

function close() {
  emit('update:visible', false)
}
</script>

<template>
  <el-drawer :model-value="visible" :title="title" size="560px" @update:model-value="emit('update:visible', $event)">
    <el-skeleton v-if="loading" :rows="8" animated />
    <el-alert
      v-else-if="error"
      type="error"
      show-icon
      :closable="false"
      :title="`单据详情加载失败：${error}`"
    />

    <template v-else-if="detail">
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="单号">{{ detail.no }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag size="small">{{ detail.status }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="业务日期">{{ detail.bizDate }}</el-descriptions-item>
        <el-descriptions-item label="往来单位">{{ detail.partyName || '—' }}</el-descriptions-item>
        <el-descriptions-item label="仓库">{{ detail.warehouseName || '—' }}</el-descriptions-item>
        <el-descriptions-item label="总数量">{{ detail.totalQty }}</el-descriptions-item>
        <el-descriptions-item label="总金额（元）">
          ¥{{ fenToYuan(detail.totalAmount) }}
        </el-descriptions-item>
      </el-descriptions>

      <div class="lines-title">明细（{{ detail.lines.length }} 行）</div>
      <el-table :data="detail.lines" border size="small" class="lines-table">
        <el-table-column label="行号" width="60" prop="lineNo" />
        <el-table-column label="SKU" width="80" prop="skuId" />
        <el-table-column label="商品" min-width="160" prop="skuName" />
        <el-table-column label="数量" width="80" prop="qty" />
        <el-table-column label="单价(元)" width="100">
          <template #default="{ row }">{{ fenToYuan(row.unitPriceAmount) }}</template>
        </el-table-column>
        <el-table-column label="金额(元)" width="100">
          <template #default="{ row }">{{ fenToYuan(row.amount) }}</template>
        </el-table-column>
      </el-table>
    </template>

    <div v-else class="empty-block">没有可显示的单据信息</div>

    <template #footer>
      <el-button @click="close">关闭</el-button>
    </template>
  </el-drawer>
</template>

<style scoped>
.lines-title {
  margin: 16px 0 8px;
  font-weight: 600;
}

.lines-table {
  width: 100%;
}

.empty-block {
  padding: 24px 0;
  text-align: center;
  color: #909399;
}
</style>
