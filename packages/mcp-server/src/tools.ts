/**
 * MCP 工具注册：每个工具都是后端 REST 接口的薄适配器。
 *
 * 这里刻意不实现任何业务判断（库存够不够、单据能不能操作），
 * 与后端 NL 层同一条边界：Agent 拿到的是后端真实结果与错误码。
 */
import type { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js'
import { z } from 'zod'
import { ErpClient, ErpHttpError } from './erpClient.js'

type ToolResult = {
  content: Array<{ type: 'text'; text: string }>
  isError?: boolean
}

const MAX_TEXT = 20_000

function textResult(data: unknown): ToolResult {
  const text = typeof data === 'string' ? data : JSON.stringify(data, null, 2)
  return {
    content: [{
      type: 'text',
      text: text.length > MAX_TEXT ? `${text.slice(0, MAX_TEXT)}\n…（已截断，共 ${text.length} 字符）` : text,
    }],
  }
}

function errorResult(error: unknown): ToolResult {
  if (error instanceof ErpHttpError) {
    return {
      isError: true,
      content: [{
        type: 'text',
        text: JSON.stringify({
          error: {
            status: error.status,
            code: error.code,
            message: error.message,
            details: error.details,
          },
        }, null, 2),
      }],
    }
  }
  return {
    isError: true,
    content: [{
      type: 'text',
      text: JSON.stringify({
        error: { code: 'MCP_INTERNAL', message: (error as Error)?.message ?? String(error) },
      }, null, 2),
    }],
  }
}

async function safe(run: () => Promise<unknown>): Promise<ToolResult> {
  try {
    return textResult(await run())
  } catch (error) {
    return errorResult(error)
  }
}

/** 供 index.ts 的 /health 汇报用：工具清单只有一处定义，避免两边数不一致。 */
export const TOOL_NAMES = [
  'system_status',
  'dashboard_overview',
  'query_inventory',
  'list_documents',
  'get_document',
  'inventory_summary',
  'query_master_data',
  'nl_command',
  'nl_plan',
] as const

export function registerTools(server: McpServer, client: ErpClient): void {
  // ── 0. 自检：Agent 在被问到"系统通不通"时，先有据可依 ──
  server.registerTool(
    'system_status',
    {
      title: '系统运行状态',
      description:
        '检查进销存后端是否在线、数据库等依赖是否健康、当前以什么身份连接。不需要任何权限，也不产生业务数据。',
      inputSchema: {},
    },
    () =>
      safe(async () => {
        const health = await client.health()
        return {
          backend: {
            status: health.status,
            baseUrl: client.origin,
            latencyMs: health.latencyMs,
            components: health.components,
          },
          auth: client.authInfo(),
          server: 'erp-inventory-mcp 0.2.0',
          tools: TOOL_NAMES,
        }
      }),
  )

  // ── 1. 工作台聚合：一次拿到 KPI + 待办 + 最近单据 ──
  server.registerTool(
    'dashboard_overview',
    {
      title: '经营概览',
      description:
        '一次取回工作台聚合数据：8 个 KPI（商品/SKU/客户/供应商/进货单/出货单/库存行/零库存）、待办计数、最近单据、趋势图。需要 dashboard:read。',
      inputSchema: {
        includeCharts: z.boolean().optional().describe('是否包含趋势图数据（默认关闭，减少 token 消耗）'),
      },
    },
    (args) =>
      safe(async () => {
        const data = (await client.dashboardSummary()) as Record<string, unknown>
        if (args.includeCharts) return data
        const { charts: _charts, ...rest } = data
        return { ...rest, chartsOmitted: true }
      }),
  )

  server.registerTool(
    'query_inventory',
    {
      title: '查询库存',
      description: '查询库存余额（实存/预占/可用）。可按 SKU、仓库、批次过滤；参数全空时返回所有库存行。需要 inv:read。',
      inputSchema: {
        skuId: z.number().int().positive().optional().describe('SKU id'),
        warehouseId: z.number().int().positive().optional().describe('仓库 id'),
        batchNo: z.string().optional().describe('批次号'),
        onlyAvailable: z.boolean().optional().describe('只看可用量大于 0 的行'),
      },
    },
    (args) => safe(() => client.queryInventory(args)),
  )

  server.registerTool(
    'list_documents',
    {
      title: '查询单据列表',
      description: '分页查询进货单 / 出货单 / 退货单。partyId 对进货是供应商 id，对出货/退货是客户 id。需要对应模块的读权限。',
      inputSchema: {
        type: z.enum(['purchase', 'sales', 'return']),
        status: z.enum(['DRAFT', 'SUBMITTED', 'APPROVED', 'PARTIAL', 'COMPLETED', 'VOIDED', 'REJECTED']).optional(),
        partyId: z.number().int().positive().optional().describe('进货=供应商 id；出货/退货=客户 id'),
        originSalesOrderId: z.number().int().positive().optional().describe('退货单的原出货单 id'),
        page: z.number().int().min(1).optional(),
        size: z.number().int().min(1).max(200).optional(),
      },
    },
    (args) => safe(() => client.listDocuments(args)),
  )

  server.registerTool(
    'get_document',
    {
      title: '查询单据详情',
      description: '按 id 查询一张进货单 / 出货单 / 退货单的详情。需要对应模块的读权限。',
      inputSchema: {
        type: z.enum(['purchase', 'sales', 'return']),
        id: z.number().int().positive(),
      },
    },
    (args) => safe(() => client.getDocument(args.type, args.id)),
  )

  server.registerTool(
    'inventory_summary',
    {
      title: '进销存汇总',
      description: '查询进销存汇总（期初 + 入 − 出 = 期末）。period 格式 yyyy-MM；口径按库存流水发生时间 occurred_at。需要 rpt:read。',
      inputSchema: {
        period: z.string().regex(/^\d{4}-\d{2}$/).describe('账期，如 2026-10'),
        warehouseId: z.number().int().positive().optional().describe('可选仓库过滤'),
      },
    },
    (args) => safe(() => client.inventorySummary(args)),
  )

  server.registerTool(
    'query_master_data',
    {
      title: '查询主数据',
      description: '查询商品 / SKU / 供应商 / 客户 / 仓库。keyword 同时匹配编码与名称（模糊）。需要 mdm:read。',
      inputSchema: {
        type: z.enum(['product', 'sku', 'supplier', 'customer', 'warehouse']),
        keyword: z.string().optional(),
        productId: z.number().int().positive().optional().describe('按商品过滤 SKU'),
        color: z.string().optional().describe('按颜色过滤 SKU'),
        skuSize: z.string().optional().describe('按尺码过滤 SKU'),
        onlyEnabled: z.boolean().optional().describe('仓库：仅启用'),
        page: z.number().int().min(1).optional(),
        size: z.number().int().min(1).max(200).optional(),
      },
    },
    (args) => safe(() => client.queryMasterData(args)),
  )

  server.registerTool(
    'nl_command',
    {
      title: '自然语言操作',
      description: [
        '用一句自然语言操作进销存系统（FR-SYS-04），与网页底部的「自然语言操作台」是同一个后端入口。支持：',
        '查库存、查单据列表/详情、进销存汇总、**补货建议**（目标级：一次给出该补哪些 SKU、各补多少，',
        '组合可用量/在途/上下限）、查主数据、创建进货草稿、创建出货草稿。',
        '写操作只建草稿，不审核/入库/出货/作废。',
        '示例：“补货建议 仓库 421”“查询 SKU 12 在仓库 3 的库存”“账期 2026-10 进销存汇总”。',
      ].join(''),
      inputSchema: {
        command: z.string().min(1).max(500).describe('自然语言指令'),
      },
    },
    (args) => safe(() => client.nlCommand(args.command)),
  )

  // ── 执行计划：写操作前先给人看"将要发生什么"（FR-V4-03 · S3） ──
  // 为什么单独暴露：Agent 直接调 nl_command 就落库了，人看不到中间那一步。
  // 有了 nl_plan，Agent 可以先拿计划、把影响念给人听，得到同意再执行。
  server.registerTool(
    'nl_plan',
    {
      title: '生成执行计划（不执行）',
      description: [
        '把一句自然语言变成"执行计划"：打算做什么、影响什么、有没有问题，但**不做任何修改**。',
        '返回三块：`plan`（计划）、`review`（复核意见）、`confirmation`（确认回执）。',
        '`plan.requiresReview=true` 表示这是写操作：先向人展示 `plan.summary` 与 `review.findings`，',
        '得到同意后带 `confirmation.token` 调 `nl_command`；`requiresReview=false` 表示只读，可直接执行。',
        '`plan.executable=false` 时看 `plan.blockers`：缺参数或引用的对象不存在，补齐后再试。',
        '`review.verdict` 三档：APPROVE（复核不反对，**不等于批准**）、NEED_HUMAN（要人判断）、',
        'REJECT（不允许执行，此时不会签发回执）。',
        '它是只读的，可以反复调用。示例：“创建进货单 供应商=1 仓库=421 SKU=629 数量=10 单价=1200”。',
      ].join(''),
      inputSchema: {
        command: z.string().min(1).max(500).describe('自然语言指令'),
      },
    },
    (args) => safe(() => client.nlPlan(args.command)),
  )
}
