package com.erp.mdm.api;

import com.erp.mdm.application.SupplierApplicationService;
import com.erp.mdm.domain.Supplier;
import com.erp.iam.security.RequirePermission;
import com.erp.shared.web.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
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

/** 供应商接口（契约：{@code /suppliers}）。 */
@RestController
@RequestMapping("/api/v1/suppliers")
public class SupplierController {

    private final SupplierApplicationService suppliers;

    public SupplierController(SupplierApplicationService suppliers) {
        this.suppliers = suppliers;
    }

    public record CreateRequest(
            @NotBlank @Size(min = 2, max = 32) String code,
            @NotBlank @Size(max = 128) String name,
            @Size(max = 64) String contactName,
            @Size(max = 32) String contactPhone,
            @Size(max = 200) String address,
            Boolean enabled) {
    }

    public record UpdateRequest(
            @NotBlank @Size(max = 128) String name,
            @Size(max = 64) String contactName,
            @Size(max = 32) String contactPhone,
            @Size(max = 200) String address,
            Boolean enabled) {
    }

    @GetMapping
    @RequirePermission("mdm:read")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponses.page(suppliers.page(keyword, page, size)));
    }

    @GetMapping("/{id}")
    @RequirePermission("mdm:read")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(suppliers.require(id))));
    }

    @PostMapping
    @RequirePermission("mdm:write")
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateRequest req) {
        Supplier s = suppliers.create(new SupplierApplicationService.CreateCommand(
                req.code(), req.name(), req.contactName(), req.contactPhone(), req.address(),
                req.enabled() == null || req.enabled()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponses.ok(toMap(s)));
    }

    @PutMapping("/{id}")
    @RequirePermission("mdm:write")
    public ResponseEntity<Map<String, Object>> update(@PathVariable long id,
                                                      @Valid @RequestBody UpdateRequest req) {
        Supplier s = suppliers.update(id, new SupplierApplicationService.UpdateCommand(
                req.name(), req.contactName(), req.contactPhone(), req.address(),
                req.enabled() == null || req.enabled()));
        return ResponseEntity.ok(ApiResponses.ok(toMap(s)));
    }

    @PostMapping("/{id}/disable")
    @RequirePermission("mdm:write")
    public ResponseEntity<Map<String, Object>> disable(@PathVariable long id) {
        suppliers.disable(id);
        return ResponseEntity.ok(ApiResponses.ok(toMap(suppliers.require(id))));
    }

    private static Map<String, Object> toMap(Supplier s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("code", s.code());
        m.put("name", s.name());
        m.put("contactName", s.contactName());
        m.put("contactPhone", s.contactPhone());
        m.put("address", s.address());
        m.put("enabled", s.enabled());
        return m;
    }
}
