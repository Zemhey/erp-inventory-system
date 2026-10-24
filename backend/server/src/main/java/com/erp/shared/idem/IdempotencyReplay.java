package com.erp.shared.idem;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 「本次请求是幂等重放」的标记（V2-C1）。
 *
 * <p><b>为什么需要它</b>：幂等命中时应用服务不再产生任何业务写入，但审计切面
 * {@code AuditAspect} 位于 Controller 外层、只看"这是个写请求"，
 * 它无法从返回值里分辨"真的建了一行"和"重放拿回了已有的那一行"。
 * 于是同一次业务变更会因为客户端重试而被记成两条一模一样的 CREATE 流水 ——
 * "谁在什么时候改了什么"这个查询信号被重试噪声稀释。
 * 本类提供两者之间的一个字节宽的握手：守卫命中时打标记，切面见到标记就跳过留痕。
 *
 * <p><b>为什么用 request attribute 而不是 ThreadLocal / 进程内缓存</b>：
 * <ol>
 *   <li>attribute 的生命周期由 Servlet 容器管，请求结束即释放 ——
 *       ThreadLocal 需要每个出口（含异常、异步、线程池复用）都记得清理，
 *       漏一处就会把"上一次请求是重放"泄漏给下一个复用该线程的请求，
 *       表现为随机的"审计少了记录"，且几乎无法复现；</li>
 *   <li>本标记<b>只影响审计是否留痕</b>，不参与任何幂等判定：
 *       幂等判定的唯一事实源是数据库唯一索引（见 V15），进程等不等价于重启与否都无关。</li>
 * </ol>
 *
 * <p><b>非 HTTP 入口</b>（单测直接调应用服务、将来的批处理）拿不到 request，
 * 此时静默 no-op：没有审计切面在跑，也就没有噪声要消除。
 */
public final class IdempotencyReplay {

    /** 请求属性名：存在即表示本次请求被幂等守卫判为重放。 */
    public static final String REQUEST_ATTR = "erp.idem.replayed";

    private IdempotencyReplay() {
    }

    /** 在"当前正在处理的那个 HTTP 请求"上打重放标记；非 HTTP 调用时 no-op。 */
    public static void markCurrentRequest() {
        HttpServletRequest request = currentRequest();
        if (request != null) {
            request.setAttribute(REQUEST_ATTR, Boolean.TRUE);
        }
    }

    /** 该请求是否已被判为重放。 */
    public static boolean isReplayed(HttpServletRequest request) {
        return request != null && Boolean.TRUE.equals(request.getAttribute(REQUEST_ATTR));
    }

    private static HttpServletRequest currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
    }
}
