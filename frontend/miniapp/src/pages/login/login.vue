<script setup lang="ts">
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { getToken } from '@/api/client'
import { login } from '@/stores/session'
import { errorMessage } from '@/utils/error'

const username = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')

onShow(() => {
  // 已有本地 token 直接进工作台；token 真过期时工作台会收到 401 并送回登录页
  if (getToken()) {
    uni.switchTab({ url: '/pages/dashboard/dashboard' })
  }
})

async function handleLogin(): Promise<void> {
  if (loading.value) return
  error.value = ''

  if (!username.value.trim()) {
    error.value = '请输入用户名'
    return
  }
  if (!password.value) {
    error.value = '请输入密码'
    return
  }

  loading.value = true
  try {
    await login(username.value, password.value)
    uni.switchTab({ url: '/pages/dashboard/dashboard' })
  } catch (e) {
    // 登录页的 401 是"账号或密码错误"的业务结果，必须原样展示
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <view class="login">
    <view class="halo halo-one" />
    <view class="halo halo-two" />

    <view class="brand">
      <view class="brand-mark">云</view>
      <text class="brand-name">云裳进销存</text>
      <text class="brand-tagline">服装 · 鞋帽 · 移动工作台</text>
    </view>

    <view class="form-card">
      <text class="form-title">登录</text>
      <text class="form-subtitle">使用管理员分配的账号进入系统</text>

      <view class="field">
        <text class="field-label">用户名</text>
        <input
          v-model="username"
          class="field-input"
          type="text"
          placeholder="请输入用户名"
          confirm-type="next"
          :disabled="loading"
        />
      </view>

      <view class="field">
        <text class="field-label">密码</text>
        <input
          v-model="password"
          class="field-input"
          password
          placeholder="请输入密码"
          confirm-type="done"
          :disabled="loading"
          @confirm="handleLogin"
        />
      </view>

      <view v-if="error" class="error-banner">
        <text class="error-dot">!</text>
        <text class="error-text">{{ error }}</text>
      </view>

      <button
        class="btn-primary login-btn"
        hover-class="none"
        :loading="loading"
        :disabled="loading"
        @click="handleLogin"
      >
        {{ loading ? '登录中…' : '进入工作台' }}
      </button>

      <text class="form-hint">账号由管理员创建，系统不预置默认账号</text>
    </view>

    <view class="footer">
      <text class="footer-main">M4 第一批 · H5 / 微信小程序 / Android</text>
      <text class="footer-sub">一套 uni-app 代码，多端复用</text>
    </view>
  </view>
</template>

<style scoped>
.login {
  min-height: 100vh;
  padding: calc(var(--status-bar-height) + 96rpx) 48rpx calc(56rpx + env(safe-area-inset-bottom));
  background: #101725;
  display: flex;
  flex-direction: column;
  position: relative;
  overflow: hidden;
}

.halo {
  position: absolute;
  border-radius: 50%;
  opacity: 0.5;
  filter: blur(2rpx);
}

.halo-one {
  width: 520rpx;
  height: 520rpx;
  top: -180rpx;
  right: -160rpx;
  background: radial-gradient(circle, rgba(64, 102, 168, 0.75) 0%, rgba(16, 23, 37, 0) 68%);
}

.halo-two {
  width: 420rpx;
  height: 420rpx;
  bottom: 120rpx;
  left: -180rpx;
  background: radial-gradient(circle, rgba(201, 166, 107, 0.25) 0%, rgba(16, 23, 37, 0) 70%);
}

.brand {
  position: relative;
  margin-bottom: 72rpx;
}

.brand-mark {
  width: 96rpx;
  height: 96rpx;
  border-radius: 28rpx;
  background: #d8b36a;
  color: #1b2233;
  font-size: 48rpx;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 32rpx;
}

.brand-name {
  display: block;
  color: #ffffff;
  font-size: 52rpx;
  font-weight: 700;
  letter-spacing: 4rpx;
}

.brand-tagline {
  display: block;
  margin-top: 12rpx;
  color: #8e98ad;
  font-size: 26rpx;
  letter-spacing: 2rpx;
}

.form-card {
  position: relative;
  background: #ffffff;
  border-radius: 32rpx;
  padding: 44rpx 36rpx 36rpx;
  box-shadow: 0 30rpx 80rpx rgba(4, 8, 16, 0.35);
}

.form-title {
  display: block;
  font-size: 40rpx;
  font-weight: 700;
  color: #1c2230;
}

.form-subtitle {
  display: block;
  margin-top: 8rpx;
  margin-bottom: 36rpx;
  font-size: 24rpx;
  color: #8a90a2;
}

.field {
  margin-bottom: 26rpx;
}

.field-label {
  display: block;
  margin-bottom: 12rpx;
  font-size: 24rpx;
  color: #586176;
  letter-spacing: 1rpx;
}

.field-input {
  width: 100%;
  height: 88rpx;
  padding: 0 24rpx;
  background: #f4f6fa;
  border: 1rpx solid #e4e8ef;
  border-radius: 16rpx;
  font-size: 30rpx;
  color: #1c2230;
}

.error-banner {
  display: flex;
  align-items: flex-start;
  padding: 20rpx 22rpx;
  margin-bottom: 24rpx;
  background: #fdf1ee;
  border: 1rpx solid #f3d3ca;
  border-radius: 14rpx;
}

.error-dot {
  flex-shrink: 0;
  width: 32rpx;
  height: 32rpx;
  margin-right: 14rpx;
  border-radius: 50%;
  background: #b4462f;
  color: #ffffff;
  font-size: 22rpx;
  line-height: 32rpx;
  text-align: center;
  font-weight: 700;
}

.error-text {
  flex: 1;
  color: #9c3a26;
  font-size: 25rpx;
  line-height: 1.45;
}

.login-btn {
  width: 100%;
  margin-top: 8rpx;
}

.form-hint {
  display: block;
  margin-top: 24rpx;
  text-align: center;
  font-size: 22rpx;
  color: #a2a8b6;
}

.footer {
  position: relative;
  margin-top: auto;
  padding-top: 72rpx;
  text-align: center;
}

.footer-main {
  display: block;
  color: #6f7a90;
  font-size: 23rpx;
  letter-spacing: 1rpx;
}

.footer-sub {
  display: block;
  margin-top: 8rpx;
  color: #4c5568;
  font-size: 21rpx;
}
</style>
