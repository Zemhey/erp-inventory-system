package com.erp.iam.security;

import com.erp.iam.domain.AppUser;
import com.erp.shared.time.ClockProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * JWT 签发与校验（FR-IAM-01）。
 *
 * <p><b>为什么手写这部分而不是引第三方 starter</b>：starter 把"如何校验"藏进过滤器里，
 * 而本项目要做的是<b>可测试的安全边界</b> —— 过期、篡改、算法混淆这三类攻击
 * 必须各有独立用例。直接把 jjwt 摆在明面，测试就能精确地构造每一种坏 token。
 *
 * <p><b>三个安全要点</b>：
 * <ol>
 *   <li><b>过期时间用注入时钟计算</b>，测试才能"穿越"到 token 过期之后
 *       （ClockProvider 的价值在此体现，计划书 §3.5）；</li>
 *   <li><b>只接受 HS256</b>：jjwt 0.12 起解析时必须显式给出密钥，
 *       不再按 token 头部的 alg 自选算法 —— 这从根本上杜绝了 {@code alg=none}
 *       与"HMAC 伪装成 RSA"两类著名漏洞；</li>
 *   <li><b>校验失败一律返回空</b>，不区分"过期"与"签名错"，
 *       避免把内部细节泄露给攻击者（具体原因只写日志）。</li>
 * </ol>
 */
@Component
public class JwtTokenService {

    /** token 有效期。生产可按需调整（见 application.yml）。 */
    private final Duration ttl;

    private final SecretKey key;
    private final ClockProvider clock;

    public JwtTokenService(@Value("${erp.iam.jwt-secret}") String secret,
                           @Value("${erp.iam.token-ttl-minutes:120}") long ttlMinutes,
                           ClockProvider clock) {
        // HS256 要求密钥至少 256 bit（32 字节）。这里在启动期就校验，
        // 让配置错误在启动时炸掉，而不是在第一个用户登录时才失败。
        byte[] raw = secret.getBytes(StandardCharsets.UTF_8);
        if (raw.length < 32) {
            throw new IllegalStateException(
                    "erp.iam.jwt-secret 过短：%d 字节，HS256 要求至少 32 字节".formatted(raw.length));
        }
        this.key = Keys.hmacShaKeyFor(raw);
        this.ttl = Duration.ofMinutes(ttlMinutes);
        this.clock = clock;
    }

    /** 签发 token。 */
    public String issue(AppUser user) {
        Instant now = clock.now().atZone(java.time.ZoneId.systemDefault()).toInstant();
        return Jwts.builder()
                .subject(user.username())
                .claim("uid", user.id())
                .claim("roles", user.roles())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 校验并解析 token。
     *
     * @return token 中的用户名；签名错误 / 过期 / 格式损坏一律返回空
     */
    public Optional<String> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)          // 只认这一把密钥 → 绑定 HS256
                    // ★ 关键：解析器的时钟也必须指向**注入时钟**。
                    //   jjwt 默认用真实系统时间校验 exp，而业务时间来自 ClockProvider
                    //   （计划书 §3.5：禁止 LocalDateTime.now()）。两者一旦分家，
                    //   测试里把时钟固定在 2025 年签出的 token，会被 jjwt 用 2026 年的
                    //   真实时间判成"43738 秒前就过期了"，于是在正确代码上得到 401。
                    //   （M1 开发中真实踩到，10 条 IAM 用例里 3 条因它变红。）
                    .clock(() -> now())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String subject = claims.getSubject();
            return subject == null || subject.isBlank() ? Optional.empty() : Optional.of(subject);
        } catch (JwtException | IllegalArgumentException ex) {
            // 签名不符、结构损坏 —— 对外一律"无效"，不暴露细节
            return Optional.empty();
        }
    }

    /** token 中的角色（仅用于诊断，鉴权以数据库为准）。 */
    public List<String> rolesOf(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key)
                    .build()
                    .parseSignedClaims(token).getPayload();
            Object roles = claims.get("roles");
            if (roles instanceof List<?> list) {
                return list.stream().map(String::valueOf).toList();
            }
            return List.of();
        } catch (JwtException | IllegalArgumentException ex) {
            return List.of();
        }
    }

    /**
     * jjwt 0.12 的 `Clock` 函数式接口返回 java.util.Date，故这里也用 Date。
     * 时间来源始终是可注入的 ClockProvider，绝不直接用 System.currentTimeMillis()。
     */
    private Date now() {
        return Date.from(clock.now().atZone(java.time.ZoneId.systemDefault()).toInstant());
    }

    /** 供测试断言有效期。 */
    public Duration ttl() {
        return ttl;
    }
}
