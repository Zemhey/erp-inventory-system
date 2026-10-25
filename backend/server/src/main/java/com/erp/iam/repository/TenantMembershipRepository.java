package com.erp.iam.repository;

import com.erp.shared.tenant.TenantContext;
import com.erp.shared.tenant.TenantRef;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 用户 × 租户成员关系（B1 · FR-TNT-07）。
 *
 * <p><b>★ 为什么这里的读全部走 {@link TenantContext#callAsSystem}</b>：
 * 成员关系回答的是"这个人属于哪些租户"，它<b>天生跨租户</b> —— 当前请求绑定在
 * 租户 A，却要列出该用户在 A、B、C 的成员关系。若按当前租户过滤，
 * 登录后的切换器永远只看得到 1 个租户（假象），而这不是隔离，是功能坏了。
 *
 * <p>这是系统旁路的第三个合法用途（前两个：登录按用户名定位账号、运维/迁移），
 * 边界同样收得很窄：
 * <ul>
 *   <li>只读 {@code user_id → tenant_id} 这一种关联，不碰任何业务表；</li>
 *   <li>只按<b>已认证用户自己的 id</b> 调用（登录、/auth/me、切换租户），
 *       不接受任意用户 id 的枚举接口；</li>
 *   <li>写路径不在这里：成员关系由 V17 的触发器与迁移种子维护，当前没有
 *       "平台管理员给人加租户"的接口（不在 B1 范围）。</li>
 * </ul>
 */
@Repository
public class TenantMembershipRepository {

    private static final RowMapper<TenantRef> MAPPER = (rs, i) -> new TenantRef(
            rs.getLong("id"), rs.getString("code"), rs.getString("name"),
            rs.getBoolean("enabled"));

    private final JdbcTemplate jdbc;

    public TenantMembershipRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 某用户的全部租户成员关系（含主租户），按租户 id 升序。
     *
     * <p>停用的租户也返回（带 {@code enabled=false}）：前端据此把切换项置灰，
     * 后端在切换时仍会独立拒绝 —— 显示与授权是两件事，不能只靠前端拦。
     */
    public List<TenantRef> membershipsOf(long userId) {
        return TenantContext.callAsSystem(() -> jdbc.query("""
                SELECT t.id, t.code, t.name, t.enabled
                  FROM app_user_tenants ut
                  JOIN tenants t ON t.id = ut.tenant_id
                 WHERE ut.user_id = ?
                 ORDER BY t.id
                """, MAPPER, userId));
    }

    /**
     * 该用户是否是该租户的成员。
     *
     * <p>每次登录、切换、以及每个带 token 的请求都会问一次。查询走
     * {@code (user_id, tenant_id)} 主键，代价是一次索引命中 —— 与
     * {@code JwtAuthenticationFilter} 每请求回库取角色的既有取舍一致。
     */
    public boolean isMember(long userId, long tenantId) {
        Integer n = TenantContext.callAsSystem(() -> jdbc.queryForObject("""
                SELECT COUNT(*) FROM app_user_tenants
                 WHERE user_id = ? AND tenant_id = ?
                """, Integer.class, userId, tenantId));
        return n != null && n > 0;
    }
}
