package com.erp.sal.api;

import com.erp.iam.security.RequirePermission;
import com.erp.sal.application.ProductBundleService;
import com.erp.sal.domain.BundleItem;
import com.erp.sal.domain.ProductBundle;
import com.erp.shared.web.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
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

/** 组合商品/套装接口（FR-SAL-10）。 */
@RestController
@RequestMapping("/api/v1/product-bundles")
public class ProductBundleController {

    private final ProductBundleService bundles;

    public ProductBundleController(ProductBundleService bundles) {
        this.bundles = bundles;
    }

    public record ItemRequest(@NotNull Long componentSkuId,
                              @NotNull @Min(1) Integer ratioQty) {
    }

    public record CreateRequest(
            @NotBlank @Size(min = 2, max = 32) String code,
            @NotBlank @Size(max = 128) String name,
            @Min(0) Long priceAmount,
            Boolean enabled,
            @NotEmpty @Valid List<ItemRequest> items) {
    }

    public record UpdateRequest(
            @NotBlank @Size(max = 128) String name,
            @Min(0) Long priceAmount,
            Boolean enabled,
            @NotEmpty @Valid List<ItemRequest> items) {
    }

    @GetMapping
    @RequirePermission("sal:read")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "false") boolean onlyEnabled,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponses.page(
                bundles.page(keyword, onlyEnabled, page, size)));
    }

    @GetMapping("/{id}")
    @RequirePermission("sal:read")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(bundles.require(id))));
    }

    @PostMapping
    @RequirePermission("sal:bundle")
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateRequest req) {
        ProductBundle bundle = bundles.create(new ProductBundleService.CreateCommand(
                req.code(), req.name(), req.priceAmount() == null ? 0L : req.priceAmount(),
                req.enabled() == null || req.enabled(), toCommands(req.items())));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponses.ok(toMap(bundle)));
    }

    @PutMapping("/{id}")
    @RequirePermission("sal:bundle")
    public ResponseEntity<Map<String, Object>> update(@PathVariable long id,
                                                      @Valid @RequestBody UpdateRequest req) {
        ProductBundle bundle = bundles.update(id, new ProductBundleService.UpdateCommand(
                req.name(), req.priceAmount() == null ? 0L : req.priceAmount(),
                req.enabled() == null || req.enabled(), toCommands(req.items())));
        return ResponseEntity.ok(ApiResponses.ok(toMap(bundle)));
    }

    @PostMapping("/{id}/disable")
    @RequirePermission("sal:bundle")
    public ResponseEntity<Map<String, Object>> disable(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(bundles.disable(id))));
    }

    /** 套装可用量 = min(组件可用 ÷ 配比)；套装本身无库存行（INV-16）。 */
    @GetMapping("/{id}/availability")
    @RequirePermission("sal:read")
    public ResponseEntity<Map<String, Object>> availability(
            @PathVariable long id,
            @RequestParam(required = false) Long warehouseId) {
        var availability = bundles.availability(id, warehouseId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bundleId", availability.bundleId());
        m.put("warehouseId", availability.warehouseId());
        m.put("availableQty", availability.availableQty());
        List<Map<String, Object>> components = new ArrayList<>();
        for (var c : availability.components()) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("componentSkuId", c.componentSkuId());
            cm.put("ratioQty", c.ratioQty());
            cm.put("availableQty", c.availableQty());
            cm.put("maxBundles", c.maxBundles());
            components.add(cm);
        }
        m.put("components", components);
        return ResponseEntity.ok(ApiResponses.ok(m));
    }

    private static List<ProductBundleService.ItemCommand> toCommands(List<ItemRequest> items) {
        return items.stream()
                .map(i -> new ProductBundleService.ItemCommand(i.componentSkuId(), i.ratioQty()))
                .toList();
    }

    static Map<String, Object> toMap(ProductBundle b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.id());
        m.put("code", b.code());
        m.put("name", b.name());
        m.put("priceAmount", b.priceAmount());
        m.put("enabled", b.enabled());
        List<Map<String, Object>> items = new ArrayList<>();
        for (BundleItem item : b.items()) {
            Map<String, Object> im = new LinkedHashMap<>();
            im.put("id", item.id());
            im.put("componentSkuId", item.componentSkuId());
            im.put("ratioQty", item.ratioQty());
            items.add(im);
        }
        m.put("items", items);
        return m;
    }
}
