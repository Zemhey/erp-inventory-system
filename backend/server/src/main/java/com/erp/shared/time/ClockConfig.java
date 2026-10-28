package com.erp.shared.time;

import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时钟装配。
 *
 * <p>默认使用系统时钟；测试 profile 下由 test-kit 覆盖为可控时钟，
 * 从而让跨月结算、效期到期、预占超时变成可断言的确定性用例。
 *
 * <p>演示/回填场景（{@code erp.demo.clock-override-enabled=true}）下改为
 * {@link OverridableClockProvider}——它只在收到
 * {@code X-Demo-Biz-Time} 请求头时退让给被指定的时间，否则仍是真实系统时间。
 * 生产配置不打开这个开关，装配出来的 provider 与改动前行为完全一致。
 */
@Configuration
public class ClockConfig {

    @Bean
    public ClockProvider systemClockProvider(
            @Value("${erp.demo.clock-override-enabled:false}") boolean demoClockOverrideEnabled) {
        return new OverridableClockProvider(demoClockOverrideEnabled);
    }
}
