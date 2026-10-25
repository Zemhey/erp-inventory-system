# 测试用例索引（自动生成，请勿手写）

> 来源：扫源码中的 `@CaseId` 注解 + 追踪矩阵（docs/00-索引与追踪矩阵.md）

| 用例ID | 用例名称 | 所在文件 |
|---|---|---|
| `TC-API-095` | ★ 文档债豁免集保持空集；V2-3 的 28 个操作确实写在契约里且结构完整（契约清偿 2 的 DoD） | `backend/server/src/test/java/com/erp/api/ApiContractMappingIT.java` |
| `TC-API-096` | ★ V2-3 写操作的 X-Request-Id：契约声明 ⇔ 实现消费，两个方向都必须对上 | `backend/server/src/test/java/com/erp/api/ApiContractMappingIT.java` |
| `TC-API-040` | POST /cost/recalculate：返回成本层数与守恒标志 | `backend/server/src/test/java/com/erp/api/CostApiIT.java` |
| `TC-API-041` | POST /cost/monthly：月结字段完整，重复执行 duplicate=true | `backend/server/src/test/java/com/erp/api/CostApiIT.java` |
| `TC-API-042` | GET /cost/monthly：按账期查询月结行 | `backend/server/src/test/java/com/erp/api/CostApiIT.java` |
| `TC-API-043` | GET /reports/profit：按商品聚合毛利，金额与毛利率字段符合契约申请 | `backend/server/src/test/java/com/erp/api/CostApiIT.java` |
| `TC-API-044` | 权限：FINANCE 有 cst:read，PURCHASER 无 cst:read 被 403 | `backend/server/src/test/java/com/erp/api/CostApiIT.java` |
| `TC-API-045` | 参数校验：非法账期与非法 groupBy 都是 400 SYS_VALIDATION_ERROR | `backend/server/src/test/java/com/erp/api/CostApiIT.java` |
| `TC-API-046` | 写接口缺少 X-Request-Id：400 而不是 500 | `backend/server/src/test/java/com/erp/api/CostApiIT.java` |
| `TC-API-012` | ★ 库存流水：分页参数生效、按来源单据反查、响应字段符合契约 | `backend/server/src/test/java/com/erp/api/InventoryApiIT.java` |
| `TC-API-013` | 库存流水：未登录 401（权限守卫生效，不是 500） | `backend/server/src/test/java/com/erp/api/InventoryApiIT.java` |
| `TC-API-019` | POST /inventory/transfers：建单即发运，响应含 transferNo / inTransitQty / lines | `backend/server/src/test/java/com/erp/api/InventoryOpsApiIT.java` |
| `TC-API-020` | 写操作缺 X-Request-Id：400 而不是 500；只读角色写接口 403 | `backend/server/src/test/java/com/erp/api/InventoryOpsApiIT.java` |
| `TC-API-021` | POST /inventory/transfers/{id}/receive：状态变为 COMPLETED 且守恒差为 0 | `backend/server/src/test/java/com/erp/api/InventoryOpsApiIT.java` |
| `TC-API-022` | GET /inventory/transfers：状态筛选与分页字段符合契约 | `backend/server/src/test/java/com/erp/api/InventoryOpsApiIT.java` |
| `TC-API-023` | 盘点：建单返回 DRAFT 与账面快照，过账后 POSTED 且差异正确 | `backend/server/src/test/java/com/erp/api/InventoryOpsApiIT.java` |
| `TC-API-024` | 盘点全部差异为 0：过账返回 409 INV_STOCKTAKE_NO_DIFFERENCE | `backend/server/src/test/java/com/erp/api/InventoryOpsApiIT.java` |
| `TC-API-025` | 库存调整：原因空白返回 400（Bean Validation），不是 500 | `backend/server/src/test/java/com/erp/api/InventoryOpsApiIT.java` |
| `TC-API-026` | 冻结/解冻：冻结后可用量下降，解冻后恢复（GET /inventory 可见 frozenQty） | `backend/server/src/test/java/com/erp/api/InventoryOpsApiIT.java` |
| `TC-API-027` | GET /inventory/balance-dimensions：五维行 + 总量与状态合计 | `backend/server/src/test/java/com/erp/api/InventoryOpsApiIT.java` |
| `TC-API-028` | GET /inventory/alerts：安全库存预警字段符合契约 | `backend/server/src/test/java/com/erp/api/InventoryOpsApiIT.java` |
| `TC-API-029` | GET /inventory/replenishment：给出建议；存在未到货进货单后建议消失 | `backend/server/src/test/java/com/erp/api/InventoryOpsApiIT.java` |
| `TC-API-001` | 商品：建 → 查 → 改 → 停用 全链路，响应包络符合契约 | `backend/server/src/test/java/com/erp/api/MasterDataApiIT.java` |
| `TC-API-002` | 商品：编码重复 → 409 MDM_CODE_DUPLICATED；不存在 → 404 SYS_NOT_FOUND | `backend/server/src/test/java/com/erp/api/MasterDataApiIT.java` |
| `TC-API-003` | ★ 参数校验：编码过短 / 名称为空 → 400 SYS_VALIDATION_ERROR（不是 500） | `backend/server/src/test/java/com/erp/api/MasterDataApiIT.java` |
| `TC-API-004` | 商品列表分页：items/total/page/size 齐全，size 越界被钳制到 200 | `backend/server/src/test/java/com/erp/api/MasterDataApiIT.java` |
| `TC-API-005` | SKU：建单条 → 矩阵生成 → 重复矩阵跳过 → 自冲突 409 | `backend/server/src/test/java/com/erp/api/MasterDataApiIT.java` |
| `TC-API-006` | SKU 列表按商品过滤；不存在的商品查 SKU → 404 | `backend/server/src/test/java/com/erp/api/MasterDataApiIT.java` |
| `TC-API-011` | ★ SKU 分页：size 控制条数，skuSize 过滤尺码，两参数互不吞并 | `backend/server/src/test/java/com/erp/api/MasterDataApiIT.java` |
| `TC-API-007` | 供应商：建 → 重复 409 → 改 → 停用 | `backend/server/src/test/java/com/erp/api/MasterDataApiIT.java` |
| `TC-API-008` | ★ 客户折扣率：越界 400（契约层校验），合法 9 折成功 | `backend/server/src/test/java/com/erp/api/MasterDataApiIT.java` |
| `TC-API-009` | 仓库：列表返回数组（非分页）；onlyEnabled 过滤生效 | `backend/server/src/test/java/com/erp/api/MasterDataApiIT.java` |
| `TC-API-010` | 缺少 X-Request-Id 的写接口 → 400（契约要求写操作必须带幂等键） | `backend/server/src/test/java/com/erp/api/MasterDataApiIT.java` |
| `TC-API-085` | POST /warehouses：同键重放返回同一 id，表里只有一行 | `backend/server/src/test/java/com/erp/api/MdmIdempotencyIT.java` |
| `TC-API-086` | POST /suppliers：同键重放返回同一 id，表里只有一行 | `backend/server/src/test/java/com/erp/api/MdmIdempotencyIT.java` |
| `TC-API-087` | POST /customers：同键重放返回同一 id，表里只有一行 | `backend/server/src/test/java/com/erp/api/MdmIdempotencyIT.java` |
| `TC-API-088` | POST /products：同键重放返回同一 id，表里只有一行 | `backend/server/src/test/java/com/erp/api/MdmIdempotencyIT.java` |
| `TC-API-089` | POST /skus：同键重放返回同一 id，且不撞变体唯一约束 | `backend/server/src/test/java/com/erp/api/MdmIdempotencyIT.java` |
| `TC-API-090` | POST /warehouses/{id}/disable：同键重放结果相同、零写入、零新增审计 | `backend/server/src/test/java/com/erp/api/MdmIdempotencyIT.java` |
| `TC-API-091` | POST /suppliers/{id}/disable：同键重放结果相同、零写入 | `backend/server/src/test/java/com/erp/api/MdmIdempotencyIT.java` |
| `TC-API-092` | POST /customers/{id}/disable：同键重放结果相同、零写入 | `backend/server/src/test/java/com/erp/api/MdmIdempotencyIT.java` |
| `TC-API-093` | 缺 X-Request-Id 或幂等键为空白 → 400 SYS_VALIDATION_ERROR，且失败也要留痕 | `backend/server/src/test/java/com/erp/api/MdmIdempotencyIT.java` |
| `TC-API-094` | ★ 重放不重复写 audit_logs：5 个创建端点各 1 条 CREATE，重放后仍是 1 条 | `backend/server/src/test/java/com/erp/api/MdmIdempotencyIT.java` |
| `TC-MDM-027` | ★ 重放返回资源的当前状态，不是当时的响应快照（选方案 b 的理由） | `backend/server/src/test/java/com/erp/api/MdmIdempotencyIT.java` |
| `TC-MDM-028` | ★ 同键第二次 INSERT 被数据库唯一索引拦住（绕过应用层，证明守卫不活在进程里） | `backend/server/src/test/java/com/erp/api/MdmIdempotencyIT.java` |
| `TC-MDM-029` | V15 的 8 个局部唯一索引真实存在，且都带 IS NOT NULL 谓词 | `backend/server/src/test/java/com/erp/api/MdmIdempotencyIT.java` |
| `TC-MDM-030` | 幂等键不稀释编码唯一：不同键 + 同编码，仍然 409 MDM_CODE_DUPLICATED | `backend/server/src/test/java/com/erp/api/MdmIdempotencyIT.java` |
| `TC-MDM-031` | 同键 + 不同编码：返回首次那一行（重放语义优先于业务字段差异） | `backend/server/src/test/java/com/erp/api/MdmIdempotencyIT.java` |
| `TC-MDM-032` | 不带键的内部写入彼此永不冲突（局部索引不约束 NULL） | `backend/server/src/test/java/com/erp/api/MdmIdempotencyIT.java` |
| `TC-MDM-033` | 停用后被重新启用、再用原键停用：这是真实变更，必须真的生效并留痕 | `backend/server/src/test/java/com/erp/api/MdmIdempotencyIT.java` |
| `TC-MDM-034` | 已是停用态时用新键再停一次：状态已达成，不写第二遍 | `backend/server/src/test/java/com/erp/api/MdmIdempotencyIT.java` |
| `TC-MDM-035` | 同一个停用键打到两个不同资源 → 409 SYS_DUPLICATE_REQUEST（返回「已停用」是错的） | `backend/server/src/test/java/com/erp/api/MdmIdempotencyIT.java` |
| `TC-MDM-036` | ★ MDM 写入永不占用单号序列；重放同样不进位（含正向对照） | `backend/server/src/test/java/com/erp/api/MdmIdempotencyIT.java` |
| `TC-MDM-037` | 重放不改任何一张 MDM 表的行数（五个创建端点各重放一次） | `backend/server/src/test/java/com/erp/api/MdmIdempotencyIT.java` |
| `TC-MDM-038` | 幂等键按表命名空间：同一个键打到不同资源类型互不干扰 | `backend/server/src/test/java/com/erp/api/MdmIdempotencyIT.java` |
| `TC-MDM-039` | 重放响应体与首次逐字段一致（创建 + 停用两类都验） | `backend/server/src/test/java/com/erp/api/MdmIdempotencyIT.java` |
| `TC-MDM-040` | ★ 五张创建表的数据库守卫逐表生效：同键第二次 INSERT 一律被自己的索引拦住 | `backend/server/src/test/java/com/erp/api/MdmIdempotencyIT.java` |
| `TC-MDM-041` | 停用键的冲突判定对 suppliers / customers 同样生效（不只是仓库） | `backend/server/src/test/java/com/erp/api/MdmIdempotencyIT.java` |
| `TC-MDM-042` | ★ 首次调用恰好 1 行审计、重放 0 行新增（兜底过滤器不得补写 FAIL） | `backend/server/src/test/java/com/erp/api/MdmIdempotencyIT.java` |
| `TC-NL-006` | ★ 查询库存：三量正确；未登录 401 —— NL 不会成为绕过认证的旁路 | `backend/server/src/test/java/com/erp/api/NlCommandApiIT.java` |
| `TC-NL-007` | ★ 单据列表/详情：中文状态与分页生效，详情 id 与列表行一致 | `backend/server/src/test/java/com/erp/api/NlCommandApiIT.java` |
| `TC-NL-008` | ★ 写意图：建进货草稿成功；同 X-Request-Id 重放只有一张单；缺键 400 | `backend/server/src/test/java/com/erp/api/NlCommandApiIT.java` |
| `TC-NL-009` | ★ 进销存汇总：与 REST 报表逐字段一致；缺省账期=当前月 | `backend/server/src/test/java/com/erp/api/NlCommandApiIT.java` |
| `TC-NL-010` | ★ 意图级权限：只有 rpt:read 的账号能汇总，读库存/主数据与建单全部 403 | `backend/server/src/test/java/com/erp/api/NlCommandApiIT.java` |
| `TC-NL-011` | ★ 失败语义：无法识别 422、参数非法 400、空指令 400、业务 404 原样透出 | `backend/server/src/test/java/com/erp/api/NlCommandApiIT.java` |
| `TC-API-018` | ★ 三个报表接口全部可达且包络符合契约；默认分组是 supplier/customer；报表零写入 | `backend/server/src/test/java/com/erp/api/ReportApiIT.java` |
| `TC-RPT-009` | 非法参数一律 400：groupBy 取值非法 / 跨报表维度 / from>to / period 格式 / 缺参数 | `backend/server/src/test/java/com/erp/api/ReportApiIT.java` |
| `TC-API-135` | ★ 四个新报表可达：包络、字段名与数值符合契约 | `backend/server/src/test/java/com/erp/api/ReportV28ApiIT.java` |
| `TC-API-136` | 参数非法一律 400：dimension / deadDays / limit | `backend/server/src/test/java/com/erp/api/ReportV28ApiIT.java` |
| `TC-API-137` | 账龄参数：type 非法/缺失、asOf 缺失均 400；大小写不敏感 | `backend/server/src/test/java/com/erp/api/ReportV28ApiIT.java` |
| `TC-API-138` | from > to / 日期格式错误 / 缺必填参数均 400，不落到 500 | `backend/server/src/test/java/com/erp/api/ReportV28ApiIT.java` |
| `TC-API-139` | 默认值：dimension=day、deadDays=30、limit=20；month 粒度返回月行 | `backend/server/src/test/java/com/erp/api/ReportV28ApiIT.java` |
| `TC-API-140` | 七个导出端点：200 + text/csv + attachment 文件名 + UTF-8 BOM | `backend/server/src/test/java/com/erp/api/ReportV28ApiIT.java` |
| `TC-API-141` | ★ 同一 HTTP 参数下，经营报表 JSON 与 CSV 逐格一致（31 行 × 9 列） | `backend/server/src/test/java/com/erp/api/ReportV28ApiIT.java` |
| `TC-API-142` | 鉴权：未登录 401；有登录但无 rpt:read 的角色 403 | `backend/server/src/test/java/com/erp/api/ReportV28ApiIT.java` |
| `TC-API-143` | ★ 全部新报表 + 导出跑完后，单据/流水/余额/应收应付计数零变化 | `backend/server/src/test/java/com/erp/api/ReportV28ApiIT.java` |
| `TC-API-144` | 空区间/无数据：四个报表 200，不 500；经营报表按分桶返回零行 | `backend/server/src/test/java/com/erp/api/ReportV28ApiIT.java` |
| `TC-API-014` | ★ 退货 HTTP 端到端：建单→提交→审核→入库，响应字段符合契约；出货明细露出 returnedQty | `backend/server/src/test/java/com/erp/api/ReturnOrderApiIT.java` |
| `TC-API-015` | 退货接口守卫：缺 X-Request-Id → 400（不是 500）；VIEWER 可查不可建；未登录 401 | `backend/server/src/test/java/com/erp/api/ReturnOrderApiIT.java` |
| `TC-RMA-013` | ★ 只有 rma:read 的临时账号也能拿到非空可退列表；同一账号读 /sales-orders 仍然 403 | `backend/server/src/test/java/com/erp/api/ReturnableOrderApiIT.java` |
| `TC-RMA-014` | 已全部退完的出货单不出现在可退列表；未出货的 DRAFT/SUBMITTED/APPROVED 也不出现 | `backend/server/src/test/java/com/erp/api/ReturnableOrderApiIT.java` |
| `TC-RMA-015` | ★ returnableQty = shippedQty − returnedQty，且与退货建单预检同口径（超 1 件即 409） | `backend/server/src/test/java/com/erp/api/ReturnableOrderApiIT.java` |
| `TC-RMA-016` | 可退列表是只读接口：GET 不要求 X-Request-Id，也不得留下任何写痕迹 | `backend/server/src/test/java/com/erp/api/ReturnableOrderApiIT.java` |
| `TC-RMA-017` | ★ 分页口径：total 是单据张数、翻页取得后面的单，size 越界只钳制不当全量 | `backend/server/src/test/java/com/erp/api/ReturnableOrderApiIT.java` |
| `TC-SAL-024` | ★ 出货 void：缺幂等键 400；红冲 200 + 库存回补；同键重放 200；新键 409 | `backend/server/src/test/java/com/erp/api/ReversalApiIT.java` |
| `TC-PUR-023` | ★ 进货 void：缺幂等键 400；红冲 200 + 库存回退；同键重放 200；新键 409 | `backend/server/src/test/java/com/erp/api/ReversalApiIT.java` |
| `TC-API-070` | 报价 HTTP：建单 201 → 提交/审核 → 转化 200，报价 COMPLETED 且订单已预占 | `backend/server/src/test/java/com/erp/api/SalesClosedLoopApiIT.java` |
| `TC-API-071` | 报价转化信用超额：409 SAL_CREDIT_LIMIT_EXCEEDED，且无预占 | `backend/server/src/test/java/com/erp/api/SalesClosedLoopApiIT.java` |
| `TC-API-072` | 自动选仓 HTTP：201，allocations 跨两仓且 Σ qty == 行数量 | `backend/server/src/test/java/com/erp/api/SalesClosedLoopApiIT.java` |
| `TC-API-073` | 套装 HTTP：建套装 201，可用量 = min(组件可用÷配比) | `backend/server/src/test/java/com/erp/api/SalesClosedLoopApiIT.java` |
| `TC-API-074` | 发货计划 HTTP：200 + 重放同键返回同一计划，不重复建发货单 | `backend/server/src/test/java/com/erp/api/SalesClosedLoopApiIT.java` |
| `TC-API-075` | 扫码 HTTP：错条码 400；正确条码累计并最终 PICKED | `backend/server/src/test/java/com/erp/api/SalesClosedLoopApiIT.java` |
| `TC-API-076` | 发货确认 HTTP：幂等重放 200；新键 409；库存只扣一次 | `backend/server/src/test/java/com/erp/api/SalesClosedLoopApiIT.java` |
| `TC-API-077` | 收款核销不守恒 HTTP：409 SAL_RECEIPT_UNBALANCED，不生成收款单 | `backend/server/src/test/java/com/erp/api/SalesClosedLoopApiIT.java` |
| `TC-API-078` | 核销超额 HTTP：409 SAL_RECEIPT_OVER_ALLOCATED，余额不变 | `backend/server/src/test/java/com/erp/api/SalesClosedLoopApiIT.java` |
| `TC-API-079` | 应收查询 HTTP：summary 与列表返回余额字段 | `backend/server/src/test/java/com/erp/api/SalesClosedLoopApiIT.java` |
| `TC-API-080` | 信用 HTTP：GET 默认 / PUT 调整生效 / 负数 400 | `backend/server/src/test/java/com/erp/api/SalesClosedLoopApiIT.java` |
| `TC-API-081` | 未登录访问报价列表：401 IAM_UNAUTHENTICATED | `backend/server/src/test/java/com/erp/api/SalesClosedLoopApiIT.java` |
| `TC-API-082` | VIEWER 无 sal:quote：建报价 403 IAM_FORBIDDEN | `backend/server/src/test/java/com/erp/api/SalesClosedLoopApiIT.java` |
| `TC-API-083` | 写接口缺 X-Request-Id：400 SYS_VALIDATION_ERROR | `backend/server/src/test/java/com/erp/api/SalesClosedLoopApiIT.java` |
| `TC-API-084` | 主数据扩展 HTTP：SKU 季节筛选、仓库 priority 排序 | `backend/server/src/test/java/com/erp/api/SalesClosedLoopApiIT.java` |
| `TC-API-016` | ★ 结算 HTTP 端到端：日清 items/totals → 月结 CLOSED → 对账 balanced，字段符合契约 | `backend/server/src/test/java/com/erp/api/SettlementApiIT.java` |
| `TC-API-017` | ★★ 结算守卫：锁账后开单 409；FINANCE 可结算不可开单；缺幂等键/非法账期是 400 不是 500 | `backend/server/src/test/java/com/erp/api/SettlementApiIT.java` |
| `TC-SET-015` | ★ 只有 set:read 的临时账号能拿到非空账期列表，且月结结果刷新后逐字段还在；同一账号 POST 日清仍 403 | `backend/server/src/test/java/com/erp/api/SettlementPeriodApiIT.java` |
| `TC-SET-016` | ★ OPEN 账期的 closedAt/closedBy 为 null；CLOSED 行如实带出 closedBy 与期间汇总，字段名与契约一致 | `backend/server/src/test/java/com/erp/api/SettlementPeriodApiIT.java` |
| `TC-SET-017` | ★ status 筛选生效：CLOSED 只给已锁账期、OPEN 只给未锁，非法取值是 400 而不是 500 | `backend/server/src/test/java/com/erp/api/SettlementPeriodApiIT.java` |
| `TC-SET-018` | ★ 分页口径：total 是账期行数、按 period 倒序翻页，size 越界只钳制；GET 无副作用也不要求幂等键 | `backend/server/src/test/java/com/erp/api/SettlementPeriodApiIT.java` |
| `TC-APS-012` | ★ 金额阈值：小额单据只实例化 1 个节点，超阈值多出财务节点（FR-IAM-07 的核心） | `backend/server/src/test/java/com/erp/aps/ApprovalIT.java` |
| `TC-APS-013` | ★ 大额单据：主管通过后流转到财务；财务通过才 APPROVED | `backend/server/src/test/java/com/erp/aps/ApprovalIT.java` |
| `TC-APS-014` | ★ 节点权限逐节点校验：只有 pur:approve 的主管过不了财务节点（APS_NOT_APPROVER） | `backend/server/src/test/java/com/erp/aps/ApprovalIT.java` |
| `TC-APS-015` | ★ 无审批权的采购员不能同意：有 aps:submit 不等于有节点审批资格 | `backend/server/src/test/java/com/erp/aps/ApprovalIT.java` |
| `TC-APS-016` | ★ 会签（ALL_SIGN）：凑够 3 个不同的人才通过，同一人重复点击只算一票 | `backend/server/src/test/java/com/erp/aps/ApprovalIT.java` |
| `TC-APS-017` | ★ 或签（ANY_SIGN）：任意一人同意即通过，不必等其他人 | `backend/server/src/test/java/com/erp/aps/ApprovalIT.java` |
| `TC-APS-018` | ★ 驳回：节点置 REJECTED、剩余节点统一 SKIPPED、实例终态不可再同意 | `backend/server/src/test/java/com/erp/aps/ApprovalIT.java` |
| `TC-APS-019` | ★ 撤回：只有发起人本人能撤回；别人（即使有审批权）不能替他撤回 | `backend/server/src/test/java/com/erp/aps/ApprovalIT.java` |
| `TC-APS-020` | ★ 转办：PENDING -> TRANSFERRED，接办人接受后回到 PENDING，前一位的票被清零 | `backend/server/src/test/java/com/erp/aps/ApprovalIT.java` |
| `TC-APS-021` | ★ 提交审批幂等：同一 requestId 重放返回首次实例，不产生第二个 | `backend/server/src/test/java/com/erp/aps/ApprovalIT.java` |
| `TC-APS-022` | ★ 一张单据只能有一个未结审批：换一个 requestId 再提交被拒（APS_INSTANCE_ALREADY_OPEN） | `backend/server/src/test/java/com/erp/aps/ApprovalIT.java` |
| `TC-APS-023` | 没有启用流程时提交审批 → APS_FLOW_NOT_FOUND（与「无流程则免审批」是两件事） | `backend/server/src/test/java/com/erp/aps/ApprovalIT.java` |
| `TC-APS-024` | ★ 配了流程后：未提交审批的单据不能审核（APS_APPROVAL_REQUIRED） | `backend/server/src/test/java/com/erp/aps/ApprovalIT.java` |
| `TC-APS-025` | ★ 审批进行中时单据不能审核（APS_APPROVAL_PENDING）；通过后放行并由单据侧自己推进状态 | `backend/server/src/test/java/com/erp/aps/ApprovalIT.java` |
| `TC-APS-026` | ★ 没有启用流程时单据审核照旧（存量行为零改动） | `backend/server/src/test/java/com/erp/aps/ApprovalIT.java` |
| `TC-APS-027` | ★ 单据作废时连带取消未结审批（避免「死人待办」），且重复取消幂等 | `backend/server/src/test/java/com/erp/aps/ApprovalIT.java` |
| `TC-APS-028` | ★ 审批不改单据表：审批走完后单据状态纹丝不动，仍由单据侧自己推进 | `backend/server/src/test/java/com/erp/aps/ApprovalIT.java` |
| `TC-APS-029` | 审批列表与详情接口：按状态过滤，未登录 401 | `backend/server/src/test/java/com/erp/aps/ApprovalIT.java` |
| `TC-APS-030` | ★ 提交接口缺 X-Request-Id → 400；参数非法 → 400（客户端问题不装成 500） | `backend/server/src/test/java/com/erp/aps/ApprovalIT.java` |
| `TC-APS-001` | ★ 穷举 7×7=49 种审批状态组合：合法转移必须通过，非法转移必须全部被拒绝 | `backend/server/src/test/java/com/erp/aps/domain/ApprovalStatusMachineTest.java` |
| `TC-APS-002` | ★ 参数化穷举 49 条：非法转移抛 APS_STATUS_ILLEGAL 且消息含当前态、目标态与实例标识 | `backend/server/src/test/java/com/erp/aps/domain/ApprovalStatusMachineTest.java` |
| `TC-APS-003` | 终态不可再转移：APPROVED / REJECTED / WITHDRAWN / CANCELLED 都没有出路 | `backend/server/src/test/java/com/erp/aps/domain/ApprovalStatusMachineTest.java` |
| `TC-APS-004` | 非终态只有 DRAFT/PENDING/TRANSFERRED；isOpen 与 isTerminal 严格互补 | `backend/server/src/test/java/com/erp/aps/domain/ApprovalStatusMachineTest.java` |
| `TC-APS-005` | ★ 在途状态只有 PENDING / TRANSFERRED：只有它们占用「一张单据一个未结实例」的槽位 | `backend/server/src/test/java/com/erp/aps/domain/ApprovalStatusMachineTest.java` |
| `TC-APS-006` | 只有 APPROVED 让单据放行：REJECTED/WITHDRAWN/CANCELLED 都不放行 | `backend/server/src/test/java/com/erp/aps/domain/ApprovalStatusMachineTest.java` |
| `TC-APS-007` | ★ TRANSFERRED 不是终态：转办后接办人仍可同意/驳回（转办≠通过） | `backend/server/src/test/java/com/erp/aps/domain/ApprovalStatusMachineTest.java` |
| `TC-APS-008` | ★ REJECTED 是终态、不回 DRAFT：驳回后重提会生成新实例，旧结论不可推翻 | `backend/server/src/test/java/com/erp/aps/domain/ApprovalStatusMachineTest.java` |
| `TC-APS-009` | 自环一律非法：审批状态没有「原地不动」的业务语义（与单据 PARTIAL->PARTIAL 不同） | `backend/server/src/test/java/com/erp/aps/domain/ApprovalStatusMachineTest.java` |
| `TC-APS-010` | 目标状态为 null 时拒绝，且消息点明当前状态 | `backend/server/src/test/java/com/erp/aps/domain/ApprovalStatusMachineTest.java` |
| `TC-APS-011` | ★ 两套状态机互不干扰：审批的 7 个状态名与单据的 7 个状态名不是同一组 | `backend/server/src/test/java/com/erp/aps/domain/ApprovalStatusMachineTest.java` |
| `TC-ARCH-001` | ★ 规则自检：禁止系统时间规则必须能拦住违规代码 | `backend/server/src/test/java/com/erp/arch/ArchitectureTest.java` |
| `TC-ARCH-002` | ★ 规则自检：禁止裸异常规则必须能拦住违规代码 | `backend/server/src/test/java/com/erp/arch/ArchitectureTest.java` |
| `TC-ARCH-003` | ★ 规则自检：状态机规则必须拦住不调 requireTransition 的方法 | `backend/server/src/test/java/com/erp/arch/ArchitectureTest.java` |
| `TC-ARCH-004` | ★ 规则自检：单据模块不得依赖 set 的规则必须拦住直接 import 的写法 | `backend/server/src/test/java/com/erp/arch/ArchitectureTest.java` |
| `TC-ARCH-005` | ★ 规则自检：报表只读规则必须拦住跨模块应用服务依赖 | `backend/server/src/test/java/com/erp/arch/ArchitectureTest.java` |
| `TC-ARCH-006` | ★ 规则自检：退货模块不得直连 sal.repository 的规则必须拦住违规 import | `backend/server/src/test/java/com/erp/arch/ArchitectureTest.java` |
| `TC-ARCH-007` | ★ 规则自检：NL 入口不得直连仓储的规则必须拦住违规 import | `backend/server/src/test/java/com/erp/arch/ArchitectureTest.java` |
| `TC-ARCH-008` | ★ 规则自检：成本模块禁止 BigDecimal 的规则必须拦住违规依赖 | `backend/server/src/test/java/com/erp/arch/ArchitectureTest.java` |
| `TC-ARCH-009` | ★ 规则自检：成本模块不得直连库存仓储的规则必须拦住违规 import | `backend/server/src/test/java/com/erp/arch/ArchitectureTest.java` |
| `TC-ARCH-011` | ★ 规则自检：sys 只依赖 shared 的规则必须拦住依赖业务模块 | `backend/server/src/test/java/com/erp/arch/ArchitectureTest.java` |
| `TC-ARCH-012` | ★ 规则自检：审计仓储不得存在改删方法的规则必须拦住 deleteById | `backend/server/src/test/java/com/erp/arch/ArchitectureTest.java` |
| `TC-ARCH-013` | ★ 规则自检（反向）：只有 insert 的审计仓储必须通过不可篡改规则 | `backend/server/src/test/java/com/erp/arch/ArchitectureTest.java` |
| `TC-ARCH-014` | ★ 规则自检（反向）：sys 依赖 shared 必须合法 | `backend/server/src/test/java/com/erp/arch/ArchitectureTest.java` |
| `TC-ARCH-015` | ★ 规则自检：审批模块不得依赖单据仓储的规则必须拦住违规 import | `backend/server/src/test/java/com/erp/arch/ArchitectureTest.java` |
| `TC-ARCH-016` | ★ 规则自检：审批模块不得依赖单据领域类型的规则必须拦住违规引用 | `backend/server/src/test/java/com/erp/arch/ArchitectureTest.java` |
| `TC-CST-028` | 从流水重算：成本层落表、汇总满足 INV-11 | `backend/server/src/test/java/com/erp/cst/CostEngineIT.java` |
| `TC-CST-029` | 真实进货 + 出货链路：出库按移动均价结转，流水成本不被回写 | `backend/server/src/test/java/com/erp/cst/CostEngineIT.java` |
| `TC-CST-030` | 两笔进货后出货：单位成本 = 加权均价 150 | `backend/server/src/test/java/com/erp/cst/CostEngineIT.java` |
| `TC-CST-031` | 真实进货红冲：PURCHASE_REVERSAL 按原进价结转，期末归零 | `backend/server/src/test/java/com/erp/cst/CostEngineIT.java` |
| `TC-CST-032` | 真实出货红冲：SALES_REVERSAL 配对原出库成本，净额为 0 | `backend/server/src/test/java/com/erp/cst/CostEngineIT.java` |
| `TC-CST-033` | 真实退货：RETURN_IN 按原出货成本回补 | `backend/server/src/test/java/com/erp/cst/CostEngineIT.java` |
| `TC-CST-034` | 单池重算与全量重算结果完全一致 | `backend/server/src/test/java/com/erp/cst/CostEngineIT.java` |
| `TC-CST-035` | ★ 并发入库：advisory lock 串行化重算，均价不后写赢 | `backend/server/src/test/java/com/erp/cst/CostEngineIT.java` |
| `TC-CST-036` | 重算幂等：同一批流水连跑两次，成本层完全一致 | `backend/server/src/test/java/com/erp/cst/CostEngineIT.java` |
| `TC-CST-037` | 成本层独立持久化：不回写 inventory_ledger.unit_cost_amount | `backend/server/src/test/java/com/erp/cst/CostEngineIT.java` |
| `TC-CST-038` | 多仓成本池独立：互不污染均价与期末成本 | `backend/server/src/test/java/com/erp/cst/CostEngineIT.java` |
| `TC-CST-039` | 月结行 = 期初 + 入 − 出，期末金额与成本层一致 | `backend/server/src/test/java/com/erp/cst/CostMonthlyIT.java` |
| `TC-CST-040` | ★ 同账期跑两次：第二次 duplicate=true，业务字段与 computed_at 都不变 | `backend/server/src/test/java/com/erp/cst/CostMonthlyIT.java` |
| `TC-CST-041` | 跨期衔接：下月期初 = 上月期末 | `backend/server/src/test/java/com/erp/cst/CostMonthlyIT.java` |
| `TC-CST-042` | 账期已锁：复用 PeriodGuard，成本月结被拒绝 | `backend/server/src/test/java/com/erp/cst/CostMonthlyIT.java` |
| `TC-CST-043` | 账期格式非法：月结与查询都返回参数错误 | `backend/server/src/test/java/com/erp/cst/CostMonthlyIT.java` |
| `TC-CST-044` | 期间数据变化后重跑：同键行更新且 version 递增 | `backend/server/src/test/java/com/erp/cst/CostMonthlyIT.java` |
| `TC-CST-045` | 月结查询：无活动但有期初的账期仍落一行，未月结账期返回空 | `backend/server/src/test/java/com/erp/cst/CostMonthlyIT.java` |
| `TC-CST-046` | 无流水账期：月结成功但无行，duplicate=true | `backend/server/src/test/java/com/erp/cst/CostMonthlyIT.java` |
| `TC-CST-055` | 月结重算后不再有流水的池：旧月结行被删除 | `backend/server/src/test/java/com/erp/cst/CostMonthlyIT.java` |
| `TC-CST-062` | 真实调拨（无 unitCostAmount）：调入成本=调出成本，两仓合计守恒，调拨后出库成本正确 | `backend/server/src/test/java/com/erp/cst/CostTransferIT.java` |
| `TC-CST-047` | 成本月结生成毛利记录：收入、成本、毛利与毛利率 | `backend/server/src/test/java/com/erp/cst/MarginAnalysisIT.java` |
| `TC-CST-048` | 毛利成本 = SALES_OUT 成本层合计 | `backend/server/src/test/java/com/erp/cst/MarginAnalysisIT.java` |
| `TC-CST-049` | 按商品聚合：同 SKU 多张出货单合并，orderCount 去重 | `backend/server/src/test/java/com/erp/cst/MarginAnalysisIT.java` |
| `TC-CST-050` | 按客户聚合：不同客户各一行，金额按客户分组 | `backend/server/src/test/java/com/erp/cst/MarginAnalysisIT.java` |
| `TC-CST-051` | 按期间聚合：两个账期各一行，收入按期间归属 | `backend/server/src/test/java/com/erp/cst/MarginAnalysisIT.java` |
| `TC-CST-052` | 退货净额：数量、收入、成本同时冲减，毛利率按净额重算 | `backend/server/src/test/java/com/erp/cst/MarginAnalysisIT.java` |
| `TC-CST-053` | 毛利查询：空账期返回空；区间/维度非法返回 400 | `backend/server/src/test/java/com/erp/cst/MarginAnalysisIT.java` |
| `TC-CST-054` | 收入为 0：毛利率记 0，但成本与毛利按实际金额保留 | `backend/server/src/test/java/com/erp/cst/MarginAnalysisIT.java` |
| `TC-CST-056` | 完全退货：净数量为 0 的毛利记录被删除 | `backend/server/src/test/java/com/erp/cst/MarginAnalysisIT.java` |
| `TC-CST-006` | 空流水 → 全零且守恒 | `backend/server/src/test/java/com/erp/cst/domain/MovingAverageCostCalculatorTest.java` |
| `TC-CST-007` | 单笔入库：成本层单位成本 = 进货单价，池状态正确 | `backend/server/src/test/java/com/erp/cst/domain/MovingAverageCostCalculatorTest.java` |
| `TC-CST-008` | 两笔入库加权平均：10@100 + 10@200 → 均价 150 | `backend/server/src/test/java/com/erp/cst/domain/MovingAverageCostCalculatorTest.java` |
| `TC-CST-009` | 部分出库后均价保持不变 | `backend/server/src/test/java/com/erp/cst/domain/MovingAverageCostCalculatorTest.java` |
| `TC-CST-010` | 一次出清：结转全部池成本，期末数量与成本都为 0 | `backend/server/src/test/java/com/erp/cst/domain/MovingAverageCostCalculatorTest.java` |
| `TC-CST-011` | 出库除法按 HALF_UP；最后一件取剩余全部成本 | `backend/server/src/test/java/com/erp/cst/domain/MovingAverageCostCalculatorTest.java` |
| `TC-CST-012` | 进货红冲按原进价出库，池金额不被当前均价污染 | `backend/server/src/test/java/com/erp/cst/domain/MovingAverageCostCalculatorTest.java` |
| `TC-CST-013` | 出货红冲配对原 SALES_OUT：成本净额为 0 | `backend/server/src/test/java/com/erp/cst/domain/MovingAverageCostCalculatorTest.java` |
| `TC-CST-014` | 出货红冲无原始流水可配对时退回当前均价 | `backend/server/src/test/java/com/erp/cst/domain/MovingAverageCostCalculatorTest.java` |
| `TC-CST-015` | 退货按原出货成本回补：出货 + 退货在成本侧净额为 0 | `backend/server/src/test/java/com/erp/cst/domain/MovingAverageCostCalculatorTest.java` |
| `TC-CST-016` | 退货找不到原出货单时退回当前均价 | `backend/server/src/test/java/com/erp/cst/domain/MovingAverageCostCalculatorTest.java` |
| `TC-CST-017` | 无显式成本的调整入库按当前均价入池（调拨入库已改为优先配对） | `backend/server/src/test/java/com/erp/cst/domain/MovingAverageCostCalculatorTest.java` |
| `TC-CST-018` | 不同 (SKU, 仓库) 成本池互不影响 | `backend/server/src/test/java/com/erp/cst/domain/MovingAverageCostCalculatorTest.java` |
| `TC-CST-019` | 混合序列（入/出/红冲/退货/调整）仍满足 INV-11 | `backend/server/src/test/java/com/erp/cst/domain/MovingAverageCostCalculatorTest.java` |
| `TC-CST-020` | 乱序输入按业务时间 + 流水 id 排序，结果确定 | `backend/server/src/test/java/com/erp/cst/domain/MovingAverageCostCalculatorTest.java` |
| `TC-CST-021` | 逐条增量计算与一次性回放结果一致 | `backend/server/src/test/java/com/erp/cst/domain/MovingAverageCostCalculatorTest.java` |
| `TC-CST-022` | 出库超过池数量必须拒绝（程序性错误，不允许静默负库存） | `backend/server/src/test/java/com/erp/cst/domain/MovingAverageCostCalculatorTest.java` |
| `TC-CST-057` | 调拨：目标仓为空时，调入按源仓出库成本配对，两仓合计守恒 | `backend/server/src/test/java/com/erp/cst/domain/MovingAverageCostCalculatorTest.java` |
| `TC-CST-058` | 调拨：目标仓已有不同均价时，调入仍按源仓成本，不被目标均价重估 | `backend/server/src/test/java/com/erp/cst/domain/MovingAverageCostCalculatorTest.java` |
| `TC-CST-059` | 多批次/部分调拨：每个批次各自配对，未带 unitCostAmount 仍守恒 | `backend/server/src/test/java/com/erp/cst/domain/MovingAverageCostCalculatorTest.java` |
| `TC-CST-060` | 调拨后再出库：目标仓出库成本沿用源仓成本，外部出库 + 两仓期末守恒 | `backend/server/src/test/java/com/erp/cst/domain/MovingAverageCostCalculatorTest.java` |
| `TC-IAM-024` | ★ 越权矩阵：单据直查 404、列表类 200 只见本仓（不出现 B 仓数据）、主数据作对照 | `backend/server/src/test/java/com/erp/iam/DataScopeMatrixIT.java` |
| `TC-IAM-043` | ★ 矩阵期望表自检：404 / 只见本仓 / 主数据对照 三类格子数必须固定 | `backend/server/src/test/java/com/erp/iam/DataScopeMatrixIT.java` |
| `TC-IAM-025` | ★ 矩阵规模自检：8×5×3 = 120 个组合，一个都不能少 | `backend/server/src/test/java/com/erp/iam/DataScopeMatrixIT.java` |
| `TC-IAM-026` | ★ 对照组（防假绿）：本仓库存/单据必须 200 且有内容，证明 404 来自收窄而非接口全坏 | `backend/server/src/test/java/com/erp/iam/DataScopeMatrixIT.java` |
| `TC-IAM-027` | ★ 建账号时复杂度不合规一律拒绝（IAM_PASSWORD_WEAK），与改密用同一套策略 | `backend/server/src/test/java/com/erp/iam/IamDeepeningIT.java` |
| `TC-IAM-028` | ★ 新建账号一律置强制改密：管理员知道初始密码，用户必须先改掉 | `backend/server/src/test/java/com/erp/iam/IamDeepeningIT.java` |
| `TC-IAM-029` | ★ 密码过期后：业务接口被拦成 IAM_PASSWORD_EXPIRED，但改密接口仍然可用 | `backend/server/src/test/java/com/erp/iam/IamDeepeningIT.java` |
| `TC-IAM-030` | ★ 改密必须验旧密码；新旧相同被拒；新密码不合规被拒 | `backend/server/src/test/java/com/erp/iam/IamDeepeningIT.java` |
| `TC-IAM-031` | ★ 连续失败锁定：阈值内提示剩余次数，到阈值锁 IAM_ACCOUNT_LOCKED，到期自动解锁 | `backend/server/src/test/java/com/erp/iam/IamDeepeningIT.java` |
| `TC-IAM-032` | ★ 管理员重置密码后必须强制改密；重置也走同一套复杂度策略 | `backend/server/src/test/java/com/erp/iam/IamDeepeningIT.java` |
| `TC-IAM-033` | ★ 授权即时生效：改角色权限后，同一会话的下一个请求立刻按新权限判定（无需重新登录） | `backend/server/src/test/java/com/erp/iam/IamDeepeningIT.java` |
| `TC-IAM-034` | ★ 数据权限约束变更同样即时生效：改 scope 后同一个 token 的下一次查询立刻变窄 | `backend/server/src/test/java/com/erp/iam/IamDeepeningIT.java` |
| `TC-IAM-035` | ★ 角色管理自我保护：不能清空自己的角色、不能停用自己（否则把自己锁在系统外） | `backend/server/src/test/java/com/erp/iam/IamDeepeningIT.java` |
| `TC-IAM-036` | ★ 角色权限是覆盖式：取消勾选必须真的生效（不是增量追加） | `backend/server/src/test/java/com/erp/iam/IamDeepeningIT.java` |
| `TC-IAM-037` | 用户接口：列表可搜关键字；响应绝不包含 passwordHash | `backend/server/src/test/java/com/erp/iam/IamDeepeningIT.java` |
| `TC-IAM-038` | ★ 数据权限解析优先级：未配置=全量 / 用户直配 > 角色继承 / ALL 覆盖 WAREHOUSE | `backend/server/src/test/java/com/erp/iam/IamDeepeningIT.java` |
| `TC-IAM-039` | ★ SELF 范围：只看本人创建的行（历史行 created_by 为空 → 看不到） | `backend/server/src/test/java/com/erp/iam/IamDeepeningIT.java` |
| `TC-IAM-040` | ★ 数据权限配置非法一律 400：WAREHOUSE 缺 id / 未知类型 / 数值非数字 | `backend/server/src/test/java/com/erp/iam/IamDeepeningIT.java` |
| `TC-IAM-041` | ★ 空仓库集合 = 什么都不许看（AND 1=0），而不是回退到全量 | `backend/server/src/test/java/com/erp/iam/IamDeepeningIT.java` |
| `TC-IAM-042` | 数据权限管理接口：需 iam:scope:write，无权限 403 | `backend/server/src/test/java/com/erp/iam/IamDeepeningIT.java` |
| `TC-IAM-001` | 登录成功返回 token 与权限；密码错误返回 401（且不区分用户不存在） | `backend/server/src/test/java/com/erp/iam/IamIT.java` |
| `TC-IAM-002` | ★ 密码连续错误达到阈值后锁定；到期后自动解锁（锁在数据库，重启不失效） | `backend/server/src/test/java/com/erp/iam/IamIT.java` |
| `TC-IAM-003` | 停用账号无法登录（IAM_ACCOUNT_DISABLED） | `backend/server/src/test/java/com/erp/iam/IamIT.java` |
| `TC-IAM-004` | ★ token 过期后失效：快进超过有效期，校验必须失败 | `backend/server/src/test/java/com/erp/iam/IamIT.java` |
| `TC-IAM-005` | ★ 篡改 token 的任意一段都必须失效（签名校验） | `backend/server/src/test/java/com/erp/iam/IamIT.java` |
| `TC-IAM-006` | 未携带 token 访问受保护接口 → 401；登录接口本身匿名可达 | `backend/server/src/test/java/com/erp/iam/IamIT.java` |
| `TC-IAM-007` | ★ 无权限接口返回 403：VIEWER 只读可以，写操作必须 403 | `backend/server/src/test/java/com/erp/iam/IamIT.java` |
| `TC-IAM-008` | ★ 角色隔离：采购员可建进货单但不能出货；管理员全权 | `backend/server/src/test/java/com/erp/iam/IamIT.java` |
| `TC-IAM-009` | 权限变更立即生效：撤掉角色后，旧 token 的下一次请求即被拒绝 | `backend/server/src/test/java/com/erp/iam/IamIT.java` |
| `TC-IAM-010` | 密码只存 BCrypt 哈希，明文绝不出现在库中 | `backend/server/src/test/java/com/erp/iam/IamIT.java` |
| `TC-IAM-011` | ★ FINANCE 权限面：V7 后可读主数据字典与账期列表，仍不能写主数据、仍读不到单据 | `backend/server/src/test/java/com/erp/iam/IamIT.java` |
| `TC-IAM-012` | ★ 长度边界：8 位通过、7 位拒绝（下限是 ≥ 而不是 >） | `backend/server/src/test/java/com/erp/iam/domain/PasswordPolicyTest.java` |
| `TC-IAM-013` | ★ 字符类别边界：2 类拒绝、3 类通过、4 类通过（纯小写即使够长也拒） | `backend/server/src/test/java/com/erp/iam/domain/PasswordPolicyTest.java` |
| `TC-IAM-014` | ★ 8 位纯小写必须被拒：只查长度会让 aaaaaaaa 通过 —— 两类规则缺一不可 | `backend/server/src/test/java/com/erp/iam/domain/PasswordPolicyTest.java` |
| `TC-IAM-015` | ★ 弱口令表大小写不敏感：Admin@123 与 admin@123 是同一条字典项 | `backend/server/src/test/java/com/erp/iam/domain/PasswordPolicyTest.java` |
| `TC-IAM-016` | 弱口令检查优先于长度/类别：让错误消息直指真正原因（换个更像样的密码） | `backend/server/src/test/java/com/erp/iam/domain/PasswordPolicyTest.java` |
| `TC-IAM-017` | 空密码 / 纯空白一律拒绝 | `backend/server/src/test/java/com/erp/iam/domain/PasswordPolicyTest.java` |
| `TC-IAM-018` | ★ 策略可配置：同一密码在 permissive 策略下通过、在 standard 下被拒 | `backend/server/src/test/java/com/erp/iam/domain/PasswordPolicyTest.java` |
| `TC-IAM-019` | 非法策略参数在构造期就炸掉：minLength<=0 / 类别数越界 | `backend/server/src/test/java/com/erp/iam/domain/PasswordPolicyTest.java` |
| `TC-IAM-020` | ★ 密码过期判定：过期时刻早于/等于此刻 = 过期，晚于 = 未过期 | `backend/server/src/test/java/com/erp/iam/domain/PasswordPolicyTest.java` |
| `TC-IAM-021` | ★ 强制改密标记：即使密码永不过期，mustChangePassword=true 也必须先改 | `backend/server/src/test/java/com/erp/iam/domain/PasswordPolicyTest.java` |
| `TC-IAM-022` | 过期时刻按 maxAgeDays 计算；0 表示不按时间过期 | `backend/server/src/test/java/com/erp/iam/domain/PasswordPolicyTest.java` |
| `TC-IAM-023` | 策略描述可读且完整：前端照它展示，用户不必反复试错 | `backend/server/src/test/java/com/erp/iam/domain/PasswordPolicyTest.java` |
| `TC-INV-048` | 调增：实存增加、写 ADJUST_IN 正流水、原因落库 | `backend/server/src/test/java/com/erp/inv/InventoryAdjustmentIT.java` |
| `TC-INV-049` | 调减：实存减少、写 ADJUST_OUT 负流水 | `backend/server/src/test/java/com/erp/inv/InventoryAdjustmentIT.java` |
| `TC-INV-050` | 原因必填：空白原因直接拒绝，不产生任何库存变动 | `backend/server/src/test/java/com/erp/inv/InventoryAdjustmentIT.java` |
| `TC-INV-051` | 调整数量为 0：没有意义，拒绝 | `backend/server/src/test/java/com/erp/inv/InventoryAdjustmentIT.java` |
| `TC-INV-052` | 同键重复调整：只生效一次，返回首次结果（INV-9） | `backend/server/src/test/java/com/erp/inv/InventoryAdjustmentIT.java` |
| `TC-INV-053` | 调减不得把实存压到预占以下：拒绝且不留流水 | `backend/server/src/test/java/com/erp/inv/InventoryAdjustmentIT.java` |
| `TC-INV-054` | 调整列表：按 SKU / 仓库筛选，倒序返回 | `backend/server/src/test/java/com/erp/inv/InventoryAdjustmentIT.java` |
| `TC-INV-055` | 调增可以为尚不存在的批次建行；INV-2 仍然成立 | `backend/server/src/test/java/com/erp/inv/InventoryAdjustmentIT.java` |
| `TC-INV-056` | 冻结只减少可用量：实存不变、frozen_qty 增加、可用量 = 实存 − 预占 − 冻结 | `backend/server/src/test/java/com/erp/inv/InventoryFreezeIT.java` |
| `TC-INV-057` | 冻结量超过可用量：拒绝（INV-1 可用量不得为负） | `backend/server/src/test/java/com/erp/inv/InventoryFreezeIT.java` |
| `TC-INV-058` | 解冻：可用量恢复、状态 RELEASED、解冻时间来自注入时钟 | `backend/server/src/test/java/com/erp/inv/InventoryFreezeIT.java` |
| `TC-INV-059` | 同键重复冻结：只冻结一次（INV-9） | `backend/server/src/test/java/com/erp/inv/InventoryFreezeIT.java` |
| `TC-INV-060` | 同键重复解冻：返回首次结果，不重复释放冻结量 | `backend/server/src/test/java/com/erp/inv/InventoryFreezeIT.java` |
| `TC-INV-061` | 已解冻的记录换键再解冻：状态非法 | `backend/server/src/test/java/com/erp/inv/InventoryFreezeIT.java` |
| `TC-INV-062` | 冻结量不可再被预占：预占校验的是可用量（INV-1 / INV-3） | `backend/server/src/test/java/com/erp/inv/InventoryFreezeIT.java` |
| `TC-INV-063` | 解冻量超过冻结量：余额不允许减成负数 | `backend/server/src/test/java/com/erp/inv/InventoryFreezeIT.java` |
| `TC-INV-064` | ★ 冻结超时：到期后自动解冻并归还可用量（时钟快进 11 分钟） | `backend/server/src/test/java/com/erp/inv/InventoryFreezeIT.java` |
| `TC-INV-065` | 未到期的冻结不得被超时任务释放 | `backend/server/src/test/java/com/erp/inv/InventoryFreezeIT.java` |
| `TC-INV-066` | 冻结/解冻不写库存流水（INV-2 只约束实存变动） | `backend/server/src/test/java/com/erp/inv/InventoryFreezeIT.java` |
| `TC-INV-067` | 冻结列表：状态 / SKU / 仓库筛选生效 | `backend/server/src/test/java/com/erp/inv/InventoryFreezeIT.java` |
| `TC-INV-068` | 冻结记录不存在：404 语义 | `backend/server/src/test/java/com/erp/inv/InventoryFreezeIT.java` |
| `TC-INV-069` | 冻结到期时间不晚于当前时间：参数校验拒绝 | `backend/server/src/test/java/com/erp/inv/InventoryFreezeIT.java` |
| `TC-INV-070` | ★ 五维结存：对每个 (仓, SKU, 批次, 权属)，Σ各状态 == 实存总量 | `backend/server/src/test/java/com/erp/inv/InventoryInsightIT.java` |
| `TC-INV-071` | 五维结存：状态筛选只返回指定状态的数量 | `backend/server/src/test/java/com/erp/inv/InventoryInsightIT.java` |
| `TC-INV-072` | 五维结存：仓库 / SKU / 批次筛选生效 | `backend/server/src/test/java/com/erp/inv/InventoryInsightIT.java` |
| `TC-INV-073` | 五维结存：未接入的权属类型显式拒绝，不静默当成自有 | `backend/server/src/test/java/com/erp/inv/InventoryInsightIT.java` |
| `TC-INV-074` | 安全库存预警：可用量低于 min_level 时出现，阈值与数量正确 | `backend/server/src/test/java/com/erp/inv/InventoryInsightIT.java` |
| `TC-INV-075` | 安全库存看的是可用量：实存够但被预占/冻结后仍需预警 | `backend/server/src/test/java/com/erp/inv/InventoryInsightIT.java` |
| `TC-INV-076` | 可用量不低于安全库存：不产生安全库存预警 | `backend/server/src/test/java/com/erp/inv/InventoryInsightIT.java` |
| `TC-INV-077` | 临期预警：效期在 N 天内出现；远超阈值的不出现 | `backend/server/src/test/java/com/erp/inv/InventoryInsightIT.java` |
| `TC-INV-078` | 临期边界：今天 + 30 天在内，今天 + 31 天在外（注入时钟） | `backend/server/src/test/java/com/erp/inv/InventoryInsightIT.java` |
| `TC-INV-079` | 滞销预警：超过 N 天没有出库时出现，最后出库时间来自流水 | `backend/server/src/test/java/com/erp/inv/InventoryInsightIT.java` |
| `TC-INV-080` | 滞销边界：未满 N 天不预警 | `backend/server/src/test/java/com/erp/inv/InventoryInsightIT.java` |
| `TC-INV-081` | 预警类型筛选：只返回请求的类型 | `backend/server/src/test/java/com/erp/inv/InventoryInsightIT.java` |
| `TC-INV-082` | 补货建议：可用量低于 min 时补到 max，建议量 = max − available | `backend/server/src/test/java/com/erp/inv/InventoryInsightIT.java` |
| `TC-INV-083` | ★ 补货建议幂等：连续两次调用结果逐字段一致，且不落任何表 | `backend/server/src/test/java/com/erp/inv/InventoryInsightIT.java` |
| `TC-INV-084` | ★ 已下单未到货的 SKU 不重复建议（APPROVED 且有未收数量） | `backend/server/src/test/java/com/erp/inv/InventoryInsightIT.java` |
| `TC-INV-085` | 可用量已达标或 max_level=0：都不产生补货建议 | `backend/server/src/test/java/com/erp/inv/InventoryInsightIT.java` |
| `TC-INV-012` | 到期 ACTIVE 预占被自动释放：可用量恢复、实存不动、状态置 EXPIRED | `backend/server/src/test/java/com/erp/inv/InventoryReservationExpiryIT.java` |
| `TC-INV-013` | 未到期预占不动，且边界精确：ttl-1 与 ttl 都不放，ttl+1 才放 | `backend/server/src/test/java/com/erp/inv/InventoryReservationExpiryIT.java` |
| `TC-INV-014` | 超时释放只影响预占量，一条流水都不写（不变量 INV-2 的前提） | `backend/server/src/test/java/com/erp/inv/InventoryReservationExpiryIT.java` |
| `TC-INV-015` | 过期任务幂等且定时入口只是转发；测试上下文里调度器根本没装配（坑 9b 开关生效） | `backend/server/src/test/java/com/erp/inv/InventoryReservationExpiryIT.java` |
| `TC-INV-016` | 过期释放后，余额行 reserved_qty 与 ACTIVE 预占合计逐行一致 | `backend/server/src/test/java/com/erp/inv/InventoryReservationExpiryIT.java` |
| `TC-INV-001` | 预占成功后：实存不变、预占增加、可用量相应减少（不变量 INV-3） | `backend/server/src/test/java/com/erp/inv/InventoryReserveIT.java` |
| `TC-INV-002` | 可用量不足必须拒绝，且不产生任何副作用（错误码含定位信息） | `backend/server/src/test/java/com/erp/inv/InventoryReserveIT.java` |
| `TC-INV-003` | 预占只受可用量约束：实存 10、已预占 8 时，预占 3 必须失败 | `backend/server/src/test/java/com/erp/inv/InventoryReserveIT.java` |
| `TC-INV-004` | 释放预占后可用量恢复，实存不受影响 | `backend/server/src/test/java/com/erp/inv/InventoryReserveIT.java` |
| `TC-INV-005` | 相同 requestId 重复预占只生效一次（幂等，不变量 INV-9） | `backend/server/src/test/java/com/erp/inv/InventoryReserveIT.java` |
| `TC-INV-006` | ★ 并发预占：100 个请求抢 50 件库存，绝不超卖且无流水漂移 | `backend/server/src/test/java/com/erp/inv/InventoryReserveIT.java` |
| `TC-INV-007` | ★ 并发预占同一 requestId：只允许一次生效（数据库唯一约束兜底） | `backend/server/src/test/java/com/erp/inv/InventoryReserveIT.java` |
| `TC-INV-008` | 入库写正向流水后，实存与流水净和一致（不变量 INV-2） | `backend/server/src/test/java/com/erp/inv/InventoryReserveIT.java` |
| `TC-INV-009` | 按 FEFO 出库：优先扣减最早效期批次，单笔可拆多批次 | `backend/server/src/test/java/com/erp/inv/InventoryReserveIT.java` |
| `TC-INV-010` | 数据库 CHECK 约束兜底：即使应用层有 bug，实存也不可能被写成负数 | `backend/server/src/test/java/com/erp/inv/InventoryReserveIT.java` |
| `TC-INV-011` | 数据库 CHECK 约束兜底：预占不得超过实存 | `backend/server/src/test/java/com/erp/inv/InventoryReserveIT.java` |
| `TC-INV-017` | 建单即调出：源仓实存减少、在途量增加，守恒式（INV-7）为 0 | `backend/server/src/test/java/com/erp/inv/InventoryTransferIT.java` |
| `TC-INV-018` | 到货：在途归零、目的仓实存增加，守恒式仍为 0 | `backend/server/src/test/java/com/erp/inv/InventoryTransferIT.java` |
| `TC-INV-019` | 多张调拨单交错到货：每张单的守恒式各自为 0 | `backend/server/src/test/java/com/erp/inv/InventoryTransferIT.java` |
| `TC-INV-020` | 源仓可用量不足：整单拒绝，不建单、不产生调出流水 | `backend/server/src/test/java/com/erp/inv/InventoryTransferIT.java` |
| `TC-INV-021` | 调出仓与调入仓相同：参数校验拒绝 | `backend/server/src/test/java/com/erp/inv/InventoryTransferIT.java` |
| `TC-INV-022` | 同一 X-Request-Id 重复建调拨：只扣一次、返回首次结果（INV-9） | `backend/server/src/test/java/com/erp/inv/InventoryTransferIT.java` |
| `TC-INV-023` | 同键重复到货：返回首次结果，不重复增加目的仓库存 | `backend/server/src/test/java/com/erp/inv/InventoryTransferIT.java` |
| `TC-INV-024` | 已到货的单换一个幂等键再次到货：状态非法，库存不变 | `backend/server/src/test/java/com/erp/inv/InventoryTransferIT.java` |
| `TC-INV-025` | 调拨单不存在：404 语义 | `backend/server/src/test/java/com/erp/inv/InventoryTransferIT.java` |
| `TC-INV-026` | 多 SKU 多批次调拨：逐行守恒，目的仓按原批次落账 | `backend/server/src/test/java/com/erp/inv/InventoryTransferIT.java` |
| `TC-INV-027` | 冻结量不可调拨：可用量不足时拒绝 | `backend/server/src/test/java/com/erp/inv/InventoryTransferIT.java` |
| `TC-INV-028` | 已预占的量不可调拨：扣减后不得低于预占量 | `backend/server/src/test/java/com/erp/inv/InventoryTransferIT.java` |
| `TC-INV-029` | 批次生产日期/效期随调拨带到目的仓（FEFO 不丢效期） | `backend/server/src/test/java/com/erp/inv/InventoryTransferIT.java` |
| `TC-INV-030` | 调拨列表：状态与仓库筛选、分页字段正确 | `backend/server/src/test/java/com/erp/inv/InventoryTransferIT.java` |
| `TC-INV-031` | 调拨数量必须为正、明细不得为空：参数校验拒绝 | `backend/server/src/test/java/com/erp/inv/InventoryTransferIT.java` |
| `TC-INV-032` | 同一 SKU + 批次重复出现：拒绝，避免流水唯一键冲突 | `backend/server/src/test/java/com/erp/inv/InventoryTransferIT.java` |
| `TC-INV-033` | 源批次没有余额行：明确报库存不存在，而不是写成负库存 | `backend/server/src/test/java/com/erp/inv/InventoryTransferIT.java` |
| `TC-INV-034` | 建盘点单：账面量在此时快照，状态 DRAFT，差异由数据库生成列计算 | `backend/server/src/test/java/com/erp/inv/StocktakeIT.java` |
| `TC-INV-035` | 盘盈：过账生成 ADJUST_IN 正流水并更新实存 | `backend/server/src/test/java/com/erp/inv/StocktakeIT.java` |
| `TC-INV-036` | 盘亏：过账生成 ADJUST_OUT 负流水并更新实存 | `backend/server/src/test/java/com/erp/inv/StocktakeIT.java` |
| `TC-INV-037` | ★ 差异为 0 的行不产生过账流水，有差异的行照常过账 | `backend/server/src/test/java/com/erp/inv/StocktakeIT.java` |
| `TC-INV-038` | 全部行差异为 0：拒绝过账，状态留在 DRAFT，不产生任何调整流水 | `backend/server/src/test/java/com/erp/inv/StocktakeIT.java` |
| `TC-INV-039` | 同键重复建盘点单：返回首次结果，不重复建单（INV-9） | `backend/server/src/test/java/com/erp/inv/StocktakeIT.java` |
| `TC-INV-040` | 同键重复过账：返回首次结果，库存只调整一次 | `backend/server/src/test/java/com/erp/inv/StocktakeIT.java` |
| `TC-INV-041` | 已过账的盘点单换键再过账：状态非法 | `backend/server/src/test/java/com/erp/inv/StocktakeIT.java` |
| `TC-INV-042` | 盘亏会把实存压到预占以下：整单回滚，状态退回 DRAFT | `backend/server/src/test/java/com/erp/inv/StocktakeIT.java` |
| `TC-INV-043` | 盘盈盘亏混合过账后，INV-2（实存 == 流水净和）仍成立 | `backend/server/src/test/java/com/erp/inv/StocktakeIT.java` |
| `TC-INV-044` | 盘点单不存在：404 语义 | `backend/server/src/test/java/com/erp/inv/StocktakeIT.java` |
| `TC-INV-045` | 实盘数量为负：参数校验拒绝 | `backend/server/src/test/java/com/erp/inv/StocktakeIT.java` |
| `TC-INV-046` | 同一 SKU + 批次重复盘点：拒绝 | `backend/server/src/test/java/com/erp/inv/StocktakeIT.java` |
| `TC-INV-047` | 盘点列表：状态与仓库筛选生效 | `backend/server/src/test/java/com/erp/inv/StocktakeIT.java` |
| `TC-MDM-001` | 商品编码重复必须被数据库唯一约束拒绝，并翻译成 MDM_CODE_DUPLICATED | `backend/server/src/test/java/com/erp/mdm/MasterDataIT.java` |
| `TC-MDM-002` | ★ 并发创建同一商品编码：只允许一个成功，其余全部是 409 而不是 500 | `backend/server/src/test/java/com/erp/mdm/MasterDataIT.java` |
| `TC-MDM-003` | 商品停用后记录保留（停用不删），且 enabled 变为 false | `backend/server/src/test/java/com/erp/mdm/MasterDataIT.java` |
| `TC-MDM-004` | 商品分页：keyword 同时匹配编码与名称，total 是全部匹配数而非当前页 | `backend/server/src/test/java/com/erp/mdm/MasterDataIT.java` |
| `TC-MDM-005` | 商品不存在时查详情返回 404 语义的业务异常（不是空指针） | `backend/server/src/test/java/com/erp/mdm/MasterDataIT.java` |
| `TC-MDM-006` | ★ SKU 矩阵生成：2 色 × 3 码 = 6 个 SKU，且组合与价格正确 | `backend/server/src/test/java/com/erp/mdm/MasterDataIT.java` |
| `TC-MDM-007` | ★ 矩阵重复执行：已存在组合被跳过而不是报错（运营补色的安全重放） | `backend/server/src/test/java/com/erp/mdm/MasterDataIT.java` |
| `TC-MDM-008` | ★ 矩阵请求自身重复（颜色列表写了两次）：必须报 MDM_SKU_DUPLICATED，不静默去重 | `backend/server/src/test/java/com/erp/mdm/MasterDataIT.java` |
| `TC-MDM-009` | 矩阵的颜色或尺码列表为空：校验错误，不静默生成 0 条 | `backend/server/src/test/java/com/erp/mdm/MasterDataIT.java` |
| `TC-MDM-010` | 同一 (颜色, 尺码) 在不同商品下可重复；同一商品下重复则被约束拒绝 | `backend/server/src/test/java/com/erp/mdm/MasterDataIT.java` |
| `TC-MDM-011` | SKU 停用后不删：仍可查到，但 enabled=false | `backend/server/src/test/java/com/erp/mdm/MasterDataIT.java` |
| `TC-MDM-012` | SKU 零售价为负：校验错误（金额非负，ADR-0005） | `backend/server/src/test/java/com/erp/mdm/MasterDataIT.java` |
| `TC-MDM-013` | 给不存在的商品建 SKU：返回 404 语义（外键约束被翻译） | `backend/server/src/test/java/com/erp/mdm/MasterDataIT.java` |
| `TC-MDM-014` | 供应商编码唯一：重复编码返回 MDM_CODE_DUPLICATED | `backend/server/src/test/java/com/erp/mdm/MasterDataIT.java` |
| `TC-MDM-015` | 供应商停用不删，联系方式保留 | `backend/server/src/test/java/com/erp/mdm/MasterDataIT.java` |
| `TC-MDM-016` | 客户折扣等级：默认 10000（不打折），可设 9000（9 折） | `backend/server/src/test/java/com/erp/mdm/MasterDataIT.java` |
| `TC-MDM-017` | ★ 折扣率越界（0 / 10001 / 负数）必须被拒绝：0 等于白送，不允许 | `backend/server/src/test/java/com/erp/mdm/MasterDataIT.java` |
| `TC-MDM-018` | 数据库 CHECK 兜底：即使绕过应用层，折扣率也无法写成 0 或超过 10000 | `backend/server/src/test/java/com/erp/mdm/MasterDataIT.java` |
| `TC-MDM-019` | 仓库 CRUD + 编码唯一 + onlyEnabled 过滤 | `backend/server/src/test/java/com/erp/mdm/MasterDataIT.java` |
| `TC-MDM-020` | SKU 创建写入季节/波段/上市日期，年份由上市日期派生 | `backend/server/src/test/java/com/erp/mdm/SalesMasterDataDimensionsIT.java` |
| `TC-MDM-021` | SKU 更新维度：只改季节/波段不丢基础字段，null 表示保持原值 | `backend/server/src/test/java/com/erp/mdm/SalesMasterDataDimensionsIT.java` |
| `TC-MDM-022` | 按季节编码筛选：只返回匹配 SKU | `backend/server/src/test/java/com/erp/mdm/SalesMasterDataDimensionsIT.java` |
| `TC-MDM-023` | 按波段 + 上市日期区间筛选 | `backend/server/src/test/java/com/erp/mdm/SalesMasterDataDimensionsIT.java` |
| `TC-MDM-024` | 按年份筛选：seasonYear 从 launch_date 派生，不需要冗余列 | `backend/server/src/test/java/com/erp/mdm/SalesMasterDataDimensionsIT.java` |
| `TC-MDM-025` | 仓库优先级：创建/更新生效，列表按 priority 降序；缺省为 100 | `backend/server/src/test/java/com/erp/mdm/SalesMasterDataDimensionsIT.java` |
| `TC-MDM-026` | SKU 全量更新：基础字段与季节/波段同时更新，编码不变 | `backend/server/src/test/java/com/erp/mdm/SalesMasterDataDimensionsIT.java` |
| `TC-NL-004` | ★ tool_call 映射为意图与参数；请求带 Authorization、tools 与 tool_choice=auto | `backend/server/src/test/java/com/erp/nl/OpenAiNlCommandParserTest.java` |
| `TC-NL-005` | ★ 无 tool call → 422；上游 500 → 503；缺 key → 503 且提示 ERP_NL_API_KEY | `backend/server/src/test/java/com/erp/nl/OpenAiNlCommandParserTest.java` |
| `TC-NL-001` | 中文/键值对指令解析为正确意图与参数（库存/列表/详情/主数据/帮助） | `backend/server/src/test/java/com/erp/nl/StubNlCommandParserTest.java` |
| `TC-NL-002` | 本月/上月由注入时钟决定；建单多行用分号分隔；缺省账期不编造 | `backend/server/src/test/java/com/erp/nl/StubNlCommandParserTest.java` |
| `TC-NL-003` | 空/无法识别 → NL_COMMAND_UNRECOGNIZED；非法数字 → NL_COMMAND_INVALID_ARGUMENT | `backend/server/src/test/java/com/erp/nl/StubNlCommandParserTest.java` |
| `TC-API-030` | 请购单 HTTP：缺幂等键 400；建单 201；列表/详情包络与字段正确 | `backend/server/src/test/java/com/erp/pur/PurchaseLoopApiIT.java` |
| `TC-API-031` | 请购转单 HTTP：超量 409；正常 200；同键重放同一订单；换键 409 | `backend/server/src/test/java/com/erp/pur/PurchaseLoopApiIT.java` |
| `TC-API-032` | 到货质检 HTTP：部分合格入库；响应带 qc 字段；到货记录可查 | `backend/server/src/test/java/com/erp/pur/PurchaseLoopApiIT.java` |
| `TC-API-033` | 质检数量越界 HTTP：400 PUR_QC_QTY_INVALID，返回码不是 500 | `backend/server/src/test/java/com/erp/pur/PurchaseLoopApiIT.java` |
| `TC-API-034` | 采购退货 HTTP：缺幂等键 400；建单 201；执行 200；作废 200 | `backend/server/src/test/java/com/erp/pur/PurchaseLoopApiIT.java` |
| `TC-API-035` | 应付 HTTP：列表/详情/核对；余额与恒等式字段齐全 | `backend/server/src/test/java/com/erp/pur/PurchaseLoopApiIT.java` |
| `TC-API-036` | 付款 HTTP：部分核销 201；超额核销 409（明确不是 401） | `backend/server/src/test/java/com/erp/pur/PurchaseLoopApiIT.java` |
| `TC-API-037` | ★ 失败语义分离：未登录 401；VIEWER 无付款权限 403；缺幂等键 400 | `backend/server/src/test/java/com/erp/pur/PurchaseLoopApiIT.java` |
| `TC-API-038` | 参数校验 HTTP：非法 id 类型 400；数量为 0 400；空核销明细 400 | `backend/server/src/test/java/com/erp/pur/PurchaseLoopApiIT.java` |
| `TC-API-039` | 404 语义：请购 / 退货 / 应付 / 核销未知单据均返回对应 NOT_FOUND 码 | `backend/server/src/test/java/com/erp/pur/PurchaseLoopApiIT.java` |
| `TC-PUR-001` | 建单：单号为 PO+日期+流水，总金额由明细汇总（不反算），状态 DRAFT | `backend/server/src/test/java/com/erp/pur/PurchaseOrderIT.java` |
| `TC-PUR-002` | 建单幂等：相同 requestId 重复提交返回同一张单，不新增记录 | `backend/server/src/test/java/com/erp/pur/PurchaseOrderIT.java` |
| `TC-PUR-003` | 建单校验：空明细 / 数量为 0 / 单价为负 全部拒绝 | `backend/server/src/test/java/com/erp/pur/PurchaseOrderIT.java` |
| `TC-PUR-004` | 状态机正常路径：DRAFT → SUBMITTED → APPROVED | `backend/server/src/test/java/com/erp/pur/PurchaseOrderIT.java` |
| `TC-PUR-005` | ★ 跳过审核直接入库（DRAFT 状态）必须被拒绝：状态机不许被绕过 | `backend/server/src/test/java/com/erp/pur/PurchaseOrderIT.java` |
| `TC-PUR-006` | 非法状态转移：DRAFT 不能直接 APPROVED，APPROVED 不能回 SUBMITTED | `backend/server/src/test/java/com/erp/pur/PurchaseOrderIT.java` |
| `TC-PUR-007` | ★ 全额入库：状态变 COMPLETED，实存增加，且不变量 INV-2 成立 | `backend/server/src/test/java/com/erp/pur/PurchaseOrderIT.java` |
| `TC-PUR-008` | ★ 分批入库：先入 4 件进 PARTIAL，再入 6 件进 COMPLETED，累计正确 | `backend/server/src/test/java/com/erp/pur/PurchaseOrderIT.java` |
| `TC-PUR-009` | ★ 超量入库必须被拒绝（BR-DOC-04 → PUR_OVER_RECEIVE），且库存不变 | `backend/server/src/test/java/com/erp/pur/PurchaseOrderIT.java` |
| `TC-PUR-010` | ★ 多行入库其中一行超量：已处理行的库存增量必须随之回滚（整批事务） | `backend/server/src/test/java/com/erp/pur/PurchaseOrderIT.java` |
| `TC-PUR-011` | 同一进货单多行入库不产生流水唯一键冲突（requestId 按行拆分） | `backend/server/src/test/java/com/erp/pur/PurchaseOrderIT.java` |
| `TC-PUR-012` | 已完成的进货单不允许再入库（终态保护） | `backend/server/src/test/java/com/erp/pur/PurchaseOrderIT.java` |
| `TC-PUR-013` | 入库时指定不存在的明细行：404 语义，不产生库存变动 | `backend/server/src/test/java/com/erp/pur/PurchaseOrderIT.java` |
| `TC-PUR-014` | ★ 已入库单据红冲：回退实存、写 PURCHASE_REVERSAL、状态 VOIDED | `backend/server/src/test/java/com/erp/pur/PurchaseOrderIT.java` |
| `TC-PUR-015` | 草稿作废成功；作废后不能再提交 | `backend/server/src/test/java/com/erp/pur/PurchaseOrderIT.java` |
| `TC-PUR-016` | ★ COMPLETED 红冲（多次入库、多批次）：逐条反向流水，两个批次各自回退 | `backend/server/src/test/java/com/erp/pur/PurchaseOrderReversalIT.java` |
| `TC-PUR-017` | ★ 红冲幂等：同 X-Request-Id 重放不产生第二套流水；已作废后换新键被拒 | `backend/server/src/test/java/com/erp/pur/PurchaseOrderReversalIT.java` |
| `TC-PUR-018` | ★ 锁账期拒绝红冲：SET_PERIOD_CLOSED，状态/库存/流水全部不动 | `backend/server/src/test/java/com/erp/pur/PurchaseOrderReversalIT.java` |
| `TC-PUR-019` | ★ 货已被下游出货消耗：红冲被 INV_NEGATIVE_NOT_ALLOWED 拒绝，且零副作用 | `backend/server/src/test/java/com/erp/pur/PurchaseOrderReversalIT.java` |
| `TC-PUR-020` | ★ 货已被出货单预占：红冲会跌破预占线，拒绝且不动库存 | `backend/server/src/test/java/com/erp/pur/PurchaseOrderReversalIT.java` |
| `TC-PUR-021` | ★ 退货回补到别的批次不算：红冲不跨批次挪用库存 | `backend/server/src/test/java/com/erp/pur/PurchaseOrderReversalIT.java` |
| `TC-PUR-022` | ★ 并发红冲（异键）：只有一个成功，库存只回退一次 | `backend/server/src/test/java/com/erp/pur/PurchaseOrderReversalIT.java` |
| `TC-PUR-036` | ★ 全部合格：实存/可用量增加，行 qc_status=PASSED，应付按合格金额生成 | `backend/server/src/test/java/com/erp/pur/PurchaseQcIT.java` |
| `TC-PUR-037` | ★ 部分合格：只有合格量入库与生成应付，不合格量不产生可用库存 | `backend/server/src/test/java/com/erp/pur/PurchaseQcIT.java` |
| `TC-PUR-038` | ★ 全部不合格：不入库、不生成应付，行 qc_status=FAILED，但到货量正常关闭 | `backend/server/src/test/java/com/erp/pur/PurchaseQcIT.java` |
| `TC-PUR-039` | 合格数量 > 到货量：400 PUR_QC_QTY_INVALID，无任何库存/到货副作用 | `backend/server/src/test/java/com/erp/pur/PurchaseQcIT.java` |
| `TC-PUR-040` | 合格数量为负：400 PUR_QC_QTY_INVALID，原单保持 APPROVED | `backend/server/src/test/java/com/erp/pur/PurchaseQcIT.java` |
| `TC-PUR-041` | 多行混合质检：合格行进库存，全损行不入库，应付只按合格金额 | `backend/server/src/test/java/com/erp/pur/PurchaseQcIT.java` |
| `TC-PUR-042` | ★ 分批质检累计：5 合格 → 5 全损 → 5 合格，行状态按累计口径变 PARTIAL | `backend/server/src/test/java/com/erp/pur/PurchaseQcIT.java` |
| `TC-PUR-043` | 入库质检幂等：同 X-Request-Id 重放不重复入库/应付/到货记录 | `backend/server/src/test/java/com/erp/pur/PurchaseQcIT.java` |
| `TC-PUR-044` | 到货记录查询：返回批次、到货量、合格量、不合格量与质检状态 | `backend/server/src/test/java/com/erp/pur/PurchaseQcIT.java` |
| `TC-PUR-045` | 超量到货仍拒绝：不生成到货单、不改累计到货量 | `backend/server/src/test/java/com/erp/pur/PurchaseQcIT.java` |
| `TC-PUR-046` | 同一次入库重复提交同一明细行：400，整批回滚 | `backend/server/src/test/java/com/erp/pur/PurchaseQcIT.java` |
| `TC-PUR-024` | 建请购单：单号 PR 前缀、DRAFT、金额由明细汇总、明细完整 | `backend/server/src/test/java/com/erp/pur/PurchaseRequisitionIT.java` |
| `TC-PUR-025` | 建请购单幂等：同 requestId 返回同一张单，不新增记录 | `backend/server/src/test/java/com/erp/pur/PurchaseRequisitionIT.java` |
| `TC-PUR-026` | 建请购单校验：空明细 / 数量非正 / 单价为负全部拒绝 | `backend/server/src/test/java/com/erp/pur/PurchaseRequisitionIT.java` |
| `TC-PUR-027` | 请购状态机：DRAFT → SUBMITTED → APPROVED | `backend/server/src/test/java/com/erp/pur/PurchaseRequisitionIT.java` |
| `TC-PUR-028` | 请购非法转移：DRAFT 不能直接审核，APPROVED 不能回退提交 | `backend/server/src/test/java/com/erp/pur/PurchaseRequisitionIT.java` |
| `TC-PUR-029` | ★ 转订单：行不丢、数量可下调、请购单置 COMPLETED 并关联订单 | `backend/server/src/test/java/com/erp/pur/PurchaseRequisitionIT.java` |
| `TC-PUR-030` | 转单允许部分数量：申请 10 转 4，订单行数量为 4 | `backend/server/src/test/java/com/erp/pur/PurchaseRequisitionIT.java` |
| `TC-PUR-031` | ★ 转单数量超过申请数量必须拒绝，且不生成订单 | `backend/server/src/test/java/com/erp/pur/PurchaseRequisitionIT.java` |
| `TC-PUR-032` | 转单漏行 / 多行 / 未知行全部拒绝（行不丢） | `backend/server/src/test/java/com/erp/pur/PurchaseRequisitionIT.java` |
| `TC-PUR-033` | ★ 转单幂等：同键重放返回同一订单；换键再转 → PUR_REQ_STATUS_ILLEGAL | `backend/server/src/test/java/com/erp/pur/PurchaseRequisitionIT.java` |
| `TC-PUR-034` | ★ 转化后请购单不可再改：提交/审核/再次转化全部拒绝 | `backend/server/src/test/java/com/erp/pur/PurchaseRequisitionIT.java` |
| `TC-PUR-035` | ★ 请购单与进货单隔离：不进进货列表、不能走进货详情/入库 | `backend/server/src/test/java/com/erp/pur/PurchaseRequisitionIT.java` |
| `TC-PUR-047` | 建退货草稿：不扣库存、不冲应付、不写退货流水 | `backend/server/src/test/java/com/erp/pur/PurchaseReturnIT.java` |
| `TC-PUR-048` | 建退货草稿幂等：同 requestId 返回同一张单 | `backend/server/src/test/java/com/erp/pur/PurchaseReturnIT.java` |
| `TC-PUR-049` | ★ 执行退货：扣实存、写 PURCHASE_RETURN 负流水、应付退货额与余额同步变化 | `backend/server/src/test/java/com/erp/pur/PurchaseReturnIT.java` |
| `TC-PUR-050` | 退货数量超过合格到货量：409 PUR_RETURN_QTY_EXCEED，不生成退货单 | `backend/server/src/test/java/com/erp/pur/PurchaseReturnIT.java` |
| `TC-PUR-051` | ★ 累计退货上限：第一次退 3 后，再退 2（只剩 1）必须拒绝 | `backend/server/src/test/java/com/erp/pur/PurchaseReturnIT.java` |
| `TC-PUR-052` | 执行退货幂等：同键重放不重复扣库存/冲应付 | `backend/server/src/test/java/com/erp/pur/PurchaseReturnIT.java` |
| `TC-PUR-053` | 已执行退货不能再执行：换新幂等键 → PUR_RETURN_STATUS_ILLEGAL | `backend/server/src/test/java/com/erp/pur/PurchaseReturnIT.java` |
| `TC-PUR-054` | 草稿作废：零副作用、同键幂等；已执行退货不能作废 | `backend/server/src/test/java/com/erp/pur/PurchaseReturnIT.java` |
| `TC-PUR-055` | ★ 已付款部分不能被退货冲掉：PUR_RETURN_EXCEED_PAYABLE，库存与流水回滚 | `backend/server/src/test/java/com/erp/pur/PurchaseReturnIT.java` |
| `TC-PUR-056` | ★ 货已被卖出、原批次实存不足：INV_NEGATIVE_NOT_ALLOWED，应付不变 | `backend/server/src/test/java/com/erp/pur/PurchaseReturnIT.java` |
| `TC-PUR-057` | 到货行不属于该进货单：404，不生成退货单 | `backend/server/src/test/java/com/erp/pur/PurchaseReturnIT.java` |
| `TC-PUR-058` | 账期已锁：建退货单被 SET_PERIOD_CLOSED 拒绝，零副作用 | `backend/server/src/test/java/com/erp/pur/PurchaseReturnIT.java` |
| `TC-PUR-059` | ★ 质检合格到货生成应付：原值 = 合格量×单价，余额 = 原值，状态 OPEN | `backend/server/src/test/java/com/erp/pur/SupplierPayableIT.java` |
| `TC-PUR-060` | ★ 应付余额恒等式可重复校验：Σ余额 == Σ进货 − Σ退货 − Σ付款，两次查询一致 | `backend/server/src/test/java/com/erp/pur/SupplierPayableIT.java` |
| `TC-PUR-061` | 应付详情包含来源订单号与（初始为空的）核销明细 | `backend/server/src/test/java/com/erp/pur/SupplierPayableIT.java` |
| `TC-PUR-062` | 应付列表按供应商与状态过滤：OPEN 有 1 条，SETTLED 为 0，其它供应商为 0 | `backend/server/src/test/java/com/erp/pur/SupplierPayableIT.java` |
| `TC-PUR-063` | 全部质检不合格：不生成应付单，余额核对为 0 | `backend/server/src/test/java/com/erp/pur/SupplierPayableIT.java` |
| `TC-PUR-064` | 采购退货冲减应付：退货额累加、余额下降、状态变 PARTIAL | `backend/server/src/test/java/com/erp/pur/SupplierPayableIT.java` |
| `TC-PUR-065` | ★ 数据库兜底：退货 + 付款超过原值时 CHECK 约束拒绝，余额不可能为负 | `backend/server/src/test/java/com/erp/pur/SupplierPayableIT.java` |
| `TC-PUR-066` | ★ 部分核销：应付 10000 付 4000，余额 6000、状态 PARTIAL、核销明细为 4000 | `backend/server/src/test/java/com/erp/pur/SupplierPaymentIT.java` |
| `TC-PUR-067` | 全额核销：余额归零、状态 SETTLED | `backend/server/src/test/java/com/erp/pur/SupplierPaymentIT.java` |
| `TC-PUR-068` | ★ 一张付款单核销多张应付：明细合计 == 付款金额，两张应付各自减少 | `backend/server/src/test/java/com/erp/pur/SupplierPaymentIT.java` |
| `TC-PUR-069` | ★ 超额核销：409 PUR_PAYMENT_EXCEED_PAYABLE（不是 401），应付与明细均不变 | `backend/server/src/test/java/com/erp/pur/SupplierPaymentIT.java` |
| `TC-PUR-070` | ★ 核销守恒：明细合计 ≠ 付款金额 → 400 PUR_PAYMENT_AMOUNT_MISMATCH | `backend/server/src/test/java/com/erp/pur/SupplierPaymentIT.java` |
| `TC-PUR-071` | 非法核销请求：同一应付重复核销 400；跨供应商核销 400 PUR_PAYMENT_SUPPLIER_MISMATCH | `backend/server/src/test/java/com/erp/pur/SupplierPaymentIT.java` |
| `TC-PUR-072` | 付款幂等：同 X-Request-Id 重放只核销一次、只生成一组明细 | `backend/server/src/test/java/com/erp/pur/SupplierPaymentIT.java` |
| `TC-PUR-073` | ★ 守恒查询能发现漂移：正常时 conserved=true，篡改明细金额后变 false | `backend/server/src/test/java/com/erp/pur/SupplierPaymentIT.java` |
| `TC-RMA-001` | ★ 建单：金额由原出货行单价与折扣派生，客户/仓库继承原单，状态 DRAFT 且库存不变 | `backend/server/src/test/java/com/erp/rma/ReturnOrderIT.java` |
| `TC-RMA-002` | 原出货单还没出货（DRAFT / SUBMITTED / APPROVED）→ RMA_ORIGIN_NOT_SHIPPED，且不建单 | `backend/server/src/test/java/com/erp/rma/ReturnOrderIT.java` |
| `TC-RMA-003` | 明细行不属于原出货单 → 404，整单不建、库存不动 | `backend/server/src/test/java/com/erp/rma/ReturnOrderIT.java` |
| `TC-RMA-004` | ★★ 超退拦截：建单预检挡一次，两张各自合规的单在入库时被原子守卫再挡一次，全程无副作用 | `backend/server/src/test/java/com/erp/rma/ReturnOrderIT.java` |
| `TC-RMA-005` | 建单幂等：相同 requestId 重复提交返回同一张单，不新增记录 | `backend/server/src/test/java/com/erp/rma/ReturnOrderIT.java` |
| `TC-RMA-006` | ★ 状态机：DRAFT→SUBMITTED→APPROVED→COMPLETED 全通，跳步与终态后的动作全被拒 | `backend/server/src/test/java/com/erp/rma/ReturnOrderIT.java` |
| `TC-RMA-007` | ★ 入库：实存回补、产生 RETURN_IN 正向流水、原出货行 returned_qty 累加、状态 COMPLETED | `backend/server/src/test/java/com/erp/rma/ReturnOrderIT.java` |
| `TC-RMA-008` | ★★ 入库幂等：同 requestId 重复调用不重复回补；已完成后换新 requestId 再入库被拒 | `backend/server/src/test/java/com/erp/rma/ReturnOrderIT.java` |
| `TC-RMA-009` | 入库前作废：状态 VOIDED，库存 / 流水 / returned_qty 全部不变 | `backend/server/src/test/java/com/erp/rma/ReturnOrderIT.java` |
| `TC-RMA-010` | 入库后作废必须被拒绝（红冲属 M2.5），且已回补的库存不动 | `backend/server/src/test/java/com/erp/rma/ReturnOrderIT.java` |
| `TC-RMA-011` | ★ 指定批次与效期回补：货进到新批次，且下一次出货的 FEFO 能看见它 | `backend/server/src/test/java/com/erp/rma/ReturnOrderIT.java` |
| `TC-RMA-012` | ★ 不变量：余额净和 == 流水净和；退货数量 == RETURN_IN 流水正变动合计 | `backend/server/src/test/java/com/erp/rma/ReturnOrderIT.java` |
| `TC-RPT-035` | ★ 应收四桶：行级与汇总级 桶合计 == 余额；桶金额精确 | `backend/server/src/test/java/com/erp/rpt/ReportAgingIT.java` |
| `TC-RPT-036` | ★ 账龄边界：30/31、60/61、90/91 各自落桶；未到期与无到期日归 0-30 | `backend/server/src/test/java/com/erp/rpt/ReportAgingIT.java` |
| `TC-RPT-037` | ★ 应付四桶：以应付单业务日起算，行级/汇总级同样守恒 | `backend/server/src/test/java/com/erp/rpt/ReportAgingIT.java` |
| `TC-RPT-038` | 作废与余额为 0 的应收不进账龄；正常行照常 | `backend/server/src/test/java/com/erp/rpt/ReportAgingIT.java` |
| `TC-RPT-039` | 汇总 == 各行相加；伙伴数、余额、四桶全部对齐 | `backend/server/src/test/java/com/erp/rpt/ReportAgingIT.java` |
| `TC-RPT-040` | asOf 推移会把同一笔余额从 0-30 推到 31-60、61-90、90+ | `backend/server/src/test/java/com/erp/rpt/ReportAgingIT.java` |
| `TC-RPT-041` | 空账龄：AR/AP 都返回空 items 与全零汇总 | `backend/server/src/test/java/com/erp/rpt/ReportAgingIT.java` |
| `TC-RPT-028` | ★ 与出货统计逐格一致：同区间同仓库的数量/金额/单据数完全相同 | `backend/server/src/test/java/com/erp/rpt/ReportContributionIT.java` |
| `TC-RPT-029` | 复购 = 期间单据数 ≥ 2：两单为复购，一单不是 | `backend/server/src/test/java/com/erp/rpt/ReportContributionIT.java` |
| `TC-RPT-030` | 新客 = from 之前无已执行出货；历史客户为回头客；只有历史单的客户不出行 | `backend/server/src/test/java/com/erp/rpt/ReportContributionIT.java` |
| `TC-RPT-031` | 汇总覆盖全部客户：复购率万分比 3333、新客/回头客、总数守恒 | `backend/server/src/test/java/com/erp/rpt/ReportContributionIT.java` |
| `TC-RPT-032` | Top N 只截断 items，summary 仍覆盖全量；limit 越界 400 | `backend/server/src/test/java/com/erp/rpt/ReportContributionIT.java` |
| `TC-RPT-033` | 仓库过滤：分仓贡献之和 == 全部，且各自与同仓出货统计一致 | `backend/server/src/test/java/com/erp/rpt/ReportContributionIT.java` |
| `TC-RPT-034` | 空区间：items 为空、summary 全零、复购率 0，不除以零 | `backend/server/src/test/java/com/erp/rpt/ReportContributionIT.java` |
| `TC-RPT-042` | ★ 进货统计 CSV == 同一次服务查询逐格；逗号/引号名称可正确往返 | `backend/server/src/test/java/com/erp/rpt/ReportExportIT.java` |
| `TC-RPT-043` | ★ 出货统计 CSV == 服务查询，含 .5 尾差舍入与订单数 | `backend/server/src/test/java/com/erp/rpt/ReportExportIT.java` |
| `TC-RPT-044` | ★ 进销存 CSV 与服务导出查询逐格；名称与页面同一格式 | `backend/server/src/test/java/com/erp/rpt/ReportExportIT.java` |
| `TC-RPT-045` | ★ 经营日报/月报 CSV == 服务查询；日/月两种粒度都验证 | `backend/server/src/test/java/com/erp/rpt/ReportExportIT.java` |
| `TC-RPT-046` | ★ 周转 CSV == 服务查询：周转天数一位小数，无出库为 -，呆滞 是/否 | `backend/server/src/test/java/com/erp/rpt/ReportExportIT.java` |
| `TC-RPT-047` | ★ 客户贡献 CSV == 服务查询；Top N 行 + 全量汇总行 | `backend/server/src/test/java/com/erp/rpt/ReportExportIT.java` |
| `TC-RPT-048` | ★ 账龄 AR/AP CSV == 服务查询；明细行与汇总行逐格，桶合计守恒 | `backend/server/src/test/java/com/erp/rpt/ReportExportIT.java` |
| `TC-RPT-049` | CSV 文件形态：UTF-8 BOM、CRLF、text/csv、attachment 文件名 | `backend/server/src/test/java/com/erp/rpt/ReportExportIT.java` |
| `TC-RPT-050` | 导出遵守筛选条件：换仓库/换区间后行集变化，且仍与服务查询一致 | `backend/server/src/test/java/com/erp/rpt/ReportExportIT.java` |
| `TC-RPT-051` | 导出同样受鉴权保护：未登录 401 | `backend/server/src/test/java/com/erp/rpt/ReportExportIT.java` |
| `TC-RPT-052` | ★ 导出是只读的：跑完全部导出，单据/流水/余额/应收应付计数不变 | `backend/server/src/test/java/com/erp/rpt/ReportExportIT.java` |
| `TC-RPT-001` | 进货统计按供应商分组：数量/金额与已入库明细一致；未入库与已作废不进统计 | `backend/server/src/test/java/com/erp/rpt/ReportIT.java` |
| `TC-RPT-002` | 进货统计按 product / sku / day / month 分组：行数与合计全部对得上 | `backend/server/src/test/java/com/erp/rpt/ReportIT.java` |
| `TC-RPT-003` | 出货统计按客户分组：金额按行折扣公式；.5 尾差与 Java HALF_UP 完全一致 | `backend/server/src/test/java/com/erp/rpt/ReportIT.java` |
| `TC-RPT-004` | 出货统计按 product / sku / day / month 分组：合计一致，逐行金额等于 SAL 的行金额公式 | `backend/server/src/test/java/com/erp/rpt/ReportIT.java` |
| `TC-RPT-005` | ★ 日期闭区间 [from, to]：首末日都包含，越界一天也不含 | `backend/server/src/test/java/com/erp/rpt/ReportIT.java` |
| `TC-RPT-006` | ★ 进销存恒等式：期初+入−出=期末，且期末等于截至期末的流水净和与当前余额 | `backend/server/src/test/java/com/erp/rpt/ReportIT.java` |
| `TC-RPT-007` | 仓库过滤：只统计指定仓库，跨仓不串数；两仓之和等于不过滤 | `backend/server/src/test/java/com/erp/rpt/ReportIT.java` |
| `TC-RPT-008` | 空区间返回空 items，不是错误 | `backend/server/src/test/java/com/erp/rpt/ReportIT.java` |
| `TC-RPT-010` | ★ 与日清口径可对照：同日同仓的采购/销售数量金额，与 §3.4 日清公式重算一致 | `backend/server/src/test/java/com/erp/rpt/ReportIT.java` |
| `TC-RPT-011` | ★ 无 N+1：分组数从 1 涨到 8，每张报表仍只发一条聚合 SQL | `backend/server/src/test/java/com/erp/rpt/ReportIT.java` |
| `TC-RPT-012` | ★ 两种口径不许混：单据 biz_date vs 流水 occurred_at，跨天执行各归各的 | `backend/server/src/test/java/com/erp/rpt/ReportIT.java` |
| `TC-RPT-014` | ★ 逐日出行（含空日）：每行 期初+入−出=期末，首行期初来自期初流水，末日等于当前余额 | `backend/server/src/test/java/com/erp/rpt/ReportOperatingIT.java` |
| `TC-RPT-015` | ★ 与原始流水独立复算一致；进货/销售金额与单据对象独立复算一致（两条路径） | `backend/server/src/test/java/com/erp/rpt/ReportOperatingIT.java` |
| `TC-RPT-016` | ★ 月报按完整自然月：跨月区间首尾月恒等式成立；日合计 == 月合计 | `backend/server/src/test/java/com/erp/rpt/ReportOperatingIT.java` |
| `TC-RPT-017` | 仓库过滤：分仓期末之和 == 全部，跨仓不串数；空仓日仍出行 | `backend/server/src/test/java/com/erp/rpt/ReportOperatingIT.java` |
| `TC-RPT-018` | ★ 口径不混：2/28 的单据在 3/20 才入库，单据侧归 2/28、流水侧归 3/20 | `backend/server/src/test/java/com/erp/rpt/ReportOperatingIT.java` |
| `TC-RPT-019` | 空区间：日/月都按分桶返回 0 行，不是空数组也不是错误 | `backend/server/src/test/java/com/erp/rpt/ReportOperatingIT.java` |
| `TC-RPT-020` | 单日闭区间包含首末日；from > to 必须 400 | `backend/server/src/test/java/com/erp/rpt/ReportOperatingIT.java` |
| `TC-RPT-021` | ★ 期初/期末/出库与流水独立复算一致；周转天数 = (期初+期末)/2 × 天数 / 出库 | `backend/server/src/test/java/com/erp/rpt/ReportTurnoverIT.java` |
| `TC-RPT-022` | 周转天数保留一位小数（10.333… → 10.3）；无出库为 null，不抛除零 | `backend/server/src/test/java/com/erp/rpt/ReportTurnoverIT.java` |
| `TC-RPT-023` | 呆滞：N 天无出库；从未出库从首次流水起算；年轻库存不算呆滞 | `backend/server/src/test/java/com/erp/rpt/ReportTurnoverIT.java` |
| `TC-RPT-024` | 呆滞阈值可调：40 天前出库在 30 天口径下呆滞、60 天口径下不呆滞 | `backend/server/src/test/java/com/erp/rpt/ReportTurnoverIT.java` |
| `TC-RPT-025` | ★ 与进销存汇总同口径：同月同 SKU 的期初/期末/出库逐格一致 | `backend/server/src/test/java/com/erp/rpt/ReportTurnoverIT.java` |
| `TC-RPT-026` | 仓库过滤：只返回该仓的 SKU，分仓之和 == 全部 | `backend/server/src/test/java/com/erp/rpt/ReportTurnoverIT.java` |
| `TC-RPT-027` | 只返回期末有库存的 SKU：清空的 SKU 不出行；deadDays 越界与 from>to 均 400 | `backend/server/src/test/java/com/erp/rpt/ReportTurnoverIT.java` |
| `TC-RPT-013` | ★ 三条报表计划：事实表各只扫一次、分组在数据库完成、V6 索引可用 | `backend/server/src/test/java/com/erp/rpt/repository/ReportQueryPlanIT.java` |
| `TC-SAL-081` | 额度 0 = 不设限：大额订单可建，且不写信用快照 | `backend/server/src/test/java/com/erp/sal/CreditControlIT.java` |
| `TC-SAL-082` | ★★ 信用超额拒单：额度 5000，订单 6000 → 拒单且预占/订单/快照全部为 0 | `backend/server/src/test/java/com/erp/sal/CreditControlIT.java` |
| `TC-SAL-083` | 敞口包含未出货订单：额度 10000，先 6000 再过 5000 → 第二单被拒，第一单预占保留 | `backend/server/src/test/java/com/erp/sal/CreditControlIT.java` |
| `TC-SAL-084` | 敞口包含应收余额：6000 出货后仍未回款，再下 5000 单被拒 | `backend/server/src/test/java/com/erp/sal/CreditControlIT.java` |
| `TC-SAL-085` | 边界值：敞口 == 额度允许；超出 1 分拒绝 | `backend/server/src/test/java/com/erp/sal/CreditControlIT.java` |
| `TC-SAL-086` | 信用快照：日期来自 ClockProvider，记录额度/敞口/本单金额/订单号 | `backend/server/src/test/java/com/erp/sal/CreditControlIT.java` |
| `TC-SAL-087` | 调整额度/账期后立即生效：1000 拒 2000 单，提到 5000 后允许 | `backend/server/src/test/java/com/erp/sal/CreditControlIT.java` |
| `TC-SAL-088` | ★ 并发授信：额度 10000，两笔 8000 并发 → 只成功一笔，另一笔超额 | `backend/server/src/test/java/com/erp/sal/CreditControlIT.java` |
| `TC-SAL-089` | ★ 套装+自动选仓超额：拒单且两个仓的组件预占都为 0 | `backend/server/src/test/java/com/erp/sal/CreditControlIT.java` |
| `TC-SAL-068` | ★ 出货即生成应收：shipped=10000，balance=10000，到期日=时钟今天+30天 | `backend/server/src/test/java/com/erp/sal/ReceivableIT.java` |
| `TC-SAL-069` | ★ 部分收款：收 3000 核销 3000 → balance 7000，核销明细合计 == 收款金额 | `backend/server/src/test/java/com/erp/sal/ReceivableIT.java` |
| `TC-SAL-070` | 一张收款单核销多张应收：10000 + 5000 → 第一张结清、第二张余 5000 | `backend/server/src/test/java/com/erp/sal/ReceivableIT.java` |
| `TC-SAL-071` | ★ 核销守恒：Σ分配 3000 ≠ 收款 5000 → SAL_RECEIPT_UNBALANCED，无收款单 | `backend/server/src/test/java/com/erp/sal/ReceivableIT.java` |
| `TC-SAL-072` | ★ 超额核销：应收余额 10000，核销 10001 → SAL_RECEIPT_OVER_ALLOCATED，余额不变 | `backend/server/src/test/java/com/erp/sal/ReceivableIT.java` |
| `TC-SAL-073` | 跨客户核销：应收不属于收款客户 → SAL_RECEIVABLE_NOT_FOUND，无收款单 | `backend/server/src/test/java/com/erp/sal/ReceivableIT.java` |
| `TC-SAL-074` | 同一应收在一张收款单里出现两次 → SYS_VALIDATION_ERROR | `backend/server/src/test/java/com/erp/sal/ReceivableIT.java` |
| `TC-SAL-075` | 收款幂等：同键重放返回首次收款单，不重复核销 | `backend/server/src/test/java/com/erp/sal/ReceivableIT.java` |
| `TC-SAL-076` | ★ 退货联动：退 2 件 → returned_amount 2000、balance 8000，恒等式仍成立 | `backend/server/src/test/java/com/erp/sal/ReceivableIT.java` |
| `TC-SAL-077` | 全额收款 → SETTLED / balance 0；再核销任何金额 → OVER_ALLOCATED | `backend/server/src/test/java/com/erp/sal/ReceivableIT.java` |
| `TC-SAL-078` | ★ 已收款不可红冲：SAL_STATUS_ILLEGAL，订单/库存/应收均不变 | `backend/server/src/test/java/com/erp/sal/ReceivableIT.java` |
| `TC-SAL-079` | 未收款红冲：应收置 VOIDED、余额 0，库存回补，恒等式仍成立 | `backend/server/src/test/java/com/erp/sal/ReceivableIT.java` |
| `TC-SAL-080` | ★ 账龄：时钟推进 31 天 → 汇总 overdueAmount == balance，逾期筛选命中 | `backend/server/src/test/java/com/erp/sal/ReceivableIT.java` |
| `TC-SAL-037` | ★ 套装可用量 = min(组件可用 ÷ 配比)：A 10/2=5，B 15/3=5 → 5 | `backend/server/src/test/java/com/erp/sal/SalesBundleIT.java` |
| `TC-SAL-038` | 套装可用量取短板：A 7/2=3（向下取整），B 20/3=6 → 3 | `backend/server/src/test/java/com/erp/sal/SalesBundleIT.java` |
| `TC-SAL-046` | 跨仓汇总口径 = min(Σ组件可用 ÷ 配比)，与单仓 min 不同 | `backend/server/src/test/java/com/erp/sal/SalesBundleIT.java` |
| `TC-SAL-039` | ★ 卖 2 套 → 展开为组件行：A 4 件、B 6 件；组件被预占，套装不建库存行 | `backend/server/src/test/java/com/erp/sal/SalesBundleIT.java` |
| `TC-SAL-040` | ★★ INV-16：出货 2 套 → A 扣 4、B 扣 6；组件 shippedQty == 套数×配比 | `backend/server/src/test/java/com/erp/sal/SalesBundleIT.java` |
| `TC-SAL-041` | INV-16 聚合：同一订单两组套装（1 套 + 2 套）→ 组件累计 3 套×配比 | `backend/server/src/test/java/com/erp/sal/SalesBundleIT.java` |
| `TC-SAL-042` | ★ 套装库存不足：组件 A 只够 1 套，买 2 套 → 拒单且无任何预占 | `backend/server/src/test/java/com/erp/sal/SalesBundleIT.java` |
| `TC-SAL-043` | 停用套装不能建单：MDM_BUNDLE_DISABLED，且无预占 | `backend/server/src/test/java/com/erp/sal/SalesBundleIT.java` |
| `TC-SAL-044` | 套装定义校验：配比 <=0 / 组件重复 / 组件不存在 → 拒绝 | `backend/server/src/test/java/com/erp/sal/SalesBundleIT.java` |
| `TC-SAL-045` | 套装明细不写金额：组件行按组件零售价与折扣计价，合计 == 行金额之和 | `backend/server/src/test/java/com/erp/sal/SalesBundleIT.java` |
| `TC-SAL-090` | 套装维护：更新配比/价格、按关键字查询、停用、编码重复拒绝 | `backend/server/src/test/java/com/erp/sal/SalesBundleIT.java` |
| `TC-SAL-091` | 建单行校验：SKU 与套装二选一；负单价拒绝且无预占 | `backend/server/src/test/java/com/erp/sal/SalesBundleIT.java` |
| `TC-SAL-001` | 建单即预占：单据为 DRAFT，但库存已被锁住（实存不变、预占增加） | `backend/server/src/test/java/com/erp/sal/SalesOrderIT.java` |
| `TC-SAL-002` | 多行建单：每行独立预占（requestId 按行拆分，不会互相幂等吞掉） | `backend/server/src/test/java/com/erp/sal/SalesOrderIT.java` |
| `TC-SAL-003` | ★★ 库存不足时整单回滚：3 行明细第 3 行不足 → 前 2 行的预占必须一起回滚 | `backend/server/src/test/java/com/erp/sal/SalesOrderIT.java` |
| `TC-SAL-004` | 建单幂等：相同 requestId 重复提交返回同一张单，不重复预占 | `backend/server/src/test/java/com/erp/sal/SalesOrderIT.java` |
| `TC-SAL-005` | ★ 折扣按整数万分比计算：9 折 12900 分 × 2 = 23220 分（无浮点尾巴） | `backend/server/src/test/java/com/erp/sal/SalesOrderIT.java` |
| `TC-SAL-006` | 折扣金额四舍五入到分：3 × 333 × 9000/10000 = 899.1 → 899 | `backend/server/src/test/java/com/erp/sal/SalesOrderIT.java` |
| `TC-SAL-007` | 折扣率越界（0 / 10001）必须被拒绝 | `backend/server/src/test/java/com/erp/sal/SalesOrderIT.java` |
| `TC-SAL-008` | ★ 出货确认：预占转出库，实存与预占同时减少，状态 COMPLETED，INV-2 成立 | `backend/server/src/test/java/com/erp/sal/SalesOrderIT.java` |
| `TC-SAL-009` | ★★ FEFO 拆批次端到端：库存全部带批次时建单自动分段预占，出货按效期扣减并拆多条流水 | `backend/server/src/test/java/com/erp/sal/SalesOrderIT.java` |
| `TC-SAL-010` | ★ 跳过审核直接出货（DRAFT）必须被拒绝，库存不动 | `backend/server/src/test/java/com/erp/sal/SalesOrderIT.java` |
| `TC-SAL-011` | ★ 作废未出货单据必须释放预占（否则库存被永久锁死） | `backend/server/src/test/java/com/erp/sal/SalesOrderIT.java` |
| `TC-SAL-012` | ★ 已出货单据红冲：按原批次回补实存、写 SALES_REVERSAL、状态 VOIDED | `backend/server/src/test/java/com/erp/sal/SalesOrderIT.java` |
| `TC-SAL-013` | 客户或仓库停用后不能再建出货单 | `backend/server/src/test/java/com/erp/sal/SalesOrderIT.java` |
| `TC-SAL-014` | 出货单不存在：404 语义 | `backend/server/src/test/java/com/erp/sal/SalesOrderIT.java` |
| `TC-SAL-015` | ★ 端到端：进货入库 → 出货 → 两单流水可各自反查，且全库 INV-2 成立 | `backend/server/src/test/java/com/erp/sal/SalesOrderIT.java` |
| `TC-SAL-016` | ★★ 出货后预占记录必须转为 CONSUMED：不允许永远停在 ACTIVE（M1 真实捕获的缺陷） | `backend/server/src/test/java/com/erp/sal/SalesOrderIT.java` |
| `TC-SAL-017` | 作废后预占记录转为 RELEASED，且 ACTIVE 合计归零（与余额行一致） | `backend/server/src/test/java/com/erp/sal/SalesOrderIT.java` |
| `TC-SAL-018` | ★ 红冲幂等：同 X-Request-Id 重放不产生第二套流水；已作废后换新键被拒 | `backend/server/src/test/java/com/erp/sal/SalesOrderReversalIT.java` |
| `TC-SAL-019` | ★ 锁账期拒绝红冲：SET_PERIOD_CLOSED，单据状态/库存/流水全部不动 | `backend/server/src/test/java/com/erp/sal/SalesOrderReversalIT.java` |
| `TC-SAL-020` | ★ 有退货的出货单不能红冲（退货已回补过库存，再全额红冲会双补） | `backend/server/src/test/java/com/erp/sal/SalesOrderReversalIT.java` |
| `TC-SAL-021` | ★ 红冲后原单上待入库的退货单不能再入库（否则会二次回补库存） | `backend/server/src/test/java/com/erp/sal/SalesOrderReversalIT.java` |
| `TC-SAL-022` | ★ PARTIAL 红冲：已出货行回补实存，未出货行的 ACTIVE 预占必须一起释放 | `backend/server/src/test/java/com/erp/sal/SalesOrderReversalIT.java` |
| `TC-SAL-023` | ★ 并发红冲（异键）：只有一个成功，库存只回补一次 | `backend/server/src/test/java/com/erp/sal/SalesOrderReversalIT.java` |
| `TC-SAL-025` | ★ 建报价单：DRAFT、金额按万分比折扣计算、不预占库存、不生成出货单 | `backend/server/src/test/java/com/erp/sal/SalesQuoteIT.java` |
| `TC-SAL-026` | 报价建单幂等：同 requestId 返回同一张报价单，不重复建行 | `backend/server/src/test/java/com/erp/sal/SalesQuoteIT.java` |
| `TC-SAL-027` | 报价校验：空明细/数量非正/重复 SKU/折扣越界/有效期早于报价日 → 400，无残留 | `backend/server/src/test/java/com/erp/sal/SalesQuoteIT.java` |
| `TC-SAL-028` | 报价状态机：DRAFT→SUBMITTED→APPROVED；DRAFT 直接审核非法 | `backend/server/src/test/java/com/erp/sal/SalesQuoteIT.java` |
| `TC-SAL-029` | ★ 转化：行不丢（数量/单价/折扣/金额一致），调用建单预占，报价单 COMPLETED | `backend/server/src/test/java/com/erp/sal/SalesQuoteIT.java` |
| `TC-SAL-030` | 转化幂等：同键返回首次订单且不重复预占；新键再转化 → 409 | `backend/server/src/test/java/com/erp/sal/SalesQuoteIT.java` |
| `TC-SAL-031` | 未审核报价不可转化：DRAFT/SUBMITTED → 409，库存不变 | `backend/server/src/test/java/com/erp/sal/SalesQuoteIT.java` |
| `TC-SAL-032` | 已过期报价不可转化：报价保持 APPROVED，无订单/预占 | `backend/server/src/test/java/com/erp/sal/SalesQuoteIT.java` |
| `TC-SAL-033` | ★ 转化时库存不足：整单回滚，报价单保持 APPROVED，无订单/预占 | `backend/server/src/test/java/com/erp/sal/SalesQuoteIT.java` |
| `TC-SAL-034` | ★ 转化时信用超额：报价保持 APPROVED，无订单/预占（FR-SAL-09 联动） | `backend/server/src/test/java/com/erp/sal/SalesQuoteIT.java` |
| `TC-SAL-035` | 转化省略 warehouseId：按优先级自动选仓/拆单，报价单完成 | `backend/server/src/test/java/com/erp/sal/SalesQuoteIT.java` |
| `TC-SAL-036` | 作废已审核报价：状态 VOIDED，之后不能转化 | `backend/server/src/test/java/com/erp/sal/SalesQuoteIT.java` |
| `TC-SAL-055` | ★ 计划守恒：单仓订单生成一张发货单，Σ拣货行 == 未出量 | `backend/server/src/test/java/com/erp/sal/ShipmentIT.java` |
| `TC-SAL-056` | 计划幂等：同键重放返回首次计划，不重复拆行/不重复占用预占 | `backend/server/src/test/java/com/erp/sal/ShipmentIT.java` |
| `TC-SAL-057` | 不同键重复计划同一批预占 → SAL_STATUS_ILLEGAL（防重复占用） | `backend/server/src/test/java/com/erp/sal/ShipmentIT.java` |
| `TC-SAL-058` | ★ 扫码累计：部分扫码 → PICKING；超过计划 → SAL_SHIPMENT_OVER_PICK 且数量不变 | `backend/server/src/test/java/com/erp/sal/ShipmentIT.java` |
| `TC-SAL-059` | 扫码条码校验：匹配则累计，不匹配必须拒绝且不累计 | `backend/server/src/test/java/com/erp/sal/ShipmentIT.java` |
| `TC-SAL-060` | 未全部扫码不能确认发货；扫满后状态 PICKED | `backend/server/src/test/java/com/erp/sal/ShipmentIT.java` |
| `TC-SAL-061` | ★ 确认发货：实存扣减、预占转 CONSUMED、订单 COMPLETED、流水可追溯、INV-2 成立 | `backend/server/src/test/java/com/erp/sal/ShipmentIT.java` |
| `TC-SAL-062` | 确认幂等：同键重放不重复扣减；已完成换新键 → 409 | `backend/server/src/test/java/com/erp/sal/ShipmentIT.java` |
| `TC-SAL-063` | ★ 跨仓拆单发货：两张发货单分别确认，两仓各自扣减且总量守恒 | `backend/server/src/test/java/com/erp/sal/ShipmentIT.java` |
| `TC-SAL-064` | 部分发货：先确认一张仓单 → 订单 PARTIAL；再确认另一张 → COMPLETED | `backend/server/src/test/java/com/erp/sal/ShipmentIT.java` |
| `TC-SAL-065` | 状态守卫：DRAFT 不能确认；COMPLETED 不能再扫码 | `backend/server/src/test/java/com/erp/sal/ShipmentIT.java` |
| `TC-SAL-066` | 订单已完成后再生成发货计划 → SAL_STATUS_ILLEGAL | `backend/server/src/test/java/com/erp/sal/ShipmentIT.java` |
| `TC-SAL-067` | ★ 确认发货同步登记应收：金额 == 本次出货金额，余额 == 出货 − 回款，到期日走 ClockProvider | `backend/server/src/test/java/com/erp/sal/ShipmentIT.java` |
| `TC-SAL-047` | ★ 单仓足够不拆：高优先仓有 20 件，买 15 → 只有一个仓的分配与一张发货单 | `backend/server/src/test/java/com/erp/sal/WarehouseAllocationIT.java` |
| `TC-SAL-048` | ★ 单仓不足才拆：高优先仓 10，普通仓 10，买 15 → 10+5，低优先仓不动 | `backend/server/src/test/java/com/erp/sal/WarehouseAllocationIT.java` |
| `TC-SAL-049` | 优先级顺序：高优先仓先吃满，再吃普通仓（两仓各 5 件买 10） | `backend/server/src/test/java/com/erp/sal/WarehouseAllocationIT.java` |
| `TC-SAL-050` | 所有启用仓合计不足：整单回滚，无订单/预占/发货计划 | `backend/server/src/test/java/com/erp/sal/WarehouseAllocationIT.java` |
| `TC-SAL-051` | 停用仓不参与自动选仓：高优先仓停用且有 100 件，仍从普通仓出 | `backend/server/src/test/java/com/erp/sal/WarehouseAllocationIT.java` |
| `TC-SAL-052` | ★ 套装自动选仓：按每仓成套量吃量，组件同仓成套（高优 2 套 + 普通 2 套） | `backend/server/src/test/java/com/erp/sal/WarehouseAllocationIT.java` |
| `TC-SAL-053` | 多行自动选仓：每行各自守恒，互不吞量 | `backend/server/src/test/java/com/erp/sal/WarehouseAllocationIT.java` |
| `TC-SAL-054` | ★ 跨仓拆单：订单 15 拆成 10+5 → 两张发货单，Σ拣货行 == 原订单行数量 | `backend/server/src/test/java/com/erp/sal/WarehouseAllocationIT.java` |
| `TC-SET-001` | ★ 日清（单仓）：采购/销售/退货的数量与金额按单据 biz_date 正确汇总 | `backend/server/src/test/java/com/erp/set/SettlementIT.java` |
| `TC-SET-002` | 日清省略 warehouseId：按仓库返回 items，再给出跨仓 totals，跨仓不串数 | `backend/server/src/test/java/com/erp/set/SettlementIT.java` |
| `TC-SET-003` | ★ 日清幂等：同日重复执行结果一致且仍只有一行；重跑是覆盖而不是累加 | `backend/server/src/test/java/com/erp/set/SettlementIT.java` |
| `TC-SET-004` | ★★ 月结锁账后，PUR / SAL / RMA 建单全部返回 SET_PERIOD_CLOSED 且不落库 | `backend/server/src/test/java/com/erp/set/SettlementIT.java` |
| `TC-SET-005` | ★★ 锁账后入库 / 出货 / 退货入库全部被拒，库存、累计量与流水一条都不动 | `backend/server/src/test/java/com/erp/set/SettlementIT.java` |
| `TC-SET-012` | 已锁账期再执行日清 → SET_PERIOD_CLOSED，且已锁的日清行一个字节都不改 | `backend/server/src/test/java/com/erp/set/SettlementIT.java` |
| `TC-SET-006` | ★ 月结：期间汇总正确、状态置 CLOSED、closedAt 来自注入时钟；OPEN 行走 version 乐观锁更新 | `backend/server/src/test/java/com/erp/set/SettlementIT.java` |
| `TC-SET-007` | ★★ 月结幂等与漂移：重复关闭返回同一汇总 duplicate=true；篡改过日清/汇总必须报 SET_ALREADY_SETTLED | `backend/server/src/test/java/com/erp/set/SettlementIT.java` |
| `TC-SET-008` | ★ 跨月边界：1/31 与 2/1 的单据归属不同账期，锁 1 月不影响 2 月 | `backend/server/src/test/java/com/erp/set/SettlementIT.java` |
| `TC-SET-009` | ★ 跨年与闰月：2024-02-29 / 2024-12-31 / 2025-01-01 各自归入正确账期 | `backend/server/src/test/java/com/erp/set/SettlementIT.java` |
| `TC-SET-010` | ★ 对账平衡：差额 0、balanced=true；账期不存在或仍为 OPEN 时给 SET_PERIOD_NOT_CLOSED | `backend/server/src/test/java/com/erp/set/SettlementIT.java` |
| `TC-SET-011` | 对账不平：篡改期间汇总 → SET_RECONCILE_MISMATCH，details 带 period/generalTotal/detailTotal/diff | `backend/server/src/test/java/com/erp/set/SettlementIT.java` |
| `TC-SET-013` | ★★ 并发日清：8 个线程同时清同一天，只产生一行/仓，金额一份不翻倍 | `backend/server/src/test/java/com/erp/set/SettlementIT.java` |
| `TC-SET-014` | ★ 金额舍入：奇数单价 × 折扣 9000 与 SAL 同一公式（HALF_UP），尾差不为负 | `backend/server/src/test/java/com/erp/set/SettlementIT.java` |
| `TC-DOC-007` | 单号格式：前缀 + yyyyMMdd + 4 位流水，按天重置 | `backend/server/src/test/java/com/erp/shared/document/DocumentNumberGeneratorIT.java` |
| `TC-DOC-008` | ★ 并发建单不重号：50 线程同时取号，单号必须全不重复且恰好 50 个 | `backend/server/src/test/java/com/erp/shared/document/DocumentNumberGeneratorIT.java` |
| `TC-DOC-009` | 取号序列持久化在数据库中：不依赖进程内计数器（重启不重号） | `backend/server/src/test/java/com/erp/shared/document/DocumentNumberGeneratorIT.java` |
| `TC-DOC-010` | 流水号超过 4 位后自然进位，不被截断（第 10000 号） | `backend/server/src/test/java/com/erp/shared/document/DocumentNumberGeneratorIT.java` |
| `TC-DOC-001` | ★ 穷举 7×7=49 种状态组合：合法转移必须通过，非法转移必须全部被拒绝 | `backend/server/src/test/java/com/erp/shared/document/DocumentStatusMachineTest.java` |
| `TC-DOC-002` | ★ 参数化穷举：非法转移抛 PUR_STATUS_ILLEGAL 且消息含当前态与目标态 | `backend/server/src/test/java/com/erp/shared/document/DocumentStatusMachineTest.java` |
| `TC-DOC-003` | 终态不可再转移：COMPLETED / VOIDED 没有任何出路 | `backend/server/src/test/java/com/erp/shared/document/DocumentStatusMachineTest.java` |
| `TC-DOC-004` | 可执行状态只有 APPROVED / PARTIAL：DRAFT 与 SUBMITTED 不得触碰库存 | `backend/server/src/test/java/com/erp/shared/document/DocumentStatusMachineTest.java` |
| `TC-DOC-005` | 未结状态判定：只有终态被视为已结 | `backend/server/src/test/java/com/erp/shared/document/DocumentStatusMachineTest.java` |
| `TC-DOC-006` | PARTIAL->PARTIAL 自环合法：分批入库/出货可以多次进入部分状态 | `backend/server/src/test/java/com/erp/shared/document/DocumentStatusMachineTest.java` |
| `TC-DOC-011` | ★ 红冲白名单：仅 PARTIAL / COMPLETED 可补偿作废，目标恒为 VOIDED | `backend/server/src/test/java/com/erp/shared/document/DocumentStatusMachineTest.java` |
| `TC-DOC-012` | ★ 红冲不改常规状态机：COMPLETED 在 7×7 表里仍是终态（月结 isOpen 口径不被污染） | `backend/server/src/test/java/com/erp/shared/document/DocumentStatusMachineTest.java` |
| `TC-CST-001` | 行金额 = 数量 × 单价 × 折扣万分比 ÷ 10000 | `backend/server/src/test/java/com/erp/shared/money/MoneyMathTest.java` |
| `TC-CST-002` | 行金额除法按 HALF_UP 落到分 | `backend/server/src/test/java/com/erp/shared/money/MoneyMathTest.java` |
| `TC-CST-003` | mulDivHalfUp：正数按 HALF_UP 舍入 | `backend/server/src/test/java/com/erp/shared/money/MoneyMathTest.java` |
| `TC-CST-004` | mulDivHalfUp：负数按远离零方向 HALF_UP | `backend/server/src/test/java/com/erp/shared/money/MoneyMathTest.java` |
| `TC-CST-005` | 除数为 0 / 非法入参必须拒绝 | `backend/server/src/test/java/com/erp/shared/money/MoneyMathTest.java` |
| `TC-TNT-064` | 查询/更新/批量的每个重载都经过改写层（空操作也能到达） | `backend/server/src/test/java/com/erp/shared/tenant/TenantJdbcSurfaceIT.java` |
| `TC-TNT-065` | ★ PreparedStatementCreator 族必须大声失败（拿不到 SQL 文本就不能保证过滤） | `backend/server/src/test/java/com/erp/shared/tenant/TenantJdbcSurfaceIT.java` |
| `TC-TNT-066` | TenantJdbc 辅助与 TenantDirectory：查询已存在/不存在的租户，诊断上下文 | `backend/server/src/test/java/com/erp/shared/tenant/TenantJdbcSurfaceIT.java` |
| `TC-TNT-060` | ★ 无法安全改写的 INSERT 形态必须抛，而不是静默返回原 SQL | `backend/server/src/test/java/com/erp/shared/tenant/TenantSqlDiagnosticsTest.java` |
| `TC-TNT-061` | 非业务表 / 无表语句 / 空语句原样返回；非法租户直接拒绝 | `backend/server/src/test/java/com/erp/shared/tenant/TenantSqlDiagnosticsTest.java` |
| `TC-TNT-062` | ★ 每个 *_request_id 列都生成正确的部分索引谓词（具体列先于通用列） | `backend/server/src/test/java/com/erp/shared/tenant/TenantSqlDiagnosticsTest.java` |
| `TC-TNT-063` | 诊断入口：别名 / RETURNING / 无尾巴 / touchesBusinessTables 的全部分支 | `backend/server/src/test/java/com/erp/shared/tenant/TenantSqlDiagnosticsTest.java` |
| `TC-TNT-001` | 按 id 直查：谓词追加在 WHERE 之后 | `backend/server/src/test/java/com/erp/shared/tenant/TenantSqlTest.java` |
| `TC-TNT-002` | 无 WHERE 的列表查询：自动补 WHERE 1 = 1 再追加 | `backend/server/src/test/java/com/erp/shared/tenant/TenantSqlTest.java` |
| `TC-TNT-003` | LIMIT/OFFSET 在谓词之后（不会把占位符排到 LIMIT 后面） | `backend/server/src/test/java/com/erp/shared/tenant/TenantSqlTest.java` |
| `TC-TNT-004` | ★ 联表：两侧各自带谓词（靠别名限定，不串味） | `backend/server/src/test/java/com/erp/shared/tenant/TenantSqlTest.java` |
| `TC-TNT-005` | ★ LEFT JOIN：右表谓词写进 ON（保住外连接语义），左表谓词仍在 WHERE | `backend/server/src/test/java/com/erp/shared/tenant/TenantSqlTest.java` |
| `TC-TNT-006` | 聚合统计：GROUP BY 在谓词之后 | `backend/server/src/test/java/com/erp/shared/tenant/TenantSqlTest.java` |
| `TC-TNT-007` | ★ WITH + DISTINCT ON + 子查询：CTE 内外都要有谓词 | `backend/server/src/test/java/com/erp/shared/tenant/TenantSqlTest.java` |
| `TC-TNT-008` | ★ 派生表（FROM (SELECT …)）里的业务表也要被过滤 | `backend/server/src/test/java/com/erp/shared/tenant/TenantSqlTest.java` |
| `TC-TNT-009` | FOR UPDATE 在谓词之后（否则锁的范围会跨租户） | `backend/server/src/test/java/com/erp/shared/tenant/TenantSqlTest.java` |
| `TC-TNT-010` | EXISTS 子查询：内层业务表也带谓词 | `backend/server/src/test/java/com/erp/shared/tenant/TenantSqlTest.java` |
| `TC-TNT-011` | UNION 的两个分支各自带谓词（谓词留在各自分支内，不跨作用域） | `backend/server/src/test/java/com/erp/shared/tenant/TenantSqlTest.java` |
| `TC-TNT-030` | ★ UNION ALL + 第二分支联表：每个分支按各自的表追加别名谓词 | `backend/server/src/test/java/com/erp/shared/tenant/TenantSqlTest.java` |
| `TC-TNT-049` | ★ LEFT JOIN 的右表谓词进 ON（写进 WHERE 会把外连接退化成内连接） | `backend/server/src/test/java/com/erp/shared/tenant/TenantSqlTest.java` |
| `TC-TNT-012` | 非业务表（tenants / flyway_schema_history）不被改写 | `backend/server/src/test/java/com/erp/shared/tenant/TenantSqlTest.java` |
| `TC-TNT-013` | 单行 VALUES：列清单加一列、行尾加一个字面量 | `backend/server/src/test/java/com/erp/shared/tenant/TenantSqlTest.java` |
| `TC-TNT-014` | ★ 多行 VALUES：每一行都追加（只加第一行会让第二行落错租户） | `backend/server/src/test/java/com/erp/shared/tenant/TenantSqlTest.java` |
| `TC-TNT-015` | ★ 占位符个数不变（字面量注入的核心性质） | `backend/server/src/test/java/com/erp/shared/tenant/TenantSqlTest.java` |
| `TC-TNT-016` | RETURNING id 不被破坏（取主键的路径必须仍然可用） | `backend/server/src/test/java/com/erp/shared/tenant/TenantSqlTest.java` |
| `TC-TNT-017` | ★ ON CONFLICT 的目标列表也要前缀 tenant_id（复合唯一改了，目标必须跟着改） | `backend/server/src/test/java/com/erp/shared/tenant/TenantSqlTest.java` |
| `TC-TNT-018` | ON CONFLICT DO NOTHING（无目标）不改动 | `backend/server/src/test/java/com/erp/shared/tenant/TenantSqlTest.java` |
| `TC-TNT-019` | ★ INSERT … SELECT：列清单补列、SELECT 列表补值，源表也被过滤 | `backend/server/src/test/java/com/erp/shared/tenant/TenantSqlTest.java` |
| `TC-TNT-050` | ★ 已显式带 tenant_id 的 INSERT，仍要修 ON CONFLICT 目标（否则复合唯一匹配不上） | `backend/server/src/test/java/com/erp/shared/tenant/TenantSqlTest.java` |
| `TC-TNT-051` | ★ 发货计划的 request_id 幂等目标补 tenant_id，并保留（非部分）索引可用的谓词 | `backend/server/src/test/java/com/erp/shared/tenant/TenantSqlTest.java` |
| `TC-TNT-052` | ★ INSERT … SELECT + ON CONFLICT：SELECT 列表与 ON CONFLICT 之间必须有空白（防 1ON） | `backend/server/src/test/java/com/erp/shared/tenant/TenantSqlTest.java` |
| `TC-TNT-020` | ★ 拒绝改写：INSERT 缺列清单 → 抛（不能静默放过一条无租户的写） | `backend/server/src/test/java/com/erp/shared/tenant/TenantSqlTest.java` |
| `TC-TNT-021` | UPDATE 带 WHERE：谓词追加在其后 | `backend/server/src/test/java/com/erp/shared/tenant/TenantSqlTest.java` |
| `TC-TNT-022` | ★ 无 WHERE 的 UPDATE：补 WHERE 1 = 1（否则会更新全库所有租户的行） | `backend/server/src/test/java/com/erp/shared/tenant/TenantSqlTest.java` |
| `TC-TNT-023` | DELETE FROM：同法 | `backend/server/src/test/java/com/erp/shared/tenant/TenantSqlTest.java` |
| `TC-TNT-024` | ★ TRUNCATE 是 DDL，不改写（造数工具要能清全库） | `backend/server/src/test/java/com/erp/shared/tenant/TenantSqlTest.java` |
| `TC-TNT-025` | ★ JdbcTemplate 层改过之后，DataSource 层再过一遍是 no-op | `backend/server/src/test/java/com/erp/shared/tenant/TenantSqlTest.java` |
| `TC-TNT-026` | ★ 手工写了谓词的 SQL 不会被重复追加（当前租户断言） | `backend/server/src/test/java/com/erp/shared/tenant/TenantSqlTest.java` |
| `TC-TNT-027` | ★ 只给两张表中一张加了谓词时，仍会被补上（不能被误判成已完成） | `backend/server/src/test/java/com/erp/shared/tenant/TenantSqlTest.java` |
| `TC-TNT-028` | ★ 没有绑定租户 → 抛 SYS_TENANT_CONTEXT_MISSING，绝不回落默认租户 | `backend/server/src/test/java/com/erp/shared/tenant/TenantSqlTest.java` |
| `TC-TNT-029` | 系统旁路上下文也不能走业务查询（没有确定的租户归属） | `backend/server/src/test/java/com/erp/shared/tenant/TenantSqlTest.java` |
| `TC-SYS-001` | ★ /actuator/prometheus 返回 200 且匿名可访问（抓取器不带 JWT） | `backend/server/src/test/java/com/erp/sys/ActuatorPrometheusIT.java` |
| `TC-SYS-002` | ★ 端点含自定义业务指标：erp_order_placed_total / erp_order_failed_total / erp_audit_* | `backend/server/src/test/java/com/erp/sys/ActuatorPrometheusIT.java` |
| `TC-SYS-003` | ★ 指标暴露格式合法：每个指标都有 HELP/TYPE 且带 application 标签 | `backend/server/src/test/java/com/erp/sys/ActuatorPrometheusIT.java` |
| `TC-SYS-004` | ★ 指标端点不会因为对账差额重复记录而 500（Gauge 重复注册的回归用例） | `backend/server/src/test/java/com/erp/sys/ActuatorPrometheusIT.java` |
| `TC-SYS-005` | health 端点仍可匿名访问（改管理端点时不得误伤探针） | `backend/server/src/test/java/com/erp/sys/ActuatorPrometheusIT.java` |
| `TC-SYS-006` | 审计表行数 gauge 随真实写入增长（审计链路没停的看板信号） | `backend/server/src/test/java/com/erp/sys/ActuatorPrometheusIT.java` |
| `TC-SYS-007` | 审计指标计数随写入递增（audit_written 是审计链路自身的健康信号） | `backend/server/src/test/java/com/erp/sys/ActuatorPrometheusIT.java` |
| `TC-SYS-028` | ★ INV-15：每个写接口至少产生 1 条 audit_logs（漏一个即红） | `backend/server/src/test/java/com/erp/sys/AuditCompletenessIT.java` |
| `TC-SYS-029` | ★ INV-15 下界：审计行数必须 ≥ 枚举出的写接口数 | `backend/server/src/test/java/com/erp/sys/AuditCompletenessIT.java` |
| `TC-SYS-030` | ★ 枚举器自检：只枚举写方法、排除 __test__ 后门、不遗漏已知写接口 | `backend/server/src/test/java/com/erp/sys/AuditCompletenessIT.java` |
| `TC-SYS-031` | ★ 后门前缀 /__test__/* 的写请求不产生审计（注册真实后门，删掉排除逻辑即红） | `backend/server/src/test/java/com/erp/sys/AuditCompletenessIT.java` |
| `TC-SYS-032` | ★ 审计写入失败必须返回 -1 且不抛异常（审计绝不影响业务） | `backend/server/src/test/java/com/erp/sys/AuditCompletenessIT.java` |
| `TC-SYS-033` | ★ 成功的写操作留痕：建商品 → 1 条 CREATE/PRODUCT/SUCCESS | `backend/server/src/test/java/com/erp/sys/AuditCoreIT.java` |
| `TC-SYS-034` | ★ 失败的写操作必须留痕：业务 404 也要有 1 条 result=FAIL 的审计 | `backend/server/src/test/java/com/erp/sys/AuditCoreIT.java` |
| `TC-SYS-035` | ★ 业务失败回滚后审计仍在（REQUIRES_NEW 独立事务的行为级证明） | `backend/server/src/test/java/com/erp/sys/AuditCoreIT.java` |
| `TC-SYS-036` | 越权被拒（403）也必须留痕 —— 那是安全事件 | `backend/server/src/test/java/com/erp/sys/AuditCoreIT.java` |
| `TC-SYS-037` | 匿名写操作留痕：actorId 为空但 actorName 有值 | `backend/server/src/test/java/com/erp/sys/AuditCoreIT.java` |
| `TC-SYS-038` | ★ 前后值可还原：before/after 能读出商品名的变更前后值 | `backend/server/src/test/java/com/erp/sys/AuditCoreIT.java` |
| `TC-SYS-039` | 首次创建：before_json 为 null（如实表达「之前不存在」） | `backend/server/src/test/java/com/erp/sys/AuditCoreIT.java` |
| `TC-SYS-040` | ★ 快照片段首尾相接：第 2 次变更的 before == 第 1 次变更的 after | `backend/server/src/test/java/com/erp/sys/AuditCoreIT.java` |
| `TC-SYS-041` | ★ 不可篡改：UPDATE audit_logs 被数据库触发器拒绝 | `backend/server/src/test/java/com/erp/sys/AuditCoreIT.java` |
| `TC-SYS-042` | ★ 不可篡改：DELETE audit_logs 被数据库触发器拒绝 | `backend/server/src/test/java/com/erp/sys/AuditCoreIT.java` |
| `TC-SYS-043` | ★ traceId 贯穿：请求头 → 响应头 → 审计行，三处同值 | `backend/server/src/test/java/com/erp/sys/AuditCoreIT.java` |
| `TC-SYS-044` | 无 X-Trace-Id 时自动生成，且响应头与审计行一致 | `backend/server/src/test/java/com/erp/sys/AuditCoreIT.java` |
| `TC-SYS-045` | 按 traceId 能捞出该请求的全部审计（查询接口 /audits/by-trace） | `backend/server/src/test/java/com/erp/sys/AuditCoreIT.java` |
| `TC-SYS-046` | ★ 审计不得记录密码明文（/auth/login 的 after_json 里没有 password 原文） | `backend/server/src/test/java/com/erp/sys/AuditCoreIT.java` |
| `TC-SYS-047` | 动作按路径语义归类：/disable → DISABLE，而不是 POST → CREATE | `backend/server/src/test/java/com/erp/sys/AuditCoreIT.java` |
| `TC-SYS-048` | 单据动作归类：进货单 submit/approve/receive 各有独立动作，不混成 CREATE | `backend/server/src/test/java/com/erp/sys/AuditCoreIT.java` |
| `TC-SYS-049` | biz_ref_no 从响应体取得：建单审计能挂上单据号 | `backend/server/src/test/java/com/erp/sys/AuditCoreIT.java` |
| `TC-SYS-050` | ★ 取不到 biz_ref_no 时写 null 且业务不受影响（审计绝不影响业务） | `backend/server/src/test/java/com/erp/sys/AuditCoreIT.java` |
| `TC-SYS-051` | ★ 按人查：actorName 过滤只返回该账号的审计 | `backend/server/src/test/java/com/erp/sys/AuditQueryIT.java` |
| `TC-SYS-052` | ★ 按操作类型查：action=CREATE 只返回建单审计 | `backend/server/src/test/java/com/erp/sys/AuditQueryIT.java` |
| `TC-SYS-053` | ★ 按单据查：bizRefNo 过滤只返回该单据的审计 | `backend/server/src/test/java/com/erp/sys/AuditQueryIT.java` |
| `TC-SYS-054` | ★ 按时间查：未来时间区间的结果为 0，过去区间的结果 > 0 | `backend/server/src/test/java/com/erp/sys/AuditQueryIT.java` |
| `TC-SYS-055` | 组合过滤：action=DISABLE 且 result=SUCCESS 同时生效 | `backend/server/src/test/java/com/erp/sys/AuditQueryIT.java` |
| `TC-SYS-056` | 按资源类型与资源 id 过滤：能定位到具体某个商品的全部审计 | `backend/server/src/test/java/com/erp/sys/AuditQueryIT.java` |
| `TC-SYS-057` | 单据轨迹按时间正序：建单在提交之前 | `backend/server/src/test/java/com/erp/sys/AuditQueryIT.java` |
| `TC-SYS-058` | 审计详情：按 id 取回单条，字段完整 | `backend/server/src/test/java/com/erp/sys/AuditQueryIT.java` |
| `TC-SYS-059` | 审计详情不存在：404 + SYS_AUDIT_NOT_FOUND（不是 500） | `backend/server/src/test/java/com/erp/sys/AuditQueryIT.java` |
| `TC-SYS-060` | 非法 action 取值：400 + SYS_AUDIT_QUERY_INVALID（客户端问题不报成 500） | `backend/server/src/test/java/com/erp/sys/AuditQueryIT.java` |
| `TC-SYS-061` | 非法 result 取值：400 + SYS_AUDIT_QUERY_INVALID | `backend/server/src/test/java/com/erp/sys/AuditQueryIT.java` |
| `TC-SYS-062` | 时间区间倒置（from >= to）：400 + SYS_AUDIT_QUERY_INVALID | `backend/server/src/test/java/com/erp/sys/AuditQueryIT.java` |
| `TC-SYS-063` | ★ 无 sys:read 权限：403 IAM_FORBIDDEN（审计表里有全公司操作记录） | `backend/server/src/test/java/com/erp/sys/AuditQueryIT.java` |
| `TC-SYS-064` | 未登录访问审计：401 IAM_UNAUTHENTICATED | `backend/server/src/test/java/com/erp/sys/AuditQueryIT.java` |
| `TC-SYS-065` | VIEWER 持有 sys:read：能读审计（只读角色也应有审计可见性） | `backend/server/src/test/java/com/erp/sys/AuditQueryIT.java` |
| `TC-SYS-066` | ★ 审计查询只有 GET：对 /audits 发 POST/PUT/DELETE 一律 405，永不写审计表 | `backend/server/src/test/java/com/erp/sys/AuditQueryIT.java` |
| `TC-SYS-067` | 分页：size 超上限被钳制到 200，page 越界被钳制到 1 | `backend/server/src/test/java/com/erp/sys/AuditQueryIT.java` |
| `TC-SYS-068` | 列表默认按时间倒序：第一条是最新的审计 | `backend/server/src/test/java/com/erp/sys/AuditQueryIT.java` |
| `TC-SYS-080` | ★ 下单数随成功建单递增（指标真的在计数，不是只注册了个空名字） | `backend/server/src/test/java/com/erp/sys/MetricsAndTraceIT.java` |
| `TC-SYS-081` | ★ 失败数按错误码分维度：非法参数产生 errorCode 维度并 +1 | `backend/server/src/test/java/com/erp/sys/MetricsAndTraceIT.java` |
| `TC-SYS-082` | ★ 审计行数 gauge 与实际行数一致（审计链路没停的看板信号） | `backend/server/src/test/java/com/erp/sys/MetricsAndTraceIT.java` |
| `TC-SYS-083` | 对账差额指标：erp_reconcile_diff_abs 可读且非负 | `backend/server/src/test/java/com/erp/sys/MetricsAndTraceIT.java` |
| `TC-SYS-084` | ★ traceId 贯穿日志：按 traceId 能捞出该请求的全部日志行 | `backend/server/src/test/java/com/erp/sys/MetricsAndTraceIT.java` |
| `TC-SYS-085` | 不同请求的 traceId 互不串味（并发安全：MDC 用完即清） | `backend/server/src/test/java/com/erp/sys/MetricsAndTraceIT.java` |
| `TC-SYS-086` | 字典类型列表：含 COLOR/SIZE/SEASON/UNIT 四个种子类型 | `backend/server/src/test/java/com/erp/sys/MetricsAndTraceIT.java` |
| `TC-SYS-087` | 字典项按 sort_no 升序返回，且带 typeCode | `backend/server/src/test/java/com/erp/sys/MetricsAndTraceIT.java` |
| `TC-SYS-088` | 字典类型不存在：404 + SYS_DICT_TYPE_NOT_FOUND（不是空数组） | `backend/server/src/test/java/com/erp/sys/MetricsAndTraceIT.java` |
| `TC-SYS-089` | 字典接口无 sys:read 权限：403 | `backend/server/src/test/java/com/erp/sys/MetricsAndTraceIT.java` |
| `TC-SYS-090` | 字典是只读的：对 /dicts 发 POST 返回 405（不是 500） | `backend/server/src/test/java/com/erp/sys/MetricsAndTraceIT.java` |
| `TC-SYS-008` | ★ 密码字段被遮成 ***（审计不得记录明文密码） | `backend/server/src/test/java/com/erp/sys/application/AuditJsonTest.java` |
| `TC-SYS-009` | ★ token / secret / apiKey / authorization 全部脱敏 | `backend/server/src/test/java/com/erp/sys/application/AuditJsonTest.java` |
| `TC-SYS-010` | 键名变体也脱敏：passwordHash / new_password / clientSecret | `backend/server/src/test/java/com/erp/sys/application/AuditJsonTest.java` |
| `TC-SYS-011` | 非敏感键不被误遮：tokenType / username / name 原样保留 | `backend/server/src/test/java/com/erp/sys/application/AuditJsonTest.java` |
| `TC-SYS-012` | 嵌套对象与数组里的敏感字段同样被遮（不能只管第一层） | `backend/server/src/test/java/com/erp/sys/application/AuditJsonTest.java` |
| `TC-SYS-013` | 标量与 null 不产生快照（审计只关心对象的字段值） | `backend/server/src/test/java/com/erp/sys/application/AuditJsonTest.java` |
| `TC-SYS-014` | ★ 超大快照返回 null 而不是截断（截断会产生非法 JSON，让「可还原」失效） | `backend/server/src/test/java/com/erp/sys/application/AuditJsonTest.java` |
| `TC-SYS-015` | 合法快照是能把字段值读回来的 JSON（可还原性的最低要求） | `backend/server/src/test/java/com/erp/sys/application/AuditJsonTest.java` |
| `TC-SYS-016` | CREATE + SUCCESS 计入下单数（orderType 按资源类型映射） | `backend/server/src/test/java/com/erp/sys/application/MetricsRecorderTest.java` |
| `TC-SYS-017` | ★ 审核/入库不计入下单数（否则经营看板单量虚高） | `backend/server/src/test/java/com/erp/sys/application/MetricsRecorderTest.java` |
| `TC-SYS-018` | 失败数按 errorCode 分维度累加 | `backend/server/src/test/java/com/erp/sys/application/MetricsRecorderTest.java` |
| `TC-SYS-019` | errorCode 为 null 时归到 UNKNOWN 维度（不能让指标因为 null 崩掉） | `backend/server/src/test/java/com/erp/sys/application/MetricsRecorderTest.java` |
| `TC-SYS-020` | 未知资源类型归到 other 维度 | `backend/server/src/test/java/com/erp/sys/application/MetricsRecorderTest.java` |
| `TC-SYS-021` | 审计写入计数按 action/result 分维度 | `backend/server/src/test/java/com/erp/sys/application/MetricsRecorderTest.java` |
| `TC-SYS-022` | 审计写入失败计数可读（应恒为 0，非 0 即告警） | `backend/server/src/test/java/com/erp/sys/application/MetricsRecorderTest.java` |
| `TC-SYS-023` | ★ 对账差额取绝对值：-1234 → 1234（差额方向不改变「差多少」的含义） | `backend/server/src/test/java/com/erp/sys/application/MetricsRecorderTest.java` |
| `TC-SYS-024` | 重复注册对账 gauge 不抛异常（Gauge 幂等注册） | `backend/server/src/test/java/com/erp/sys/application/MetricsRecorderTest.java` |
| `TC-SYS-025` | containsMetric 能识别已注册的指标（测试断言用的自检入口） | `backend/server/src/test/java/com/erp/sys/application/MetricsRecorderTest.java` |
| `TC-SYS-026` | 指标读不到时返回 0 而不是 null（避免测试里到处判空） | `backend/server/src/test/java/com/erp/sys/application/MetricsRecorderTest.java` |
| `TC-SYS-027` | forTests 工厂可直接构造（不依赖 Spring 的单元用例入口） | `backend/server/src/test/java/com/erp/sys/application/MetricsRecorderTest.java` |
| `TC-SYS-069` | 路径动作词优先：POST /xxx/{id}/disable → DISABLE 而不是 CREATE | `backend/server/src/test/java/com/erp/sys/domain/AuditActionTest.java` |
| `TC-SYS-070` | 无动作词时按 HTTP 方法归类：POST→CREATE / PUT→UPDATE / DELETE→DELETE | `backend/server/src/test/java/com/erp/sys/domain/AuditActionTest.java` |
| `TC-SYS-071` | 末段是纯数字时视为无动作词（/products/42 → CREATE/UPDATE，不是 OTHER） | `backend/server/src/test/java/com/erp/sys/domain/AuditActionTest.java` |
| `TC-SYS-072` | 无前缀路径与尾斜杠同样归类正确（切面拿到的 path 可能带不带 context-path） | `backend/server/src/test/java/com/erp/sys/domain/AuditActionTest.java` |
| `TC-SYS-073` | 方法名大小写不敏感；null 方法与 null 路径都退化为 OTHER 而不抛异常 | `backend/server/src/test/java/com/erp/sys/domain/AuditActionTest.java` |
| `TC-SYS-074` | 未识别的动作词退化为 OTHER（宁可归类差，也不能丢审计） | `backend/server/src/test/java/com/erp/sys/domain/AuditActionTest.java` |
| `TC-SYS-075` | ★ 审计结果只有 SUCCESS / FAIL 两个值（与数据库 CHECK 约束一致） | `backend/server/src/test/java/com/erp/sys/domain/AuditEnumsTest.java` |
| `TC-SYS-076` | 每个动作都能被 of() 产出（枚举无死角：没有任何值是永远达不到的） | `backend/server/src/test/java/com/erp/sys/domain/AuditEnumsTest.java` |
| `TC-SYS-077` | ★ 动作集合包含所有业务域（新增动作时必须来改这条用例，形成提醒） | `backend/server/src/test/java/com/erp/sys/domain/AuditEnumsTest.java` |
| `TC-SYS-078` | OTHER 只作为兜底存在，不参与路径映射（避免它变成常用分支） | `backend/server/src/test/java/com/erp/sys/domain/AuditEnumsTest.java` |
| `TC-SYS-079` | 动作名长度不超过数据库列宽 VARCHAR(32) | `backend/server/src/test/java/com/erp/sys/domain/AuditEnumsTest.java` |
| `TC-API-125` | ★ 登录结果与 token 都携带租户：租户 2 用户登录后 tenantId=2，且只能读自己的数据 | `backend/server/src/test/java/com/erp/tenant/TenantAuthIT.java` |
| `TC-API-126` | ★ 没有 tid 声明的 token（旧版本签发）→ 401，不能靠库里的租户兜底 | `backend/server/src/test/java/com/erp/tenant/TenantAuthIT.java` |
| `TC-API-127` | ★ tid 与账号真实归属不一致 → 401（不能拿别的租户的声明蒙混） | `backend/server/src/test/java/com/erp/tenant/TenantAuthIT.java` |
| `TC-API-128` | ★ 商品/SKU 是最基础的读路径：跨租户 id → 404，同租户 200 有内容 | `backend/server/src/test/java/com/erp/tenant/TenantAuthIT.java` |
| `TC-API-133` | ★ 租户 ∧ 数据权限的复合过滤：跨租户 404、同租户跨仓 404、本租户本仓 200 | `backend/server/src/test/java/com/erp/tenant/TenantAuthIT.java` |
| `TC-API-134` | ★ 反向越权同样 404：租户 1 的 token 查租户 2 的商品/单据/审计 | `backend/server/src/test/java/com/erp/tenant/TenantAuthIT.java` |
| `TC-TNT-044` | ★ Σ(各租户库存) == 全局库存；任一租户的 SQL 只能看见自己的行 | `backend/server/src/test/java/com/erp/tenant/TenantInvariantsIT.java` |
| `TC-TNT-045` | ★ 单号序列按租户独立：同一天两个租户都从 0001 开始，各自递增不串号 | `backend/server/src/test/java/com/erp/tenant/TenantInvariantsIT.java` |
| `TC-TNT-046` | ★ 幂等键按租户：同一 requestId 在两个租户各建一单；同租户重放只回自己那单 | `backend/server/src/test/java/com/erp/tenant/TenantInvariantsIT.java` |
| `TC-TNT-047` | ★ 审计归属：租户 2 写入后按真实 tenant_id 查库 == 2（不是 DB 默认值 1） | `backend/server/src/test/java/com/erp/tenant/TenantInvariantsIT.java` |
| `TC-TNT-048` | ★ 定时任务逐租户执行：两个租户各有一条到期预占，调度后各自只释放自己的 | `backend/server/src/test/java/com/erp/tenant/TenantInvariantsIT.java` |
| `TC-TNT-038` | 矩阵 · 按 id 直查：异租户 id → 404；同租户 → 200 且真有该行 | `backend/server/src/test/java/com/erp/tenant/TenantIsolationMatrixIT.java` |
| `TC-TNT-039` | 矩阵 · 列表：异租户过滤条件 → 200 空；同租户 → 200 有本租户行 | `backend/server/src/test/java/com/erp/tenant/TenantIsolationMatrixIT.java` |
| `TC-TNT-040` | 矩阵 · 导出：export=csv 与列表同一查询，不得到别租户的行 | `backend/server/src/test/java/com/erp/tenant/TenantIsolationMatrixIT.java` |
| `TC-TNT-041` | 矩阵 · 关联查询：从本租户资源反查时看不到别租户的关联行 | `backend/server/src/test/java/com/erp/tenant/TenantIsolationMatrixIT.java` |
| `TC-TNT-042` | 矩阵 · 聚合：合计数里不得混入别租户的数据 | `backend/server/src/test/java/com/erp/tenant/TenantIsolationMatrixIT.java` |
| `TC-TNT-043` | ★ 矩阵规模自检：9 × 5 × 3 = 135 格，一格都不能少 | `backend/server/src/test/java/com/erp/tenant/TenantIsolationMatrixIT.java` |
| `TC-API-129` | ★ 接口层：三张单据 + 商品/SKU/审计的跨租户详情一律 404（不是 403） | `backend/server/src/test/java/com/erp/tenant/TenantIsolationMatrixIT.java` |
| `TC-API-130` | ★ 接口层：审计按 id / 按单据 / 按 traceId 三条读路径都看不到别租户 | `backend/server/src/test/java/com/erp/tenant/TenantIsolationMatrixIT.java` |
| `TC-API-131` | ★ 接口层：列表/导出响应里不得出现别租户的 id（不是靠前端隐藏） | `backend/server/src/test/java/com/erp/tenant/TenantIsolationMatrixIT.java` |
| `TC-API-132` | ★ 接口层：租户 2 看自己的数据必须 200 且有内容（证明 404 来自隔离而非接口坏） | `backend/server/src/test/java/com/erp/tenant/TenantIsolationMatrixIT.java` |
| `TC-TNT-031` | ★ 表清单与库里真实列双向相等：少一张=漏过滤，多一张=改写器写不存在的列 | `backend/server/src/test/java/com/erp/tenant/TenantSchemaIT.java` |
| `TC-TNT-032` | ★ V16 之后所有业务表都没有 tenant_id 为 NULL 的行（回填完整） | `backend/server/src/test/java/com/erp/tenant/TenantSchemaIT.java` |
| `TC-TNT-033` | ★ ON CONFLICT 白名单里的表，都存在以 tenant_id 开头的唯一索引 | `backend/server/src/test/java/com/erp/tenant/TenantSchemaIT.java` |
| `TC-TNT-034` | ★ 审计表的 tenant_id 仍是 V13 的 NOT NULL DEFAULT 1（已知坑），演示租户已种角色 | `backend/server/src/test/java/com/erp/tenant/TenantSchemaIT.java` |
| `TC-TNT-035` | ★ 容器里的 JdbcTemplate 必须是租户感知实现，且强制开关默认开启 | `backend/server/src/test/java/com/erp/tenant/TenantWiringIT.java` |
| `TC-TNT-036` | ★ 没有租户绑定时拒绝执行，而不是回落到默认租户 | `backend/server/src/test/java/com/erp/tenant/TenantWiringIT.java` |
| `TC-TNT-037` | 上下文成对开闭：嵌套切换后精确回退到上一层，异常也不泄漏 | `backend/server/src/test/java/com/erp/tenant/TenantWiringIT.java` |

共 787 条用例。
