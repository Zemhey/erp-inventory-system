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
    SYS_DICT_TYPE_NOT_FOUND(404);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
