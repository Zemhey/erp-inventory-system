package com.erp.shared.tenant;

import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Supplier;

/**
 * 租户上下文的<b>唯一持有者</b>（FR-TNT-04）。
 *
 * <p><b>★ 为什么是 {@code ThreadLocal} 而不是 {@code ScopedValue}</b>：
 * 本项目要求的 Java 版本是 17（{@code build.gradle.kts} 的 toolchain），
 * {@code ScopedValue} 要到 21 才预览、23 才转正。在 17 上用
 * {@code ThreadLocal} 就必须自己解决它的两个经典缺陷 —— 本类用三条措施解决：
 * <ol>
 *   <li>{@link #runIn} / {@link #callIn} 用 {@code try/finally} 保证<b>必定</b>清理；</li>
 *   <li>用<b>栈</b>而不是单值：嵌套切换（定时任务里逐租户循环、请求里临时切到系统视角）
 *       必须能精确回退到上一层，而不是"清成 null"；</li>
 *   <li>不提供裸的 {@code set()}：调用方只能拿到"成对的开/闭"，写不出漏清理的代码。</li>
 * </ol>
 *
 * <p><b>★ 为什么"拿不到上下文"要抛异常，而不是回落到默认租户</b>：
 * 回落到默认租户是这类系统里最危险的一个默认值 —— 它让"忘了绑定租户"的代码
 * 静默地读写<b>默认租户</b>的数据，用例照样绿，而真实租户的用户会看到别人的数据
 * （或自己的数据凭空消失）。这里选择<b>大声失败</b>：
 * {@link #require()} 在没有绑定时抛 {@code SYS_INTERNAL_ERROR}，
 * 于是缺陷在第一次执行时就暴露。
 *
 * <p>唯一的例外是迁移/运维脚本与"登录时按用户名找账号"，
 * 它们显式走 {@link #systemScope()}（打开数据库旁路）并必须在结束时关闭。
 * 这两条路径都<b>不经过 HTTP 业务接口</b>，由 {@code TenantSqlGuardTest} 守着。
 */
public final class TenantContext {

    /** 上下文缺失时的错误码。用 500 而不是 403/404：这是<b>缺陷</b>，不是用户越权。 */
    static final ErrorCode MISSING = ErrorCode.SYS_TENANT_CONTEXT_MISSING;

    /**
     * 一个"租户绑定"。
     *
     * @param tenantId 租户 id；{@link #SYSTEM} 时为 {@code null}
     * @param system   true = 系统旁路（数据库侧同样打开旁路开关，见 V16 的
     *                 {@code erp_tenant_bypass()}）
     */
    public record Binding(Long tenantId, boolean system) {

        static final Binding SYSTEM = new Binding(null, true);

        public Binding {
            if (!system && tenantId == null) {
                throw new IllegalArgumentException("非系统绑定的 tenantId 不能为空");
            }
        }
    }

    private static final ThreadLocal<Deque<Binding>> STACK = ThreadLocal.withInitial(ArrayDeque::new);

    private TenantContext() {
    }

    /**
     * 当前租户 id。
     *
     * @throws BusinessException 未绑定租户时（{@code SYS_INTERNAL_ERROR}）——
     *         <b>不允许</b>静默回落到默认租户，理由见类注释
     */
    public static long require() {
        Binding b = current();
        if (b == null) {
            throw new BusinessException(MISSING,
                    "租户上下文缺失：该调用路径没有绑定租户（拒绝回落到默认租户，"
                            + "以免静默读写别人的数据）");
        }
        if (b.system()) {
            throw new BusinessException(MISSING,
                    "当前是系统旁路上下文，没有确定的租户归属；"
                            + "业务读写必须在明确的租户绑定下进行");
        }
        return b.tenantId();
    }

    /** 当前绑定；未绑定时为空。<b>不抛异常</b>，供"我要判一下"的少数场合使用。 */
    public static Binding current() {
        Deque<Binding> stack = STACK.get();
        return stack.isEmpty() ? null : stack.peek();
    }

    /** 当前是否处于系统旁路。仅用于诊断与 SQL 注入层的冷静判断。 */
    public static boolean isSystem() {
        Binding b = current();
        return b != null && b.system();
    }

    /** 是否已绑定（含系统旁路）。 */
    public static boolean isBound() {
        return current() != null;
    }

    /**
     * 在指定租户下执行，结束（含异常）时<b>必定</b>恢复上一层的绑定。
     *
     * @param tenantId 租户 id
     * @param action   要执行的动作
     */
    public static void runIn(long tenantId, Runnable action) {
        push(new Binding(tenantId, false));
        try {
            action.run();
        } finally {
            pop();
        }
    }

    /** {@link #runIn} 的带返回值版本。 */
    public static <T> T callIn(long tenantId, Supplier<T> action) {
        push(new Binding(tenantId, false));
        try {
            return action.get();
        } finally {
            pop();
        }
    }

    /**
     * 在系统旁路下执行（跨租户可见）。
     *
     * <p><b>只允许两种调用</b>：① 登录时按用户名定位账号（此刻还不知道租户）；
     * ② 运维/迁移脚本。业务读写一律不得使用 —— 它的存在是为了让那两条
     * 合法路径不必"假装自己属于某个租户"，而不是给业务一条方便的后门。
     */
    public static <T> T callAsSystem(Supplier<T> action) {
        push(Binding.SYSTEM);
        try {
            return action.get();
        } finally {
            pop();
        }
    }

    /** {@link #callAsSystem} 的无返回值版本。 */
    public static void runAsSystem(Runnable action) {
        push(Binding.SYSTEM);
        try {
            action.run();
        } finally {
            pop();
        }
    }

    /**
     * 把一段动作**包装**成"在指定租户下执行"，供跨线程传递用途
     * （定时任务提交到线程池、异步任务）。
     *
     * <p><b>★ 为什么必须有这个包装而不是直接继承 ThreadLocal</b>：
     * {@code ThreadLocal} 不跨线程继承 —— 把任务提交到线程池后，工作线程上的
     * 上下文是<b>那个线程上一次任务留下的</b>（线程池复用！）。不显式包装的后果是
     * "异步任务用错租户"，而且只在特定调度顺序下复现。
     */
    public static Runnable wrap(long tenantId, Runnable action) {
        return () -> runIn(tenantId, action);
    }

    /** {@link #wrap} 的带返回值版本。 */
    public static <T> Supplier<T> wrap(long tenantId, Supplier<T> action) {
        return () -> callIn(tenantId, action);
    }

    /**
     * 测试用：清空当前线程的整条绑定栈。
     *
     * <p><b>为什么测试需要它</b>：测试在同一线程里连续跑，若某个用例
     * （或被测代码的缺陷）漏了 pop，下一条用例就会继承上一个租户 —— 而那类失败
     * 只在特定执行顺序下出现。每个用例前清一次，让"顺序依赖"无处藏身。
     */
    public static void clearForTest() {
        STACK.remove();
    }

    private static void push(Binding binding) {
        STACK.get().push(binding);
    }

    private static void pop() {
        Deque<Binding> stack = STACK.get();
        stack.poll();
        if (stack.isEmpty()) {
            // ★ 栈空时必须把 ThreadLocal 本身也移除：线程池里的线程会活很久，
            //   留一个空 Deque 在 ThreadLocalMap 里等于让"这条线程曾经跑过请求"
            //   这个事实一直挂在线程上，且 JDK 只在弱键失效时才清理。
            STACK.remove();
        }
    }
}
