<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { Lock, User } from '@element-plus/icons-vue'
import { useSessionStore } from '@/stores/session'

/**
 * 登录页（FR-IAM-01）。
 *
 * 注意这里**不区分**"用户不存在"和"密码错误" ——
 * 后端刻意返回同一个错误码 IAM_BAD_CREDENTIALS 以防用户名枚举，
 * 前端若自行区分就会把这个防护重新打开。
 */

const router = useRouter()
const route = useRoute()
const session = useSessionStore()

const formRef = ref<FormInstance>()
const loading = ref(false)

const form = reactive({
  username: '',
  password: '',
})

const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

async function handleSubmit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    await session.login(form.username, form.password)
    ElMessage.success('登录成功')
    const redirect = (route.query.redirect as string) || '/dashboard'
    router.push(redirect)
  } catch {
    // 错误消息已由 axios 拦截器统一提示（含后端返回的定位信息），此处不重复弹
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-brand">
        <h1>进销存系统</h1>
        <p class="text-muted">服装 / 鞋帽网店 · 多仓管理</p>
      </div>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        size="large"
        @keyup.enter="handleSubmit"
      >
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" :prefix-icon="User" placeholder="请输入用户名" clearable />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            :prefix-icon="Lock"
            placeholder="请输入密码"
            show-password
          />
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="loading" class="login-button" @click="handleSubmit">
            登 录
          </el-button>
        </el-form-item>
      </el-form>

      <p class="login-tip text-muted">
        账号由管理员创建（系统不提供自助注册）。
      </p>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100vh;
  background: linear-gradient(135deg, #304156 0%, #1f2d3d 100%);
}

.login-card {
  width: 400px;
  padding: 40px 36px 28px;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.24);
}

.login-brand {
  text-align: center;
  margin-bottom: 28px;
}

.login-brand h1 {
  margin: 0 0 6px;
  font-size: 24px;
  font-weight: 600;
  color: #304156;
}

.login-brand p {
  margin: 0;
  font-size: 13px;
}

.login-button {
  width: 100%;
}

.login-tip {
  margin: 0;
  text-align: center;
  font-size: 12px;
}
</style>
