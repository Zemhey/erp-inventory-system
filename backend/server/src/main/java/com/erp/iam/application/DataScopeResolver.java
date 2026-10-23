package com.erp.iam.application;

import com.erp.iam.domain.AppUser;
import com.erp.iam.repository.DataScopeRepository;
import com.erp.iam.repository.DataScopeRepository.ScopeRow;
import com.erp.shared.scope.DataScope;
import com.erp.shared.scope.DataScopeProvider;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * {@link DataScopeProvider} 的实现（FR-IAM-04）。
 *
 * <p>把 {@code data_scopes} 的行折成一个 {@link DataScope}。折叠规则写在
 * {@code DataScopeRepository} 的类注释里（ALL 优先 → WAREHOUSE 并集 → SELF → 未配置即全量），
 * 本类只做翻译，不重新定义优先级。
 *
 * <p><b>拿不到身份时返回"什么都不许看"，而不是全量</b>：
 * 空 {@code SecurityContext} 出现在"过滤器没跑/令牌失效/异步线程丢了上下文"三种情形，
 * 其中前两种都是异常路径。给异常路径一个全量默认值是最经典的权限漏洞
 * —— 修 bug 时把过滤器一关，权限就全开了。
 *
 * <p><b>每次请求都解析，不做缓存</b>：见 {@link DataScopeProvider} 的接口注释
 * （授权即时生效）。这里是一次两表的联合查询，主键索引级别。
 */
@Component
public class DataScopeResolver implements DataScopeProvider {

    private final AuthenticationService authentication;
    private final DataScopeRepository scopes;

    public DataScopeResolver(AuthenticationService authentication, DataScopeRepository scopes) {
        this.authentication = authentication;
        this.scopes = scopes;
    }

    @Override
    public DataScope currentScope() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null) {
            // ★ 没有身份 = 不是"用户发起的查询"，而是内部调用（服务里 create 之后回读、
            //   定时任务、集成测试直接调应用服务）。此时**不存在**"该收窄到谁"这个问题，
            //   因此不收窄。
            //
            //   这不会变成权限漏洞，因为 SecurityConfig 是 anyRequest().authenticated()：
            //   任何 HTTP 请求在没有有效 token 时都在过滤器层就被 401 挡住，
            //   根本到不了控制器/服务。越权矩阵里"无 token"那一类断言 401 就是在盯这条。
            //
            //   反过来说，如果哪天有人加了 permitAll 的业务路径，这个分支就变成了
            //   "匿名可读全量"—— 因此 ScopeContext 的注释里把这条前提写死，
            //   并由 DataScopeMatrixIT 的 40 个"无 token"组合守着。
            return DataScope.all();
        }
        String username = String.valueOf(auth.getPrincipal());
        return authentication.resolveUsername(username)
                .map(u -> resolve(u.id()))
                .orElseGet(DataScopeResolver::denyAll);
    }

    @Override
    public DataScope scopeOf(long userId) {
        return resolve(userId);
    }

    /**
     * 把范围行折成 {@link DataScope}。
     *
     * <p>用 {@link LinkedHashSet} 收集仓库 id：数据库可能因"用户直配 + 角色继承"
     * 返回重复仓库，去重后再交给 SQL 的 {@code = ANY(?)}。
     * 声明顺序（导入顺序）被保留，让日志与断言可复现。
     */
    DataScope resolve(long userId) {
        List<ScopeRow> rows = scopes.rowsForUser(userId);
        if (rows.isEmpty()) {
            // 未配置 = 不收窄（V14 注释①）。存量 234 条用例与生产数据都走这条路径。
            return DataScope.all();
        }
        boolean hasAll = rows.stream().anyMatch(r -> "ALL".equals(r.scopeType()));
        if (hasAll) {
            return DataScope.all();
        }
        Set<Long> warehouses = new LinkedHashSet<>();
        boolean hasSelf = false;
        for (ScopeRow row : rows) {
            if ("WAREHOUSE".equals(row.scopeType()) && row.scopeValue() != null) {
                warehouses.add(row.scopeValue());
            } else if ("SELF".equals(row.scopeType())) {
                hasSelf = true;
            }
        }
        if (!warehouses.isEmpty()) {
            // WAREHOUSE 优先于 SELF：仓库是更强的"看得见什么"约束，
            // 同时配了 SELF 时两者取交集，由 DataScope 的谓词同时生效。
            return hasSelf
                    ? new DataScope(DataScope.ScopeType.WAREHOUSE, List.copyOf(warehouses), userId)
                    : DataScope.warehouses(List.copyOf(warehouses));
        }
        if (hasSelf) {
            return DataScope.self(userId);
        }
        // 只配了无法识别的类型（理论上被 CHECK 挡住）：保守收紧
        return denyAll();
    }

    /**
     * 什么都不许看。
     *
     * <p>用 {@code WAREHOUSE + 空集合} 表达（落成 SQL 是 {@code AND 1 = 0}），
     * 而不是造一个第四种枚举值 —— 四种范围已经够多，
     * 而"空集合"在语义上就是"这些仓库里没有一个成立"，正好等于全否。
     */
    static DataScope denyAll() {
        return DataScope.warehouses(List.of());
    }

    /** 供测试断言：某用户的有效仓库集合（空 = 全量或全否，需配合 type 判断）。 */
    Optional<DataScope> effectiveScopeOf(String username) {
        return authentication.resolveUsername(username).map(u -> resolve(u.id()));
    }

    /** 供诊断：当前用户（可能为空）。 */
    Optional<AppUser> currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null) {
            return Optional.empty();
        }
        return authentication.resolveUsername(String.valueOf(auth.getPrincipal()));
    }
}
