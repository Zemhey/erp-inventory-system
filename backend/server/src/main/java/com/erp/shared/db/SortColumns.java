package com.erp.shared.db;

import java.util.Map;

/**
 * 排序子句白名单（供"用户指定排序"的查询使用）。
 *
 * <p><b>为什么要有这个类</b>：自然语言里用户会说"按时间排序"，这个"时间"必须
 * 变成 SQL 的 {@code ORDER BY biz_date}。但 <b>用户可控的字符串绝不能拼进 SQL</b> ——
 * {@code ORDER BY} 不支持参数占位符，一旦把原话拼进去就是注入面。
 * 因此这里只做"键→列名"的映射：键不在白名单里，一律退回默认列，
 * 用户输入永远不会成为 SQL 文本的一部分。
 *
 * <p><b>不做"猜"</b>：{@code "date"} 这类别名也不放行。放行的每一列都要有人想过
 * "这列能不能给用户按它排"（按金额排序本身没问题，但按内部 id 排会暴露数据量级）。
 */
public final class SortColumns {

    private SortColumns() {
    }

    /**
     * @param requested      用户/模型给的排序键（可为 null）
     * @param ascending      升序还是降序
     * @param allowed        白名单：键 → 真实列名
     * @param fallbackColumn 键非法时使用的列名（必须是本方法调用方自己写死的常量）
     * @return 形如 {@code biz_date ASC} 的片段，**可安全拼接**
     */
    public static String orderBy(String requested, boolean ascending,
                                 Map<String, String> allowed, String fallbackColumn) {
        String column = requested == null
                ? null
                : allowed.get(requested.trim().toLowerCase());
        if (column == null) {
            column = fallbackColumn;
        }
        return column + (ascending ? " ASC" : " DESC");
    }

    /**
     * 只解析"升/降"，默认降序。
     * 之所以默认 DESC：单据列表的时间与金额排序，用户心里想的几乎都是"最近的/最大的在前"。
     */
    public static boolean ascending(String direction) {
        return direction != null && "asc".equalsIgnoreCase(direction.trim());
    }
}
