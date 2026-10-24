package com.erp.shared.tenant;

/**
 * 数一条 SQL 里有多少个 {@code ?}（测试辅助）。
 *
 * <p>存在的理由很具体：{@link TenantSql} 用<b>字面量</b>注入租户，
 * 而不是追加占位符。这正是"19 处 {@code ps.setXxx(1..n)} 不用改下标"的前提。
 * 没有这条辅助，"占位符个数不变"只能靠人眼数 —— 而人眼数正是那一类
 * 静默缺陷的来源。
 */
final class TenantJdbcPlaceholderCounter {

    private TenantJdbcPlaceholderCounter() {
    }

    /** 统计 {@code ?} 的个数（引号内的不算）。 */
    static int count(String sql) {
        int n = 0;
        boolean inSingle = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                inSingle = !inSingle;
                continue;
            }
            if (!inSingle && c == '?') {
                n++;
            }
        }
        return n;
    }
}
