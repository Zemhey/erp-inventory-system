<script setup lang="ts">
/**
 * 报表中心（FR-V3-31）。
 *
 * 左侧报表目录（5 类 11 张）+ 右侧 ReportShell。
 *
 * ★ 为什么既有 /reports/<id> 独立路由、又有一个目录页：
 *   - 独立路由让每张报表能收藏、能从侧边栏直达、浏览器标题正确；
 *   - 目录页（/reports/catalog）保留「左侧切报表」的体验，切报表只换 props。
 *   两条路走的是同一个组件、同一份 ReportDefinition，不会出现两份实现。
 *
 * ★ 旧的 /reports（ReportView.vue 单页 8 个 tab）原样保留，不删路由，
 *   这里只做它的「拆版」，其他页面已有的跳转链接不会 404。
 */
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { DataAnalysis } from '@element-plus/icons-vue'
import ReportShell from './components/ReportShell.vue'
import { REPORTS, REPORT_GROUPS, findReport } from './core/registry'
import type { ReportDefinition } from './core/types'

const props = defineProps<{ report?: string }>()

const route = useRoute()

/** 报表 id 来自路由 props（/reports/<id>）；目录页兜底用 ?r=<id>，再兜底第一张。 */
const activeId = computed(() => props.report || String(route.query.r ?? '') || '')

const active = computed<ReportDefinition>(() => findReport(activeId.value) ?? REPORTS[0])

const grouped = computed(() =>
  REPORT_GROUPS.map((group) => ({
    ...group,
    reports: REPORTS.filter((r) => r.group === group.key),
  })).filter((g) => g.reports.length > 0),
)

const total = REPORTS.length
</script>

<template>
  <div class="report-center">
    <aside class="rpt-catalog no-print">
      <div class="catalog-head">
        <el-icon><DataAnalysis /></el-icon>
        <span>报表目录</span>
        <em>{{ total }} 张</em>
      </div>
      <nav class="catalog-body">
        <section v-for="group in grouped" :key="group.key" class="catalog-group">
          <h4 class="catalog-group-title">{{ group.title }}</h4>
          <RouterLink
            v-for="item in group.reports"
            :key="item.id"
            :to="{ path: `/reports/${item.id}` }"
            class="catalog-item"
            :class="{ active: item.id === active.id }"
          >
            <span class="catalog-item-title">{{ item.title }}</span>
            <span class="catalog-item-tag">
              {{ item.csv.kind === 'server' ? '服务端导出' : '前端导出' }}
            </span>
          </RouterLink>
        </section>
      </nav>
      <div class="catalog-foot">
        <RouterLink class="catalog-item foot-link" :to="{ path: '/reports' }">
          <span class="catalog-item-title">旧版多标签报表</span>
        </RouterLink>
      </div>
    </aside>

    <main class="rpt-body">
      <ReportShell :key="active.id" :definition="active" />
    </main>
  </div>
</template>

<style scoped>
.report-center {
  display: flex;
  gap: 14px;
  align-items: flex-start;
}
.rpt-catalog {
  flex: none;
  width: 208px;
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  position: sticky;
  top: 12px;
  max-height: calc(100vh - 110px);
  overflow-y: auto;
}
.catalog-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 11px 12px;
  font-size: 13px;
  font-weight: 600;
  border-bottom: 1px solid #f0f2f5;
  color: #1f2d3d;
}
.catalog-head em {
  margin-left: auto;
  font-style: normal;
  font-size: 11px;
  color: #a0a6b0;
}
.catalog-body {
  padding: 6px 0 10px;
}
.catalog-group + .catalog-group {
  margin-top: 4px;
  padding-top: 6px;
  border-top: 1px dashed #eef2f7;
}
.catalog-group-title {
  margin: 6px 12px 2px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.4px;
  color: #9aa4b2;
}
.catalog-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 7px 12px;
  font-size: 13px;
  color: #4a5568;
  text-decoration: none;
  border-left: 2px solid transparent;
}
.catalog-item:hover {
  background: #f6f9fd;
  color: #2f7ed8;
}
.catalog-item.active {
  background: #eef5fd;
  color: #2f7ed8;
  border-left-color: #2f7ed8;
  font-weight: 600;
}
.catalog-item-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.catalog-item-tag {
  flex: none;
  font-size: 10px;
  color: #b1b7c0;
}
.foot-link {
  font-size: 12px;
  color: #909399;
}
.catalog-foot {
  border-top: 1px solid #f0f2f5;
  padding: 4px 0;
}
.rpt-body {
  flex: 1;
  min-width: 0;
}

@media print {
  .rpt-catalog {
    display: none !important;
  }
  .report-center {
    display: block;
  }
}

@media (max-width: 900px) {
  .report-center {
    flex-direction: column;
  }
  .rpt-catalog {
    width: 100%;
    position: static;
    max-height: none;
  }
}
</style>
