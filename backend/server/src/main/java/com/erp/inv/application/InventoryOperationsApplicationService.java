package com.erp.inv.application;

import com.erp.inv.domain.FreezeStatus;
import com.erp.inv.domain.InventoryAdjustment;
import com.erp.inv.domain.InventoryBalance;
import com.erp.inv.domain.InventoryFreeze;
import com.erp.inv.domain.InventoryTransfer;
import com.erp.inv.domain.Stocktake;
import com.erp.inv.domain.StocktakeLine;
import com.erp.inv.domain.StocktakeStatus;
import com.erp.inv.domain.TransferLine;
import com.erp.inv.domain.TransferStatus;
import com.erp.inv.repository.InventoryAdjustmentRepository;
import com.erp.inv.repository.InventoryFreezeRepository;
import com.erp.inv.repository.InventoryTransferRepository;
import com.erp.inv.repository.StocktakeRepository;
import com.erp.shared.document.DocumentNumberGenerator;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.time.ClockProvider;
import com.erp.shared.web.PageResult;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 库存作业应用服务（V2-1）：仓间调拨 / 盘点 / 调整 / 冻结。
 *
 * <p><b>为什么单独一个服务，而不是继续堆进 {@link InventoryApplicationService}</b>：
 * 那个类的职责是"库存数量的唯一入口"（receiveIn / shipOut / reserve），
 * 本类负责<b>单据编排</b>（取号、幂等、状态推进），
 * 真正的数量变动仍然逐笔调用它 —— 所以"库存只能有一个改法"没有被破坏，
 * 只是把"为什么改"与"怎么改"分开。
 *
 * <p><b>幂等</b>：所有写操作都要求 {@code requestId}；建单用
 * {@code ON CONFLICT (request_id) DO NOTHING}，到货/过账/解冻用条件更新，
 * 同键重放返回首次结果（INV-9），异键重复执行报状态非法。
 */
@Service
public class InventoryOperationsApplicationService {

    private final InventoryApplicationService inventory;
    private final InventoryTransferRepository transfers;
    private final StocktakeRepository stocktakes;
    private final InventoryAdjustmentRepository adjustments;
    private final InventoryFreezeRepository freezes;
    private final DocumentNumberGenerator numbers;
    private final ClockProvider clock;

    public InventoryOperationsApplicationService(InventoryApplicationService inventory,
                                                 InventoryTransferRepository transfers,
                                                 StocktakeRepository stocktakes,
                                                 InventoryAdjustmentRepository adjustments,
                                                 InventoryFreezeRepository freezes,
                                                 DocumentNumberGenerator numbers,
                                                 ClockProvider clock) {
        this.inventory = inventory;
        this.transfers = transfers;
        this.stocktakes = stocktakes;
        this.adjustments = adjustments;
        this.freezes = freezes;
        this.numbers = numbers;
        this.clock = clock;
    }

    // ───────────────────────────── 调拨（FR-INV-07） ─────────────────────────────

    public record TransferLineCommand(long skuId, String batchNo, int qty, Long unitCostAmount) {
    }

    public record TransferResult(InventoryTransfer transfer, boolean duplicate) {
    }

    /**
     * 建调拨单并立即发运（两段式的第一段）。
     *
     * <p>源仓扣减在同一个事务里完成：任一明细库存不足，整单回滚，
     * 不会出现"前几行已调出、后面报错"的半成品。
     */
    @Transactional
    public TransferResult createTransfer(long fromWarehouseId, long toWarehouseId,
                                         LocalDate bizDate, String remark,
                                         List<TransferLineCommand> lines, String requestId) {
        requireRequestId(requestId);
        if (fromWarehouseId == toWarehouseId) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "调出仓库与调入仓库不能相同：warehouseId=%d".formatted(fromWarehouseId));
        }
        if (lines == null || lines.isEmpty()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR, "调拨单至少需要一行明细");
        }
        Set<String> keys = new HashSet<>();
        for (TransferLineCommand line : lines) {
            if (line.qty() <= 0) {
                throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                        "调拨数量必须为正：skuId=%d, qty=%d".formatted(line.skuId(), line.qty()));
            }
            String key = line.skuId() + "\u0000" + normalizeBatch(line.batchNo());
            if (!keys.add(key)) {
                throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                        "同一 SKU + 批次在调拨单中重复：skuId=%d, batchNo='%s'"
                                .formatted(line.skuId(), normalizeBatch(line.batchNo())));
            }
        }

        var existing = transfers.findByRequestId(requestId);
        if (existing.isPresent()) {
            return new TransferResult(existing.get(), true);
        }

        LocalDate effectiveDate = bizDate == null ? clock.today() : bizDate;
        String transferNo = numbers.next(DocumentNumberGenerator.DocType.TRANSFER_ORDER, effectiveDate);
        var headerId = transfers.insertHeader(transferNo, fromWarehouseId, toWarehouseId,
                effectiveDate, remark, requestId);
        if (headerId.isEmpty()) {
            // 并发下同一幂等键的另一请求已建单
            return new TransferResult(transfers.findByRequestId(requestId).orElseThrow(
                    () -> new BusinessException(ErrorCode.SYS_DUPLICATE_REQUEST,
                            "重复请求且未找到首次调拨单：requestId=%s".formatted(requestId))), true);
        }
        long transferId = headerId.get();

        int lineNo = 1;
        for (TransferLineCommand line : lines) {
            String batchNo = normalizeBatch(line.batchNo());
            InventoryBalance source = inventory.requireBalance(line.skuId(), fromWarehouseId, batchNo);
            // 逐行派生幂等键：流水唯一索引是 (request_id, biz_type, batch_no)，
            // 不含 sku_id —— 两个不同 SKU 使用同一批号时会撞键。
            inventory.transferOut(line.skuId(), fromWarehouseId, batchNo, line.qty(),
                    transferNo, transferId, scopedRequestId(requestId, ":L" + lineNo));
            transfers.insertLine(transferId, lineNo++, line.skuId(), batchNo, line.qty(),
                    source.productionDate(), source.expiryDate(), line.unitCostAmount());
        }
        return new TransferResult(requireTransfer(transferId), false);
    }

    /**
     * 到货（两段式的第二段）：在途量结清、目的仓实存增加。
     *
     * <p>时间来自注入时钟，因此"在途多久"可以在测试里精确控制。
     */
    @Transactional
    public TransferResult receiveTransfer(long id, String requestId) {
        requireRequestId(requestId);
        InventoryTransfer transfer = requireTransfer(id);

        if (transfer.status() == TransferStatus.COMPLETED) {
            if (requestId.equals(transfers.receiveRequestId(id))) {
                return new TransferResult(transfer, true);
            }
            throw new BusinessException(ErrorCode.INV_TRANSFER_STATUS_ILLEGAL,
                    "调拨单 %s 已到货，不能重复执行到货（requestId=%s）"
                            .formatted(transfer.transferNo(), requestId));
        }

        LocalDateTime now = clock.now();
        if (!transfers.markReceived(id, requestId, now)) {
            InventoryTransfer current = requireTransfer(id);
            if (current.status() == TransferStatus.COMPLETED
                    && requestId.equals(transfers.receiveRequestId(id))) {
                return new TransferResult(current, true);
            }
            throw new BusinessException(ErrorCode.INV_TRANSFER_STATUS_ILLEGAL,
                    "调拨单 %s 当前状态 %s，无法到货"
                            .formatted(current.transferNo(), current.status()));
        }

        for (TransferLine line : transfer.lines()) {
            if (line.inTransitQty() <= 0) {
                continue;
            }
            inventory.transferIn(line.skuId(), transfer.toWarehouseId(), line.batchNo(),
                    line.productionDate(), line.expiryDate(), line.inTransitQty(),
                    transfer.transferNo(), transfer.id(), line.unitCostAmount(),
                    scopedRequestId(requestId, ":L" + line.lineNo()));
        }
        transfers.clearInTransit(id);
        return new TransferResult(requireTransfer(id), false);
    }

    /**
     * 调拨守恒校验（INV-7）：源仓流水 + 在途 + 目的仓流水 == 0。
     *
     * <p>返回的差值恒应为 0；测试直接断言这个差值，
     * 而不是断言各仓的具体数量 —— 后者会把实现细节写死。
     */
    @Transactional(readOnly = true)
    public int transferConservationDiff(long id) {
        InventoryTransfer transfer = requireTransfer(id);
        return transfers.ledgerNet(transfer.transferNo()) + transfers.inTransitQty(id);
    }

    @Transactional(readOnly = true)
    public InventoryTransfer requireTransfer(long id) {
        return transfers.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.INV_TRANSFER_NOT_FOUND,
                        "调拨单不存在：id=%d".formatted(id)));
    }

    @Transactional(readOnly = true)
    public PageResult<InventoryTransfer> pageTransfers(TransferStatus status, Long fromWarehouseId,
                                                        Long toWarehouseId, int page, int size) {
        return transfers.page(status, fromWarehouseId, toWarehouseId, page, size);
    }

    // ───────────────────────────── 盘点（FR-INV-08） ─────────────────────────────

    public record StocktakeLineCommand(long skuId, String batchNo, int countedQty) {
    }

    public record StocktakeResult(Stocktake stocktake, boolean duplicate) {
    }

    /**
     * 建盘点单：账面量在建单时快照，之后库存再变动也不改这份快照。
     *
     * <p>差异 = 实盘 − 账面，由数据库生成列计算，应用层无法算错方向。
     */
    @Transactional
    public StocktakeResult createStocktake(long warehouseId, LocalDate bizDate, String remark,
                                           List<StocktakeLineCommand> lines, String requestId) {
        requireRequestId(requestId);
        if (lines == null || lines.isEmpty()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR, "盘点单至少需要一行明细");
        }
        Set<String> keys = new HashSet<>();
        for (StocktakeLineCommand line : lines) {
            if (line.countedQty() < 0) {
                throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                        "实盘数量不能为负：skuId=%d, countedQty=%d"
                                .formatted(line.skuId(), line.countedQty()));
            }
            String key = line.skuId() + "\u0000" + normalizeBatch(line.batchNo());
            if (!keys.add(key)) {
                throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                        "同一 SKU + 批次在盘点单中重复：skuId=%d, batchNo='%s'"
                                .formatted(line.skuId(), normalizeBatch(line.batchNo())));
            }
        }

        var existing = stocktakes.findByRequestId(requestId);
        if (existing.isPresent()) {
            return new StocktakeResult(existing.get(), true);
        }

        LocalDate effectiveDate = bizDate == null ? clock.today() : bizDate;
        String stocktakeNo = numbers.next(DocumentNumberGenerator.DocType.STOCKTAKE_ORDER, effectiveDate);
        var headerId = stocktakes.insertHeader(stocktakeNo, warehouseId, effectiveDate,
                remark, requestId);
        if (headerId.isEmpty()) {
            return new StocktakeResult(stocktakes.findByRequestId(requestId).orElseThrow(
                    () -> new BusinessException(ErrorCode.SYS_DUPLICATE_REQUEST,
                            "重复请求且未找到首次盘点单：requestId=%s".formatted(requestId))), true);
        }
        long stocktakeId = headerId.get();

        int lineNo = 1;
        for (StocktakeLineCommand line : lines) {
            String batchNo = normalizeBatch(line.batchNo());
            int bookQty = stocktakes.bookQty(line.skuId(), warehouseId, batchNo);
            stocktakes.insertLine(stocktakeId, lineNo++, line.skuId(), batchNo,
                    bookQty, line.countedQty());
        }
        return new StocktakeResult(requireStocktake(stocktakeId), false);
    }

    /**
     * 过账：把差异写成 ADJUST_IN / ADJUST_OUT 流水。
     *
     * <p><b>差异为 0 的行不写流水</b>；整单全部为 0 时直接拒绝过账
     * （{@code INV_STOCKTAKE_NO_DIFFERENCE}），不留下"过账了但什么都没调整"的空单。
     */
    @Transactional
    public StocktakeResult postStocktake(long id, String requestId) {
        requireRequestId(requestId);
        Stocktake stocktake = requireStocktake(id);

        if (stocktake.status() == StocktakeStatus.POSTED) {
            if (requestId.equals(stocktakes.postRequestId(id))) {
                return new StocktakeResult(stocktake, true);
            }
            throw new BusinessException(ErrorCode.INV_STOCKTAKE_STATUS_ILLEGAL,
                    "盘点单 %s 已过账，不能重复过账（requestId=%s）"
                            .formatted(stocktake.stocktakeNo(), requestId));
        }
        if (!stocktake.hasDifference()) {
            throw new BusinessException(ErrorCode.INV_STOCKTAKE_NO_DIFFERENCE,
                    "盘点单 %s 全部行差异为 0，无需过账".formatted(stocktake.stocktakeNo()));
        }

        LocalDateTime now = clock.now();
        if (!stocktakes.markPosted(id, requestId, now)) {
            Stocktake current = requireStocktake(id);
            if (current.status() == StocktakeStatus.POSTED
                    && requestId.equals(stocktakes.postRequestId(id))) {
                return new StocktakeResult(current, true);
            }
            throw new BusinessException(ErrorCode.INV_STOCKTAKE_STATUS_ILLEGAL,
                    "盘点单 %s 当前状态 %s，无法过账"
                            .formatted(current.stocktakeNo(), current.status()));
        }

        for (StocktakeLine line : stocktake.lines()) {
            if (line.diffQty() == 0) {
                continue;
            }
            inventory.adjust(line.skuId(), stocktake.warehouseId(), line.batchNo(),
                    line.diffQty(), stocktake.stocktakeNo(), stocktake.id(), null,
                    scopedRequestId(requestId, ":L" + line.lineNo()));
        }
        return new StocktakeResult(requireStocktake(id), false);
    }

    @Transactional(readOnly = true)
    public Stocktake requireStocktake(long id) {
        return stocktakes.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.INV_STOCKTAKE_NOT_FOUND,
                        "盘点单不存在：id=%d".formatted(id)));
    }

    @Transactional(readOnly = true)
    public PageResult<Stocktake> pageStocktakes(StocktakeStatus status, Long warehouseId,
                                                 int page, int size) {
        return stocktakes.page(status, warehouseId, page, size);
    }

    // ───────────────────────────── 库存调整（FR-INV-09） ─────────────────────────────

    public record AdjustmentResult(InventoryAdjustment adjustment, boolean duplicate) {
    }

    /** 库存调整：原因必填；正数调增、负数调减，一次调整只产生一条流水。 */
    @Transactional
    public AdjustmentResult createAdjustment(long skuId, long warehouseId, String batchNo,
                                             int changeQty, String reason, Long unitCostAmount,
                                             String requestId) {
        requireRequestId(requestId);
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.INV_ADJUST_REASON_REQUIRED,
                    "库存调整必须填写原因：skuId=%d, warehouseId=%d".formatted(skuId, warehouseId));
        }
        if (changeQty == 0) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "调整数量不能为 0：skuId=%d".formatted(skuId));
        }

        var existing = adjustments.findByRequestId(requestId);
        if (existing.isPresent()) {
            return new AdjustmentResult(existing.get(), true);
        }

        String effectiveBatch = normalizeBatch(batchNo);
        String adjustNo = numbers.next(DocumentNumberGenerator.DocType.ADJUSTMENT);
        var id = adjustments.insert(adjustNo, skuId, warehouseId, effectiveBatch,
                changeQty, reason.trim(), requestId);
        if (id.isEmpty()) {
            return new AdjustmentResult(adjustments.findByRequestId(requestId).orElseThrow(
                    () -> new BusinessException(ErrorCode.SYS_DUPLICATE_REQUEST,
                            "重复请求且未找到首次调整单：requestId=%s".formatted(requestId))), true);
        }
        inventory.adjust(skuId, warehouseId, effectiveBatch, changeQty, adjustNo, id.get(),
                unitCostAmount, requestId);
        var saved = adjustments.findByRequestId(requestId).orElseThrow(
                () -> new BusinessException(ErrorCode.SYS_NOT_FOUND,
                        "调整单写入后无法回读：requestId=%s".formatted(requestId)));
        return new AdjustmentResult(saved, false);
    }

    @Transactional(readOnly = true)
    public PageResult<InventoryAdjustment> pageAdjustments(Long skuId, Long warehouseId,
                                                            int page, int size) {
        return adjustments.page(skuId, warehouseId, page, size);
    }

    // ───────────────────────────── 冻结 / 解冻（FR-INV-10） ─────────────────────────────

    public record FreezeResult(InventoryFreeze freeze, boolean duplicate) {
    }

    /** 冻结库存：冻结量立即从可用量里扣除，但实存与流水都不动。 */
    @Transactional
    public FreezeResult createFreeze(long skuId, long warehouseId, String batchNo, int qty,
                                     String reason, LocalDateTime expiresAt, String requestId) {
        requireRequestId(requestId);
        if (qty <= 0) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "冻结数量必须为正：skuId=%d, qty=%d".formatted(skuId, qty));
        }
        if (expiresAt != null && !expiresAt.isAfter(clock.now())) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "冻结到期时间必须晚于当前时间：expiresAt=%s".formatted(expiresAt));
        }

        var existing = freezes.findByRequestId(requestId);
        if (existing.isPresent()) {
            return new FreezeResult(existing.get(), true);
        }

        String effectiveBatch = normalizeBatch(batchNo);
        var id = freezes.insert(skuId, warehouseId, effectiveBatch, qty,
                reason, expiresAt, requestId);
        if (id.isEmpty()) {
            return new FreezeResult(freezes.findByRequestId(requestId).orElseThrow(
                    () -> new BusinessException(ErrorCode.SYS_DUPLICATE_REQUEST,
                            "重复请求且未找到首次冻结记录：requestId=%s".formatted(requestId))), true);
        }
        inventory.freeze(skuId, warehouseId, effectiveBatch, qty);
        return new FreezeResult(freezes.findById(id.get()).orElseThrow(), false);
    }

    /** 人工解冻。 */
    @Transactional
    public FreezeResult releaseFreeze(long id, String requestId) {
        requireRequestId(requestId);
        InventoryFreeze freeze = requireFreeze(id);

        if (freeze.status() != FreezeStatus.ACTIVE) {
            if (requestId.equals(freezes.releaseRequestId(id))) {
                return new FreezeResult(freeze, true);
            }
            throw new BusinessException(ErrorCode.INV_FREEZE_STATUS_ILLEGAL,
                    "冻结记录 %d 当前状态 %s，不能再解冻"
                            .formatted(id, freeze.status()));
        }

        if (!freezes.markReleased(id, FreezeStatus.RELEASED, requestId, clock.now())) {
            InventoryFreeze current = requireFreeze(id);
            if (requestId.equals(freezes.releaseRequestId(id))) {
                return new FreezeResult(current, true);
            }
            throw new BusinessException(ErrorCode.INV_FREEZE_STATUS_ILLEGAL,
                    "冻结记录 %d 当前状态 %s，不能再解冻".formatted(id, current.status()));
        }
        inventory.unfreeze(freeze.skuId(), freeze.warehouseId(), freeze.batchNo(), freeze.qty());
        return new FreezeResult(requireFreeze(id), false);
    }

    /**
     * 超时解冻（FR-INV-10）。
     *
     * <p>到期判断使用注入时钟；状态翻转是条件更新，多实例/重复调用只会成功一次。
     *
     * @return 本次实际解冻的冻结记录数
     */
    @Transactional
    public int expireOverdueFreezes() {
        LocalDateTime now = clock.now();
        int expired = 0;
        for (InventoryFreeze freeze : freezes.findOverdueActive(now)) {
            if (!freezes.markReleased(freeze.id(), FreezeStatus.EXPIRED,
                    "auto-expire:" + freeze.id(), now)) {
                continue;
            }
            inventory.unfreeze(freeze.skuId(), freeze.warehouseId(), freeze.batchNo(), freeze.qty());
            expired++;
        }
        return expired;
    }

    @Transactional(readOnly = true)
    public InventoryFreeze requireFreeze(long id) {
        return freezes.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.INV_FREEZE_NOT_FOUND,
                        "冻结记录不存在：id=%d".formatted(id)));
    }

    @Transactional(readOnly = true)
    public PageResult<InventoryFreeze> pageFreezes(FreezeStatus status, Long skuId,
                                                    Long warehouseId, int page, int size) {
        return freezes.page(status, skuId, warehouseId, page, size);
    }

    // ───────────────────────────── 内部辅助 ─────────────────────────────

    private static String normalizeBatch(String batchNo) {
        return batchNo == null ? "" : batchNo;
    }

    private static void requireRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR, "缺少幂等键 X-Request-Id");
        }
    }

    /**
     * 给子流水派生幂等键：同一次过账/到货可能拆成多条流水（每行/每批次一条），
     * 不能共用同一个 request_id（流水唯一索引含 biz_type + batch_no，但不同行同批次仍会撞）。
     * 截断基串以保住 64 字符上限。
     */
    private static String scopedRequestId(String base, String suffix) {
        int maxBase = Math.max(1, 64 - suffix.length());
        String b = base.length() > maxBase ? base.substring(0, maxBase) : base;
        return b + suffix;
    }
}
