package com.erp.sys.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.support.CaseId;
import com.erp.sys.domain.AuditAction;
import com.erp.sys.domain.AuditResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 业务指标记录器（FR-SYS-09）的纯单测。
 *
 * <p>用 {@link SimpleMeterRegistry} 而不是起 Spring 容器：指标是纯逻辑，
 * 起容器只会让这些用例慢 100 倍，并且互相污染全局注册表。
 */
@DisplayName("指标 · 业务指标记录")
class MetricsRecorderTest {

    private MeterRegistry registry;
    private MetricsRecorder metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new MetricsRecorder(registry);
    }

    @Test
    @CaseId("TC-SYS-016")
    @DisplayName("CREATE + SUCCESS 计入下单数（orderType 按资源类型映射）")
    void createSuccess_incrementsOrderCreated() {
        metrics.observeWrite("PURCHASE_ORDER", AuditAction.CREATE, AuditResult.SUCCESS, null);
        assertThat(metrics.counterValue("erp.order.placed", "orderType", "purchase"))
                .isEqualTo(1d);
    }

    @Test
    @CaseId("TC-SYS-017")
    @DisplayName("★ 审核/入库不计入下单数（否则经营看板单量虚高）")
    void nonCreateActions_doNotCountAsOrders() {
        metrics.observeWrite("PURCHASE_ORDER", AuditAction.APPROVE, AuditResult.SUCCESS, null);
        metrics.observeWrite("PURCHASE_ORDER", AuditAction.RECEIVE, AuditResult.SUCCESS, null);
        assertThat(metrics.counterValue("erp.order.placed", "orderType", "purchase"))
                .as("★ 只有 CREATE 才是「下了一张单」")
                .isZero();
    }

    @Test
    @CaseId("TC-SYS-018")
    @DisplayName("失败数按 errorCode 分维度累加")
    void failIncrementsByErrorCode() {
        metrics.observeWrite("SALES_ORDER", AuditAction.CREATE, AuditResult.FAIL,
                "INV_INSUFFICIENT_STOCK");
        metrics.observeWrite("SALES_ORDER", AuditAction.CREATE, AuditResult.FAIL,
                "INV_INSUFFICIENT_STOCK");
        assertThat(metrics.counterValue("erp.order.failed", "orderType", "sales",
                "errorCode", "INV_INSUFFICIENT_STOCK")).isEqualTo(2d);
    }

    @Test
    @CaseId("TC-SYS-019")
    @DisplayName("errorCode 为 null 时归到 UNKNOWN 维度（不能让指标因为 null 崩掉）")
    void nullErrorCode_mapsToUnknown() {
        metrics.observeWrite("PRODUCT", AuditAction.UPDATE, AuditResult.FAIL, null);
        assertThat(metrics.counterValue("erp.order.failed", "orderType", "product",
                "errorCode", "UNKNOWN")).isEqualTo(1d);
    }

    @Test
    @CaseId("TC-SYS-020")
    @DisplayName("未知资源类型归到 other 维度")
    void unknownResourceType_mapsToOther() {
        metrics.observeWrite("WIDGET", AuditAction.CREATE, AuditResult.SUCCESS, null);
        assertThat(metrics.counterValue("erp.order.placed", "orderType", "other"))
                .isEqualTo(1d);
    }

    @Test
    @CaseId("TC-SYS-021")
    @DisplayName("审计写入计数按 action/result 分维度")
    void auditWritten_isTagged() {
        metrics.auditWritten(AuditAction.CREATE, AuditResult.SUCCESS);
        assertThat(metrics.counterValue("erp.audit.written",
                "action", "CREATE", "result", "SUCCESS")).isEqualTo(1d);
    }

    @Test
    @CaseId("TC-SYS-022")
    @DisplayName("审计写入失败计数可读（应恒为 0，非 0 即告警）")
    void auditWriteFailed_isCounted() {
        metrics.auditWriteFailed(AuditAction.UPDATE);
        assertThat(metrics.counterValue("erp.audit.write.failed", "action", "UPDATE"))
                .isEqualTo(1d);
    }

    @Test
    @CaseId("TC-SYS-023")
    @DisplayName("★ 对账差额取绝对值：-1234 → 1234（差额方向不改变「差多少」的含义）")
    void reconcileDiff_isAbsolute() {
        metrics.reconcileDiff(-1234);
        assertThat(metrics.gaugeValue("erp.reconcile.diff.abs")).isEqualTo(1234d);
        metrics.reconcileDiff(500);
        assertThat(metrics.gaugeValue("erp.reconcile.diff.abs")).isEqualTo(500d);
    }

    @Test
    @CaseId("TC-SYS-024")
    @DisplayName("重复注册对账 gauge 不抛异常（Gauge 幂等注册）")
    void reconcileDiff_registrationIsIdempotent() {
        metrics.reconcileDiff(10);
        metrics.reconcileDiff(20);
        assertThat(metrics.gaugeValue("erp.reconcile.diff.abs")).isEqualTo(20d);
    }

    @Test
    @CaseId("TC-SYS-025")
    @DisplayName("containsMetric 能识别已注册的指标（测试断言用的自检入口）")
    void containsMetric_works() {
        metrics.auditWritten(AuditAction.CREATE, AuditResult.SUCCESS);
        assertThat(metrics.containsMetric("erp.audit.written")).isTrue();
        assertThat(metrics.containsMetric("erp.nonexistent.metric")).isFalse();
    }

    @Test
    @CaseId("TC-SYS-026")
    @DisplayName("指标读不到时返回 0 而不是 null（避免测试里到处判空）")
    void missingCounter_returnsZero() {
        assertThat(metrics.counterValue("erp.not.there")).isZero();
        assertThat(metrics.gaugeValue("erp.not.there")).isZero();
    }

    @Test
    @CaseId("TC-SYS-027")
    @DisplayName("forTests 工厂可直接构造（不依赖 Spring 的单元用例入口）")
    void forTests_buildsUsableRecorder() {
        MetricsRecorder standalone = MetricsRecorder.forTests();
        standalone.observeWrite("PRODUCT", AuditAction.CREATE, AuditResult.SUCCESS, null);
        assertThat(standalone.counterValue("erp.order.placed", "orderType", "product"))
                .isEqualTo(1d);
    }
}
