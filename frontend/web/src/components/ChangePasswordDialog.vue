<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { authApi } from '@/api/client'
import { useSessionStore } from '@/stores/session'

/**
 * 自助修改密码（FR-IAM-06）。
 *
 * ★ 规则来源是后端：`/auth/login` 与 `/auth/me` 都带 `passwordRules`
 *   （`PasswordPolicy.describe()` 的输出，如「密码长度至少 8 位」「至少包含 3 类字符」）。
 *   这里把它**原文显示**，并从中**解析**出长度下限与字符类别数做前置校验 ——
 *   解析不出来（策略文案改了）就不拦，交给后端 400 IAM_PASSWORD_WEAK 的具体理由。
 *   前端不自带一套阈值，否则管理员调了策略，界面还在按老规则提示。
 *
 * ★ `forced` 为真时对话框不可关、不给跳过：此时后端 PasswordExpiryFilter 已经把
 *   除「改密 / 看自己 / 登录」以外的所有接口拦成 403，留着界面让用户点只会全是报错。
 *   唯一逃生口是退出登录（换账号是合理需求）。
 */

const props = defineProps<{ modelValue: boolean; forced?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const session = useSessionStore()
const formRef = ref<FormInstance>()
const submitting = ref(false)

const form = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })

/** 后端下发的规则原文；没有就不显示（首次登录后 /auth/me 会补齐）。 */
const rules = computed(() => session.passwordRules)

/** 从「密码长度至少 8 位」取最小长度；取不到返回 0 表示不做前端预检。 */
const minLength = computed(() => {
  const line = rules.value.find((r) => r.includes('长度'))
  const n = line ? Number(/\d+/.exec(line)?.[0]) : NaN
  return Number.isFinite(n) && n > 0 ? n : 0
})

/** 从「至少包含 3 类字符（大写/小写/数字/符号）」取字符类别数；取不到返回 0。 */
const minCharClasses = computed(() => {
  const line = rules.value.find((r) => r.includes('类字符'))
  const n = line ? Number(/\d+/.exec(line)?.[0]) : NaN
  return Number.isFinite(n) && n > 0 ? n : 0
})

function charClasses(value: string): number {
  let classes = 0
  if (/[a-z]/.test(value)) classes += 1
  if (/[A-Z]/.test(value)) classes += 1
  if (/\d/.test(value)) classes += 1
  if (/[^A-Za-z0-9]/.test(value)) classes += 1
  return classes
}

const expiresHint = computed(() => {
  if (session.passwordExpiresAt) {
    return `当前密码有效期至 ${String(session.passwordExpiresAt).slice(0, 10)}。`
  }
  return ''
})

function validateNew(_rule: unknown, value: string, callback: (error?: Error) => void) {
  if (!value) return callback(new Error('请输入新密码'))
  if (minLength.value > 0 && value.length < minLength.value) {
    return callback(new Error(`密码长度至少 ${minLength.value} 位`))
  }
  if (minCharClasses.value > 0 && charClasses(value) < minCharClasses.value) {
    return callback(
      new Error(`至少包含 ${minCharClasses.value} 类字符（大写/小写/数字/符号），当前只有 ${charClasses(value)} 类`),
    )
  }
  if (value === form.oldPassword) return callback(new Error('新密码不能与旧密码相同'))
  return callback()
}

function validateConfirm(_rule: unknown, value: string, callback: (error?: Error) => void) {
  if (!value) return callback(new Error('请再次输入新密码'))
  if (value !== form.newPassword) return callback(new Error('两次输入的新密码不一致'))
  return callback()
}

const formRules: FormRules = {
  oldPassword: [{ required: true, message: '请输入旧密码', trigger: 'blur' }],
  newPassword: [{ validator: validateNew, trigger: 'blur' }],
  confirmPassword: [{ validator: validateConfirm, trigger: 'blur' }],
}

function reset() {
  form.oldPassword = ''
  form.newPassword = ''
  form.confirmPassword = ''
  formRef.value?.clearValidate()
}

/** 关闭：强制态一律不给关（改密是这个状态下唯一被放行的写操作）。 */
function requestClose() {
  if (props.forced) return
  reset()
  emit('update:modelValue', false)
}

/**
 * el-dialog 自己的关闭动作（右上角 X / 遮罩 / ESC）。
 *
 * 这里刻意用 `:model-value` 单向绑定 + 接管 update，而不是 v-model：
 * 强制态下必须能"拒绝被关闭"。若直接 v-model，对话框一被置 false 就真关了，
 * 用户就绕过了改密 —— 而后端在这状态下把其它接口全拦着，界面等于白屏。
 */
function onVisibilityChange(value: boolean) {
  if (!value) requestClose()
}

async function submit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    await authApi.changePassword(form.oldPassword, form.newPassword)
    // 后端已清掉 must_change_password；重新拉一次会话，界面才知道"可以正常用了"。
    await session.refresh().catch(() => undefined)
    ElMessage.success('密码已修改')
    reset()
    emit('update:modelValue', false)
  } catch {
    // 旧密码不对（401 IAM_BAD_CREDENTIALS）、太弱（400 IAM_PASSWORD_WEAK）等
    // 具体理由由 axios 拦截器统一弹出，后端 message 里带缺哪条规则，比前端自拟的准。
  } finally {
    submitting.value = false
  }
}

function handleLogout() {
  session.logout()
  reset()
  emit('update:modelValue', false)
  window.location.href = '/login'
}

// 关掉再打开时不该残留上一次输入（尤其强制态下用户可能试了几个密码）。
watch(
  () => props.modelValue,
  (open) => {
    if (!open) reset()
  },
)
</script>

<template>
  <el-dialog
    :model-value="props.modelValue"
    :title="props.forced ? '必须先修改密码' : '修改密码'"
    width="440px"
    :close-on-click-modal="false"
    :close-on-press-escape="!props.forced"
    :show-close="!props.forced"
    @update:model-value="onVisibilityChange"
  >
    <el-alert
      v-if="props.forced"
      type="warning"
      show-icon
      :closable="false"
      class="mb-12"
      title="按安全策略，当前密码需要修改后才能继续使用系统"
      description="常见原因是密码已到期，或管理员为你重置过初始密码。改密成功前，除查看本人信息外的操作都会被后端拒绝。"
    />

    <div v-if="rules.length" class="pwd-rules">
      <div class="pwd-rules-title">密码规则（由后端策略下发）</div>
      <ul>
        <li v-for="rule in rules" :key="rule">{{ rule }}</li>
      </ul>
    </div>
    <p v-if="expiresHint" class="pwd-hint">{{ expiresHint }}</p>

    <el-form ref="formRef" :model="form" :rules="formRules" label-position="top" @keyup.enter="submit">
      <el-form-item label="旧密码" prop="oldPassword">
        <el-input v-model="form.oldPassword" type="password" show-password autocomplete="current-password" />
      </el-form-item>
      <el-form-item label="新密码" prop="newPassword">
        <el-input
          v-model="form.newPassword"
          type="password"
          show-password
          autocomplete="new-password"
          :placeholder="minLength ? `至少 ${minLength} 位` : '请输入新密码'"
        />
      </el-form-item>
      <el-form-item label="确认新密码" prop="confirmPassword">
        <el-input v-model="form.confirmPassword" type="password" show-password autocomplete="new-password" />
      </el-form-item>
    </el-form>

    <template #footer>
      <div class="pwd-footer">
        <el-button v-if="props.forced" text @click="handleLogout">退出登录</el-button>
        <span class="spacer" />
        <el-button v-if="!props.forced" @click="requestClose">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">确认修改</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.pwd-rules {
  margin-bottom: 12px;
  padding: 8px 12px;
  background: #f5f7fa;
  border-radius: 4px;
  font-size: 12px;
  color: #606266;
}

.pwd-rules-title {
  font-weight: 600;
  margin-bottom: 4px;
}

.pwd-rules ul {
  margin: 0;
  padding-left: 18px;
}

.pwd-hint {
  margin: 0 0 8px;
  font-size: 12px;
  color: #909399;
}

.pwd-footer {
  display: flex;
  align-items: center;
}

.spacer {
  flex: 1;
}

.mb-12 {
  margin-bottom: 12px;
}
</style>
