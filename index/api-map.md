# 接口索引（自动生成，请勿手写）

> 来源：`packages/api-contract/openapi.yaml`（单一事实源，ADR-0008）

| 方法 | 路径 | operationId | 摘要 |
|---|---|---|---|
| GET | `/warehouses` | `listWarehouses` | 查询仓库列表（返回数组，不分页） |
| POST | `/warehouses` | `createWarehouse` | 新建仓库 |
| GET | `/warehouses/{id}` | `getWarehouse` | 仓库详情 |
| PUT | `/warehouses/{id}` | `updateWarehouse` | 更新仓库（编码不可修改） |
| POST | `/warehouses/{id}/disable` | `disableWarehouse` | 停用仓库 |
| GET | `/suppliers` | `listSuppliers` | 查询供应商列表 |
| POST | `/suppliers` | `createSupplier` | 新建供应商 |
| GET | `/suppliers/{id}` | `getSupplier` | 供应商详情 |
| PUT | `/suppliers/{id}` | `updateSupplier` | 更新供应商（编码不可修改） |
| POST | `/suppliers/{id}/disable` | `disableSupplier` | 停用供应商 |
| GET | `/customers` | `listCustomers` | 查询客户列表 |
| POST | `/customers` | `createCustomer` | 新建客户 |
| GET | `/customers/{id}` | `getCustomer` | 客户详情 |
| PUT | `/customers/{id}` | `updateCustomer` | 更新客户（编码不可修改） |
| POST | `/customers/{id}/disable` | `disableCustomer` | 停用客户 |
| GET | `/products` | `listProducts` | 查询商品列表 |
| POST | `/products` | `createProduct` | 新建商品（款式） |
| GET | `/products/{id}` | `getProduct` | 商品详情 |
| PUT | `/products/{id}` | `updateProduct` | 更新商品（编码不可改） |
| GET | `/skus` | `listSkus` | 查询 SKU 列表 |
| POST | `/skus` | `createSku` | 新建 SKU（或按颜色×尺码矩阵批量生成） |
| GET | `/inventory` | `queryInventory` | 实时库存查询（多仓/多SKU） |
| GET | `/inventory/ledger` | `queryInventoryLedger` | 库存流水（不可变，可反查来源单据） |
| POST | `/inventory/reserve` | `reserveInventory` | 库存预占（下单锁定可用量） |
| POST | `/inventory/reservations/{id}/release` | `releaseReservation` | 释放预占（取消订单 / 超时释放） |
| GET | `/inventory/transfers` | `listInventoryTransfers` | 调拨单列表（FR-INV-07） |
| POST | `/inventory/transfers` | `createInventoryTransfer` | 建调拨单并立即调出（两段式第一段） |
| POST | `/inventory/transfers/{id}/receive` | `receiveInventoryTransfer` | 调拨到货（两段式第二段） |
| GET | `/inventory/stocktakes` | `listStocktakes` | 盘点单列表（FR-INV-08） |
| POST | `/inventory/stocktakes` | `createStocktake` | 建盘点单（快照账面量） |
| POST | `/inventory/stocktakes/{id}/post` | `postStocktake` | 盘点过账（差异生成 ADJUST_IN/OUT 流水） |
| GET | `/inventory/adjustments` | `listInventoryAdjustments` | 库存调整列表（FR-INV-09） |
| POST | `/inventory/adjustments` | `createInventoryAdjustment` | 库存调整（原因必填） |
| GET | `/inventory/freezes` | `listInventoryFreezes` | 冻结记录列表（FR-INV-10） |
| POST | `/inventory/freezes` | `createInventoryFreeze` | 冻结库存（冻结量不进可用量） |
| POST | `/inventory/freezes/{id}/release` | `releaseInventoryFreeze` | 解冻库存（人工） |
| GET | `/inventory/balance-dimensions` | `queryBalanceDimensions` | 五维结存查询（仓 × SKU × 批次 × 状态 × 权属，FR-INV-11） |
| GET | `/inventory/alerts` | `queryInventoryAlerts` | 库存预警（安全库存 / 滞销 / 临期，FR-INV-12） |
| GET | `/inventory/replenishment` | `queryReplenishment` | 补货建议（min/max 阈值档，FR-INV-13） |
| GET | `/purchase-orders` | `listPurchaseOrders` | 查询进货单 |
| POST | `/purchase-orders` | `createPurchaseOrder` | 创建进货单（草稿） |
| GET | `/purchase-orders/{id}` | `getPurchaseOrder` | 进货单详情 |
| POST | `/purchase-orders/{id}/receive` | `receivePurchaseOrder` | 入库确认（生成入库流水、增加库存） |
| POST | `/purchase-orders/{id}/void` | `voidPurchaseOrder` | 作废/红冲进货单 |
| GET | `/sales-orders` | `listSalesOrders` | 查询出货单 |
| POST | `/sales-orders` | `createSalesOrder` | 创建出货单（同时预占库存） |
| POST | `/sales-orders/{id}/ship` | `shipSalesOrder` | 出货确认（预占转出库、扣减库存） |
| POST | `/sales-orders/{id}/void` | `voidSalesOrder` | 作废/红冲出货单（回补库存） |
| GET | `/return-orders` | `listReturnOrders` | 查询退货单（rma:read） |
| POST | `/return-orders` | `createReturnOrder` | 创建退货单（rma:create） |
| GET | `/return-orders/returnable-orders` | `listReturnableOrders` | 查询可退货的出货单（rma:read） |
| GET | `/return-orders/{id}` | `getReturnOrder` | 退货单详情（rma:read） |
| POST | `/return-orders/{id}/submit` | `submitReturnOrder` | 提交退货单（rma:submit） |
| POST | `/return-orders/{id}/approve` | `approveReturnOrder` | 审核退货单（rma:approve） |
| POST | `/return-orders/{id}/reject` | `rejectReturnOrder` | 驳回退货单（rma:reject） |
| POST | `/return-orders/{id}/receive` | `receiveReturnOrder` | 退货入库（rma:receive） |
| POST | `/return-orders/{id}/void` | `voidReturnOrder` | 作废退货单（rma:void） |
| POST | `/settlements/daily` | `runDailySettlement` | 日清（每日汇总当日出入库与金额） |
| POST | `/settlements/monthly` | `runMonthlySettlement` | 月结（期间结账并锁账） |
| GET | `/settlements/reconcile` | `reconcile` | 总账与明细对账（差额必须为 0） |
| GET | `/settlements/periods` | `listSettlementPeriods` | 账期列表查询（set:read，只读） |
| GET | `/reports/purchase` | `purchaseReport` | 进货统计（按供应商/商品/期间） |
| GET | `/reports/sales` | `salesReport` | 出货统计（按客户/商品/期间） |
| GET | `/reports/inventory-summary` | `inventorySummaryReport` | 进销存汇总表（期初 + 入 − 出 = 期末） |
| GET | `/reports/operating` | `operatingReport` | 经营日报/月报（流水四数满足 INV-6，单据金额另列） |
| GET | `/reports/inventory-turnover` | `inventoryTurnoverReport` | 库存周转与呆滞（周转天数、N 天无出库） |
| GET | `/reports/customer-contribution` | `customerContributionReport` | 客户贡献与复购（Top N、复购率、新客/回头客） |
| GET | `/reports/aging` | `agingReport` | 应收/应付账龄（0-30 / 31-60 / 61-90 / 90+） |
| GET | `/reports/purchase/export` | `purchaseExportReport` | 进货统计导出 CSV（与页面同一查询、同一舍入） |
| GET | `/reports/sales/export` | `salesExportReport` | 出货统计导出 CSV（与页面同一查询、同一舍入） |
| GET | `/reports/inventory-summary/export` | `inventorySummaryExportReport` | 进销存汇总导出 CSV（与页面同一 SQL，含名称列） |
| GET | `/reports/operating/export` | `operatingExportReport` | 经营日报/月报导出 CSV（与页面同一查询、同一舍入） |
| GET | `/reports/inventory-turnover/export` | `inventoryTurnoverExportReport` | 库存周转与呆滞导出 CSV（与页面同一查询、同一舍入） |
| GET | `/reports/customer-contribution/export` | `customerContributionExportReport` | 客户贡献与复购导出 CSV（含全量汇总） |
| GET | `/reports/aging/export` | `agingExportReport` | 应收/应付账龄导出 CSV（含守恒汇总） |
| GET | `/reports/slow-moving` | `slowMovingReport` | 呆滞分析（服务端过滤，行形状与库存周转一致） |
| GET | `/reports/slow-moving/export` | `slowMovingExportReport` | 呆滞分析导出 CSV |
| GET | `/reports/inventory-value` | `inventoryValueReport` | 库存金额（库存总值） |
| GET | `/reports/inventory-value/export` | `inventoryValueExportReport` | 库存金额导出 CSV（金额列标（元），未月结显示 -） |
| GET | `/reports/purchase-detail` | `purchaseDetailReport` | 进货明细（按单据展开，分页） |
| GET | `/reports/purchase-detail/export` | `purchaseDetailExportReport` | 进货明细导出 CSV（全量、不带分页） |
| GET | `/reports/sales-detail` | `salesDetailReport` | 销售明细（按单据展开，分页） |
| GET | `/reports/sales-detail/export` | `salesDetailExportReport` | 销售明细导出 CSV（全量、不带分页） |
| GET | `/reports/profit/export` | `profitExportReport` | 毛利分析导出 CSV（补 FR-RPT-09 缺的最后一张） |
| GET | `/nl/pending` | `nlPending` | 待确认队列（FR-V4-03 · S5） |
| POST | `/nl/pending/{id}/execute` | `nlPendingExecute` | 在队列里确认执行（FR-V4-03 · S5） |
| POST | `/nl/pending/{id}/dismiss` | `nlPendingDismiss` | 驳回一条待确认指令（FR-V4-03 · S5） |
| GET | `/nl/audit` | `nlAuditPage` | 自然语言操作审计（FR-V4-03 · S6） |
| GET | `/nl/audit/{id}` | `nlAuditDetail` | 单条操作记录（FR-V4-03 · S6） |
| POST | `/nl/review` | `nlReview` | 单独复核一条指令（FR-V4-03 · S4） |
| GET | `/nl/settings` | `getNlSettings` | 读取 AI 接入配置（FR-SYS-04） |
| PUT | `/nl/settings` | `updateNlSettings` | 保存 AI 接入配置（保存即生效） |
| POST | `/nl/settings/test` | `testNlSettings` | 测试模型连接（不落库） |
| POST | `/nl/plan` | `nlPlan` | 生成执行计划（FR-V4-03 · S3） |
| POST | `/nl/command` | `nlCommand` | 自然语言操作入口（FR-SYS-04） |
| POST | `/cost/recalculate` | `recalculateCost` | 全量重算成本层（FR-CST-01，INV-11） |
| POST | `/cost/monthly` | `runCostMonthly` | 成本月结（FR-CST-02，幂等） |
| GET | `/cost/monthly` | `listCostMonthly` | 查询成本月结（FR-CST-02） |
| GET | `/reports/profit` | `getProfitReport` | 毛利分析（FR-CST-03 / FR-RPT-04） |
| GET | `/purchase-requisitions` | `listPurchaseRequisitions` | 查询请购单 |
| POST | `/purchase-requisitions` | `createPurchaseRequisition` | 创建请购单（草稿） |
| GET | `/purchase-requisitions/{id}` | `getPurchaseRequisition` | 请购单详情 |
| POST | `/purchase-requisitions/{id}/submit` | `submitPurchaseRequisition` | 提交请购单 |
| POST | `/purchase-requisitions/{id}/approve` | `approvePurchaseRequisition` | 审核请购单 |
| POST | `/purchase-requisitions/{id}/convert` | `convertPurchaseRequisition` | 请购转采购订单 |
| GET | `/purchase-orders/{id}/receipts` | `listPurchaseReceipts` | 到货/质检记录 |
| GET | `/purchase-returns` | `listPurchaseReturns` | 查询采购退货单 |
| POST | `/purchase-returns` | `createPurchaseReturn` | 创建采购退货单（草稿，不动库存/应付） |
| GET | `/purchase-returns/{id}` | `getPurchaseReturn` | 采购退货单详情 |
| POST | `/purchase-returns/{id}/approve` | `approvePurchaseReturn` | 执行采购退货（扣库存 + 冲应付） |
| POST | `/purchase-returns/{id}/void` | `voidPurchaseReturn` | 作废采购退货草稿 |
| GET | `/supplier-payables` | `listSupplierPayables` | 查询供应商应付单 |
| GET | `/supplier-payables/reconcile` | `reconcileSupplierPayables` | 应付余额与核销守恒核对 |
| GET | `/supplier-payables/{id}` | `getSupplierPayable` | 应付单详情（含核销明细） |
| POST | `/supplier-payments` | `createSupplierPayment` | 付款并核销（支持部分核销、多应付、超额拒绝） |
| GET | `/audits` | `listAudits` | 审计查询（FR-SYS-07，按人/单据/时间/操作类型，只读） |
| GET | `/audits/{id}` | `getAudit` | 审计详情（FR-SYS-07） |
| GET | `/audits/timeline` | `auditTimeline` | 某张业务单据的审计轨迹（FR-SYS-07，按时间正序） |
| GET | `/audits/by-trace` | `auditByTrace` | 按 traceId 取该请求产生的全部审计（FR-SYS-08 贯穿性的落库侧证据） |
| GET | `/dicts` | `listDictTypes` | 字典类型列表（只读） |
| GET | `/dicts/{typeCode}/items` | `listDictItems` | 某类型的字典项（按 sortNo 升序） |
| POST | `/auth/login` | `login` | 登录换取 JWT |
| GET | `/auth/me` | `getCurrentUser` | 当前登录用户（刷新页面后恢复权限菜单） |
| POST | `/auth/change-password` | `changePassword` | 修改自己的密码（密码过期状态下唯一可用的写接口） |
| GET | `/iam/users` | `listIamUsers` | 账号清单 |
| POST | `/iam/users` | `createIamUser` | 创建账号 |
| GET | `/iam/users/{id}` | `getIamUser` | 账号详情 |
| PUT | `/iam/users/{id}/roles` | `replaceIamUserRoles` | 改角色（覆盖式） |
| POST | `/iam/users/{id}/enabled` | `setIamUserEnabled` | 启停账号 |
| POST | `/iam/users/{id}/reset-password` | `resetIamUserPassword` | 管理员重置密码 |
| GET | `/iam/roles` | `listIamRoles` | 角色清单（返回数组，不分页） |
| POST | `/iam/roles` | `createIamRole` | 创建角色 |
| GET | `/iam/roles/{id}/permissions` | `getIamRolePermissions` | 角色权限（角色信息 + 权限码数组） |
| PUT | `/iam/roles/{id}/permissions` | `replaceIamRolePermissions` | 覆盖式设置角色权限（授权即时生效的触发点） |
| GET | `/iam/users/{id}/data-scopes` | `getIamUserDataScopes` | 账号的数据权限（直配 + 角色继承） |
| PUT | `/iam/users/{id}/data-scopes` | `replaceIamUserDataScopes` | 覆盖式设置账号的数据权限 |
| GET | `/iam/roles/{id}/data-scopes` | `getIamRoleDataScopes` | 角色的数据范围配置 |
| PUT | `/iam/roles/{id}/data-scopes` | `replaceIamRoleDataScopes` | 覆盖式设置角色的数据范围 |
| GET | `/approvals` | `listApprovals` | 审批列表 |
| POST | `/approvals` | `submitApproval` | 提交审批 |
| GET | `/approvals/{id}` | `getApproval` | 审批详情（含节点快照） |
| POST | `/approvals/{id}/approve` | `approveApproval` | 同意当前节点 |
| POST | `/approvals/{id}/reject` | `rejectApproval` | 驳回（终止审批） |
| POST | `/approvals/{id}/withdraw` | `withdrawApproval` | 撤回（仅发起人本人） |
| POST | `/approvals/{id}/transfer` | `transferApproval` | 转办 |
| POST | `/approvals/{id}/accept-transfer` | `acceptApprovalTransfer` | 接办（转办的目标人确认接手） |
| GET | `/approvals/flows` | `listApprovalFlows` | 流程定义（含节点，只读） |
| POST | `/approvals/flows/{id}/enabled` | `setApprovalFlowEnabled` | 启停审批流程 |
| POST | `/products/{id}/disable` | `disableProduct` | 停用商品（软删） |
| GET | `/skus/{id}` | `getSku` | SKU 详情 |
| PUT | `/skus/{id}` | `updateSku` | 更新 SKU（颜色/尺码不可改） |
| POST | `/skus/matrix` | `createSkuMatrix` | 颜色 × 尺码矩阵批量生成 SKU |
| POST | `/skus/{id}/disable` | `disableSku` | 停用 SKU（软删） |
| POST | `/inventory/receipts` | `internalReceiveStock` | 入库（内部控制端点） |
| POST | `/inventory/shipments` | `internalShipStock` | 出库（FEFO，内部控制端点） |
| GET | `/inventory/consistency-check` | `checkInventoryConsistency` | 不变量校验 · 实存 == 流水净和（运维/对账端点） |
| POST | `/purchase-orders/{id}/submit` | `submitPurchaseOrder` | 提交进货单（DRAFT → SUBMITTED） |
| POST | `/purchase-orders/{id}/approve` | `approvePurchaseOrder` | 审核进货单（SUBMITTED → APPROVED） |
| POST | `/purchase-orders/{id}/reject` | `rejectPurchaseOrder` | 驳回进货单（SUBMITTED → REJECTED） |
| GET | `/sales-orders/{id}` | `getSalesOrder` | 出货单详情 |
| POST | `/sales-orders/{id}/submit` | `submitSalesOrder` | 提交出货单（DRAFT → SUBMITTED） |
| POST | `/sales-orders/{id}/approve` | `approveSalesOrder` | 审核出货单（SUBMITTED → APPROVED） |
| POST | `/sales-orders/{id}/reject` | `rejectSalesOrder` | 驳回出货单（SUBMITTED → REJECTED） |
| GET | `/sales-quotes` | `listSalesQuotes` | 查询报价单 |
| POST | `/sales-quotes` | `createSalesQuote` | 创建报价单（草稿，不预占库存） |
| GET | `/sales-quotes/{id}` | `getSalesQuote` | 报价单详情（含明细行） |
| POST | `/sales-quotes/{id}/submit` | `submitSalesQuote` | 提交报价单（DRAFT → SUBMITTED） |
| POST | `/sales-quotes/{id}/approve` | `approveSalesQuote` | 审核通过报价单（SUBMITTED → APPROVED） |
| POST | `/sales-quotes/{id}/reject` | `rejectSalesQuote` | 驳回报价单（SUBMITTED → REJECTED） |
| POST | `/sales-quotes/{id}/void` | `voidSalesQuote` | 作废报价单（→ VOIDED） |
| POST | `/sales-quotes/{id}/convert` | `convertSalesQuote` | 报价单转化为出货单（同时预占库存） |
| POST | `/sales-orders/auto-split` | `createSalesOrderAutoSplit` | 自动选仓建出货单（逐仓吃量拆分配） |
| GET | `/product-bundles` | `listProductBundles` | 查询套装 |
| POST | `/product-bundles` | `createProductBundle` | 创建套装 |
| GET | `/product-bundles/{id}` | `getProductBundle` | 套装详情（含组件配比） |
| PUT | `/product-bundles/{id}` | `updateProductBundle` | 修改套装（整体替换组件清单） |
| POST | `/product-bundles/{id}/disable` | `disableProductBundle` | 停用套装 |
| GET | `/product-bundles/{id}/availability` | `getProductBundleAvailability` | 套装可用量（min(组件可用量 ÷ 配比)） |
| POST | `/sales-orders/{id}/shipments` | `createShipmentPlan` | 为出货单生成发货计划（按预占所在仓拆单） |
| GET | `/shipments` | `listShipments` | 查询发货单 |
| GET | `/shipments/{id}` | `getShipment` | 发货单详情（含拣货行与累计已扫量） |
| POST | `/shipments/{id}/scan` | `scanShipment` | 扫码拣货（累计已扫数量） |
| POST | `/shipments/{id}/confirm` | `confirmShipment` | 确认发货（出库 + 消费预占 + 累计已出量 + 登记应收） |
| GET | `/receivables` | `listReceivables` | 查询客户应收单 |
| GET | `/receivables/summary` | `getReceivableSummary` | 客户应收汇总（对账口径） |
| GET | `/receivables/{id}` | `getReceivable` | 应收单详情 |
| GET | `/customer-receipts` | `listCustomerReceipts` | 查询收款单 |
| POST | `/customer-receipts` | `createCustomerReceipt` | 登记收款并核销应收 |
| GET | `/customer-receipts/{id}` | `getCustomerReceipt` | 收款单详情（含核销明细） |
| GET | `/customers/{id}/credit` | `getCustomerCredit` | 客户信用视图（额度、敞口、可用额度、逾期） |
| PUT | `/customers/{id}/credit` | `updateCustomerCredit` | 调整客户信用额度与账期 |
| POST | `/auth/switch-tenant` | `switchTenant` | 切换本次会话的行动租户（重新签发 JWT） |
| GET | `/tenants` | `listTenants` | 租户清单（平台租户元数据，不分页） |
| POST | `/tenants/{id}/enabled` | `setTenantEnabled` | 启用 / 停用租户（平台元数据） |
| GET | `/dashboard/summary` | `dashboardSummary` | 工作台聚合数据（FR-V3-02） |
| GET | `/todos` | `listTodos` | 待办中心列表（FR-V3-03） |
| GET | `/search` | `globalSearch` | 全局搜索（FR-V3-05） |
| GET | `/documents/{type}/{id}` | `documentDetail` | 单据详情（FR-V3-04） |
| GET | `/org/departments` | `departmentTree` | 部门树（FR-V3-23） |
| POST | `/org/departments` | `createDepartment` | 新建部门（FR-V3-23） |
| PUT | `/org/departments` | `updateDepartment` | 更新部门（FR-V3-23） |
| DELETE | `/org/departments` | `deleteDepartment` | 删除部门（FR-V3-23） |
| GET | `/org/positions` | `listPositions` | 岗位列表（FR-V3-23） |
| POST | `/org/positions` | `createPosition` | 新建岗位（FR-V3-23） |
| PUT | `/org/positions` | `updatePosition` | 更新岗位（FR-V3-23） |
| DELETE | `/org/positions` | `deletePosition` | 删除岗位（FR-V3-23） |
| GET | `/sys-config` | `listSystemConfig` | 系统参数列表（FR-V3-24） |
| PUT | `/sys-config` | `updateSystemConfig` | 修改系统参数值（FR-V3-24） |
| GET | `/number-rules` | `listNumberRules` | 单号规则列表（FR-V3-25） |
| PUT | `/number-rules` | `updateNumberRule` | 更新单号规则（FR-V3-25） |
| POST | `/number-rules` | `previewNumberRule` | 预览下一张单号（不消费流水，FR-V3-25） |
| GET | `/login-logs` | `listLoginLogs` | 登录日志列表（FR-V3-29） |
| GET | `/login-logs/online` | `onlineLoginLogs` | 当前在线用户（最近 N 分钟成功登录，FR-V3-29） |
| GET | `/fin/accounts` | `listFinAccounts` | 会计科目平铺列表（fin:read；新租户首次访问自动补种标准科目模板） |
| POST | `/fin/accounts` | `createFinAccount` | 新建科目（fin:account:write；编码在本租户内唯一） |
| GET | `/fin/accounts/tree` | `getFinAccountTree` | 科目树（一级科目为根，children 递归；页面按 category 六大类分组渲染） |
| PUT | `/fin/accounts/{id}` | `updateFinAccount` | 修改科目（编码与父级创建后不可改；停用被引用科目允许、删除不允许） |
| DELETE | `/fin/accounts/{id}` | `deleteFinAccount` | 删除科目（被凭证分录引用或有子科目 → 409 FIN_ACCOUNT_IN_USE） |
| GET | `/fin/vouchers` | `pageFinVouchers` | 凭证分页列表（status / sourceType / period / from / to / keyword 过滤） |
| POST | `/fin/vouchers` | `createFinVoucher` | 手工录凭证（借贷必须相等；不等 400 FIN_VOUCHER_UNBALANCED，details 带差额） |
| GET | `/fin/vouchers/{id}` | `getFinVoucher` | 凭证详情（头 + 全部分录） |
| PUT | `/fin/vouchers/{id}` | `updateFinVoucher` | 修改凭证（仅 DRAFT；分录整单替换，借贷平衡校验同 POST） |
| DELETE | `/fin/vouchers/{id}` | `deleteFinVoucher` | 删除凭证（仅 DRAFT；分录连带清除，跨租户 id 只会得到"未删除"） |
| POST | `/fin/vouchers/{id}/audit` | `auditFinVoucher` | 审核凭证（DRAFT → AUDITED） |
| POST | `/fin/vouchers/{id}/post` | `postFinVoucher` | 过账凭证（AUDITED → POSTED；报表只统计 POSTED） |
| GET | `/fin/bank-accounts` | `listBankAccounts` | 银行账户列表（默认账户排首位） |
| POST | `/fin/bank-accounts` | `createBankAccount` | 新建银行账户（本租户第一个账户自动成为默认入账账户） |
| PUT | `/fin/bank-accounts/{id}` | `updateBankAccount` | 修改银行账户（balance 字段是账面余额，手工调整仅初始化时使用） |
| DELETE | `/fin/bank-accounts/{id}` | `deleteBankAccount` | 删除银行账户（已有日记账流水 → 409，请停用而不是删除） |
| POST | `/fin/bank-accounts/{id}/default` | `setDefaultBankAccount` | 设为默认账户（同租户其余账户自动取消默认；收付款自动日记账的落点） |
| GET | `/fin/cash-journal` | `queryCashJournal` | 现金/银行日记账（当日余额 = 上日余额 + 借 − 贷，余额逐笔连续） |
| POST | `/fin/cash-journal` | `addCashJournalEntry` | 手工补录日记账（利息 / 银行间调拨等；自动延续余额链并回写账户余额） |
| GET | `/fin/expenses` | `pageFinExpenses` | 报销单分页（status / keyword 过滤） |
| POST | `/fin/expenses` | `createFinExpense` | 新建报销单（明细合计 = 主表金额，落 DRAFT） |
| GET | `/fin/expenses/{id}` | `getFinExpense` | 报销单详情（含明细行） |
| PUT | `/fin/expenses/{id}` | `updateFinExpense` | 修改报销单（仅 DRAFT；驳回后同样是 DRAFT + rejectReason） |
| DELETE | `/fin/expenses/{id}` | `deleteFinExpense` | 删除报销单（仅 DRAFT） |
| POST | `/fin/expenses/{id}/submit` | `submitFinExpense` | 提交审批（DRAFT → SUBMITTED） |
| POST | `/fin/expenses/{id}/approve` | `approveFinExpense` | 审批通过（SUBMITTED → APPROVED；权限 fin:expense:approve，与申请人分离） |
| POST | `/fin/expenses/{id}/reject` | `rejectFinExpense` | 驳回（SUBMITTED → DRAFT，原因写 reject_reason，必填） |
| POST | `/fin/expenses/{id}/pay` | `payFinExpense` | 付款（APPROVED → PAID，自动生成凭证 借6602/贷1002 并落银行日记账） |
| GET | `/fin/invoices` | `pageFinInvoices` | 发票台账分页（type = OUTPUT 销项 / INPUT 进项，页面两个 Tab） |
| POST | `/fin/invoices` | `createFinInvoice` | 登记发票（可关联进货单/出货单；同一单据累计开票不得超过单据金额） |
| GET | `/fin/invoices/{id}` | `getFinInvoice` | 发票详情 |
| PUT | `/fin/invoices/{id}` | `updateFinInvoice` | 修改发票（仅 NORMAL；改额同样受"同一单据不超额"约束） |
| DELETE | `/fin/invoices/{id}` | `deleteFinInvoice` | 删除发票（台账订正用；业务作废走 /void） |
| POST | `/fin/invoices/{id}/void` | `voidFinInvoice` | 作废发票（释放该单据的已开额度；超额校验只统计 NORMAL） |
| GET | `/fin/reports/balance-sheet` | `getBalanceSheet` | 资产负债表（只统计 POSTED 凭证；资产 = 负债 + 权益，diff 必须回传） |
| GET | `/fin/reports/income-statement` | `getIncomeStatement` | 利润表（本期：营业收入 − 营业成本 − 费用 = 净利润；同时给出毛利） |
| GET | `/fin/overview` | `getFinanceOverview` | 财务中心首页（资金余额 / 应收总额 / 应付总额 / 本月收支 + 近 6 个月现金流） |
| GET | `/prm/price-plans` | `listPricePlans` | 价格方案列表（含明细行） |
| POST | `/prm/price-plans` | `createPricePlan` | 新建价格方案（AGREEMENT/CUSTOMER/CUSTOMER_LEVEL） |
| GET | `/prm/price-plans/{id}` | `getPricePlan` | 价格方案详情 |
| PUT | `/prm/price-plans/{id}` | `updatePricePlan` | 全量替换方案头与明细 |
| POST | `/prm/price-plans/{id}/disable` | `disablePricePlan` | 停用方案 |
| GET | `/prm/price` | `resolvePrice` | 取价试算（协议价>专属价>等级价>标准售价；纯查询幂等） |
| GET | `/prm/promotions` | `listPromotions` | 促销活动列表（台账；规则 JSON 原样回显，不参与算价） |
| POST | `/prm/promotions` | `createPromotion` | 新建促销活动（初始 DRAFT） |
| POST | `/prm/promotions/{id}/activate` | `activatePromotion` | DRAFT/STOPPED → ACTIVE |
| POST | `/prm/promotions/{id}/stop` | `stopPromotion` | ACTIVE → STOPPED |

