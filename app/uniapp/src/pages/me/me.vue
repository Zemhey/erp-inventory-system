<script setup lang="ts">
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { authApi, type MeResult } from '@/api/client'
import { logout, session } from '@/stores/session'
import { errorMessage } from '@/utils/error'
import { ensureLoggedIn } from '@/utils/routeGuard'

/**
 * 我的（个人中心）。
 *
 * ★ 为什么单独一页而不是塞进工作台：工号、部门、入职日这些是"我是谁"的静态信息，
 *   工作台每一屏都要用来干活；混在一起会让首屏多一块与当下任务无关的内容。
 * ★ 数据来源是 /auth/me 而不是花名册 /iam/employees：花名册要 iam:read，
 *   一线仓管/销售拿不到 —— 但看自己的工号不需要管理权限（后端同步补了 employee 字段）。
 * ★ 改密与退出放在这一页，工作台头部只保留"刷新"：退出是低频破坏性操作，
 *   放在每屏都能点到的地方，迟早会有人误触。
 */

const profile = ref<MeResult | null>(null)
const loading = ref(false)
const loadError = ref('')

const displayName = computed(
  () => profile.value?.displayName || session.displayName || session.username || '用户',
)
const initial = computed(() => displayName.value.slice(0, 1).toUpperCase())
const rolesText = computed(() => (profile.value?.roles ?? session.roles).join(' / ') || '未分配角色')
const tenantText = computed(() => profile.value?.tenantName || '')
const employee = computed(() => profile.value?.employee ?? null)

/** 六行固定字段：缺值显示"未填写"，而不是把整行藏掉 —— 藏掉会让人以为系统没这个字段。 */
const profileRows = computed(() => [
  { label: '工号', value: employee.value?.employeeNo },
  { label: '部门', value: employee.value?.departmentName },
  { label: '岗位', value: employee.value?.positionName },
  { label: '手机', value: employee.value?.phone },
  { label: '邮箱', value: employee.value?.email },
  { label: '入职日', value: employee.value?.hireDate },
])

async function load(): Promise<void> {
  if (loading.value) return
  loading.value = true
  loadError.value = ''
  try {
    profile.value = await authApi.me()
  } catch (e) {
    // 拿不到就用本地会话兜底：至少姓名与角色是对的，页面不至于整块空着
    loadError.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

// ─────────────────────────── 修改密码 ───────────────────────────

const pwdOpen = ref(false)
const oldPwd = ref('')
const newPwd = ref('')
const confirmPwd = ref('')
const pwdError = ref('')
const pwdOk = ref('')
const pwdLoading = ref(false)

function resetPwdForm(): void {
  oldPwd.value = ''
  newPwd.value = ''
  confirmPwd.value = ''
  pwdError.value = ''
  pwdOk.value = ''
}

function togglePwd(): void {
  pwdOpen.value = !pwdOpen.value
  if (!pwdOpen.value) resetPwdForm()
}

async function submitPassword(): Promise<void> {
  pwdError.value = ''
  pwdOk.value = ''
  if (!oldPwd.value || !newPwd.value) {
    pwdError.value = '原密码与新密码都要填'
    return
  }
  // 两次输入不一致是手误，不是业务错误 —— 在本地拦掉，别让用户白等一次请求
  if (newPwd.value !== confirmPwd.value) {
    pwdError.value = '两次输入的新密码不一致'
    return
  }
  if (newPwd.value === oldPwd.value) {
    pwdError.value = '新密码不能与原密码相同'
    return
  }
  pwdLoading.value = true
  try {
    await authApi.changePassword(oldPwd.value, newPwd.value)
    // 先清空表单再报成功：旧密码留在输入框里既没用也不安全
    resetPwdForm()
    pwdOk.value = '密码已修改，下次登录请用新密码'
  } catch (e) {
    pwdError.value = errorMessage(e)
  } finally {
    pwdLoading.value = false
  }
}

function confirmLogout(): void {
  uni.showModal({
    title: '退出登录',
    content: '退出后需要重新输入账号密码。',
    confirmText: '退出',
    confirmColor: '#c0392b',
    success: (res) => {
      if (res.confirm) logout()
    },
  })
}

onShow(() => {
  ensureLoggedIn()
  void load()
})
</script>

<template>
  <view class="page me">
    <!-- 身份卡 -->
    <view class="card identity">
      <view class="identity-row">
        <view class="avatar">
          <text class="avatar-text">{{ initial }}</text>
        </view>
        <view class="identity-main">
          <text class="identity-name">{{ displayName }}</text>
          <text class="identity-sub">{{ profile?.username || session.username }}</text>
        </view>
      </view>
      <view class="identity-meta">
        <text class="meta-item">{{ rolesText }}</text>
        <text v-if="tenantText" class="meta-item meta-tenant">{{ tenantText }}</text>
      </view>
      <text v-if="loadError" class="identity-warn">档案加载失败：{{ loadError }}（下面显示的是本机缓存）</text>
      <text v-else-if="!employee" class="identity-warn">
        还没有人事档案信息，请联系管理员在「系统 · 员工花名册」里补全工号与部门。
      </text>
    </view>

    <!-- 人事档案 -->
    <view class="card section">
      <view class="row-between">
        <text class="section-title">人事档案</text>
        <text class="section-hint">由管理员维护</text>
      </view>
      <view v-for="row in profileRows" :key="row.label" class="kv">
        <text class="kv-label">{{ row.label }}</text>
        <text class="kv-value" :class="{ 'kv-empty': !row.value }">
          {{ row.value || '未填写' }}
        </text>
      </view>
      <text v-if="employee?.remark" class="remark">{{ employee.remark }}</text>
    </view>

    <!-- 账号与安全 -->
    <view class="card section">
      <text class="section-title">账号与安全</text>

      <view class="action-row" @click="togglePwd">
        <text class="action-text">修改密码</text>
        <text class="action-caret">{{ pwdOpen ? '收起' : '展开' }}</text>
      </view>

      <view v-if="pwdOpen" class="pwd">
        <input v-model="oldPwd" class="pwd-input" password placeholder="原密码" />
        <input v-model="newPwd" class="pwd-input" password placeholder="新密码" />
        <input v-model="confirmPwd" class="pwd-input" password placeholder="再输一次新密码" />
        <text v-if="pwdError" class="pwd-error">{{ pwdError }}</text>
        <text v-if="pwdOk" class="pwd-ok">{{ pwdOk }}</text>
        <button class="btn-primary pwd-submit" :loading="pwdLoading" hover-class="none" @click="submitPassword">
          确认修改
        </button>
      </view>

      <view class="action-row action-danger" @click="confirmLogout">
        <text class="action-text action-text-danger">退出登录</text>
        <text class="action-caret">›</text>
      </view>
    </view>

    <text class="footer">云裳进销存 · 移动端</text>
  </view>
</template>

<style scoped>
.me {
  padding-top: 20rpx;
}

.identity {
  padding: 28rpx 24rpx;
}

.identity-row {
  display: flex;
  align-items: center;
  gap: 20rpx;
}

.avatar {
  width: 96rpx;
  height: 96rpx;
  border-radius: 28rpx;
  background: #1f3a63;
  display: flex;
  align-items: center;
  justify-content: center;
}

.avatar-text {
  font-size: 42rpx;
  font-weight: 700;
  color: #ffffff;
}

.identity-main {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.identity-name {
  font-size: 36rpx;
  font-weight: 700;
  color: #1c2230;
}

.identity-sub {
  margin-top: 6rpx;
  font-size: 23rpx;
  color: #8a90a2;
}

.identity-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-top: 20rpx;
}

.meta-item {
  padding: 6rpx 16rpx;
  border-radius: 999rpx;
  background: #f2f4f8;
  font-size: 21rpx;
  color: #4d5666;
}

.meta-tenant {
  background: #eef4fb;
  color: #2f5d8a;
}

.identity-warn {
  display: block;
  margin-top: 18rpx;
  padding-top: 16rpx;
  border-top: 1rpx solid #f0f2f6;
  font-size: 21rpx;
  color: #9c3a26;
  line-height: 1.55;
}

.section {
  margin-top: 20rpx;
  padding: 24rpx;
}

.section-title {
  font-size: 28rpx;
  font-weight: 700;
  color: #1c2230;
}

.section-hint {
  font-size: 21rpx;
  color: #8a90a2;
}

.kv {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 18rpx;
  padding-bottom: 14rpx;
  border-bottom: 1rpx solid #f0f2f6;
}

.kv-label {
  font-size: 24rpx;
  color: #6e7585;
}

.kv-value {
  font-size: 24rpx;
  color: #1c2230;
  max-width: 60%;
  text-align: right;
}

.kv-empty {
  color: #b3b9c6;
}

.remark {
  display: block;
  margin-top: 16rpx;
  font-size: 21rpx;
  color: #8a90a2;
  line-height: 1.6;
}

.action-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 18rpx;
  padding: 18rpx 0;
  border-bottom: 1rpx solid #f0f2f6;
}

.action-text {
  font-size: 26rpx;
  color: #1c2230;
}

.action-text-danger {
  color: #c0392b;
}

.action-caret {
  font-size: 22rpx;
  color: #8a90a2;
}

.action-danger {
  border-bottom: 0;
}

.pwd {
  margin-top: 6rpx;
  padding: 18rpx;
  border-radius: 14rpx;
  background: #f8f9fb;
}

.pwd-input {
  height: 76rpx;
  margin-bottom: 12rpx;
  padding: 0 18rpx;
  border-radius: 12rpx;
  background: #ffffff;
  font-size: 25rpx;
  color: #1c2230;
}

.pwd-error {
  display: block;
  margin: 6rpx 4rpx;
  font-size: 22rpx;
  color: #c0392b;
  line-height: 1.5;
}

.pwd-ok {
  display: block;
  margin: 6rpx 4rpx;
  font-size: 22rpx;
  color: #2e7d54;
  line-height: 1.5;
}

.pwd-submit {
  margin-top: 12rpx;
}

.footer {
  display: block;
  margin: 28rpx 0 10rpx;
  text-align: center;
  font-size: 20rpx;
  color: #b3b9c6;
}
</style>
