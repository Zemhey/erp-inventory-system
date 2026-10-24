package com.erp.mdm.application;

import com.erp.mdm.domain.Supplier;
import com.erp.mdm.repository.SupplierRepository;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.idem.IdempotencyReplay;
import com.erp.shared.web.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 供应商应用服务（FR-MDM-02）。
 */
@Service
public class SupplierApplicationService {

    private final SupplierRepository suppliers;

    public SupplierApplicationService(SupplierRepository suppliers) {
        this.suppliers = suppliers;
    }

    public record CreateCommand(String code, String name, String contactName,
                                String contactPhone, String address, boolean enabled,
                                String requestId) {

        public CreateCommand(String code, String name, String contactName,
                             String contactPhone, String address, boolean enabled) {
            this(code, name, contactName, contactPhone, address, enabled, null);
        }
    }

    public record UpdateCommand(String name, String contactName, String contactPhone,
                                String address, boolean enabled) {
    }

    /**
     * 建供应商（FR-MDM-02 + V2-C1 幂等）。
     *
     * <p>幂等键的回查-插入-并发兜底三步与 {@link WarehouseApplicationService#create} 完全同构，
     * 理由（含"为什么编码不做先查后插、幂等键却要"）也在那里，不重复一遍。
     */
    @Transactional
    public Supplier create(CreateCommand cmd) {
        var replay = suppliers.findByRequestId(cmd.requestId());
        if (replay.isPresent()) {
            IdempotencyReplay.markCurrentRequest();
            return replay.get();
        }
        long id = suppliers.insert(
                ProductApplicationService.requireText(cmd.code(), "供应商编码"),
                ProductApplicationService.requireText(cmd.name(), "供应商名称"),
                ProductApplicationService.trimToNull(cmd.contactName()),
                ProductApplicationService.trimToNull(cmd.contactPhone()),
                ProductApplicationService.trimToNull(cmd.address()),
                cmd.enabled(),
                cmd.requestId());
        return require(id);
    }

    @Transactional(readOnly = true)
    public Supplier require(long id) {
        return suppliers.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.SYS_NOT_FOUND,
                        "供应商不存在：id=%d".formatted(id)));
    }

    @Transactional(readOnly = true)
    public PageResult<Supplier> page(String keyword, int page, int size) {
        return suppliers.page(keyword, page, size);
    }

    @Transactional
    public Supplier update(long id, UpdateCommand cmd) {
        require(id);
        boolean ok = suppliers.update(id,
                ProductApplicationService.requireText(cmd.name(), "供应商名称"),
                ProductApplicationService.trimToNull(cmd.contactName()),
                ProductApplicationService.trimToNull(cmd.contactPhone()),
                ProductApplicationService.trimToNull(cmd.address()),
                cmd.enabled());
        if (!ok) {
            throw new BusinessException(ErrorCode.SYS_NOT_FOUND,
                    "供应商不存在：id=%d".formatted(id));
        }
        return require(id);
    }

    /** 停用供应商，不带幂等键。 */
    @Transactional
    public void disable(long id) {
        require(id);
        if (!suppliers.disable(id)) {
            throw new BusinessException(ErrorCode.SYS_NOT_FOUND,
                    "供应商不存在：id=%d".formatted(id));
        }
    }

    /**
     * 停用供应商（V2-C1 幂等）。0 行（已是停用态）判为重放、不留第二条审计；
     * 语义与 {@link WarehouseApplicationService#disable(long, String)} 完全一致。
     */
    @Transactional
    public void disable(long id, String requestId) {
        require(id);
        if (!suppliers.disable(id, requestId)) {
            IdempotencyReplay.markCurrentRequest();
        }
    }
}
