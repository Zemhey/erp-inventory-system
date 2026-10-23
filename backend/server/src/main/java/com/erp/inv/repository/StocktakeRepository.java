package com.erp.inv.repository;

import com.erp.inv.domain.Stocktake;
import com.erp.inv.domain.StocktakeLine;
import com.erp.inv.domain.StocktakeStatus;
import com.erp.shared.web.PageResult;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 盘点单仓储（FR-INV-08）。 */
@Repository
public class StocktakeRepository {

    private final JdbcTemplate jdbc;

    public StocktakeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<StocktakeLine> LINE_MAPPER = (rs, i) -> new StocktakeLine(
            rs.getLong("id"),
            rs.getLong("stocktake_id"),
            rs.getInt("line_no"),
            rs.getLong("sku_id"),
            rs.getString("batch_no"),
            rs.getInt("book_qty"),
            rs.getInt("counted_qty"),
            rs.getInt("diff_qty"));

    private record Header(long id, String stocktakeNo, long warehouseId, StocktakeStatus status,
                          LocalDate bizDate, String remark, OffsetDateTime postedAt,
                          OffsetDateTime createdAt, String requestId, String postRequestId) {
    }

    private static final RowMapper<Header> HEADER_MAPPER = (rs, i) -> new Header(
            rs.getLong("id"),
            rs.getString("stocktake_no"),
            rs.getLong("warehouse_id"),
            StocktakeStatus.valueOf(rs.getString("status")),
            rs.getObject("biz_date", LocalDate.class),
            rs.getString("remark"),
            rs.getObject("posted_at", OffsetDateTime.class),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getString("request_id"),
            rs.getString("post_request_id"));

    public Optional<Stocktake> findById(long id) {
        return findHeader("WHERE id = ?", id).map(this::withLines);
    }

    public Optional<Stocktake> findByRequestId(String requestId) {
        return findHeader("WHERE request_id = ?", requestId).map(this::withLines);
    }

    public String postRequestId(long id) {
        List<String> rows = jdbc.queryForList(
                "SELECT post_request_id FROM stocktakes WHERE id = ?", String.class, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Optional<Header> findHeader(String where, Object arg) {
        List<Header> rows = jdbc.query("""
                SELECT id, stocktake_no, warehouse_id, status, biz_date, remark,
                       posted_at, created_at, request_id, post_request_id
                  FROM stocktakes
                """ + where, HEADER_MAPPER, arg);
        return rows.stream().findFirst();
    }

    private Stocktake withLines(Header h) {
        return new Stocktake(h.id(), h.stocktakeNo(), h.warehouseId(), h.status(), h.bizDate(),
                h.remark(), h.postedAt(), h.createdAt(), lines(h.id()));
    }

    public List<StocktakeLine> lines(long stocktakeId) {
        return jdbc.query("""
                SELECT id, stocktake_id, line_no, sku_id, batch_no, book_qty, counted_qty, diff_qty
                  FROM stocktake_lines
                 WHERE stocktake_id = ?
                 ORDER BY line_no
                """, LINE_MAPPER, stocktakeId);
    }

    /** 插入盘点单头；同一 requestId 重复建单返回空（INV-9）。 */
    public Optional<Long> insertHeader(String stocktakeNo, long warehouseId, LocalDate bizDate,
                                       String remark, String requestId) {
        List<Long> ids = jdbc.queryForList("""
                INSERT INTO stocktakes
                    (stocktake_no, warehouse_id, status, biz_date, remark, request_id)
                VALUES (?, ?, 'DRAFT', ?, ?, ?)
                ON CONFLICT (request_id) WHERE request_id IS NOT NULL DO NOTHING
                RETURNING id
                """, Long.class, stocktakeNo, warehouseId, bizDate, remark, requestId);
        return ids.stream().findFirst();
    }

    public void insertLine(long stocktakeId, int lineNo, long skuId, String batchNo,
                           int bookQty, int countedQty) {
        jdbc.update("""
                INSERT INTO stocktake_lines
                    (stocktake_id, line_no, sku_id, batch_no, book_qty, counted_qty)
                VALUES (?, ?, ?, ?, ?, ?)
                """, stocktakeId, lineNo, skuId, batchNo, bookQty, countedQty);
    }

    /**
     * 标记已过账（DRAFT → POSTED）。
     *
     * <p>条件更新确保并发下只有一个请求成功；随后才写调整流水，
     * 任一行失败由事务整体回滚，状态一并退回 DRAFT。
     */
    public boolean markPosted(long id, String postRequestId, LocalDateTime postedAt) {
        return jdbc.update("""
                UPDATE stocktakes
                   SET status = 'POSTED', post_request_id = ?, posted_at = ?, updated_at = now()
                 WHERE id = ? AND status = 'DRAFT'
                """, postRequestId, postedAt, id) == 1;
    }

    /** 建单时读取某批次的账面量；余额行不存在则为 0。 */
    public int bookQty(long skuId, long warehouseId, String batchNo) {
        Integer v = jdbc.queryForObject("""
                SELECT COALESCE(MAX(on_hand_qty), 0) FROM inventory_balances
                 WHERE sku_id = ? AND warehouse_id = ? AND batch_no = ?
                """, Integer.class, skuId, warehouseId, batchNo);
        return v == null ? 0 : v;
    }

    public PageResult<Stocktake> page(StocktakeStatus status, Long warehouseId, int page, int size) {
        int p = PageResult.normalizePage(page);
        int s = PageResult.normalizeSize(size);
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (status != null) {
            where.append(" AND status = ?");
            args.add(status.name());
        }
        if (warehouseId != null) {
            where.append(" AND warehouse_id = ?");
            args.add(warehouseId);
        }
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stocktakes" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add(PageResult.offset(p, s));
        List<Header> headers = jdbc.query("""
                SELECT id, stocktake_no, warehouse_id, status, biz_date, remark,
                       posted_at, created_at, request_id, post_request_id
                  FROM stocktakes
                """ + where + " ORDER BY id DESC LIMIT ? OFFSET ?", HEADER_MAPPER, pageArgs.toArray());
        List<Stocktake> items = headers.stream().map(this::withLines).toList();
        return new PageResult<>(items, total == null ? 0 : total, p, s);
    }
}
