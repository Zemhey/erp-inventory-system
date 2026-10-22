package com.erp.set.application;

import com.erp.set.repository.SettlementPeriodRepository;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.period.PeriodGuard;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@link PeriodGuard} 的实现：账期锁校验（计划书 §3.4）。
 *
 * <p><b>为什么实现放 {@code set.application}、接口放 {@code shared.period}</b>：
 * 锁账后 PUR/SAL/RMA 都要拒绝写入。若它们直接 import {@code com.erp.set.*}，
 * 而 SET 又要读这三域的单据做汇总，包依赖立刻成环。
 * 依赖倒置之后，ArchUnit 有一条可以执行的规则：
 * <i>pur/sal/rma 不得依赖 {@code com.erp.set..}，只能依赖 {@code shared.period}</i>。
 *
 * <p><b>只认 CLOSED</b>：查不到行、或状态是 OPEN，都放行。
 * 这条判断是 M1 的 170 条基线不被误伤的前提 —— 生产默认数据不建期间行，
 * 测试库万一残留一行 OPEN 也不会让进货/出货随机变红（计划书 §6 坑 3）。
 */
@Component
public class SettlementPeriodGuard implements PeriodGuard {

    private final SettlementPeriodRepository periods;

    public SettlementPeriodGuard(SettlementPeriodRepository periods) {
        this.periods = periods;
    }

    /**
     * 账期已锁则抛 {@code SET_PERIOD_CLOSED}。
     *
     * <p>消息里带上<b>账期</b>而没有带"哪个动作"：调用点遍布三域，
     * 在这里拼动作名要么加参数、要么靠堆栈猜，两者都不如让调用方的
     * {@code BusinessException} 文案去描述动作。账期是用户唯一需要的定位信息。
     */
    @Override
    public void requireOpen(LocalDate bizDate) {
        if (bizDate == null) {
            return;
        }
        String period = periodOf(bizDate);
        if (periods.isClosed(period)) {
            throw new BusinessException(ErrorCode.SET_PERIOD_CLOSED,
                    "账期 %s 已月结锁账（业务日期 %s 落在该期间），不允许再新增或变更该期间的单据"
                            .formatted(period, bizDate),
                    Map.of("period", period, "bizDate", bizDate.toString()));
        }
    }

    private static String periodOf(LocalDate bizDate) {
        return "%04d-%02d".formatted(bizDate.getYear(), bizDate.getMonthValue());
    }
}
