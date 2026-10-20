package com.erp.shared.document;

import com.erp.shared.time.ClockProvider;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 单据号生成器（docs/04-数据模型 §单据号，计划书 §3.2 坑点 1）。
 *
 * <p>格式：{@code <前缀><yyyyMMdd>-<4位以上流水>}，例如 {@code PO20250601-0001}。
 *
 * <p><b>为什么不能用 {@code SELECT max(no) + 1}</b>：
 * 两个并发事务会读到同一个 max 值，各自 +1，得到同一个号。
 * 有人会说"加唯一索引兜底就行" —— 那只是把重号变成了报错，
 * 用户看到的是"偶发创建失败"，而且重试也会失败。
 *
 * <p><b>正确做法是让数据库原子地分配序号</b>：
 * 每个 (前缀, 日期) 一行，用 {@code INSERT ... ON CONFLICT DO UPDATE ... RETURNING}
 * 在一条语句里"没有就建、有就加一、并返回新值"。
 * PostgreSQL 会在该行上加行锁，并发请求自然串行化 —— 不存在读后写窗口。
 *
 * <p>按天重置流水号：{@code PO20250601-0001}、{@code PO20250602-0001}。
 * 序列行由 {@code document_sequences} 表持久化，重启服务不会重号。
 */
@Component
public class DocumentNumberGenerator {

    /** 单据号前缀（与契约中的单号约定一致）。 */
    public enum DocType {
        /** 进货单 PURCHASE ORDER。 */
        PURCHASE_ORDER("PO"),
        /** 出货单 SALES ORDER。 */
        SALES_ORDER("SO"),
        /** 退货单 RETURN ORDER（M2 使用）。 */
        RETURN_ORDER("RO"),
        /** 调拨单 TRANSFER ORDER（P1 使用）。 */
        TRANSFER_ORDER("TO"),
        /** 盘点单 STOCKTAKE ORDER（P2 使用）。 */
        STOCKTAKE_ORDER("ST");

        private final String prefix;

        DocType(String prefix) {
            this.prefix = prefix;
        }

        public String prefix() {
            return prefix;
        }
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);
    private static final int PAD_WIDTH = 4;

    private final JdbcTemplate jdbc;
    private final ClockProvider clock;

    public DocumentNumberGenerator(JdbcTemplate jdbc, ClockProvider clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** 用业务时钟的"今天"取号。 */
    public String next(DocType type) {
        return next(type, clock.today());
    }

    /**
     * 按指定日期取号（补录历史单据时用）。
     *
     * <p>注意：这里的原子性只覆盖<b>取号</b>本身。
     * 由于取号发生在业务事务内，若该事务随后回滚，号会被"用掉"（出现空号）。
     * 这是<b>刻意接受的权衡</b>：空号无害（单号只需唯一，不需连续），
     * 而"为了号连续而跨事务取号"会让并发重号重新出现。
     */
    public String next(DocType type, LocalDate bizDate) {
        String dayKey = bizDate.format(DAY);
        Long seq = jdbc.queryForObject("""
                INSERT INTO document_sequences (doc_prefix, day_key, current_no)
                VALUES (?, ?, 1)
                ON CONFLICT (doc_prefix, day_key)
                DO UPDATE SET current_no = document_sequences.current_no + 1,
                              updated_at = now()
                RETURNING current_no
                """, Long.class, type.prefix(), dayKey);
        if (seq == null) {
            throw new IllegalStateException(
                    "取号失败：document_sequences 未返回序号 prefix=%s day=%s"
                            .formatted(type.prefix(), dayKey));
        }
        String serial = String.format(Locale.ROOT, "%0" + PAD_WIDTH + "d", seq);
        return type.prefix() + dayKey + "-" + serial;
    }
}
