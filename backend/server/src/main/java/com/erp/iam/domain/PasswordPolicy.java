package com.erp.iam.domain;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 登录安全策略（FR-IAM-06）：密码复杂度、有效期、失败锁定。
 *
 * <p><b>★ 为什么做成"纯对象 + 可配置"而不是写死在代码里</b>：
 * 复杂度策略是<b>可以被合规要求推翻</b>的业务参数（等保三级要求 8 位四类、
 * 某些客户要求 12 位、某些要求 90 天过期）。写死后每次调整都要改代码 + 发版，
 * 而配置化之后，管理员改一行 {@code application.yml} 就能适配。
 *
 * <p>本类<b>不依赖 Spring 与数据库</b>（domain 层的规矩）：
 * 只回答"这条密码合不合规""这个账号该不该强制改密"。
 * 配置注入在 {@code application} 层的服务里完成。
 *
 * <p><b>三条规则的语义（都要能被测试直接断言）</b>：
 * <ol>
 *   <li><b>复杂度</b>：长度 + 字符类别数 + 禁用弱口令表。
 *       三者同时成立才算通过 —— 只查长度会让 {@code aaaaaaaa} 通过，
 *       只查类别会让 {@code Aa1!} 通过。</li>
 *   <li><b>过期</b>：{@code password_expires_at} 早于此刻 = 过期；
 *       {@code must_change_password = true} = 强制改密。
 *       两者都归 {@code IAM_PASSWORD_EXPIRED}，因为客户端处理方式相同（跳改密页）。</li>
 *   <li><b>锁定</b>：由 {@code AppUser.isLockedAt} 判定（已有能力，本类不重复实现）。</li>
 * </ol>
 *
 * @param minLength        最小长度
 * @param minCharClasses   至少包含几类字符（大写/小写/数字/符号）
 * @param maxAgeDays       密码最长有效期（天）；0 = 不按时间过期
 */
public record PasswordPolicy(int minLength, int minCharClasses, int maxAgeDays) {

    /**
     * 内置弱口令表 —— <b>刻意保持短小</b>（覆盖最常见的几十个，而非抄一份十万条的字典）。
     *
     * <p>理由：真正的弱口令防护靠"失败锁定 + 长密码"，而不是靠字典。
     * 一份十万条的表放进代码库会让 review 变成翻字典，而拦住的仍是那些
     * 早被失败锁定拦下的尝试。这里只钉住最典型的几个，让"admin/admin"这类
     * 默认口令无法被设置（V3 的注释里也明确不预置默认账号，同一条立场）。
     */
    /**
     * <p><b>★ 用 {@code Set.copyOf(List.of(...))} 而不是 {@code Set.of(...)}</b>：
     * {@code Set.of} 在遇到<b>重复元素</b>时会抛 {@code IllegalArgumentException}，
     * 而这个静态初始化异常会被 JDK 包成 {@code NoClassDefFoundError: Could not
     * initialize class PasswordPolicy} —— 报错信息完全看不出"是字典里有重复项"，
     * 而症状是整片用例一起红（连 {@code PasswordPolicy} 都加载不了）。
     * 第一次跑测试时真实踩到：手写长字典很容易重复，用 List 兜底后重复只是去重，
     * 不会让整个类初始化失败。
     */
    private static final Set<String> COMMON_WEAK = Set.copyOf(List.of(
            "password", "passw0rd", "12345678", "123456789", "1234567890",
            "admin123", "admin@123", "administrator", "qwerty", "qwerty123",
            "abc12345", "iloveyou", "11111111", "88888888", "password1",
            "p@ssw0rd", "letmein", "letmein1", "welcome", "welcome1", "changeme",
            "qwertyuiop", "1qaz2wsx", "zaq12wsx", "abcd1234", "a1b2c3d4"));

    /** 字符类别的判定：四类各自一条正则（不写成一个巨型正则，便于分别报错）。 */
    private static final Pattern UPPER = Pattern.compile(".*[A-Z].*");
    private static final Pattern LOWER = Pattern.compile(".*[a-z].*");
    private static final Pattern DIGIT = Pattern.compile(".*[0-9].*");
    private static final Pattern SYMBOL = Pattern.compile(".*[^A-Za-z0-9].*");

    public PasswordPolicy {
        if (minLength < 1) {
            throw new IllegalArgumentException("密码最小长度必须为正：" + minLength);
        }
        if (minCharClasses < 1 || minCharClasses > 4) {
            throw new IllegalArgumentException("字符类别数必须在 1~4：" + minCharClasses);
        }
    }

    /** 一个常用的默认策略：8 位、至少 3 类字符、90 天过期（等保三级口径）。 */
    public static PasswordPolicy standard() {
        return new PasswordPolicy(8, 3, 90);
    }

    /** 不做任何校验（仅供关闭策略的部署使用；测试里也用来验证"可配置"确实生效）。 */
    public static PasswordPolicy permissive() {
        return new PasswordPolicy(1, 1, 0);
    }

    /**
     * 校验密码合规性。
     *
     * @param rawPassword 明文密码（只在内存里出现，不落库、不进日志）
     * @return 违规原因；为空表示合规
     */
    public Optional<String> check(String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            return Optional.of("密码不能为空");
        }
        int classes = charClasses(rawPassword);
        // ★ 先用小写归一化再查弱口令表：{@code Admin@123} 与 {@code admin@123}
        //   在暴力破解字典里是同一条，区分大小写等于放过一半。
        if (COMMON_WEAK.contains(rawPassword.toLowerCase(Locale.ROOT))) {
            return Optional.of("密码属于常见弱口令，请更换");
        }
        if (rawPassword.length() < minLength) {
            return Optional.of("密码长度至少 %d 位，当前 %d 位"
                    .formatted(minLength, rawPassword.length()));
        }
        if (classes < minCharClasses) {
            return Optional.of("密码至少需要 %d 类字符（大写/小写/数字/符号），当前 %d 类"
                    .formatted(minCharClasses, classes));
        }
        return Optional.empty();
    }

    /** 数一数密码里出现了几类字符（0~4）。 */
    public static int charClasses(String raw) {
        if (raw == null || raw.isEmpty()) {
            return 0;
        }
        int classes = 0;
        if (UPPER.matcher(raw).matches()) {
            classes++;
        }
        if (LOWER.matcher(raw).matches()) {
            classes++;
        }
        if (DIGIT.matcher(raw).matches()) {
            classes++;
        }
        if (SYMBOL.matcher(raw).matches()) {
            classes++;
        }
        return classes;
    }

    /**
     * 某账号在给定时刻是否必须改密。
     *
     * @param user 账号
     * @param now  当前业务时刻（注入时钟换算而来，便于测试穿越）
     * @return 需要强制改密的原因；为空表示不必
     */
    public Optional<String> mustChange(AppUser user, java.time.Instant now) {
        if (user.mustChangePassword()) {
            return Optional.of("密码已被重置，请先修改密码");
        }
        if (user.passwordExpiresAt() != null && !user.passwordExpiresAt().isAfter(now)) {
            return Optional.of("密码已于 %s 过期，请先修改密码".formatted(user.passwordExpiresAt()));
        }
        return Optional.empty();
    }

    /**
     * 计算新密码的过期时刻。
     *
     * @param issuedAt 本次设置密码的时刻
     * @return 过期时刻；{@code maxAgeDays == 0} 时返回 null（不按时间过期）
     */
    public java.time.Instant expiryOf(java.time.Instant issuedAt) {
        return maxAgeDays <= 0 ? null : issuedAt.plus(java.time.Duration.ofDays(maxAgeDays));
    }

    /** 策略的可读描述（进 {@code /auth/me} 与登录响应，前端据此提示用户）。 */
    public List<String> describe() {
        return List.of(
                "密码长度至少 %d 位".formatted(minLength),
                "至少包含 %d 类字符（大写/小写/数字/符号）".formatted(minCharClasses),
                maxAgeDays > 0 ? "密码每 %d 天过期".formatted(maxAgeDays) : "密码不按时间过期");
    }
}
