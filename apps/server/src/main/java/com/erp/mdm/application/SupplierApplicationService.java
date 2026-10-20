package com.erp.mdm.application;

import com.erp.mdm.domain.Supplier;
import com.erp.mdm.repository.SupplierRepository;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
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
                                String contactPhone, String address, boolean enabled) {
    }

    public record UpdateCommand(String name, String contactName, String contactPhone,
                                String address, boolean enabled) {
    }

    @Transactional
    public Supplier create(CreateCommand cmd) {
        long id = suppliers.insert(
                ProductApplicationService.requireText(cmd.code(), "供应商编码"),
                ProductApplicationService.requireText(cmd.name(), "供应商名称"),
                ProductApplicationService.trimToNull(cmd.contactName()),
                ProductApplicationService.trimToNull(cmd.contactPhone()),
                ProductApplicationService.trimToNull(cmd.address()),
                cmd.enabled());
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

    @Transactional
    public void disable(long id) {
        require(id);
        if (!suppliers.disable(id)) {
            throw new BusinessException(ErrorCode.SYS_NOT_FOUND,
                    "供应商不存在：id=%d".formatted(id));
        }
    }
}
