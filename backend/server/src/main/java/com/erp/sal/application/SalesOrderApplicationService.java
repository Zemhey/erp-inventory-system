package com.erp.sal.application;

import com.erp.inv.application.InventoryApplicationService;
import com.erp.inv.application.InventoryApplicationService.ReserveResult;
import com.erp.mdm.repository.CustomerRepository;
import com.erp.mdm.repository.SkuRepository;
import com.erp.mdm.repository.WarehouseRepository;
import com.erp.sal.domain.SalesOrder;
import com.erp.sal.domain.SalesOrderLine;
import com.erp.sal.repository.SalesOrderRepository;
import com.erp.shared.document.DocumentNumberGenerator;
import com.erp.shared.document.DocumentStatus;
import com.erp.shared.document.DocumentStatusMachine;
import com.erp.shared.db.DbErrors;
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
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 销售出货应用服务（FR-SAL-01 建单即预占、FR-SAL-02 出货确认）。
 *
 * <p><b>为什么出货比进货难</b>：进货是"凭空增加"，出货是"在别人的份额里扣"。
 * 建单必须预占（否则会超卖），预占必须有失败路径（库存不足），
 * 而失败时必须保证<b>整单回滚</b> —— 否则会出现"单子没建成，但库存被神秘锁住"。
 *
 * <p><b>跨模块规则</b>（计划书 §3.3）：
 * <pre>
 * SalesOrderApplicationService ──调用──▶ InventoryApplicationService.reserve(...)
 *                              ──调用──▶ InventoryApplicationService.shipOut(...)
 * </pre>
 * 本类<b>不碰</b> {@code InventoryBalanceRepository}，由 ArchUnit 强制。
 */
@Service
public class SalesOrderApplicationService {

    private final SalesOrderRepository orders;
    private final CustomerRepository customers;
    private final WarehouseRepository warehouses;
    private final SkuRepository skus;
    private final InventoryApplicationService inventory;
    private final DocumentNumberGenerator numberGenerator;
    private final ClockProvider clock;
    /**
     * 账期锁（M2-S2）。只依赖 {@code shared.period} 接口，实现由 SET 提供；
     * 出货的"建单即预占"也算写操作，因此建单路径同样要过锁。
     */
    private final PeriodGuard periodGuard;
    /** 审批闸门（V2-6 FR-IAM-07）：只输出可否，不改单据状态。 */
    private final ApprovalGate approvalGate;
    /** 当前操作人（数据权限"按本人"）。 */
    private final ActorContext actors;

    public SalesOrderApplicationService(SalesOrderRepository orders,
                                        CustomerRepository customers,
                                        WarehouseRepository warehouses,
                                        SkuRepository skus,
                                        InventoryApplicationService inventory,
                                        DocumentNumberGenerator numberGenerator,
                                        ClockProvider clock,
                                        PeriodGuard periodGuard,
                                           ApprovalGate approvalGate,
                                           ActorContext actors ) {
        this.orders = orders;
        this.customers = customers;
        this.warehouses = warehouses;
        this.skus = skus;
        this.inventory = inventory;
        this.numberGenerator = numberGenerator;
        this.clock = clock;
        this.periodGuard = periodGuard;
        this.approvalGate = approvalGate;
        this.actors = actors;
    }

    /** 折扣率基准：10000 = 不打折（整数万分比，ADR-0005）。 */
    public static final int NO_DISCOUNT = 10000;

    public record CreateLineCommand(long skuId, int qty, long unitPriceAmount, Integer discountRate) {
    }

    public record CreateCommand(long customerId, long warehouseId, LocalDate bizDate,
                                String remark, List<CreateLineCommand> lines, String requestId) {
    }

    // ─────────────────────────── 建单（同时预占） ───────────────────────────

    /**
     * 创建出货单，并逐行预占库存（FR-SAL-01）。
     *
     * <p><b>整单原子性</b>：本方法在单个事务内完成"建单 + 全部行预占"。
     * 任意一行预占失败（库存不足），异常抛出让事务回滚 ——
     * 之前成功的行的预占、以及单据头与明细，全部一起消失。
     *
     * <p>这条性质由 TC-SAL-003 专门验证（3 行明细，第 3 行不足，前 2 行的预占必须归零）。
     */
    @Transactional
    public SalesOrder create(CreateCommand cmd) {
        Optional<SalesOrder> existing = orders.findByRequestId(cmd.requestId());
        if (existing.isPresent()) {
            return orders.findById(existing.get().id()).orElseThrow();
        }

        requireCustomerEnabled(cmd.customerId());
        requireWarehouseEnabled(cmd.warehouseId());
        if (cmd.lines() == null || cmd.lines().isEmpty()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR, "出货单至少需要一行明细");
        }
        LocalDate bizDate = cmd.bizDate() == null ? clock.today() : cmd.bizDate();
        // 账期锁：置于幂等短路之后，重复提交不产生改动，锁账后也应原样返回首次结果。
        periodGuard.requireOpen(bizDate);

        // ① 先算金额（纯计算，不碰数据库）
        long totalAmount = 0;
        int totalQty = 0;
        List<long[]> computed = new ArrayList<>();   // skuId, qty, unitPrice, discountRate, amount
        for (CreateLineCommand line : cmd.lines()) {
            if (line.qty() <= 0) {
                throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                        "明细数量必须为正：skuId=%d, qty=%d".formatted(line.skuId(), line.qty()));
            }
            if (line.unitPriceAmount() < 0) {
                throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                        "单价不能为负：skuId=%d".formatted(line.skuId()));
            }
            int rate = line.discountRate() == null ? NO_DISCOUNT : line.discountRate();
            if (rate <= 0 || rate > NO_DISCOUNT) {
                throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                        "折扣率非法：%d，必须是 1~10000 的整数万分比".formatted(rate));
            }
            requireSkuEnabled(line.skuId());
            long amount = lineAmount(line.qty(), line.unitPriceAmount(), rate);
            totalAmount = Math.addExact(totalAmount, amount);
            totalQty += line.qty();
            computed.add(new long[]{line.skuId(), line.qty(), line.unitPriceAmount(), rate, amount});
        }

        // ② 建单据头与明细
        String orderNo = numberGenerator.next(DocumentNumberGenerator.DocType.SALES_ORDER, bizDate);
        long orderId = orders.insertHeader(orderNo, cmd.customerId(), cmd.warehouseId(), bizDate,
                totalAmount, totalQty, periodOf(bizDate), trimToNull(cmd.remark()), cmd.requestId(),
                actors.current().map(com.erp.shared.security.ActorContext.Actor::id).orElse(null));
        int lineNo = 1;
        for (long[] c : computed) {
            orders.insertLine(orderId, lineNo++, c[0], (int) c[1], c[2], (int) c[3], c[4]);
        }

        // ③ 逐行预占（任一行失败 → 整个事务回滚）
        //
        //    使用 reserveFefo 而不是 reserve：出货单在批次维度上是"无知"的
        //    （批次由 FEFO 在出货时决定），因此预占必须由 INV 按效期自动分段。
        //    一行可能拆成多段预占（例如 12 件 = B1 的 10 + B2 的 2），
        //    每一段都要登记，出货/作废时逐段消费或释放。
        List<SalesOrderLine> lines = orders.findLines(orderId);
        for (SalesOrderLine line : lines) {
            List<ReserveResult> segments = inventory.reserveFefo(
                    line.skuId(), cmd.warehouseId(), line.qty(),
                    reservationRequestId(cmd.requestId(), line.lineNo()));
            int seq = 1;
            for (ReserveResult r : segments) {
                orders.attachReservation(line.id(), r.reservationId(), seq++, r.reservedQty());
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
     * 作废出货单（FR-SAL-04，M2.5 补全红冲闭环）。
     *
     * <p><b>未出货</b>：释放预占，状态 → VOIDED，不动实存、不写流水。
     *
     * <p><b>已出货（PARTIAL/COMPLETED）</b>：红冲 —— 按原 `SALES_OUT` 流水逐批次回补实存，
     * 写 `SALES_REVERSAL` 正向流水，状态 → VOIDED。规则：
     * <ul>
     *   <li>有退货数量（`returnedQty > 0`）拒绝：退货已经回补过库存，再全额红冲会双补；</li>
     *   <li>账期已锁拒绝（`SET_PERIOD_CLOSED`）；</li>
     *   <li>`Σ shippedQty` 必须等于原 `SALES_OUT` 流水净额，否则拒绝（防流水漂移下红冲）；</li>
     *   <li>同 `X-Request-Id` 重放幂等返回；新键重复作废报 `SAL_STATUS_ILLEGAL`。</li>
     * </ul>
     *
     * <p><b>并发</b>：先做「状态 + version」条件 UPDATE，再做库存反向流水。
     * 状态 UPDATE 是串行化点：并发异键只有一个能成功，另一个在库存动之前就失败；
     * 同键后到者重读后走幂等返回。
     */
    @Transactional
    public SalesOrder voidOrder(long id, String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "作废出货单必须提供幂等键 X-Request-Id");
        }
        SalesOrder o = require(id);

        // ① 已作废：同键幂等返回；异键报状态非法。放在账期检查之前，
        //    这样"首次成功后账期才被锁"的重放仍然返回首次结果。
        if (o.status() == DocumentStatus.VOIDED) {
            if (orders.isVoidedByRequest(o.id(), requestId)) {
                return o;
            }
            throw new BusinessException(ErrorCode.SAL_STATUS_ILLEGAL,
                    "出货单 %s 已作废（首次作废请求与本次不同），不能再次作废"
                            .formatted(o.orderNo()));
        }

        // ② 账期锁
        periodGuard.requireOpen(o.bizDate());

        if (o.anyShipped()) {
            return reverseShippedOrder(o, requestId);
        }

        // ③ 未出货：原语义（释放预占），状态更新带幂等键
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

    /** 已出货单据的红冲：校验 → 状态串行化 → 库存逐批次回补 → 释放未出货预占。 */
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
                    java.util.Map.of("orderNo", o.orderNo(), "shippedQty", shipped,
                            "ledgerQty", ledgerOut));
        }

        if (!markVoided(o, requestId, true)) {
            return require(o.id());   // 并发同键：另一个请求已完成红冲，本请求幂等收口
        }

        int reversed = inventory.reverseSalesOut(o.orderNo(), o.id(), requestId);
        if (reversed != shipped) {
            throw new BusinessException(ErrorCode.SAL_STATUS_ILLEGAL,
                    "出货单 %s 红冲数量 %d 与累计出货量 %d 不一致，已回滚"
                            .formatted(o.orderNo(), reversed, shipped));
        }
        // PARTIAL 单据里未出货行的预占仍是 ACTIVE，必须释放；COMPLETED 行均为 CONSUMED，自动跳过。
        for (SalesOrderLine line : o.lines()) {
            for (long reservationId : line.reservationIds()) {
                inventory.releaseIfActive(reservationId);
            }
        }
        return require(o.id());
    }

    /**
     * 状态条件更新 + 幂等键；失败时按「同键重放 / 已退货 / 并发冲突」分流。
     *
     * <p>红冲多一个 `NOT EXISTS returned_qty > 0` 条件：与并发退货入库靠
     * `sales_orders` 行锁串行化，退货先提交则这里 0 行。
     *
     * @return true = 本次请求完成了状态翻转；false = 并发同键的另一请求已完成
     */
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
            return false;   // 并发同键：另一个请求已完成，本请求幂等收口
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
     * <p>调用 {@link InventoryApplicationService#shipOut}，由 INV 模块按 FEFO
     * 拆批次扣减，单笔可产生多条流水。本模块不关心批次怎么拆。
     *
     * <p>出货完成后：
     * <ul>
     *   <li>全部出完 → COMPLETED；否则 → PARTIAL；</li>
     *   <li>对应预占记录被消费（实存与预占同时减少）。</li>
     * </ul>
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

        for (SalesOrderLine line : o.lines()) {
            int openQty = line.openQty();
            if (openQty <= 0) {
                continue;
            }
            if (!orders.applyShippedQty(line.id(), openQty)) {
                throw new BusinessException(ErrorCode.SAL_STATUS_ILLEGAL,
                        "出货数量超出单据数量（并发冲突）：出货单 %s 第 %d 行"
                                .formatted(o.orderNo(), line.lineNo()));
            }
            inventory.shipOut(line.skuId(), o.warehouseId(), openQty,
                    "SALES_OUT", o.orderNo(), o.id(),
                    shipRequestId(requestId, line.lineNo()));
            // ★ 预占转出库后必须把预占记录标记为已消费。
            //   shipOut 只改了余额行的数量，若不同步更新状态，预占记录会永远停在 ACTIVE，
            //   导致在途量统计虚高、且无法区分"已发货"与"仍占用"。
            for (long reservationId : line.reservationIds()) {
                inventory.consumeReservation(reservationId);
            }
        }

        SalesOrder refreshed = require(id);
        DocumentStatus target = refreshed.fullyShipped()
                ? DocumentStatus.COMPLETED
                : DocumentStatus.PARTIAL;
        DocumentStatusMachine.requireTransition(refreshed.status(), target,
                ErrorCode.SAL_STATUS_ILLEGAL, refreshed.orderNo());
        transition(refreshed, target);
        return require(id);
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

    // ─────────────────────────── 金额 ───────────────────────────

    /**
     * 行金额 = round(qty × unitPrice × discountRate / 10000)，四舍五入到分。
     *
     * <p><b>为什么用 BigDecimal 而不是 long 直接乘</b>：
     * 先乘后除会丢掉中间精度（{@code 3 × 333 × 9000 / 10000} 若用整数除会截断）。
     * 而用 {@code double} 会出现 {@code 116.09999999999999} 这类二进制小数尾巴
     * （ADR-0005 禁止浮点金额的原因）。
     * BigDecimal 全程十进制，再用 {@link RoundingMode#HALF_UP} 落到分，结果可复现。
     */
    public static long lineAmount(int qty, long unitPriceAmount, int discountRate) {
        BigDecimal raw = BigDecimal.valueOf(unitPriceAmount)
                .multiply(BigDecimal.valueOf(qty))
                .multiply(BigDecimal.valueOf(discountRate))
                .divide(BigDecimal.valueOf(NO_DISCOUNT), 0, RoundingMode.HALF_UP);
        return raw.longValueExact();
    }

    // ─────────────────────────── 内部辅助 ───────────────────────────

    private void transition(SalesOrder o, DocumentStatus target) {
        if (!orders.updateStatus(o.id(), o.status(), target, o.version())) {
            throw new BusinessException(ErrorCode.SAL_STATUS_ILLEGAL,
                    "出货单 %s 状态推进冲突（并发修改），请刷新后重试：期望 %s -> %s"
                            .formatted(o.orderNo(), o.status(), target));
        }
    }

    private void requireCustomerEnabled(long customerId) {
        var c = customers.findById(customerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SYS_NOT_FOUND,
                        "客户不存在：id=%d".formatted(customerId)));
        if (!c.enabled()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "客户已停用，不能建单：%s".formatted(c.code()));
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
     * 预占的 requestId 必须<b>按行</b>唯一。
     *
     * <p>{@code inventory_reservations} 的唯一约束是 {@code request_id}。
     * 一张出货单多行若共用一个 requestId，第二行预占就会命中第一行的幂等记录，
     * 表现为"只预占了第一行，其余行静默没预占" —— 这是最危险的一类缺陷：
     * 没有报错，但库存没锁住，之后出货时才失败。
     */
    private static String reservationRequestId(String baseRequestId, int lineNo) {
        String base = baseRequestId == null || baseRequestId.isBlank() ? "so-reserve" : baseRequestId;
        return base + ":R" + lineNo;
    }

    /** 出货流水的 requestId 同理按行唯一（见 {{@code uq_ledger_request}}）。 */
    private static String shipRequestId(String baseRequestId, int lineNo) {
        String base = baseRequestId == null || baseRequestId.isBlank() ? "so-ship" : baseRequestId;
        return base + ":S" + lineNo;
    }

    private static String periodOf(LocalDate bizDate) {
        return "%04d-%02d".formatted(bizDate.getYear(), bizDate.getMonthValue());
    }

    private static String trimToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
