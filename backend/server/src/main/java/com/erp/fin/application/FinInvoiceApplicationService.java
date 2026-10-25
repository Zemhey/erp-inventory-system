package com.erp.fin.application;

import com.erp.fin.domain.Invoice;
import com.erp.fin.domain.InvoiceStatus;
import com.erp.fin.domain.InvoiceType;
import com.erp.fin.repository.FinInvoiceRepository;
import com.erp.fin.repository.FinSourceRepository;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.time.ClockProvider;
import com.erp.shared.web.PageResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 发票台账（FR-V3-11）。
 *
 * <p>超额口径（简报验收"超额开票被拒 409"）：同一源单据的 <b>NORMAL 发票价税合计</b>
 * 加本次不得超过单据金额；作废（VOID）票不计入占用。
 * 单据金额取业务域 total_amount（整数分）换算成元后比较，两种口径不在同一处混用。
 */
@Service
public class FinInvoiceApplicationService {

    public record InvoiceCommand(String invoiceNo, String type, Long partnerId, String partnerName,
                                 BigDecimal amount, BigDecimal taxAmount, LocalDate invoiceDate,
                                 String sourceType, Long sourceId, String remark) {
    }

    private final FinInvoiceRepository invoices;
    private final FinSourceRepository sources;
    private final ClockProvider clock;

    public FinInvoiceApplicationService(FinInvoiceRepository invoices,
                                        FinSourceRepository sources,
                                        ClockProvider clock) {
        this.invoices = invoices;
        this.sources = sources;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResult<Invoice> page(InvoiceType type, InvoiceStatus status, String keyword,
                                   LocalDate from, LocalDate to, int page, int size) {
        return invoices.page(type, status, keyword, from, to, page, size);
    }

    @Transactional(readOnly = true)
    public Invoice require(long id) {
        return invoices.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.FIN_INVOICE_NOT_FOUND,
                        "发票不存在：id=" + id));
    }

    @Transactional
    public Invoice create(InvoiceCommand cmd) {
        InvoiceType type = parseType(cmd.type());
        if (cmd.invoiceNo() == null || cmd.invoiceNo().isBlank()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR, "发票号必填");
        }
        if (cmd.partnerName() == null || cmd.partnerName().isBlank()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR, "购方/销方名称必填");
        }
        BigDecimal amount = scale(cmd.amount());
        BigDecimal tax = scale(cmd.taxAmount() == null ? BigDecimal.ZERO : cmd.taxAmount());
        BigDecimal total = amount.add(tax);
        if (total.signum() <= 0) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "发票价税合计必须为正（不含税 + 税额）");
        }
        String sourceType = normalizeSourceType(cmd.sourceType());
        Long sourceId = cmd.sourceId();
        if ((sourceType == null) != (sourceId == null)) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "关联单据需要同时提供 sourceType 与 sourceId");
        }
        requireWithinSourceLimit(sourceType, sourceId, total, null);
        LocalDate date = cmd.invoiceDate() == null ? clock.today() : cmd.invoiceDate();
        long id = invoices.insert(cmd.invoiceNo().trim(), type, cmd.partnerId(),
                cmd.partnerName().trim(), amount, tax, total, date, sourceType, sourceId,
                trim(cmd.remark()));
        return require(id);
    }

    @Transactional
    public Invoice update(long id, InvoiceCommand cmd) {
        Invoice existing = require(id);
        if (existing.status() != InvoiceStatus.NORMAL) {
            throw new BusinessException(ErrorCode.FIN_INVOICE_STATUS_ILLEGAL,
                    "发票 %s 已作废，不可修改".formatted(existing.invoiceNo()));
        }
        BigDecimal amount = scale(cmd.amount() == null ? existing.amount() : cmd.amount());
        BigDecimal tax = scale(cmd.taxAmount() == null ? existing.taxAmount() : cmd.taxAmount());
        BigDecimal total = amount.add(tax);
        // 改额同样受"同一单据不超额"约束：把旧值从占用中剔除后再比较
        if (existing.sourceType() != null && existing.sourceId() != null) {
            requireWithinSourceLimit(existing.sourceType(), existing.sourceId(), total, existing);
        }
        if (!invoices.update(id, cmd.partnerId() == null ? existing.partnerId() : cmd.partnerId(),
                cmd.partnerName() == null || cmd.partnerName().isBlank()
                        ? existing.partnerName() : cmd.partnerName().trim(),
                amount, tax, total,
                cmd.invoiceDate() == null ? existing.invoiceDate() : cmd.invoiceDate(),
                cmd.remark() == null ? existing.remark() : trim(cmd.remark()))) {
            throw new BusinessException(ErrorCode.FIN_INVOICE_STATUS_ILLEGAL,
                    "发票 %s 状态已被并发修改".formatted(existing.invoiceNo()));
        }
        return require(id);
    }

    /** 作废：释放该发票占用的可开票额度（超额校验只统计 NORMAL）。 */
    @Transactional
    public Invoice voidInvoice(long id) {
        Invoice existing = require(id);
        if (existing.status() == InvoiceStatus.VOID) {
            throw new BusinessException(ErrorCode.FIN_INVOICE_STATUS_ILLEGAL,
                    "发票 %s 已是作废态".formatted(existing.invoiceNo()));
        }
        if (!invoices.setStatus(id, InvoiceStatus.NORMAL, InvoiceStatus.VOID)) {
            throw new BusinessException(ErrorCode.FIN_INVOICE_STATUS_ILLEGAL,
                    "发票 %s 状态已被并发修改".formatted(existing.invoiceNo()));
        }
        return require(id);
    }

    @Transactional
    public void delete(long id) {
        require(id);
        invoices.deleteById(id);
    }

    // ─────────────────────────── 校验 ───────────────────────────

    private void requireWithinSourceLimit(String sourceType, Long sourceId, BigDecimal newTotal,
                                          Invoice excluding) {
        if (sourceType == null || sourceId == null) {
            return;
        }
        BigDecimal docYuan = sources.sourceDocAmount(sourceType, sourceId)
                .map(FinVoucherApplicationService::fenToYuan)
                .orElseThrow(() -> new BusinessException(ErrorCode.SYS_NOT_FOUND,
                        "关联单据不存在或类型非法：%s id=%d".formatted(sourceType, sourceId)));
        BigDecimal already = invoices.invoicedTotalBySource(sourceType, sourceId);
        if (excluding != null) {
            already = already.subtract(excluding.totalAmount());
        }
        BigDecimal after = already.add(newTotal);
        if (after.compareTo(docYuan) > 0) {
            throw new BusinessException(ErrorCode.FIN_INVOICE_OVER_AMOUNT,
                    "超过单据可开票金额：单据 %s 金额 %s 元，已开 %s 元，本次 %s 元，超出 %s 元"
                            .formatted(sourceType + ":" + sourceId, docYuan.toPlainString(),
                                    already.toPlainString(), newTotal.toPlainString(),
                                    after.subtract(docYuan).toPlainString()),
                    Map.of("docAmount", docYuan, "invoiced", already,
                            "requested", newTotal, "over", after.subtract(docYuan)));
        }
    }

    private static InvoiceType parseType(String raw) {
        try {
            return InvoiceType.parse(raw);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "type 取值非法：OUTPUT（销项）/ INPUT（进项）");
        }
    }

    private static String normalizeSourceType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim().toUpperCase(Locale.ROOT);
        if (!v.equals("PURCHASE_ORDER") && !v.equals("SALES_ORDER")) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "sourceType 仅支持 PURCHASE_ORDER / SALES_ORDER，收到：" + raw);
        }
        return v;
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
