package com.erp.mdm.application;

import com.erp.mdm.domain.Warehouse;
import com.erp.mdm.repository.WarehouseRepository;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.idem.IdempotencyReplay;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 仓库应用服务（FR-MDM-04）。
 *
 * <p>仓库停用后不允许再新建单据（由 PUR/SAL 建单时校验），
 * 但其中的库存依然可查、可出库 —— 停用是"不再收新业务"，不是"冻结库存"。
 */
@Service
public class WarehouseApplicationService {

    private final WarehouseRepository warehouses;

    public WarehouseApplicationService(WarehouseRepository warehouses) {
        this.warehouses = warehouses;
    }

    public record CreateCommand(String code, String name, String address, boolean enabled,
                                Integer priority, String requestId) {

        public CreateCommand(String code, String name, String address, boolean enabled) {
            this(code, name, address, enabled, null, null);
        }

        public CreateCommand(String code, String name, String address, boolean enabled,
                             Integer priority) {
            this(code, name, address, enabled, priority, null);
        }
    }

    public record UpdateCommand(String name, String address, boolean enabled, Integer priority) {

        public UpdateCommand(String name, String address, boolean enabled) {
            this(name, address, enabled, null);
        }
    }

    /**
     * 建仓库（FR-MDM-04 + V2-C1 幂等）。
     *
     * <p>幂等由 {@code warehouses.create_request_id} 的局部唯一索引保证（V15），
     * 与建单幂等键（{@code purchase_orders.request_id}，V2）同一套分工：
     * <ol>
     *   <li>先按键回查 —— 命中即"这个请求已经建过仓库"，直接返回首次那一行的<b>当前状态</b>，
     *       不插入第二行；</li>
     *   <li>没命中就带着键插入，由数据库索引拦住并发下的第二个同键请求
     *       （翻成 409 {@code SYS_DUPLICATE_REQUEST}，见仓储层）。</li>
     * </ol>
     *
     * <p>★ 与"编码唯一"的区别（{@link ProductApplicationService} 明确要求编码不做先查后插）：
     * 编码冲突是<b>两个不同请求</b>撞了同一个业务标识，只能由数据库裁决并翻译成 409；
     * 幂等键冲突是<b>同一个请求</b>到了两次，正确响应是"把首次的结果给我"，
     * 所以必须回查 —— 翻译错误码在这里是错的。
     */
    @Transactional
    public Warehouse create(CreateCommand cmd) {
        var replay = warehouses.findByRequestId(cmd.requestId());
        if (replay.isPresent()) {
            IdempotencyReplay.markCurrentRequest();
            return replay.get();
        }
        long id = warehouses.insert(
                ProductApplicationService.requireText(cmd.code(), "仓库编码"),
                ProductApplicationService.requireText(cmd.name(), "仓库名称"),
                ProductApplicationService.trimToNull(cmd.address()),
                cmd.enabled(),
                cmd.priority() == null ? 100 : requirePriority(cmd.priority()),
                cmd.requestId());
        return require(id);
    }

    @Transactional(readOnly = true)
    public Warehouse require(long id) {
        return warehouses.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.MDM_WAREHOUSE_NOT_FOUND,
                        "仓库不存在：id=%d".formatted(id)));
    }

    @Transactional(readOnly = true)
    public List<Warehouse> list(boolean onlyEnabled) {
        return warehouses.findAll(onlyEnabled);
    }

    @Transactional
    public Warehouse update(long id, UpdateCommand cmd) {
        Warehouse current = require(id);
        boolean ok = warehouses.update(id,
                ProductApplicationService.requireText(cmd.name(), "仓库名称"),
                ProductApplicationService.trimToNull(cmd.address()),
                cmd.enabled(),
                cmd.priority() == null ? current.priority() : requirePriority(cmd.priority()));
        if (!ok) {
            throw new BusinessException(ErrorCode.MDM_WAREHOUSE_NOT_FOUND,
                    "仓库不存在：id=%d".formatted(id));
        }
        return require(id);
    }

    private static int requirePriority(int priority) {
        if (priority < 0) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "仓库优先级不能为负：%d".formatted(priority));
        }
        return priority;
    }

    /** 停用仓库，不带幂等键（内部调用与服务层用例用）。 */
    @Transactional
    public void disable(long id) {
        require(id);
        if (!warehouses.disable(id)) {
            throw new BusinessException(ErrorCode.MDM_WAREHOUSE_NOT_FOUND,
                    "仓库不存在：id=%d".formatted(id));
        }
    }

    /**
     * 停用仓库（V2-C1 幂等）。
     *
     * <p>幂等由两件事共同成立，缺一不可：
     * <ol>
     *   <li><b>状态自身</b>：仓储的 {@code AND enabled} 谓词让"已经是停用态"的重复请求
     *       更新 0 行 —— 不写第二遍、不把 {@code updated_at} 推后；</li>
     *   <li><b>幂等键</b>：{@code disable_request_id} 记住是哪个请求做的这次停用，
     *       使"同一个键打到两个不同资源"被唯一索引拦成 409，
     *       而不是让第二个资源静默地"看起来已停用"。</li>
     * </ol>
     * 0 行时打重放标记，审计因此不留第二条流水；
     * 但若资源被重新启用后原键再来，那是一次<b>真实</b>的状态变更 —— 会写、会留痕，
     * 幂等键不该把一次真实的"再停一次"吞掉。
     */
    @Transactional
    public void disable(long id, String requestId) {
        require(id);
        if (!warehouses.disable(id, requestId)) {
            IdempotencyReplay.markCurrentRequest();
        }
    }
}
