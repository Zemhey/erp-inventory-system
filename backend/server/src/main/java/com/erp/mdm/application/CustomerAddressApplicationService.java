package com.erp.mdm.application;

import com.erp.mdm.domain.CustomerAddress;
import com.erp.mdm.repository.CustomerAddressRepository;
import com.erp.mdm.repository.CustomerRepository;
import com.erp.shared.db.DbErrors;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.idem.IdempotencyReplay;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 客户收货地址应用服务（V18）。
 *
 * <p><b>默认地址的三条规则</b>：
 * <ol>
 *   <li>同一客户最多一个默认；首条启用地址自动成为默认；</li>
 *   <li>切换默认 = 先摘旧默认、再置新默认，全程锁客户行；</li>
 *   <li>停用默认地址时，自动把最早创建的启用地址提升为默认 ——
 *       不留下“有可用地址但一个默认都没有”的状态。</li>
 * </ol>
 *
 * <p>停用不物理删除：历史出货单只引用 {@code customer_id}，地址变更不会改写历史。
 */
@Service
public class CustomerAddressApplicationService {

    private final CustomerAddressRepository addresses;
    private final CustomerRepository customers;

    public CustomerAddressApplicationService(CustomerAddressRepository addresses,
                                             CustomerRepository customers) {
        this.addresses = addresses;
        this.customers = customers;
    }

    public record CreateCommand(String addressKey, String recipientName, String phone,
                                String province, String city, String district,
                                String detailAddress, Boolean isDefault, Boolean enabled,
                                String requestId) {
    }

    /** 编码（addressKey）创建后不可改；null 表示保持原值。 */
    public record UpdateCommand(String recipientName, String phone, String province,
                                String city, String district, String detailAddress,
                                Boolean isDefault, Boolean enabled) {
    }

    @Transactional
    public CustomerAddress create(long customerId, CreateCommand cmd) {
        lockCustomer(customerId);
        var replay = addresses.findByRequestId(cmd.requestId());
        if (replay.isPresent()) {
            if (replay.get().customerId() != customerId) {
                throw new BusinessException(ErrorCode.SYS_DUPLICATE_REQUEST,
                        "幂等键已用于另一个客户的地址：requestId=%s".formatted(cmd.requestId()));
            }
            IdempotencyReplay.markCurrentRequest();
            return replay.get();
        }

        boolean enabled = cmd.enabled() == null || cmd.enabled();
        boolean requestedDefault = Boolean.TRUE.equals(cmd.isDefault());
        if (requestedDefault && !enabled) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "停用的地址不能设为默认");
        }
        boolean makeDefault = enabled
                && (requestedDefault || addresses.countEnabled(customerId) == 0);
        if (makeDefault) {
            addresses.clearDefault(customerId);
        }

        long id = addresses.insert(customerId,
                ProductApplicationService.requireText(cmd.addressKey(), "地址键"),
                ProductApplicationService.requireText(cmd.recipientName(), "收件人"),
                ProductApplicationService.requireText(cmd.phone(), "联系电话"),
                ProductApplicationService.requireText(cmd.province(), "省"),
                ProductApplicationService.requireText(cmd.city(), "市"),
                ProductApplicationService.trimToNull(cmd.district()),
                ProductApplicationService.requireText(cmd.detailAddress(), "详细地址"),
                makeDefault, enabled, cmd.requestId());
        return require(customerId, id);
    }

    @Transactional(readOnly = true)
    public List<CustomerAddress> list(long customerId, boolean onlyEnabled) {
        requireCustomer(customerId);
        return addresses.findAll(customerId, onlyEnabled);
    }

    @Transactional(readOnly = true)
    public CustomerAddress require(long customerId, long id) {
        requireCustomer(customerId);
        return addresses.findById(customerId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.MDM_ADDRESS_NOT_FOUND,
                        "客户地址不存在：customerId=%d, addressId=%d".formatted(customerId, id)));
    }

    @Transactional
    public CustomerAddress update(long customerId, long id, UpdateCommand cmd) {
        lockCustomer(customerId);
        CustomerAddress current = require(customerId, id);

        boolean targetEnabled = cmd.enabled() == null ? current.enabled() : cmd.enabled();
        boolean targetDefault = cmd.isDefault() == null ? current.isDefault() : cmd.isDefault();
        if (!targetEnabled) {
            if (Boolean.TRUE.equals(cmd.isDefault())) {
                throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                        "停用的地址不能设为默认");
            }
            targetDefault = false;
        }
        if (current.isDefault() && !targetDefault && targetEnabled) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "不能直接取消默认地址；请把另一个启用地址设为默认");
        }
        if (targetDefault && !current.isDefault()) {
            addresses.clearDefault(customerId);
        }

        addresses.update(customerId, id,
                ProductApplicationService.requireText(cmd.recipientName(), "收件人"),
                ProductApplicationService.requireText(cmd.phone(), "联系电话"),
                ProductApplicationService.requireText(cmd.province(), "省"),
                ProductApplicationService.requireText(cmd.city(), "市"),
                ProductApplicationService.trimToNull(cmd.district()),
                ProductApplicationService.requireText(cmd.detailAddress(), "详细地址"),
                targetDefault, targetEnabled);

        if (current.isDefault() && !targetEnabled) {
            promoteDefault(customerId, id);
        }
        return require(customerId, id);
    }

    @Transactional
    public CustomerAddress setDefault(long customerId, long id) {
        lockCustomer(customerId);
        CustomerAddress current = require(customerId, id);
        if (!current.enabled()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "停用的地址不能设为默认：addressId=%d".formatted(id));
        }
        if (current.isDefault()) {
            return current;
        }
        addresses.clearDefault(customerId);
        try {
            if (!addresses.markDefault(customerId, id)) {
                throw new BusinessException(ErrorCode.MDM_ADDRESS_NOT_FOUND,
                        "客户地址不存在：customerId=%d, addressId=%d".formatted(customerId, id));
            }
        } catch (DataIntegrityViolationException ex) {
            BusinessException conflict = DbErrors.translate(ex, "uq_customer_address_default",
                    ErrorCode.MDM_ADDRESS_DUPLICATED, "该客户已有默认地址");
            if (conflict != null) {
                throw conflict;
            }
            throw ex;
        }
        return require(customerId, id);
    }

    @Transactional
    public CustomerAddress disable(long customerId, long id) {
        lockCustomer(customerId);
        CustomerAddress current = require(customerId, id);
        if (!current.enabled()) {
            return current;
        }
        addresses.disable(customerId, id);
        if (current.isDefault()) {
            promoteDefault(customerId, id);
        }
        return require(customerId, id);
    }

    @Transactional
    public CustomerAddress enable(long customerId, long id) {
        lockCustomer(customerId);
        CustomerAddress current = require(customerId, id);
        if (current.enabled()) {
            return current;
        }
        addresses.enable(customerId, id);
        return require(customerId, id);
    }

    // ─────────────────────────── 内部辅助 ───────────────────────────

    private void lockCustomer(long customerId) {
        customers.findByIdForUpdate(customerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SYS_NOT_FOUND,
                        "客户不存在：id=%d".formatted(customerId)));
    }

    private void requireCustomer(long customerId) {
        customers.findById(customerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SYS_NOT_FOUND,
                        "客户不存在：id=%d".formatted(customerId)));
    }

    private void promoteDefault(long customerId, long excludedId) {
        addresses.findFirstEnabledIdExcept(customerId, excludedId)
                .ifPresent(nextId -> addresses.markDefault(customerId, nextId));
    }
}
