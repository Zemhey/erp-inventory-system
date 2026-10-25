<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { finApi, type FinAccountView } from '@/api/client'
import { useSessionStore } from '@/stores/session'

/**
 * 会计科目表（FR-V3-06）。
 * 左侧科目树（按 category 分六大类分组）+ 右侧编辑表单。
 * 验收：树能展开；新增科目出现在树里；被凭证引用的科目删除返回 409（消息原样透出）。
 */

const session = useSessionStore()
const canWrite = computed(() => session.hasPermission('fin:account:write'))

const CATEGORIES = [
  { key: 'ASSET', label: '资产' },
  { key: 'LIABILITY', label: '负债' },
  { key: 'EQUITY', label: '权益' },
  { key: 'COST', label: '成本' },
  { key: 'REVENUE', label: '收入' },
  { key: 'EXPENSE', label: '费用' },
] as const

const flat = ref<FinAccountView[]>([])
const loading = ref(false)
const selected = ref<FinAccountView | null>(null)
const creating = ref(false)

/** 树数据：六大类虚拟根 → 一级科目 → 子科目（递归）。 */
const tree = computed(() =>
  CATEGORIES.map((c) => ({
    key: `cat-${c.key}`,
    label: `${c.label}类`,
    category: true as const,
    children: buildRoots(c.key),
  })),
)

function buildRoots(category: string): unknown[] {
  const kids = flat.value.filter((a) => a.category === category && a.parentId == null)
  return kids.map((a) => node(a))
}

function node(a: FinAccountView): Record<string, unknown> {
  const children = flat.value.filter((x) => x.parentId === a.id).map((x) => node(x))
  return { key: a.id, label: `${a.code} ${a.name}`, account: a, children }
}

const form = reactive({
  code: '',
  name: '',
  category: 'ASSET',
  direction: 'DEBIT',
  initBalance: 0,
  enabled: true,
  remark: '',
})

async function load() {
  loading.value = true
  try {
    const data = await finApi.accounts()
    flat.value = data.items
  } finally {
    loading.value = false
  }
}

function onTreeClick(item: { account?: FinAccountView }) {
  if (!item.account) return
  selected.value = item.account
  creating.value = false
  form.code = item.account.code
  form.name = item.account.name
  form.category = item.account.category
  form.direction = item.account.direction
  form.initBalance = item.account.initBalance
  form.enabled = item.account.enabled
  form.remark = item.account.remark ?? ''
}

function startCreate(parent?: FinAccountView) {
  creating.value = true
  selected.value = null
  form.code = ''
  form.name = ''
  form.category = parent?.category ?? 'ASSET'
  form.direction = parent?.direction ?? 'DEBIT'
  form.initBalance = 0
  form.enabled = true
  form.remark = ''
  pendingParentId.value = parent?.id ?? null
}

const pendingParentId = ref<number | null>(null)

async function save() {
  try {
    if (creating.value) {
      await finApi.createAccount({
        code: form.code.trim(),
        name: form.name.trim(),
        parentId: pendingParentId.value,
        category: form.category,
        direction: form.direction,
        initBalance: Number(form.initBalance) || 0,
        enabled: form.enabled,
        remark: form.remark,
      })
      ElMessage.success('科目已创建')
    } else if (selected.value) {
      await finApi.updateAccount(selected.value.id, {
        name: form.name.trim(),
        direction: form.direction,
        initBalance: Number(form.initBalance) || 0,
        enabled: form.enabled,
        remark: form.remark,
      })
      ElMessage.success('科目已保存')
    }
    await load()
  } catch {
    /* 拦截器已提示（含 409 编码重复） */
  }
}

async function remove() {
  if (!selected.value) return
  const a = selected.value
  try {
    await ElMessageBox.confirm(
      `删除科目 ${a.code} ${a.name}？被凭证分录引用的科目会被后端拒绝（409）。`,
      '删除科目',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await finApi.deleteAccount(a.id)
    ElMessage.success('已删除')
    selected.value = null
    await load()
  } catch {
    /* 409 FIN_ACCOUNT_IN_USE 等消息由拦截器原样透出 */
  }
}

onMounted(load)
</script>

<template>
  <div class="account-page">
    <el-row :gutter="12">
      <el-col :xs="24" :sm="10" :md="8">
        <el-card shadow="never" class="tree-card">
          <template #header>
            <div class="card-header">
              <span>科目树（六大类）</span>
              <el-button v-if="canWrite" size="small" type="primary" plain @click="startCreate()">
                新建一级科目
              </el-button>
            </div>
          </template>
          <el-tree
            v-loading="loading"
            :data="tree as never[]"
            node-key="key"
            :props="{ label: 'label', children: 'children' }"
            default-expand-all
            highlight-current
            @node-click="onTreeClick"
          >
            <template #default="{ data }">
              <span class="tree-node">
                <span>{{ data.label }}</span>
                <el-tag v-if="data.account && !data.account.leaf" size="small" type="info">非末级</el-tag>
                <el-tag v-if="data.account && !data.account.enabled" size="small" type="danger">停用</el-tag>
                <el-button
                  v-if="canWrite && data.account"
                  link
                  size="small"
                  @click.stop="startCreate(data.account)"
                >
                  加子科目
                </el-button>
              </span>
            </template>
          </el-tree>
        </el-card>
      </el-col>

      <el-col :xs="24" :sm="14" :md="16">
        <el-card shadow="never">
          <template #header>
            <span>{{ creating ? '新建科目' : selected ? `编辑：${selected.code} ${selected.name}` : '选择左侧科目' }}</span>
          </template>
          <el-form v-if="creating || selected" label-width="100px">
            <el-form-item label="科目编码">
              <el-input v-model="form.code" :disabled="!creating" maxlength="32"
                        :placeholder="creating ? '如 1122' : '编码创建后不可改'" />
            </el-form-item>
            <el-form-item label="科目名称">
              <el-input v-model="form.name" maxlength="64" />
            </el-form-item>
            <el-form-item label="类别">
              <el-select v-model="form.category" :disabled="!creating" class="w-180">
                <el-option v-for="c in CATEGORIES" :key="c.key" :label="`${c.label}（${c.key}）`" :value="c.key" />
              </el-select>
            </el-form-item>
            <el-form-item label="余额方向">
              <el-radio-group v-model="form.direction">
                <el-radio value="DEBIT">借</el-radio>
                <el-radio value="CREDIT">贷</el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="期初余额(元)">
              <el-input-number v-model="form.initBalance" :min="0" :precision="2" :step="100" />
            </el-form-item>
            <el-form-item label="启用">
              <el-switch v-model="form.enabled" />
            </el-form-item>
            <el-form-item label="备注">
              <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="255" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="save">保存</el-button>
              <el-button v-if="selected && canWrite" type="danger" plain @click="remove">删除</el-button>
              <el-button @click="creating = false">取消</el-button>
            </el-form-item>
          </el-form>
          <el-empty v-else description="点左侧树里的科目即可编辑；金额口径：元" />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.tree-card {
  min-height: 480px;
}

.tree-node {
  display: flex;
  align-items: center;
  gap: 6px;
}

.w-180 {
  width: 180px;
}
</style>
