package com.erp.iam.application;

import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.tenant.TenantContext;
import com.erp.shared.tenant.TenantDirectory;
import com.erp.shared.tenant.TenantId;
import com.erp.shared.tenant.TenantRef;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 平台租户元数据管理（B1 · FR-TNT-08）。
 *
 * <p><b>★ 边界：只碰 {@code tenants} 元数据，不碰任何业务表</b>。
 * 平台租户管理员不是"跨租户业务数据超级管理员"：即使他持有
 * {@code tenant:manage}，业务请求仍然经过 {@code TenantSql} 按**当前行动租户**
 * 过滤；拿着租户 2 的商品 id 在租户 1 的会话里请求，仍然是 404。
 * 这条边界由 {@code TenantAdminIT} 的跨租户用例守着。
 *
 * <p><b>两条停用保护</b>（都是"把自己锁在门外"的预防）：
 * <ol>
 *   <li>不能停用平台租户（id = 1）：平台管理员的账号主租户在那里，
 *       停用后连登录都进不来，只能改库救援；</li>
 *   <li>不能停用当前行动租户：停用后本请求之后的每个请求都会 401
 *       （{@code resolve} 校验租户启用），包括"再启用回来"的那个请求。</li>
 * </ol>
 * 启用任何租户都允许（包括当前租户）。
 */
@Service
public class TenantAdminService {

    private final TenantDirectory tenants;
    private final JdbcTemplate jdbc;

    public TenantAdminService(TenantDirectory tenants, JdbcTemplate jdbc) {
        this.tenants = tenants;
        this.jdbc = jdbc;
    }

    /** 全部租户元数据（按 id 升序）。 */
    @Transactional(readOnly = true)
    public List<TenantRef> list() {
        return tenants.all();
    }

    /**
     * 启用 / 停用一个租户。
     *
     * <p>幂等：纯开关（状态本身幂等），重复设置同一状态不会产生额外副作用；
     * 因此接口不消费 {@code X-Request-Id}。
     */
    @Transactional
    public TenantRef setEnabled(long id, boolean enabled) {
        TenantRef current = tenants.byId(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.TENANT_NOT_FOUND,
                        "租户不存在：id=" + id));

        if (!enabled) {
            if (id == TenantId.DEFAULT) {
                throw new BusinessException(ErrorCode.TENANT_STATE_ILLEGAL,
                        "不能停用平台租户（id=1）：平台管理员的账号与平台权限都归属该租户");
            }
            long actingTenant = TenantContext.require();
            if (id == actingTenant) {
                throw new BusinessException(ErrorCode.TENANT_STATE_ILLEGAL,
                        "不能停用当前所在租户：停用后本会话的后续请求都会被拒绝，"
                                + "包括用于重新启用的请求");
            }
        }

        if (current.enabled() != enabled) {
            int rows = jdbc.update("UPDATE tenants SET enabled = ? WHERE id = ?", enabled, id);
            if (rows == 0) {
                throw new BusinessException(ErrorCode.TENANT_NOT_FOUND,
                        "租户不存在：id=" + id);
            }
        }
        return tenants.byId(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.TENANT_NOT_FOUND,
                        "租户不存在：id=" + id));
    }
}
