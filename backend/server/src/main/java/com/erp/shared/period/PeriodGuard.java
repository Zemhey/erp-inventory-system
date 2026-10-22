package com.erp.shared.period;

import java.time.LocalDate;

/**
 * 账期锁校验。接口放在 shared，避免单据模块直接依赖 com.erp.set 形成依赖环。
 */
public interface PeriodGuard {

    /** 账期已关闭时抛出 SET_PERIOD_CLOSED。 */
    void requireOpen(LocalDate bizDate);
}
