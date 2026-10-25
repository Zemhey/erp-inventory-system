package com.erp.mdm.api;

import com.erp.mdm.application.ProductAttributeApplicationService;
import com.erp.mdm.domain.ProductAttribute;
import com.erp.iam.security.RequirePermission;
import com.erp.shared.web.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 商品规格属性接口（V18）。 */
@RestController
@RequestMapping("/api/v1/products/{productId}/attributes")
public class ProductAttributeController {

    private final ProductAttributeApplicationService attributes;

    public ProductAttributeController(ProductAttributeApplicationService attributes) {
        this.attributes = attributes;
    }

    public record AttributeItem(
            @NotBlank @Size(max = 32) String key,
            @NotBlank @Size(max = 256) String value) {
    }

    public record ReplaceRequest(
            @NotNull @Size(max = 50) List<@Valid AttributeItem> attributes) {
    }

    @GetMapping
    @RequirePermission("mdm:read")
    public ResponseEntity<Map<String, Object>> list(@PathVariable long productId) {
        return ResponseEntity.ok(ApiResponses.ok(toItems(attributes.list(productId))));
    }

    /** 全量替换；空数组表示清空。重复 PUT 同一份数据结果一致。 */
    @PutMapping
    @RequirePermission("mdm:write")
    public ResponseEntity<Map<String, Object>> replace(
            @PathVariable long productId,
            @Valid @RequestBody ReplaceRequest req) {
        List<ProductAttribute> values = req.attributes().stream()
                .map(item -> new ProductAttribute(item.key(), item.value()))
                .toList();
        return ResponseEntity.ok(ApiResponses.ok(
                toItems(attributes.replace(productId, values))));
    }

    private static Map<String, Object> toItems(List<ProductAttribute> values) {
        List<Map<String, Object>> items = values.stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", a.key());
            m.put("value", a.value());
            return m;
        }).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        return data;
    }
}
