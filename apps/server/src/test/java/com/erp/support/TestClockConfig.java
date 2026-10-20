package com.erp.support;

import com.erp.shared.time.ClockProvider;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * 测试用可控时钟（docs/06-测试策略 §3）。
 *
 * <p><b>为什么需要它</b>：token 过期、账号锁定到期、预占超时、跨月账期归属，
 * 这些都依赖"现在几点"。用真实时钟只能靠 {@code Thread.sleep} 等待，
 * 测试会从毫秒级变成分钟级，而且不稳定。
 *
 * <p>用法：在需要的测试类上 {@code @ActiveProfiles("clock")}，然后
 * {@code clock.advance(Duration.ofMinutes(31))} 让时间"快进"。
 * 由于是单例且有状态，测试之间必须注意隔离（每个用例前 reset）。
 */
@Configuration
@Profile("clock")
public class TestClockConfig {

    @Bean
    @Primary
    public ControllableClock controllableClock() {
        return new ControllableClock(LocalDateTime.of(2025, 6, 1, 9, 0, 0));
    }

    /** 可快进的时钟实现。 */
    public static class ControllableClock implements ClockProvider {

        private LocalDateTime current;

        public ControllableClock(LocalDateTime start) {
            this.current = start;
        }

        @Override
        public LocalDateTime now() {
            return current;
        }

        public synchronized void advance(Duration d) {
            this.current = current.plus(d);
        }

        public synchronized void set(LocalDateTime t) {
            this.current = t;
        }

        public synchronized LocalDateTime current() {
            return current;
        }
    }
}
