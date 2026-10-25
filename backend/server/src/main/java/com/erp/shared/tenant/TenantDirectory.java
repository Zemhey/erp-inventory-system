package com.erp.shared.tenant;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 租户清单的读取入口（FR-TNT-01/04）。
 *
 * <p><b>★ 为什么需要一个专门的地方读租户清单</b>：定时任务（预占超时回收、
 * 冻结超时解冻）是"没有 HTTP 请求"的执行路径，因此<b>没有</b>天然的租户归属。
 * 而"多租户系统里的后台任务"只有两种合法形态：
 * <ol>
 *   <li><b>逐租户循环</b>：读出租户清单，对每个租户分别绑定上下文再跑一遍；</li>
 *   <li><b>显式声明为跨租户</b>：例如迁移脚本、对账跑批 —— 必须写明理由并登记。</li>
 * </ol>
 * 本项目采用前者：它更慢（N 个租户跑 N 遍），但<b>失败是局部的</b> ——
 * 某个租户的数据有问题只影响那一个租户的这次回收，不会把别的租户卷进去。
 * 后者的问题在于"一处出错，全域受影响"，且一旦忘了加租户条件就会跨租户改数据。
 *
 * <p>本类<b>只</b>读 {@code tenants} 表 —— 它是唯一不带 {@code tenant_id} 的业务表，
 * 因此也是唯一"不需要租户上下文就能读"的表。读取本身走
 * {@link TenantContext#callAsSystem}（否则会被改写层要求绑定租户，而这里恰恰
 * 是"要找出有哪些租户"的地方 —— 这是系统旁路的第二个合法用途）。
 */
@Component
public class TenantDirectory {

    private final JdbcTemplate jdbc;

    public TenantDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 启用中的租户 id（按 id 升序，保证后台任务的执行顺序可复现）。
     *
     * <p>按 id 升序是刻意的：定时任务失败时，日志里的"跑到第几个租户"才有意义；
     * 无序扫描会让"这次跑到哪"每次不同，排查时无法复现。
     */
    public List<Long> enabledTenantIds() {
        return TenantContext.callAsSystem(() -> jdbc.queryForList(
                "SELECT id FROM tenants WHERE enabled ORDER BY id", Long.class));
    }

    /** 某个租户的编码（诊断/日志用；不存在时返回空）。 */
    public java.util.Optional<String> codeOf(long tenantId) {
        return byId(tenantId).map(TenantRef::code);
    }

    /**
     * 全部租户（平台管理员列表用；按 id 升序，结果可复现）。
     *
     * <p>读的是 {@code tenants} —— 唯一不带 {@code tenant_id} 的业务表，
     * 因此这里走系统旁路是"跨租户元数据读取"，不是绕过业务隔离。
     */
    public List<TenantRef> all() {
        return TenantContext.callAsSystem(() -> jdbc.query(
                "SELECT id, code, name, enabled FROM tenants ORDER BY id", MAPPER));
    }

    /** 按 id 取租户（登录/切换/每请求校验都用它；不存在返回空）。 */
    public java.util.Optional<TenantRef> byId(long tenantId) {
        if (!TenantId.isKnown(tenantId)) {
            return java.util.Optional.empty();
        }
        return TenantContext.callAsSystem(() -> jdbc.query(
                        "SELECT id, code, name, enabled FROM tenants WHERE id = ?", MAPPER, tenantId))
                .stream().findFirst();
    }

    /** 按编码取租户（登录可选参数 {@code tenantCode} 用它定位）。 */
    public java.util.Optional<TenantRef> byCode(String code) {
        if (code == null || code.isBlank()) {
            return java.util.Optional.empty();
        }
        return TenantContext.callAsSystem(() -> jdbc.query(
                        "SELECT id, code, name, enabled FROM tenants WHERE code = ?",
                        MAPPER, code.trim()))
                .stream().findFirst();
    }

    private static final org.springframework.jdbc.core.RowMapper<TenantRef> MAPPER =
            (rs, i) -> new TenantRef(rs.getLong("id"), rs.getString("code"),
                    rs.getString("name"), rs.getBoolean("enabled"));
}
