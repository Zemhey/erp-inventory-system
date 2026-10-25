package com.erp.prm.api;

import com.erp.iam.security.RequirePermission;
import com.erp.prm.application.PricePlanApplicationService;
import com.erp.prm.domain.PricePlan;
import com.erp.prm.domain.PricePlan.PlanType;
import com.erp.shared.web.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
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
 * 价格方案接口（FR-V3-20）。
 *
 * <p>金额口径：<b>分</b>（priceAmount），折扣：<b>万分比</b>（discountBp，10000=不打折），
 * 与 sal 报价/出货完全一致；禁止 double。取价优先级见
 * {@link PricePlanApplicationService} 类注释。
 */
@RestController
@RequestMapping("/api/v1/prm")
public class PricePlanController {

    private final PricePlanApplicationService pricePlans;

    public PricePlanController(PricePlanApplicationService pricePlans) {
        this.pricePlans = pricePlans;
    }

    public record ItemRequest(
            @NotNull Long skuId,
            @NotNull @Min(0) Long priceAmount,
            @Min(0) @NotNull Integer discountBp,
            @NotNull @Min(0) Integer minQty) {
    }

    public record PlanRequest(
            @NotBlank @Size(min = 2, max = 32) String code,
            @NotBlank @Size(max = 128) String name,
            @NotNull PlanType planType,
            Long customerId,
            @Size(max = 16) String customerLevel,
            Integer priority,
            LocalDate startDate,
            LocalDate endDate,
            Boolean enabled,
            @Size(max = 500) String remark,
            @NotEmpty @Valid List<ItemRequest> items) {
    }

    @GetMapping("/price-plans")
    @RequirePermission("prm:read")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String keyword) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (PricePlan p : pricePlans.page(keyword)) {
            items.add(toMap(p));
        }
        return ResponseEntity.ok(ApiResponses.ok(Map.of("items", items, "total", items.size())));
    }

    @GetMapping("/price-plans/{id}")
    @RequirePermission("prm:read")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(pricePlans.require(id))));
    }

    @PostMapping("/price-plans")
    @RequirePermission("prm:write")
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody PlanRequest req) {
        PricePlan saved = pricePlans.create(toDomain(req, null));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponses.ok(toMap(saved)));
    }

    @PutMapping("/price-plans/{id}")
    @RequirePermission("prm:write")
    public ResponseEntity<Map<String, Object>> update(@PathVariable long id,
                                                      @Valid @RequestBody PlanRequest req) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(pricePlans.update(id, toDomain(req, id)))));
    }

    @PostMapping("/price-plans/{id}/disable")
    @RequirePermission("prm:write")
    public ResponseEntity<Map<String, Object>> disable(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(pricePlans.disable(id))));
    }

    /**
     * 取价试算：{@code GET /api/v1/prm/price?skuId=&customerId=&qty=}。
     * 纯查询、幂等；返回 source 标明命中的是哪一级价格。
     */
    @GetMapping("/price")
    @RequirePermission("prm:read")
    public ResponseEntity<Map<String, Object>> price(
            @RequestParam long skuId,
            @RequestParam(required = false) Long customerId,
            @RequestParam(defaultValue = "1") long qty,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day) {
        var r = pricePlans.resolvePrice(skuId, customerId, qty, day);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", r.source());
        m.put("planCode", r.planCode());
        m.put("planId", r.planId());
        m.put("priceAmount", r.priceAmount());
        m.put("discountBp", r.discountBp());
        m.put("qty", r.qty());
        return ResponseEntity.ok(ApiResponses.ok(m));
    }

    private static PricePlan toDomain(PlanRequest req, Long id) {
        List<PricePlan.Item> items = req.items().stream()
                .map(i -> new PricePlan.Item(null, i.skuId(), i.priceAmount(),
                        i.discountBp() == null ? 10000 : i.discountBp(), i.minQty()))
                .toList();
        return new PricePlan(id, req.code(), req.name(), req.planType(), req.customerId(),
                req.customerLevel(), req.priority() == null ? 0 : req.priority(),
                req.startDate(), req.endDate(), req.enabled() == null || req.enabled(),
                req.remark(), items);
    }

    private static Map<String, Object> toMap(PricePlan p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id());
        m.put("code", p.code());
        m.put("name", p.name());
        m.put("planType", p.planType().name());
        m.put("customerId", p.customerId());
        m.put("customerLevel", p.customerLevel());
        m.put("priority", p.priority());
        m.put("startDate", p.startDate() == null ? null : p.startDate().toString());
        m.put("endDate", p.endDate() == null ? null : p.endDate().toString());
        m.put("enabled", p.enabled());
        m.put("remark", p.remark());
        List<Map<String, Object>> items = new ArrayList<>();
        for (PricePlan.Item i : p.items()) {
            Map<String, Object> im = new LinkedHashMap<>();
            im.put("id", i.id());
            im.put("skuId", i.skuId());
            im.put("priceAmount", i.priceAmount());
            im.put("discountBp", i.discountBp());
            im.put("minQty", i.minQty());
            im.put("effectivePrice", i.effectivePrice());
            items.add(im);
        }
        m.put("items", items);
        return m;
    }
}
