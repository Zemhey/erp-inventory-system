package com.erp.inv.application;

import com.erp.inv.domain.InventoryBalance;
import com.erp.inv.domain.InventoryLedgerEntry;
import com.erp.inv.repository.InventoryBalanceRepository;
import com.erp.inv.repository.InventoryLedgerRepository;
import com.erp.inv.repository.InventoryReservationRepository;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.time.ClockProvider;
import com.erp.shared.web.PageResult;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 库存应用服务 —— <b>库存变动的唯一入口</b>（docs/02-领域模型 §2）。
 *
 * <p>所有会改变库存的操作（进货入库、销售出库、退货回补、调拨、盘点）
 * 都必须调用本服务，禁止任何模块直接写库存表。
 * 这条规则让"库存一致性"只有一个实现，也只有一个测试靶点。
 */
@Service
public class InventoryApplicationService {

    private final InventoryBalanceRepository balances;
    private final InventoryLedgerRepository ledger;
    private final InventoryReservationRepository reservations;
    private final ClockProvider clock;

    /** 预占超时时长（分钟），BR-INV-05。配置见 application.yml。 */
    private final long reservationTtlMinutes;

    public InventoryApplicationService(InventoryBalanceRepository balances,
                                       InventoryLedgerRepository ledger,
                                       InventoryReservationRepository reservations,
                                       ClockProvider clock,
                                       @Value("${erp.inventory.reservation-ttl-minutes:30}")
                                       long reservationTtlMinutes) {
        this.balances = balances;
        this.ledger = ledger;
        this.reservations = reservations;
        this.clock = clock;
        this.reservationTtlMinutes = reservationTtlMinutes;
    }

    // ───────────────────────────── 查询 ─────────────────────────────

    @Transactional(readOnly = true)
    public List<InventoryBalance> query(Long skuId, Long warehouseId, String batchNo, boolean onlyAvailable) {
        return balances.query(skuId, warehouseId, batchNo, onlyAvailable);
    }

    /** 库存流水查询（FR-INV-02）：可按来源单据号反查某笔出入库。 */
    @Transactional(readOnly = true)
    public PageResult<InventoryLedgerEntry> queryLedger(Long skuId, Long warehouseId, String bizRefNo,
                                                        OffsetDateTime from, OffsetDateTime to,
                                                        int page, int size) {
        return ledger.page(skuId, warehouseId, bizRefNo, from, to, page, size);
    }

    @Transactional(readOnly = true)
    public InventoryBalance requireBalance(long skuId, long warehouseId, String batchNo) {
        return balances.find(skuId, warehouseId, batchNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.SYS_NOT_FOUND,
                        "库存余额不存在：skuId=%d, warehouseId=%d, batchNo='%s'"
                                .formatted(skuId, warehouseId, batchNo)));
    }

    // ───────────────────────────── 预占 ─────────────────────────────

    public record ReserveCommand(long skuId, long warehouseId, String batchNo, int qty, String requestId) {
    }

    public record ReserveResult(long reservationId, long skuId, long warehouseId, String batchNo,
                                int reservedQty, int onHandQty, int availableQty, boolean duplicate) {
    }

    /**
     * 库存预占（下单锁定可用量）。
     *
     * <p><b>幂等</b>：先查 requestId 是否已有预占记录 —— 命中则直接返回首次结果，
     * {@code duplicate=true}，不产生任何新的库存变动（不变量 INV-9）。
     *
     * <p><b>防超卖</b>：真正的守卫在 {@link InventoryBalanceRepository#tryReserve} 里，
     * 是带条件的原子 UPDATE。本方法不做"先查后写"的判断。
     *
     * <p>失败时抛出 {@code INV_INSUFFICIENT_STOCK}，消息含定位信息
     * （哪个 SKU、哪个仓、差多少），便于测试断言与排障。
     */
    @Transactional
    public ReserveResult reserve(ReserveCommand cmd) {
        String batchNo = cmd.batchNo() == null ? "" : cmd.batchNo();

        // ① 幂等检查：同一 requestId 已预占过 → 返回首次结果，不做任何变动
        var existing = reservations.findByRequestId(cmd.requestId());
        if (existing.isPresent()) {
            var r = existing.get();
            InventoryBalance b = requireBalance(r.skuId(), r.warehouseId(), r.batchNo());
            return new ReserveResult(r.id(), r.skuId(), r.warehouseId(), r.batchNo(),
                    r.reservedQty(), b.onHandQty(), b.availableQty(), true);
        }

        // ② 余额行必须存在（可用量为 0 也算存在）
        //
        //    注意：这里刻意保留"行必须存在"的前置检查，因为 M0 的
        //    TC-INV-002 / TC-INV-006 依赖"库存不足 → INV_INSUFFICIENT_STOCK"
        //    这一稳定语义。但错误消息必须能区分两种截然不同的情况，
        //    否则排障时会误以为是"货不够"：
        //      · 某批次行存在但可用量不足   → 真·库存不足
        //      · 要预占的批次行压根没建      → 批次维度或建账时机问题
        InventoryBalance before = balances.find(cmd.skuId(), cmd.warehouseId(), batchNo)
                .orElseThrow(() -> {
                    int onHandAnyBatch = balances
                            .query(cmd.skuId(), cmd.warehouseId(), null, false)
                            .stream().mapToInt(InventoryBalance::onHandQty).sum();
                    String hint = onHandAnyBatch > 0
                            ? "该 SKU 在此仓库有货共 %d 件，但没有批次 '%s' 的余额行"
                                    .formatted(onHandAnyBatch, batchNo)
                            : "该 SKU 在此仓库无任何库存";
                    return new BusinessException(ErrorCode.SYS_NOT_FOUND,
                            "库存余额不存在：skuId=%d, warehouseId=%d, batchNo='%s'（%s）"
                                    .formatted(cmd.skuId(), cmd.warehouseId(), batchNo, hint));
                });

        // ③ 原子预占：条件不满足则更新 0 行 → 可用量不足
        boolean ok = balances.tryReserve(cmd.skuId(), cmd.warehouseId(), batchNo, cmd.qty());
        if (!ok) {
            // 重新读取当前值，让错误消息带上真实可用量（定位信息）
            int available = balances.find(cmd.skuId(), cmd.warehouseId(), batchNo)
                    .map(InventoryBalance::availableQty).orElse(0);
            throw new BusinessException(ErrorCode.INV_INSUFFICIENT_STOCK,
                    "库存不足：SKU %d 在仓库 %d 可用量 %d，需求 %d"
                            .formatted(cmd.skuId(), cmd.warehouseId(), available, cmd.qty()),
                    java.util.Map.of("skuId", cmd.skuId(), "warehouseId", cmd.warehouseId(),
                            "available", available, "required", cmd.qty()));
        }

        // ④ 落预占记录（biz_ref_no 在 M0 用 requestId 占位，M1 接单据号）
        LocalDateTime expiresAt = clock.now().plusMinutes(reservationTtlMinutes);
        boolean inserted = reservations.tryInsert(cmd.skuId(), cmd.warehouseId(), batchNo,
                cmd.qty(), cmd.requestId(), cmd.requestId(), expiresAt);
        if (!inserted) {
            // 并发下另一个持有相同 requestId 的请求已先完成预占。
            // 契约要求幂等：**返回首次结果**，而不是报错（docs/05：写操作必须携带幂等键）。
            // 因此必须把本次已加的预占回滚掉（release 负负相抵），保证净变动为 0。
            balances.release(cmd.skuId(), cmd.warehouseId(), batchNo, cmd.qty());
            var first = reservations.findByRequestId(cmd.requestId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.SYS_DUPLICATE_REQUEST,
                            "重复请求且未找到首次结果：requestId=%s".formatted(cmd.requestId())));
            InventoryBalance b = requireBalance(cmd.skuId(), cmd.warehouseId(), batchNo);
            return new ReserveResult(first.id(), first.skuId(), first.warehouseId(),
                    first.batchNo(), first.reservedQty(),
                    b.onHandQty(), b.availableQty(), true);
        }

        var saved = reservations.findByRequestId(cmd.requestId()).orElseThrow();
        InventoryBalance after = requireBalance(cmd.skuId(), cmd.warehouseId(), batchNo);
        return new ReserveResult(saved.id(), cmd.skuId(), cmd.warehouseId(), batchNo,
                cmd.qty(), after.onHandQty(), after.availableQty(), false);
    }

    /**
     * 释放预占（取消订单 / 超时释放）。
     */
    @Transactional
    public ReserveResult release(long reservationId) {
        var r = reservations.findById(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INV_RESERVATION_NOT_FOUND,
                        "预占记录不存在：id=%d".formatted(reservationId)));
        if (!"ACTIVE".equals(r.status())) {
            throw new BusinessException(ErrorCode.INV_RESERVE_FAILED,
                    "预占记录状态非 ACTIVE，无法释放：id=%d, status=%s".formatted(r.id(), r.status()));
        }
        boolean released = reservations.markReleased(r.id());
        if (released) {
            balances.release(r.skuId(), r.warehouseId(), r.batchNo(), r.reservedQty());
        }
        InventoryBalance after = requireBalance(r.skuId(), r.warehouseId(), r.batchNo());
        return new ReserveResult(r.id(), r.skuId(), r.warehouseId(), r.batchNo(),
                r.reservedQty(), after.onHandQty(), after.availableQty(), false);
    }

    /**
     * 释放所有已到期的 ACTIVE 预占（BR-INV-05 预占超时自动释放）。
     *
     * <p><b>为什么必须存在</b>：预占只是"锁定可用量"，不是真实出库。
     * 客户下单不付款，锁定的量若不回收，这批货就永远卖不掉。
     *
     * <p><b>与 {@link #release} 的区别只有状态</b>：数量侧完全一致
     * （只减 {@code reserved_qty}，实存不动），语义侧要能区分"人为取消"和"超时回收"，
     * 所以下游报表看到的是 EXPIRED 而不是 RELEASED。
     *
     * <p><b>绝不写流水</b>：本方法不触碰 {@code on_hand_qty}，
     * 而不变量 INV-2（实存 == 流水净和）只在实存变动时才要求有流水。
     * 若在这里补一条流水，等于凭空造出一次库存变动，INV-2 立刻漂移
     * （TC-INV-014 守的就是这条线）。
     *
     * <p><b>幂等</b>：先做 {@code ACTIVE → EXPIRED} 的条件更新，只有真的翻转成功
     * 才扣减预占量。因此重复执行、或并发多实例同时执行，同一条预占只会被释放一次
     * （TC-INV-015）。整体放在一个事务里：要么这批全部落定，要么全部回滚，
     * 不会出现"记录已 EXPIRED 但可用量没恢复"的中间态。
     *
     * <p>时间来自注入的 {@link ClockProvider}，所以到期与未到期的分界
     * 可以由测试用可控时钟精确摆布（TC-INV-012/013）。
     *
     * @return 本次实际释放的预占条数
     */
    @Transactional
    public int expireOverdueReservations() {
        LocalDateTime now = clock.now();
        var overdue = reservations.findOverdueActive(now);
        int expired = 0;
        for (var r : overdue) {
            if (!reservations.markExpired(r.id())) {
                continue;
            }
            balances.release(r.skuId(), r.warehouseId(), r.batchNo(), r.reservedQty());
            expired++;
        }
        return expired;
    }

    /**
     * <b>按批次自动选择</b>的预占 —— 出货单建单专用（FR-SAL-01）。
     *
     * <p><b>为什么需要这个方法</b>（M1 集成测试捕获的真实的架构缺口）：
     * {@link #reserve} 是 M0 为"指定批次"的场景设计的，它要求
     * {@code (sku, warehouse, batch_no)} 的余额行存在。
     * 但<b>出货单在建单时并不知道批次</b> —— 批次是出货确认时由 FEFO 决定的
     * （这正是 FEFO 的意义：谁的效期早谁先走）。
     * 若建单就锁定某个批次，FEFO 就失去意义了。
     *
     * <p>而一旦某 SKU 有了批次（进货时指定了 batch_no），
     * 就不存在 {@code batch_no = ''} 的余额行，导致 FEFO 场景下出货单<b>根本无法建单</b>。
     * 这个缺口在 M0 不会暴露（M0 只有空批次的库存）。
     *
     * <p><b>本方法的语义</b>：
     * <ol>
     *   <li>按 FEFO 顺序（效期早的先分）把请求数量分配到各批次，
     *       每分配一段就对该批次做一次原子预占；</li>
     *   <li>所有分段预占共用调用方给出的 {@code requestIdPrefix}（附加 {@code #N} 区分），
     *       任一段失败则抛异常，由调用方的事务整体回滚（不会留下部分预占）；</li>
     *   <li>返回各段的预占结果，调用方记录全部 id，出货/作废时逐段处理。</li>
     * </ol>
     *
     * @param skuId             SKU
     * @param warehouseId       仓库
     * @param qty               需要的总数量
     * @param requestIdPrefix   幂等键前缀（调用方保证每行唯一）
     * @return 各段的预占结果（至少一段；库存不足时抛 {@code INV_INSUFFICIENT_STOCK}）
     */
    @Transactional
    public List<ReserveResult> reserveFefo(long skuId, long warehouseId, int qty,
                                           String requestIdPrefix) {
        if (qty <= 0) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "预占数量必须为正：qty=%d".formatted(qty));
        }
        // 口径与出货扣减保持一致：这里看的是实存 onHand（因为还没被本单占用），
        // 出货扣减看的是 reserved（因为届时已被本单占用）。
        List<InventoryBalance> candidates = balances.findFefoCandidates(skuId, warehouseId);
        int totalOnHand = candidates.stream().mapToInt(InventoryBalance::onHandQty).sum();
        if (totalOnHand < qty) {
            throw new BusinessException(ErrorCode.INV_INSUFFICIENT_STOCK,
                    "库存不足：SKU %d 在仓库 %d 实存 %d，需求 %d"
                            .formatted(skuId, warehouseId, totalOnHand, qty),
                    java.util.Map.of("skuId", skuId, "warehouseId", warehouseId,
                            "onHand", totalOnHand, "required", qty));
        }

        List<ReserveResult> results = new java.util.ArrayList<>();
        int remaining = qty;
        int segment = 1;
        for (InventoryBalance b : candidates) {
            if (remaining <= 0) {
                break;
            }
            int take = Math.min(remaining, b.availableQty());
            if (take <= 0) {
                continue;
            }
            ReserveResult r = reserve(new ReserveCommand(skuId, warehouseId, b.batchNo(), take,
                    requestIdPrefix + "#" + segment));
            results.add(r);
            remaining -= take;
            segment++;
        }
        if (remaining > 0) {
            // 走到这里说明期间有并发预占抢走了可用量；抛异常让整单回滚
            throw new BusinessException(ErrorCode.INV_INSUFFICIENT_STOCK,
                    "库存不足（并发争抢）：SKU %d 在仓库 %d 仍有 %d 件未能预占"
                            .formatted(skuId, warehouseId, remaining),
                    java.util.Map.of("skuId", skuId, "warehouseId", warehouseId,
                            "shortage", remaining));
        }
        return results;
    }

    // ───────────────────────────── 入库 / 出库（M1 由 PUR/SAL 调用） ─────────────────────────────

    /**
     * 入库：增加实存并写正向流水。
     *
     * <p>入库是实存变动，必须留下流水（不变量 INV-2）。
     */
    @Transactional
    public void receiveIn(long skuId, long warehouseId, String batchNo,
                          LocalDate productionDate, LocalDate expiryDate,
                          int qty, String bizType, String bizRefNo, Long bizRefId,
                          Long unitCostAmount, String requestId) {
        balances.increaseOnHand(skuId, warehouseId, batchNo, productionDate, expiryDate, qty);
        ledger.insert(skuId, warehouseId, batchNo, qty, bizType, bizRefNo, bizRefId,
                unitCostAmount, requestId, clock.now());
    }

    /**
     * 出库：预占转实际出库（实存与预占同时减少），并写负向流水。
     *
     * <p>按 FEFO 扣减：优先扣最早效期批次，单笔可拆多批次（BR-INV-06）。
     * 每个批次的扣减各产生一条流水，因此"单笔出货 → 多条流水"是正常现象。
     *
     * <p><b>可扣减量按 reserved_qty 判断，而不是 available_qty</b>：
     * 出货确认时预占已被本单锁定，可用量可能为 0；
     * 若按可用量判断会得出"无货可发"，预占的货反而发不出去。
     * （该缺陷由 TC-INV-009 集成测试在 M0 阶段真实捕获。）
     */
    @Transactional
    public void shipOut(long skuId, long warehouseId, int qty,
                        String bizType, String bizRefNo, Long bizRefId, String requestId) {
        int remaining = qty;
        List<InventoryBalance> candidates = balances.findFefoShippableCandidates(skuId, warehouseId);
        int totalReserved = candidates.stream().mapToInt(InventoryBalance::reservedQty).sum();
        if (totalReserved < qty) {
            throw new BusinessException(ErrorCode.INV_INSUFFICIENT_STOCK,
                    "库存不足：SKU %d 在仓库 %d 已预占 %d，需求 %d"
                            .formatted(skuId, warehouseId, totalReserved, qty),
                    java.util.Map.of("skuId", skuId, "warehouseId", warehouseId,
                            "reserved", totalReserved, "required", qty));
        }
        for (InventoryBalance b : candidates) {
            if (remaining <= 0) {
                break;
            }
            int take = Math.min(remaining, b.reservedQty());
            if (take <= 0) {
                continue;
            }
            // 同一批次扣减：实存与预占同时减少（预占已在本单建单时锁定）
            if (!balances.consumeReserved(skuId, warehouseId, b.batchNo(), take)) {
                throw new BusinessException(ErrorCode.INV_RESERVE_FAILED,
                        "出库扣减失败（并发冲突）：skuId=%d, batchNo='%s', qty=%d"
                                .formatted(skuId, b.batchNo(), take));
            }
            ledger.insert(skuId, warehouseId, b.batchNo(), -take, bizType, bizRefNo, bizRefId,
                    null, requestId, clock.now());
            remaining -= take;
        }
        if (remaining > 0) {
            throw new BusinessException(ErrorCode.INV_INSUFFICIENT_STOCK,
                    "出库失败：仍有 %d 件未扣减（skuId=%d）".formatted(remaining, skuId));
        }
    }

    // ───────────────────────────── 不变量校验（对账，不变量 INV-2） ─────────────────────────────

    public record InvariantViolation(long skuId, long warehouseId, String batchNo,
                                     int onHandQty, int ledgerNet) {
    }

    /**
     * 消费预占记录：出货确认成功后调用，把预占状态从 ACTIVE 置为 CONSUMED。
     *
     * <p><b>为什么必须单独有这一步</b>（M1 集成测试捕获的真实缺陷）：
     * {@link #shipOut} 已经同时扣减了余额行上的 {@code on_hand_qty} 与
     * {@code reserved_qty}，但<b>预占记录本身</b>仍是 ACTIVE。
     * 于是：
     * <ul>
     *   <li>{@code inventory_reservations} 里躺着永远不会被释放的 ACTIVE 记录；</li>
     *   <li>任何按预占记录统计的报表/对账都会高估在途量；</li>
     *   <li>无法从数据上回答"这笔预占到底是发出去了还是作废了"。</li>
     * </ul>
     *
     * <p>余额行的数量是对的，错的是<b>记录的生命周期</b> —— 这类缺陷不会让
     * 任何数量断言变红，只有"状态流转"的断言才能发现。
     *
     * @param reservationId 预占记录 id
     * @return 是否由 ACTIVE 成功转为 CONSUMED（false = 已非 ACTIVE）
     */
    @Transactional
    public boolean consumeReservation(long reservationId) {
        return reservations.markConsumed(reservationId);
    }

    /**
     * 某 SKU 在某仓库下所有仍处 ACTIVE 的预占总量。
     *
     * <p>用于对账与测试断言：出货后、或单据作废后，这个数应当与
     * 余额行的 {@code reserved_qty} 合计一致。
     */
    @Transactional(readOnly = true)
    public int sumActiveReservedQty(long skuId, long warehouseId) {
        return reservations.sumActiveReservedQty(skuId, warehouseId);
    }

    /**
     * 校验不变量 INV-2：任意 (SKU, 仓库, 批次) 的实存必须等于流水净和。
     *
     * <p>这是"流水为事实源"（ADR-0007）的直接兑现：
     * 出现漂移说明有代码绕过流水直接改了实存，必须报警。
     *
     * <p>也用于测试断言 —— 并发场景结束后直接断言"无漂移"，
     * 比逐一比对数字更强。
     */
    @Transactional(readOnly = true)
    public List<InvariantViolation> checkLedgerConsistency() {
        return balances.query(null, null, null, false).stream()
                .map(b -> new InvariantViolation(b.skuId(), b.warehouseId(), b.batchNo(),
                        b.onHandQty(), ledger.netChange(b.skuId(), b.warehouseId(), b.batchNo())))
                .filter(v -> v.onHandQty() != v.ledgerNet())
                .toList();
    }
}
