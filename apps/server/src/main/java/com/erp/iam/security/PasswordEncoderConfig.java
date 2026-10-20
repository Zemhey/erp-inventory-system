package com.erp.iam.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码编码器（单独成类，只做一件事）。
 *
 * <p><b>为什么必须与 {@link SecurityConfig} 分开</b>：
 * 依赖链是 {@code SecurityConfig → JwtAuthenticationFilter → AuthenticationService
 * → PasswordEncoder}。若把 {@code PasswordEncoder} 也声明在 {@code SecurityConfig} 里，
 * 就形成了 {@code SecurityConfig → ... → SecurityConfig} 的构造器循环依赖，
 * 启动即失败（M1 开发中真实踩到：10 条 IAM 用例全部因上下文加载失败而红，
 * 而错误信息指向 AuthenticationService，很有误导性）。
 *
 * <p>把它拆成一个不含任何安全配置依赖的极简配置类，循环自然断开。
 */
@Configuration
public class PasswordEncoderConfig {

    /**
     * BCrypt 强度 10（Spring 默认）。强度每 +1 计算耗时翻倍：
     * 10 ≈ 60ms/次，足以让离线爆破变得昂贵，又不至于拖慢登录接口。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
