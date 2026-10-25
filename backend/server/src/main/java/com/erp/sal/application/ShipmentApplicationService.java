package com.erp.sal.application;

import com.erp.inv.application.InventoryApplicationService;
import com.erp.inv.application.InventoryApplicationService.ActiveReservation;
import com.erp.mdm.domain.Sku;
import com.erp.mdm.repository.SkuRepository;
import com.erp.sal.domain.SalesOrder;
import com.erp.sal.domain.SalesOrderLine;
import com.erp.sal.domain.Shipment;
import com.erp.sal.domain.ShipmentLine;
import com.erp.sal.domain.ShipmentPlan;
import com.erp.sal.domain.ShipmentStatus;
import com.erp.sal.repository.SalesOrderRepository;
import com.erp.sal.repository.ShipmentRepository;
import com.erp.shared.document.DocumentNumberGenerator;
import com.erp.shared.document.DocumentStatus;
import com.erp.shared.document.DocumentStatusMachine;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.period.PeriodGuard;
import com.erp.shared.time.ClockProvider;
import com.erp.shared.web.PageResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 拣货/发货应用服务（FR-SAL-06，含 FR-SAL-11 的按仓拆单）。
 *
 * <p><b>三层数量守恒</b>：
 * <ol>
 *   <li>发货计划的拣货行 = 订单行的 ACTIVE 预占分段，Σ 计划量 == 未出量；</li>
 *   <li>扫码累计不得超过计划量（原子条件 UPDATE）；</li>
 *   <li>确认发货才真正扣库存：按仓调 {@code InventoryApplicationService.shipOut}，
 *       并消费对应预占。</li>
 * </ol>
 */
@Service
public class ShipmentApplicationService {

    private final ShipmentRepository shipments;
    private final SalesOrderRepository salesOrderRows;
    private final SalesOrderApplicationService salesOrders;
    private final InventoryApplicationService inventory;
    private final ReceivableService receivables;
    private final SkuRepository skus;
    private final DocumentNumberGenerator numberGenerator;
    private final ClockProvider clock;
    private final PeriodGuard periodGuard;
    /**
     * 凭证触发出口（V3-2 FR-V3-08，依赖倒置见 {@code shared.finance.VoucherTrigger}）。
     * 确认发货后：借 1122 应收 / 贷 6001 收入；成本引擎有结转额时追加 6401/1405。
     */
    private final com.erp.shared.finance.VoucherTrigger vouchers;

    public ShipmentApplicationService(ShipmentRepository shipments,
                                      SalesOrderRepository salesOrderRows,
                                      SalesOrderApplicationService salesOrders,
                                      InventoryApplicationService inventory,
                                      ReceivableService receivables,
                                      SkuRepository skus,
                                      DocumentNumberGenerator numberGenerator,
                                      ClockProvider clock,
                                      PeriodGuard periodGuard,
                                      com.erp.shared.finance.VoucherTrigger vouchers) {
        this.shipments = shipments;
        this.salesOrderRows = salesOrderRows;
        this.salesOrders = salesOrders;
        this.inventory = inventory;
        this.receivables = receivables;
        this.skus = skus;
        this.numberGenerator = numberGenerator;
        this.clock = clock;
        this.periodGuard = periodGuard;
        this.vouchers = vouchers;
    }

    /** 计划中的一个拣货行（尚未落库）。 */
    private record PlannedLine(SalesOrderLine orderLine, long warehouseId,
                               ActiveReservation reservation) {
    }

    // ─────────────────────────── 计划 ───────────────────────────

    /**
     * 为订单生成发货计划：按预占所在仓拆成多张发货单。
     *
     * <p>幂等：同一 {@code requestId} 重放返回首次计划；不允许同一批预占被两个计划占用。
     */
    @Transactional
    public ShipmentPlan createPlan(long salesOrderId, String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "生成发货计划必须提供幂等键 X-Request-Id");
        }
        Optional<ShipmentPlan> existing = shipments.findPlanByRequestId(requestId);
        if (existing.isPresent()) {
            if (existing.get().salesOrderId() != salesOrderId) {
                throw new BusinessException(ErrorCode.SYS_DUPLICATE_REQUEST,
                        "该幂等键已用于另一张订单的发货计划");
            }
            return existing.get();
        }

        SalesOrder order = salesOrders.require(salesOrderId);
        if (!order.status().isExecutable()) {
            throw new BusinessException(ErrorCode.SAL_STATUS_ILLEGAL,
                    "出货单 %s 当前状态 %s 不可生成发货计划（仅 APPROVED/PARTIAL）"
                            .formatted(order.orderNo(), order.status()));
        }
        periodGuard.requireOpen(order.bizDate());

        List<PlannedLine> planned = new ArrayList<>();
        for (SalesOrderLine line : order.lines()) {
            int openQty = line.openQty();
            if (openQty <= 0) {
                continue;
            }
            List<ActiveReservation> active = inventory.findActiveReservations(line.reservationIds());
            int activeQty = active.stream().mapToInt(ActiveReservation::reservedQty).sum();
            if (activeQty != openQty) {
                throw new BusinessException(ErrorCode.SAL_STATUS_ILLEGAL,
                        "出货单 %s 第 %d 行预占与未出量不一致：未出 %d，ACTIVE 预占 %d"
                                .formatted(order.orderNo(), line.lineNo(), openQty, activeQty));
            }
            for (ActiveReservation reservation : active) {
                if (shipments.isReservationPlanned(reservation.id())) {
                    throw new BusinessException(ErrorCode.SAL_STATUS_ILLEGAL,
                            "出货单 %s 第 %d 行预占 %d 已被其他发货计划占用，不能重复计划"
                                    .formatted(order.orderNo(), line.lineNo(), reservation.id()));
                }
                planned.add(new PlannedLine(line, reservation.warehouseId(), reservation));
            }
        }
        if (planned.isEmpty()) {
            throw new BusinessException(ErrorCode.SAL_STATUS_ILLEGAL,
                    "出货单 %s 没有可发货的未出量".formatted(order.orderNo()));
        }

        String planNo = numberGenerator.next(
                DocumentNumberGenerator.DocType.SHIPMENT, clock.today());
        Optional<Long> planId = shipments.tryInsertPlan(planNo, order.id(), requestId);
        if (planId.isEmpty()) {
            ShipmentPlan first = shipments.findPlanByRequestId(requestId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.SYS_DUPLICATE_REQUEST,
                            "重复的发货计划请求：requestId=%s".formatted(requestId)));
            if (first.salesOrderId() != salesOrderId) {
                throw new BusinessException(ErrorCode.SYS_DUPLICATE_REQUEST,
                        "该幂等键已用于另一张订单的发货计划");
            }
            return first;
        }

        Map<Long, List<PlannedLine>> byWarehouse = new LinkedHashMap<>();
        for (PlannedLine line : planned) {
            byWarehouse.computeIfAbsent(line.warehouseId(), k -> new ArrayList<>()).add(line);
        }
        for (Map.Entry<Long, List<PlannedLine>> entry : byWarehouse.entrySet()) {
            String shipmentNo = numberGenerator.next(
                    DocumentNumberGenerator.DocType.SHIPMENT, clock.today());
            long shipmentId = shipments.insertShipment(planId.get(), shipmentNo,
                    order.id(), entry.getKey());
            int lineNo = 1;
            for (PlannedLine line : entry.getValue()) {
                shipments.insertLine(shipmentId, lineNo++, line.orderLine().id(),
                        line.orderLine().skuId(), line.reservation().id(),
                        line.reservation().batchNo(), line.reservation().reservedQty());
            }
        }
        return shipments.findPlanById(planId.get()).orElseThrow();
    }

    // ─────────────────────────── 扫码 ───────────────────────────

    /**
     * 扫码确认数量：累计 `picked_qty`，不得超计划。
     *
     * <p>条码可选；提供时必须与拣货行 SKU 的条码一致（防止扫错货）。
     */
    @Transactional
    public Shipment scan(long shipmentId, long shipmentLineId, int qty, String barcode) {
        if (qty <= 0) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "扫码数量必须为正：%d".formatted(qty));
        }
        Shipment shipment = shipments.findByIdForUpdate(shipmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SAL_SHIPMENT_NOT_FOUND,
                        "发货单不存在：id=%d".formatted(shipmentId)));
        if (shipment.status() != ShipmentStatus.DRAFT
                && shipment.status() != ShipmentStatus.PICKING) {
            throw new BusinessException(ErrorCode.SAL_SHIPMENT_STATUS_ILLEGAL,
                    "发货单 %s 当前状态 %s 不可扫码".formatted(
                            shipment.shipmentNo(), shipment.status()));
        }
        ShipmentLine line = shipment.lines().stream()
                .filter(l -> l.id() == shipmentLineId)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.SYS_NOT_FOUND,
                        "拣货行不存在或不属于该发货单：id=%d".formatted(shipmentLineId)));
        if (barcode != null && !barcode.isBlank()) {
            Sku sku = skus.findById(line.skuId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.MDM_SKU_NOT_FOUND,
                            "SKU 不存在：id=%d".formatted(line.skuId())));
            if (sku.barcode() == null || !sku.barcode().equals(barcode.trim())) {
                throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                        "条码不匹配：扫描 '%s'，该行 SKU 条码为 '%s'"
                                .formatted(barcode, sku.barcode()));
            }
        }
        if (!shipments.addPicked(line.id(), qty)) {
            throw new BusinessException(ErrorCode.SAL_SHIPMENT_OVER_PICK,
                    "扫码数量超过计划：拣货行 %d 计划 %d，已扫 %d，本次 %d"
                            .formatted(line.id(), line.plannedQty(), line.pickedQty(), qty),
                    Map.of("shipmentLineId", line.id(), "plannedQty", line.plannedQty(),
                            "pickedQty", line.pickedQty(), "scanned", qty));
        }
        Shipment refreshed = require(shipmentId);
        ShipmentStatus target = refreshed.fullyPicked()
                ? ShipmentStatus.PICKED
                : (refreshed.status() == ShipmentStatus.DRAFT
                        ? ShipmentStatus.PICKING : refreshed.status());
        if (target != refreshed.status()
                && !shipments.updateStatus(refreshed.id(), refreshed.status(), target,
                        refreshed.version())) {
            throw new BusinessException(ErrorCode.SAL_SHIPMENT_STATUS_ILLEGAL,
                    "发货单 %s 状态推进冲突（并发扫码），请刷新后重试"
                            .formatted(refreshed.shipmentNo()));
        }
        return require(shipmentId);
    }

    // ─────────────────────────── 确认发货 ───────────────────────────

    /**
     * 确认发货：整张发货单一次出库。
     *
     * <p>要求全部拣货行扫满（状态 PICKED）；按行调 INV 出库并消费预占，
     * 累计订单行已出数量，推进订单状态，并登记应收。
     */
    @Transactional
    public Shipment confirm(long shipmentId, String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "确认发货必须提供幂等键 X-Request-Id");
        }
        Shipment shipment = shipments.findByIdForUpdate(shipmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SAL_SHIPMENT_NOT_FOUND,
                        "发货单不存在：id=%d".formatted(shipmentId)));
        if (shipment.status() == ShipmentStatus.COMPLETED) {
            if (requestId.equals(shipment.confirmRequestId())) {
                return shipment;
            }
            throw new BusinessException(ErrorCode.SAL_SHIPMENT_STATUS_ILLEGAL,
                    "发货单 %s 已确认发货（首次请求与本次不同），不能重复确认"
                            .formatted(shipment.shipmentNo()));
        }
        if (shipment.status() != ShipmentStatus.PICKED) {
            throw new BusinessException(ErrorCode.SAL_SHIPMENT_STATUS_ILLEGAL,
                    "发货单 %s 当前状态 %s 不可确认（需全部扫码完成）"
                            .formatted(shipment.shipmentNo(), shipment.status()));
        }

        SalesOrder order = salesOrders.require(shipment.salesOrderId());
        periodGuard.requireOpen(order.bizDate());
        Map<Long, SalesOrderLine> orderLines = new LinkedHashMap<>();
        for (SalesOrderLine line : order.lines()) {
            orderLines.put(line.id(), line);
        }

        long shippedAmount = 0;
        for (ShipmentLine line : shipment.lines()) {
            SalesOrderLine orderLine = orderLines.get(line.salesOrderLineId());
            if (orderLine == null) {
                throw new BusinessException(ErrorCode.SAL_STATUS_ILLEGAL,
                        "拣货行 %d 指向的订单行不存在".formatted(line.id()));
            }
            if (!salesOrderRows.applyShippedQty(orderLine.id(), line.plannedQty())) {
                throw new BusinessException(ErrorCode.SAL_STATUS_ILLEGAL,
                        "出货数量超出单据数量（并发冲突）：出货单 %s 第 %d 行"
                                .formatted(order.orderNo(), orderLine.lineNo()));
            }
            inventory.shipOut(line.skuId(), shipment.warehouseId(), line.plannedQty(),
                    "SALES_OUT", order.orderNo(), order.id(),
                    ledgerRequestId(shipment.shipmentNo(), line.lineNo()));
            if (!inventory.consumeReservation(line.reservationId())) {
                throw new BusinessException(ErrorCode.INV_RESERVE_FAILED,
                        "预占 %d 不是 ACTIVE，无法消费（并发冲突）"
                                .formatted(line.reservationId()));
            }
            shippedAmount = Math.addExact(shippedAmount,
                    SalesOrderApplicationService.lineAmount(line.plannedQty(),
                            orderLine.unitPriceAmount(), orderLine.discountRate()));
        }

        SalesOrder refreshed = salesOrders.require(order.id());
        DocumentStatus target = refreshed.fullyShipped()
                ? DocumentStatus.COMPLETED : DocumentStatus.PARTIAL;
        DocumentStatusMachine.requireTransition(refreshed.status(), target,
                ErrorCode.SAL_STATUS_ILLEGAL, refreshed.orderNo());
        if (!salesOrderRows.updateStatus(refreshed.id(), refreshed.status(), target,
                refreshed.version())) {
            throw new BusinessException(ErrorCode.SAL_STATUS_ILLEGAL,
                    "出货单 %s 状态推进冲突（并发修改），请刷新后重试"
                            .formatted(refreshed.orderNo()));
        }
        receivables.recordShipment(order.id(), order.customerId(), order.bizDate(), shippedAmount);

        if (!shipments.complete(shipment.id(), ShipmentStatus.PICKED,
                shipment.version(), requestId)) {
            throw new BusinessException(ErrorCode.SAL_SHIPMENT_STATUS_ILLEGAL,
                    "发货单 %s 确认状态推进冲突（并发修改），请刷新后重试"
                            .formatted(shipment.shipmentNo()));
        }
        // V3-2 FR-V3-08：出库确认 → 自动生成凭证（幂等键 (SAL_OUT, shipmentId)）。
        vouchers.generateForSource("SAL_OUT", shipment.id());
        return require(shipmentId);
    }

    @Transactional(readOnly = true)
    public Shipment require(long id) {
        return shipments.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.SAL_SHIPMENT_NOT_FOUND,
                        "发货单不存在：id=%d".formatted(id)));
    }

    @Transactional(readOnly = true)
    public PageResult<Shipment> page(ShipmentStatus status, Long salesOrderId, int page, int size) {
        return shipments.page(status, salesOrderId, page, size);
    }

    /** 流水幂等键：一条拣货行一条 SALES_OUT 请求。 */
    private static String ledgerRequestId(String shipmentNo, int lineNo) {
        String suffix = ":L" + lineNo;
        String base = shipmentNo;
        if (base.length() > 64 - suffix.length()) {
            base = base.substring(0, 64 - suffix.length());
        }
        return base + suffix;
    }
}
