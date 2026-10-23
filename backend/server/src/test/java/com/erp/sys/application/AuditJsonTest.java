package com.erp.sys.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.support.CaseId;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 审计快照的序列化与脱敏（FR-SYS-06）。
 *
 * <p><b>这一层为什么必须有单测</b>：脱敏失败不会让任何接口报错，
 * 它只会安静地把密码明文写进审计表 —— 一个直到有人翻审计时才发现的
 * 安全事故。因此脱敏的每一条规则都要有对应用例钉住。
 */
@DisplayName("审计 · 快照序列化与脱敏")
class AuditJsonTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    @CaseId("TC-SYS-008")
    @DisplayName("★ 密码字段被遮成 ***（审计不得记录明文密码）")
    void password_isMasked() {
        String snapshot = AuditJson.snapshot(
                Map.of("username", "alice", "password", "PlainText1!"), json);
        assertThat(snapshot).isNotNull();
        assertThat(snapshot).doesNotContain("PlainText1!");
        assertThat(snapshot).contains("***");
        // 非敏感字段原样保留：审计的价值在于"字段值一致"
        assertThat(snapshot).contains("alice");
    }

    @Test
    @CaseId("TC-SYS-009")
    @DisplayName("★ token / secret / apiKey / authorization 全部脱敏")
    void otherSensitiveKeys_areMasked() {
        String snapshot = AuditJson.snapshot(Map.of(
                "token", "tk-123",
                "accessToken", "at-456",
                "secret", "s-789",
                "apiKey", "k-000",
                "authorization", "Bearer xyz"), json);
        assertThat(snapshot)
                .doesNotContain("tk-123", "at-456", "s-789", "k-000", "Bearer xyz");
    }

    @Test
    @CaseId("TC-SYS-010")
    @DisplayName("键名变体也脱敏：passwordHash / new_password / clientSecret")
    void keyVariants_areMasked() {
        assertThat(AuditJson.isSensitive("passwordHash")).isTrue();
        assertThat(AuditJson.isSensitive("new_password")).isTrue();
        assertThat(AuditJson.isSensitive("clientSecret")).isTrue();
        assertThat(AuditJson.isSensitive("PASSWORD")).isTrue();
        assertThat(AuditJson.isSensitive("api-key")).isTrue();
    }

    @Test
    @CaseId("TC-SYS-011")
    @DisplayName("非敏感键不被误遮：tokenType / username / name 原样保留")
    void nonSensitiveKeys_areNotMasked() {
        assertThat(AuditJson.isSensitive("tokenType")).isFalse();
        assertThat(AuditJson.isSensitive("username")).isFalse();
        assertThat(AuditJson.isSensitive("name")).isFalse();
        assertThat(AuditJson.isSensitive("passwordResetCount")).isFalse();

        String snapshot = AuditJson.snapshot(
                Map.of("tokenType", "Bearer", "username", "bob"), json);
        assertThat(snapshot).contains("Bearer").contains("bob");
    }

    @Test
    @CaseId("TC-SYS-012")
    @DisplayName("嵌套对象与数组里的敏感字段同样被遮（不能只管第一层）")
    void nestedStructures_areMasked() {
        Map<String, Object> value = Map.of(
                "items", List.of(Map.of("sku", "S1", "secret", "deep-secret")),
                "meta", Map.of("password", "deep-pass"));
        String snapshot = AuditJson.snapshot(value, json);
        assertThat(snapshot).doesNotContain("deep-secret", "deep-pass");
    }

    @Test
    @CaseId("TC-SYS-013")
    @DisplayName("标量与 null 不产生快照（审计只关心对象的字段值）")
    void scalarsProduceNoSnapshot() {
        assertThat(AuditJson.snapshot(null, json)).isNull();
        assertThat(AuditJson.snapshot(42L, json)).isNull();
        assertThat(AuditJson.snapshot("text", json)).isNull();
    }

    @Test
    @CaseId("TC-SYS-014")
    @DisplayName("★ 超大快照返回 null 而不是截断（截断会产生非法 JSON，让「可还原」失效）")
    void oversizeSnapshot_isDroppedNotTruncated() {
        String huge = "x".repeat(AuditJson.MAX_SNAPSHOT_CHARS + 100);
        String snapshot = AuditJson.snapshot(Map.of("blob", huge), json);
        assertThat(snapshot)
                .as("★ 超过上限必须整体丢弃；截断会得到非法 JSON")
                .isNull();
    }

    @Test
    @CaseId("TC-SYS-015")
    @DisplayName("合法快照是能把字段值读回来的 JSON（可还原性的最低要求）")
    void validSnapshot_isParsable() throws Exception {
        String snapshot = AuditJson.snapshot(
                Map.of("id", 7, "name", "商品", "enabled", true), json);
        var node = json.readTree(snapshot);
        assertThat(node.path("id").asLong()).isEqualTo(7L);
        assertThat(node.path("name").asText()).isEqualTo("商品");
        assertThat(node.path("enabled").asBoolean()).isTrue();
    }
}
