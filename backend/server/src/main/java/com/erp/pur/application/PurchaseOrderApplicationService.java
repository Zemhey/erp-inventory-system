package com.erp.pur.application;

import com.erp.inv.application.InventoryApplicationService;
import com.erp.mdm.repository.SkuRepository;
import com.erp.mdm.repository.SupplierRepository;
import com.erp.mdm.repository.WarehouseRepository;
import com.erp.pur.domain.PurchaseOrder;
import com.erp.pur.domain.PurchaseOrderLine;
import com.erp.pur.domain.PurchaseReceipt;
import com.erp.pur.domain.QcStatus;
import com.erp.pur.repository.PurchaseOrderRepository;
import com.erp.pur.repository.PurchaseReceiptRepository;
import com.erp.shared.document.DocumentNumberGenerator;
import com.erp.shared.document.DocumentStatus;
import com.erp.shared.document.DocumentStatusMachine;
import com.erp.shared.db.DbErrors;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 进货应用服务（FR-PUR-01 建单/提交/审核、FR-PUR-02 入库确认）。
 *
 * <p><b>本类是 M1 的核心</b>：它是"单据 → 库存"的第一次真实联动，
 * 也是跨模块调用规则的示范（计划书 §3.3）：
 *
 * <pre>
 * PurchaseOrderApplicationService ──调用──▶ InventoryApplicationService.receiveIn(...)
 * </pre>
 *
 * <p><b>绝对不要</b>在这里写 {@code UPDATE inventory_balances}。
 * 这条规则由 ArchUnit 测试（{@code ArchitectureTest}）在 CI 中强制。
 */
@Service
public class PurchaseOrderApplicationService {

    private final PurchaseOrderRepository orders;
    private final PurchaseReceiptRepository receipts;
    private final SupplierPayableService payables;
    private final SupplierRepository suppliers;
    private final WarehouseRepository warehouses;
    private final SkuRepository skus;
    private final InventoryApplicationService inventory;
    private final DocumentNumberGenerator numberGenerator;
    private final ClockProvider clock;
    /**
     * 账期锁（M2-S2）。只依赖 {@code shared.period} 的接口，实现由 SET 提供 ——
     * 若这里 import {@code com.erp.set}，就会和"SET 只读进货单据"形成包依赖环，
     * 这条边界由 ArchUnit 的「单据模块不得依赖 set」规则守住。
     */
    private final PeriodGuard periodGuard;

    public PurchaseOrderApplicationService(PurchaseOrderRepository orders,
                                           PurchaseReceiptRepository receipts,
                                           SupplierPayableService payables,
                                           SupplierRepository suppliers,
                                           WarehouseRepository warehouses,
                                           SkuRepository skus,
                                           InventoryApplicationService inventory,
                                           DocumentNumberGenerator numberGenerator,
                                           ClockProvider clock,
                                           PeriodGuard periodGuard) {
        this.orders = orders;
        this.receipts = receipts;
        this.payables = payables;
        this.suppliers = suppliers;
        this.warehouses = warehouses;
        this.skus = skus;
        this.inventory = inventory;
        this.numberGenerator = numberGenerator;
        this.clock = clock;
        this.periodGuard = periodGuard;
    }

    // ─────────────────────────── 命令 / 结果 ───────────────────────────

    public record CreateLineCommand(long skuId, int qty, long unitPriceAmount) {
    }

    public record CreateCommand(long supplierId, long warehouseId, LocalDate bizDate,
                                String remark, List<CreateLineCommand> lines, String requestId) {
    }

    /**
     * 入库确认的一行：指定明细行与本次实收数量（可选指定批次与效期）。
     *
     * <p>V2-2 增加 {@code qcQty / qcNote}：{@code qcQty} 是本次到货中的合格数量，
     * 缺省等于 {@code qty}（兼容 M1 的调用与前端旧版本）。
     * 不合格量只记到货单，不产生库存与应付。
     */
    public record ReceiveLineCommand(long lineId, int qty, String batchNo,
                                     LocalDate productionDate, LocalDate expiryDate,
                                     Integer qcQty, String qcNote) {

        public ReceiveLineCommand(long lineId, int qty, String batchNo,
                                  LocalDate productionDate, LocalDate expiryDate) {
            this(lineId, qty, batchNo, productionDate, expiryDate, null, null);
        }
    }

    public record ReceiveCommand(List<ReceiveLineCommand> lines, String requestId) {
    }

    /** 预检通过、准备落库的一行。 */
    private record PreparedReceive(PurchaseOrderLine line, int qty, int qcQty, String qcNote,
                                   String batchNo, LocalDate productionDate, LocalDate expiryDate,
                                   long acceptedAmount, QcStatus qcStatus) {
    }

    // ─────────────────────────── 建单 ───────────────────────────

    /**
     * 创建进货单（草稿）。
     *
     * <p><b>幂等</b>：同一 {@code requestId} 重复提交直接返回首次建成的单据，
     * 由数据库唯一索引 {@code uq_po_request} 兜底（并发下也一样）。
     *
     * <p><b>金额规则</b>（ADR-0005）：{@code amount = qty × unitPriceAmount}，
     * 总金额由明细汇总得出，<b>不允许反算</b>。
     */
    @Transactional
    public PurchaseOrder create(CreateCommand cmd) {
        Optional<PurchaseOrder> existing = orders.findByRequestId(cmd.requestId());
        if (existing.isPresent()) {
            return orders.findById(existing.get().id()).orElseThrow();
        }

        requireSupplierEnabled(cmd.supplierId());
        requireWarehouseEnabled(cmd.warehouseId());
        if (cmd.lines() == null || cmd.lines().isEmpty()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR, "进货单至少需要一行明细");
        }
        LocalDate bizDate = cmd.bizDate() == null ? clock.today() : cmd.bizDate();
        // 账期锁：放在幂等短路之后 —— 重复提交同一个 requestId 不产生任何改动，
        // 即便账期已锁也必须原样返回首次结果，否则客户端会误以为建单失败而重做。
        periodGuard.requireOpen(bizDate);

        long totalAmount = 0;
        int totalQty = 0;
        List<long[]> computed = new ArrayList<>();   // skuId, qty, unitPrice, amount
        for (CreateLineCommand line : cmd.lines()) {
            if (line.qty() <= 0) {
                throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                        "明细数量必须为正：skuId=%d, qty=%d".formatted(line.skuId(), line.qty()));
            }
            if (line.unitPriceAmount() < 0) {
                throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                        "单价不能为负：skuId=%d, unitPrice=%d"
                                .formatted(line.skuId(), line.unitPriceAmount()));
            }
            requireSkuEnabled(line.skuId());
            long amount = Math.multiplyExact((long) line.qty(), line.unitPriceAmount());
            totalAmount = Math.addExact(totalAmount, amount);
            totalQty += line.qty();
            computed.add(new long[]{line.skuId(), line.qty(), line.unitPriceAmount(), amount});
        }

        String orderNo = numberGenerator.next(DocumentNumberGenerator.DocType.PURCHASE_ORDER, bizDate);
        long orderId = orders.insertHeader(orderNo, cmd.supplierId(), cmd.warehouseId(), bizDate,
                totalAmount, totalQty, periodOf(bizDate), trimToNull(cmd.remark()), cmd.requestId());
        int lineNo = 1;
        for (long[] c : computed) {
            orders.insertLine(orderId, lineNo++, c[0], (int) c[1], c[2], c[3]);
        }
        return require(orderId);
    }

    // ─────────────────────────── 状态流转 ───────────────────────────

    @Transactional
    public PurchaseOrder submit(long id) {
        PurchaseOrder o = require(id);
        periodGuard.requireOpen(o.bizDate());
        DocumentStatusMachine.requireTransition(o.status(), DocumentStatus.SUBMITTED,
                ErrorCode.PUR_STATUS_ILLEGAL, o.orderNo());
        transition(o, DocumentStatus.SUBMITTED);
        return require(id);
    }

    @Transactional
    public PurchaseOrder approve(long id) {
        PurchaseOrder o = require(id);
        periodGuard.requireOpen(o.bizDate());
        DocumentStatusMachine.requireTransition(o.status(), DocumentStatus.APPROVED,
                ErrorCode.PUR_STATUS_ILLEGAL, o.orderNo());
        transition(o, DocumentStatus.APPROVED);
        return require(id);
    }

    @Transactional
    public PurchaseOrder reject(long id) {
        PurchaseOrder o = require(id);
        periodGuard.requireOpen(o.bizDate());
        DocumentStatusMachine.requireTransition(o.status(), DocumentStatus.REJECTED,
                ErrorCode.PUR_STATUS_ILLEGAL, o.orderNo());
        transition(o, DocumentStatus.REJECTED);
        return require(id);
    }

    /**
     * 作废进货单（FR-PUR-03，M2.5 补全红冲闭环）。
     *
     * <p><b>未入库</b>：状态 → VOIDED，不动库存。
     *
     * <p><b>已入库（PARTIAL/COMPLETED）</b>：红冲 —— 按原 `PURCHASE_IN` 流水逐批次回退实存，
     * 写 `PURCHASE_REVERSAL` 负向流水。每个批次都要求当前实存足够且回退后不低于预占，
     * 否则 `INV_NEGATIVE_NOT_ALLOWED`（不跨批次挪用）。
     *
     * <p>账期已锁 → `SET_PERIOD_CLOSED`；同 `X-Request-Id` 重放幂等；
     * 新键重复作废报 `PUR_STATUS_ILLEGAL`。
     */
    @Transactional
    public PurchaseOrder voidOrder(long id, String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "作废进货单必须提供幂等键 X-Request-Id");
        }
        PurchaseOrder o = require(id);

        if (o.status() == DocumentStatus.VOIDED) {
            if (orders.isVoidedByRequest(o.id(), requestId)) {
                return o;
            }
            throw new BusinessException(ErrorCode.PUR_STATUS_ILLEGAL,
                    "进货单 %s 已作废（首次作废请求与本次不同），不能再次作废"
                            .formatted(o.orderNo()));
        }

        periodGuard.requireOpen(o.bizDate());

        if (o.anyReceived()) {
            return reverseReceivedOrder(o, requestId);
        }

        DocumentStatusMachine.requireTransition(o.status(), DocumentStatus.VOIDED,
                ErrorCode.PUR_STATUS_ILLEGAL, o.orderNo());
        markVoided(o, requestId);
        return require(id);
    }

    /** 已入库单据的红冲：校验 → 状态串行化 → 库存逐批次回退。 */
    private PurchaseOrder reverseReceivedOrder(PurchaseOrder o, String requestId) {
        DocumentStatusMachine.requireRedReversal(o.status(), ErrorCode.PUR_STATUS_ILLEGAL,
                o.orderNo());

        int received = o.lines().stream().mapToInt(PurchaseOrderLine::receivedQty).sum();
        int ledgerIn = inventory.ledgerEntries(o.orderNo(), "PURCHASE_IN").stream()
                .mapToInt(com.erp.inv.domain.InventoryLedgerEntry::changeQty).sum();
        if (received != ledgerIn) {
            throw new BusinessException(ErrorCode.PUR_STATUS_ILLEGAL,
                    "进货单 %s 的累计入库量 %d 与 PURCHASE_IN 流水净额 %d 不一致，拒绝红冲"
                            .formatted(o.orderNo(), received, ledgerIn),
                    java.util.Map.of("orderNo", o.orderNo(), "receivedQty", received,
                            "ledgerQty", ledgerIn));
        }

        if (!markVoided(o, requestId)) {
            return require(o.id());   // 并发同键：另一个请求已完成红冲，本请求幂等收口
        }

        int reversed = inventory.reversePurchaseIn(o.orderNo(), o.id(), requestId);
        if (reversed != received) {
            throw new BusinessException(ErrorCode.PUR_STATUS_ILLEGAL,
                    "进货单 %s 红冲数量 %d 与累计入库量 %d 不一致，已回滚"
                            .formatted(o.orderNo(), reversed, received));
        }
        return require(o.id());
    }

    /**
     * 状态条件更新 + 幂等键；失败时按「同键重放 / 已作废 / 并发冲突」分流。
     *
     * @return true = 本次请求完成了状态翻转；false = 并发同键的另一请求已完成
     */
    private boolean markVoided(PurchaseOrder o, String requestId) {
        boolean updated;
        try {
            updated = orders.markVoided(o.id(), o.status(), o.version(), requestId);
        } catch (DataIntegrityViolationException ex) {
            BusinessException duplicate = DbErrors.translate(ex, "uq_po_void_request",
                    ErrorCode.SYS_DUPLICATE_REQUEST,
                    "重复的作废请求：该幂等键已经用于另一张进货单");
            if (duplicate != null) {
                throw duplicate;
            }
            throw ex;
        }
        if (updated) {
            return true;
        }
        PurchaseOrder latest = require(o.id());
        if (latest.status() == DocumentStatus.VOIDED
                && orders.isVoidedByRequest(latest.id(), requestId)) {
            return false;
        }
        if (latest.status() == DocumentStatus.VOIDED) {
            throw new BusinessException(ErrorCode.PUR_STATUS_ILLEGAL,
                    "进货单 %s 已作废（首次作废请求与本次不同），不能再次作废"
                            .formatted(latest.orderNo()));
        }
        throw new BusinessException(ErrorCode.PUR_STATUS_ILLEGAL,
                "进货单 %s 状态推进冲突（并发修改），请刷新后重试：期望 %s -> VOIDED"
                        .formatted(latest.orderNo(), o.status()));
    }

    // ─────────────────────────── 入库确认（★ 调 INV） ───────────────────────────

    /**
     * 入库确认：把单据上的数量真实入到库存。
     *
     * <p><b>这是本模块唯一允许触碰库存的地方</b>，且必须经由
     * {@link InventoryApplicationService#receiveIn}（计划书 §3.3）：
     *
     * <pre>
     * // ❌ 严禁：PUR 模块自己写库存
     * jdbc.update("UPDATE inventory_balances SET on_hand_qty = on_hand_qty + ?", qty);
     * // ✅ 正确
     * inventory.receiveIn(skuId, warehouseId, batchNo, ..., qty, "PURCHASE_IN", orderNo, ...);
     * </pre>
     *
     * <p><b>分批入库</b>：每次只需提交部分行与部分数量；累计入库量不得超过单据数量
     * （BR-DOC-04 → {@code PUR_OVER_RECEIVE}），由 SQL 条件原子守卫，并发安全。
     *
     * <p><b>状态推进</b>：全部入库完 → COMPLETED；否则 → PARTIAL。
     * 单据进入 COMPLETED 之后不允许再入库。
     *
     * <p>整个方法在一个事务里：任一行失败（超量 / SKU 不存在 / 仓库不存在），
     * 已处理的行的库存增量会随事务一起回滚 —— 不会出现"入了 2 行，第 3 行失败，
     * 但前 2 行已经进库"的半成品状态。
     */
    @Transactional
    public PurchaseOrder receive(long id, ReceiveCommand cmd) {
        PurchaseOrder o = require(id);
        if (cmd.requestId() == null || cmd.requestId().isBlank()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "入库确认必须提供幂等键 X-Request-Id");
        }

        // 同键重放：不重复入库、不重复记质检、不重复生成应付。
        Optional<PurchaseReceipt> replay = receipts.findByRequestId(cmd.requestId());
        if (replay.isPresent()) {
            if (replay.get().purchaseOrderId() != o.id()) {
                throw new BusinessException(ErrorCode.SYS_DUPLICATE_REQUEST,
                        "重复的入库请求：该幂等键已用于另一张进货单");
            }
            return require(id);
        }

        if (!o.status().isExecutable()) {
            throw new BusinessException(ErrorCode.PUR_STATUS_ILLEGAL,
                    "进货单 %s 当前状态 %s 不可入库（仅 APPROVED / PARTIAL 可入库）"
                            .formatted(o.orderNo(), o.status()));
        }
        periodGuard.requireOpen(o.bizDate());
        if (cmd.lines() == null || cmd.lines().isEmpty()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR, "入库明细不能为空");
        }

        List<PreparedReceive> prepared = new ArrayList<>();
        Set<Long> seenLines = new HashSet<>();
        int totalQty = 0;
        int acceptedQty = 0;
        long acceptedAmount = 0;
        for (ReceiveLineCommand rc : cmd.lines()) {
            PurchaseOrderLine line = o.lines().stream()
                    .filter(l -> l.id() == rc.lineId())
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.SYS_NOT_FOUND,
                            "进货单 %s 中不存在明细行 lineId=%d"
                                    .formatted(o.orderNo(), rc.lineId())));
            if (!seenLines.add(line.id())) {
                throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                        "同一明细行在一次入库确认中只能出现一次：lineId=%d"
                                .formatted(line.id()));
            }
            if (rc.qty() <= 0) {
                throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                        "入库数量必须为正：lineId=%d, qty=%d".formatted(rc.lineId(), rc.qty()));
            }
            if (line.receivedQty() + rc.qty() > line.qty()) {
                throw new BusinessException(ErrorCode.PUR_OVER_RECEIVE,
                        "超量入库：进货单 %s 第 %d 行单据数量 %d，已入库 %d，本次 %d，超出 %d"
                                .formatted(o.orderNo(), line.lineNo(), line.qty(),
                                        line.receivedQty(), rc.qty(),
                                        line.receivedQty() + rc.qty() - line.qty()),
                        java.util.Map.of("orderNo", o.orderNo(), "lineNo", line.lineNo(),
                                "ordered", line.qty(), "received", line.receivedQty(),
                                "requested", rc.qty()));
            }
            int qcQty = rc.qcQty() == null ? rc.qty() : rc.qcQty();
            if (qcQty < 0 || qcQty > rc.qty()) {
                throw new BusinessException(ErrorCode.PUR_QC_QTY_INVALID,
                        "合格数量越界：lineId=%d 本次到货 %d，合格 %d（必须在 0..到货量之间）"
                                .formatted(line.id(), rc.qty(), qcQty),
                        java.util.Map.of("lineId", line.id(), "receivedQty", rc.qty(),
                                "qcQty", qcQty));
            }
            long lineAccepted = Math.multiplyExact((long) qcQty, line.unitPriceAmount());
            prepared.add(new PreparedReceive(line, rc.qty(), qcQty, trimToNull(rc.qcNote()),
                    rc.batchNo() == null ? "" : rc.batchNo(), rc.productionDate(),
                    rc.expiryDate(), lineAccepted, QcStatus.of(rc.qty(), qcQty)));
            totalQty += rc.qty();
            acceptedQty += qcQty;
            acceptedAmount = Math.addExact(acceptedAmount, lineAccepted);
        }

        String receiptNo = numberGenerator.next(DocumentNumberGenerator.DocType.PURCHASE_RECEIPT,
                o.bizDate());
        long receiptId = receipts.insertHeader(receiptNo, o.id(), o.supplierId(), o.warehouseId(),
                o.bizDate(), periodOf(o.bizDate()), totalQty, acceptedQty, totalQty - acceptedQty,
                acceptedAmount, cmd.requestId(), clock.now());

        int lineNo = 1;
        for (PreparedReceive p : prepared) {
            // ① 累计到货量（原子守卫，防并发超量）
            if (!orders.applyReceivedQty(p.line().id(), p.qty())) {
                throw new BusinessException(ErrorCode.PUR_OVER_RECEIVE,
                        "超量入库（并发冲突）：进货单 %s 第 %d 行"
                                .formatted(o.orderNo(), p.line().lineNo()));
            }
            // ② 累计质检量（状态按累加后的数量推导）
            if (!orders.applyQc(p.line().id(), p.qcQty(), p.qcNote())) {
                throw new BusinessException(ErrorCode.PUR_QC_QTY_INVALID,
                        "质检数量写入冲突：进货单 %s 第 %d 行"
                                .formatted(o.orderNo(), p.line().lineNo()));
            }
            // ③ 只有合格量进库存 —— 不合格量没有流水、没有余额行
            if (p.qcQty() > 0) {
                inventory.receiveIn(p.line().skuId(), o.warehouseId(), p.batchNo(),
                        p.productionDate(), p.expiryDate(), p.qcQty(),
                        "PURCHASE_IN", o.orderNo(), o.id(), p.line().unitPriceAmount(),
                        receiptRequestId(cmd.requestId(), p.line().lineNo()));
            }
            receipts.insertLine(receiptId, lineNo++, p.line().id(), p.line().skuId(),
                    p.batchNo(), p.productionDate(), p.expiryDate(), p.qty(), p.qcQty(),
                    p.qty() - p.qcQty(), p.line().unitPriceAmount(), p.acceptedAmount(),
                    p.qcStatus(), p.qcNote());
        }

        // ④ 应付只按合格金额生成；全部不合格则不生成应付单
        if (acceptedAmount > 0) {
            payables.createForReceipt(receiptId, o.supplierId(), o.id(), o.bizDate(),
                    periodOf(o.bizDate()), acceptedAmount);
        }

        // ⑤ 状态推进以"到货量"为准：不合格数量同样关闭未到货量，避免永远无法完成
        PurchaseOrder refreshed = require(id);
        DocumentStatus target = refreshed.fullyReceived()
                ? DocumentStatus.COMPLETED
                : DocumentStatus.PARTIAL;
        DocumentStatusMachine.requireTransition(refreshed.status(), target,
                ErrorCode.PUR_STATUS_ILLEGAL, refreshed.orderNo());
        transition(refreshed, target);
        return require(id);
    }

    /** 到货/质检记录（FR-PUR-05）：按到货单顺序返回。 */
    @Transactional(readOnly = true)
    public List<PurchaseReceipt> receipts(long orderId) {
        require(orderId);
        return receipts.findByOrderId(orderId);
    }

    // ─────────────────────────── 查询 ───────────────────────────

    @Transactional(readOnly = true)
    public PurchaseOrder require(long id) {
        return orders.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PUR_ORDER_NOT_FOUND,
                        "进货单不存在：id=%d".formatted(id)));
    }

    @Transactional(readOnly = true)
    public PageResult<PurchaseOrder> page(DocumentStatus status, Long supplierId,
                                          int page, int size) {
        return orders.page(status, supplierId, page, size);
    }

    // ─────────────────────────── 内部辅助 ───────────────────────────

    private void transition(PurchaseOrder o, DocumentStatus target) {
        if (!orders.updateStatus(o.id(), o.status(), target, o.version())) {
            throw new BusinessException(ErrorCode.PUR_STATUS_ILLEGAL,
                    "进货单 %s 状态推进冲突（并发修改），请刷新后重试：期望 %s -> %s"
                            .formatted(o.orderNo(), o.status(), target));
        }
    }

    private void requireSupplierEnabled(long supplierId) {
        var s = suppliers.findById(supplierId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SYS_NOT_FOUND,
                        "供应商不存在：id=%d".formatted(supplierId)));
        if (!s.enabled()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "供应商已停用，不能建单：%s".formatted(s.code()));
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

    private void requireSkuEnabled(long skuId) {
        var s = skus.findById(skuId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MDM_SKU_NOT_FOUND,
                        "SKU 不存在：id=%d".formatted(skuId)));
        if (!s.enabled()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "SKU 已停用，不能建单：id=%d".formatted(skuId));
        }
    }

    /**
     * 入库流水的 requestId 必须<b>按行</b>唯一。
     *
     * <p>为什么：{@code inventory_ledger} 的唯一索引是
     * {@code (request_id, biz_type, batch_no)}。一张进货单多行若共用同一个 requestId
     * 且批次都为空，第二条流水就会被误判为重复提交。
     * 这与 M0 捕获的"合法拆批次被误判重复"是同一类缺陷，这里在源头规避。
     */
    private static String receiptRequestId(String baseRequestId, int lineNo) {
        String base = baseRequestId == null || baseRequestId.isBlank() ? "po-recv" : baseRequestId;
        return base + ":L" + lineNo;
    }

    private static String periodOf(LocalDate bizDate) {
        return "%04d-%02d".formatted(bizDate.getYear(), bizDate.getMonthValue());
    }

    private static String trimToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
