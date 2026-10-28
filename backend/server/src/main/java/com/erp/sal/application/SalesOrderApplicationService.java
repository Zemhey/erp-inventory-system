package com.erp.sal.application;

import com.erp.inv.application.InventoryApplicationService;
import com.erp.inv.application.InventoryApplicationService.ActiveReservation;
import com.erp.inv.application.InventoryApplicationService.ReserveResult;
import com.erp.mdm.domain.Sku;
import com.erp.mdm.domain.Warehouse;
import com.erp.mdm.repository.SkuRepository;
import com.erp.mdm.repository.WarehouseRepository;
import com.erp.sal.domain.BundleItem;
import com.erp.sal.domain.LineReservation;
import com.erp.sal.domain.ProductBundle;
import com.erp.sal.domain.SalesOrder;
import com.erp.sal.domain.SalesOrderLine;
import com.erp.sal.repository.SalesOrderRepository;
import com.erp.shared.db.DbErrors;
import com.erp.shared.document.DocumentNumberGenerator;
import com.erp.shared.document.DocumentStatus;
import com.erp.shared.document.DocumentStatusMachine;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.approval.ApprovalGate;
import com.erp.shared.period.PeriodGuard;
import com.erp.shared.security.ActorContext;
import com.erp.shared.time.ClockProvider;
import com.erp.shared.web.PageResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 销售出货应用服务（FR-SAL-01/02，V2-3 扩展 FR-SAL-05~11）。
 *
 * <p><b>为什么出货比进货难</b>：进货是"凭空增加"，出货是"在别人的份额里扣"。
 * 建单必须预占（否则会超卖），预占必须有失败路径（库存不足），
 * 而失败时必须保证<b>整单回滚</b> —— 否则会出现"单子没建成，但库存被神秘锁住"。
 *
 * <p><b>V2-3 新增的四条边界</b>：
 * <ul>
 *   <li>套装行展开为组件行，套装本身不持有库存（INV-16）；</li>
 *   <li>自动选仓：按 priority 逐仓吃量，单仓够则不拆；</li>
 *   <li>信用校验前置于<b>任何预占</b>（硬验收 6）；</li>
 *   <li>出货确认同步登记应收（FR-SAL-07）。</li>
 * </ul>
 */
@Service
public class SalesOrderApplicationService {

    private final SalesOrderRepository orders;
    private final WarehouseRepository warehouses;
    private final SkuRepository skus;
    private final InventoryApplicationService inventory;
    private final DocumentNumberGenerator numberGenerator;
    private final ClockProvider clock;
    private final PeriodGuard periodGuard;
    private final ProductBundleService bundleService;
    private final CreditService credit;
    private final ReceivableService receivables;
    /** 审批闸门（V2-6 FR-IAM-07）：只输出可否，不改单据状态。 */
    private final ApprovalGate approvalGate;
    /** 当前操作人（数据权限"按本人"）。 */
    private final ActorContext actors;

    /**
     * 凭证触发出口（依赖倒置见 {@code shared.finance.VoucherTrigger}）。
     * 出货完成时：借 1122 应收 / 贷 6001 收入；成本引擎有结转额时追加 6401/1405。
     */
    private final com.erp.shared.finance.VoucherTrigger vouchers;

    public SalesOrderApplicationService(SalesOrderRepository orders,
                                        WarehouseRepository warehouses,
                                        SkuRepository skus,
                                        InventoryApplicationService inventory,
                                        DocumentNumberGenerator numberGenerator,
                                        ClockProvider clock,
                                        PeriodGuard periodGuard,
                                        ProductBundleService bundleService,
                                        CreditService credit,
                                        ReceivableService receivables,
                                        ApprovalGate approvalGate,
                                        ActorContext actors,
                                        com.erp.shared.finance.VoucherTrigger vouchers) {
        this.orders = orders;
        this.warehouses = warehouses;
        this.skus = skus;
        this.inventory = inventory;
        this.numberGenerator = numberGenerator;
        this.clock = clock;
        this.periodGuard = periodGuard;
        this.bundleService = bundleService;
        this.credit = credit;
        this.receivables = receivables;
        this.approvalGate = approvalGate;
        this.actors = actors;
        this.vouchers = vouchers;
    }

    /** 折扣率基准：10000 = 不打折（整数万分比，ADR-0005）。 */
    public static final int NO_DISCOUNT = 10000;

    /**
     * 一行建单命令。
     *
     * <p>{@code skuId} 与 {@code bundleId} 二选一：套装行填 {@code bundleId}，
     * 其 {@code unitPriceAmount} 忽略（组件按各自零售价计价，见计划书 §2.3）。
     */
    public record CreateLineCommand(long skuId, int qty, long unitPriceAmount,
                                    Integer discountRate, Long bundleId) {

        /** 兼容 M1~M2.5 的普通 SKU 行。 */
        public CreateLineCommand(long skuId, int qty, long unitPriceAmount, Integer discountRate) {
            this(skuId, qty, unitPriceAmount, discountRate, null);
        }
    }

    public record CreateCommand(long customerId, long warehouseId, LocalDate bizDate,
                                String remark, List<CreateLineCommand> lines, String requestId) {
    }

    private record Allocation(long warehouseId, int qty) {
    }

    /**
     * 展开后的可落库行。
     *
     * <p>套装行会展开成多条组件行；{@code allocations} 记录该组件行在各仓的预占数量。
     */
    private record PreparedLine(long skuId, int qty, long unitPriceAmount, int discountRate,
                                long amount, Long bundleId, Integer bundleGroupNo,
                                Integer bundleRatio, List<Allocation> allocations) {
    }

    // ─────────────────────────── 建单（同时预占） ───────────────────────────

    /**
     * 指定仓建单（原有语义，FR-SAL-01）。
     */
    @Transactional
    public SalesOrder create(CreateCommand cmd) {
        return createInternal(cmd, false);
    }

    /**
     * 自动选仓建单（FR-SAL-11）：按仓库 priority 逐仓吃量，单仓不足才跨仓拆分配。
     *
     * <p>命令中的 {@code warehouseId} 被忽略（可传 0）；订单头记录首个分配仓。
     */
    @Transactional
    public SalesOrder createAutoSplit(CreateCommand cmd) {
        return createInternal(cmd, true);
    }

    private SalesOrder createInternal(CreateCommand cmd, boolean autoSplit) {
        Optional<SalesOrder> existing = orders.findByRequestId(cmd.requestId());
        if (existing.isPresent()) {
            return orders.findById(existing.get().id()).orElseThrow();
        }
        if (cmd.lines() == null || cmd.lines().isEmpty()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR, "出货单至少需要一行明细");
        }
        LocalDate bizDate = cmd.bizDate() == null ? clock.today() : cmd.bizDate();
        // 账期锁：置于幂等短路之后，重复提交不产生改动，锁账后也应原样返回首次结果。
        periodGuard.requireOpen(bizDate);

        List<Warehouse> autoWarehouses = List.of();
        if (autoSplit) {
            autoWarehouses = warehouses.findEnabledByPriority();
            if (autoWarehouses.isEmpty()) {
                throw new BusinessException(ErrorCode.MDM_WAREHOUSE_NOT_FOUND,
                        "没有启用的仓库，无法自动选仓");
            }
        } else {
            requireWarehouseEnabled(cmd.warehouseId());
        }

        // ① 展开套装 + 计算金额/分配（不碰数据库写）
        Map<Long, Map<Long, Integer>> availableCache = new HashMap<>();
        List<PreparedLine> prepared = prepareLines(cmd.lines(), autoSplit, cmd.warehouseId(),
                autoWarehouses, availableCache);
        long totalAmount = 0;
        int totalQty = 0;
        for (PreparedLine line : prepared) {
            totalAmount = Math.addExact(totalAmount, line.amount());
            totalQty += line.qty();
        }

        // ② ★ 信用校验必须在任何预占之前（硬验收 6）
        CreditService.Approval approval = credit.check(cmd.customerId(), totalAmount);

        // ③ 建单据头与明细
        long headerWarehouseId = autoSplit
                ? firstAllocationWarehouse(prepared)
                : cmd.warehouseId();
        String orderNo = numberGenerator.next(DocumentNumberGenerator.DocType.SALES_ORDER, bizDate);
        long orderId = orders.insertHeader(orderNo, cmd.customerId(), headerWarehouseId, bizDate,
                totalAmount, totalQty, periodOf(bizDate), trimToNull(cmd.remark()), cmd.requestId(),
                actors.current().map(ActorContext.Actor::id).orElse(null));
        int lineNo = 1;
        for (PreparedLine line : prepared) {
            orders.insertLine(orderId, lineNo++, line.skuId(), line.qty(),
                    line.unitPriceAmount(), line.discountRate(), line.amount(),
                    line.bundleId(), line.bundleGroupNo(), line.bundleRatio());
        }
        credit.recordSnapshot(approval, totalAmount, orderId, cmd.requestId());

        // ④ 逐行逐仓预占（任一段失败 → 整个事务回滚）
        List<SalesOrderLine> lines = orders.findLines(orderId);
        for (int i = 0; i < lines.size(); i++) {
            SalesOrderLine line = lines.get(i);
            PreparedLine preparedLine = prepared.get(i);
            int seq = 1;
            for (Allocation allocation : preparedLine.allocations()) {
                List<ReserveResult> segments = inventory.reserveFefo(
                        line.skuId(), allocation.warehouseId(), allocation.qty(),
                        reservationRequestId(cmd.requestId(), line.lineNo(),
                                allocation.warehouseId()));
                for (ReserveResult r : segments) {
                    orders.attachReservation(line.id(), r.reservationId(), seq++,
                            r.reservedQty(), r.warehouseId(), r.batchNo());
                }
            }
        }
        return require(orderId);
    }

    // ─────────────────────────── 状态流转 ───────────────────────────

    @Transactional
    public SalesOrder submit(long id) {
        SalesOrder o = require(id);
        periodGuard.requireOpen(o.bizDate());
        DocumentStatusMachine.requireTransition(o.status(), DocumentStatus.SUBMITTED,
                ErrorCode.SAL_STATUS_ILLEGAL, o.orderNo());
        transition(o, DocumentStatus.SUBMITTED);
        return require(id);
    }

    @Transactional
    public SalesOrder approve(long id) {
        SalesOrder o = require(id);
        periodGuard.requireOpen(o.bizDate());
        // ★ 审批闸门：无启用流程时静默通过；有流程时要求审批已通过。不代审批改状态。
        approvalGate.requireApproved(ApprovalGate.BizType.SALES_ORDER, o.id(),
                o.orderNo(), o.totalAmount());
        DocumentStatusMachine.requireTransition(o.status(), DocumentStatus.APPROVED,
                ErrorCode.SAL_STATUS_ILLEGAL, o.orderNo());
        transition(o, DocumentStatus.APPROVED);
        return require(id);
    }

    @Transactional
    public SalesOrder reject(long id) {
        SalesOrder o = require(id);
        periodGuard.requireOpen(o.bizDate());
        DocumentStatusMachine.requireTransition(o.status(), DocumentStatus.REJECTED,
                ErrorCode.SAL_STATUS_ILLEGAL, o.orderNo());
        transition(o, DocumentStatus.REJECTED);
        return require(id);
    }

    /**
     * 作废出货单（FR-SAL-04，M2.5 红冲闭环）。
     *
     * <p>未出货：释放预占；已出货：按原 SALES_OUT 流水逐批次回补并写 SALES_REVERSAL。
     * V2-3 额外约束：应收已收款的单据拒绝红冲，避免"钱收了、单作废"的死账。
     */
    @Transactional
    public SalesOrder voidOrder(long id, String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "作废出货单必须提供幂等键 X-Request-Id");
        }
        SalesOrder o = require(id);

        if (o.status() == DocumentStatus.VOIDED) {
            if (orders.isVoidedByRequest(o.id(), requestId)) {
                return o;
            }
            throw new BusinessException(ErrorCode.SAL_STATUS_ILLEGAL,
                    "出货单 %s 已作废（首次作废请求与本次不同），不能再次作废"
                            .formatted(o.orderNo()));
        }
        periodGuard.requireOpen(o.bizDate());

        if (o.anyShipped()) {
            return reverseShippedOrder(o, requestId);
        }

        DocumentStatusMachine.requireTransition(o.status(), DocumentStatus.VOIDED,
                ErrorCode.SAL_STATUS_ILLEGAL, o.orderNo());
        if (!markVoided(o, requestId, false)) {
            return require(id);
        }
        for (SalesOrderLine line : o.lines()) {
            for (long reservationId : line.reservationIds()) {
                inventory.release(reservationId);
            }
        }
        return require(id);
    }

    private SalesOrder reverseShippedOrder(SalesOrder o, String requestId) {
        if (o.anyReturned()) {
            int returned = o.lines().stream().mapToInt(SalesOrderLine::returnedQty).sum();
            throw new BusinessException(ErrorCode.SAL_STATUS_ILLEGAL,
                    "出货单 %s 已退货 %d 件，不能红冲（退货已回补库存，再红冲会重复入库）"
                            .formatted(o.orderNo(), returned));
        }
        DocumentStatusMachine.requireRedReversal(o.status(), ErrorCode.SAL_STATUS_ILLEGAL,
                o.orderNo());

        int shipped = o.lines().stream().mapToInt(SalesOrderLine::shippedQty).sum();
        int ledgerOut = inventory.ledgerEntries(o.orderNo(), "SALES_OUT").stream()
                .mapToInt(e -> -e.changeQty()).sum();
        if (shipped != ledgerOut) {
            throw new BusinessException(ErrorCode.SAL_STATUS_ILLEGAL,
                    "出货单 %s 的累计出货量 %d 与 SALES_OUT 流水净额 %d 不一致，拒绝红冲"
                            .formatted(o.orderNo(), shipped, ledgerOut),
                    Map.of("orderNo", o.orderNo(), "shippedQty", shipped,
                            "ledgerQty", ledgerOut));
        }

        // 已收款不可红冲：在动库存之前拒绝，保证失败无副作用
        receivables.reverseForVoid(o.id());

        if (!markVoided(o, requestId, true)) {
            return require(o.id());
        }

        int reversed = inventory.reverseSalesOut(o.orderNo(), o.id(), requestId);
        if (reversed != shipped) {
            throw new BusinessException(ErrorCode.SAL_STATUS_ILLEGAL,
                    "出货单 %s 红冲数量 %d 与累计出货量 %d 不一致，已回滚"
                            .formatted(o.orderNo(), reversed, shipped));
        }
        for (SalesOrderLine line : o.lines()) {
            for (long reservationId : line.reservationIds()) {
                inventory.releaseIfActive(reservationId);
            }
        }
        return require(o.id());
    }

    private boolean markVoided(SalesOrder o, String requestId, boolean noReturnsAllowed) {
        boolean updated;
        try {
            updated = noReturnsAllowed
                    ? orders.markVoidedWithNoReturns(o.id(), o.status(), o.version(), requestId)
                    : orders.markVoided(o.id(), o.status(), o.version(), requestId);
        } catch (DataIntegrityViolationException ex) {
            BusinessException duplicate = DbErrors.translate(ex, "uq_so_void_request",
                    ErrorCode.SYS_DUPLICATE_REQUEST,
                    "重复的作废请求：该幂等键已经用于另一张出货单");
            if (duplicate != null) {
                throw duplicate;
            }
            throw ex;
        }
        if (updated) {
            return true;
        }
        SalesOrder latest = require(o.id());
        if (latest.status() == DocumentStatus.VOIDED
                && orders.isVoidedByRequest(latest.id(), requestId)) {
            return false;
        }
        if (latest.anyReturned()) {
            throw new BusinessException(ErrorCode.SAL_STATUS_ILLEGAL,
                    "出货单 %s 已退货，不能红冲".formatted(latest.orderNo()));
        }
        if (latest.status() == DocumentStatus.VOIDED) {
            throw new BusinessException(ErrorCode.SAL_STATUS_ILLEGAL,
                    "出货单 %s 已作废（首次作废请求与本次不同），不能再次作废"
                            .formatted(latest.orderNo()));
        }
        throw new BusinessException(ErrorCode.SAL_STATUS_ILLEGAL,
                "出货单 %s 状态推进冲突（并发修改），请刷新后重试：期望 %s -> VOIDED"
                        .formatted(latest.orderNo(), o.status()));
    }

    // ─────────────────────────── 出货确认（★ 调 INV） ───────────────────────────

    /**
     * 出货确认：把预占转为实际出库（FR-SAL-02）。
     *
     * <p>V2-3：按预占的 {@code warehouse_id} 分组出库，支持自动选仓/跨仓拆分配；
     * 出货完成后按本次实际出货金额登记应收。
     */
    @Transactional
    public SalesOrder ship(long id, String requestId) {
        SalesOrder o = require(id);
        if (!o.status().isExecutable()) {
            throw new BusinessException(ErrorCode.SAL_STATUS_ILLEGAL,
                    "出货单 %s 当前状态 %s 不可出货（仅 APPROVED / PARTIAL 可出货）"
                            .formatted(o.orderNo(), o.status()));
        }
        periodGuard.requireOpen(o.bizDate());

        long shippedAmount = 0;
        for (SalesOrderLine line : o.lines()) {
            int openQty = line.openQty();
            if (openQty <= 0) {
                continue;
            }
            Map<Long, List<ActiveReservation>> byWarehouse =
                    activeReservationsByWarehouse(line, o.warehouseId());
            int reserved = byWarehouse.values().stream()
                    .flatMap(List::stream).mapToInt(ActiveReservation::reservedQty).sum();
            if (reserved != openQty) {
                throw new BusinessException(ErrorCode.INV_INSUFFICIENT_STOCK,
                        "出货单 %s 第 %d 行预占不足：未出货 %d，ACTIVE 预占 %d"
                                .formatted(o.orderNo(), line.lineNo(), openQty, reserved),
                        Map.of("orderNo", o.orderNo(), "lineNo", line.lineNo(),
                                "openQty", openQty, "reservedQty", reserved));
            }
            if (!orders.applyShippedQty(line.id(), openQty)) {
                throw new BusinessException(ErrorCode.SAL_STATUS_ILLEGAL,
                        "出货数量超出单据数量（并发冲突）：出货单 %s 第 %d 行"
                                .formatted(o.orderNo(), line.lineNo()));
            }
            for (Map.Entry<Long, List<ActiveReservation>> entry : byWarehouse.entrySet()) {
                long warehouseId = entry.getKey();
                int qty = entry.getValue().stream()
                        .mapToInt(ActiveReservation::reservedQty).sum();
                inventory.shipOut(line.skuId(), warehouseId, qty,
                        "SALES_OUT", o.orderNo(), o.id(),
                        shipRequestId(requestId, line.lineNo(), warehouseId));
                for (ActiveReservation reservation : entry.getValue()) {
                    inventory.consumeReservation(reservation.id());
                }
            }
            shippedAmount = Math.addExact(shippedAmount, lineAmount(
                    openQty, line.unitPriceAmount(), line.discountRate()));
        }

        SalesOrder refreshed = require(id);
        DocumentStatus target = refreshed.fullyShipped()
                ? DocumentStatus.COMPLETED
                : DocumentStatus.PARTIAL;
        DocumentStatusMachine.requireTransition(refreshed.status(), target,
                ErrorCode.SAL_STATUS_ILLEGAL, refreshed.orderNo());
        transition(refreshed, target);
        receivables.recordShipment(id, refreshed.customerId(), refreshed.bizDate(), shippedAmount);

        // V3-5 FR-V3-08 补口：直接出货路径也要出凭证（此前只有发货单确认会生成，
        // 从出货单页面点"出货"扣了库存、记了应收，总账却一片空白）。
        //
        // 只在整单发完时生成：凭证源取的是累计已发量，而幂等键 (SAL_ORDER_OUT, 单 id)
        // 决定一单只出一张 —— 若在部分出货时就生成，后续补发会被幂等吞掉，永久少计收入。
        //
        // 已知边界：同一张单既走发货单又走直接出货时，两边各出一张会重复入账。
        // 这两条是互斥的履约流程，界面按单据状态只会开放其一，故不在本次范围内做去重。
        if (target == DocumentStatus.COMPLETED) {
            vouchers.generateForSource("SAL_ORDER_OUT", id);
        }
        return require(id);
    }

    /** 按 ACTIVE 预占的仓库分组；历史数据缺 warehouse_id 时回退到订单头仓库。 */
    private Map<Long, List<ActiveReservation>> activeReservationsByWarehouse(
            SalesOrderLine line, long fallbackWarehouseId) {
        Map<Long, ActiveReservation> activeById = new LinkedHashMap<>();
        for (ActiveReservation r : inventory.findActiveReservations(line.reservationIds())) {
            activeById.put(r.id(), r);
        }
        Map<Long, List<ActiveReservation>> grouped = new LinkedHashMap<>();
        for (LineReservation segment : line.reservations()) {
            ActiveReservation active = activeById.get(segment.reservationId());
            if (active == null) {
                continue;
            }
            long warehouseId = segment.warehouseId() == null
                    ? (active.warehouseId() == 0 ? fallbackWarehouseId : active.warehouseId())
                    : segment.warehouseId();
            grouped.computeIfAbsent(warehouseId, k -> new ArrayList<>()).add(active);
        }
        return grouped;
    }

    // ─────────────────────────── 查询 ───────────────────────────

    @Transactional(readOnly = true)
    public SalesOrder require(long id) {
        return orders.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.SAL_ORDER_NOT_FOUND,
                        "出货单不存在：id=%d".formatted(id)));
    }

    @Transactional(readOnly = true)
    public PageResult<SalesOrder> page(DocumentStatus status, Long customerId, int page, int size) {
        return orders.page(status, customerId, page, size);
    }

    /** 带业务日期区间与排序的分页（"最近一个月的出货单，按时间排序"用）。 */
    @Transactional(readOnly = true)
    public PageResult<SalesOrder> page(DocumentStatus status, Long customerId,
                                       LocalDate dateFrom, LocalDate dateTo,
                                       String orderBy, boolean ascending, int page, int size) {
        return orders.page(status, customerId, dateFrom, dateTo, orderBy, ascending, page, size);
    }

    /** 客户的销售画像（"这个客户跟我做了多少生意"用），统计口径与列表页一致。 */
    @Transactional(readOnly = true)
    public com.erp.shared.document.PartyDealSummary summaryByCustomer(long customerId) {
        return orders.summaryByCustomer(customerId);
    }

    // ─────────────────────────── 金额 ───────────────────────────

    /**
     * 行金额 = round(qty × unitPrice × discountRate / 10000)，四舍五入到分。
     */
    public static long lineAmount(int qty, long unitPriceAmount, int discountRate) {
        BigDecimal raw = BigDecimal.valueOf(unitPriceAmount)
                .multiply(BigDecimal.valueOf(qty))
                .multiply(BigDecimal.valueOf(discountRate))
                .divide(BigDecimal.valueOf(NO_DISCOUNT), 0, RoundingMode.HALF_UP);
        return raw.longValueExact();
    }

    // ─────────────────────────── 展开与选仓 ───────────────────────────

    private List<PreparedLine> prepareLines(List<CreateLineCommand> lines, boolean autoSplit,
                                            long fixedWarehouseId, List<Warehouse> autoWarehouses,
                                            Map<Long, Map<Long, Integer>> availableCache) {
        List<PreparedLine> prepared = new ArrayList<>();
        int bundleGroupNo = 0;
        for (CreateLineCommand line : lines) {
            if (line.qty() <= 0) {
                throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                        "明细数量必须为正：skuId=%d, qty=%d"
                                .formatted(line.skuId(), line.qty()));
            }
            int rate = line.discountRate() == null ? NO_DISCOUNT : line.discountRate();
            if (rate <= 0 || rate > NO_DISCOUNT) {
                throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                        "折扣率非法：%d，必须是 1~10000 的整数万分比".formatted(rate));
            }
            boolean hasSku = line.skuId() > 0;
            boolean hasBundle = line.bundleId() != null;
            if (hasSku == hasBundle) {
                throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                        "每行必须且只能指定 skuId 或 bundleId 之一");
            }
            if (hasBundle) {
                bundleGroupNo++;
                prepared.addAll(expandBundle(line, rate, bundleGroupNo, autoSplit,
                        fixedWarehouseId, autoWarehouses, availableCache));
            } else {
                if (line.unitPriceAmount() < 0) {
                    throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                            "单价不能为负：skuId=%d".formatted(line.skuId()));
                }
                Sku sku = requireSkuEnabled(line.skuId());
                List<Allocation> allocations = autoSplit
                        ? allocateSku(sku.id(), line.qty(), autoWarehouses, availableCache)
                        : List.of(new Allocation(fixedWarehouseId, line.qty()));
                prepared.add(new PreparedLine(sku.id(), line.qty(), line.unitPriceAmount(),
                        rate, lineAmount(line.qty(), line.unitPriceAmount(), rate),
                        null, null, null, allocations));
            }
        }
        return prepared;
    }

    private List<PreparedLine> expandBundle(CreateLineCommand line, int rate, int groupNo,
                                            boolean autoSplit, long fixedWarehouseId,
                                            List<Warehouse> autoWarehouses,
                                            Map<Long, Map<Long, Integer>> availableCache) {
        ProductBundle bundle = bundleService.requireEnabled(line.bundleId());
        List<Allocation> bundleAllocations = autoSplit
                ? allocateBundle(bundle, line.qty(), autoWarehouses, availableCache)
                : List.of(new Allocation(fixedWarehouseId, line.qty()));
        List<PreparedLine> components = new ArrayList<>();
        for (BundleItem item : bundle.items()) {
            Sku component = requireSkuEnabled(item.componentSkuId());
            int componentQty = Math.multiplyExact(line.qty(), item.ratioQty());
            List<Allocation> componentAllocations = bundleAllocations.stream()
                    .map(a -> new Allocation(a.warehouseId(),
                            Math.multiplyExact(a.qty(), item.ratioQty())))
                    .toList();
            components.add(new PreparedLine(component.id(), componentQty,
                    component.retailPriceAmount(), rate,
                    lineAmount(componentQty, component.retailPriceAmount(), rate),
                    bundle.id(), groupNo, item.ratioQty(), componentAllocations));
        }
        return components;
    }

    /** 单仓够则只产生一个分配；不足才按 priority 进入下一仓。 */
    private List<Allocation> allocateSku(long skuId, int qty, List<Warehouse> warehousesInPriority,
                                         Map<Long, Map<Long, Integer>> cache) {
        List<Allocation> allocations = new ArrayList<>();
        int remaining = qty;
        for (Warehouse warehouse : warehousesInPriority) {
            int available = remainingAvailable(skuId, warehouse.id(), cache);
            int take = Math.min(remaining, available);
            if (take > 0) {
                allocations.add(new Allocation(warehouse.id(), take));
                consumeAvailable(skuId, warehouse.id(), take, cache);
                remaining -= take;
            }
            if (remaining == 0) {
                break;
            }
        }
        if (remaining > 0) {
            throw new BusinessException(ErrorCode.INV_INSUFFICIENT_STOCK,
                    "库存不足：SKU %d 在所有启用仓合计仍差 %d 件".formatted(skuId, remaining),
                    Map.of("skuId", skuId, "shortage", remaining));
        }
        return allocations;
    }

    /** 套装按仓成套吃量：每仓可用套装 = min(组件可用 ÷ 配比)。 */
    private List<Allocation> allocateBundle(ProductBundle bundle, int bundleQty,
                                            List<Warehouse> warehousesInPriority,
                                            Map<Long, Map<Long, Integer>> cache) {
        List<Allocation> allocations = new ArrayList<>();
        int remaining = bundleQty;
        for (Warehouse warehouse : warehousesInPriority) {
            int availableBundles = Integer.MAX_VALUE;
            for (BundleItem item : bundle.items()) {
                availableBundles = Math.min(availableBundles,
                        remainingAvailable(item.componentSkuId(), warehouse.id(), cache)
                                / item.ratioQty());
            }
            int take = Math.min(remaining, Math.max(0, availableBundles));
            if (take > 0) {
                allocations.add(new Allocation(warehouse.id(), take));
                for (BundleItem item : bundle.items()) {
                    consumeAvailable(item.componentSkuId(), warehouse.id(),
                            Math.multiplyExact(take, item.ratioQty()), cache);
                }
                remaining -= take;
            }
            if (remaining == 0) {
                break;
            }
        }
        if (remaining > 0) {
            throw new BusinessException(ErrorCode.INV_INSUFFICIENT_STOCK,
                    "套装 %s 库存不足：还差 %d 套（按组件可用量÷配比计算）"
                            .formatted(bundle.code(), remaining),
                    Map.of("bundleId", bundle.id(), "shortage", remaining));
        }
        return allocations;
    }

    private int remainingAvailable(long skuId, long warehouseId,
                                   Map<Long, Map<Long, Integer>> cache) {
        return cache.computeIfAbsent(skuId, k -> new HashMap<>())
                .computeIfAbsent(warehouseId, w -> inventory.availableQty(skuId, warehouseId));
    }

    private void consumeAvailable(long skuId, long warehouseId, int qty,
                                  Map<Long, Map<Long, Integer>> cache) {
        cache.computeIfAbsent(skuId, k -> new HashMap<>())
                .merge(warehouseId, -qty, Integer::sum);
    }

    private static long firstAllocationWarehouse(List<PreparedLine> prepared) {
        return prepared.stream()
                .flatMap(p -> p.allocations().stream())
                .map(Allocation::warehouseId)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                        "没有可用的库存分配"));
    }

    // ─────────────────────────── 内部辅助 ───────────────────────────

    private void transition(SalesOrder o, DocumentStatus target) {
        if (!orders.updateStatus(o.id(), o.status(), target, o.version())) {
            throw new BusinessException(ErrorCode.SAL_STATUS_ILLEGAL,
                    "出货单 %s 状态推进冲突（并发修改），请刷新后重试：期望 %s -> %s"
                            .formatted(o.orderNo(), o.status(), target));
        }
    }

    private void requireWarehouseEnabled(long warehouseId) {
        var w = warehouses.findById(warehouseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MDM_WAREHOUSE_NOT_FOUND,
                        "仓库不存在：id=%d".formatted(warehouseId)));
        if (!w.enabled()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "仓库已停用，不能建单：%s".formatted(w.code()));
        }
    }

    private Sku requireSkuEnabled(long skuId) {
        Sku s = skus.findById(skuId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MDM_SKU_NOT_FOUND,
                        "SKU 不存在：id=%d".formatted(skuId)));
        if (!s.enabled()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "SKU 已停用，不能建单：id=%d".formatted(skuId));
        }
        return s;
    }

    /** 预占幂等键按「行 + 仓」唯一；截断以适配 VARCHAR(64)。 */
    private static String reservationRequestId(String baseRequestId, int lineNo, long warehouseId) {
        String base = baseRequestId == null || baseRequestId.isBlank()
                ? "so-reserve" : baseRequestId;
        String suffix = ":R" + lineNo + ":W" + warehouseId;
        if (base.length() > 64 - suffix.length()) {
            base = base.substring(0, 64 - suffix.length());
        }
        return base + suffix;
    }

    /** 出货流水幂等键按「行 + 仓」唯一；截断以适配 VARCHAR(64)。 */
    private static String shipRequestId(String baseRequestId, int lineNo, long warehouseId) {
        String base = baseRequestId == null || baseRequestId.isBlank() ? "so-ship" : baseRequestId;
        String suffix = ":S" + lineNo + ":W" + warehouseId;
        if (base.length() > 64 - suffix.length()) {
            base = base.substring(0, 64 - suffix.length());
        }
        return base + suffix;
    }

    private static String periodOf(LocalDate bizDate) {
        return "%04d-%02d".formatted(bizDate.getYear(), bizDate.getMonthValue());
    }

    private static String trimToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
