package com.erp.rpt.api;

import com.erp.iam.security.RequirePermission;
import com.erp.rpt.application.DocumentService;
import com.erp.rpt.domain.DocDetail;
import com.erp.shared.web.ApiResponses;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 单据详情接口（契约：/documents/{type}/{id}，FR-V3-04）。 */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final DocumentService service;

    public DocumentController(DocumentService service) {
        this.service = service;
    }

    @GetMapping("/{type}/{id}")
    @RequirePermission("dashboard:read")
    public ResponseEntity<Map<String, Object>> detail(
            @PathVariable String type, @PathVariable long id) {
        DocDetail d = service.detail(type, id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", d.type());
        m.put("id", d.id());
        m.put("no", d.no());
        m.put("status", d.status());
        m.put("bizDate", d.bizDate().toString());
        m.put("partyName", d.partyName());
        m.put("warehouseName", d.warehouseName());
        m.put("totalQty", d.totalQty());
        m.put("totalAmount", d.totalAmount());
        List<Map<String, Object>> lines = d.lines().stream().map(l -> {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("lineNo", l.lineNo());
            lm.put("skuId", l.skuId());
            lm.put("skuName", l.skuName());
            lm.put("qty", l.qty());
            lm.put("unitPriceAmount", l.unitPriceAmount());
            lm.put("amount", l.amount());
            return lm;
        }).toList();
        m.put("lines", lines);
        return ResponseEntity.ok(ApiResponses.ok(m));
    }
}
