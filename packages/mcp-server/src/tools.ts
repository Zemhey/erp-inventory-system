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

export function registerTools(server: McpServer, client: ErpClient): void {
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
        '用一句自然语言操作进销存系统（FR-SYS-04）。支持：查库存、查单据列表/详情、进销存汇总、查主数据、',
        '创建进货草稿、创建出货草稿。写操作只建草稿，不审核/入库/出货/作废。',
        '示例：“查询 SKU 12 在仓库 3 的库存”“账期 2026-10 进销存汇总”“进货单列表 状态 已审核”。',
      ].join(''),
      inputSchema: {
        command: z.string().min(1).max(500).describe('自然语言指令'),
      },
    },
    (args) => safe(() => client.nlCommand(args.command)),
  )
}
