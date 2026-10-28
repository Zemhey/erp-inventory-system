<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  nlSettingsApi,
  type NlSettingsTestResult,
  type NlSettingsView,
} from '@/api/client'

/**
 * AI 接入配置（FR-SYS-04）。
 *
 * ★ 为什么要有这一页，而不是让人改 application.yml：
 *   yml 进 git（key 会泄露）、改完要重启、而且"这台机器到底调没调模型"没人知道。
 *   放到这里之后：填完即生效，状态条直接告诉你现在是关键词模式还是大模型模式。
 *
 * ★ 密钥框永远是空的，这是刻意的：
 *   后端只回显 ••••abcd，前端也不缓存明文。留空 = 不修改，
 *   这样"只想换个模型名"时不必重新粘贴 key。要清空必须显式点"清除"。
 */

const view = ref<NlSettingsView | null>(null)
const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const error = ref('')
const testResult = ref<NlSettingsTestResult | null>(null)

const form = reactive({
  parser: 'stub',
  reviewer: 'rule',
  baseUrl: '',
  model: '',
  apiKey: '',
})

/** 解析方式选了大模型但还没填 key（且库里也没有）时，提前提示，别等保存后再发现不生效。 */
const keyMissing = computed(() => form.parser === 'openai' && !form.apiKey.trim() && !view.value?.hasKey)

const statusTone = computed(() => {
  if (!view.value) return 'info'
  return view.value.llmEnabled ? 'success' : 'warning'
})

const statusText = computed(() => {
  if (!view.value) return '正在读取配置…'
  if (view.value.llmEnabled) {
    return `大模型已启用：${view.value.model} · ${view.value.baseUrl}`
  }
  return form.parser === 'openai'
    ? '已选择大模型，但还没有可用的 API Key —— 实际仍在跑关键词模式'
    : '当前为关键词模式（本地规则匹配，不联网）'
})

const keyPlaceholder = computed(() => {
  if (view.value?.hasKey) {
    return `已保存 ${view.value.keyMasked}，留空则不修改`
  }
  return '粘贴 API Key，例如 sk-...'
})

function describeError(cause: unknown, fallback: string): string {
  const e = cause as { response?: { data?: { message?: string } }; message?: string }
  return e?.response?.data?.message || e?.message || fallback
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = await nlSettingsApi.get()
    view.value = res
    form.parser = res.parser
    form.reviewer = res.reviewer
    form.baseUrl = res.baseUrl
    form.model = res.model
    form.apiKey = ''
  } catch (cause: unknown) {
    error.value = describeError(cause, '配置加载失败')
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!form.baseUrl.trim().startsWith('http')) {
    ElMessage.warning('接口地址必须以 http:// 或 https:// 开头')
    return
  }
  if (!form.model.trim()) {
    ElMessage.warning('模型名不能为空')
    return
  }
  saving.value = true
  try {
    const res = await nlSettingsApi.save({
      parser: form.parser,
      reviewer: form.reviewer,
      baseUrl: form.baseUrl.trim(),
      model: form.model.trim(),
      // 空串 = 不修改（后端语义），不要传 undefined 之外的东西去混淆它
      apiKey: form.apiKey.trim(),
    })
    view.value = res
    form.apiKey = ''
    testResult.value = null
    ElMessage.success(res.llmEnabled ? '已保存，大模型配置已生效' : '已保存（当前未启用大模型）')
  } catch (cause: unknown) {
    ElMessage.error(describeError(cause, '保存失败'))
  } finally {
    saving.value = false
  }
}

async function test() {
  if (!form.baseUrl.trim().startsWith('http')) {
    ElMessage.warning('先填写接口地址')
    return
  }
  testing.value = true
  testResult.value = null
  try {
    testResult.value = await nlSettingsApi.test({
      baseUrl: form.baseUrl.trim(),
      model: form.model.trim(),
      apiKey: form.apiKey.trim(),
    })
  } catch (cause: unknown) {
    testResult.value = {
      ok: false,
      elapsedMs: 0,
      model: form.model,
      detail: describeError(cause, '测试请求失败'),
      intent: null,
    }
  } finally {
    testing.value = false
  }
}

async function clearKey() {
  try {
    await ElMessageBox.confirm(
      '清除后，大模型解析会立即退回关键词模式。确定清除已保存的密钥吗？',
      '清除密钥',
      { type: 'warning', confirmButtonText: '清除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    const res = await nlSettingsApi.save({ apiKey: '__CLEAR__' })
    view.value = res
    form.apiKey = ''
    ElMessage.success('密钥已清除')
  } catch (cause: unknown) {
    ElMessage.error(describeError(cause, '清除失败'))
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2 class="page-title">AI 接入</h2>
      <div>
        <el-button :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>
    <p class="page-hint">
      填写模型服务商信息后保存即刻生效，不需要重启后端。密钥经加密后存库，接口只回显后四位。
    </p>

    <el-alert v-if="error" type="error" show-icon :closable="false" class="mb-16" :title="`加载失败：${error}`">
      <el-button size="small" @click="load">重试</el-button>
    </el-alert>

    <el-skeleton v-if="loading && !view" :rows="6" animated />

    <template v-else>
      <el-alert
        :type="statusTone"
        show-icon
        :closable="false"
        class="mb-16"
        :title="statusText"
      >
        <template v-if="view">
          复核层：{{ view.aiReviewEnabled ? '规则 + AI 复核' : '仅确定性规则' }}
          <span class="sep">·</span>
          实际解析方式：{{ view.effectiveParser === 'openai' ? '大模型' : '关键词规则' }}
        </template>
      </el-alert>

      <el-card shadow="never" class="mb-16">
        <template #header><span>模型配置</span></template>
        <el-form label-width="120px" class="settings-form">
          <el-form-item label="解析方式">
            <el-select v-model="form.parser" class="w240">
              <el-option label="关键词规则（离线，不联网）" value="stub" />
              <el-option label="大模型 function calling" value="openai" />
            </el-select>
            <span class="hint">选大模型后，缺少参数时会追问而不是猜。</span>
          </el-form-item>

          <el-form-item label="复核方式">
            <el-select v-model="form.reviewer" class="w240">
              <el-option label="仅确定性规则" value="rule" />
              <el-option label="规则 + AI 复核" value="openai" />
            </el-select>
            <span class="hint">只对写操作生效，查询不会触发模型调用。</span>
          </el-form-item>

          <el-form-item label="接口地址">
            <el-input v-model="form.baseUrl" class="w420" placeholder="https://api.deepseek.com/v1" />
            <span class="hint">DeepSeek 填 https://api.deepseek.com/v1</span>
          </el-form-item>

          <el-form-item label="模型名">
            <el-input v-model="form.model" class="w240" placeholder="deepseek-chat" />
            <span class="hint">必须支持 function calling；deepseek-reasoner 不支持。</span>
          </el-form-item>

          <el-form-item label="API Key">
            <div class="key-row">
              <el-input
                v-model="form.apiKey"
                type="password"
                show-password
                class="w420"
                :placeholder="keyPlaceholder"
              />
              <el-button v-if="view?.hasKey" link type="danger" @click="clearKey">清除密钥</el-button>
            </div>
            <span v-if="keyMissing" class="hint warn">还没有填 Key：保存后仍会以关键词模式运行。</span>
          </el-form-item>
        </el-form>

        <div class="actions">
          <el-button :loading="testing" @click="test">测试连接</el-button>
          <el-button type="primary" :loading="saving" @click="save">保存并生效</el-button>
        </div>

        <el-alert
          v-if="testResult"
          :type="testResult.ok ? 'success' : 'error'"
          show-icon
          :closable="false"
          class="mt-16"
          :title="testResult.ok ? `连接正常（${testResult.elapsedMs} ms）` : `连接失败（${testResult.elapsedMs} ms）`"
        >
          <div class="test-detail">{{ testResult.detail }}</div>
          <div v-if="testResult.intent" class="test-detail">
            识别意图：{{ testResult.intent }}
          </div>
        </el-alert>
      </el-card>

      <el-card shadow="never">
        <template #header><span>这套配置影响什么</span></template>
        <ul class="notes">
          <li>解析方式决定「一句话」怎么变成意图：关键词是本地匹配，大模型是 function calling。</li>
          <li>复核方式只影响<strong>写操作</strong>（建单、提交）；查询类指令不会调用模型。</li>
          <li>无论选哪种，最终都要经过计划预览和人工确认——模型没有执行和授权能力。</li>
          <li>保存后下一句指令即按新配置执行，已在待确认队列里的旧计划不受影响。</li>
        </ul>
      </el-card>
    </template>
  </div>
</template>

<style scoped>
.settings-form :deep(.el-form-item) {
  margin-bottom: 18px;
}
.w240 {
  width: 240px;
}
.w420 {
  width: 420px;
}
.hint {
  margin-left: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.hint.warn {
  color: var(--el-color-warning);
  margin-left: 0;
}
.key-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.sep {
  margin: 0 6px;
}
.actions {
  display: flex;
  gap: 10px;
  padding-left: 120px;
}
.test-detail {
  font-size: 12px;
  line-height: 1.7;
  word-break: break-all;
}
.notes {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.9;
  color: var(--el-text-color-regular);
}
.mt-16 {
  margin-top: 16px;
}
</style>
