#!/usr/bin/env node
/**
 * 进销存 MCP Server（stdio）。
 *
 * 启动环境变量：
 *   ERP_BASE_URL  后端地址（默认 http://127.0.0.1:8080/api/v1）
 *   ERP_TOKEN     登录后拿到的 JWT（必填）
 *
 * ★ stdio 模式下 stdout 属于协议通道：日志一律写 stderr，否则会破坏 JSON-RPC 流。
 */
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js'
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js'
import { ErpClient } from './erpClient.js'
import { registerTools } from './tools.js'

const baseUrl = process.env.ERP_BASE_URL ?? 'http://127.0.0.1:8080/api/v1'
const token = process.env.ERP_TOKEN ?? ''

if (!token.trim()) {
  console.error('[erp-mcp] 缺少 ERP_TOKEN。请先 POST /api/v1/auth/login 获取 JWT，再作为环境变量传给 MCP Server。')
  process.exit(1)
}

const server = new McpServer({
  name: 'erp-inventory-mcp',
  version: '0.1.0',
})

registerTools(server, new ErpClient({ baseUrl, token }))

const transport = new StdioServerTransport()
await server.connect(transport)
console.error(`[erp-mcp] connected via stdio; baseUrl=${baseUrl}`)
