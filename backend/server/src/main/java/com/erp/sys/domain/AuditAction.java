package com.erp.sys.domain;

import java.util.Locale;
import java.util.Map;

/**
 * 审计动作语义（FR-SYS-06）。
 *
 * <p><b>为什么不直接存 HTTP 方法</b>：{@code POST} 在本系统里至少表达四种完全不同的业务
 * 语义 —— 建单、提交、审核、入库。审计查询要回答的是"谁审核了这张单"，
 * 而不是"谁发了一个 POST"，所以动作必须按<b>路径语义</b>归一化。
 *
 * <p>枚举刻意做成<b>闭合集合</b>：新增动作必须改这里，ArchUnit 与单元用例会逼着
 * 实现者确认"这个新动作确实被审计覆盖了"，而不是悄悄多出一个没人认识的字符串。
 */
public enum AuditAction {

    CREATE,
    UPDATE,
    DELETE,
    DISABLE,
    SUBMIT,
    APPROVE,
    REJECT,
    VOID,
    RECEIVE,
    SHIP,
    RESERVE,
    RELEASE,
    SETTLE_DAILY,
    SETTLE_MONTHLY,
    LOGIN,
    COMMAND,
    /** 兜底：能到这里说明动作命名没跟上，但审计仍然留痕（宁可质量差也不能丢）。 */
    OTHER;

    /** 末段路径 → 动作。顺序无意义，整段精确匹配（避免 {@code receive} 误吞 {@code receiver}）。 */
    private static final Map<String, AuditAction> BY_PATH_SUFFIX = Map.ofEntries(
            Map.entry("submit", SUBMIT),
            Map.entry("approve", APPROVE),
            Map.entry("reject", REJECT),
            Map.entry("void", VOID),
            Map.entry("receive", RECEIVE),
            Map.entry("ship", SHIP),
            Map.entry("disable", DISABLE),
            Map.entry("reserve", RESERVE),
            Map.entry("release", RELEASE),
            Map.entry("daily", SETTLE_DAILY),
            Map.entry("monthly", SETTLE_MONTHLY),
            Map.entry("login", LOGIN),
            Map.entry("command", COMMAND));

    /**
     * 由 HTTP 方法与路径推导动作。
     *
     * <p>路径末段的动作词优先于 HTTP 方法：{@code POST /products/{id}/disable} 的业务语义是
     * "停用"，按 {@code POST → CREATE} 归类会让审计查询得出"有人创建了商品"的错误结论。
     *
     * @param httpMethod 请求方法（大小写不敏感）
     * @param path       请求路径（可带或不带 {@code /api/v1} 前缀）
     */
    public static AuditAction of(String httpMethod, String path) {
        String lastSegment = lastSegment(path);
        AuditAction bySuffix = lastSegment == null ? null : BY_PATH_SUFFIX.get(lastSegment);
        if (bySuffix != null) {
            return bySuffix;
        }
        String method = httpMethod == null ? "" : httpMethod.toUpperCase(Locale.ROOT);
        return switch (method) {
            case "POST" -> CREATE;
            case "PUT", "PATCH" -> UPDATE;
            case "DELETE" -> DELETE;
            default -> OTHER;
        };
    }

    /** 取路径末段；末段是纯数字（{@code /products/42}）时视为"没有动作词"，返回 null。 */
    private static String lastSegment(String path) {
        if (path == null) {
            return null;
        }
        String trimmed = path.endsWith("/") && path.length() > 1
                ? path.substring(0, path.length() - 1)
                : path;
        int slash = trimmed.lastIndexOf('/');
        String last = slash < 0 ? trimmed : trimmed.substring(slash + 1);
        if (last.isEmpty() || last.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return last;
    }
}
