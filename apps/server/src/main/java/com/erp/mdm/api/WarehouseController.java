package com.erp.mdm.api;

import com.erp.mdm.application.WarehouseApplicationService;
import com.erp.mdm.domain.Warehouse;
import com.erp.iam.security.RequirePermission;
import com.erp.shared.web.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 仓库接口（契约：{@code /warehouses}，返回 List 而非分页）。 */
@RestController
@RequestMapping("/api/v1/warehouses")
public class WarehouseController {

    private final WarehouseApplicationService warehouses;

    public WarehouseController(WarehouseApplicationService warehouses) {
        this.warehouses = warehouses;
    }

    public record CreateRequest(
            @NotBlank @Size(min = 2, max = 32) String code,
            @NotBlank @Size(max = 64) String name,
            @Size(max = 200) String address,
            Boolean enabled) {
    }

    public record UpdateRequest(
            @NotBlank @Size(max = 64) String name,
            @Size(max = 200) String address,
            Boolean enabled) {
    }

    @GetMapping
    @RequirePermission("mdm:read")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(defaultValue = "false") boolean onlyEnabled) {
        List<Map<String, Object>> items = warehouses.list(onlyEnabled)
                .stream().map(WarehouseController::toMap).toList();
        return ResponseEntity.ok(ApiResponses.ok(items));
    }

    @GetMapping("/{id}")
    @RequirePermission("mdm:read")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(warehouses.require(id))));
    }

    @PostMapping
    @RequirePermission("mdm:write")
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateRequest req) {
        Warehouse w = warehouses.create(new WarehouseApplicationService.CreateCommand(
                req.code(), req.name(), req.address(), req.enabled() == null || req.enabled()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponses.ok(toMap(w)));
    }

    @PutMapping("/{id}")
    @RequirePermission("mdm:write")
    public ResponseEntity<Map<String, Object>> update(@PathVariable long id,
                                                      @Valid @RequestBody UpdateRequest req) {
        Warehouse w = warehouses.update(id, new WarehouseApplicationService.UpdateCommand(
                req.name(), req.address(), req.enabled() == null || req.enabled()));
        return ResponseEntity.ok(ApiResponses.ok(toMap(w)));
    }

    @PostMapping("/{id}/disable")
    @RequirePermission("mdm:write")
    public ResponseEntity<Map<String, Object>> disable(@PathVariable long id) {
        warehouses.disable(id);
        return ResponseEntity.ok(ApiResponses.ok(toMap(warehouses.require(id))));
    }

    private static Map<String, Object> toMap(Warehouse w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", w.id());
        m.put("code", w.code());
        m.put("name", w.name());
        m.put("address", w.address());
        m.put("enabled", w.enabled());
        return m;
    }
}
