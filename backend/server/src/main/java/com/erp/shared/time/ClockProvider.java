package com.erp.shared.time;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 可注入时钟 —— 可测试性设计的核心钩子（docs/06-测试策略.md §3）。
 *
 * <p><b>为什么必须存在</b>：日清月结、批次效期（FEFO）、预占超时、账期归属
 * 全部依赖"现在几点"。若业务代码直接调 {@code LocalDateTime.now()}，
 * 跨月/跨年/闰月等边界就只能等到真实时间到来才能验证 —— 实际上无法自动化测试。
 *
 * <p><b>约定</b>：业务代码<b>禁止</b>直接调用 {@code now()}，
 * 一律通过本接口。M1 会用 ArchUnit 规则在 CI 中强制检查。
 */
public interface ClockProvider {

    LocalDateTime now();

    default LocalDate today() {
        return now().toLocalDate();
    }
}
