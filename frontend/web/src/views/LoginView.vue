<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { Lock, OfficeBuilding, User } from '@element-plus/icons-vue'
import { useSessionStore } from '@/stores/session'

/**
 * 登录页（FR-IAM-01）。
 *
 * 注意这里**不区分**"用户不存在"和"密码错误" ——
 * 后端刻意返回同一个错误码 IAM_BAD_CREDENTIALS 以防用户名枚举，
 * 前端若自行区分就会把这个防护重新打开。
 *
 * 视觉：左侧是当季面料色卡（黛青 / 雾蓝 / 胭脂 / 陶土 / 米白），
 * 右侧是登录表单。不放大插画、不做 3D、不做玻璃拟态。
 */

const router = useRouter()
const route = useRoute()
const session = useSessionStore()

const formRef = ref<FormInstance>()
const loading = ref(false)

const form = reactive({
  username: '',
  password: '',
  /**
   * 可选租户编码（B1）：多租户成员用它选择本次登录的行动租户。
   * 留空 = 账号的主租户；支持 `?tenant=demo` 预填，便于演示与排障链接。
   */
  tenantCode: typeof route.query.tenant === 'string' ? route.query.tenant : '',
})

const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

/** 当季主线色卡。色名与色号来自商品部的面料样册口径。 */
const bolts = [
  { name: '黛青', code: 'DQ-46', hex: '#2E5D63', offset: 0 },
  { name: '雾蓝', code: 'WL-71', hex: '#7C93A6', offset: 26 },
  { name: '胭脂', code: 'YZ-19', hex: '#A25F6B', offset: 8 },
  { name: '陶土', code: 'TT-33', hex: '#A96436', offset: 34 },
  { name: '米白', code: 'MB-08', hex: '#EFEADF', offset: 14, light: true },
]

async function handleSubmit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    await session.login(form.username, form.password, form.tenantCode)
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
    <!-- 左：色卡台 -->
    <section class="login-stage" aria-label="当季面料色卡">
      <div class="stage-weave" aria-hidden="true"></div>

      <div class="stage-brand">
        <span class="brand-mark" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor">
            <path d="M12 7.4V6.2a2 2 0 1 1 2.6-1.9" />
            <path d="m12 7.4-8 6.2h16l-8-6.2Z" />
            <path d="M4 20h16" />
          </svg>
        </span>
        <span class="brand-text">
          <b>云裳</b>
          <i>YUNSHANG · RETAIL OPS</i>
        </span>
      </div>

      <div class="bolt-row" aria-hidden="true">
        <div
          v-for="bolt in bolts"
          :key="bolt.code"
          class="bolt"
          :class="{ 'bolt--light': bolt.light }"
          :style="{ background: bolt.hex, marginTop: bolt.offset + 'px' }"
        >
          <span class="bolt-tag">{{ bolt.name }} <em>{{ bolt.code }}</em></span>
        </div>
      </div>

      <div class="stage-caption">
        <p class="quote">
          <b>秋冬 2025 面料样本</b>
          五色为当季主线：黛青与雾蓝取自重彩山水，胭脂与陶土取自旧绫，米白为坯布本色。
          吊牌价与成本价据此核定。
        </p>
        <p class="spec">
          幅宽 145 cm<br />
          克重 320 g/m²<br />
          色号 25AW-A
        </p>
      </div>
    </section>

    <!-- 右：登录表单 -->
    <section class="login-panel">
      <div class="login-form-wrap">
        <p class="panel-eyebrow">云裳服饰　杭州总部</p>
        <h1 class="panel-title">登录进销存</h1>
        <p class="panel-help">
          使用公司分配的账号登录。账号由管理员创建，系统不提供自助注册。
        </p>

        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          size="large"
          class="login-form"
          @keyup.enter="handleSubmit"
        >
          <el-form-item label="用户名" prop="username">
            <el-input
              v-model="form.username"
              :prefix-icon="User"
              placeholder="请输入用户名"
              clearable
              autocomplete="username"
            />
          </el-form-item>

          <el-form-item label="密码" prop="password">
            <el-input
              v-model="form.password"
              type="password"
              :prefix-icon="Lock"
              placeholder="请输入密码"
              show-password
              autocomplete="current-password"
            />
          </el-form-item>

          <el-form-item label="租户编码（可选）" prop="tenantCode">
            <el-input
              v-model="form.tenantCode"
              :prefix-icon="OfficeBuilding"
              placeholder="多租户成员填写；留空进入主租户"
              clearable
            />
          </el-form-item>

          <el-form-item class="submit-item">
            <el-button
              type="primary"
              size="large"
              :loading="loading"
              class="login-button"
              @click="handleSubmit"
            >
              登录
            </el-button>
          </el-form-item>
        </el-form>

        <p class="panel-tip">
          所属多个租户时，可在此选择登录租户；登录后也可从顶栏切换。
        </p>

        <p class="panel-foot">
          登录即表示同意《数据处理与保密约定》。<br />
          本系统仅在杭州、上海、广州三地部署，数据不出境。
        </p>
      </div>
    </section>
  </div>
</template>

<style scoped>
.login-page {
  display: grid;
  grid-template-columns: minmax(360px, 46%) 1fr;
  min-height: 100vh;
  background: var(--ys-nuanbai);
}

/* ══ 左侧色卡台 ══════════════════════════════════════════════════════ */
.login-stage {
  position: relative;
  display: flex;
  flex-direction: column;
  padding: 34px 40px 26px;
  background: #f1ebdf;
  border-right: 1px solid var(--ys-line);
  overflow: hidden;
}

/* 布面经纬织纹 —— 是纹理，不是渐变 */
.stage-weave {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background-image: repeating-linear-gradient(
      90deg,
      rgba(120, 100, 74, 0.045) 0 1px,
      transparent 1px 4px
    ),
    repeating-linear-gradient(0deg, rgba(120, 100, 74, 0.035) 0 1px, transparent 1px 4px);
}

.stage-brand {
  position: relative;
  display: flex;
  align-items: center;
  gap: 11px;
}

/* 织唛：黛青底 + 织纹 + 骑缝虚线，这是品牌唯一的图形资产 */
.brand-mark {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  flex: none;
  border-radius: 7px;
  color: #f6f1e6;
  background: var(--ys-daiqing);
  background-image: repeating-linear-gradient(
      90deg,
      rgba(255, 255, 255, 0.11) 0 1px,
      transparent 1px 3px
    ),
    repeating-linear-gradient(0deg, rgba(255, 255, 255, 0.09) 0 1px, transparent 1px 3px);
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.16);
}

.brand-mark::after {
  content: '';
  position: absolute;
  inset: 5px;
  border: 1px dashed rgba(243, 237, 226, 0.5);
  border-radius: 3px;
}

.brand-mark svg {
  width: 21px;
  height: 21px;
  stroke-width: 1.9;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.brand-text {
  display: flex;
  flex-direction: column;
  line-height: 1.25;
}

.brand-text b {
  font-size: 17px;
  font-weight: 600;
  letter-spacing: 0.08em;
  color: var(--ys-mo);
}

.brand-text i {
  font-style: normal;
  font-family: var(--ys-font-num);
  font-size: 10px;
  letter-spacing: 0.16em;
  color: #8c8272;
  margin-top: 2px;
}

/* 色布 —— 这一页唯一放纵的地方 */
.bolt-row {
  position: relative;
  display: flex;
  gap: 2px;
  margin: auto 0;
  padding-top: 18px;
  height: clamp(220px, 34vh, 340px);
}

.bolt {
  position: relative;
  flex: 1;
  border-radius: 2px;
  overflow: hidden;
}

.bolt::after {
  content: '';
  position: absolute;
  inset: 0;
  background-image: repeating-linear-gradient(
      90deg,
      rgba(255, 255, 255, 0.16) 0 1px,
      transparent 1px 3px
    ),
    repeating-linear-gradient(0deg, rgba(0, 0, 0, 0.035) 0 1px, transparent 1px 4px);
}

.bolt-tag {
  position: absolute;
  left: 8px;
  bottom: 10px;
  z-index: 1;
  writing-mode: vertical-rl;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11px;
  letter-spacing: 0.18em;
  color: rgba(255, 253, 250, 0.94);
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.14);
}

.bolt-tag em {
  font-style: normal;
  font-family: var(--ys-font-num);
  font-size: 9px;
  letter-spacing: 0.1em;
  opacity: 0.72;
}

.bolt--light .bolt-tag {
  color: rgba(35, 32, 28, 0.66);
  text-shadow: none;
}

.stage-caption {
  position: relative;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px solid rgba(120, 100, 74, 0.18);
}

.quote {
  margin: 0;
  max-width: 30ch;
  font-size: 13px;
  line-height: 1.7;
  color: #6f6656;
}

.quote b {
  display: block;
  margin-bottom: 3px;
  font-size: 14px;
  font-weight: 500;
  letter-spacing: 0.02em;
  color: #4a4438;
}

.spec {
  margin: 0;
  font-family: var(--ys-font-num);
  font-size: 11px;
  line-height: 1.7;
  color: #918675;
  text-align: right;
  white-space: nowrap;
}

/* ══ 右侧登录表单 ════════════════════════════════════════════════════ */
.login-panel {
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 40px clamp(28px, 8vw, 104px);
  background: var(--ys-cibai);
}

.login-form-wrap {
  width: 100%;
  max-width: 356px;
  margin: 0 auto;
}

.panel-eyebrow {
  margin: 0;
  font-size: 13px;
  letter-spacing: 0.02em;
  color: var(--ys-mo-3);
}

.panel-title {
  margin: 6px 0 0;
  font-size: 26px;
  font-weight: 500;
  letter-spacing: 0.02em;
  line-height: 1.35;
  color: var(--ys-mo);
}

.panel-help {
  margin: 8px 0 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--ys-mo-3);
}

.login-form {
  margin-top: 26px;
}

.login-form :deep(.el-form-item__label) {
  font-size: 12px;
  color: var(--ys-mo-3);
  padding-bottom: 4px;
}

.login-form :deep(.el-form-item) {
  margin-bottom: 16px;
}

.login-form :deep(.el-input__wrapper) {
  height: 42px;
  border-radius: 11px;
  background: var(--ys-warm-50);
  box-shadow: 0 0 0 1px #e6dccc inset;
}

.login-form :deep(.el-input__wrapper.is-focus) {
  background: var(--ys-cibai);
  box-shadow: 0 0 0 1px var(--ys-daiqing) inset, 0 0 0 3px rgba(46, 93, 99, 0.1);
}

.submit-item {
  margin-top: 4px;
}

.login-button {
  width: 100%;
  height: 44px;
  border-radius: 11px;
  font-size: 14px;
}

.panel-tip {
  margin: 16px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ys-mo-4);
}

.panel-foot {
  margin: 30px 0 0;
  padding-top: 16px;
  border-top: 1px solid var(--ys-line-soft);
  font-size: 11px;
  line-height: 1.7;
  color: var(--ys-mo-5);
}

/* ══ 窄屏 ════════════════════════════════════════════════════════════ */
@media (max-width: 860px) {
  .login-page {
    grid-template-columns: 1fr;
  }

  .login-stage {
    min-height: 30vh;
    padding: 22px 22px 16px;
    border-right: 0;
    border-bottom: 1px solid var(--ys-line);
  }

  .bolt-row {
    height: 132px;
    padding-top: 10px;
  }

  .bolt-tag {
    writing-mode: horizontal-tb;
    font-size: 10px;
    letter-spacing: 0.06em;
  }

  .bolt-tag em,
  .stage-caption {
    display: none;
  }

  .login-panel {
    padding: 32px 22px 44px;
  }
}
</style>
