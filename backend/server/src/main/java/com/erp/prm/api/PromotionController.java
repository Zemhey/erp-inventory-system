package com.erp.prm.api;

import com.erp.iam.security.RequirePermission;
import com.erp.prm.application.PromotionApplicationService;
import com.erp.prm.domain.Promotion;
import com.erp.prm.domain.Promotion.PromoType;
import com.erp.shared.web.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
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

/** 促销活动接口（FR-V3-21）：台账式 CRUD；规则 JSON 原样存取，不参与算价。 */
@RestController
@RequestMapping("/api/v1/prm/promotions")
public class PromotionController {

    private final PromotionApplicationService promotions;

    public PromotionController(PromotionApplicationService promotions) {
        this.promotions = promotions;
    }

    public record RuleRequest(String conditionJson, String benefitJson) {
    }

    public record PromoRequest(
            @NotBlank @Size(min = 2, max = 32) String code,
            @NotBlank @Size(max = 128) String name,
            @NotNull PromoType promoType,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            Integer priority,
            @Size(max = 500) String remark,
            List<RuleRequest> rules) {
    }

    @GetMapping
    @RequirePermission("prm:read")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Promotion p : promotions.page(status, keyword)) {
            items.add(toMap(p));
        }
        return ResponseEntity.ok(ApiResponses.ok(Map.of("items", items, "total", items.size())));
    }

    @GetMapping("/{id}")
    @RequirePermission("prm:read")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(promotions.require(id))));
    }

    @PostMapping
    @RequirePermission("prm:write")
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody PromoRequest req) {
        Promotion saved = promotions.create(toDomain(req));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponses.ok(toMap(saved)));
    }

    @PutMapping("/{id}")
    @RequirePermission("prm:write")
    public ResponseEntity<Map<String, Object>> update(@PathVariable long id,
                                                      @Valid @RequestBody PromoRequest req) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(promotions.update(id, toDomain(req)))));
    }

    @PostMapping("/{id}/activate")
    @RequirePermission("prm:write")
    public ResponseEntity<Map<String, Object>> activate(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(promotions.transition(id, true))));
    }

    @PostMapping("/{id}/stop")
    @RequirePermission("prm:write")
    public ResponseEntity<Map<String, Object>> stop(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(promotions.transition(id, false))));
    }

    private static Promotion toDomain(PromoRequest req) {
        List<Promotion.Rule> rules = req.rules() == null ? List.of()
                : req.rules().stream()
                        .map(r -> new Promotion.Rule(null, r.conditionJson(), r.benefitJson()))
                        .toList();
        return new Promotion(null, req.code(), req.name(), req.promoType(), req.startTime(),
                req.endTime(), req.priority() == null ? 0 : req.priority(),
                Promotion.STATUS_DRAFT, req.remark(), rules);
    }

    private static Map<String, Object> toMap(Promotion p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id());
        m.put("code", p.code());
        m.put("name", p.name());
        m.put("promoType", p.promoType().name());
        m.put("startTime", p.startTime() == null ? null : p.startTime().toString());
        m.put("endTime", p.endTime() == null ? null : p.endTime().toString());
        m.put("priority", p.priority());
        m.put("status", p.status());
        m.put("remark", p.remark());
        List<Map<String, Object>> rules = new ArrayList<>();
        for (Promotion.Rule r : p.rules()) {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("id", r.id());
            rm.put("conditionJson", r.conditionJson());
            rm.put("benefitJson", r.benefitJson());
            rules.add(rm);
        }
        m.put("rules", rules);
        return m;
    }
}
