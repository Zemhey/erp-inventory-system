package com.erp.pur.application;

import com.erp.inv.application.InventoryApplicationService;
import com.erp.mdm.repository.SkuRepository;
import com.erp.mdm.repository.SupplierRepository;
import com.erp.mdm.repository.WarehouseRepository;
import com.erp.pur.domain.PurchaseOrder;
import com.erp.pur.domain.PurchaseOrderLine;
import com.erp.pur.repository.PurchaseOrderRepository;
import com.erp.shared.document.DocumentNumberGenerator;
import com.erp.shared.document.DocumentStatus;
import com.erp.shared.document.DocumentStatusMachine;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.time.ClockProvider;
import com.erp.shared.web.PageResult;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
    private final SupplierRepository suppliers;
    private final WarehouseRepository warehouses;
    private final SkuRepository skus;
    private final InventoryApplicationService inventory;
    private final DocumentNumberGenerator numberGenerator;
    private final ClockProvider clock;

    public PurchaseOrderApplicationService(PurchaseOrderRepository orders,
                                           SupplierRepository suppliers,
                                           WarehouseRepository warehouses,
                                           SkuRepository skus,
                                           InventoryApplicationService inventory,
                                           DocumentNumberGenerator numberGenerator,
                                           ClockProvider clock) {
        this.orders = orders;
        this.suppliers = suppliers;
        this.warehouses = warehouses;
        this.skus = skus;
        this.inventory = inventory;
        this.numberGenerator = numberGenerator;
        this.clock = clock;
    }

    // ─────────────────────────── 命令 / 结果 ───────────────────────────

    public record CreateLineCommand(long skuId, int qty, long unitPriceAmount) {
    }

    public record CreateCommand(long supplierId, long warehouseId, LocalDate bizDate,
                                String remark, List<CreateLineCommand> lines, String requestId) {
    }

    /** 入库确认的一行：指定明细行与本次实收数量（可选指定批次与效期）。 */
    public record ReceiveLineCommand(long lineId, int qty, String batchNo,
                                     LocalDate productionDate, LocalDate expiryDate) {
    }

    public record ReceiveCommand(List<ReceiveLineCommand> lines, String requestId) {
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
        DocumentStatusMachine.requireTransition(o.status(), DocumentStatus.SUBMITTED,
                ErrorCode.PUR_STATUS_ILLEGAL, o.orderNo());
        transition(o, DocumentStatus.SUBMITTED);
        return require(id);
    }

    @Transactional
    public PurchaseOrder approve(long id) {
        PurchaseOrder o = require(id);
        DocumentStatusMachine.requireTransition(o.status(), DocumentStatus.APPROVED,
                ErrorCode.PUR_STATUS_ILLEGAL, o.orderNo());
        transition(o, DocumentStatus.APPROVED);
        return require(id);
    }

    @Transactional
    public PurchaseOrder reject(long id) {
        PurchaseOrder o = require(id);
        DocumentStatusMachine.requireTransition(o.status(), DocumentStatus.REJECTED,
                ErrorCode.PUR_STATUS_ILLEGAL, o.orderNo());
        transition(o, DocumentStatus.REJECTED);
        return require(id);
    }

    @Transactional
    public PurchaseOrder voidOrder(long id) {
        PurchaseOrder o = require(id);
        DocumentStatusMachine.requireTransition(o.status(), DocumentStatus.VOIDED,
                ErrorCode.PUR_STATUS_ILLEGAL, o.orderNo());
        if (o.anyReceived()) {
            // 已入库就作废必须先生成反向流水冲减库存（BR-DOC-03），属 P1（FR-PUR-03）
            throw new BusinessException(ErrorCode.PUR_STATUS_ILLEGAL,
                    "进货单 %s 已有入库，作废需先红冲（FR-PUR-03，M2 实现）".formatted(o.orderNo()));
        }
        transition(o, DocumentStatus.VOIDED);
        return require(id);
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
        if (!o.status().isExecutable()) {
            throw new BusinessException(ErrorCode.PUR_STATUS_ILLEGAL,
                    "进货单 %s 当前状态 %s 不可入库（仅 APPROVED / PARTIAL 可入库）"
                            .formatted(o.orderNo(), o.status()));
        }
        if (cmd.lines() == null || cmd.lines().isEmpty()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR, "入库明细不能为空");
        }

        int receivedThisTime = 0;
        for (ReceiveLineCommand rc : cmd.lines()) {
            PurchaseOrderLine line = o.lines().stream()
                    .filter(l -> l.id() == rc.lineId())
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.SYS_NOT_FOUND,
                            "进货单 %s 中不存在明细行 lineId=%d"
                                    .formatted(o.orderNo(), rc.lineId())));

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

            // ① 累计入库量（原子守卫，防并发超量）
            if (!orders.applyReceivedQty(line.id(), rc.qty())) {
                throw new BusinessException(ErrorCode.PUR_OVER_RECEIVE,
                        "超量入库（并发冲突）：进货单 %s 第 %d 行"
                                .formatted(o.orderNo(), line.lineNo()));
            }

            // ② ★ 唯一库存入口
            String batchNo = rc.batchNo() == null ? "" : rc.batchNo();
            inventory.receiveIn(line.skuId(), o.warehouseId(), batchNo,
                    rc.productionDate(), rc.expiryDate(), rc.qty(),
                    "PURCHASE_IN", o.orderNo(), o.id(),
                    line.unitPriceAmount(), receiptRequestId(cmd.requestId(), line.lineNo()));

            receivedThisTime += rc.qty();
        }
        if (receivedThisTime == 0) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR, "本次入库数量合计为 0");
        }

        // ③ 状态推进
        PurchaseOrder refreshed = require(id);
        DocumentStatus target = refreshed.fullyReceived()
                ? DocumentStatus.COMPLETED
                : DocumentStatus.PARTIAL;
        DocumentStatusMachine.requireTransition(refreshed.status(), target,
                ErrorCode.PUR_STATUS_ILLEGAL, refreshed.orderNo());
        transition(refreshed, target);
        return require(id);
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
