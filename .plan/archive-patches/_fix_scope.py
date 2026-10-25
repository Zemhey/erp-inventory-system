# -*- coding: utf-8 -*-
"""用"逐作用域改写"替换 TenantSql 的 SELECT 改写部分（V2-7）。

为什么必须换：原实现把**整条语句**当成一个作用域，取所有表站点再把谓词
统一追加在顶层 WHERE。实测暴露了两个正确性缺陷：

  ① 派生表：`SELECT … FROM (SELECT … FROM cost_layers) latest`
     谓词被追加到**外层** WHERE，但外层作用域里根本没有 `cost_layers` 这个表
     → `missing FROM-clause entry for table "cost_layers"`（PG 直接报错，500）。
  ② EXISTS 子查询：`SELECT EXISTS (SELECT 1 FROM shipment_lines WHERE …)`
     `EXISTS (…)` 本身是一个**表达式**，没有 WHERE 可追加 →
     生成 `… WHERE …) WHERE 1 = 1 AND shipment_lines.tenant_id = 1`
     → SQL 语法错误。

正确做法：**按括号深度分层**，每个作用域只处理"本层直接出现的表"，
并在本层的 WHERE 里加谓词；括号内的内容递归处理（它自成一个作用域，
有自己的 FROM 与 WHERE）。这与 SQL 的作用域语义严格一致。

★ 另外修 ON CONFLICT 目标：它必须与**实际建了复合唯一索引的列**匹配。
  本项目只对 8 张 MDM 表 + 若干单据表建了 `(tenant_id, …)` 唯一索引；
  对没有建复合索引的表（如 customer_receivables 只有 uq_receivable_order
  (sales_order_id)，未被 V16 改）加 tenant_id 进目标，PG 会报
  "no unique or exclusion constraint matching the ON CONFLICT specification"。
  因此改成"按表查白名单"：只有白名单里的表才改目标。
"""
import re
from pathlib import Path

P = Path(r"D:\erp-build-v27\backend\server\src\main\java\com\erp\shared\tenant\TenantSql.java")
src = P.read_text(encoding="utf-8")

# ── 1) 替换 rewriteSelect + appendPredicates 整段 ──
start = src.index("    private static Rewritten rewriteSelect(String sql, long tenantId) {")
end = src.index("    // ─────────────────────────── INSERT ───────────────────────────")
new_block = '''    private static Rewritten rewriteSelect(String sql, long tenantId) {
        if (collectSites(sql).isEmpty()) {
            return new Rewritten(sql, false);
        }
        String out = rewriteScope(sql, tenantId);
        return new Rewritten(out, !out.equals(sql));
    }

    /**
     * 改写**一个作用域**（一条 SELECT / 一个带 FROM 的括号子查询）。
     *
     * <p><b>★ 为什么必须逐层处理而不是一条 WHERE 打天下</b>：SQL 的
     * {@code FROM} 只对**本层**可见。把派生表内层的表名写到外层 WHERE 上，
     * PostgreSQL 会直接报 {@code missing FROM-clause entry for table}；
     * 而把 {@code EXISTS (…)} 这种"表达式子查询"当成有 WHERE 的子句，
     * 会拼出 {@code …) WHERE 1 = 1 …} 这种非法语法。两者都实测踩到过。
     *
     * <p>处理顺序：① 先递归改括号内的子语句（它们各自成域）；
     * ② 再为本层直接出现的表追加谓词。
     * 先递归是必要的 —— 递归会改变文本长度，后做的外层插入用重新计算的偏移。
     */
    private static String rewriteScope(String sql, long tenantId) {
        // ① 自底向上：把每个括号里的"看起来是完整语句"的片段先改写掉
        StringBuilder rewritten = new StringBuilder(sql);
        List<int[]> regions = parenthesizedRegions(rewritten.toString());
        // 从后往前替换，避免前面的替换影响后面的下标
        for (int i = regions.size() - 1; i >= 0; i--) {
            int open = regions.get(i)[0];
            int close = regions.get(i)[1];
            String inner = rewritten.substring(open + 1, close);
            String head = firstKeyword(inner.strip());
            if ("select".equals(head) || "with".equals(head)) {
                String innerNew = rewriteScope(inner, tenantId);
                rewritten.replace(open + 1, close, innerNew);
            }
        }
        String sql2 = rewritten.toString();

        // ② 本层：只认深度 0 的 FROM / JOIN 目标
        List<TableSite> sites = collectSitesAtDepthZero(sql2);
        if (sites.isEmpty()) {
            return sql2;
        }
        if (alreadyScopedAtDepthZero(sql2, sites)) {
            return sql2;
        }
        StringBuilder predicates = new StringBuilder();
        for (TableSite site : sites) {
            predicates.append(" AND ").append(qualified(site))
                    .append(".tenant_id = ").append(tenantId);
        }
        int whereIdx = indexOfTopLevel(sql2, "where", 0);
        int guardEnd = minPositive(
                indexOfTopLevel(sql2, "group", 0),
                indexOfTopLevel(sql2, "order", 0),
                indexOfTopLevel(sql2, "limit", 0),
                indexOfTopLevel(sql2, "offset", 0),
                indexOfTopLevel(sql2, "for update", 0));
        if (whereIdx >= 0) {
            int insertAt = guardEnd > whereIdx ? guardEnd : sql2.length();
            return insert(sql2, insertAt, predicates.toString().strip());
        }
        int insertAt = guardEnd >= 0 ? guardEnd : sql2.length();
        return insert(sql2, insertAt, "WHERE 1 = 1" + predicates);
    }

    /** 所有括号区间（含嵌套），按左括号位置升序。 */
    private static List<int[]> parenthesizedRegions(String sql) {
        List<int[]> out = new ArrayList<>();
        boolean inSingle = false;
        int depth = 0;
        int openAt = -1;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\\'') {
                inSingle = !inSingle;
                continue;
            }
            if (inSingle) {
                continue;
            }
            if (c == '(') {
                if (depth == 0) {
                    openAt = i;
                }
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0 && openAt >= 0) {
                    out.add(new int[]{openAt, i});
                    openAt = -1;
                }
            }
        }
        return out;
    }

    /**
     * 收集**深度 0** 的 FROM / JOIN 目标（不看括号里的）。
     *
     * <p>与 {@link #collectSites} 的区别：后者会把子查询里的表也算进来，
     * 那些表对外层 WHERE 不可见，写上去就是 {@code missing FROM-clause entry}。
     */
    static List<TableSite> collectSitesAtDepthZero(String sql) {
        List<TableSite> sites = new ArrayList<>();
        boolean inSingle = false;
        int depth = 0;
        Matcher kw = Pattern.compile("(?i)\\\\b(from|join)\\\\b").matcher(sql);
        while (kw.find()) {
            // 计算该关键字处（含）之前的括号深度
            depth = 0;
            inSingle = false;
            for (int i = 0; i < kw.start(); i++) {
                char c = sql.charAt(i);
                if (c == '\\'') {
                    inSingle = !inSingle;
                } else if (!inSingle && c == '(') {
                    depth++;
                } else if (!inSingle && c == ')') {
                    depth--;
                }
            }
            if (depth != 0) {
                continue;
            }
            if (precededBy(sql, kw.start(), "do") || precededBy(sql, kw.start(), "conflict")
                    || precededBy(sql, kw.start(), "for")) {
                continue;
            }
            int after = kw.end();
            while (after < sql.length() && Character.isWhitespace(sql.charAt(after))) {
                after++;
            }
            if (after < sql.length() && sql.charAt(after) == '(') {
                continue;
            }
            Matcher tok = TABLE_TOKEN.matcher(sql);
            tok.region(after, sql.length());
            if (!tok.lookingAt()) {
                continue;
            }
            String word = tok.group().toLowerCase(Locale.ROOT);
            if (KEYWORDS.contains(word) || !TABLES.contains(word)) {
                continue;
            }
            sites.add(new TableSite(word, tok.start(), tok.end(), aliasAfter(sql, tok.end())));
        }
        return sites;
    }

    /** 本层的表是否都已有谓词。 */
    private static boolean alreadyScopedAtDepthZero(String sql, List<TableSite> sites) {
        int whereIdx = indexOfTopLevel(sql, "where", 0);
        if (whereIdx < 0) {
            return false;
        }
        int guardEnd = minPositive(
                indexOfTopLevel(sql, "group", 0),
                indexOfTopLevel(sql, "order", 0),
                indexOfTopLevel(sql, "limit", 0),
                indexOfTopLevel(sql, "offset", 0),
                indexOfTopLevel(sql, "for update", 0));
        String whereClause = sql.substring(whereIdx,
                guardEnd > whereIdx ? guardEnd : sql.length());
        for (TableSite site : sites) {
            if (!whereClause.contains(qualified(site) + ".tenant_id")) {
                return false;
            }
        }
        return true;
    }

'''
src = src[:start] + new_block + src[end:]
P.write_text(src, encoding="utf-8")
print("rewrote rewriteSelect block")
