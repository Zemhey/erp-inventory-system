package com.erp.rma.application;

import com.erp.inv.application.InventoryApplicationService;
import com.erp.rma.domain.ReturnOrder;
import com.erp.rma.domain.ReturnOrderLine;
import com.erp.rma.repository.ReturnOrderRepository;
import com.erp.sal.application.ReturnableSalesOrderService;
import com.erp.sal.application.ReturnableSalesOrderService.ReturnableLine;
import com.erp.sal.application.ReturnableSalesOrderService.ReturnableOrder;
import com.erp.sal.application.SalesOrderApplicationService;
import com.erp.sal.application.SalesOrderReturnService;
import com.erp.shared.db.DbErrors;
import com.erp.shared.document.DocumentNumberGenerator;
import com.erp.shared.document.DocumentStatus;
import com.erp.shared.document.DocumentStatusMachine;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.time.ClockProvider;
import com.erp.shared.web.PageResult;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 退货应用服务（FR-RMA-01）。
 *
 * <p><b>两条不能弯的线（延续 M1 的架构约束）</b>：
 * <pre>
 * ReturnOrderApplicationService ──▶ ReturnableSalesOrderService（SAL 只读：可退明细）
 *                                ──▶ SalesOrderReturnService（SAL 写：累计退货量）
 *                                ──▶ InventoryApplicationService.receiveIn(RETURN_IN)（唯一库存入口）
 * </pre>
 * 本类不注入任何 SAL 仓储、也不碰 {@code InventoryBalanceRepository}，
 * 由 ArchUnit 在 CI 强制。
 *
 * <p><b>金额不接受前端传入</b>：单价与折扣一律从原出货行派生，
 * 金额复用 {@link SalesOrderApplicationService#lineAmount} 同一公式 ——
 * 复制一份"看起来一样"的舍入代码，是日清对不上账的第一大来源。
 */
@Service
public class ReturnOrderApplicationService {

    private final ReturnOrderRepository orders;
    private final ReturnableSalesOrderService returnableOrders;
    private final SalesOrderReturnService salesReturns;
    private final InventoryApplicationService inventory;
    private final DocumentNumberGenerator numberGenerator;
    private final ClockProvider clock;

    public ReturnOrderApplicationService(ReturnOrderRepository orders,
                                        ReturnableSalesOrderService returnableOrders,
                                        SalesOrderReturnService salesReturns,
                                        InventoryApplicationService inventory,
                                        DocumentNumberGenerator numberGenerator,
                                        ClockProvider clock) {
        this.orders = orders;
        this.returnableOrders = returnableOrders;
        this.salesReturns = salesReturns;
        this.inventory = inventory;
        this.numberGenerator = numberGenerator;
        this.clock = clock;
    }

    // ─────────────────────────── 命令 ───────────────────────────

    /**
     * 建单的一行明细。
     *
     * <p>刻意<b>没有</b>单价、折扣、客户、仓库、SKU 这些字段：
     * 它们全部由原出货行与原出货单派生，接受外部传入等于允许客户端改写金额。
     */
    public record CreateLineCommand(long originLineId, int qty, String batchNo,
                                    LocalDate productionDate, LocalDate expiryDate) {
    }

    public record CreateCommand(long originSalesOrderId, LocalDate bizDate, String remark,
                                List<CreateLineCommand> lines, String requestId) {
    }

    /** 校验通过、准备落库的一行（带派生出来的价格与金额）。 */
    private record PreparedLine(ReturnableLine source, int qty, long amount, String batchNo,
                                LocalDate productionDate, LocalDate expiryDate) {
    }

    // ─────────────────────────── 建单 ───────────────────────────

    /**
     * 创建退货单（草稿，<b>不改变库存</b>）。
     *
     * <p>数量校验在这一层只是<b>预检</b>：真正的上限守卫在入库时的条件 UPDATE 里
     * （见 {@link SalesOrderReturnService#registerReturn}）。原因是同一张原出货行
     * 可能被多张退货单同时引用，"先查后判"存在读后写窗口。
     *
     * <p><b>幂等</b>：相同 {@code requestId} 重复提交直接返回首次建成的单据，
     * 由部分唯一索引 {@code uq_ro_request} 在并发下兜底。
     */
    @Transactional
    public ReturnOrder create(CreateCommand cmd) {
        Optional<ReturnOrder> existing = orders.findByRequestId(cmd.requestId());
        if (existing.isPresent()) {
            return orders.findById(existing.get().id()).orElseThrow();
        }
        if (cmd.lines() == null || cmd.lines().isEmpty()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR, "退货单至少需要一行明细");
        }

        ReturnableOrder origin = returnableOrders.requireShippedOrder(cmd.originSalesOrderId());
        Map<Long, ReturnableLine> byOriginLine = new HashMap<>();
        for (ReturnableLine line : origin.lines()) {
            byOriginLine.put(line.originLineId(), line);
        }

        LocalDate bizDate = cmd.bizDate() == null ? clock.today() : cmd.bizDate();
        long totalAmount = 0;
        int totalQty = 0;
        List<PreparedLine> prepared = new ArrayList<>();
        Set<Long> seenOriginLines = new HashSet<>();

        for (CreateLineCommand line : cmd.lines()) {
            if (!seenOriginLines.add(line.originLineId())) {
                throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                        "同一原出货行在一张退货单里只能出现一次：originLineId=%d"
                                .formatted(line.originLineId()));
            }
            if (line.qty() <= 0) {
                throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                        "退货数量必须为正：originLineId=%d, qty=%d"
                                .formatted(line.originLineId(), line.qty()));
            }
            ReturnableLine source = byOriginLine.get(line.originLineId());
            if (source == null) {
                throw new BusinessException(ErrorCode.SYS_NOT_FOUND,
                        "出货单 %s 中没有可退的明细行 originLineId=%d（该行不属于本单，或未出货或已退完）"
                                .formatted(origin.orderNo(), line.originLineId()),
                        Map.of("originSalesOrderId", origin.orderId(),
                                "originLineId", line.originLineId()));
            }
            if (line.qty() > source.returnableQty()) {
                throw new BusinessException(ErrorCode.RMA_QTY_EXCEED_ORIGIN,
                        "超出可退数量：出货单 %s 第 %d 行（SKU %d）已出货 %d，已退货 %d，本次 %d，最多还能退 %d"
                                .formatted(origin.orderNo(), source.lineNo(), source.skuId(),
                                        source.shippedQty(), source.returnedQty(), line.qty(),
                                        source.returnableQty()),
                        Map.of("originLineId", source.originLineId(), "skuId", source.skuId(),
                                "shippedQty", source.shippedQty(), "returnedQty", source.returnedQty(),
                                "requested", line.qty(), "returnable", source.returnableQty()));
            }

            long amount = SalesOrderApplicationService.lineAmount(
                    line.qty(), source.unitPriceAmount(), source.discountRate());
            totalAmount = Math.addExact(totalAmount, amount);
            totalQty += line.qty();
            prepared.add(new PreparedLine(source, line.qty(), amount, line.batchNo(),
                    line.productionDate(), line.expiryDate()));
        }

        String orderNo = numberGenerator.next(DocumentNumberGenerator.DocType.RETURN_ORDER, bizDate);
        long orderId = orders.insertHeader(orderNo, origin.orderId(), origin.customerId(),
                origin.warehouseId(), bizDate, totalAmount, totalQty, periodOf(bizDate),
                trimToNull(cmd.remark()), cmd.requestId());
        int lineNo = 1;
        for (PreparedLine p : prepared) {
            orders.insertLine(orderId, lineNo++, p.source().originLineId(), p.source().skuId(),
                    p.qty(), p.source().unitPriceAmount(), p.source().discountRate(), p.amount(),
                    p.batchNo(), p.productionDate(), p.expiryDate());
        }
        return require(orderId);
    }

    // ─────────────────────────── 状态流转 ───────────────────────────

    @Transactional
    public ReturnOrder submit(long id) {
        ReturnOrder o = require(id);
        DocumentStatusMachine.requireTransition(o.status(), DocumentStatus.SUBMITTED,
                ErrorCode.RMA_STATUS_ILLEGAL, o.orderNo());
        transition(o, DocumentStatus.SUBMITTED);
        return require(id);
    }

    @Transactional
    public ReturnOrder approve(long id) {
        ReturnOrder o = require(id);
        DocumentStatusMachine.requireTransition(o.status(), DocumentStatus.APPROVED,
                ErrorCode.RMA_STATUS_ILLEGAL, o.orderNo());
        transition(o, DocumentStatus.APPROVED);
        return require(id);
    }

    @Transactional
    public ReturnOrder reject(long id) {
        ReturnOrder o = require(id);
        DocumentStatusMachine.requireTransition(o.status(), DocumentStatus.REJECTED,
                ErrorCode.RMA_STATUS_ILLEGAL, o.orderNo());
        transition(o, DocumentStatus.REJECTED);
        return require(id);
    }

    /**
     * 作废退货单：<b>仅入库前</b>可以作废，且作废不产生任何库存变动。
     *
     * <p>已入库的退货单要"作废"必须先生成反向流水冲减库存（红冲，FR-SAL-04 同族能力），
     * 本期明确不做（M2 计划书 §7 决策 3）。这里给出明确拒绝而不是静默成功，
     * 否则用户以为退了货、库存却回不来。
     */
    @Transactional
    public ReturnOrder voidOrder(long id) {
        ReturnOrder o = require(id);
        if (o.status() == DocumentStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.RMA_STATUS_ILLEGAL,
                    "退货单 %s 已入库完成，作废需先红冲并再次出库（M2.5 提供），本操作已拒绝"
                            .formatted(o.orderNo()));
        }
        DocumentStatusMachine.requireTransition(o.status(), DocumentStatus.VOIDED,
                ErrorCode.RMA_STATUS_ILLEGAL, o.orderNo());
        transition(o, DocumentStatus.VOIDED);
        return require(id);
    }

    // ─────────────────────────── 退货入库（★ 唯一库存入口） ───────────────────────────

    /**
     * 退货入库：整单一次性回补库存，无请求体参数。
     *
     * <p><b>顺序</b>（每行两步，任一步失败整个事务回滚）：
     * <ol>
     *   <li>{@link SalesOrderReturnService#registerReturn} 原子累加原出货行的累计退货量，
     *       超过已出货数量直接拒绝；</li>
     *   <li>{@link InventoryApplicationService#receiveIn} 增加实存并写 {@code RETURN_IN} 流水。</li>
     * </ol>
     * 也就是说：<b>不会出现"库存回了但出货行没记下"或反之</b>的半成品状态。
     *
     * <p><b>幂等</b>：入库键单独一列（{@code receive_request_id}），与建单键互不干扰。
     * 同一键重复调用直接返回已完成的单据；已完成后再用新键调用返回
     * {@code RMA_STATUS_ILLEGAL}，避免"以为又退了一次"。
     */
    @Transactional
    public ReturnOrder receive(long id, String requestId) {
        ReturnOrder o = require(id);

        if (o.status() == DocumentStatus.COMPLETED) {
            if (requestId.equals(o.receiveRequestId())) {
                return o;
            }
            throw new BusinessException(ErrorCode.RMA_STATUS_ILLEGAL,
                    "退货单 %s 已入库完成，不能重复入库（首次入库请求：%s，本次：%s）"
                            .formatted(o.orderNo(), o.receiveRequestId(), requestId));
        }
        if (!o.status().isExecutable()) {
            throw new BusinessException(ErrorCode.RMA_STATUS_ILLEGAL,
                    "退货单 %s 当前状态 %s 不可入库（仅 APPROVED 可入库）"
                            .formatted(o.orderNo(), o.status()));
        }

        for (ReturnOrderLine line : o.lines()) {
            salesReturns.registerReturn(line.originLineId(), line.qty());
            inventory.receiveIn(line.skuId(), o.warehouseId(), line.batchNo(),
                    line.productionDate(), line.expiryDate(), line.qty(),
                    "RETURN_IN", o.orderNo(), o.id(), null,
                    ledgerRequestId(requestId, line.lineNo()));
        }

        DocumentStatusMachine.requireTransition(o.status(), DocumentStatus.COMPLETED,
                ErrorCode.RMA_STATUS_ILLEGAL, o.orderNo());
        try {
            if (!orders.completeReceive(id, o.status(), o.version(), requestId)) {
                throw new BusinessException(ErrorCode.RMA_STATUS_ILLEGAL,
                        "退货单 %s 入库推进失败（并发修改），请刷新后重试"
                                .formatted(o.orderNo()));
            }
        } catch (DataIntegrityViolationException ex) {
            // 并发下同一个 receive_request_id 被两个请求同时写入：唯一索引拦住后来者，
            // 本事务的库存增量随之回滚。翻译成业务码，而不是让客户端看到 500。
            BusinessException duplicate = DbErrors.translate(ex, "uq_ro_receive_request",
                    ErrorCode.SYS_DUPLICATE_REQUEST,
                    "重复的入库请求：该幂等键已经入库过一次");
            if (duplicate != null) {
                throw duplicate;
            }
            throw ex;
        }
        return require(id);
    }

    // ─────────────────────────── 查询 ───────────────────────────

    @Transactional(readOnly = true)
    public ReturnOrder require(long id) {
        return orders.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RMA_ORDER_NOT_FOUND,
                        "退货单不存在：id=%d".formatted(id)));
    }

    @Transactional(readOnly = true)
    public PageResult<ReturnOrder> page(DocumentStatus status, Long customerId,
                                        Long originSalesOrderId, int page, int size) {
        return orders.page(status, customerId, originSalesOrderId, page, size);
    }

    // ─────────────────────────── 内部辅助 ───────────────────────────

    private void transition(ReturnOrder o, DocumentStatus target) {
        if (!orders.updateStatus(o.id(), o.status(), target, o.version())) {
            throw new BusinessException(ErrorCode.RMA_STATUS_ILLEGAL,
                    "退货单 %s 状态推进冲突（并发修改），请刷新后重试：期望 %s -> %s"
                            .formatted(o.orderNo(), o.status(), target));
        }
    }

    /**
     * 流水的幂等键必须<b>按行</b>唯一。
     *
     * <p>{@code uq_ledger_request} 的口径是 (request_id, biz_type, batch_no)：
     * 一张退货单多行若共用同一个 requestId 且批次都为空，第二条流水会被判成重复提交。
     * 与进货入库、出货预占是同一个坑，这里同样在源头规避。
     */
    private static String ledgerRequestId(String baseRequestId, int lineNo) {
        String base = baseRequestId == null || baseRequestId.isBlank() ? "rma-receive" : baseRequestId;
        return base + ":L" + lineNo;
    }

    private static String periodOf(LocalDate bizDate) {
        return "%04d-%02d".formatted(bizDate.getYear(), bizDate.getMonthValue());
    }

    private static String trimToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
