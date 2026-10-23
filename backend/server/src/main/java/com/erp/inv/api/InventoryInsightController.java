package com.erp.inv.api;

import com.erp.iam.security.RequirePermission;
import com.erp.inv.application.InventoryInsightApplicationService;
import com.erp.inv.application.InventoryInsightApplicationService.BalanceDimensionRow;
import com.erp.inv.domain.AlertType;
import com.erp.inv.domain.InventoryAlert;
import com.erp.inv.domain.Ownership;
import com.erp.inv.domain.ReplenishmentSuggestion;
import com.erp.inv.domain.StockStatus;
import com.erp.shared.web.ApiResponses;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 库存洞察接口（V2-1）：五维结存 / 预警 / 补货建议。
 *
 * <p>全部为 GET 只读接口，不要求 {@code X-Request-Id}；
 * 补货建议天然幂等（纯查询、不落表）。
 */
@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryInsightController {

    private final InventoryInsightApplicationService insights;

    public InventoryInsightController(InventoryInsightApplicationService insights) {
        this.insights = insights;
    }

    /**
     * 五维结存（FR-INV-11）：仓 × SKU × 批次 × 状态 × 权属。
     *
     * <p>{@code totals} 给出总量与各状态合计，便于页面直接展示；
     * 对任一 (仓, SKU, 批次, 权属)，各状态之和恒等于 {@code totals.onHandQty}。
     */
    @GetMapping("/balance-dimensions")
    @RequirePermission("inv:read")
    public ResponseEntity<Map<String, Object>> balanceDimensions(
            @RequestParam(required = false) Long skuId,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) String batchNo,
            @RequestParam(required = false) StockStatus status,
            @RequestParam(required = false) Ownership ownership) {
        List<BalanceDimensionRow> rows = insights.balanceDimensions(
                skuId, warehouseId, batchNo, status, ownership);
        List<Map<String, Object>> items = new ArrayList<>();
        int total = 0;
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (StockStatus s : StockStatus.values()) {
            byStatus.put(s.name(), 0);
        }
        for (BalanceDimensionRow row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("warehouseId", row.warehouseId());
            m.put("skuId", row.skuId());
            m.put("batchNo", row.batchNo());
            m.put("stockStatus", row.stockStatus().name());
            m.put("ownership", row.ownership().name());
            m.put("qty", row.qty());
            items.add(m);
            total += row.qty();
            byStatus.merge(row.stockStatus().name(), row.qty(), Integer::sum);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("totals", Map.of("onHandQty", total, "byStatus", byStatus));
        return ResponseEntity.ok(ApiResponses.ok(data));
    }

    /** 库存预警（FR-INV-12）：安全库存 / 滞销 / 临期，时间基准为注入时钟。 */
    @GetMapping("/alerts")
    @RequirePermission("inv:read")
    public ResponseEntity<Map<String, Object>> alerts(
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) AlertType type,
            @RequestParam(required = false) Integer expiringDays,
            @RequestParam(required = false) Integer slowMovingDays) {
        List<InventoryAlert> alerts = insights.alerts(warehouseId, type,
                expiringDays, slowMovingDays);
        List<Map<String, Object>> items = new ArrayList<>();
        for (InventoryAlert a : alerts) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", a.type().name());
            m.put("skuId", a.skuId());
            m.put("warehouseId", a.warehouseId());
            m.put("batchNo", a.batchNo());
            m.put("onHandQty", a.onHandQty());
            m.put("availableQty", a.availableQty());
            m.put("threshold", a.threshold());
            m.put("expiryDate", a.expiryDate() == null ? null : a.expiryDate().toString());
            m.put("lastMovementAt", a.lastMovementAt() == null ? null : a.lastMovementAt().toString());
            m.put("warehousePriority", a.warehousePriority());
            items.add(m);
        }
        return ResponseEntity.ok(ApiResponses.ok(Map.of("items", items, "total", items.size())));
    }

    /** 补货建议（FR-INV-13）：低于 min 补到 max，已下单未到货的 SKU 不出现。 */
    @GetMapping("/replenishment")
    @RequirePermission("inv:read")
    public ResponseEntity<Map<String, Object>> replenishment(
            @RequestParam(required = false) Long warehouseId) {
        List<ReplenishmentSuggestion> suggestions = insights.replenishment(warehouseId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (ReplenishmentSuggestion s : suggestions) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("skuId", s.skuId());
            m.put("warehouseId", s.warehouseId());
            m.put("availableQty", s.availableQty());
            m.put("minLevel", s.minLevel());
            m.put("maxLevel", s.maxLevel());
            m.put("suggestedQty", s.suggestedQty());
            m.put("warehousePriority", s.warehousePriority());
            items.add(m);
        }
        return ResponseEntity.ok(ApiResponses.ok(Map.of("items", items, "total", items.size())));
    }
}
