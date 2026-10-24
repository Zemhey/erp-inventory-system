package com.erp.sys.api;

import com.erp.iam.security.RequirePermission;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.web.ApiResponses;
import com.erp.shared.web.PageResult;
import com.erp.sys.application.AuditQueryService;
import com.erp.sys.domain.AuditAction;
import com.erp.sys.domain.AuditLog;
import com.erp.sys.domain.AuditQueryFilter;
import com.erp.sys.domain.AuditResult;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计查询接口（FR-SYS-07，契约 {@code /audits}）。
 *
 * <p><b>全部是 GET</b>：审计只读，"不可改"这件事在 HTTP 方法上就看得见 ——
 * 没有任何 POST/PUT/DELETE 能被误接到审计表上。
 *
 * <p>本层只做参数绑定与 DTO 转换，查询条件组装在
 * {@link AuditQueryService} / {@code AuditLogQueryRepository}。
 */
@RestController
@RequestMapping("/api/v1/audits")
public class AuditController {

    private final AuditQueryService audits;

    public AuditController(AuditQueryService audits) {
        this.audits = audits;
    }

    /**
     * 审计列表（FR-SYS-07：按人 / 单据 / 时间 / 操作类型）。
     *
     * <p>六个过滤条件全部可选，可任意组合。时间区间用半开区间
     * {@code [from, to)}：这样才能"按天查"而不漏掉当天最后一毫秒。
     */
    @GetMapping
    @RequirePermission("sys:read")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) String actorName,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) String bizRefNo,
            @RequestParam(required = false) String result,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        if (from != null && to != null && !from.isBefore(to)) {
            throw new BusinessException(ErrorCode.SYS_AUDIT_QUERY_INVALID,
                    "时间区间非法：from=%s 必须早于 to=%s".formatted(from, to));
        }
        AuditQueryFilter filter = new AuditQueryFilter(actorId, actorName, parseAction(action),
                resourceType, resourceId, bizRefNo, parseResult(result), from, to);
        PageResult<AuditLog> found = audits.page(filter, page, size);
        List<Map<String, Object>> items = found.items().stream()
                .map(AuditController::toMap).toList();
        return ResponseEntity.ok(ApiResponses.page(new PageResult<>(items, found.total(),
                found.page(), found.size())));
    }

    @GetMapping("/{id}")
    @RequirePermission("sys:read")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(audits.require(id))));
    }

    /**
     * 某张业务单据的审计轨迹（正序）。
     *
     * <p>这是"按单据追溯"最常用的入口：拿一个单号就能回答
     * "这张单谁建的、谁审的、谁改的"。
     */
    @GetMapping("/timeline")
    @RequirePermission("sys:read")
    public ResponseEntity<Map<String, Object>> timeline(
            @RequestParam String bizRefNo) {
        List<Map<String, Object>> items = audits.timeline(bizRefNo).stream()
                .map(AuditController::toMap).toList();
        return ResponseEntity.ok(ApiResponses.ok(items));
    }

    /** 按 traceId 取该请求产生的全部审计（FR-SYS-08 贯穿性的落库侧证据）。 */
    @GetMapping("/by-trace")
    @RequirePermission("sys:read")
    public ResponseEntity<Map<String, Object>> byTrace(@RequestParam String traceId) {
        List<Map<String, Object>> items = audits.byTrace(traceId).stream()
                .map(AuditController::toMap).toList();
        return ResponseEntity.ok(ApiResponses.ok(items));
    }

    private static AuditAction parseAction(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return AuditAction.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            // 非法枚举值属客户端问题：400 而不是 500（★ 契约 §3 的归因规则）
            throw new BusinessException(ErrorCode.SYS_AUDIT_QUERY_INVALID,
                    "非法 action 取值：%s（可选值见 GET /dicts/ACTIONS）".formatted(raw));
        }
    }

    private static AuditResult parseResult(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return AuditResult.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.SYS_AUDIT_QUERY_INVALID,
                    "非法 result 取值：%s（仅 SUCCESS / FAIL）".formatted(raw));
        }
    }

    private static Map<String, Object> toMap(AuditLog log) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", log.id());
        m.put("actorId", log.actorId());
        m.put("actorName", log.actorName());
        m.put("action", log.action().name());
        m.put("resourceType", log.resourceType());
        m.put("resourceId", log.resourceId());
        m.put("bizRefNo", log.bizRefNo());
        m.put("beforeJson", log.beforeJson());
        m.put("afterJson", log.afterJson());
        m.put("traceId", log.traceId());
        m.put("ip", log.ip());
        m.put("occurredAt", log.occurredAt() == null ? null : log.occurredAt().toString());
        m.put("result", log.result().name());
        m.put("errorCode", log.errorCode());
        return m;
    }
}
