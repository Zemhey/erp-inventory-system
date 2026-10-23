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

    // ── 进货 / 出货 ──
    PUR_ORDER_NOT_FOUND(404),
    PUR_STATUS_ILLEGAL(409),
    PUR_OVER_RECEIVE(409),
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
    NL_PARSER_UNAVAILABLE(503);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
