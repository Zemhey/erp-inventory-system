package com.erp.sal.application;

import com.erp.mdm.repository.CustomerRepository;
import com.erp.mdm.repository.SkuRepository;
import com.erp.sal.domain.SalesOrder;
import com.erp.sal.domain.SalesQuote;
import com.erp.sal.repository.SalesQuoteRepository;
import com.erp.shared.document.DocumentNumberGenerator;
import com.erp.shared.document.DocumentStatus;
import com.erp.shared.document.DocumentStatusMachine;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.period.PeriodGuard;
import com.erp.shared.time.ClockProvider;
import com.erp.shared.web.PageResult;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 报价单应用服务（FR-SAL-05）。
 *
 * <p><b>转化不是“复制一张单”</b>：它调用出货建单的同一条路径，
 * 因此库存预占、套装展开、自动选仓、信用校验全部复用，不会出现
 * “报价能转、但转出来的单不占库存/不受信用限制”的旁路。
 */
@Service
public class SalesQuoteApplicationService {

    private final SalesQuoteRepository quotes;
    private final CustomerRepository customers;
    private final SkuRepository skus;
    private final SalesOrderApplicationService salesOrders;
    private final DocumentNumberGenerator numberGenerator;
    private final ClockProvider clock;
    private final PeriodGuard periodGuard;

    public SalesQuoteApplicationService(SalesQuoteRepository quotes, CustomerRepository customers,
                                        SkuRepository skus, SalesOrderApplicationService salesOrders,
                                        DocumentNumberGenerator numberGenerator,
                                        ClockProvider clock, PeriodGuard periodGuard) {
        this.quotes = quotes;
        this.customers = customers;
        this.skus = skus;
        this.salesOrders = salesOrders;
        this.numberGenerator = numberGenerator;
        this.clock = clock;
        this.periodGuard = periodGuard;
    }

    public record CreateLineCommand(long skuId, int qty, long unitPriceAmount,
                                    Integer discountRate) {
    }

    public record CreateCommand(long customerId, LocalDate bizDate, LocalDate validUntil,
                                String remark, List<CreateLineCommand> lines, String requestId) {
    }

    public record ConvertCommand(Long warehouseId, String remark, String requestId) {
    }

    @Transactional
    public SalesQuote create(CreateCommand cmd) {
        Optional<SalesQuote> existing = quotes.findByRequestId(cmd.requestId());
        if (existing.isPresent()) {
            return existing.get();
        }
        if (cmd.lines() == null || cmd.lines().isEmpty()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR, "报价单至少需要一行明细");
        }
        var customer = customers.findById(cmd.customerId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SYS_NOT_FOUND,
                        "客户不存在：id=%d".formatted(cmd.customerId())));
        if (!customer.enabled()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "客户已停用，不能报价：%s".formatted(customer.code()));
        }
        LocalDate bizDate = cmd.bizDate() == null ? clock.today() : cmd.bizDate();
        if (cmd.validUntil() != null && cmd.validUntil().isBefore(bizDate)) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "报价有效期不能早于报价日期：%s < %s".formatted(cmd.validUntil(), bizDate));
        }
        periodGuard.requireOpen(bizDate);

        long totalAmount = 0;
        int totalQty = 0;
        List<long[]> computed = new ArrayList<>();
        Set<Long> seenSku = new HashSet<>();
        for (CreateLineCommand line : cmd.lines()) {
            if (line.qty() <= 0) {
                throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                        "明细数量必须为正：skuId=%d, qty=%d"
                                .formatted(line.skuId(), line.qty()));
            }
            if (line.unitPriceAmount() < 0) {
                throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                        "单价不能为负：skuId=%d".formatted(line.skuId()));
            }
            int rate = line.discountRate() == null ? SalesOrderApplicationService.NO_DISCOUNT
                    : line.discountRate();
            if (rate <= 0 || rate > SalesOrderApplicationService.NO_DISCOUNT) {
                throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                        "折扣率非法：%d，必须是 1~10000 的整数万分比".formatted(rate));
            }
            if (!seenSku.add(line.skuId())) {
                throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                        "同一 SKU 不能重复成两行：skuId=%d".formatted(line.skuId()));
            }
            var sku = skus.findById(line.skuId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.MDM_SKU_NOT_FOUND,
                            "SKU 不存在：id=%d".formatted(line.skuId())));
            if (!sku.enabled()) {
                throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                        "SKU 已停用，不能报价：id=%d".formatted(line.skuId()));
            }
            long amount = SalesOrderApplicationService.lineAmount(
                    line.qty(), line.unitPriceAmount(), rate);
            totalAmount = Math.addExact(totalAmount, amount);
            totalQty += line.qty();
            computed.add(new long[]{line.skuId(), line.qty(), line.unitPriceAmount(), rate, amount});
        }

        String quoteNo = numberGenerator.next(
                DocumentNumberGenerator.DocType.SALES_QUOTE, bizDate);
        long quoteId = quotes.insertHeader(quoteNo, cmd.customerId(), bizDate, cmd.validUntil(),
                totalAmount, totalQty, trimToNull(cmd.remark()), cmd.requestId());
        int lineNo = 1;
        for (long[] c : computed) {
            quotes.insertLine(quoteId, lineNo++, c[0], (int) c[1], c[2], (int) c[3], c[4]);
        }
        return require(quoteId);
    }

    // ─────────────────────────── 状态流转 ───────────────────────────

    @Transactional
    public SalesQuote submit(long id) {
        return move(id, DocumentStatus.SUBMITTED);
    }

    @Transactional
    public SalesQuote approve(long id) {
        return move(id, DocumentStatus.APPROVED);
    }

    @Transactional
    public SalesQuote reject(long id) {
        return move(id, DocumentStatus.REJECTED);
    }

    @Transactional
    public SalesQuote voidOrder(long id) {
        return move(id, DocumentStatus.VOIDED);
    }

    private SalesQuote move(long id, DocumentStatus target) {
        SalesQuote quote = require(id);
        periodGuard.requireOpen(quote.bizDate());
        DocumentStatusMachine.requireTransition(quote.status(), target,
                ErrorCode.SAL_QUOTE_STATUS_ILLEGAL, quote.quoteNo());
        if (!quotes.updateStatus(id, quote.status(), target, quote.version())) {
            throw new BusinessException(ErrorCode.SAL_QUOTE_STATUS_ILLEGAL,
                    "报价单 %s 状态推进冲突（并发修改），请刷新后重试：期望 %s -> %s"
                            .formatted(quote.quoteNo(), quote.status(), target));
        }
        return require(id);
    }

    // ─────────────────────────── 转化 ───────────────────────────

    /**
     * 审批通过的报价单转化为出货单。
     *
     * <p>同键重放返回首次订单；已转化后用新键再转化 → 409。
     * 转化过程中的库存不足/信用超额会整单回滚，报价单保持 APPROVED。
     */
    @Transactional
    public SalesOrder convert(long id, ConvertCommand cmd) {
        SalesQuote quote = quotes.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.SAL_QUOTE_NOT_FOUND,
                        "报价单不存在：id=%d".formatted(id)));

        if (quote.converted()) {
            if (cmd.requestId().equals(quote.convertRequestId())) {
                return salesOrders.require(quote.convertedOrderId());
            }
            throw new BusinessException(ErrorCode.SAL_QUOTE_STATUS_ILLEGAL,
                    "报价单 %s 已转化为出货单 %d（首次转化请求与本次不同），不能重复转化"
                            .formatted(quote.quoteNo(), quote.convertedOrderId()));
        }
        if (quote.status() != DocumentStatus.APPROVED) {
            throw new BusinessException(ErrorCode.SAL_QUOTE_STATUS_ILLEGAL,
                    "报价单 %s 当前状态 %s 不可转化（仅 APPROVED 可转化）"
                            .formatted(quote.quoteNo(), quote.status()));
        }
        if (quote.expired(clock.today())) {
            throw new BusinessException(ErrorCode.SAL_QUOTE_STATUS_ILLEGAL,
                    "报价单 %s 已于 %s 过期，不能转化"
                            .formatted(quote.quoteNo(), quote.validUntil()));
        }
        if (quote.lines().isEmpty()) {
            throw new BusinessException(ErrorCode.SAL_QUOTE_STATUS_ILLEGAL,
                    "报价单 %s 没有明细，不能转化".formatted(quote.quoteNo()));
        }

        DocumentStatusMachine.requireTransition(quote.status(), DocumentStatus.COMPLETED,
                ErrorCode.SAL_QUOTE_STATUS_ILLEGAL, quote.quoteNo());

        List<SalesOrderApplicationService.CreateLineCommand> lines = quote.lines().stream()
                .map(l -> new SalesOrderApplicationService.CreateLineCommand(
                        l.skuId(), l.qty(), l.unitPriceAmount(), l.discountRate()))
                .toList();
        // 订单业务日期用“转化当天”，避免把今天的转化记到已锁账的报价月份
        LocalDate orderBizDate = clock.today();
        String remark = cmd.remark() == null ? quote.remark() : cmd.remark();
        String orderRequestId = orderRequestId(quote.id(), cmd.requestId());
        SalesOrderApplicationService.CreateCommand createCmd =
                new SalesOrderApplicationService.CreateCommand(
                        quote.customerId(),
                        cmd.warehouseId() == null ? 0L : cmd.warehouseId(),
                        orderBizDate, remark, lines, orderRequestId);
        SalesOrder order = cmd.warehouseId() == null
                ? salesOrders.createAutoSplit(createCmd)
                : salesOrders.create(createCmd);

        if (!quotes.markConverted(quote.id(), quote.status(), quote.version(),
                cmd.requestId(), order.id())) {
            throw new BusinessException(ErrorCode.SAL_QUOTE_STATUS_ILLEGAL,
                    "报价单 %s 转化状态推进冲突（并发修改），请刷新后重试"
                            .formatted(quote.quoteNo()));
        }
        return salesOrders.require(order.id());
    }

    @Transactional(readOnly = true)
    public SalesQuote require(long id) {
        return quotes.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.SAL_QUOTE_NOT_FOUND,
                        "报价单不存在：id=%d".formatted(id)));
    }

    @Transactional(readOnly = true)
    public PageResult<SalesQuote> page(DocumentStatus status, Long customerId, int page, int size) {
        return quotes.page(status, customerId, page, size);
    }

    private static String orderRequestId(long quoteId, String convertRequestId) {
        String suffix = ":Q" + quoteId;
        String base = convertRequestId == null || convertRequestId.isBlank()
                ? "quote-convert" : convertRequestId;
        if (base.length() > 64 - suffix.length()) {
            base = base.substring(0, 64 - suffix.length());
        }
        return base + suffix;
    }

    private static String trimToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
