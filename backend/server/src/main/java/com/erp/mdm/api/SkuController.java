package com.erp.mdm.api;

import com.erp.mdm.application.SkuApplicationService;
import com.erp.mdm.domain.Sku;
import com.erp.iam.security.RequirePermission;
import com.erp.shared.web.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SKU 接口（契约：{@code /skus}）。
 *
 * <p>契约里 {@code POST /skus} 的 schema 是单条 {@code SkuCreate}。
 * 矩阵生成是本项目"服装行业真实需求"的体现，因此作为<b>同路径的另一个动作</b>
 * {@code POST /skus/matrix} 提供 —— 不复用单条接口去做"传数组或对象"的多态解析，
 * 那会让契约与校验都变得难以表达。
 */
@RestController
@RequestMapping("/api/v1/skus")
public class SkuController {

    private final SkuApplicationService skus;

    public SkuController(SkuApplicationService skus) {
        this.skus = skus;
    }

    public record CreateRequest(
            @NotNull Long productId,
            @NotBlank @Size(max = 32) String color,
            @NotBlank @Size(max = 16) String size,
            @Size(max = 64) String barcode,
            @Min(0) Long retailPriceAmount,
            Boolean enabled) {
    }

    public record MatrixRequest(
            @NotNull Long productId,
            @NotEmpty List<@NotBlank String> colors,
            @NotEmpty List<@NotBlank String> sizes,
            @Min(0) Long retailPriceAmount,
            Boolean enabled) {
    }

    public record UpdateRequest(
            @Size(max = 64) String barcode,
            @Min(0) Long retailPriceAmount,
            Boolean enabled) {
    }

    @GetMapping
    @RequirePermission("mdm:read")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) String color,
            @RequestParam(required = false) String size,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size_) {
        return ResponseEntity.ok(ApiResponses.page(skus.page(productId, color, size, page, size_)));
    }

    @GetMapping("/{id}")
    @RequirePermission("mdm:read")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(skus.require(id))));
    }

    @PostMapping
    @RequirePermission("mdm:write")
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateRequest req) {
        Sku s = skus.create(new SkuApplicationService.CreateCommand(
                req.productId(), req.color(), req.size(), req.barcode(),
                req.retailPriceAmount() == null ? 0L : req.retailPriceAmount(),
                req.enabled() == null || req.enabled()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponses.ok(toMap(s)));
    }

    /**
     * 颜色 × 尺码矩阵批量生成。
     *
     * <p>已存在的组合跳过（返回 {@code skipped} 计数），请求自身重复则报
     * {@code MDM_SKU_DUPLICATED}（语义见 {@link SkuApplicationService#createMatrix}）。
     */
    @PostMapping("/matrix")
    @RequirePermission("mdm:write")
    public ResponseEntity<Map<String, Object>> createMatrix(@Valid @RequestBody MatrixRequest req) {
        var result = skus.createMatrix(req.productId(), req.colors(), req.sizes(),
                req.retailPriceAmount() == null ? 0L : req.retailPriceAmount(),
                req.enabled() == null || req.enabled());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("created", result.created().stream().map(SkuController::toMap).toList());
        data.put("createdCount", result.created().size());
        data.put("skipped", result.skipped());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponses.ok(data));
    }

    @PutMapping("/{id}")
    @RequirePermission("mdm:write")
    public ResponseEntity<Map<String, Object>> update(@PathVariable long id,
                                                      @Valid @RequestBody UpdateRequest req) {
        Sku current = skus.require(id);
        Sku s = skus.update(id, req.barcode(),
                req.retailPriceAmount() == null ? current.retailPriceAmount() : req.retailPriceAmount(),
                req.enabled() == null || req.enabled());
        return ResponseEntity.ok(ApiResponses.ok(toMap(s)));
    }

    @PostMapping("/{id}/disable")
    @RequirePermission("mdm:write")
    public ResponseEntity<Map<String, Object>> disable(@PathVariable long id) {
        skus.disable(id);
        return ResponseEntity.ok(ApiResponses.ok(toMap(skus.require(id))));
    }

    private static Map<String, Object> toMap(Sku s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("productId", s.productId());
        m.put("color", s.color());
        m.put("size", s.size());
        m.put("barcode", s.barcode());
        m.put("retailPriceAmount", s.retailPriceAmount());
        m.put("enabled", s.enabled());
        return m;
    }
}
