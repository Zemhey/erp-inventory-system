package com.erp.pur.api;

import com.erp.iam.security.RequirePermission;
import com.erp.pur.application.SupplierPayableService;
import com.erp.pur.domain.PayableAllocation;
import com.erp.pur.domain.SupplierPayable;
import com.erp.shared.web.ApiResponses;
import com.erp.shared.web.PageResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 供应商应付查询（FR-PUR-07）。
 *
 * <p>{@code /reconcile} 是硬验收 1 的可重复校验入口：
 * 它每次实时聚合应付与核销明细，不读缓存、不写结果。
 */
@RestController
@RequestMapping("/api/v1/supplier-payables")
public class SupplierPayableController {

    private final SupplierPayableService payables;

    public SupplierPayableController(SupplierPayableService payables) {
        this.payables = payables;
    }

    @GetMapping
    @RequirePermission("pur:payable")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResult<SupplierPayable> found = payables.page(supplierId, status, page, size);
        List<Map<String, Object>> items = found.items().stream()
                .map(SupplierPayableController::toMap).toList();
        return ResponseEntity.ok(ApiResponses.page(
                new PageResult<>(items, found.total(), found.page(), found.size())));
    }

    @GetMapping("/reconcile")
    @RequirePermission("pur:payable")
    public ResponseEntity<Map<String, Object>> reconcile(
            @RequestParam(required = false) Long supplierId) {
        SupplierPayableService.ReconcileResult r = payables.reconcile(supplierId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("supplierId", r.supplierId());
        m.put("purchaseAmount", r.purchaseAmount());
        m.put("returnAmount", r.returnAmount());
        m.put("paidAmount", r.paidAmount());
        m.put("balance", r.balance());
        m.put("paymentTotal", r.paymentTotal());
        m.put("allocationTotal", r.allocationTotal());
        m.put("balanced", r.balanced());
        m.put("conserved", r.conserved());
        return ResponseEntity.ok(ApiResponses.ok(m));
    }

    @GetMapping("/{id}")
    @RequirePermission("pur:payable")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(payables.require(id))));
    }

    static Map<String, Object> toMap(SupplierPayable p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id());
        m.put("payableNo", p.payableNo());
        m.put("supplierId", p.supplierId());
        m.put("purchaseOrderId", p.purchaseOrderId());
        m.put("orderNo", p.orderNo());
        m.put("receiptId", p.receiptId());
        m.put("bizDate", p.bizDate() == null ? null : p.bizDate().toString());
        m.put("period", p.period());
        m.put("originalAmount", p.originalAmount());
        m.put("returnedAmount", p.returnedAmount());
        m.put("paidAmount", p.paidAmount());
        m.put("balance", p.balanceAmount());
        m.put("status", p.status());
        List<Map<String, Object>> allocations = new ArrayList<>();
        for (PayableAllocation a : p.allocations()) {
            Map<String, Object> am = new LinkedHashMap<>();
            am.put("id", a.id());
            am.put("paymentNo", a.paymentNo());
            am.put("amount", a.amount());
            am.put("paymentAmount", a.paymentAmount());
            am.put("bizDate", a.bizDate() == null ? null : a.bizDate().toString());
            am.put("remark", a.remark());
            allocations.add(am);
        }
        m.put("allocations", allocations);
        return m;
    }
}
