package com.erp.shared.scope;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 数据权限的**调用侧便捷入口**（FR-IAM-04）。
 *
 * <p><b>为什么要有这层薄封装</b>：八个资源模块的仓储都需要"取当前范围 + 拼谓词 +
 * 收集参数"这三步。让每个仓储都注入 {@code DataScopeProvider} 再自己展开，
 * 会得到 8 份各写一遍的样板代码；而样板代码越多，漏掉某一处就越不可能被发现
 * —— 漏掉的那一处就是越权入口。
 *
 * <p>本类只做"转发"，<b>不做缓存、不做判定</b>：判定在
 * {@link DataScope}（纯函数），解析在 {@code SharedScopeBridge} 绑定的实现里。
 *
 * <p><b>★ 与 {@code SharedScopeBridge} 的关系</b>：
 * 直接让仓储注入 {@code DataScopeProvider} 是可行的 —— 但那样每个仓储都要
 * try/catch "没有实现"的情形（例如只跑单元测试、没有 IAM 上下文时）。
 * 下面这个桥接组件负责"始终给一个可用实例"，让仓储的代码保持一条直线。
 * 见 {@code SharedScopeBridge} 的说明。
 */
@Component
public class ScopeContext {

    private final DataScopeProvider provider;

    public ScopeContext(DataScopeProvider provider) {
        this.provider = provider;
    }

    /** 当前请求方的作用域。永远非 null（拿不到身份时为"全否"）。 */
    public DataScope current() {
        return provider.currentScope();
    }

    /** 指定用户的作用域（后台任务/测试用）。 */
    public DataScope of(long userId) {
        return provider.scopeOf(userId);
    }

    /**
     * 把"仓库 + 本人"两种收窄一次性拼进 WHERE，并同步收集参数。
     *
     * <p>这是给 {@code purchase_orders / sales_orders / return_orders} 这类
     * <b>同时有仓库列和 {@code created_by} 列</b>的表用的。
     * 库存余额/流水没有 {@code created_by}（它们不是"人建的"，是单据产生的），
     * 只走 {@link #warehouseOnly}。
     *
     * @param warehouseColumn 常量列名（如 {@code "o.warehouse_id"}）
     * @param creatorColumn   常量列名（如 {@code "o.created_by"}）
     * @param args            参数收集器
     * @return 以 {@code AND} 开头的 SQL 片段；无收窄时为空串
     */
    public String predicates(String warehouseColumn, String creatorColumn, List<Object> args) {
        DataScope scope = current();
        return scope.warehousePredicate(warehouseColumn, args)
                + scope.creatorPredicate(creatorColumn, args);
    }

    /** 只有仓库维度的表（余额/流水/日清）。 */
    public String warehouseOnly(String warehouseColumn, List<Object> args) {
        return current().warehousePredicate(warehouseColumn, args);
    }

    /**
     * 当前方是否完全看不到东西（{@code AND 1 = 0}）。
     *
     * <p>给"按 id 直查"用：直查时没有任何 WHERE 可加，只能先判"这个范围是不是全否"。
     * 若是，调用方必须抛 {@code SYS_NOT_FOUND}（<b>404 而非 403</b>，见
     * {@code ErrorCode.IAM_DATA_FORBIDDEN} 的注释）。
     */
    public boolean deniesAll() {
        DataScope scope = current();
        return scope.type() == DataScope.ScopeType.WAREHOUSE && scope.warehouseIds().isEmpty();
    }

    /**
     * 判断某一行是否在当前范围内 —— <b>按 id 直查时的唯一正确做法</b>。
     *
     * <p>为什么不写成"查出来再比对"：那会先把不该看的行读进内存，
     * 日志、缓存、异常堆栈都可能带出它的内容。判定必须在取数之前或同时。
     *
     * @param warehouseId 该行的仓库
     * @param createdBy   该行的创建人（可为 null —— 历史数据 created_by 为空）
     * @return 是否可见
     */
    public boolean allows(Long warehouseId, Long createdBy) {
        DataScope scope = current();
        return switch (scope.type()) {
            case ALL -> true;
            case WAREHOUSE -> warehouseId != null && scope.warehouseIds().contains(warehouseId);
            case SELF -> createdBy != null && createdBy.equals(scope.actorId());
        };
    }
}
