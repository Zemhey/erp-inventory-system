package com.erp.set.application;

import com.erp.set.domain.SettlementPeriodRow;
import com.erp.set.domain.SettlementPeriodStatus;
import com.erp.set.repository.SettlementPeriodQueryRepository;
import com.erp.shared.web.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账期查询服务（<b>只读</b>，计划书 §6 坑 15 的补口）。
 *
 * <p>存在的唯一理由：日清 / 月结是 POST、对账要 {@code set:reconcile}，
 * 于是只有 {@code set:read} 的账号进得了结算页却拿不到任何数据，
 * 月结结果（{@code status / closedAt}）刷新即失。本服务给出 {@code set:read}
 * 唯一可消费的读出口，因此刻意<b>不</b>挂在
 * {@link SettlementApplicationService} 上 —— 那个类的职责是"把账算出来并写下去"，
 * 混进读路径会让"谁能改账本"重新变得看不清。
 */
@Service
public class SettlementPeriodQueryService {

    private final SettlementPeriodQueryRepository periods;

    public SettlementPeriodQueryService(SettlementPeriodQueryRepository periods) {
        this.periods = periods;
    }

    /**
     * 分页查账期。
     *
     * @param status null = 全部状态（OPEN 与 CLOSED 一起给）
     */
    @Transactional(readOnly = true)
    public PageResult<SettlementPeriodRow> page(SettlementPeriodStatus status, int page, int size) {
        return periods.page(status, page, size);
    }
}
