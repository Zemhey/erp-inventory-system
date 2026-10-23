package com.erp.sal.api;

import com.erp.iam.security.RequirePermission;
import com.erp.sal.application.CreditService;
import com.erp.shared.web.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 客户信用与账期接口（FR-SAL-09）。 */
@RestController
@RequestMapping("/api/v1/customers/{id}/credit")
public class CreditController {

    private final CreditService credit;

    public CreditController(CreditService credit) {
        this.credit = credit;
    }

    public record UpdateRequest(@NotNull @Min(0) Long creditLimit,
                                @NotNull @Min(0) Integer creditDays) {
    }

    @GetMapping
    @RequirePermission("mdm:read")
    public ResponseEntity<Map<String, Object>> view(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(credit.view(id))));
    }

    @PutMapping
    @RequirePermission("mdm:write")
    public ResponseEntity<Map<String, Object>> update(@PathVariable long id,
                                                      @Valid @RequestBody UpdateRequest req) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(
                credit.updateCredit(id, req.creditLimit(), req.creditDays()))));
    }

    static Map<String, Object> toMap(CreditService.CreditView v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("customerId", v.customerId());
        m.put("creditLimit", v.creditLimit());
        m.put("creditDays", v.creditDays());
        m.put("exposureAmount", v.exposureAmount());
        // -1 = 不设限（creditLimit = 0）
        m.put("availableCredit", v.availableCredit());
        m.put("overdueAmount", v.overdueAmount());
        m.put("snapshotDate", v.snapshotDate() == null ? null : v.snapshotDate().toString());
        return m;
    }
}
