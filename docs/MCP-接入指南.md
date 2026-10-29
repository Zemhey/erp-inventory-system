# MCP 接入指南（让 AI Agent 直接操作进销存）

> 对应需求：`FR-SYS-05`。目标只有一个：**在豆包、WorkBuddy 这类 Agent 里，用一句人话说"查询 SKU 12 的库存"，就能真的查到。**

---

## 1. 它是什么（以及不是什么）

```
  豆包 / WorkBuddy / Claude Desktop / 自研控制台
                  │  MCP（JSON-RPC）
                  ▼
    packages/mcp-server  ← 薄适配器：只做 HTTP 转发，不做业务判断
                  │  HTTP + JWT
                  ▼
    后端 /api/v1/*  ← 权限、幂等、库存规则、状态机全在这里
```

三条边界，决定了它为什么可靠：

| 原则 | 含义 |
|---|---|
| **不进后端进程** | MCP 是独立的 Node 进程，后端不用为 AI 改造一行代码 |
| **不复制业务逻辑** | 库存够不够、单据能不能审核，一律由后端回答；MCP 只翻译 HTTP |
| **不成为越权旁路** | 每个工具都要过后端 RBAC。给 Agent 的最小权限账号，它就只能干那些事 |

也**不是**：不是"给 AI 开个后门"，不是"绕过审批直接改库存"。写操作只建**草稿**，审核 / 入库 / 出货 / 作废仍然必须回页面二次确认。

---

## 2. 工具清单（9 个）

| 工具 | 参数 | 后端接口 | 权限 |
|---|---|---|---|
| `system_status` | — | `GET /actuator/health` | 无需 |
| `dashboard_overview` | `includeCharts?` | `GET /dashboard/summary` | `dashboard:read` |
| `query_inventory` | `skuId? warehouseId? batchNo? onlyAvailable?` | `GET /inventory` | `inv:read` |
| `list_documents` | `type` `status?` `partyId?` `page?` `size?` | `GET /purchase-orders` 等 | 对应 `*:read` |
| `get_document` | `type` `id` | `GET /{type}/{id}` | 对应 `*:read` |
| `inventory_summary` | `period`（yyyy-MM）`warehouseId?` | `GET /reports/inventory-summary` | `rpt:read` |
| `query_master_data` | `type` `keyword?` … | `GET /products` 等 | `mdm:read` |
| `nl_command` | `command`（自然语言） | `POST /nl/command` | 意图对应权限 |
| `nl_plan` | `command`（自然语言） | `POST /nl/plan` | 意图对应权限 |

> `replenish_advice` 不是独立工具，它是 `nl_command` 的一个**意图**
> （说"补货建议"即可），别在工具列表里找它。

`nl_command` 与网页工作台底部的「自然语言操作台」走**同一个后端入口**，支持 9 个意图：
`help / query_inventory / list_documents / get_document / inventory_summary / replenish_advice / query_master_data / create_purchase_order / create_sales_order`。

其中 `replenish_advice` 是**目标级意图**：一句话给出"该补哪些 SKU、各补多少"，内部组合了可用量、在途采购、上下限与补货公式——用户不必再自己拆成三次查询。只读，不建单。

**写操作请先 `nl_plan` 再 `nl_command`，并且必须把回执带回去。** 从 V4-S4 起这不是建议，是服务端强制：

1. `nl_plan` 返回 `plan`（计划）、`review`（复核意见）、`confirmation`（确认回执），都不做任何修改；
2. Agent 把 `plan.summary` 与 `review.findings` 念给人听，**得到明确同意**；
3. 带 `confirmation.token` 调 `nl_command`（工具参数名 `planToken`），服务端校验通过才落库；
4. 不带回执直接调 `nl_command` 会返回 **409 `NL_CONFIRM_REQUIRED`**，响应里附带这一次的 `plan` 与 `review`
   —— 拿到就能直接展示给人，不用再请求一次。

回执绑定账号 / 意图 / 参数哈希与 5 分钟有效期，所以"预览 10 件、执行 20000 件"或借用别人的回执都会被拒。
只读意图（`requiresReview=false`）没有这道闸门——**读不审**，不必打扰人。

`review.verdict` 三档要读懂：`APPROVE` 是"复核方不反对"，**不是批准**；`NEED_HUMAN` 是把判断交回给人；
`REJECT` 时根本不会签发回执，Agent 应当去改指令而不是重试。

**每次 `nl_plan` 都会在服务端留一份档案**（`nl_execution`），Agent 请求会带 `X-Nl-Source: MCP`，
所以在「自然语言 → 操作审计」里能分清"这条是网页点的还是 Agent 发的"。
审计记录的是：谁、说了什么、当时算出的影响、复核怎么判、最后落成哪张单（或为什么没落成）。
**Agent 做过的每一件事都查得到**——这是"AI 代操作"能不能进生产的前提；反过来说，
如果某条指令没出现在审计里，那它一定没被执行。

> `system_status` 是给 Agent 用的自检：先确认"系统通不通、我用什么身份"，再谈业务，避免它拿着 403 瞎猜。

---

## 3. 凭据：优先用账号密码，而不是手抄 JWT

MCP Server 支持两种，按优先级：

```ini
# 推荐：首次调用时自动登录，token 过期（401）自动续签
ERP_USERNAME=admin
ERP_PASSWORD=你的密码

# 备选：临时排障用，JWT 一过期就要手动换
ERP_TOKEN=eyJhbGciOi...
```

两者都没给时，进程**直接退出并说明原因**，不会静默降级成匿名调用。

本地已备好 `packages/mcp-server/.env`（已被 `.gitignore` 忽略），填一次即可，`npm start` / `npm run smoke` / Agent 都读它。

---

## 4. 接入 WorkBuddy（本机，stdio）

配置文件：`C:\Users\<你>\.workbuddy\mcp.json`（**注意不是** `~/.workbuddy/.mcp.json`）。

```json
{
  "mcpServers": {
    "erp-inventory": {
      "type": "stdio",
      "command": "node",
      "args": ["D:\\MajorCacheFolder\\Company system\\erp-inventory-system\\packages\\mcp-server\\dist\\index.js"],
      "env": {
        "ERP_BASE_URL": "http://127.0.0.1:8080/api/v1",
        "ERP_USERNAME": "admin",
        "ERP_PASSWORD": "你的密码"
      }
    }
  }
}
```

步骤：

1. 确认 `packages/mcp-server/dist/index.js` 存在（没有就 `cd packages/mcp-server && npm install && npm run build`）；
2. 把上面的 `erp-inventory` 合并进 `mcpServers`（**别覆盖**已有条目）；
3. 重启 WorkBuddy；
4. 在「连接器」管理页右上角找到这条自定义 MCP，点 **信任 / Trust** 才会生效。

然后在对话里直接问：

> 帮我查一下 SKU 12 在仓库 3 的库存，然后告诉我这个月进销存汇总。

Agent 会自己先调 `system_status`，再调 `query_inventory` / `inventory_summary`。

---

## 5. 接入豆包 / 云端 Agent（HTTP，只需一个 URL）

豆包这类客户端不方便"本地拉子进程"，所以 MCP Server 额外提供**无状态 Streamable HTTP** 传输：

```bash
cd packages/mcp-server
npm run start:http        # 默认 http://127.0.0.1:8790/mcp
```

能填 URL 的地方就填：

| 项 | 值 |
|---|---|
| MCP 地址 | `http://127.0.0.1:8790/mcp` |
| 传输方式 | Streamable HTTP（无状态） |
| 健康检查 | `GET http://127.0.0.1:8790/health` |

> **豆包要在云端访问，就得让这个地址可达。** 本服务默认只绑 `127.0.0.1`（安全默认），
> 云端调用需要内网穿透（如 frp / cpolar / ngrok）或部署到内网服务器，并配 `ERP_MCP_BIND`。
> 不要把 8790 直接暴露到公网 —— 它等于把整台进销存的后门挂在外面。

---

## 6. 验证（三条命令，眼见为实）

```bash
cd packages/mcp-server

# ① stdio：起子进程、列工具、真调 4 个
npm run smoke

# ② HTTP：另开一个终端 npm run start:http，再跑这条
npm run smoke:http

# ③ 只看健康
curl http://127.0.0.1:8790/health
```

三条都通过，才算"接上了"。`smoke` 会断言 8 个工具齐全，任一调用 `isError` 就以非 0 退出。

---

## 7. 网页上看得到的状态

工作台顶部的「系统运行状态」面板会探测 MCP：

- 面板走 Vite 代理 `/mcp-bridge/health` → `127.0.0.1:8790/health`；
- **没启动 HTTP 传输时显示「按需接入」**，这是正常的 —— MCP 不常驻，Agent 用的时候才起；
- 启动 `npm run start:http` 后刷新，会显示「8 个工具 · 可用于豆包 / WorkBuddy」。

这也是"自然语言操作台"与 Agent 的连通性证据：同一套指令，两个入口。

---

## 8. 常见问题

| 现象 | 原因 / 处理 |
|---|---|
| `ERP_NO_CREDENTIALS` | 没配 token 也没配账号密码，见第 3 节 |
| 工具返回 403 `IAM_FORBIDDEN` | 账号缺权限。别用 ADMIN 掩盖问题，给 Agent 建最小权限账号 |
| 工具返回 401 后仍失败 | 密码错了。MCP 只自动重试一次，避免把错误密码打成死循环 |
| `ERP_UNREACHABLE` | 后端没起。先跑根目录 `启动系统.cmd` |
| `npm run smoke` 报找不到凭据 | `.env` 没建，或 env 变量名写错（`ERP_USERNAME` / `ERP_PASSWORD`） |
| 豆包连不上 | 8790 只绑了回环，云端不可达 —— 见第 5 节 |

---

## 9. 安全清单

- Server 只持有你给的凭据，**不写日志、不落盘、不打印 token**；
- 默认只监听 `127.0.0.1`；
- 写操作只建草稿，审核 / 出入库 / 作废仍需人工在页面确认；
- 生产环境建议给 Agent 单独开只读账号（`VIEWER`），不要复用 ADMIN；
- `.env` 与 `~/.workbuddy/mcp.json` 都在本机，别提交到仓库。
