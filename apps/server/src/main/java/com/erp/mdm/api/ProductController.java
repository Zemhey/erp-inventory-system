package com.erp.mdm.api;

import com.erp.mdm.application.ProductApplicationService;
import com.erp.mdm.domain.Product;
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

/**
 * 商品接口（契约：{@code /products}）。
 *
 * <p>本层只做参数绑定/校验与 DTO 转换，业务规则在 {@link ProductApplicationService}。
 */
@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductApplicationService products;

    public ProductController(ProductApplicationService products) {
        this.products = products;
    }

    public record CreateRequest(
            @NotBlank @Size(min = 2, max = 32) String code,
            @NotBlank @Size(max = 128) String name,
            @Size(max = 64) String category,
            @Size(max = 32) String season,
            Boolean enabled) {
    }

    public record UpdateRequest(
            @NotBlank @Size(max = 128) String name,
            @Size(max = 64) String category,
            @Size(max = 32) String season,
            Boolean enabled) {
    }

    @GetMapping
    @RequirePermission("mdm:read")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponses.page(products.page(keyword, page, size)));
    }

    @GetMapping("/{id}")
    @RequirePermission("mdm:read")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(products.require(id))));
    }

    @PostMapping
    @RequirePermission("mdm:write")
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateRequest req) {
        Product p = products.create(new ProductApplicationService.CreateCommand(
                req.code(), req.name(), req.category(), req.season(),
                req.enabled() == null || req.enabled()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponses.ok(toMap(p)));
    }

    @PutMapping("/{id}")
    @RequirePermission("mdm:write")
    public ResponseEntity<Map<String, Object>> update(@PathVariable long id,
                                                      @Valid @RequestBody UpdateRequest req) {
        Product p = products.update(id, new ProductApplicationService.UpdateCommand(
                req.name(), req.category(), req.season(),
                req.enabled() == null || req.enabled()));
        return ResponseEntity.ok(ApiResponses.ok(toMap(p)));
    }

    /** 停用（软删）。契约未定义 DELETE，用 PUT 语义化的子路径动作。 */
    @PostMapping("/{id}/disable")
    @RequirePermission("mdm:write")
    public ResponseEntity<Map<String, Object>> disable(@PathVariable long id) {
        products.disable(id);
        return ResponseEntity.ok(ApiResponses.ok(toMap(products.require(id))));
    }

    private static Map<String, Object> toMap(Product p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id());
        m.put("code", p.code());
        m.put("name", p.name());
        m.put("category", p.category());
        m.put("season", p.season());
        m.put("enabled", p.enabled());
        return m;
    }
}
