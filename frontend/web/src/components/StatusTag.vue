<script setup lang="ts">
import { computed } from 'vue'
import { statusMeta } from '@/utils/documentStatus'

/**
 * 单据状态标签（七态统一配色）。
 *
 * ★ 为什么值得单独做一个组件：
 *   状态标签会出现在单据列表、详情弹窗、工作台待办、导出文件里。
 *   若每处都 `el-tag :type="status === 'APPROVED' ? 'success' : ..."`，
 *   加一个状态就要改 N 个地方，且 N 处的颜色迟早不一致 ——
 *   用户看到同一个"已审核"两种颜色，会怀疑是两种不同的东西。
 *
 * 颜色与文案的唯一来源是 `utils/documentStatus.ts` 的 STATUS_META。
 */
interface Props {
  status: string | null | undefined
  /** 尺寸；表格里用 small，页头用 default */
  size?: 'large' | 'default' | 'small'
  /** 是否在标签后显示一句解释（默认不显示，避免表格里过宽） */
  withHint?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  size: 'default',
  withHint: false,
})

const meta = computed(() => statusMeta(props.status))

/**
 * 未知状态时的额外提示。
 *
 * 后端新增状态而前端还没跟上时，标签会显示"原始状态码"而不是空白。
 * 空白是最坏的：看起来像"没有状态"，排查时会怀疑数据缺失，
 * 而真相是前端不认识它。
 */
const unknown = computed(() => meta.value.effect === 'plain')
</script>

<template>
  <span class="status-tag">
    <el-tag :type="meta.type" :effect="meta.effect" :size="size" disable-transitions>
      {{ meta.label }}
    </el-tag>
    <span v-if="withHint && !unknown" class="status-hint" :title="meta.description">
      {{ meta.description }}
    </span>
    <span v-else-if="withHint && unknown" class="status-hint text-danger">
      未识别状态，请刷新页面或联系管理员
    </span>
  </span>
</template>

<style scoped>
.status-tag {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.status-hint {
  font-size: 12px;
  color: #909399;
}
</style>
