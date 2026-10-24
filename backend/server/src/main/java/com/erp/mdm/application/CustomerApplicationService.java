package com.erp.mdm.application;

import com.erp.mdm.domain.Customer;
import com.erp.mdm.repository.CustomerRepository;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.idem.IdempotencyReplay;
import com.erp.shared.web.PageResult;
import java.util.Collection;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 客户/会员应用服务（FR-MDM-03）。
 *
 * <p>折扣率校验是本类的核心：{@code discountRate} 是整数万分比，
 * 合法区间 {@code (0, 10000]}。10000 = 不打折。
 * 允许 0 会造成"白送"，因此下界取开区间。
 */
@Service
public class CustomerApplicationService {

    /** 契约上限：10000 = 不打折。 */
    public static final int NO_DISCOUNT = 10000;

    private final CustomerRepository customers;

    public CustomerApplicationService(CustomerRepository customers) {
        this.customers = customers;
    }

    public record CreateCommand(String code, String name, String phone, int discountRate,
                                String level, boolean enabled, String requestId) {

        public CreateCommand(String code, String name, String phone, int discountRate,
                             String level, boolean enabled) {
            this(code, name, phone, discountRate, level, enabled, null);
        }
    }

    public record UpdateCommand(String name, String phone, int discountRate,
                                String level, boolean enabled) {
    }

    /**
     * 建客户（FR-MDM-03 + V2-C1 幂等）。
     *
     * <p>回查-插入-并发兜底三步与 {@link WarehouseApplicationService#create} 同构；
     * 幂等命中时直接返回首次那一行，连折扣率这类业务校验也不再重复跑
     * —— 重放要的语义是"把上次的结果给我"，不是"再判一次我有没有写错"。
     */
    @Transactional
    public Customer create(CreateCommand cmd) {
        var replay = customers.findByRequestId(cmd.requestId());
        if (replay.isPresent()) {
            IdempotencyReplay.markCurrentRequest();
            return replay.get();
        }
        int rate = requireValidDiscountRate(cmd.discountRate());
        long id = customers.insert(
                ProductApplicationService.requireText(cmd.code(), "客户编码"),
                ProductApplicationService.requireText(cmd.name(), "客户名称"),
                ProductApplicationService.trimToNull(cmd.phone()),
                rate,
                requireLevel(cmd.level()),
                cmd.enabled(),
                cmd.requestId());
        return require(id);
    }

    @Transactional(readOnly = true)
    public Customer require(long id) {
        return customers.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.SYS_NOT_FOUND,
                        "客户不存在：id=%d".formatted(id)));
    }

    @Transactional(readOnly = true)
    public PageResult<Customer> page(String keyword, int page, int size) {
        return customers.page(keyword, page, size);
    }

    /**
     * 按 id 批量取客户名（{@code id -> name}）。
     *
     * <p>给单据列表用的只读出口：跨模块展示客户名时不要循环 {@link #require(long)}，
     * 也不要"先拉 200 条客户再自己建字典"（超出 200 条的部分会显示成 {@code 客户#201}）。
     */
    @Transactional(readOnly = true)
    public Map<Long, String> findNamesByIds(Collection<Long> ids) {
        return customers.findNamesByIds(ids);
    }

    @Transactional
    public Customer update(long id, UpdateCommand cmd) {
        require(id);
        int rate = requireValidDiscountRate(cmd.discountRate());
        boolean ok = customers.update(id,
                ProductApplicationService.requireText(cmd.name(), "客户名称"),
                ProductApplicationService.trimToNull(cmd.phone()),
                rate,
                requireLevel(cmd.level()),
                cmd.enabled());
        if (!ok) {
            throw new BusinessException(ErrorCode.SYS_NOT_FOUND,
                    "客户不存在：id=%d".formatted(id));
        }
        return require(id);
    }

    /** 信用额度与账期（FR-SAL-09）；0 额度 = 不设限。 */
    public record CreditCommand(long creditLimit, int creditDays) {
    }

    @Transactional
    public Customer updateCredit(long id, CreditCommand cmd) {
        require(id);
        if (cmd.creditLimit() < 0 || cmd.creditDays() < 0) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "信用额度与账期不能为负：limit=%d, days=%d"
                            .formatted(cmd.creditLimit(), cmd.creditDays()));
        }
        if (!customers.updateCredit(id, cmd.creditLimit(), cmd.creditDays())) {
            throw new BusinessException(ErrorCode.SYS_NOT_FOUND,
                    "客户不存在：id=%d".formatted(id));
        }
        return require(id);
    }

    /** 停用客户，不带幂等键。 */
    @Transactional
    public void disable(long id) {
        require(id);
        if (!customers.disable(id)) {
            throw new BusinessException(ErrorCode.SYS_NOT_FOUND,
                    "客户不存在：id=%d".formatted(id));
        }
    }

    /**
     * 停用客户（V2-C1 幂等）。0 行（已是停用态）判为重放、不留第二条审计；
     * 语义与 {@link WarehouseApplicationService#disable(long, String)} 完全一致。
     */
    @Transactional
    public void disable(long id, String requestId) {
        require(id);
        if (!customers.disable(id, requestId)) {
            IdempotencyReplay.markCurrentRequest();
        }
    }

    // ─────────────────────────── 校验辅助 ───────────────────────────

    /** 折扣率必须落在 (0, 10000]，单位为万分比。 */
    static int requireValidDiscountRate(int rate) {
        if (rate <= 0 || rate > NO_DISCOUNT) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "折扣率非法：%d，必须是 1 ~ 10000 的整数万分比（10000 = 不打折）"
                            .formatted(rate));
        }
        return rate;
    }

    private static String requireLevel(String level) {
        String trimmed = ProductApplicationService.trimToNull(level);
        return trimmed == null ? "NORMAL" : trimmed;
    }
}
