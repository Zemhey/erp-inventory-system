#!/usr/bin/env node
/**
 * 以 HTTP 传输启动 MCP Server 的小包装。
 *
 * 为什么不直接在 package.json 里写 `set X=1 && node ...`：
 * 那套写法在 cmd / PowerShell / bash 里各不相同，换个终端就失效。
 * 用 Node 设环境变量再动态 import，Windows / macOS / Linux 行为一致。
 *
 * 用法：npm run start:http
 *   可选 ERP_MCP_HTTP_PORT（默认 8790）、ERP_MCP_BIND（默认 127.0.0.1）
 */
process.env.ERP_MCP_TRANSPORT = 'http'
await import('../dist/index.js')
