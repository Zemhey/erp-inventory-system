package com.erp.rpt.api;

import com.erp.rpt.domain.AgingType;
import com.erp.rpt.domain.ReportGroupBy;
import com.erp.rpt.domain.ReportPeriodDim;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 报表请求参数的统一解析（页面查询与 CSV 导出共用）。
 *
 * <p>枚举字段一律手工解析：契约取值是小写（{@code supplier/day/AR}），
 * Java 常量是大写；交给 Spring 就要赌转换器的大小写策略，还会把
 * "groupBy 拼成报表名"这种常见错误报成难定位的类型转换异常。
 */
final class ReportRequestParams {

    static final EnumSet<ReportGroupBy> PURCHASE_DIMS = EnumSet.of(
            ReportGroupBy.SUPPLIER, ReportGroupBy.PRODUCT, ReportGroupBy.SKU,
            ReportGroupBy.DAY, ReportGroupBy.MONTH);

    static final EnumSet<ReportGroupBy> SALES_DIMS = EnumSet.of(
            ReportGroupBy.CUSTOMER, ReportGroupBy.PRODUCT, ReportGroupBy.SKU,
            ReportGroupBy.DAY, ReportGroupBy.MONTH);

    private ReportRequestParams() {
    }

    static ReportGroupBy parseGroupBy(String raw, ReportGroupBy fallback,
                                      EnumSet<ReportGroupBy> allowed) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        ReportGroupBy parsed;
        try {
            parsed = ReportGroupBy.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw invalidGroupBy(raw, allowed);
        }
        if (!allowed.contains(parsed)) {
            throw invalidGroupBy(raw, allowed);
        }
        return parsed;
    }

    /** 账期格式 {@code yyyy-MM}（契约 pattern），非法即 400。 */
    static YearMonth parsePeriod(String raw) {
        try {
            return YearMonth.parse(raw.trim());
        } catch (DateTimeParseException | NullPointerException ex) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "period 格式非法：%s（要求 yyyy-MM，例如 2026-10）".formatted(raw));
        }
    }

    /** 经营报表时间粒度，缺省 {@code day}。 */
    static ReportPeriodDim parseDimension(String raw) {
        if (raw == null || raw.isBlank()) {
            return ReportPeriodDim.DAY;
        }
        try {
            return ReportPeriodDim.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "dimension 取值非法：%s（允许 day | month）".formatted(raw));
        }
    }

    /** 账龄类型 AR / AP，必填。 */
    static AgingType parseAgingType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "type 不能为空（允许 AR | AP）");
        }
        try {
            return AgingType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "type 取值非法：%s（允许 AR | AP）".formatted(raw));
        }
    }

    private static BusinessException invalidGroupBy(String raw,
                                                    EnumSet<ReportGroupBy> allowed) {
        return new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                "groupBy 取值非法：%s（本报表允许 %s）".formatted(raw, describe(allowed)));
    }

    private static String describe(EnumSet<ReportGroupBy> allowed) {
        return allowed.stream()
                .map(d -> d.name().toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(" | "));
    }
}
