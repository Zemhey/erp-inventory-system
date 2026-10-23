package com.erp.pur.application;

import com.erp.mdm.repository.SkuRepository;
import com.erp.mdm.repository.SupplierRepository;
import com.erp.mdm.repository.WarehouseRepository;
import com.erp.pur.domain.PurchaseOrder;
import com.erp.pur.domain.PurchaseOrderLine;
import com.erp.pur.domain.PurchaseRequisition;
import com.erp.pur.repository.PurchaseOrderRepository;
import com.erp.pur.repository.PurchaseRequisitionRepository;
import com.erp.shared.db.DbErrors;
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
 * 请购单应用服务（FR-PUR-04）。
 *
 * <p>请购单复用进货单存储（{@code doc_type='REQUISITION'}），但业务上是独立单据：
 * 不能入库、不能红冲；唯一出口是"转订单"。
 *
 * <p><b>转单三条硬约束</b>：
 * <ol>
 *   <li><b>行不丢</b>：请求必须覆盖请购单全部明细行；</li>
 *   <li><b>数量不超</b>：每行转化数量 ∈ [1, 申请数量]；</li>
 *   <li><b>转化后不可再改</b>：状态置 COMPLETED，且 {@code converted_order_id} 唯一。</li>
 * </ol>
 */
@Service
public class PurchaseRequisitionApplicationService {

    private final PurchaseRequisitionRepository requisitions;
    private final PurchaseOrderRepository orders;
    private final SupplierRepository suppliers;
    private final WarehouseRepository warehouses;
    private final SkuRepository skus;
    private final DocumentNumberGenerator numberGenerator;
    private final ClockProvider clock;
    private final PeriodGuard periodGuard;

    public PurchaseRequisitionApplicationService(PurchaseRequisitionRepository requisitions,
                                                 PurchaseOrderRepository orders,
                                                 SupplierRepository suppliers,
                                                 WarehouseRepository warehouses,
                                                 SkuRepository skus,
                                                 DocumentNumberGenerator numberGenerator,
                                                 ClockProvider clock,
                                                 PeriodGuard periodGuard) {
        this.requisitions = requisitions;
        this.orders = orders;
        this.suppliers = suppliers;
        this.warehouses = warehouses;
        this.skus = skus;
        this.numberGenerator = numberGenerator;
        this.clock = clock;
        this.periodGuard = periodGuard;
    }

    // ─────────────────────────── 命令 ───────────────────────────

    public record CreateLineCommand(long skuId, int qty, long unitPriceAmount) {
    }

    public record CreateCommand(long supplierId, long warehouseId, LocalDate bizDate,
                                String remark, List<CreateLineCommand> lines, String requestId) {
    }

    public record ConvertLineCommand(long lineId, int qty) {
    }

    public record ConvertCommand(LocalDate bizDate, String remark,
                                 List<ConvertLineCommand> lines, String requestId) {
    }

    // ─────────────────────────── 建单 ───────────────────────────

    @Transactional
    public PurchaseRequisition create(CreateCommand cmd) {
        Optional<PurchaseRequisition> existing = requisitions.findByRequestId(cmd.requestId());
        if (existing.isPresent()) {
            return existing.get();
        }
        requireSupplierEnabled(cmd.supplierId());
        requireWarehouseEnabled(cmd.warehouseId());
        if (cmd.lines() == null || cmd.lines().isEmpty()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR, "请购单至少需要一行明细");
        }
        LocalDate bizDate = cmd.bizDate() == null ? clock.today() : cmd.bizDate();
        periodGuard.requireOpen(bizDate);

        long totalAmount = 0;
        int totalQty = 0;
        List<long[]> computed = new ArrayList<>();
        for (CreateLineCommand line : cmd.lines()) {
            if (line.qty() <= 0) {
                throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                        "请购数量必须为正：skuId=%d, qty=%d"
                                .formatted(line.skuId(), line.qty()));
            }
            if (line.unitPriceAmount() < 0) {
                throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                        "请购单价不能为负：skuId=%d, unitPrice=%d"
                                .formatted(line.skuId(), line.unitPriceAmount()));
            }
            requireSkuEnabled(line.skuId());
            long amount = Math.multiplyExact((long) line.qty(), line.unitPriceAmount());
            totalAmount = Math.addExact(totalAmount, amount);
            totalQty += line.qty();
            computed.add(new long[]{line.skuId(), line.qty(), line.unitPriceAmount(), amount});
        }

        String orderNo = numberGenerator.next(
                DocumentNumberGenerator.DocType.PURCHASE_REQUISITION, bizDate);
        long id = requisitions.insertHeader(orderNo, cmd.supplierId(), cmd.warehouseId(), bizDate,
                totalAmount, totalQty, periodOf(bizDate), trimToNull(cmd.remark()), cmd.requestId());
        int lineNo = 1;
        for (long[] c : computed) {
            requisitions.insertLine(id, lineNo++, c[0], (int) c[1], c[2], c[3]);
        }
        return require(id);
    }

    // ─────────────────────────── 状态 ───────────────────────────

    @Transactional
    public PurchaseRequisition submit(long id) {
        PurchaseRequisition r = require(id);
        periodGuard.requireOpen(r.bizDate());
        DocumentStatusMachine.requireTransition(r.status(), DocumentStatus.SUBMITTED,
                ErrorCode.PUR_REQ_STATUS_ILLEGAL, r.orderNo());
        transition(r, DocumentStatus.SUBMITTED);
        return require(id);
    }

    @Transactional
    public PurchaseRequisition approve(long id) {
        PurchaseRequisition r = require(id);
        periodGuard.requireOpen(r.bizDate());
        DocumentStatusMachine.requireTransition(r.status(), DocumentStatus.APPROVED,
                ErrorCode.PUR_REQ_STATUS_ILLEGAL, r.orderNo());
        transition(r, DocumentStatus.APPROVED);
        return require(id);
    }

    private void transition(PurchaseRequisition r, DocumentStatus target) {
        if (!requisitions.updateStatus(r.id(), r.status(), target, r.version())) {
            throw new BusinessException(ErrorCode.PUR_REQ_STATUS_ILLEGAL,
                    "请购单 %s 状态推进冲突（并发修改），请刷新后重试：期望 %s -> %s"
                            .formatted(r.orderNo(), r.status(), target));
        }
    }

    // ─────────────────────────── 转订单 ───────────────────────────

    /**
     * 请购单转采购订单。
     *
     * <p>幂等：同一 {@code X-Request-Id} 重放返回首次生成的订单；
     * 已转化后用新键再次转化 → {@code PUR_REQ_STATUS_ILLEGAL}。
     */
    @Transactional
    public PurchaseOrder convert(long id, ConvertCommand cmd) {
        PurchaseRequisition r = require(id);
        if (cmd.requestId() == null || cmd.requestId().isBlank()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "请购转订单必须提供幂等键 X-Request-Id");
        }
        Optional<PurchaseRequisition> replay =
                requisitions.findByConversionRequestId(cmd.requestId());
        if (replay.isPresent()) {
            if (replay.get().id() != r.id()) {
                throw new BusinessException(ErrorCode.SYS_DUPLICATE_REQUEST,
                        "重复的转化请求：该幂等键已用于另一张请购单");
            }
            return orders.findById(replay.get().convertedOrderId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.PUR_ORDER_NOT_FOUND,
                            "请购单 %s 已转化，但关联订单 %d 不存在"
                                    .formatted(r.orderNo(), replay.get().convertedOrderId())));
        }
        if (r.converted()) {
            throw new BusinessException(ErrorCode.PUR_REQ_STATUS_ILLEGAL,
                    "请购单 %s 已转化，不能再次转单".formatted(r.orderNo()));
        }
        DocumentStatusMachine.requireTransition(r.status(), DocumentStatus.COMPLETED,
                ErrorCode.PUR_REQ_STATUS_ILLEGAL, r.orderNo());
        if (cmd.lines() == null || cmd.lines().isEmpty()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR, "转单明细不能为空");
        }

        Map<Long, PurchaseOrderLine> byId = new HashMap<>();
        for (PurchaseOrderLine line : r.lines()) {
            byId.put(line.id(), line);
        }
        if (cmd.lines().size() != byId.size()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "转单必须覆盖请购单全部明细行：请购 %d 行，请求 %d 行（行不丢）"
                            .formatted(byId.size(), cmd.lines().size()));
        }
        Set<Long> seen = new HashSet<>();
        long totalAmount = 0;
        int totalQty = 0;
        List<long[]> computed = new ArrayList<>();
        for (ConvertLineCommand c : cmd.lines()) {
            PurchaseOrderLine source = byId.get(c.lineId());
            if (source == null) {
                throw new BusinessException(ErrorCode.SYS_NOT_FOUND,
                        "请购单 %s 中不存在明细行 lineId=%d"
                                .formatted(r.orderNo(), c.lineId()));
            }
            if (!seen.add(c.lineId())) {
                throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                        "同一请购行在一次转单中只能出现一次：lineId=%d".formatted(c.lineId()));
            }
            if (c.qty() <= 0) {
                throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                        "转单数量必须为正：lineId=%d, qty=%d".formatted(c.lineId(), c.qty()));
            }
            if (c.qty() > source.qty()) {
                throw new BusinessException(ErrorCode.PUR_REQ_CONVERT_EXCEED,
                        "转单数量超过请购数量：lineId=%d 请购 %d，转单 %d"
                                .formatted(c.lineId(), source.qty(), c.qty()),
                        Map.of("lineId", c.lineId(), "requested", source.qty(),
                                "converted", c.qty()));
            }
            long amount = Math.multiplyExact((long) c.qty(), source.unitPriceAmount());
            totalAmount = Math.addExact(totalAmount, amount);
            totalQty += c.qty();
            computed.add(new long[]{source.skuId(), c.qty(),
                    source.unitPriceAmount(), amount});
        }

        LocalDate bizDate = cmd.bizDate() == null ? clock.today() : cmd.bizDate();
        periodGuard.requireOpen(bizDate);
        String orderNo = numberGenerator.next(
                DocumentNumberGenerator.DocType.PURCHASE_ORDER, bizDate);
        long orderId;
        try {
            orderId = orders.insertHeader(orderNo, r.supplierId(), r.warehouseId(), bizDate,
                    totalAmount, totalQty, periodOf(bizDate), trimToNull(cmd.remark()),
                    cmd.requestId(), "PURCHASE", r.id());
        } catch (DataIntegrityViolationException ex) {
            BusinessException translated = DbErrors.translate(ex, "uq_po_source_requisition",
                    ErrorCode.PUR_REQ_STATUS_ILLEGAL,
                    "请购单 %s 已被另一笔请求转化为订单".formatted(r.orderNo()));
            if (translated != null) {
                throw translated;
            }
            throw ex;
        }
        int lineNo = 1;
        for (long[] c : computed) {
            orders.insertLine(orderId, lineNo++, c[0], (int) c[1], c[2], c[3]);
        }
        if (!requisitions.markConverted(r.id(), r.version(), orderId, cmd.requestId())) {
            throw new BusinessException(ErrorCode.PUR_REQ_STATUS_ILLEGAL,
                    "请购单 %s 状态推进冲突（并发修改），请刷新后重试"
                            .formatted(r.orderNo()));
        }
        return orders.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PUR_ORDER_NOT_FOUND,
                        "转化生成的订单不存在：id=%d".formatted(orderId)));
    }

    // ─────────────────────────── 查询 ───────────────────────────

    @Transactional(readOnly = true)
    public PurchaseRequisition require(long id) {
        return requisitions.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PUR_REQ_NOT_FOUND,
                        "请购单不存在：id=%d".formatted(id)));
    }

    @Transactional(readOnly = true)
    public PageResult<PurchaseRequisition> page(DocumentStatus status, Long supplierId,
                                               int page, int size) {
        return requisitions.page(status, supplierId, page, size);
    }

    // ─────────────────────────── 内部校验 ───────────────────────────

    private void requireSupplierEnabled(long supplierId) {
        var s = suppliers.findById(supplierId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SYS_NOT_FOUND,
                        "供应商不存在：id=%d".formatted(supplierId)));
        if (!s.enabled()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "供应商已停用，不能建请购单：%s".formatted(s.code()));
        }
    }

    private void requireWarehouseEnabled(long warehouseId) {
        var w = warehouses.findById(warehouseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MDM_WAREHOUSE_NOT_FOUND,
                        "仓库不存在：id=%d".formatted(warehouseId)));
        if (!w.enabled()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "仓库已停用，不能建请购单：%s".formatted(w.code()));
        }
    }

    private void requireSkuEnabled(long skuId) {
        var s = skus.findById(skuId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MDM_SKU_NOT_FOUND,
                        "SKU 不存在：id=%d".formatted(skuId)));
        if (!s.enabled()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "SKU 已停用，不能建请购单：id=%d".formatted(skuId));
        }
    }

    private static String periodOf(LocalDate bizDate) {
        return "%04d-%02d".formatted(bizDate.getYear(), bizDate.getMonthValue());
    }

    private static String trimToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
