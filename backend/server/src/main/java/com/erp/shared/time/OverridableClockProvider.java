package com.erp.shared.time;

import java.time.LocalDateTime;

/**
 * 可被请求级覆盖的时钟：开关关闭时与改动前完全等价。
 *
 * <p>装配点在 {@link ClockConfig}（架构规则允许那里拿到真实时间）。
 * 本类只做一件事：<b>有覆盖且开关打开</b>时返回被指定的时间，否则一律返回真实时间。
 *
 * <p>把判断收在这一个 {@code if} 里，是为了让"生产行为不变"这件事可以被一眼读出来，
 * 而不是散落在各个业务服务的取时语句上。
 */
public class OverridableClockProvider implements ClockProvider {

    private final boolean overrideEnabled;

    /**
     * @param overrideEnabled 是否允许请求级覆盖（来自 {@code erp.demo.clock-override-enabled}）
     */
    public OverridableClockProvider(boolean overrideEnabled) {
        this.overrideEnabled = overrideEnabled;
    }

    @Override
    public LocalDateTime now() {
        if (overrideEnabled) {
            LocalDateTime pinned = DemoClockOverride.pinned();
            if (pinned != null) {
                return pinned;
            }
        }
        return LocalDateTime.now();
    }
}
