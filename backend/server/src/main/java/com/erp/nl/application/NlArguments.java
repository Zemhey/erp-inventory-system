package com.erp.nl.application;

import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * NL 参数形状校验。
 *
 * <p><b>只检查"能不能交给 ApplicationService"</b>：必填、类型、枚举取值。
 * qty 是否为正、库存是否够、状态能不能推进、账期是否锁账，全部由业务层判定 ——
 * 这里重复实现等于制造第二套规则（ArchUnit 规则 9 守的是同一件事）。
 *
 * <p>所有失败都是 400 {@code NL_COMMAND_INVALID_ARGUMENT}，消息带参数名与缺失原因。
 */
final class NlArguments {

    private NlArguments() {
    }

    static String requiredString(Map<String, Object> args, String key, String label) {
        String value = optionalString(args, key);
        if (value == null || value.isBlank()) {
            throw invalid("缺少参数：%s（%s）".formatted(key, label));
        }
        return value;
    }

    static String optionalString(Map<String, Object> args, String key) {
        Object raw = args.get(key);
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? null : value;
    }

    static Long optionalLong(Map<String, Object> args, String key) {
        Object raw = args.get(key);
        if (raw == null || (raw instanceof String s && s.isBlank())) {
            return null;
        }
        if (raw instanceof Number number) {
            if (number instanceof Double || number instanceof Float) {
                double d = number.doubleValue();
                if (!Double.isFinite(d) || d != Math.rint(d)) {
                    throw invalid("参数 %s 必须是整数：%s".formatted(key, raw));
                }
            }
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException ex) {
            throw invalid("参数 %s 必须是整数：%s".formatted(key, raw));
        }
    }

    static long requiredLong(Map<String, Object> args, String key, String label) {
        Long value = optionalLong(args, key);
        if (value == null) {
            throw invalid("缺少参数：%s（%s）".formatted(key, label));
        }
        return value;
    }

    static int optionalInt(Map<String, Object> args, String key, int fallback) {
        Long value = optionalLong(args, key);
        if (value == null) {
            return fallback;
        }
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw invalid("参数 %s 超出 int 范围：%d".formatted(key, value));
        }
        return value.intValue();
    }

    static boolean optionalBoolean(Map<String, Object> args, String key, boolean fallback) {
        Object raw = args.get(key);
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Boolean b) {
            return b;
        }
        String text = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return fallback;
        }
        if ("true".equals(text) || "1".equals(text) || "yes".equals(text)) {
            return true;
        }
        if ("false".equals(text) || "0".equals(text) || "no".equals(text)) {
            return false;
        }
        throw invalid("参数 %s 必须是布尔值：%s".formatted(key, raw));
    }

    static LocalDate optionalDate(Map<String, Object> args, String key) {
        String raw = optionalString(args, key);
        if (raw == null) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException ex) {
            throw invalid("参数 %s 必须是 yyyy-MM-dd：%s".formatted(key, raw));
        }
    }

    static YearMonth optionalPeriod(Map<String, Object> args, String key) {
        String raw = optionalString(args, key);
        if (raw == null) {
            return null;
        }
        try {
            return YearMonth.parse(raw);
        } catch (DateTimeParseException ex) {
            throw invalid("参数 %s 必须是 yyyy-MM：%s".formatted(key, raw));
        }
    }

    /** 建单明细：必须是对象数组，且每个对象含 skuId/qty/unitPriceAmount。 */
    static List<Map<String, Object>> requiredLines(Map<String, Object> args, String key) {
        Object raw = args.get(key);
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw invalid("缺少参数：%s（建单明细，至少一行）".formatted(key));
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        int lineNo = 1;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw invalid("参数 %s 第 %d 行必须是对象".formatted(key, lineNo));
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> line = (Map<String, Object>) map;
            requiredLong(line, "skuId", "SKU id");
            requiredLong(line, "qty", "数量");
            requiredLong(line, "unitPriceAmount", "单价（分）");
            lines.add(line);
            lineNo++;
        }
        return lines;
    }

    static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.NL_COMMAND_INVALID_ARGUMENT, message);
    }
}
