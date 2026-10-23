package com.erp.iam.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.support.CaseId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 密码策略单元测试（FR-IAM-06）—— L1 层，不依赖数据库。
 *
 * <p><b>为什么这组边界值得单独测</b>：复杂度校验是"看起来简单、实际全是边界"的典型。
 * 长度用的是 {@code >=} 还是 {@code >}、字符类别数是否把符号算进去、
 * 弱口令表区分大小写与否 —— 每一条都能让 {@code Aa1!} 通过或拒掉
 * {@code Correct1!}，而这两种错误的后果完全相反（放过弱口令 / 拦住好用户）。
 * 门禁测试的价值就在把这些"恰好一格"的边界钉死。
 */
@DisplayName("IAM · 密码策略（复杂度 / 过期）")
class PasswordPolicyTest {

    /** 等保三级口径：8 位、3 类字符、90 天过期。 */
    private static final PasswordPolicy STANDARD = PasswordPolicy.standard();

    // ─────────────────────────── 复杂度：长度 ───────────────────────────

    @ParameterizedTest(name = "[{index}] 长度 {0} → {1}")
    @CsvSource({
            // 8 位是下限（含），7 位必须拒 —— 差一位就是合规与不合规的分界
            "7,  false",
            "8,  true",
            "9,  true",
            "20, true"})
    @CaseId("TC-IAM-012")
    @DisplayName("★ 长度边界：8 位通过、7 位拒绝（下限是 ≥ 而不是 >）")
    void lengthBoundary(int length, boolean expectedOk) {
        // 构造"恰好 3 类字符"的密码：大写 + 小写 + 数字，再补足长度
        String base = "Ab1";
        String password = base + "x".repeat(Math.max(0, length - base.length()));
        Optional<String> reason = STANDARD.check(password);
        assertThat(reason.isEmpty())
                .as("长度 %d 的密码「%s」期望%s，实际原因=%s",
                        length, password, expectedOk ? "通过" : "拒绝", reason.orElse("(无)"))
                .isEqualTo(expectedOk);
        if (!expectedOk) {
            assertThat(reason.orElseThrow()).contains("长度").contains("8");
        }
    }

    // ─────────────────────────── 复杂度：字符类别 ───────────────────────────

    @ParameterizedTest(name = "[{index}] {0} 类别 → {1}")
    @CsvSource(delimiter = '|', value = {
            "abcdefgh | 1 | false",
            "abcdefg1 | 2 | false",
            "Abcdefg1 | 3 | true",
            "Abcdef1! | 4 | true"})
    @CaseId("TC-IAM-013")
    @DisplayName("★ 字符类别边界：2 类拒绝、3 类通过、4 类通过（纯小写即使够长也拒）")
    void charClassBoundary(String password, int classes, boolean expectedOk) {
        assertThat(PasswordPolicy.charClasses(password))
                .as("「%s」应被识别为 %d 类字符", password, classes)
                .isEqualTo(classes);
        assertThat(STANDARD.check(password).isEmpty())
                .as("「%s」（%d 类）期望%s", password, classes, expectedOk ? "通过" : "拒绝")
                .isEqualTo(expectedOk);
    }

    @Test
    @CaseId("TC-IAM-014")
    @DisplayName("★ 8 位纯小写必须被拒：只查长度会让 aaaaaaaa 通过 —— 两类规则缺一不可")
    void longButSingleClass_isRejected() {
        assertThat(STANDARD.check("aaaaaaaa")).isPresent()
                .get().asString().contains("类字符");
        // 同样 8 位，补到 3 类就通过 —— 证明拒的原因确实只是种类
        assertThat(STANDARD.check("Aaaaaaa1")).isEmpty();
    }

    // ─────────────────────────── 弱口令 ───────────────────────────

    @ParameterizedTest(name = "[{index}] {0} 必须被拒")
    @CsvSource({
            "password", "Passw0rd", "P@ssw0rd", "12345678", "admin@123",
            "Admin@123", "qwerty123", "letmein1"})
    @CaseId("TC-IAM-015")
    @DisplayName("★ 弱口令表大小写不敏感：Admin@123 与 admin@123 是同一条字典项")
    void commonWeakPasswords_areRejected_caseInsensitive(String weak) {
        Optional<String> reason = STANDARD.check(weak);
        assertThat(reason)
                .as("「%s」属常见弱口令，必须被拒（大小写不敏感）", weak)
                .isPresent();
        assertThat(reason.orElseThrow()).contains("弱口令");
    }

    @Test
    @CaseId("TC-IAM-016")
    @DisplayName("弱口令检查优先于长度/类别：让错误消息直指真正原因（换个更像样的密码）")
    void weakPasswordMessageComesBeforeLengthCheck() {
        // P@ssw0rd 长度 8、4 类，单看长度与类别都合规 —— 唯一的问题是它在字典里。
        // 若弱口令检查排在后面，用户会得到"你的密码没问题"然后设置成功。
        assertThat(PasswordPolicy.charClasses("P@ssw0rd")).isEqualTo(4);
        assertThat(STANDARD.check("P@ssw0rd").orElseThrow()).contains("弱口令");
    }

    // ─────────────────────────── 空值 ───────────────────────────

    @Test
    @CaseId("TC-IAM-017")
    @DisplayName("空密码 / 纯空白一律拒绝")
    void blankPassword_isRejected() {
        assertThat(STANDARD.check(null)).isPresent();
        assertThat(STANDARD.check("")).isPresent();
        assertThat(STANDARD.check("   ")).isPresent();
    }

    // ─────────────────────────── 可配置性 ───────────────────────────

    @Test
    @CaseId("TC-IAM-018")
    @DisplayName("★ 策略可配置：同一密码在 permissive 策略下通过、在 standard 下被拒")
    void policyIsConfigurable() {
        String weakPerStandard = "abcdefgh";           // 1 类，standard 拒
        assertThat(STANDARD.check(weakPerStandard)).isPresent();
        assertThat(PasswordPolicy.permissive().check(weakPerStandard))
                .as("关闭策略后必须放行 —— 否则「可配置」就是一句空话")
                .isEmpty();
        // 更严格的策略（12 位）必须拒掉 standard 认可的密码
        PasswordPolicy strict = new PasswordPolicy(12, 3, 90);
        assertThat(strict.check("Abcdefg1")).isPresent();
        assertThat(strict.check("Abcdefghij1!")).isEmpty();
    }

    @Test
    @CaseId("TC-IAM-019")
    @DisplayName("非法策略参数在构造期就炸掉：minLength<=0 / 类别数越界")
    void invalidPolicyParameters_failFast() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PasswordPolicy(0, 3, 90))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PasswordPolicy(8, 0, 90))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PasswordPolicy(8, 5, 90))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ─────────────────────────── 过期 ───────────────────────────

    @Test
    @CaseId("TC-IAM-020")
    @DisplayName("★ 密码过期判定：过期时刻早于/等于此刻 = 过期，晚于 = 未过期")
    void passwordExpiry_boundary() {
        Instant now = Instant.parse("2025-06-01T00:00:00Z");
        // 过期时刻恰等于此刻 → 视为已过期（"有效期到这一刻为止"）
        assertThat(STANDARD.mustChange(user(now, false), now))
                .as("过期时刻 == 此刻 必须判定为过期（有效期到这一刻为止）")
                .isPresent();
        assertThat(STANDARD.mustChange(user(now.minusSeconds(1), false), now)).isPresent();
        assertThat(STANDARD.mustChange(user(now.plusSeconds(1), false), now))
                .as("还有 1 秒才过期 → 未过期")
                .isEmpty();
    }

    @Test
    @CaseId("TC-IAM-021")
    @DisplayName("★ 强制改密标记：即使密码永不过期，mustChangePassword=true 也必须先改")
    void mustChangeFlag_isHonoured() {
        Instant now = Instant.parse("2025-06-01T00:00:00Z");
        assertThat(STANDARD.mustChange(user(null, true), now))
                .as("没有过期时刻但被标记为必须改密 → 仍需强制改密")
                .isPresent()
                .get().asString().contains("重置");
        // 两个条件都不成立才放行
        assertThat(STANDARD.mustChange(user(null, false), now)).isEmpty();
    }

    @Test
    @CaseId("TC-IAM-022")
    @DisplayName("过期时刻按 maxAgeDays 计算；0 表示不按时间过期")
    void expiryComputation() {
        Instant issued = Instant.parse("2025-06-01T00:00:00Z");
        assertThat(STANDARD.expiryOf(issued))
                .isEqualTo(Instant.parse("2025-08-30T00:00:00Z"));   // 90 天后
        assertThat(PasswordPolicy.permissive().expiryOf(issued))
                .as("maxAgeDays=0 → 不过期")
                .isNull();
    }

    @Test
    @CaseId("TC-IAM-023")
    @DisplayName("策略描述可读且完整：前端照它展示，用户不必反复试错")
    void describeIsHumanReadable() {
        List<String> rules = STANDARD.describe();
        assertThat(rules).hasSize(3);
        assertThat(String.join(" ", rules)).contains("8").contains("3").contains("90");
        assertThat(String.join(" ", PasswordPolicy.permissive().describe()))
                .contains("不按时间过期");
    }

    /** 造一个用于过期判定的最小账号（其余字段与本用例无关）。 */
    private static AppUser user(Instant expiresAt, boolean mustChange) {
        return new AppUser(1L, "tester", "$2a$10$hash", "测试员", true, 0, null,
                expiresAt, mustChange, List.of("VIEWER"), Set.of("mdm:read"));
    }
}
