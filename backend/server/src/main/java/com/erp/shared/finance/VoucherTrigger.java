package com.erp.shared.finance;

/**
 * 业务单据 → 会计凭证的触发出口（V3-2 FR-V3-08，依赖倒置）。
 *
 * <p><b>为什么接口在 shared、实现在 fin</b>（与 {@code shared.period.PeriodGuard}
 * 同一手法）：PUR / SAL 在"确认"节点后要求生成凭证，若直接 import
 * {@code com.erp.fin.application.FinVoucherApplicationService}，财务域反过来又
 * 要读三域单据表，包依赖面会随每个新单据类型越缠越紧。接口只回答一句话
 * —— "这张业务单据的账做完了，请出凭证"，凭证长什么样是财务域自己的事。
 *
 * <p><b>幂等契约</b>：同一 {@code (sourceType, sourceId)} 重复调用返回既有凭证，
 * 绝不生成第二张（简报 §4 FR-V3-08；数据库层由
 * {@code fin_voucher.uq_fin_voucher_source} 兜底）。
 * 实现必须在调用方事务内同步执行，让"单据确认"与"凭证生成"同生共死 ——
 * 业务动作与它的会计后果之间不允许存在只成功一半的窗口。
 *
 * <p><b>失败语义</b>：生成失败必须抛 {@code BusinessException}（如科目模板缺失），
 * 连同业务事务一起回滚；不允许"单据确认成功、凭证静默丢失"。
 */
public interface VoucherTrigger {

    /**
     * 为一张业务单据生成会计凭证。
     *
     * @param sourceType 来源类型：PUR_IN / SAL_OUT / PUR_PAY / SAL_RCV
     * @param sourceId   来源单据主键（各域自己的表主键）
     * @return 生成或已存在的凭证 id
     */
    long generateForSource(String sourceType, long sourceId);
}
