package com.erp.mdm.api;

import com.erp.mdm.application.WarehouseLocationApplicationService;
import com.erp.mdm.domain.Warehouse;
import com.erp.mdm.domain.WarehouseLocation;
import com.erp.iam.security.RequirePermission;
import com.erp.shared.web.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 仓库库位与容量核对接口（V18）。 */
@RestController
@RequestMapping("/api/v1/warehouses/{warehouseId}")
public class WarehouseLocationController {

    private final WarehouseLocationApplicationService locations;

    public WarehouseLocationController(WarehouseLocationApplicationService locations) {
        this.locations = locations;
    }

    public record CreateRequest(
            @NotBlank @Size(max = 32) String locationCode,
            @Size(max = 64) String name,
            @NotNull @Min(1) Long capacityQty,
            Boolean enabled) {
    }

    public record UpdateRequest(
            @Size(max = 64) String name,
            @NotNull @Min(1) Long capacityQty) {
    }

    public record CapacityRequest(@NotNull @Min(0) Long capacityQty) {
    }

    @GetMapping("/locations")
    @RequirePermission("mdm:read")
    public ResponseEntity<Map<String, Object>> list(
            @PathVariable long warehouseId,
            @RequestParam(defaultValue = "false") boolean onlyEnabled) {
        List<Map<String, Object>> items = locations.list(warehouseId, onlyEnabled)
                .stream().map(WarehouseLocationController::toMap).toList();
        return ResponseEntity.ok(ApiResponses.ok(items));
    }

    @PostMapping("/locations")
    @RequirePermission("mdm:write")
    public ResponseEntity<Map<String, Object>> create(
            @PathVariable long warehouseId,
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @Valid @RequestBody CreateRequest req) {
        WarehouseLocation created = locations.create(warehouseId,
                new WarehouseLocationApplicationService.CreateCommand(
                        req.locationCode(), req.name(), req.capacityQty(),
                        req.enabled(), requestId));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponses.ok(toMap(created)));
    }

    @PutMapping("/locations/{locationId}")
    @RequirePermission("mdm:write")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable long warehouseId, @PathVariable long locationId,
            @Valid @RequestBody UpdateRequest req) {
        WarehouseLocation updated = locations.update(warehouseId, locationId,
                new WarehouseLocationApplicationService.UpdateCommand(
                        req.name(), req.capacityQty()));
        return ResponseEntity.ok(ApiResponses.ok(toMap(updated)));
    }

    @PostMapping("/locations/{locationId}/disable")
    @RequirePermission("mdm:write")
    public ResponseEntity<Map<String, Object>> disable(
            @PathVariable long warehouseId, @PathVariable long locationId) {
        return ResponseEntity.ok(ApiResponses.ok(
                toMap(locations.disable(warehouseId, locationId))));
    }

    @PostMapping("/locations/{locationId}/enable")
    @RequirePermission("mdm:write")
    public ResponseEntity<Map<String, Object>> enable(
            @PathVariable long warehouseId, @PathVariable long locationId) {
        return ResponseEntity.ok(ApiResponses.ok(
                toMap(locations.enable(warehouseId, locationId))));
    }

    /** 守恒核对：启用库位容量合计 vs 仓库容量。 */
    @GetMapping("/locations/reconcile")
    @RequirePermission("mdm:read")
    public ResponseEntity<Map<String, Object>> reconcile(@PathVariable long warehouseId) {
        WarehouseLocationApplicationService.Reconcile r = locations.reconcile(warehouseId);
        return ResponseEntity.ok(ApiResponses.ok(toMap(r)));
    }

    @PutMapping("/capacity")
    @RequirePermission("mdm:write")
    public ResponseEntity<Map<String, Object>> setCapacity(
            @PathVariable long warehouseId,
            @Valid @RequestBody CapacityRequest req) {
        Warehouse warehouse = locations.setCapacity(warehouseId, req.capacityQty());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", warehouse.id());
        m.put("code", warehouse.code());
        m.put("name", warehouse.name());
        m.put("address", warehouse.address());
        m.put("enabled", warehouse.enabled());
        m.put("priority", warehouse.priority());
        m.put("capacityQty", warehouse.capacityQty());
        return ResponseEntity.ok(ApiResponses.ok(m));
    }

    private static Map<String, Object> toMap(WarehouseLocation l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.id());
        m.put("warehouseId", l.warehouseId());
        m.put("locationCode", l.locationCode());
        m.put("name", l.name());
        m.put("capacityQty", l.capacityQty());
        m.put("enabled", l.enabled());
        return m;
    }

    private static Map<String, Object> toMap(WarehouseLocationApplicationService.Reconcile r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("warehouseId", r.warehouseId());
        m.put("warehouseCapacityQty", r.warehouseCapacityQty());
        m.put("locationCapacityQty", r.locationCapacityQty());
        m.put("difference", r.difference());
        m.put("enabledLocationCount", r.enabledLocationCount());
        m.put("totalLocationCount", r.totalLocationCount());
        m.put("consistent", r.consistent());
        return m;
    }
}
