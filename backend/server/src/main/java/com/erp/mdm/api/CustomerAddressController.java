package com.erp.mdm.api;

import com.erp.mdm.application.CustomerAddressApplicationService;
import com.erp.mdm.domain.CustomerAddress;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 客户收货地址接口（V18）。 */
@RestController
@RequestMapping("/api/v1/customers/{customerId}/addresses")
public class CustomerAddressController {

    private final CustomerAddressApplicationService addresses;

    public CustomerAddressController(CustomerAddressApplicationService addresses) {
        this.addresses = addresses;
    }

    public record CreateRequest(
            @NotBlank @Size(max = 32) String addressKey,
            @NotBlank @Size(max = 64) String recipientName,
            @NotBlank @Size(max = 32) String phone,
            @NotBlank @Size(max = 32) String province,
            @NotBlank @Size(max = 32) String city,
            @Size(max = 32) String district,
            @NotBlank @Size(max = 200) String detailAddress,
            Boolean isDefault,
            Boolean enabled) {
    }

    public record UpdateRequest(
            @NotBlank @Size(max = 64) String recipientName,
            @NotBlank @Size(max = 32) String phone,
            @NotBlank @Size(max = 32) String province,
            @NotBlank @Size(max = 32) String city,
            @Size(max = 32) String district,
            @NotBlank @Size(max = 200) String detailAddress,
            Boolean isDefault,
            Boolean enabled) {
    }

    @GetMapping
    @RequirePermission("mdm:read")
    public ResponseEntity<Map<String, Object>> list(
            @PathVariable long customerId,
            @RequestParam(defaultValue = "false") boolean onlyEnabled) {
        List<Map<String, Object>> items = addresses.list(customerId, onlyEnabled)
                .stream().map(CustomerAddressController::toMap).toList();
        return ResponseEntity.ok(ApiResponses.ok(items));
    }

    @PostMapping
    @RequirePermission("mdm:write")
    public ResponseEntity<Map<String, Object>> create(
            @PathVariable long customerId,
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @Valid @RequestBody CreateRequest req) {
        CustomerAddress created = addresses.create(customerId,
                new CustomerAddressApplicationService.CreateCommand(
                        req.addressKey(), req.recipientName(), req.phone(),
                        req.province(), req.city(), req.district(), req.detailAddress(),
                        req.isDefault(), req.enabled(), requestId));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponses.ok(toMap(created)));
    }

    @PutMapping("/{addressId}")
    @RequirePermission("mdm:write")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable long customerId, @PathVariable long addressId,
            @Valid @RequestBody UpdateRequest req) {
        CustomerAddress updated = addresses.update(customerId, addressId,
                new CustomerAddressApplicationService.UpdateCommand(
                        req.recipientName(), req.phone(), req.province(), req.city(),
                        req.district(), req.detailAddress(), req.isDefault(), req.enabled()));
        return ResponseEntity.ok(ApiResponses.ok(toMap(updated)));
    }

    /** 切换默认地址（纯状态，无幂等键）。 */
    @PostMapping("/{addressId}/default")
    @RequirePermission("mdm:write")
    public ResponseEntity<Map<String, Object>> setDefault(
            @PathVariable long customerId, @PathVariable long addressId) {
        return ResponseEntity.ok(ApiResponses.ok(
                toMap(addresses.setDefault(customerId, addressId))));
    }

    /** 停用（软删）；若停的是默认地址，后端自动提升下一条启用地址。 */
    @PostMapping("/{addressId}/disable")
    @RequirePermission("mdm:write")
    public ResponseEntity<Map<String, Object>> disable(
            @PathVariable long customerId, @PathVariable long addressId) {
        return ResponseEntity.ok(ApiResponses.ok(
                toMap(addresses.disable(customerId, addressId))));
    }

    @PostMapping("/{addressId}/enable")
    @RequirePermission("mdm:write")
    public ResponseEntity<Map<String, Object>> enable(
            @PathVariable long customerId, @PathVariable long addressId) {
        return ResponseEntity.ok(ApiResponses.ok(
                toMap(addresses.enable(customerId, addressId))));
    }

    private static Map<String, Object> toMap(CustomerAddress a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.id());
        m.put("customerId", a.customerId());
        m.put("addressKey", a.addressKey());
        m.put("recipientName", a.recipientName());
        m.put("phone", a.phone());
        m.put("province", a.province());
        m.put("city", a.city());
        m.put("district", a.district());
        m.put("detailAddress", a.detailAddress());
        m.put("isDefault", a.isDefault());
        m.put("enabled", a.enabled());
        return m;
    }
}
