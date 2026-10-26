package com.erp.shared.error;

/**
 * 业务错误码表（docs/05-接口契约规范.md §4）。
 *
 * <p>约定：HTTP 状态码表达<b>类别</b>（4xx/5xx），业务错误码表达<b>具体原因</b>。
 * 不使用"200 包错误"，否则契约测试与监控都会失效。
 */
public enum ErrorCode {

    // ── 通用 ──
    SYS_VALIDATION_ERROR(400),
    SYS_DUPLICATE_REQUEST(409),
    SYS_NOT_FOUND(404),

    // ── 主数据 ──
    MDM_SKU_NOT_FOUND(404),
    MDM_SKU_DUPLICATED(409),
    MDM_CODE_DUPLICATED(409),
    MDM_WAREHOUSE_NOT_FOUND(404),

    // ── 认证与权限（FR-IAM-01/02）──
    /** 用户名或密码错误。刻意不区分"用户不存在"，防用户名枚举。 */
    IAM_BAD_CREDENTIALS(401),
    /** 未登录 / token 无效或过期。 */
    IAM_UNAUTHENTICATED(401),
    /** 已登录但无该操作权限（FR-IAM-02 要求返回 403）。 */
    IAM_FORBIDDEN(403),
    /** 账号已停用。 */
    IAM_ACCOUNT_DISABLED(403),
    /** 密码连续错误达到阈值而锁定。 */
    IAM_ACCOUNT_LOCKED(423),
    /**
     * 密码不符合复杂度策略（FR-IAM-06）：长度/字符类别/常见弱口令。
     *
     * <p>与 {@code SYS_VALIDATION_ERROR} 分开：前端要能据这个码<b>跳到改密页</b>
     * 并显示具体缺哪条规则，而不是笼统提示"参数错误"。
     */
    IAM_PASSWORD_WEAK(400),
    /** 密码已过期或被标记为必须修改（FR-IAM-06）：除改密接口外一律拒绝。 */
    IAM_PASSWORD_EXPIRED(403),
    /** 用户不存在（FR-IAM-05 用户与角色管理）。 */
    IAM_USER_NOT_FOUND(404),
    /** 角色不存在，或引用了未定义的角色编码。 */
    IAM_ROLE_NOT_FOUND(404),
    /** 用户名已存在（撞 {@code uq_app_user_username}）。 */
    IAM_USER_DUPLICATED(409),

    // ── 审批流（FR-IAM-07、FR-APS-01/02）──
    /** 审批状态非法转移（含"已终态的审批再来一次同意/驳回"）。 */
    APS_STATUS_ILLEGAL(409),
    /** 审批实例不存在。 */
    APS_INSTANCE_NOT_FOUND(404),
    /** 审批流定义不存在、未启用，或配置不合法（无节点、阈值倒挂）。 */
    APS_FLOW_NOT_FOUND(404),
    APS_FLOW_INVALID(400),
    /** 该单据需要审批，但还没有提交审批实例。 */
    APS_APPROVAL_REQUIRED(409),
    /** 该单据的审批仍在进行中 —— 必须先完成审批才能审核单据。 */
    APS_APPROVAL_PENDING(409),
    /** 当前账号不是该节点的处理人（不具备节点要求的权限码）。 */
    APS_NOT_APPROVER(403),
    /** 同一张单据已有未结的审批实例。 */
    APS_INSTANCE_ALREADY_OPEN(409),

    // ── 库存 ──
    INV_INSUFFICIENT_STOCK(409),
    INV_RESERVE_FAILED(409),
    INV_BALANCE_MISMATCH(409),
    INV_NEGATIVE_NOT_ALLOWED(409),
    INV_RESERVATION_NOT_FOUND(404),
    /** 调拨单不存在。 */
    INV_TRANSFER_NOT_FOUND(404),
    /** 调拨单状态不允许该动作（如非在途单执行到货）。 */
    INV_TRANSFER_STATUS_ILLEGAL(409),
    /** 盘点单不存在。 */
    INV_STOCKTAKE_NOT_FOUND(404),
    /** 盘点单状态不允许该动作（如已过账再次过账）。 */
    INV_STOCKTAKE_STATUS_ILLEGAL(409),
    /** 盘点单全部行差异为 0，没有可过账的调整。 */
    INV_STOCKTAKE_NO_DIFFERENCE(409),
    /** 冻结记录不存在。 */
    INV_FREEZE_NOT_FOUND(404),
    /** 冻结记录状态不允许该动作（如已解冻再次解冻）。 */
    INV_FREEZE_STATUS_ILLEGAL(409),
    /** 冻结量超过可用量（已被预占或冻结）。 */
    INV_FREEZE_EXCEEDS_AVAILABLE(409),
    /** 库存调整必须填写原因。 */
    INV_ADJUST_REASON_REQUIRED(400),

    // ── 进货 / 出货 ──
    PUR_ORDER_NOT_FOUND(404),
    PUR_STATUS_ILLEGAL(409),
    PUR_OVER_RECEIVE(409),

    // ── 采购闭环 V2-2（FR-PUR-04~08）──
    /** 请购单不存在。 */
    PUR_REQ_NOT_FOUND(404),
    /** 请购单状态不允许提交/审核/转化（含已转化后再次转化）。 */
    PUR_REQ_STATUS_ILLEGAL(409),
    /** 转化数量超过请购数量。 */
    PUR_REQ_CONVERT_EXCEED(409),
    /** 到货单不存在。 */
    PUR_RECEIPT_NOT_FOUND(404),
    /** 合格数量越界：< 0 或 > 本次到货量。 */
    PUR_QC_QTY_INVALID(400),
    /** 采购退货单或原收货行不存在。 */
    PUR_RETURN_NOT_FOUND(404),
    /** 退货单状态不允许执行/作废。 */
    PUR_RETURN_STATUS_ILLEGAL(409),
    /** 退货数量超过可退量（合格量 − 已退货量）。 */
    PUR_RETURN_QTY_EXCEED(409),
    /** 退货金额超过该到货单应付余额（已付款部分不能被退货冲掉）。 */
    PUR_RETURN_EXCEED_PAYABLE(409),
    /** 应付单不存在。 */
    PUR_PAYABLE_NOT_FOUND(404),
    /** 核销明细合计 ≠ 付款单金额，或金额非正/应付重复。 */
    PUR_PAYMENT_AMOUNT_MISMATCH(400),
    /** 核销的应付不属于该供应商。 */
    PUR_PAYMENT_SUPPLIER_MISMATCH(400),
    /** 核销额超过应付余额（业务冲突，409 —— 不是登录态 401）。 */
    PUR_PAYMENT_EXCEED_PAYABLE(409),

    SAL_ORDER_NOT_FOUND(404),
    SAL_STATUS_ILLEGAL(409),

    // ── 销售闭环（V2-3 FR-SAL-05 ~ 11）──
    SAL_QUOTE_NOT_FOUND(404),
    SAL_QUOTE_STATUS_ILLEGAL(409),
    SAL_SHIPMENT_NOT_FOUND(404),
    SAL_SHIPMENT_STATUS_ILLEGAL(409),
    SAL_SHIPMENT_OVER_PICK(409),
    SAL_RECEIVABLE_NOT_FOUND(404),
    SAL_RECEIPT_UNBALANCED(409),
    SAL_RECEIPT_OVER_ALLOCATED(409),
    SAL_CREDIT_LIMIT_EXCEEDED(409),
    MDM_BUNDLE_NOT_FOUND(404),
    MDM_BUNDLE_DUPLICATED(409),
    MDM_BUNDLE_DISABLED(409),

    // ── 退货（RMA）──
    RMA_ORDER_NOT_FOUND(404),
    RMA_STATUS_ILLEGAL(409),
    RMA_ORIGIN_NOT_SHIPPED(409),
    RMA_QTY_EXCEED_ORIGIN(409),

    // ── 结算 ──
    SET_PERIOD_CLOSED(409),
    SET_PERIOD_NOT_CLOSED(409),
    SET_RECONCILE_MISMATCH(409),
    SET_ALREADY_SETTLED(409),

    // ── 自然语言操作（FR-SYS-04）──
    /** 解析器无法把文本映射为已支持的意图（含 LLM 未返回 tool call）。 */
    NL_COMMAND_UNRECOGNIZED(422),
    /** 意图已识别，但参数缺失/类型非法/枚举取值非法。 */
    NL_COMMAND_INVALID_ARGUMENT(400),
    /** 配置为真实 LLM 但解析器不可用：key 缺失、网络/超时、上游响应非法。 */
    NL_PARSER_UNAVAILABLE(503),

    // ── 审计与可观测（V2-5 / FR-SYS-06~09）──
    // ★ 只追加在末尾，不重排任何既有码：本批多条泳道同时改本文件，
    //   只有"纯追加"git 才能自动合并（见 .plan/V2-5-计划书.md §2.3）。
    /** 审计记录不存在（按 id 查）。 */
    SYS_AUDIT_NOT_FOUND(404),
    /** 审计查询条件非法（时间区间倒置、单据号为空等）。 */
    SYS_AUDIT_QUERY_INVALID(400),
    /** 字典类型不存在。 */
    SYS_DICT_TYPE_NOT_FOUND(404),

    // ── 多租户（V2-7 · FR-TNT-04）──
    SYS_TENANT_CONTEXT_MISSING(500),

    // ── 多租户 UI / 平台租户管理（B1 · FR-TNT-07/08）──
    /** 已登录，但不是目标租户的成员（登录可选 tenantCode / 切换租户时拒绝）。 */
    IAM_TENANT_NOT_MEMBER(403),
    /** 目标租户已停用：登录、切换、以及该租户的既有 token 全部拒绝。 */
    IAM_TENANT_DISABLED(403),
    /** 平台租户管理里按 id 找不到租户（只有 tenant:manage 持有者可见此错误）。 */
    TENANT_NOT_FOUND(404),
    /** 租户状态操作非法（如停用平台租户、停用当前所在租户）。 */
    TENANT_STATE_ILLEGAL(409),

    // ── 主数据字段扩展（Batch B · B2，纯追加）──
    /** 客户收货地址不存在（含不属于该客户 / 不属于当前租户 —— 统一 404 防枚举）。 */
    MDM_ADDRESS_NOT_FOUND(404),
    /** 地址键在客户内重复，或建单幂等键被另一个客户复用。 */
    MDM_ADDRESS_DUPLICATED(409),
    /** 仓库库位不存在（含不属于该仓库 / 当前租户）。 */
    MDM_LOCATION_NOT_FOUND(404),
    /** 库位编码在仓库内重复。 */
    MDM_LOCATION_DUPLICATED(409),
    /** 启用库位容量合计将超过仓库容量。 */
    MDM_LOCATION_CAPACITY_EXCEEDED(409),
    /** 商品规格属性请求非法（键重复、键/值超长、条数超限）。 */
    MDM_PRODUCT_ATTRIBUTE_INVALID(400),

    // ── 骨架组（V3-1 · FR-V3-23/24/25/29，纯追加）──
    /** 组织架构：部门不存在（含不属于当前租户 —— 统一 404 防枚举）。 */
    SYS_DEPARTMENT_NOT_FOUND(404),
    /** 部门编码在当前租户内重复。 */
    SYS_DEPARTMENT_CODE_DUPLICATED(409),
    /** 岗位不存在（含不属于当前租户）。 */
    SYS_POSITION_NOT_FOUND(404),
    /** 岗位编码在当前租户内重复。 */
    SYS_POSITION_CODE_DUPLICATED(409),
    /** 系统参数：配置键不存在（含不属于当前租户）。 */
    SYS_CONFIG_NOT_FOUND(404),
    /** 配置键在当前租户/分组内重复。 */
    SYS_CONFIG_KEY_DUPLICATED(409),
    /** 单号规则：单据类型不存在对应规则。 */
    SYS_NUMBER_RULE_NOT_FOUND(404),
    /** 单号规则：预览时日期/格式解析失败。 */
    SYS_NUMBER_RULE_INVALID(400),
    /** 登录日志：记录不存在（含不属于当前租户）。 */
    SYS_LOGIN_LOG_NOT_FOUND(404),

    // ── 财务中心（V3-2 · W2，纯追加）──
    /** 凭证借贷不平（details 带 totalDebit/totalCredit/diff，前端直接显示差额）。 */
    FIN_VOUCHER_UNBALANCED(400),
    /** 凭证不存在。 */
    FIN_VOUCHER_NOT_FOUND(404),
    /** 凭证状态不允许该动作（如已过账再改/再审）。 */
    FIN_VOUCHER_STATUS_ILLEGAL(409),
    /** 分录引用了非末级科目或停用科目。 */
    FIN_VOUCHER_LINE_INVALID(400),
    /** 会计科目不存在。 */
    FIN_ACCOUNT_NOT_FOUND(404),
    /** 科目编码在本租户已存在（撞 uq_fin_account_code）。 */
    FIN_ACCOUNT_DUPLICATED(409),
    /** 科目被凭证分录引用，不许删除（FR-V3-06 验收口径：409）。 */
    FIN_ACCOUNT_IN_USE(409),
    /** 科目有期初余额或分录余额时不许停用/改类别。 */
    FIN_ACCOUNT_HAS_BALANCE(409),
    /** 银行账户不存在。 */
    FIN_BANK_ACCOUNT_NOT_FOUND(404),
    /** 报销单不存在。 */
    FIN_EXPENSE_NOT_FOUND(404),
    /** 报销单状态不允许该动作（提交/审批/付款/驳回的非法转移）。 */
    FIN_EXPENSE_STATUS_ILLEGAL(409),
    /** 报销明细合计与主表金额不一致。 */
    FIN_EXPENSE_AMOUNT_MISMATCH(400),
    /** 发票不存在。 */
    FIN_INVOICE_NOT_FOUND(404),
    /** 同一单据累计开票金额超过单据金额（简报 FR-V3-11 验收口径：409）。 */
    FIN_INVOICE_OVER_AMOUNT(409),
    /** 发票已作废后不允许再改状态/再作废。 */
    FIN_INVOICE_STATUS_ILLEGAL(409),
    /** 生成凭证所依赖的标准科目缺失（租户科目模板被破坏时响亮失败）。 */
    FIN_ACCOUNTING_TEMPLATE_MISSING(500),
    /** 财务报表查询期间非法（格式非 yyyy-MM 或区间倒置）。 */
    FIN_REPORT_PERIOD_INVALID(400),
    /** 财务域写入的账期已被月结锁定（与 SET_PERIOD_CLOSED 分码：前端按模块提示）。 */
    FIN_PERIOD_CLOSED(409),

    // ── V3-3 供应链补口：PRM 价格与促销（纯追加）──
    /** 价格方案按 id 不存在。 */
    PRM_PLAN_NOT_FOUND(404),
    /** 价格方案编码重复。 */
    PRM_PLAN_CODE_DUPLICATED(409),
    /** 价格方案内容校验失败（明细为空、日期倒挂、类型必填项缺失、价格/折扣越界）。 */
    PRM_PRICE_PLAN_INVALID(400),
    /** 促销活动按 id 不存在。 */
    PRM_PROMOTION_NOT_FOUND(404),
    /** 促销活动编码重复。 */
    PRM_PROMOTION_CODE_DUPLICATED(409),

    // ── V4-1 接驳：凭证删除（纯追加，勿在此前任何条目上改动 —— Flyway 之外的 checksum 依赖）──
    /**
     * 凭证不是草稿，不允许删除（FR-V3-07）。
     *
     * <p>与 {@code FIN_VOUCHER_STATUS_ILLEGAL} 分码：那个码同时覆盖"非草稿不可改/不可重复审核"
     * 等多种非法转移，前端要按它做通用提示；而"删除被拒"必须给出<b>另一套</b>指引 ——
     * AUDITED/POSTED 的正解不是"改成草稿再删"，而是走既有的作废/红冲语义
     * （已进报表的凭证删掉会让期初与流水对不上，那是数据事故而不是便利功能）。
     */
    FIN_VOUCHER_DELETE_FORBIDDEN(409);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
