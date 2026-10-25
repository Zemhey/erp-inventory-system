package com.erp.prm.repository;

import com.erp.prm.domain.Promotion;
import com.erp.prm.domain.Promotion.PromoType;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 促销活动仓储（显式列清单；理由见 {@link PricePlanRepository} 类注释）。
 *
 * <p>{@code start_time/end_time} 是 timestamptz：写入用 {@code Timestamp.valueOf}，
 * 读取用 {@code getTimestamp().toLocalDateTime()} —— pgjdbc 不支持
 * {@code getObject(col, LocalDateTime.class)} 直接读 timestamptz（V20 实测）。
 */
@Repository
public class PromotionRepository {

    private final JdbcTemplate jdbc;

    public PromotionRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public List<Promotion> page(String status, String keyword) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, code, name, promo_type, start_time, end_time, priority, status, remark
                  FROM prm_promotion WHERE 1 = 1""");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (code ILIKE ? OR name ILIKE ?)");
            args.add("%" + keyword + "%");
            args.add("%" + keyword + "%");
        }
        sql.append(" ORDER BY priority DESC, id DESC");
        List<Promotion> headers = jdbc.query(sql.toString(), (rs, rowNum) -> mapHeader(rs),
                args.toArray());
        attachRules(headers);
        return headers;
    }

    public Optional<Promotion> findById(long id) {
        Promotion header = jdbc.query("""
                        SELECT id, code, name, promo_type, start_time, end_time, priority, status, remark
                          FROM prm_promotion WHERE id = ?""",
                (ResultSet rs) -> rs.next() ? mapHeader(rs) : null, id);
        if (header == null) {
            return Optional.empty();
        }
        Map<Long, List<Promotion.Rule>> byPromo = new LinkedHashMap<>();
        loadRules(List.of(id), byPromo);
        return Optional.of(rebuild(header, byPromo.getOrDefault(id, List.of())));
    }

    public boolean existsCode(String code) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM prm_promotion WHERE code = ?",
                Long.class, code);
        return n != null && n > 0;
    }

    public long insert(Promotion p) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO prm_promotion (tenant_id, code, name, promo_type, start_time,
                                               end_time, priority, status, remark)
                    VALUES (NULL, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    new String[]{"id"});
            bind(ps, p);
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("prm_promotion 插入未返回 id");
        }
        long promoId = key.longValue();
        for (Promotion.Rule r : p.rules()) {
            insertRule(promoId, r);
        }
        return promoId;
    }

    public void replace(long id, Promotion p) {
        jdbc.update("""
                        UPDATE prm_promotion
                           SET code = ?, name = ?, promo_type = ?, start_time = ?, end_time = ?,
                               priority = ?, status = ?, remark = ?, updated_at = now()
                         WHERE id = ?""",
                p.code(), p.name(), p.promoType().name(), toTs(p.startTime()), toTs(p.endTime()),
                p.priority(), p.status(), p.remark(), id);
        jdbc.update("DELETE FROM prm_promotion_rule WHERE promotion_id = ?", id);
        for (Promotion.Rule r : p.rules()) {
            insertRule(id, r);
        }
    }

    public boolean setStatus(long id, String status) {
        return jdbc.update("""
                UPDATE prm_promotion SET status = ?, updated_at = now() WHERE id = ?
                """, status, id) > 0;
    }

    // ─────────────────────────── 内部装配 ───────────────────────────

    private void insertRule(long promotionId, Promotion.Rule r) {
        jdbc.update("""
                        INSERT INTO prm_promotion_rule (tenant_id, promotion_id, condition_json, benefit_json)
                        VALUES (NULL, ?, ?, ?)""",
                promotionId, r.conditionJson(), r.benefitJson());
    }

    private void bind(PreparedStatement ps, Promotion p) throws SQLException {
        ps.setString(1, p.code());
        ps.setString(2, p.name());
        ps.setString(3, p.promoType().name());
        ps.setTimestamp(4, toTs(p.startTime()));
        ps.setTimestamp(5, toTs(p.endTime()));
        ps.setInt(6, p.priority());
        ps.setString(7, p.status());
        ps.setString(8, p.remark());
    }

    private Promotion mapHeader(ResultSet rs) throws SQLException {
        return new Promotion(
                rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                PromoType.valueOf(rs.getString("promo_type")),
                fromTs(rs.getTimestamp("start_time")), fromTs(rs.getTimestamp("end_time")),
                rs.getInt("priority"), rs.getString("status"), rs.getString("remark"),
                List.of());
    }

    private Promotion rebuild(Promotion header, List<Promotion.Rule> rules) {
        return new Promotion(header.id(), header.code(), header.name(), header.promoType(),
                header.startTime(), header.endTime(), header.priority(), header.status(),
                header.remark(), rules);
    }

    private void attachRules(List<Promotion> promos) {
        if (promos.isEmpty()) {
            return;
        }
        Map<Long, List<Promotion.Rule>> byPromo = new LinkedHashMap<>();
        loadRules(promos.stream().map(Promotion::id).toList(), byPromo);
        for (int i = 0; i < promos.size(); i++) {
            promos.set(i, rebuild(promos.get(i), byPromo.getOrDefault(promos.get(i).id(), List.of())));
        }
    }

    private void loadRules(List<Long> promoIds, Map<Long, List<Promotion.Rule>> sink) {
        String placeholders = String.join(",", promoIds.stream().map(x -> "?").toList());
        jdbc.query("""
                        SELECT id, promotion_id, condition_json, benefit_json
                          FROM prm_promotion_rule
                         WHERE promotion_id IN (""" + placeholders + ") ORDER BY promotion_id, id",
                rs -> {
                    // ResultSetExtractor 拿到的是游标初始位：必须自己 next()
                    while (rs.next()) {
                        sink.computeIfAbsent(rs.getLong("promotion_id"), k -> new ArrayList<>())
                                .add(new Promotion.Rule(rs.getLong("id"),
                                        rs.getString("condition_json"), rs.getString("benefit_json")));
                    }
                    return null;
                }, promoIds.toArray());
    }

    private static Timestamp toTs(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private static LocalDateTime fromTs(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
