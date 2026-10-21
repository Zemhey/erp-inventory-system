<script setup lang="ts">
/**
 * 页面占位组件。
 *
 * 为什么要有它：M3 分步交付，路由已全部注册，但部分页面尚未实现。
 * 与其让用户点到白屏、或让路由指向不存在的文件导致构建失败，
 * 不如明确显示"该页面在哪个里程碑实现、会做什么"。
 *
 * 这不是凑数 —— 一个诚实的"待实现"比一个假的"暂无数据"更有用：
 * 后者会让人以为是查不到数据，前者明确告诉你是功能还没上。
 */
interface Props {
  title: string
  milestone: string
  description: string
  /** 已规划的具体功能点 */
  features?: string[]
}

defineProps<Props>()
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2 class="page-title">{{ title }}</h2>
    </div>

    <el-card shadow="never">
      <el-result icon="info" :title="`计划于 ${milestone} 实现`" :sub-title="description">
        <template #extra>
          <div v-if="features && features.length" class="feature-list">
            <p class="text-muted">该页面将包含：</p>
            <ul>
              <li v-for="f in features" :key="f">{{ f }}</li>
            </ul>
          </div>
        </template>
      </el-result>
    </el-card>
  </div>
</template>

<style scoped>
.feature-list {
  text-align: left;
  display: inline-block;
}

.feature-list ul {
  margin: 8px 0 0;
  padding-left: 20px;
  color: #606266;
  line-height: 1.9;
}
</style>
