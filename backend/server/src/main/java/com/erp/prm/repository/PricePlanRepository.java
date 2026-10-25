package com.erp.prm.repository;

import com.erp.prm.domain.PricePlan;
import com.erp.prm.domain.PricePlan.PlanType;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
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
 * 价格方案仓储。
 *
 * <p><b>为什么全部用显式列清单而不是 SimpleJdbcInsert</b>（V20 的坑）：
 * SimpleJdbcInsert 会把表结构里的每一列都绑进 INSERT，未赋值列一律绑 null，
 * 顶掉 {@code created_at/updated_at} 的 {@code DEFAULT now()} → not-null 冲突。
 *
 * <p><b>timestamptz 读取</b>：本表组业务上只需要 DATE（start/end）与
 * 记录时间；DATE 用 {@code getObject(col, LocalDate.class)}（pgjdbc 支持），
 * timestamptz 一律不读（域对象不带 created_at，展示层用不到）。
 */
@Repository
public class PricePlanRepository {

    private final JdbcTemplate jdbc;

    public PricePlanRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** 方案列表（不带明细行；页面表格里明细另有详情接口）。 */
    public List<PricePlan> page(String keyword) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, code, name, plan_type, customer_id, customer_level, priority,
                       start_date, end_date, enabled, remark
                  FROM prm_price_plan""");
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" WHERE (code ILIKE ? OR name ILIKE ?)");
            args.add("%" + keyword + "%");
            args.add("%" + keyword + "%");
        }
        sql.append(" ORDER BY priority DESC, id DESC");
        List<PricePlan> plans = jdbc.query(sql.toString(), (rs, rowNum) -> mapPlanHeader(rs),
                args.toArray());
        attachItems(plans);
        return plans;
    }

    public Optional<PricePlan> findById(long id) {
        PricePlan plan = jdbc.query("""
                        SELECT id, code, name, plan_type, customer_id, customer_level, priority,
                               start_date, end_date, enabled, remark
                          FROM prm_price_plan WHERE id = ?""",
                (rs, rowNum) -> mapPlanHeader(rs), id)
                .stream().findFirst().orElse(null);
        if (plan == null) {
            return Optional.empty();
        }
        return Optional.of(withItems(plan));
    }

    public boolean existsCode(String code) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM prm_price_plan WHERE code = ?",
                Long.class, code);
        return n != null && n > 0;
    }

    public long insert(PricePlan plan) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO prm_price_plan (tenant_id, code, name, plan_type, customer_id,
                                                customer_level, priority, start_date, end_date,
                                                enabled, remark)
                    VALUES (NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    new String[]{"id"});
            bindHeader(ps, plan);
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("prm_price_plan 插入未返回 id");
        }
        long planId = key.longValue();
        for (PricePlan.Item item : plan.items()) {
            insertItem(planId, item);
        }
        return planId;
    }

    /** 全量替换语义：头字段 UPDATE + 明细先删后插（页面保存即最终状态）。 */
    public void replace(long id, PricePlan plan) {
        jdbc.update("""
                        UPDATE prm_price_plan
                           SET code = ?, name = ?, plan_type = ?, customer_id = ?,
                               customer_level = ?, priority = ?, start_date = ?, end_date = ?,
                               enabled = ?, remark = ?, updated_at = now()
                         WHERE id = ?""",
                plan.code(), plan.name(), plan.planType().name(), plan.customerId(),
                plan.customerLevel(), plan.priority(),
                plan.startDate(), plan.endDate(), plan.enabled(), plan.remark(), id);
        jdbc.update("DELETE FROM prm_price_plan_item WHERE plan_id = ?", id);
        for (PricePlan.Item item : plan.items()) {
            insertItem(id, item);
        }
    }

    public boolean setEnabled(long id, boolean enabled) {
        return jdbc.update("""
                UPDATE prm_price_plan SET enabled = ?, updated_at = now() WHERE id = ?
                """, enabled, id) > 0;
    }

    /**
     * 取价候选：命中 sku、方案启用、日期窗口覆盖 {@code day} 的全部明细行。
     * 客户/等级匹配与数量门槛过滤放在应用服务里（领域规则不进 SQL）。
     */
    public List<PriceHit> findPricedCandidates(long skuId, LocalDate day) {
        return jdbc.query("""
                        SELECT p.id, p.code, p.name, p.plan_type, p.customer_id, p.customer_level,
                               p.priority, i.price_amount, i.discount_bp, i.min_qty
                          FROM prm_price_plan p
                          JOIN prm_price_plan_item i ON i.plan_id = p.id
                         WHERE i.sku_id = ? AND p.enabled = TRUE
                           AND (p.start_date IS NULL OR p.start_date <= ?)
                           AND (p.end_date IS NULL OR p.end_date >= ?)
                        """,
                (rs, rowNum) -> new PriceHit(
                        rs.getLong("id"), rs.getString("code"), PlanType.valueOf(rs.getString("plan_type")),
                        (Long) rs.getObject("customer_id"), rs.getString("customer_level"),
                        rs.getInt("priority"), rs.getLong("price_amount"),
                        rs.getInt("discount_bp"), rs.getInt("min_qty")),
                skuId, java.sql.Date.valueOf(day), java.sql.Date.valueOf(day));
    }

    /** 取价命中行（方案头关键字段 + 明细价格）。 */
    public record PriceHit(long planId, String planCode, PlanType planType, Long customerId,
                           String customerLevel, int priority, long priceAmount, int discountBp,
                           int minQty) {

        public long effectivePrice() {
            return (Math.multiplyExact(priceAmount, discountBp) + 5000) / 10000;
        }
    }

    /** 客户等级（取价要用 customers.level；直查避免 prm → mdm 仓储的跨模块耦合面）。 */
    public Optional<String> findCustomerLevel(long customerId) {
        return jdbc.query("SELECT level FROM customers WHERE id = ?",
                rs -> rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.<String>empty(),
                customerId);
    }

    /** 商品标准售价（分）；sku 不存在返回空。 */
    public Optional<Long> findStandardRetail(long skuId) {
        return jdbc.query("SELECT retail_price_amount FROM skus WHERE id = ? AND enabled = TRUE",
                rs -> rs.next() ? Optional.of(rs.getLong(1)) : Optional.<Long>empty(),
                skuId);
    }

    // ─────────────────────────── 内部装配 ───────────────────────────

    private void insertItem(long planId, PricePlan.Item item) {
        jdbc.update("""
                        INSERT INTO prm_price_plan_item (tenant_id, plan_id, sku_id, price_amount,
                                                         discount_bp, min_qty)
                        VALUES (NULL, ?, ?, ?, ?, ?)""",
                planId, item.skuId(), item.priceAmount(), item.discountBp(), item.minQty());
    }

    private void bindHeader(PreparedStatement ps, PricePlan plan) throws SQLException {
        ps.setString(1, plan.code());
        ps.setString(2, plan.name());
        ps.setString(3, plan.planType().name());
        if (plan.customerId() == null) {
            ps.setNull(4, java.sql.Types.BIGINT);
        } else {
            ps.setLong(4, plan.customerId());
        }
        ps.setString(5, plan.customerLevel());
        ps.setInt(6, plan.priority());
        ps.setObject(7, plan.startDate());
        ps.setObject(8, plan.endDate());
        ps.setBoolean(9, plan.enabled());
        ps.setString(10, plan.remark());
    }

    private PricePlan mapPlanHeader(ResultSet rs) throws SQLException {
        return new PricePlan(
                rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                PlanType.valueOf(rs.getString("plan_type")),
                (Long) rs.getObject("customer_id"), rs.getString("customer_level"),
                rs.getInt("priority"),
                rs.getObject("start_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class),
                rs.getBoolean("enabled"), rs.getString("remark"),
                List.of());
    }

    private PricePlan withItems(PricePlan header) {
        Map<Long, List<PricePlan.Item>> byPlan = new LinkedHashMap<>();
        loadItems(List.of(header.id()), byPlan);
        return new PricePlan(header.id(), header.code(), header.name(), header.planType(),
                header.customerId(), header.customerLevel(), header.priority(),
                header.startDate(), header.endDate(), header.enabled(), header.remark(),
                byPlan.getOrDefault(header.id(), List.of()));
    }

    private void attachItems(List<PricePlan> plans) {
        if (plans.isEmpty()) {
            return;
        }
        Map<Long, List<PricePlan.Item>> byPlan = new LinkedHashMap<>();
        loadItems(plans.stream().map(PricePlan::id).toList(), byPlan);
        for (int i = 0; i < plans.size(); i++) {
            PricePlan p = plans.get(i);
            plans.set(i, new PricePlan(p.id(), p.code(), p.name(), p.planType(), p.customerId(),
                    p.customerLevel(), p.priority(), p.startDate(), p.endDate(), p.enabled(),
                    p.remark(), byPlan.getOrDefault(p.id(), List.of())));
        }
    }

    private void loadItems(List<Long> planIds, Map<Long, List<PricePlan.Item>> sink) {
        String placeholders = String.join(",", planIds.stream().map(x -> "?").toList());
        jdbc.query("""
                        SELECT id, plan_id, sku_id, price_amount, discount_bp, min_qty
                          FROM prm_price_plan_item
                         WHERE plan_id IN (""" + placeholders + ") ORDER BY plan_id, sku_id",
                rs -> {
                    // ResultSetExtractor 拿到的是游标初始位：必须自己 next()
                    while (rs.next()) {
                        long planId = rs.getLong("plan_id");
                        sink.computeIfAbsent(planId, k -> new ArrayList<>()).add(new PricePlan.Item(
                                rs.getLong("id"), rs.getLong("sku_id"), rs.getLong("price_amount"),
                                rs.getInt("discount_bp"), rs.getInt("min_qty")));
                    }
                    return null;
                }, planIds.toArray());
    }
}
