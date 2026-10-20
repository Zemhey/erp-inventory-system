package com.erp.mdm.api;

import com.erp.mdm.application.CustomerApplicationService;
import com.erp.mdm.domain.Customer;
import com.erp.iam.security.RequirePermission;
import com.erp.shared.web.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

/** 客户/会员接口（契约：{@code /customers}）。 */
@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {

    private final CustomerApplicationService customers;

    public CustomerController(CustomerApplicationService customers) {
        this.customers = customers;
    }

    public record CreateRequest(
            @NotBlank @Size(min = 2, max = 32) String code,
            @NotBlank @Size(max = 128) String name,
            @Size(max = 32) String phone,
            @Min(1) @Max(10000) Integer discountRate,
            @Size(max = 16) String level,
            Boolean enabled) {
    }

    public record UpdateRequest(
            @NotBlank @Size(max = 128) String name,
            @Size(max = 32) String phone,
            @Min(1) @Max(10000) Integer discountRate,
            @Size(max = 16) String level,
            Boolean enabled) {
    }

    @GetMapping
    @RequirePermission("mdm:read")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponses.page(customers.page(keyword, page, size)));
    }

    @GetMapping("/{id}")
    @RequirePermission("mdm:read")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(customers.require(id))));
    }

    @PostMapping
    @RequirePermission("mdm:write")
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateRequest req) {
        Customer c = customers.create(new CustomerApplicationService.CreateCommand(
                req.code(), req.name(), req.phone(),
                req.discountRate() == null ? CustomerApplicationService.NO_DISCOUNT : req.discountRate(),
                req.level(),
                req.enabled() == null || req.enabled()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponses.ok(toMap(c)));
    }

    @PutMapping("/{id}")
    @RequirePermission("mdm:write")
    public ResponseEntity<Map<String, Object>> update(@PathVariable long id,
                                                      @Valid @RequestBody UpdateRequest req) {
        Customer current = customers.require(id);
        Customer c = customers.update(id, new CustomerApplicationService.UpdateCommand(
                req.name(), req.phone(),
                req.discountRate() == null ? current.discountRate() : req.discountRate(),
                req.level(),
                req.enabled() == null || req.enabled()));
        return ResponseEntity.ok(ApiResponses.ok(toMap(c)));
    }

    @PostMapping("/{id}/disable")
    @RequirePermission("mdm:write")
    public ResponseEntity<Map<String, Object>> disable(@PathVariable long id) {
        customers.disable(id);
        return ResponseEntity.ok(ApiResponses.ok(toMap(customers.require(id))));
    }

    private static Map<String, Object> toMap(Customer c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id());
        m.put("code", c.code());
        m.put("name", c.name());
        m.put("phone", c.phone());
        m.put("discountRate", c.discountRate());
        m.put("level", c.level());
        m.put("enabled", c.enabled());
        return m;
    }
}
