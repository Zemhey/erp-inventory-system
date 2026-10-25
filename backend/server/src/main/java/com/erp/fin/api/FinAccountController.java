package com.erp.fin.api;

import com.erp.fin.application.FinAccountApplicationService;
import com.erp.fin.domain.FinAccount;
import com.erp.iam.security.RequirePermission;
import com.erp.shared.web.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 会计科目表接口（FR-V3-06，契约 tag：fin）。 */
@RestController
@RequestMapping("/api/v1/fin/accounts")
public class FinAccountController {

    public record CreateRequest(@NotBlank String code, @NotBlank String name, Long parentId,
                                @NotBlank String category, @NotBlank String direction,
                                BigDecimal initBalance, Boolean enabled, String remark) {
    }

    public record UpdateRequest(String name, String direction, BigDecimal initBalance,
                                Boolean enabled, String remark) {
    }

    private final FinAccountApplicationService accounts;

    public FinAccountController(FinAccountApplicationService accounts) {
        this.accounts = accounts;
    }

    /** 平铺列表（下拉选择器与前端分组渲染用；读路径触发新租户懒补种）。 */
    @GetMapping
    @RequirePermission("fin:read")
    public ResponseEntity<Map<String, Object>> list() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (FinAccount a : accounts.list()) {
            items.add(toMap(a));
        }
        return ResponseEntity.ok(ApiResponses.ok(Map.of("items", items)));
    }

    /** 科目树（简报验收"树能展开"：一级科目为根，children 递归）。 */
    @GetMapping("/tree")
    @RequirePermission("fin:read")
    public ResponseEntity<Map<String, Object>> tree() {
        List<Map<String, Object>> roots = new ArrayList<>();
        for (FinAccountApplicationService.AccountNode node : accounts.tree()) {
            roots.add(toNodeMap(node));
        }
        return ResponseEntity.ok(ApiResponses.ok(Map.of("items", roots)));
    }

    @PostMapping
    @RequirePermission("fin:account:write")
    public ResponseEntity<Map<String, Object>> create(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @Valid @RequestBody CreateRequest req) {
        FinAccount created = accounts.create(new FinAccountApplicationService.CreateCommand(
                req.code(), req.name(), req.parentId(), req.category(), req.direction(),
                req.initBalance(), req.enabled(), req.remark()));
        return ResponseEntity.ok(ApiResponses.ok(toMap(created)));
    }

    @PutMapping("/{id}")
    @RequirePermission("fin:account:write")
    public ResponseEntity<Map<String, Object>> update(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @PathVariable long id,
            @Valid @RequestBody UpdateRequest req) {
        FinAccount updated = accounts.update(id,
                new FinAccountApplicationService.UpdateCommand(req.name(), req.direction(),
                        req.initBalance(), req.enabled(), req.remark()));
        return ResponseEntity.ok(ApiResponses.ok(toMap(updated)));
    }

    /** 删除：被凭证引用/有子科目返回 409 FIN_ACCOUNT_IN_USE（简报验收口径）。 */
    @DeleteMapping("/{id}")
    @RequirePermission("fin:account:write")
    public ResponseEntity<Map<String, Object>> delete(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @PathVariable long id) {
        accounts.delete(id);
        return ResponseEntity.ok(ApiResponses.ok(Map.of("deleted", id)));
    }

    private static Map<String, Object> toMap(FinAccount a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.id());
        m.put("code", a.code());
        m.put("name", a.name());
        m.put("parentId", a.parentId());
        m.put("category", a.category().name());
        m.put("categoryLabel", a.category().label());
        m.put("direction", a.direction().name());
        m.put("directionLabel", a.direction().label());
        m.put("leaf", a.leaf());
        m.put("initBalance", a.initBalance());
        m.put("enabled", a.enabled());
        m.put("remark", a.remark());
        return m;
    }

    private static Map<String, Object> toNodeMap(FinAccountApplicationService.AccountNode node) {
        Map<String, Object> m = toMap(node.account());
        List<Map<String, Object>> children = new ArrayList<>();
        for (FinAccountApplicationService.AccountNode child : node.children()) {
            children.add(toNodeMap(child));
        }
        m.put("children", children);
        return m;
    }
}
