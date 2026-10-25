package com.erp.mdm.application;

import com.erp.mdm.domain.Warehouse;
import com.erp.mdm.domain.WarehouseLocation;
import com.erp.mdm.repository.WarehouseLocationRepository;
import com.erp.mdm.repository.WarehouseRepository;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.idem.IdempotencyReplay;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 仓库库位应用服务（V18）。
 *
 * <p><b>守恒口径</b>：{@code SUM(启用库位 capacity_qty) == warehouses.capacity_qty}。
 * 仓库容量 0 表示未设定，此时不设上限；建/改/启用库位时若将超过仓库容量则 409。
 * 允许暂时分配不足，由核对端点把差额如实报出来。
 *
 * <p>所有容量校验与写入都在仓库行锁内完成；库位停用只改自身状态，
 * 不触碰库存余额与流水（库存维度仍是 SKU×仓库×批次）。
 */
@Service
public class WarehouseLocationApplicationService {

    private final WarehouseLocationRepository locations;
    private final WarehouseRepository warehouses;

    public WarehouseLocationApplicationService(WarehouseLocationRepository locations,
                                               WarehouseRepository warehouses) {
        this.locations = locations;
        this.warehouses = warehouses;
    }

    public record CreateCommand(String locationCode, String name, Long capacityQty,
                                Boolean enabled, String requestId) {
    }

    /** 编码创建后不可改；null 表示保持原值。 */
    public record UpdateCommand(String name, Long capacityQty) {
    }

    /** 守恒核对结果：等式两侧与差额一次给全，前端不自行重算。 */
    public record Reconcile(long warehouseId, long warehouseCapacityQty,
                            long locationCapacityQty, long difference,
                            int enabledLocationCount, int totalLocationCount,
                            boolean consistent) {
    }

    @Transactional
    public WarehouseLocation create(long warehouseId, CreateCommand cmd) {
        Warehouse warehouse = lockWarehouse(warehouseId);
        var replay = locations.findByRequestId(cmd.requestId());
        if (replay.isPresent()) {
            if (replay.get().warehouseId() != warehouseId) {
                throw new BusinessException(ErrorCode.SYS_DUPLICATE_REQUEST,
                        "幂等键已用于另一个仓库的库位：requestId=%s".formatted(cmd.requestId()));
            }
            IdempotencyReplay.markCurrentRequest();
            return replay.get();
        }

        boolean enabled = cmd.enabled() == null || cmd.enabled();
        long capacity = requirePositiveCapacity(cmd.capacityQty());
        ensureWithinWarehouse(warehouse, capacity, enabled, 0L);

        long id = locations.insert(warehouseId,
                ProductApplicationService.requireText(cmd.locationCode(), "库位编码"),
                ProductApplicationService.trimToNull(cmd.name()),
                capacity, enabled, cmd.requestId());
        return require(warehouseId, id);
    }

    @Transactional(readOnly = true)
    public List<WarehouseLocation> list(long warehouseId, boolean onlyEnabled) {
        requireWarehouse(warehouseId);
        return locations.findAll(warehouseId, onlyEnabled);
    }

    @Transactional(readOnly = true)
    public WarehouseLocation require(long warehouseId, long id) {
        requireWarehouse(warehouseId);
        return requireLocation(warehouseId, id);
    }

    @Transactional
    public WarehouseLocation update(long warehouseId, long id, UpdateCommand cmd) {
        Warehouse warehouse = lockWarehouse(warehouseId);
        WarehouseLocation current = requireLocation(warehouseId, id);
        long capacity = cmd.capacityQty() == null
                ? current.capacityQty() : requirePositiveCapacity(cmd.capacityQty());
        ensureWithinWarehouse(warehouse, capacity, current.enabled(), id);
        locations.update(warehouseId, id,
                ProductApplicationService.trimToNull(cmd.name()), capacity);
        return require(warehouseId, id);
    }

    @Transactional
    public WarehouseLocation disable(long warehouseId, long id) {
        lockWarehouse(warehouseId);
        WarehouseLocation current = requireLocation(warehouseId, id);
        if (!current.enabled()) {
            return current;
        }
        locations.disable(warehouseId, id);
        return require(warehouseId, id);
    }

    @Transactional
    public WarehouseLocation enable(long warehouseId, long id) {
        Warehouse warehouse = lockWarehouse(warehouseId);
        WarehouseLocation current = requireLocation(warehouseId, id);
        if (current.enabled()) {
            return current;
        }
        ensureWithinWarehouse(warehouse, current.capacityQty(), true, id);
        locations.enable(warehouseId, id);
        return require(warehouseId, id);
    }

    /** 设置仓库容量（0 = 未设定）；不允许收缩到低于当前启用库位合计。 */
    @Transactional
    public Warehouse setCapacity(long warehouseId, long capacityQty) {
        Warehouse warehouse = lockWarehouse(warehouseId);
        if (capacityQty < 0) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "仓库容量不能为负：%d".formatted(capacityQty));
        }
        long used = locations.sumEnabledCapacity(warehouseId, 0L);
        if (capacityQty > 0 && used > capacityQty) {
            throw new BusinessException(ErrorCode.MDM_LOCATION_CAPACITY_EXCEEDED,
                    ("仓库容量不能小于已启用库位容量合计：已用=%d，目标容量=%d")
                            .formatted(used, capacityQty));
        }
        if (!warehouses.updateCapacity(warehouse.id(), capacityQty)) {
            throw new BusinessException(ErrorCode.MDM_WAREHOUSE_NOT_FOUND,
                    "仓库不存在：id=%d".formatted(warehouseId));
        }
        return requireWarehouse(warehouseId);
    }

    @Transactional(readOnly = true)
    public Reconcile reconcile(long warehouseId) {
        Warehouse warehouse = requireWarehouse(warehouseId);
        long used = locations.sumEnabledCapacity(warehouseId, 0L);
        return new Reconcile(warehouseId, warehouse.capacityQty(), used,
                warehouse.capacityQty() - used,
                locations.count(warehouseId, true), locations.count(warehouseId, false),
                warehouse.capacityQty() == used);
    }

    // ─────────────────────────── 内部辅助 ───────────────────────────

    private Warehouse lockWarehouse(long warehouseId) {
        return warehouses.findByIdForUpdate(warehouseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MDM_WAREHOUSE_NOT_FOUND,
                        "仓库不存在：id=%d".formatted(warehouseId)));
    }

    private Warehouse requireWarehouse(long warehouseId) {
        return warehouses.findById(warehouseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MDM_WAREHOUSE_NOT_FOUND,
                        "仓库不存在：id=%d".formatted(warehouseId)));
    }

    private WarehouseLocation requireLocation(long warehouseId, long id) {
        return locations.findById(warehouseId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.MDM_LOCATION_NOT_FOUND,
                        "库位不存在：warehouseId=%d, locationId=%d"
                                .formatted(warehouseId, id)));
    }

    private void ensureWithinWarehouse(Warehouse warehouse, long capacity, boolean enabled,
                                       long excludeLocationId) {
        if (!enabled || warehouse.capacityQty() <= 0) {
            return;
        }
        long used = locations.sumEnabledCapacity(warehouse.id(), excludeLocationId);
        if (used + capacity > warehouse.capacityQty()) {
            throw new BusinessException(ErrorCode.MDM_LOCATION_CAPACITY_EXCEEDED,
                    ("启用库位容量合计将超过仓库容量：已用=%d，本库位=%d，仓库容量=%d")
                            .formatted(used, capacity, warehouse.capacityQty()));
        }
    }

    private static long requirePositiveCapacity(Long capacityQty) {
        if (capacityQty == null || capacityQty <= 0) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "库位容量必须为正：%s".formatted(capacityQty));
        }
        return capacityQty;
    }
}
