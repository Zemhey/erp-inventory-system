package com.erp.iam.application;

import com.erp.iam.repository.DataScopeRepository;
import com.erp.iam.repository.RoleRepository;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 数据权限管理服务（FR-IAM-04）。
 *
 * <p><b>★ 这是"在线授权，改完即时生效"的写入口</b>：
 * 因为 {@code DataScopeResolver} 每请求回库解析，改完配置后
 * <b>同一会话的下一个请求</b>就按新范围过滤，不要求重新登录。
 *
 * <p><b>只允许三种范围</b>，且配置的合法性全部压到数据库的 CHECK 上：
 * {@code WAREHOUSE} 必须有 {@code scope_value}、{@code ALL/SELF} 必须没有、
 * 主体二选一。应用层这里只做"类型字符串解析"与"把配置错误变成 400"。
 */
@Service
public class DataScopeAdminService {

    private final DataScopeRepository scopes;
    private final RoleRepository roles;
    private final UserAdminService users;

    public DataScopeAdminService(DataScopeRepository scopes, RoleRepository roles,
                                 UserAdminService users) {
        this.scopes = scopes;
        this.roles = roles;
        this.users = users;
    }

    /** 某用户的范围配置（含从角色继承的那些，用于界面展示"为什么他能看到这个仓"）。 */
    @Transactional(readOnly = true)
    public List<ScopeView> scopesOfUser(long userId) {
        users.requireUser(userId);
        return toViews(scopes.rowsForUser(userId));
    }

    /** 某用户**直接配置**的行（不含角色继承）——覆盖式写入的对象。 */
    @Transactional(readOnly = true)
    public List<ScopeView> directScopesOfUser(long userId) {
        users.requireUser(userId);
        return toViews(scopes.directRowsForUser(userId));
    }

    /** 某用户通过角色继承来的行（界面据此提示"要改请改角色"）。 */
    @Transactional(readOnly = true)
    public List<ScopeView> inheritedScopesOfUser(long userId) {
        users.requireUser(userId);
        return toViews(scopes.inheritedRowsForUser(userId));
    }

    /** 某角色的范围配置。 */
    @Transactional(readOnly = true)
    public List<ScopeView> scopesOfRole(long roleId) {
        if (roles.findById(roleId).isEmpty()) {
            throw new BusinessException(ErrorCode.IAM_ROLE_NOT_FOUND,
                    "角色不存在：id=%d".formatted(roleId));
        }
        return toViews(scopes.rowsForRole(roleId));
    }

    /**
     * 覆盖式设置某用户的直接范围配置。
     *
     * @param userId  目标用户
     * @param entries 形如 {@code "ALL"}、{@code "SELF"}、
     *                {@code "WAREHOUSE:<id>"} 的字符串
     */
    @Transactional
    public void replaceUserScopes(long userId, List<String> entries) {
        users.requireUser(userId);
        scopes.clearUserScopes(userId);
        for (ParsedScope p : parse(entries)) {
            scopes.addUserScope(userId, p.scopeType(), p.scopeValue());
        }
    }

    /** 覆盖式设置某角色的范围配置。 */
    @Transactional
    public void replaceRoleScopes(long roleId, List<String> entries) {
        if (roles.findById(roleId).isEmpty()) {
            throw new BusinessException(ErrorCode.IAM_ROLE_NOT_FOUND,
                    "角色不存在：id=%d".formatted(roleId));
        }
        scopes.clearRoleScopes(roleId);
        for (ParsedScope p : parse(entries)) {
            scopes.addRoleScope(roleId, p.scopeType(), p.scopeValue());
        }
    }

    /**
     * 解析 {@code "WAREHOUSE:12"} 这样的配置项。
     *
     * <p>用字符串而不是嵌套 JSON：界面上就是一个多选 + 一个仓库多选，
     * 传一个扁平数组最省事，也让错误消息能指出"哪一项写错了"。
     */
    private static List<ParsedScope> parse(List<String> entries) {
        List<ParsedScope> out = new ArrayList<>();
        if (entries == null) {
            return out;
        }
        for (String raw : entries) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String v = raw.trim();
            int idx = v.indexOf(':');
            String type = (idx < 0 ? v : v.substring(0, idx)).toUpperCase(Locale.ROOT);
            String value = idx < 0 ? null : v.substring(idx + 1).trim();
            switch (type) {
                case "ALL", "SELF" -> {
                    if (value != null && !value.isBlank()) {
                        throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                                "范围 %s 不接受取值：%s".formatted(type, raw));
                    }
                    out.add(new ParsedScope(type, null));
                }
                case "WAREHOUSE" -> {
                    if (value == null || value.isBlank()) {
                        throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                                "WAREHOUSE 范围必须给出仓库 id：%s".formatted(raw));
                    }
                    try {
                        out.add(new ParsedScope("WAREHOUSE", Long.parseLong(value)));
                    } catch (NumberFormatException ex) {
                        throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                                "WAREHOUSE 范围的仓库 id 必须为数字：%s".formatted(raw));
                    }
                }
                default -> throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                        "范围类型非法：%s（允许 ALL | WAREHOUSE:<id> | SELF）".formatted(raw));
            }
        }
        return out;
    }

    private record ParsedScope(String scopeType, Long scopeValue) {
    }

    /**
     * 一条范围配置的<b>应用层视图</b>。
     *
     * <p>对外用与写入同形的字符串（{@code "ALL"} / {@code "SELF"} /
     * {@code "WAREHOUSE:12"}）：读写对称，前端不必写两套映射，
     * 也让"配置写成什么样、读回来就是什么样"成为可断言的性质。
     *
     * <p>为什么不直接返回仓储的 {@code ScopeRow}：Controller 不允许依赖
     * {@code ..repository..} 里的类型（ArchUnit「控制器不得直连仓储」）。
     */
    public record ScopeView(String scopeType, Long scopeValue) {

        /** 与写入同形的字符串形式。 */
        public String token() {
            return scopeValue == null ? scopeType : scopeType + ":" + scopeValue;
        }
    }

    private static List<ScopeView> toViews(List<DataScopeRepository.ScopeRow> rows) {
        return rows.stream()
                .map(r -> new ScopeView(r.scopeType(), r.scopeValue()))
                .toList();
    }
}
