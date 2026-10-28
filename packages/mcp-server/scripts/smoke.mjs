#!/usr/bin/env node
/**
 * MCP 最小客户端实证（stdio，不依赖 Inspector）：
 *   1) 启动 dist/index.js（stdio）；
 *   2) listTools 打印工具清单并校验 8 个工具都在；
 *   3) 依次调用 system_status / query_inventory / inventory_summary / nl_command；
 *   4) 任一工具返回 isError 或抛异常 → 退出码非 0。
 *
 * 用法（两种凭据任选）：
 *   ERP_USERNAME=admin ERP_PASSWORD=*** npm run smoke
 *   ERP_TOKEN=<JWT> npm run smoke
 * 可选：
 *   ERP_BASE_URL（默认 http://127.0.0.1:8080/api/v1）
 *   SMOKE_PERIOD=2026-10（默认当前月）
 *   SMOKE_SKU_ID=12（默认不按 SKU 过滤）
 */
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js'
import { fileURLToPath } from 'node:url'
import fs from 'node:fs'
import path from 'node:path'

const here = path.dirname(fileURLToPath(import.meta.url))

/**
 * 读取同目录上一级的 .env（KEY=VALUE 一行一条，忽略 # 注释）。
 * 不引 dotenv 依赖：这里只需要最朴素的语法，而且 .env 里放的是账号密码，
 * 少一个第三方包就少一处可能把它打印出来的地方。
 */
function loadEnvFile() {
  const file = path.resolve(here, '..', '.env')
  if (!fs.existsSync(file)) return
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const trimmed = line.trim()
    if (!trimmed || trimmed.startsWith('#')) continue
    const eq = trimmed.indexOf('=')
    if (eq <= 0) continue
    const key = trimmed.slice(0, eq).trim()
    const value = trimmed.slice(eq + 1).trim()
    if (!process.env[key]) process.env[key] = value
  }
}

loadEnvFile()

const token = process.env.ERP_TOKEN?.trim() ?? ''
const username = process.env.ERP_USERNAME?.trim() ?? ''
const password = process.env.ERP_PASSWORD?.trim() ?? ''

if (!token && !(username && password)) {
  console.error('缺少凭据：给 ERP_TOKEN，或同时给 ERP_USERNAME / ERP_PASSWORD（也可写在 packages/mcp-server/.env）')
  process.exit(2)
}

const baseUrl = process.env.ERP_BASE_URL ?? 'http://127.0.0.1:8080/api/v1'
const now = new Date()
const period = process.env.SMOKE_PERIOD
  ?? `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
const skuId = process.env.SMOKE_SKU_ID ? Number(process.env.SMOKE_SKU_ID) : undefined

const entry = path.resolve(here, '..', 'dist', 'index.js')

const transport = new StdioClientTransport({
  command: process.execPath,
  args: [entry],
  env: {
    ...process.env,
    ERP_BASE_URL: baseUrl,
    ERP_TOKEN: token,
    ERP_USERNAME: username,
    ERP_PASSWORD: password,
    ERP_MCP_TRANSPORT: 'stdio',
  },
  stderr: 'inherit',
})

const client = new Client({ name: 'erp-mcp-smoke', version: '0.2.0' })
await client.connect(transport)

const listed = await client.listTools()
const names = listed.tools.map((tool) => tool.name)
console.log(`[smoke] tools (${names.length}): ${names.join(', ')}`)

for (const expected of [
  'system_status',
  'dashboard_overview',
  'query_inventory',
  'list_documents',
  'get_document',
  'inventory_summary',
  'query_master_data',
  'nl_command',
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
  console.log(text.length > 2000 ? `${text.slice(0, 2000)}\n…（已截断）` : text)
  if (result.isError) {
    throw new Error(`${name} 返回 isError`)
  }
  return result
}

try {
  await call('system_status', {})
  await call('query_inventory', skuId ? { skuId } : {})
  await call('inventory_summary', { period })
  await call('nl_command', { command: skuId ? `查询 SKU ${skuId} 的库存` : '查询库存' })
  console.log(`\n[smoke] PASS：${names.length} 个工具可见，4 个真实调用成功`)
} finally {
  await client.close()
}
