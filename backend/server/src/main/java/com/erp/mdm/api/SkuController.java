package com.erp.mdm.api;

import com.erp.mdm.application.SkuApplicationService;
import com.erp.mdm.domain.Sku;
import com.erp.mdm.domain.SkuFilter;
import com.erp.iam.security.RequirePermission;
import com.erp.shared.web.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
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
            Boolean enabled,
            @Size(max = 32) String seasonCode,
            @Size(max = 32) String bandCode,
            LocalDate launchDate,
            @Min(0) Integer minLevel,
            @Min(0) Integer maxLevel) {
    }

    public record MatrixRequest(
            @NotNull Long productId,
            @NotEmpty List<@NotBlank String> colors,
            @NotEmpty List<@NotBlank String> sizes,
            @Min(0) Long retailPriceAmount,
            Boolean enabled) {
    }

    /**
     * 更新请求。
     *
     * <p><b>字段缺省语义不统一，是有意为之</b>：{@code retailPriceAmount}、
     * 三个维度列和两个阈值列省略即<b>保持原值</b>；而 {@code barcode} 省略即清空、
     * {@code enabled} 省略即 {@code true}（沿用 V1 的 PUT 全量覆盖语义，本期不改，
     * 否则会静默重新启用已停用的 SKU）。因此前端"只调阈值"的抽屉会先把整行读回来、
     * 再带着原值提交，避免顺手改掉别的列。
     *
     * <p>{@code minLevel}/{@code maxLevel} 单位是<b>件</b>（不是分），0 = 未设：
     * 下限为 0 时不进安全库存预警，上限为 0 时不进补货建议。
     * 顺序约束与库里 {@code ck_sku_level_order} 一致：
     * {@code max = 0} 或 {@code max >= min}，违反时 400 {@code MDM_SKU_LEVEL_INVALID}。
     */
    public record UpdateRequest(
            @Size(max = 64) String barcode,
            @Min(0) Long retailPriceAmount,
            Boolean enabled,
            @Size(max = 32) String seasonCode,
            @Size(max = 32) String bandCode,
            LocalDate launchDate,
            @Min(0) Integer minLevel,
            @Min(0) Integer maxLevel) {
    }

    @GetMapping
    @RequirePermission("mdm:read")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) String color,
            /* 尺码筛选叫 skuSize 而非 size：size 是契约统一的分页条数参数，
               同名会让其中一个被 Spring 静默忽略（分页永远 20 条）。 */
            @RequestParam(required = false) String skuSize,
            @RequestParam(required = false) String seasonCode,
            @RequestParam(required = false) String bandCode,
            @RequestParam(required = false) LocalDate launchDateFrom,
            @RequestParam(required = false) LocalDate launchDateTo,
            @RequestParam(required = false) Integer seasonYear,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponses.page(skus.page(
                new SkuFilter(productId, color, skuSize,
                        seasonCode, bandCode, launchDateFrom, launchDateTo, seasonYear),
                page, size)));
    }

    @GetMapping("/{id}")
    @RequirePermission("mdm:read")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(skus.require(id))));
    }

    @PostMapping
    @RequirePermission("mdm:write")
    public ResponseEntity<Map<String, Object>> create(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @Valid @RequestBody CreateRequest req) {
        Sku s = skus.create(new SkuApplicationService.CreateCommand(
                req.productId(), req.color(), req.size(), req.barcode(),
                req.retailPriceAmount() == null ? 0L : req.retailPriceAmount(),
                req.enabled() == null || req.enabled(),
                req.seasonCode(), req.bandCode(), req.launchDate(),
                req.minLevel(), req.maxLevel(), requestId));
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
        Sku s = skus.update(id, new SkuApplicationService.UpdateCommand(
                req.barcode(), req.retailPriceAmount(), req.enabled(),
                req.seasonCode(), req.bandCode(), req.launchDate(),
                req.minLevel(), req.maxLevel()));
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
        m.put("seasonCode", s.seasonCode());
        m.put("bandCode", s.bandCode());
        m.put("launchDate", s.launchDate() == null ? null : s.launchDate().toString());
        m.put("seasonYear", s.seasonYear());
        m.put("minLevel", s.minLevel());
        m.put("maxLevel", s.maxLevel());
        return m;
    }
}
