package com.erp.inv.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 预占超时自动释放（BR-INV-05）与冻结超时自动解冻（FR-INV-10）的定时入口。
 *
 * <p><b>只做调度，不含业务规则</b>：全部逻辑在
 * {@link InventoryApplicationService#expireOverdueReservations()} 里。
 * 这样测试可以直接调用服务方法，不必等待真实调度到来 ——
 * 依赖定时触发的测试必然随机红（计划书 §6 坑 9b）。
 *
 * <p><b>开关 {@code erp.inventory.expire-scheduling-enabled}</b>（默认 true）：
 * 关掉的不是"这次跑不跑"，而是<b>整个调度装配</b> ——
 * 本类同时带着 {@code @EnableScheduling}，条件不满足时类不会被注册，
 * Spring 也就不会创建任何调度线程。测试 profile 用它把自己从坑 9b 里摘出来。
 *
 * <p><b>多实例安全</b>：将来横向扩容时多个进程会同时跑这个任务，
 * 但 {@code ACTIVE → EXPIRED} 是条件更新，同一条预占只会有一个实例翻转成功，
 * 其余实例拿到 false 直接跳过（见 {@code expireOverdueReservations}），
 * 所以不需要分布式锁。
 */
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "erp.inventory", name = "expire-scheduling-enabled",
        havingValue = "true", matchIfMissing = true)
public class ReservationExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpiryScheduler.class);

    private final InventoryApplicationService inventory;
    private final InventoryOperationsApplicationService operations;
    private final com.erp.shared.tenant.TenantDirectory tenants;

    public ReservationExpiryScheduler(InventoryApplicationService inventory,
                                      InventoryOperationsApplicationService operations,
                                      com.erp.shared.tenant.TenantDirectory tenants) {
        this.inventory = inventory;
        this.operations = operations;
        this.tenants = tenants;
    }

    /**
     * 周期性回收到期预占。
     *
     * <p>间隔来自配置（{@code erp.inventory.reservation-expire-interval-ms}，单位毫秒），
     * 禁止硬编码：超时阈值本身已经是配置项（{@code reservation-ttl-minutes}），
     * 扫描间隔若写死，改 TTL 的人不会想起来同步改这里。
     *
     * <p>用 {@code fixedDelay} 而非 {@code fixedRate}：上一轮没跑完就推迟下一轮，
     * 避免慢查询场景下任务自我堆叠。
     *
     * <p><b>★ V2-7 FR-TNT-04：定时任务的租户归属</b>
     * 后台任务是"没有 HTTP 请求"的执行路径，因此<b>没有</b>天然的租户。
     * 两条可能的做法：
     * <ul>
     *   <li>跑一遍、不加租户条件 —— 结果是<b>跨租户</b>批量释放，一旦某租户的
     *       超时阈值配置不同就会错放别人的货；</li>
     *   <li><b>逐租户绑定再跑</b>（本实现）—— 每个租户各跑一遍，失败局部化。</li>
     * </ul>
     * 为什么选后者：它的代价只是"多跑 N 次"，而前者的代价是"一次出错影响全域"，
     * 且这种缺陷只在多租户同时有数据时暴露 —— 单租户的既有用例<b>全绿</b>。
     */
    @Scheduled(fixedDelayString = "${erp.inventory.reservation-expire-interval-ms}")
    public void expireOverdueReservations() {
        for (long tenantId : tenants.enabledTenantIds()) {
            com.erp.shared.tenant.TenantContext.runIn(tenantId, () -> {
                int expired = inventory.expireOverdueReservations();
                if (expired > 0) {
                    log.info("预占超时自动释放（BR-INV-05）：租户 {} 本次释放 {} 条",
                            tenantId, expired);
                }
                // 冻结超时与预占共用同一个调度装配：两者都是"到期必须回收可用量"的守卫，
                // 分成两个调度器只会让测试里多一个需要关闭的开关。
                int unfrozen = operations.expireOverdueFreezes();
                if (unfrozen > 0) {
                    log.info("冻结超时自动解冻（FR-INV-10）：租户 {} 本次解冻 {} 条",
                            tenantId, unfrozen);
                }
            });
        }
    }
}
