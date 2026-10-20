package com.erp.inv.application;

import com.erp.inv.domain.InventoryBalance;
import com.erp.inv.repository.InventoryBalanceRepository;
import com.erp.inv.repository.InventoryLedgerRepository;
import com.erp.inv.repository.InventoryReservationRepository;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.time.ClockProvider;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
        InventoryBalance before = balances.find(cmd.skuId(), cmd.warehouseId(), batchNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.SYS_NOT_FOUND,
                        "库存余额不存在：skuId=%d, warehouseId=%d, batchNo='%s'"
                                .formatted(cmd.skuId(), cmd.warehouseId(), batchNo)));

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
