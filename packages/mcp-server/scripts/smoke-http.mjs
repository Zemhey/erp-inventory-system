#!/usr/bin/env node
/**
 * HTTP 传输实证：证明"只填一个 URL 就能用"的 Agent（豆包、云端智能体、
 * 自研控制台）也能驱动本系统。
 *
 * 前置：另开一个终端跑 `npm run start:http`（监听 127.0.0.1:8790）。
 *
 * 用法：
 *   ERP_USERNAME=admin ERP_PASSWORD=*** npm run smoke:http
 *   ERP_TOKEN=<JWT> npm run smoke:http
 * 可选：ERP_MCP_URL（默认 http://127.0.0.1:8790/mcp）
 */
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'

const url = new URL(process.env.ERP_MCP_URL ?? 'http://127.0.0.1:8790/mcp')

// 先探活，把"没启动"和"启动了但出错"分开报，省得对着一句 fetch failed 猜
const health = await fetch(new URL('/health', url)).then((r) => r.json()).catch(() => null)
if (!health) {
  console.error(`[smoke:http] ${url.origin}/health 不可达：先执行 npm run start:http`)
  process.exit(2)
}
console.log(`[smoke:http] server=${health.name} ${health.version} tools=${health.tools} auth=${health.auth}`)

const transport = new StreamableHTTPClientTransport(url)
const client = new Client({ name: 'erp-mcp-smoke-http', version: '0.2.0' })
await client.connect(transport)

const listed = await client.listTools()
const names = listed.tools.map((tool) => tool.name)
console.log(`[smoke:http] tools (${names.length}): ${names.join(', ')}`)

const status = await client.callTool({ name: 'system_status', arguments: {} })
const statusText = (status.content ?? []).find((c) => c.type === 'text')?.text ?? '{}'
const parsed = JSON.parse(statusText)
if (status.isError) {
  throw new Error(`system_status 返回 isError：${statusText}`)
}
console.log(`[smoke:http] system_status → backend=${parsed.backend?.status} auth=${parsed.auth?.mode}`)

const nl = await client.callTool({ name: 'nl_command', arguments: { command: '查询库存' } })
if (nl.isError) {
  throw new Error('nl_command 返回 isError')
}
console.log('[smoke:http] nl_command → OK')

await client.close()
console.log('\n[smoke:http] PASS：HTTP 传输可用（豆包 / 云端 Agent 走这条路）')
