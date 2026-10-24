package com.erp.shared.scope;

/**
 * 数据权限解析接口（FR-IAM-04）。
 *
 * <p><b>为什么接口在 {@code shared}、实现在 {@code iam}</b>：
 * 这跟 {@code shared.period.PeriodGuard} 是同一个理由 —— 八个资源模块都要用数据权限，
 * 若它们直接 import {@code com.erp.iam.application.DataScopeResolver}，
 * 就会出现「IAM 定义权限 → 单据模块依赖 IAM → IAM 又要读单据来做审计」这种
 * 迟早成环的依赖。接口放 shared，实现由 IAM 提供，依赖方向单向：
 *
 * <pre>
 * pur/sal/rma/inv/rpt/set ──依赖──▶ shared.scope.DataScopeProvider ──实现──▶ iam
 * </pre>
 *
 * <p><b>每次请求都重新解析，不做缓存</b>：管理员改了某人能看到哪些仓，
 * 下一个请求就必须生效（硬验收第 4 条「授权即时生效」）。缓存要配失效策略，
 * 而失效策略一旦漏一个入口就是权限漏洞 —— 这里的查询是主键索引级别，
 * 每请求一次远比"缓存 + 失效"划算。
 */
public interface DataScopeProvider {

    /**
     * 解析当前请求方的数据权限。
     *
     * <p><b>如实记录一条容易搞错的边界</b>：拿不到身份时实现返回"全量"，
     * 这看起来像权限系统最经典的漏洞，但在本项目里是安全的，因为
     * {@code SecurityConfig} 用 {@code anyRequest().authenticated()} 默认拒绝 ——
     * 任何 HTTP 请求在没有有效 token 时都在过滤器层被 401 挡掉，
     * 到不了控制器。于是"空上下文"只出现在两种场合：
     * 服务内部回读（创建后立刻按 id 取回）、后台任务/集成测试直接调应用服务。
     * 这两种场合都<b>没有"该收窄到谁"这个问题</b>。
     *
     * <p>⚠️ 前提是"没有 permitAll 的业务路径"。新增匿名可达的业务接口时，
     * 必须同时确认它不经过本提供者；越权矩阵的 40 个"无 token"组合就是在守这条线。
     *
     * @return 当前请求的数据权限；永远非 null
     */
    DataScope currentScope();

    /**
     * 解析指定用户的数据权限（供后台任务、测试与转办等无 HTTP 上下文的场景使用）。
     *
     * @param userId 用户 id
     * @return 该用户的数据权限；用户不存在时返回"{@code 1=0}"式的空范围
     */
    DataScope scopeOf(long userId);
}
