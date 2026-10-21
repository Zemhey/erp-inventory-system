<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { mdmApi, type Product, yuanToFen } from '@/api/client'

/**
 * 颜色 × 尺码矩阵生成弹窗（计划书 §3.5）。
 *
 * ★ 三个必须处理好的返回值（都是用户会真实遇到的语义）：
 *   - createdCount：本次新建了几个；
 *   - skipped：**已存在而被跳过**的组合数。这不是错误，是"补色重放"的正常结果。
 *     不提示的话，用户会以为按钮没生效；
 *   - 请求自身重复（颜色里写两次"白色"）：在提交前就拦住，而不是等后端 409。
 *
 * 生成完成后弹窗不自动关闭：跳过数需要被看见，否则这次操作等于没解释。
 */

interface Props {
  modelValue: boolean
  product: Product | null
}

const props = defineProps<Props>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  generated: [result: { createdCount: number; skipped: number }]
}>()

const COLOR_PRESETS = ['白色', '黑色', '灰色', '藏青', '米色', '红色']
const SIZE_PRESETS = ['S', 'M', 'L', 'XL', 'XXL']

const colors = ref<string[]>([])
const sizes = ref<string[]>([])
const colorDraft = ref('')
const sizeDraft = ref('')
const priceYuan = ref('')
const submitting = ref(false)
const result = ref<{ createdCount: number; skipped: number } | null>(null)

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const total = computed(() => colors.value.length * sizes.value.length)
const preview = computed(() => {
  const list: string[] = []
  for (const color of colors.value) {
    for (const size of sizes.value) {
      list.push(`${color}-${size}`)
    }
  }
  return list
})

function reset() {
  colors.value = []
  sizes.value = []
  colorDraft.value = ''
  sizeDraft.value = ''
  priceYuan.value = ''
  result.value = null
}

function addValue(list: string[], value: string, maxLength: number, label: string): boolean {
  const trimmed = value.trim()
  if (!trimmed) return false
  if (trimmed.length > maxLength) {
    ElMessage.warning(`${label}最多 ${maxLength} 个字符`)
    return false
  }
  if (list.includes(trimmed)) {
    ElMessage.info(`${label}「${trimmed}」已经添加过了`)
    return false
  }
  list.push(trimmed)
  return true
}

function addColor() {
  if (addValue(colors.value, colorDraft.value, 32, '颜色')) {
    colorDraft.value = ''
  }
}

function addSize() {
  if (addValue(sizes.value, sizeDraft.value, 16, '尺码')) {
    sizeDraft.value = ''
  }
}

function addPreset(list: string[], value: string, maxLength: number) {
  // 点重复的常用项不打扰用户：常见于快速补色，静默忽略即可
  if (!list.includes(value)) {
    addValue(list, value, maxLength, list === colors.value ? '颜色' : '尺码')
  }
}

function removeColor(color: string) {
  colors.value = colors.value.filter((c) => c !== color)
}

function removeSize(size: string) {
  sizes.value = sizes.value.filter((s) => s !== size)
}

async function submit() {
  if (!props.product) return
  if (!colors.value.length) {
    ElMessage.warning('请至少添加一个颜色')
    return
  }
  if (!sizes.value.length) {
    ElMessage.warning('请至少添加一个尺码')
    return
  }
  const price = priceYuan.value === '' ? 0 : Number(priceYuan.value)
  if (Number.isNaN(price) || price < 0) {
    ElMessage.warning('零售价必须是非负数字')
    return
  }

  submitting.value = true
  result.value = null
  try {
    const res = await mdmApi.createSkuMatrix({
      productId: props.product.id,
      colors: colors.value,
      sizes: sizes.value,
      retailPriceAmount: yuanToFen(price),
    })
    result.value = { createdCount: res.createdCount, skipped: res.skipped }
    emit('generated', result.value)
    if (res.createdCount > 0) {
      ElMessage.success(`已生成 ${res.createdCount} 个 SKU`)
    } else {
      ElMessage.warning('没有新建 SKU：所有组合都已存在')
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="product ? `生成 SKU · ${product.code} ${product.name}` : '生成 SKU'"
    width="680px"
    :close-on-click-modal="false"
    @open="reset"
  >
    <el-form label-width="96px">
      <el-form-item label="颜色" required>
        <div class="tag-area">
          <el-tag
            v-for="color in colors"
            :key="color"
            closable
            @close="removeColor(color)"
          >
            {{ color }}
          </el-tag>
          <el-input
            v-model="colorDraft"
            size="small"
            placeholder="输入后回车"
            class="tag-input"
            @keyup.enter="addColor"
          />
          <el-button size="small" @click="addColor">添加</el-button>
        </div>
        <div class="preset-row">
          <span class="preset-label">常用：</span>
          <el-button
            v-for="preset in COLOR_PRESETS"
            :key="preset"
            link
            type="primary"
            size="small"
            @click="addPreset(colors, preset, 32)"
          >
            {{ preset }}
          </el-button>
        </div>
      </el-form-item>

      <el-form-item label="尺码" required>
        <div class="tag-area">
          <el-tag
            v-for="size in sizes"
            :key="size"
            closable
            @close="removeSize(size)"
          >
            {{ size }}
          </el-tag>
          <el-input
            v-model="sizeDraft"
            size="small"
            placeholder="输入后回车"
            class="tag-input"
            @keyup.enter="addSize"
          />
          <el-button size="small" @click="addSize">添加</el-button>
        </div>
        <div class="preset-row">
          <span class="preset-label">常用：</span>
          <el-button
            v-for="preset in SIZE_PRESETS"
            :key="preset"
            link
            type="primary"
            size="small"
            @click="addPreset(sizes, preset, 16)"
          >
            {{ preset }}
          </el-button>
        </div>
      </el-form-item>

      <el-form-item label="统一零售价">
        <el-input v-model="priceYuan" placeholder="129.00" class="price-input">
          <template #append>元</template>
        </el-input>
        <span class="text-muted price-hint">留空按 0 元；按整数分提交，显示才转元。</span>
      </el-form-item>

      <el-form-item label="预览">
        <div v-if="total === 0" class="text-muted">
          添加颜色和尺码后，这里会显示将要生成的 SKU 组合。
        </div>
        <div v-else>
          <div class="preview-head">
            将生成 <strong>{{ colors.length }}</strong> ×
            <strong>{{ sizes.length }}</strong> =
            <strong>{{ total }}</strong> 个 SKU
          </div>
          <div class="preview-grid">
            <el-tag v-for="name in preview.slice(0, 60)" :key="name" type="info" size="small">
              {{ name }}
            </el-tag>
            <span v-if="preview.length > 60" class="text-muted">
              仅预览前 60 个，其余 {{ preview.length - 60 }} 个提交后生成
            </span>
          </div>
        </div>
      </el-form-item>
    </el-form>

    <el-alert
      v-if="result"
      :type="result.skipped > 0 ? 'warning' : 'success'"
      :closable="false"
      show-icon
    >
      <template #title>
        本次新建 {{ result.createdCount }} 个，跳过 {{ result.skipped }} 个已存在的组合
      </template>
      <div v-if="result.skipped > 0" class="result-hint">
        「跳过」不是错误：这些颜色 × 尺码组合之前已经建过。补色后再次生成时出现跳过是正常的。
      </div>
    </el-alert>

    <template #footer>
      <el-button @click="visible = false">{{ result ? '关闭' : '取消' }}</el-button>
      <el-button type="primary" :loading="submitting" :disabled="total === 0" @click="submit">
        {{ result ? '再次生成' : '生成' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.tag-area {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  width: 100%;
}

.tag-input {
  width: 160px;
}

.preset-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  width: 100%;
  margin-top: 4px;
}

.preset-label {
  font-size: 12px;
  color: #909399;
}

.price-input {
  width: 200px;
}

.price-hint {
  margin-left: 10px;
  font-size: 12px;
}

.preview-head {
  margin-bottom: 8px;
  font-size: 13px;
}

.preview-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  max-height: 160px;
  overflow-y: auto;
}

.result-hint {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.6;
}
</style>
