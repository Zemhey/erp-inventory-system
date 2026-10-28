#!/usr/bin/env node
/**
 * 进销存 MCP Server。
 *
 * 两种传输：
 *   stdio（默认）—— 给 Claude Desktop / WorkBuddy / Cursor 这类"本地拉起子进程"的客户端；
 *   http          —— 给只要能填 URL 的 Agent（豆包、云端智能体、自研控制台）。
 *                    设 ERP_MCP_TRANSPORT=http 开启，默认监听 127.0.0.1:8790。
 *
 * 启动环境变量：
 *   ERP_BASE_URL     后端地址（默认 http://127.0.0.1:8080/api/v1）
 *   ERP_TOKEN        已有 JWT（可选）
 *   ERP_USERNAME     账号（与 ERP_PASSWORD 同时给时，自动登录 + 401 续签）
 *   ERP_PASSWORD     密码
 *   ERP_MCP_TRANSPORT  stdio | http（默认 stdio）
 *   ERP_MCP_HTTP_PORT  HTTP 端口（默认 8790）
 *   ERP_MCP_BIND       HTTP 绑定地址（默认 127.0.0.1，别随手改成 0.0.0.0）
 *
 * ★ stdio 模式下 stdout 属于协议通道：日志一律写 stderr，否则会破坏 JSON-RPC 流。
 *   写数据也一样 —— 全程只往 `origin` 发请求，不往 stdout 打印任何东西。
 */
import http from 'node:http'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js'
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js'
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js'
import { ErpClient } from './erpClient.js'
import { registerTools, TOOL_NAMES } from './tools.js'

/**
 * 读 dist 同上一级的 .env（KEY=VALUE）。已存在的环境变量优先，
 * 这样"临时用 ERP_TOKEN 覆盖 .env 里的账号密码"这种排障方式才成立。
 * 不引 dotenv：少一个依赖，也少一处可能把密码打进日志的地方。
 */
function loadEnvFile(): void {
  try {
    const here = path.dirname(fileURLToPath(import.meta.url))
    const file = path.resolve(here, '..', '.env')
    if (!fs.existsSync(file)) return
    for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
      const trimmed = line.trim()
      if (!trimmed || trimmed.startsWith('#')) continue
      const eq = trimmed.indexOf('=')
      if (eq <= 0) continue
      const key = trimmed.slice(0, eq).trim()
      const value = trimmed.slice(eq + 1).trim().replace(/^["']|["']$/g, '')
      if (!process.env[key]) process.env[key] = value
    }
  } catch {
    // .env 只是便利，读不到就按纯环境变量走
  }
}

loadEnvFile()

const NAME = 'erp-inventory-mcp'
const VERSION = '0.2.0'

const baseUrl = process.env.ERP_BASE_URL ?? 'http://127.0.0.1:8080/api/v1'
const token = process.env.ERP_TOKEN ?? ''
const username = process.env.ERP_USERNAME ?? ''
const password = process.env.ERP_PASSWORD ?? ''

const hasToken = Boolean(token.trim())
const hasPasswordLogin = Boolean(username.trim() && password.trim())

if (!hasToken && !hasPasswordLogin) {
  console.error(
    [
      '[erp-mcp] 缺少凭据，无法启动。二选一：',
      '  A) ERP_TOKEN=<登录得到的 JWT>',
      '  B) ERP_USERNAME=<账号>  ERP_PASSWORD=<密码>   ← 推荐，token 过期会自动续签',
      '获取 token：curl -X POST http://127.0.0.1:8080/api/v1/auth/login \\',
      '  -H "Content-Type: application/json" -d "{\\"username\\":\\"admin\\",\\"password\\":\\"***\\"}"',
    ].join('\n'),
  )
  process.exit(1)
}

const client = new ErpClient({ baseUrl, token, username, password })

/** 每次连接都新建 McpServer：无状态 HTTP 下多个 Agent 并发互不串台。 */
function createServer(): McpServer {
  const server = new McpServer({ name: NAME, version: VERSION })
  registerTools(server, client)
  return server
}

async function startStdio(): Promise<void> {
  const server = createServer()
  const transport = new StdioServerTransport()
  await server.connect(transport)
  console.error(`[erp-mcp] stdio 已连接；baseUrl=${baseUrl}；认证方式=${client.describeAuth()}`)
}

function readBody(req: http.IncomingMessage, limitBytes = 1_000_000): Promise<string> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = []
    let size = 0
    req.on('data', (chunk: Buffer) => {
      size += chunk.length
      if (size > limitBytes) {
        reject(new Error('请求体过大'))
        req.destroy()
        return
      }
      chunks.push(chunk)
    })
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')))
    req.on('error', reject)
  })
}

function applyCors(res: http.ServerResponse): void {
  // 只服务本机回环，放开来源是为了让浏览器里的调试页 / 状态面板也能直连。
  // 真要对外暴露，请在前面加一层网关并收紧这里。
  res.setHeader('Access-Control-Allow-Origin', '*')
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, DELETE, OPTIONS')
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Accept, Authorization, Mcp-Session-Id, MCP-Protocol-Version')
  res.setHeader('Access-Control-Expose-Headers', 'Mcp-Session-Id')
}

async function startHttp(): Promise<void> {
  const port = Number(process.env.ERP_MCP_HTTP_PORT ?? 8790)
  const bind = process.env.ERP_MCP_BIND ?? '127.0.0.1'

  const server = http.createServer(async (req, res) => {
    applyCors(res)
    const url = new URL(req.url ?? '/', `http://${req.headers.host ?? bind}`)
    const path = url.pathname.replace(/\/+$/, '') || '/'

    // 状态查询：网页工作台用它判断"MCP 有没有接上"
    if (path === '/health' && req.method === 'GET') {
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' })
      res.end(JSON.stringify({
        status: 'UP',
        name: NAME,
        version: VERSION,
        transport: 'http',
        baseUrl,
        auth: client.describeAuth(),
        tools: TOOL_NAMES.length,
        toolNames: TOOL_NAMES,
      }))
      return
    }

    if (path !== '/mcp') {
      res.writeHead(404, { 'Content-Type': 'application/json; charset=utf-8' })
      res.end(JSON.stringify({ error: 'not_found', hint: 'POST /mcp（JSON-RPC），GET /health' }))
      return
    }

    if (req.method === 'OPTIONS') {
      res.writeHead(204)
      res.end()
      return
    }

    // 无状态模式不提供独立 SSE 流：客户端应直接 POST 请求
    if (req.method !== 'POST') {
      res.writeHead(405, { 'Content-Type': 'application/json; charset=utf-8', Allow: 'POST, OPTIONS' })
      res.end(JSON.stringify({
        jsonrpc: '2.0',
        error: { code: -32000, message: 'Method Not Allowed：本服务使用无状态 Streamable HTTP，请 POST /mcp' },
        id: null,
      }))
      return
    }

    let parsed: unknown
    try {
      const raw = await readBody(req)
      parsed = raw ? JSON.parse(raw) : undefined
    } catch (error) {
      res.writeHead(400, { 'Content-Type': 'application/json; charset=utf-8' })
      res.end(JSON.stringify({
        jsonrpc: '2.0',
        error: { code: -32700, message: `Parse error：${(error as Error).message}` },
        id: null,
      }))
      return
    }

    const mcpServer = createServer()
    const transport = new StreamableHTTPServerTransport({
      sessionIdGenerator: undefined,
      enableJsonResponse: true,
    })
    res.on('close', () => {
      void transport.close()
      void mcpServer.close()
    })
    try {
      await mcpServer.connect(transport)
      await transport.handleRequest(req, res, parsed)
    } catch (error) {
      console.error('[erp-mcp] 处理请求失败：', (error as Error).message)
      if (!res.headersSent) {
        res.writeHead(500, { 'Content-Type': 'application/json; charset=utf-8' })
        res.end(JSON.stringify({
          jsonrpc: '2.0',
          error: { code: -32603, message: (error as Error).message },
          id: null,
        }))
      }
    }
  })

  await new Promise<void>((resolve, reject) => {
    server.once('error', reject)
    server.listen(port, bind, () => resolve())
  })

  console.error(`[erp-mcp] HTTP 已监听 http://${bind}:${port}/mcp（健康检查 /health）`)
  console.error(`[erp-mcp] baseUrl=${baseUrl}；认证方式=${client.describeAuth()}`)
}

const mode = (process.env.ERP_MCP_TRANSPORT ?? 'stdio').toLowerCase()
if (mode === 'http') {
  await startHttp()
} else if (mode === 'stdio') {
  await startStdio()
} else {
  console.error(`[erp-mcp] 未知的 ERP_MCP_TRANSPORT=${mode}，只支持 stdio / http`)
  process.exit(1)
}
