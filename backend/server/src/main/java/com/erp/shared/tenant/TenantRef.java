package com.erp.shared.tenant;

/**
 * 租户的只读视图（B1 · FR-TNT-07）。
 *
 * <p>刻意只包含"界面上要显示、授权时要判断"的四个字段：
 * 幂等键、创建时间等元数据与租户切换/登录响应无关，带出去只会让契约无谓膨胀。
 *
 * @param id      租户 id（JWT 的 {@code tid} 声明即此值）
 * @param code    租户编码（登录可选参数 {@code tenantCode} 用它定位）
 * @param name    租户显示名（顶栏展示）
 * @param enabled 是否启用（停用后登录、切换、已签发 token 全部拒绝）
 */
public record TenantRef(long id, String code, String name, boolean enabled) {

    public TenantRef {
        if (!TenantId.isKnown(id)) {
            throw new IllegalArgumentException("非法租户 id：" + id);
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("租户编码不能为空");
        }
    }
}
