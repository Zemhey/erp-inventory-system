package com.erp.sys.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.support.CaseId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 审计动作归类规则（FR-SYS-06）的纯单测。
 *
 * <p><b>为什么这段逻辑值得单独测</b>：动作归类决定了审计查询能不能回答问题。
 * 把 {@code POST /products/{id}/disable} 归成 {@code CREATE}，
 * 会让"谁停用了商品"这个查询永远查不到东西 —— 而数据<b>看起来是有的</b>，
 * 只是归错了类。这种缺陷不会让任何接口报错，只能靠穷举映射的用例发现。
 */
@DisplayName("审计 · 动作归类")
class AuditActionTest {

    @ParameterizedTest(name = "{0} {1} → {2}")
    @CsvSource({
            // 路径末段的动作词优先于 HTTP 方法
            "POST,   /api/v1/products/42/disable,          DISABLE",
            "POST,   /api/v1/purchase-orders/7/submit,     SUBMIT",
            "POST,   /api/v1/purchase-orders/7/approve,    APPROVE",
            "POST,   /api/v1/purchase-orders/7/reject,     REJECT",
            "POST,   /api/v1/purchase-orders/7/void,       VOID",
            "POST,   /api/v1/purchase-orders/7/receive,    RECEIVE",
            "POST,   /api/v1/sales-orders/9/ship,          SHIP",
            "POST,   /api/v1/inventory/reserve,            RESERVE",
            "POST,   /api/v1/inventory/reservations/3/release, RELEASE",
            "POST,   /api/v1/settlements/daily,            SETTLE_DAILY",
            "POST,   /api/v1/settlements/monthly,          SETTLE_MONTHLY",
            "POST,   /api/v1/auth/login,                   LOGIN",
            "POST,   /api/v1/nl/command,                   COMMAND"
    })
    @CaseId("TC-SYS-069")
    @DisplayName("路径动作词优先：POST /xxx/{id}/disable → DISABLE 而不是 CREATE")
    void pathSuffix_winsOverHttpMethod(String method, String path, String expected) {
        assertThat(AuditAction.of(method, path).name()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} {1} → {2}")
    @CsvSource({
            "POST,   /api/v1/products,      CREATE",
            "POST,   /api/v1/sales-orders,  CREATE",
            "PUT,    /api/v1/products/42,   UPDATE",
            "PATCH,  /api/v1/products/42,   UPDATE",
            "DELETE, /api/v1/products/42,   DELETE"
    })
    @CaseId("TC-SYS-070")
    @DisplayName("无动作词时按 HTTP 方法归类：POST→CREATE / PUT→UPDATE / DELETE→DELETE")
    void httpMethod_usedWhenNoSuffix(String method, String path, String expected) {
        assertThat(AuditAction.of(method, path).name()).isEqualTo(expected);
    }

    @Test
    @CaseId("TC-SYS-071")
    @DisplayName("末段是纯数字时视为无动作词（/products/42 → CREATE/UPDATE，不是 OTHER）")
    void numericLastSegment_isNotAnActionWord() {
        assertThat(AuditAction.of("POST", "/api/v1/products/42")).isEqualTo(AuditAction.CREATE);
        assertThat(AuditAction.of("PUT", "/api/v1/products/42")).isEqualTo(AuditAction.UPDATE);
    }

    @Test
    @CaseId("TC-SYS-072")
    @DisplayName("无前缀路径与尾斜杠同样归类正确（切面拿到的 path 可能带不带 context-path）")
    void pathForms_areTolerated() {
        assertThat(AuditAction.of("POST", "/products/42/disable")).isEqualTo(AuditAction.DISABLE);
        assertThat(AuditAction.of("POST", "/api/v1/products/42/disable/"))
                .isEqualTo(AuditAction.DISABLE);
    }

    @Test
    @CaseId("TC-SYS-073")
    @DisplayName("方法名大小写不敏感；null 方法与 null 路径都退化为 OTHER 而不抛异常")
    void nullAndCase_areSafe() {
        assertThat(AuditAction.of("post", "/api/v1/products")).isEqualTo(AuditAction.CREATE);
        assertThat(AuditAction.of(null, "/api/v1/products")).isEqualTo(AuditAction.OTHER);
        assertThat(AuditAction.of("GET", "/api/v1/products")).isEqualTo(AuditAction.OTHER);
        assertThat(AuditAction.of("POST", null)).isEqualTo(AuditAction.CREATE);
    }

    @Test
    @CaseId("TC-SYS-074")
    @DisplayName("未识别的动作词退化为 OTHER（宁可归类差，也不能丢审计）")
    void unknownSuffix_fallsBackGracefully() {
        assertThat(AuditAction.of("POST", "/api/v1/products/42/reactivate"))
                .isEqualTo(AuditAction.CREATE);
        assertThat(AuditAction.of("GET", "/api/v1/products")).isEqualTo(AuditAction.OTHER);
    }
}
