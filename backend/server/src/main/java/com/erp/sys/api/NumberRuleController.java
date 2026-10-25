package com.erp.sys.api;

import com.erp.iam.security.RequirePermission;
import com.erp.shared.web.ApiResponses;
import com.erp.sys.application.NumberRuleService;
import com.erp.sys.domain.NumberRule;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 单号规则接口（契约：/number-rules，FR-V3-25）。 */
@RestController
@RequestMapping("/api/v1/number-rules")
public class NumberRuleController {

    private final NumberRuleService service;

    public NumberRuleController(NumberRuleService service) {
        this.service = service;
    }

    @GetMapping
    @RequirePermission("sys:numberrule")
    public ResponseEntity<Map<String, Object>> list() {
        List<Map<String, Object>> items = service.list().stream()
                .map(NumberRuleController::toMap).toList();
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("items", items);
        return ResponseEntity.ok(ApiResponses.ok(data));
    }

    public record UpdateRule(String prefix, Integer seqLength, Boolean enabled) {
    }

    @PutMapping("/{id}")
    @RequirePermission("sys:numberrule")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable long id, @RequestBody UpdateRule body) {
        NumberRule r = service.update(id,
                body.prefix(),
                body.seqLength() == null ? 4 : body.seqLength(),
                body.enabled() != null && body.enabled());
        return ResponseEntity.ok(ApiResponses.ok(toMap(r)));
    }

    public record PreviewRequest(String docType) {
    }

    /** 预览下一张单号（不消费流水）。 */
    @PostMapping("/preview")
    @RequirePermission("sys:numberrule")
    public ResponseEntity<Map<String, Object>> preview(@RequestBody PreviewRequest body) {
        return ResponseEntity.ok(ApiResponses.ok(service.preview(body.docType())));
    }

    private static Map<String, Object> toMap(NumberRule r) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", r.id());
        m.put("docType", r.docType());
        m.put("prefix", r.prefix());
        m.put("datePattern", r.datePattern());
        m.put("seqLength", r.seqLength());
        m.put("resetCycle", r.resetCycle());
        m.put("enabled", r.enabled());
        m.put("createdAt", r.createdAt());
        m.put("updatedAt", r.updatedAt());
        return m;
    }
}
