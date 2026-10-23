package com.erp.mdm.application;

import com.erp.mdm.domain.Warehouse;
import com.erp.mdm.repository.WarehouseRepository;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 仓库应用服务（FR-MDM-04）。
 *
 * <p>仓库停用后不允许再新建单据（由 PUR/SAL 建单时校验），
 * 但其中的库存依然可查、可出库 —— 停用是"不再收新业务"，不是"冻结库存"。
 */
@Service
public class WarehouseApplicationService {

    private final WarehouseRepository warehouses;

    public WarehouseApplicationService(WarehouseRepository warehouses) {
        this.warehouses = warehouses;
    }

    public record CreateCommand(String code, String name, String address, boolean enabled,
                                Integer priority) {

        public CreateCommand(String code, String name, String address, boolean enabled) {
            this(code, name, address, enabled, null);
        }
    }

    public record UpdateCommand(String name, String address, boolean enabled, Integer priority) {

        public UpdateCommand(String name, String address, boolean enabled) {
            this(name, address, enabled, null);
        }
    }

    @Transactional
    public Warehouse create(CreateCommand cmd) {
        long id = warehouses.insert(
                ProductApplicationService.requireText(cmd.code(), "仓库编码"),
                ProductApplicationService.requireText(cmd.name(), "仓库名称"),
                ProductApplicationService.trimToNull(cmd.address()),
                cmd.enabled(),
                cmd.priority() == null ? 100 : requirePriority(cmd.priority()));
        return require(id);
    }

    @Transactional(readOnly = true)
    public Warehouse require(long id) {
        return warehouses.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.MDM_WAREHOUSE_NOT_FOUND,
                        "仓库不存在：id=%d".formatted(id)));
    }

    @Transactional(readOnly = true)
    public List<Warehouse> list(boolean onlyEnabled) {
        return warehouses.findAll(onlyEnabled);
    }

    @Transactional
    public Warehouse update(long id, UpdateCommand cmd) {
        Warehouse current = require(id);
        boolean ok = warehouses.update(id,
                ProductApplicationService.requireText(cmd.name(), "仓库名称"),
                ProductApplicationService.trimToNull(cmd.address()),
                cmd.enabled(),
                cmd.priority() == null ? current.priority() : requirePriority(cmd.priority()));
        if (!ok) {
            throw new BusinessException(ErrorCode.MDM_WAREHOUSE_NOT_FOUND,
                    "仓库不存在：id=%d".formatted(id));
        }
        return require(id);
    }

    private static int requirePriority(int priority) {
        if (priority < 0) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "仓库优先级不能为负：%d".formatted(priority));
        }
        return priority;
    }

    @Transactional
    public void disable(long id) {
        require(id);
        if (!warehouses.disable(id)) {
            throw new BusinessException(ErrorCode.MDM_WAREHOUSE_NOT_FOUND,
                    "仓库不存在：id=%d".formatted(id));
        }
    }
}
