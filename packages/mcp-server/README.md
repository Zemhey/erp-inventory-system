# ERP MCP Server

把进销存系统的核心能力暴露给任意支持 [Model Context Protocol](https://modelcontextprotocol.io/) 的 AI Agent（Claude Desktop、IDE Agent、自研客户端均可）。

> 对应需求：`FR-SYS-05`（MCP Server 暴露核心能力），任务书加分项「让 AI Agent 可以直接操作（MCP）」。
>
> **定位**：本项目**不把 MCP 做进后端进程**。它是独立的 stdio 适配器，通过 HTTP 调用既有的
> `/api/v1/*` 接口；权限、幂等、库存规则全部由后端判定，MCP 层不复制任何业务逻辑。

## 1. 工具清单（6 个）

| 工具 | 参数 | 后端接口 | 所需权限 |
|---|---|---|---|
| `query_inventory` | `skuId? warehouseId? batchNo? onlyAvailable?` | `GET /inventory` | `inv:read` |
| `list_documents` | `type: purchase\|sales\|return`、`status? partyId? originSalesOrderId? page? size?` | `GET /purchase-orders` / `/sales-orders` / `/return-orders` | 对应 `*:read` |
| `get_document` | `type`、`id` | `GET /{type}/{id}` | 对应 `*:read` |
| `inventory_summary` | `period`（yyyy-MM）、`warehouseId?` | `GET /reports/inventory-summary` | `rpt:read` |
| `query_master_data` | `type: product\|sku\|supplier\|customer\|warehouse`、`keyword? productId? color? skuSize? onlyEnabled? page? size?` | `GET /products` / `/skus` / `/suppliers` / `/customers` / `/warehouses` | `mdm:read` |
| `nl_command` | `command`（自然语言） | `POST /nl/command` | 意图对应权限 |

`nl_command` 让 Agent 也能使用 `FR-SYS-04` 的自然语言入口：查库存、查单据、进销存汇总、查主数据、
**创建进货/出货草稿**（只建草稿，不审核/入库/出货/作废）。写请求的 `X-Request-Id` 由本服务自动生成。

## 2. 构建与运行

```bash
cd packages/mcp-server
npm install          # 国内网络可加 --registry=https://registry.npmmirror.com
npm run build

export ERP_BASE_URL=http://127.0.0.1:8080/api/v1
export ERP_TOKEN=<登录得到的 JWT>
npm start
```

> stdio 模式下 **stdout 是 JSON-RPC 协议通道**，日志只写 stderr。

### 获取 token

```bash
curl -s -X POST http://127.0.0.1:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"Admin@12345"}'
# → {"code":"OK","data":{"token":"eyJ..."}}
```

## 3. 接入任意 MCP 客户端

### 3.1 Claude Desktop

编辑 `claude_desktop_config.json`（Windows：`%APPDATA%\Claude\claude_desktop_config.json`）：

```json
{
  "mcpServers": {
    "erp-inventory": {
      "command": "node",
      "args": ["D:/MajorCacheFolder/Company system/erp-m7-ai/packages/mcp-server/dist/index.js"],
      "env": {
        "ERP_BASE_URL": "http://127.0.0.1:8080/api/v1",
        "ERP_TOKEN": "<JWT>"
      }
    }
  }
}
```

重启 Claude Desktop 后，在对话里问「查一下 SKU 12 的库存」「本月进销存汇总」即可触发工具调用。

### 3.2 MCP Inspector（可选）

```bash
npx @modelcontextprotocol/inspector node dist/index.js
```

在 Inspector 里填入 `ERP_BASE_URL` / `ERP_TOKEN` 两个环境变量，即可逐个工具试调用。
（本仓库同时提供不依赖 Inspector 的最小客户端脚本，见下一节。）

### 3.3 最小客户端实证

```bash
ERP_BASE_URL=http://127.0.0.1:8083/api/v1 \
ERP_TOKEN=<JWT> \
SMOKE_SKU_ID=12 \
npm run smoke
```

脚本会：

1. 启动 `dist/index.js` 并用 SDK 的 `Client` 连接；
2. `listTools` 并校验 **6 个工具**全部存在；
3. 依次调用 `query_inventory`、`inventory_summary`、`nl_command`；
4. 任一调用返回 `isError` 或抛异常 → 退出码非 0。

## 4. 错误语义

后端错误原样透出为 MCP 工具错误（`isError: true`），JSON 形如：

```json
{ "error": { "status": 403, "code": "IAM_FORBIDDEN",
  "message": "自然语言指令被拒绝：需要权限 inv:read，当前角色 [FINANCE]" } }
```

Agent 可根据 `code` 决定下一步（例如换有权限的账号、修正参数），而不是只看到一句"失败"。

## 5. 安全说明

- Server 只持有调用者传入的 JWT，**不保存用户名/密码**，也不在日志里打印 token；
- 每个工具都受后端 RBAC 约束，MCP 不会成为越权旁路；
- 默认不暴露审核/入库/出货/作废等不可逆动作 —— 这些操作需要页面上的二次确认；
- 生产环境建议为 Agent 单独建一个最小权限账号（如只读角色 `VIEWER`），而不是直接用 `ADMIN`。

## 6. 已知限制

- 工具是 REST 的薄适配器，没有批量/事务语义；
- 按 id 操作，不负责"按名称解析 id"；可让 Agent 先调 `query_master_data` 拿到 id 再操作；
- `nl_command` 的真实自然语言能力取决于后端配置：`erp.nl.parser=stub` 只认固定文法，
  切到 `openai` 后才由 LLM 做意图解析（见 `.plan/M7-计划书.md` §2.2）。
