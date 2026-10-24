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

    public ReservationExpiryScheduler(InventoryApplicationService inventory,
                                      InventoryOperationsApplicationService operations) {
        this.inventory = inventory;
        this.operations = operations;
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
     */
    @Scheduled(fixedDelayString = "${erp.inventory.reservation-expire-interval-ms}")
    public void expireOverdueReservations() {
        int expired = inventory.expireOverdueReservations();
        if (expired > 0) {
            log.info("预占超时自动释放（BR-INV-05）：本次释放 {} 条", expired);
        }
        // 冻结超时与预占共用同一个调度装配：两者都是"到期必须回收可用量"的守卫，
        // 分成两个调度器只会让测试里多一个需要关闭的开关。
        int unfrozen = operations.expireOverdueFreezes();
        if (unfrozen > 0) {
            log.info("冻结超时自动解冻（FR-INV-10）：本次解冻 {} 条", unfrozen);
        }
    }
}
