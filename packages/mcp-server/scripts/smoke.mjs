#!/usr/bin/env node
/**
 * MCP 最小客户端实证（不依赖 Inspector）：
 *   1) 启动 dist/index.js（stdio）；
 *   2) listTools 打印工具清单；
 *   3) 依次调用 query_inventory / inventory_summary / nl_command；
 *   4) 任一工具返回 isError 或抛异常 → 退出码非 0。
 *
 * 用法：
 *   ERP_BASE_URL=http://127.0.0.1:8083/api/v1 ERP_TOKEN=<JWT> npm run smoke
 * 可选：
 *   SMOKE_PERIOD=2026-10（默认当前月）
 *   SMOKE_SKU_ID=12（默认不按 SKU 过滤）
 */
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const token = process.env.ERP_TOKEN?.trim()
if (!token) {
  console.error('缺少 ERP_TOKEN：先登录后端拿 JWT 再运行 smoke')
  process.exit(2)
}
const baseUrl = process.env.ERP_BASE_URL ?? 'http://127.0.0.1:8080/api/v1'
const now = new Date()
const period = process.env.SMOKE_PERIOD
  ?? `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
const skuId = process.env.SMOKE_SKU_ID ? Number(process.env.SMOKE_SKU_ID) : undefined

const here = path.dirname(fileURLToPath(import.meta.url))
const entry = path.resolve(here, '..', 'dist', 'index.js')

const transport = new StdioClientTransport({
  command: process.execPath,
  args: [entry],
  env: { ...process.env, ERP_BASE_URL: baseUrl, ERP_TOKEN: token },
  stderr: 'inherit',
})

const client = new Client({ name: 'erp-mcp-smoke', version: '0.1.0' })
await client.connect(transport)

const listed = await client.listTools()
const names = listed.tools.map((tool) => tool.name)
console.log(`[smoke] tools (${names.length}): ${names.join(', ')}`)

for (const expected of [
  'query_inventory', 'list_documents', 'get_document',
  'inventory_summary', 'query_master_data', 'nl_command',
]) {
  if (!names.includes(expected)) {
    throw new Error(`工具清单缺少 ${expected}`)
  }
}

async function call(name, args) {
  const result = await client.callTool({ name, arguments: args })
  const text = (result.content ?? [])
    .map((item) => (item.type === 'text' ? item.text : JSON.stringify(item)))
    .join('\n')
  console.log(`\n[smoke] ${name} →`)
  console.log(text.length > 3000 ? `${text.slice(0, 3000)}\n…（已截断）` : text)
  if (result.isError) {
    throw new Error(`${name} 返回 isError`)
  }
  return result
}

try {
  await call('query_inventory', skuId ? { skuId } : {})
  await call('inventory_summary', { period })
  await call('nl_command', { command: skuId ? `查询 SKU ${skuId} 的库存` : '查询库存' })
  console.log('\n[smoke] PASS：6 个工具可见，3 个真实调用成功')
} finally {
  await client.close()
}
