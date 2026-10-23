# 测试用例索引（自动生成，请勿手写）

> 来源：扫源码中的 `@CaseId` 注解 + 追踪矩阵（docs/00-索引与追踪矩阵.md）

| 用例ID | 用例名称 | 所在文件 |
|---|---|---|
| `TC-API-012` | ★ 库存流水：分页参数生效、按来源单据反查、响应字段符合契约 | `backend/server/src/test/java/com/erp/api/InventoryApiIT.java` |
| `TC-API-013` | 库存流水：未登录 401（权限守卫生效，不是 500） | `backend/server/src/test/java/com/erp/api/InventoryApiIT.java` |
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
| `TC-NL-006` | ★ 查询库存：三量正确；未登录 401 —— NL 不会成为绕过认证的旁路 | `backend/server/src/test/java/com/erp/api/NlCommandApiIT.java` |
| `TC-NL-007` | ★ 单据列表/详情：中文状态与分页生效，详情 id 与列表行一致 | `backend/server/src/test/java/com/erp/api/NlCommandApiIT.java` |
| `TC-NL-008` | ★ 写意图：建进货草稿成功；同 X-Request-Id 重放只有一张单；缺键 400 | `backend/server/src/test/java/com/erp/api/NlCommandApiIT.java` |
| `TC-NL-009` | ★ 进销存汇总：与 REST 报表逐字段一致；缺省账期=当前月 | `backend/server/src/test/java/com/erp/api/NlCommandApiIT.java` |
| `TC-NL-010` | ★ 意图级权限：只有 rpt:read 的账号能汇总，读库存/主数据与建单全部 403 | `backend/server/src/test/java/com/erp/api/NlCommandApiIT.java` |
| `TC-NL-011` | ★ 失败语义：无法识别 422、参数非法 400、空指令 400、业务 404 原样透出 | `backend/server/src/test/java/com/erp/api/NlCommandApiIT.java` |
| `TC-API-018` | ★ 三个报表接口全部可达且包络符合契约；默认分组是 supplier/customer；报表零写入 | `backend/server/src/test/java/com/erp/api/ReportApiIT.java` |
| `TC-RPT-009` | 非法参数一律 400：groupBy 取值非法 / 跨报表维度 / from>to / period 格式 / 缺参数 | `backend/server/src/test/java/com/erp/api/ReportApiIT.java` |
| `TC-API-014` | ★ 退货 HTTP 端到端：建单→提交→审核→入库，响应字段符合契约；出货明细露出 returnedQty | `backend/server/src/test/java/com/erp/api/ReturnOrderApiIT.java` |
| `TC-API-015` | 退货接口守卫：缺 X-Request-Id → 400（不是 500）；VIEWER 可查不可建；未登录 401 | `backend/server/src/test/java/com/erp/api/ReturnOrderApiIT.java` |
| `TC-RMA-013` | ★ 只有 rma:read 的临时账号也能拿到非空可退列表；同一账号读 /sales-orders 仍然 403 | `backend/server/src/test/java/com/erp/api/ReturnableOrderApiIT.java` |
| `TC-RMA-014` | 已全部退完的出货单不出现在可退列表；未出货的 DRAFT/SUBMITTED/APPROVED 也不出现 | `backend/server/src/test/java/com/erp/api/ReturnableOrderApiIT.java` |
| `TC-RMA-015` | ★ returnableQty = shippedQty − returnedQty，且与退货建单预检同口径（超 1 件即 409） | `backend/server/src/test/java/com/erp/api/ReturnableOrderApiIT.java` |
| `TC-RMA-016` | 可退列表是只读接口：GET 不要求 X-Request-Id，也不得留下任何写痕迹 | `backend/server/src/test/java/com/erp/api/ReturnableOrderApiIT.java` |
| `TC-RMA-017` | ★ 分页口径：total 是单据张数、翻页取得后面的单，size 越界只钳制不当全量 | `backend/server/src/test/java/com/erp/api/ReturnableOrderApiIT.java` |
| `TC-SAL-024` | ★ 出货 void：缺幂等键 400；红冲 200 + 库存回补；同键重放 200；新键 409 | `backend/server/src/test/java/com/erp/api/ReversalApiIT.java` |
| `TC-PUR-023` | ★ 进货 void：缺幂等键 400；红冲 200 + 库存回退；同键重放 200；新键 409 | `backend/server/src/test/java/com/erp/api/ReversalApiIT.java` |
| `TC-API-016` | ★ 结算 HTTP 端到端：日清 items/totals → 月结 CLOSED → 对账 balanced，字段符合契约 | `backend/server/src/test/java/com/erp/api/SettlementApiIT.java` |
| `TC-API-017` | ★★ 结算守卫：锁账后开单 409；FINANCE 可结算不可开单；缺幂等键/非法账期是 400 不是 500 | `backend/server/src/test/java/com/erp/api/SettlementApiIT.java` |
| `TC-SET-015` | ★ 只有 set:read 的临时账号能拿到非空账期列表，且月结结果刷新后逐字段还在；同一账号 POST 日清仍 403 | `backend/server/src/test/java/com/erp/api/SettlementPeriodApiIT.java` |
| `TC-SET-016` | ★ OPEN 账期的 closedAt/closedBy 为 null；CLOSED 行如实带出 closedBy 与期间汇总，字段名与契约一致 | `backend/server/src/test/java/com/erp/api/SettlementPeriodApiIT.java` |
| `TC-SET-017` | ★ status 筛选生效：CLOSED 只给已锁账期、OPEN 只给未锁，非法取值是 400 而不是 500 | `backend/server/src/test/java/com/erp/api/SettlementPeriodApiIT.java` |
| `TC-SET-018` | ★ 分页口径：total 是账期行数、按 period 倒序翻页，size 越界只钳制；GET 无副作用也不要求幂等键 | `backend/server/src/test/java/com/erp/api/SettlementPeriodApiIT.java` |
| `TC-ARCH-001` | ★ 规则自检：禁止系统时间规则必须能拦住违规代码 | `backend/server/src/test/java/com/erp/arch/ArchitectureTest.java` |
| `TC-ARCH-002` | ★ 规则自检：禁止裸异常规则必须能拦住违规代码 | `backend/server/src/test/java/com/erp/arch/ArchitectureTest.java` |
| `TC-ARCH-003` | ★ 规则自检：状态机规则必须拦住不调 requireTransition 的方法 | `backend/server/src/test/java/com/erp/arch/ArchitectureTest.java` |
| `TC-ARCH-004` | ★ 规则自检：单据模块不得依赖 set 的规则必须拦住直接 import 的写法 | `backend/server/src/test/java/com/erp/arch/ArchitectureTest.java` |
| `TC-ARCH-005` | ★ 规则自检：报表只读规则必须拦住跨模块应用服务依赖 | `backend/server/src/test/java/com/erp/arch/ArchitectureTest.java` |
| `TC-ARCH-006` | ★ 规则自检：退货模块不得直连 sal.repository 的规则必须拦住违规 import | `backend/server/src/test/java/com/erp/arch/ArchitectureTest.java` |
| `TC-ARCH-007` | ★ 规则自检：NL 入口不得直连仓储的规则必须拦住违规 import | `backend/server/src/test/java/com/erp/arch/ArchitectureTest.java` |
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
| `TC-NL-004` | ★ tool_call 映射为意图与参数；请求带 Authorization、tools 与 tool_choice=auto | `backend/server/src/test/java/com/erp/nl/OpenAiNlCommandParserTest.java` |
| `TC-NL-005` | ★ 无 tool call → 422；上游 500 → 503；缺 key → 503 且提示 ERP_NL_API_KEY | `backend/server/src/test/java/com/erp/nl/OpenAiNlCommandParserTest.java` |
| `TC-NL-001` | 中文/键值对指令解析为正确意图与参数（库存/列表/详情/主数据/帮助） | `backend/server/src/test/java/com/erp/nl/StubNlCommandParserTest.java` |
| `TC-NL-002` | 本月/上月由注入时钟决定；建单多行用分号分隔；缺省账期不编造 | `backend/server/src/test/java/com/erp/nl/StubNlCommandParserTest.java` |
| `TC-NL-003` | 空/无法识别 → NL_COMMAND_UNRECOGNIZED；非法数字 → NL_COMMAND_INVALID_ARGUMENT | `backend/server/src/test/java/com/erp/nl/StubNlCommandParserTest.java` |
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
| `TC-RPT-013` | ★ 三条报表计划：事实表各只扫一次、分组在数据库完成、V6 索引可用 | `backend/server/src/test/java/com/erp/rpt/repository/ReportQueryPlanIT.java` |
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

共 189 条用例。
