# 测试用例索引（自动生成，请勿手写）

> 来源：扫源码中的 `@CaseId` 注解 + 追踪矩阵（docs/00-索引与追踪矩阵.md）

| 用例ID | 用例名称 | 所在文件 |
|---|---|---|
| `TC-API-001` | 商品：建 → 查 → 改 → 停用 全链路，响应包络符合契约 | `backend/server/src/test/java/com/erp/api/MasterDataApiIT.java` |
| `TC-API-002` | 商品：编码重复 → 409 MDM_CODE_DUPLICATED；不存在 → 404 SYS_NOT_FOUND | `backend/server/src/test/java/com/erp/api/MasterDataApiIT.java` |
| `TC-API-003` | ★ 参数校验：编码过短 / 名称为空 → 400 SYS_VALIDATION_ERROR（不是 500） | `backend/server/src/test/java/com/erp/api/MasterDataApiIT.java` |
| `TC-API-004` | 商品列表分页：items/total/page/size 齐全，size 越界被钳制到 200 | `backend/server/src/test/java/com/erp/api/MasterDataApiIT.java` |
| `TC-API-005` | SKU：建单条 → 矩阵生成 → 重复矩阵跳过 → 自冲突 409 | `backend/server/src/test/java/com/erp/api/MasterDataApiIT.java` |
| `TC-API-006` | SKU 列表按商品过滤；不存在的商品查 SKU → 404 | `backend/server/src/test/java/com/erp/api/MasterDataApiIT.java` |
| `TC-API-007` | 供应商：建 → 重复 409 → 改 → 停用 | `backend/server/src/test/java/com/erp/api/MasterDataApiIT.java` |
| `TC-API-008` | ★ 客户折扣率：越界 400（契约层校验），合法 9 折成功 | `backend/server/src/test/java/com/erp/api/MasterDataApiIT.java` |
| `TC-API-009` | 仓库：列表返回数组（非分页）；onlyEnabled 过滤生效 | `backend/server/src/test/java/com/erp/api/MasterDataApiIT.java` |
| `TC-API-010` | 缺少 X-Request-Id 的写接口 → 400（契约要求写操作必须带幂等键） | `backend/server/src/test/java/com/erp/api/MasterDataApiIT.java` |
| `TC-ARCH-001` | ★ 规则自检：禁止系统时间规则必须能拦住违规代码 | `backend/server/src/test/java/com/erp/arch/ArchitectureTest.java` |
| `TC-ARCH-002` | ★ 规则自检：禁止裸异常规则必须能拦住违规代码 | `backend/server/src/test/java/com/erp/arch/ArchitectureTest.java` |
| `TC-ARCH-003` | ★ 规则自检：状态机规则必须拦住不调 requireTransition 的方法 | `backend/server/src/test/java/com/erp/arch/ArchitectureTest.java` |
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
| `TC-PUR-014` | ★ 已入库的单据不能直接作废：必须提示先红冲（FR-PUR-03 属 P1） | `backend/server/src/test/java/com/erp/pur/PurchaseOrderIT.java` |
| `TC-PUR-015` | 草稿作废成功；作废后不能再提交 | `backend/server/src/test/java/com/erp/pur/PurchaseOrderIT.java` |
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
| `TC-SAL-012` | 已出货的单据不能直接作废（需红冲，FR-SAL-04 属 M2） | `backend/server/src/test/java/com/erp/sal/SalesOrderIT.java` |
| `TC-SAL-013` | 客户或仓库停用后不能再建出货单 | `backend/server/src/test/java/com/erp/sal/SalesOrderIT.java` |
| `TC-SAL-014` | 出货单不存在：404 语义 | `backend/server/src/test/java/com/erp/sal/SalesOrderIT.java` |
| `TC-SAL-015` | ★ 端到端：进货入库 → 出货 → 两单流水可各自反查，且全库 INV-2 成立 | `backend/server/src/test/java/com/erp/sal/SalesOrderIT.java` |
| `TC-SAL-016` | ★★ 出货后预占记录必须转为 CONSUMED：不允许永远停在 ACTIVE（M1 真实捕获的缺陷） | `backend/server/src/test/java/com/erp/sal/SalesOrderIT.java` |
| `TC-SAL-017` | 作废后预占记录转为 RELEASED，且 ACTIVE 合计归零（与余额行一致） | `backend/server/src/test/java/com/erp/sal/SalesOrderIT.java` |
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

共 95 条用例。
