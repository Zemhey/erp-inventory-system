package com.erp.shared.time;

import java.time.LocalDateTime;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时钟装配。
 *
 * <p>默认使用系统时钟；测试 profile 下由 test-kit 覆盖为可控时钟，
 * 从而让跨月结算、效期到期、预占超时变成可断言的确定性用例。
 */
@Configuration
public class ClockConfig {

    @Bean
    public ClockProvider systemClockProvider() {
        return LocalDateTime::now;
    }
}
