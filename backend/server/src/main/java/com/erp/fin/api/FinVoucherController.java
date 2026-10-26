package com.erp.fin.api;

import com.erp.fin.application.FinVoucherApplicationService;
import com.erp.fin.domain.Voucher;
import com.erp.fin.domain.VoucherLine;
import com.erp.fin.domain.VoucherSourceType;
import com.erp.fin.domain.VoucherStatus;
import com.erp.iam.security.RequirePermission;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.web.ApiResponses;
import com.erp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * 凭证管理接口（FR-V3-07，契约 tag：fin）。
 *
 * <p>借贷不平的 400 响应在 details 里带 {@code totalDebit/totalCredit/diff}
 * （由应用服务抛出，这里不重复拼装）—— 前端录入抽屉的"实时差额"用同口径。
 */
@RestController
@RequestMapping("/api/v1/fin/vouchers")
public class FinVoucherController {

    public record LineRequest(@NotNull Long accountId, String summary, BigDecimal debit,
                              BigDecimal credit) {
    }

    public record VoucherRequest(LocalDate voucherDate, String remark,
                                 @NotEmpty @Valid List<LineRequest> lines) {
    }

    private final FinVoucherApplicationService vouchers;

    public FinVoucherController(FinVoucherApplicationService vouchers) {
        this.vouchers = vouchers;
    }

    @GetMapping
    @RequirePermission("fin:read")
    public ResponseEntity<Map<String, Object>> page(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResult<Voucher> found = vouchers.page(parseEnum(VoucherStatus.class, status,
                        "DRAFT|AUDITED|POSTED"),
                parseEnum(VoucherSourceType.class, sourceType,
                        "MANUAL|PUR_IN|SAL_OUT|SAL_ORDER_OUT|PUR_PAY|SAL_RCV|EXPENSE|INVOICE"),
                period, from, to, keyword, page, size);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Voucher v : found.items()) {
            items.add(toListMap(v));
        }
        return ResponseEntity.ok(ApiResponses.page(
                new PageResult<>(items, found.total(), found.page(), found.size())));
    }

    @GetMapping("/{id}")
    @RequirePermission("fin:read")
    public ResponseEntity<Map<String, Object>> get(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(vouchers.require(id))));
    }

    @PostMapping
    @RequirePermission("fin:voucher:write")
    public ResponseEntity<Map<String, Object>> create(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @Valid @RequestBody VoucherRequest req) {
        Voucher created = vouchers.create(toCommand(req));
        return ResponseEntity.ok(ApiResponses.ok(toMap(created)));
    }

    @PutMapping("/{id}")
    @RequirePermission("fin:voucher:write")
    public ResponseEntity<Map<String, Object>> update(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @PathVariable long id,
            @Valid @RequestBody VoucherRequest req) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(vouchers.update(id, toCommand(req)))));
    }

    @PostMapping("/{id}/audit")
    @RequirePermission("fin:voucher:write")
    public ResponseEntity<Map<String, Object>> audit(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(vouchers.audit(id))));
    }

    @PostMapping("/{id}/post")
    @RequirePermission("fin:voucher:write")
    public ResponseEntity<Map<String, Object>> post(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(vouchers.post(id))));
    }

    private static FinVoucherApplicationService.VoucherCommand toCommand(VoucherRequest req) {
        List<FinVoucherApplicationService.LineCommand> lines = new ArrayList<>();
        if (req.lines() != null) {
            for (LineRequest l : req.lines()) {
                lines.add(new FinVoucherApplicationService.LineCommand(
                        l.accountId(), l.summary(), l.debit(), l.credit()));
            }
        }
        return new FinVoucherApplicationService.VoucherCommand(req.voucherDate(), req.remark(),
                lines);
    }

    private static Map<String, Object> toListMap(Voucher v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.id());
        m.put("voucherNo", v.voucherNo());
        m.put("voucherDate", v.voucherDate() == null ? null : v.voucherDate().toString());
        m.put("period", v.period());
        m.put("sourceType", v.sourceType().name());
        m.put("sourceTypeLabel", v.sourceType().label());
        m.put("sourceId", v.sourceId());
        m.put("sourceNo", v.sourceNo());
        m.put("status", v.status().name());
        m.put("statusLabel", v.status().label());
        m.put("totalDebit", v.totalDebit());
        m.put("totalCredit", v.totalCredit());
        m.put("remark", v.remark());
        // 列表摘要 = 首条分录摘要（简报列表口径"摘要"列）
        m.put("summary", v.lines().isEmpty() ? null : v.lines().get(0).summary());
        m.put("lineCount", v.lines().size());
        return m;
    }

    private static Map<String, Object> toMap(Voucher v) {
        Map<String, Object> m = toListMap(v);
        m.put("createdBy", v.createdBy());
        m.put("auditedBy", v.auditedBy());
        m.put("postedAt", v.postedAt() == null ? null : v.postedAt().toString());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (VoucherLine l : v.lines()) {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("id", l.id());
            lm.put("lineNo", l.lineNo());
            lm.put("accountId", l.accountId());
            lm.put("accountCode", l.accountCode());
            lm.put("summary", l.summary());
            lm.put("debit", l.debit());
            lm.put("credit", l.credit());
            lines.add(lm);
        }
        m.put("lines", lines);
        return m;
    }

    /** 与 SettlementController.parseStatus 同一做法：非法取值给 400 + 明确允许集。 */
    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String allowed) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "取值非法：%s（允许 %s，或省略）".formatted(raw, allowed));
        }
    }
}
