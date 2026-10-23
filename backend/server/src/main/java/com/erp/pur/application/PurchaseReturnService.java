package com.erp.pur.application;

import com.erp.inv.application.InventoryApplicationService;
import com.erp.pur.domain.PurchaseOrder;
import com.erp.pur.domain.PurchaseReturn;
import com.erp.pur.domain.PurchaseReturnLine;
import com.erp.pur.domain.PurchaseReturnStatus;
import com.erp.pur.domain.PurchaseReceipt;
import com.erp.pur.domain.PurchaseReceiptLine;
import com.erp.pur.repository.PurchaseOrderRepository;
import com.erp.pur.repository.PurchaseReceiptRepository;
import com.erp.pur.repository.PurchaseReturnRepository;
import com.erp.shared.document.DocumentNumberGenerator;
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
 * 采购退货服务（FR-PUR-06）。
 *
 * <p>类名不以 {@code ApplicationService} 结尾：退货状态只有 DRAFT/COMPLETED/VOIDED
 * 三态，不走七态 {@code DocumentStatus} 状态机；为通过 ArchUnit 而伪造一次
 * {@code requireTransition} 调用会掩盖真实规则，因此用独立状态枚举 + 显式校验。
 *
 * <p><b>退货不是"反向进货"</b>：它必须精确落到原到货行（批次 + 单价 + 应付），
 * 因此签名只收 {@code receiptLineId + qty}，不接受任何金额。
 */
@Service
public class PurchaseReturnService {

    private final PurchaseReturnRepository returns;
    private final PurchaseReceiptRepository receipts;
    private final PurchaseOrderRepository orders;
    private final SupplierPayableService payables;
    private final InventoryApplicationService inventory;
    private final DocumentNumberGenerator numberGenerator;
    private final ClockProvider clock;
    private final PeriodGuard periodGuard;

    public PurchaseReturnService(PurchaseReturnRepository returns,
                                 PurchaseReceiptRepository receipts,
                                 PurchaseOrderRepository orders,
                                 SupplierPayableService payables,
                                 InventoryApplicationService inventory,
                                 DocumentNumberGenerator numberGenerator,
                                 ClockProvider clock,
                                 PeriodGuard periodGuard) {
        this.returns = returns;
        this.receipts = receipts;
        this.orders = orders;
        this.payables = payables;
        this.inventory = inventory;
        this.numberGenerator = numberGenerator;
        this.clock = clock;
        this.periodGuard = periodGuard;
    }

    // ─────────────────────────── 命令 ───────────────────────────

    public record CreateLineCommand(long receiptLineId, int qty) {
    }

    public record CreateCommand(long purchaseOrderId, LocalDate bizDate, String remark,
                                List<CreateLineCommand> lines, String requestId) {
    }

    /** 预检通过、准备落库的一行。 */
    private record PreparedLine(PurchaseReceiptLine receiptLine, int qty, long amount) {
    }

    // ─────────────────────────── 建单 ───────────────────────────

    @Transactional
    public PurchaseReturn create(CreateCommand cmd) {
        if (cmd.requestId() == null || cmd.requestId().isBlank()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "采购退货建单必须提供幂等键 X-Request-Id");
        }
        Optional<PurchaseReturn> existing = returns.findByRequestId(cmd.requestId());
        if (existing.isPresent()) {
            return existing.get();
        }
        PurchaseOrder order = orders.findById(cmd.purchaseOrderId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PUR_ORDER_NOT_FOUND,
                        "进货单不存在：id=%d".formatted(cmd.purchaseOrderId())));
        if (cmd.lines() == null || cmd.lines().isEmpty()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR, "退货单至少需要一行明细");
        }
        LocalDate bizDate = cmd.bizDate() == null ? clock.today() : cmd.bizDate();
        periodGuard.requireOpen(bizDate);

        Set<Long> seen = new HashSet<>();
        int totalQty = 0;
        long totalAmount = 0;
        List<PreparedLine> prepared = new ArrayList<>();
        for (CreateLineCommand c : cmd.lines()) {
            if (!seen.add(c.receiptLineId())) {
                throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                        "同一到货行在一张退货单里只能出现一次：receiptLineId=%d"
                                .formatted(c.receiptLineId()));
            }
            if (c.qty() <= 0) {
                throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                        "退货数量必须为正：receiptLineId=%d, qty=%d"
                                .formatted(c.receiptLineId(), c.qty()));
            }
            PurchaseReceiptLine line = receipts.findLineById(c.receiptLineId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.PUR_RETURN_NOT_FOUND,
                            "到货行不存在：receiptLineId=%d".formatted(c.receiptLineId())));
            PurchaseReceipt receipt = receipts.findById(line.receiptId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.PUR_RECEIPT_NOT_FOUND,
                            "到货单不存在：id=%d".formatted(line.receiptId())));
            if (receipt.purchaseOrderId() != order.id()) {
                throw new BusinessException(ErrorCode.SYS_NOT_FOUND,
                        "到货行 %d 不属于进货单 %s"
                                .formatted(c.receiptLineId(), order.orderNo()));
            }
            int alreadyReturned = returns.returnedQtyOfReceiptLine(line.id());
            if (alreadyReturned + c.qty() > line.qcQty()) {
                throw new BusinessException(ErrorCode.PUR_RETURN_QTY_EXCEED,
                        "退货数量超过可退量：到货行 %d 合格 %d，已退 %d，本次 %d"
                                .formatted(line.id(), line.qcQty(), alreadyReturned, c.qty()),
                        java.util.Map.of("receiptLineId", line.id(), "qcQty", line.qcQty(),
                                "returned", alreadyReturned, "requested", c.qty()));
            }
            long amount = Math.multiplyExact((long) c.qty(), line.unitPriceAmount());
            totalQty += c.qty();
            totalAmount = Math.addExact(totalAmount, amount);
            prepared.add(new PreparedLine(line, c.qty(), amount));
        }

        List<PurchaseReturnLine> lines = prepared.stream()
                .map(p -> new PurchaseReturnLine(p.receiptLine().id(),
                        p.receiptLine().purchaseOrderLineId(), p.receiptLine().skuId(),
                        p.qty(), p.receiptLine().unitPriceAmount(), p.amount(),
                        p.receiptLine().batchNo()))
                .toList();
        String returnNo = numberGenerator.next(
                DocumentNumberGenerator.DocType.PURCHASE_RETURN, bizDate);
        long id = returns.insert(returnNo, order.id(), order.supplierId(), order.warehouseId(),
                bizDate, periodOf(bizDate), totalQty, totalAmount, trimToNull(cmd.remark()),
                lines, cmd.requestId());
        return require(id);
    }

    // ─────────────────────────── 执行 / 作废 ───────────────────────────

    /**
     * 执行退货：扣库存 + 冲应付。
     *
     * <p>每行先锁原进货明细行，再聚合已退量 —— 两个并发退货请求在同一行上串行化，
     * 后者会看到前者已写入的退货量，从而被 {@code PUR_RETURN_QTY_EXCEED} 拒绝。
     *
     * <p>同 {@code X-Request-Id} 重放返回首次结果；已完成后换新键 → 状态非法。
     */
    @Transactional
    public PurchaseReturn approve(long id, String requestId) {
        if (!returns.lockById(id)) {
            throw new BusinessException(ErrorCode.PUR_RETURN_NOT_FOUND,
                    "采购退货单不存在：id=%d".formatted(id));
        }
        PurchaseReturn r = require(id);
        if (requestId == null || requestId.isBlank()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "执行退货必须提供幂等键 X-Request-Id");
        }
        Optional<PurchaseReturn> replay = returns.findByApproveRequestId(requestId);
        if (replay.isPresent()) {
            if (replay.get().id() != r.id()) {
                throw new BusinessException(ErrorCode.SYS_DUPLICATE_REQUEST,
                        "重复的退货执行请求：该幂等键已用于另一张退货单");
            }
            return replay.get();
        }
        if (!r.status().canExecute()) {
            throw new BusinessException(ErrorCode.PUR_RETURN_STATUS_ILLEGAL,
                    "退货单 %s 当前状态 %s 不可执行（仅 DRAFT 可执行）"
                            .formatted(r.returnNo(), r.status()));
        }
        periodGuard.requireOpen(r.bizDate());

        int lineNo = 1;
        for (PurchaseReturnLine line : r.lines()) {
            if (!orders.lockLine(line.purchaseOrderLineId())) {
                throw new BusinessException(ErrorCode.PUR_RETURN_NOT_FOUND,
                        "原进货明细行不存在：id=%d".formatted(line.purchaseOrderLineId()));
            }
            PurchaseReceiptLine receiptLine = receipts.findLineById(line.receiptLineId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.PUR_RETURN_NOT_FOUND,
                            "到货行不存在：id=%d".formatted(line.receiptLineId())));
            int alreadyReturned = returns.returnedQtyOfReceiptLine(line.receiptLineId());
            if (alreadyReturned + line.qty() > receiptLine.qcQty()) {
                throw new BusinessException(ErrorCode.PUR_RETURN_QTY_EXCEED,
                        "退货数量超过可退量（并发冲突）：到货行 %d 合格 %d，已退 %d，本次 %d"
                                .formatted(line.receiptLineId(), receiptLine.qcQty(),
                                        alreadyReturned, line.qty()));
            }
            // ① 唯一库存入口：按原批次扣减并写 PURCHASE_RETURN 负流水
            inventory.purchaseReturnOut(line.skuId(), r.warehouseId(), line.batchNo(),
                    line.qty(), r.returnNo(), r.id(), line.unitPriceAmount(),
                    returnRequestId(requestId, lineNo));
            // ② 冲减该到货单对应应付；余额不足（已付款）时整单回滚
            payables.registerReturn(receiptLine.receiptId(), line.amount());
            lineNo++;
        }

        if (!returns.markCompleted(r.id(), r.version(), requestId)) {
            PurchaseReturn latest = require(r.id());
            if (latest.status() == PurchaseReturnStatus.COMPLETED
                    && returns.isCompletedByRequest(latest.id(), requestId)) {
                return latest;
            }
            throw new BusinessException(ErrorCode.PUR_RETURN_STATUS_ILLEGAL,
                    "退货单 %s 状态推进冲突（并发修改），请刷新后重试"
                            .formatted(latest.returnNo()));
        }
        return require(r.id());
    }

    @Transactional
    public PurchaseReturn voidReturn(long id, String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "作废退货单必须提供幂等键 X-Request-Id");
        }
        if (!returns.lockById(id)) {
            throw new BusinessException(ErrorCode.PUR_RETURN_NOT_FOUND,
                    "采购退货单不存在：id=%d".formatted(id));
        }
        PurchaseReturn r = require(id);
        if (r.status() == PurchaseReturnStatus.VOIDED) {
            if (returns.isVoidedByRequest(r.id(), requestId)) {
                return r;
            }
            throw new BusinessException(ErrorCode.PUR_RETURN_STATUS_ILLEGAL,
                    "退货单 %s 已作废，不能再次作废".formatted(r.returnNo()));
        }
        if (!r.status().canVoid()) {
            throw new BusinessException(ErrorCode.PUR_RETURN_STATUS_ILLEGAL,
                    "退货单 %s 当前状态 %s 不可作废（仅 DRAFT 可作废）"
                            .formatted(r.returnNo(), r.status()));
        }
        if (!returns.markVoided(r.id(), r.version(), requestId)) {
            throw new BusinessException(ErrorCode.PUR_RETURN_STATUS_ILLEGAL,
                    "退货单 %s 状态推进冲突（并发修改），请刷新后重试".formatted(r.returnNo()));
        }
        return require(id);
    }

    // ─────────────────────────── 查询 ───────────────────────────

    @Transactional(readOnly = true)
    public PurchaseReturn require(long id) {
        return returns.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PUR_RETURN_NOT_FOUND,
                        "采购退货单不存在：id=%d".formatted(id)));
    }

    @Transactional(readOnly = true)
    public PageResult<PurchaseReturn> page(PurchaseReturnStatus status, Long supplierId,
                                           Long purchaseOrderId, int page, int size) {
        return returns.page(status, supplierId, purchaseOrderId, page, size);
    }

    private static String returnRequestId(String base, int lineNo) {
        String suffix = ":L" + lineNo;
        String b = base == null || base.isBlank() ? "return" : base;
        int maxBase = 64 - suffix.length();
        if (b.length() > maxBase) {
            b = b.substring(0, maxBase);
        }
        return b + suffix;
    }

    private static String periodOf(LocalDate bizDate) {
        return "%04d-%02d".formatted(bizDate.getYear(), bizDate.getMonthValue());
    }

    private static String trimToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
