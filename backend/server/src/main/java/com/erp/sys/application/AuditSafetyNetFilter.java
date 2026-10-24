package com.erp.sys.application;

import com.erp.sys.domain.AuditAction;
import com.erp.sys.domain.AuditResult;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 审计兜底过滤器（FR-SYS-06）—— 保证<b>每个写请求恰好一条审计</b>。
 *
 * <p><b>★ 为什么切面之外还需要这一层</b>（实测发现的真实缺口）：
 * {@link AuditAspect} 只在 Controller <b>方法被调用</b>时才生效。但一次写请求可能在
 * <b>方法调用之前</b>就被拒掉：
 * <ul>
 *   <li>{@code @Valid @RequestBody} 校验失败 → 400（参数解析期就抛了）；</li>
 *   <li>缺少 {@code X-Request-Id} 头 → 400；</li>
 *   <li>路径/查询参数类型不匹配 → 400。</li>
 * </ul>
 * 这三种都是"有人尝试写了东西但被系统拒绝"，是审计最该记录的部分，
 * 而切面<b>根本看不到</b>它们（方法没被调用）。于是 INV-15 的
 * "每个写接口至少 1 条审计"在只靠切面时做不到 —— 本泳道实测：42 个写接口
 * 只产生 26 条审计，缺的正是"空请求体/非法参数"这些接口。
 *
 * <p><b>分工</b>：
 * <ul>
 *   <li>切面负责<b>有语义的</b>审计：actor / before+after 快照 / biz_ref_no / 精确 errorCode；</li>
 *   <li>本过滤器负责<b>兜底</b>：切面没写（方法没跑）时补一条"请求级"审计，
 *       并同样计入 {@code erp_order_failed_total}。</li>
 * </ul>
 * 两者靠请求属性 {@value #AUDITED_ATTR} 协调，<b>不重复记录</b>。
 *
 * <p><b>为什么用"匹配到了处理器"作为判据</b>：只有真正命中 {@code @RequestMapping}
 * 的请求才代表"系统里存在这个写接口"。对只读端点（如 {@code POST /audits}）
 * 发写方法会在路由阶段就抛 405、连处理器都匹配不到 —— 那种请求不该产生审计
 * （否则审计表会被扫描器/误操作塞满，且它并没有"改动"任何东西）。
 *
 * <p>顺序放在 {@code TraceIdFilter} 之后（HIGHEST_PRECEDENCE + 5），
 * 这样写审计时 MDC 里的 traceId 已经就位。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class AuditSafetyNetFilter extends OncePerRequestFilter {

    /** 请求属性：本次请求是否已被 {@link AuditAspect} 记录过。 */
    public static final String AUDITED_ATTR = "erp.audit.recorded";

    private static final Logger log = LoggerFactory.getLogger(AuditSafetyNetFilter.class);

    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final String TEST_BACKDOOR_PREFIX = "/__test__";
    private static final String API_PREFIX = "/api/v1";

    private final AuditRecorder recorder;
    private final MetricsRecorder metrics;

    public AuditSafetyNetFilter(AuditRecorder recorder, MetricsRecorder metrics) {
        this.recorder = recorder;
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        chain.doFilter(request, response);
        try {
            compensateIfMissing(request, response);
        } catch (RuntimeException ex) {
            // 兜底同样不允许影响响应（响应此时已提交）
            log.error("审计兜底失败（业务响应不受影响）path={}", request.getRequestURI(), ex);
        }
    }

    private void compensateIfMissing(HttpServletRequest request, HttpServletResponse response) {
        if (!WRITE_METHODS.contains(request.getMethod())) {
            return;
        }
        String uri = request.getRequestURI();
        if (uri == null || uri.startsWith(TEST_BACKDOOR_PREFIX)) {
            return;
        }
        if (request.getAttribute(AUDITED_ATTR) != null) {
            return;
        }
        // 只对"真的匹配到了写接口"的请求补审计：405/404 这类路由失败不产生审计
        if (request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) == null) {
            return;
        }

        AuditAction action = AuditAction.of(request.getMethod(), uri);
        String resourceType = resourceTypeOf(request);
        String resourceId = trailingNumericSegment(uri);
        String errorCode = errorCodeOf(response.getStatus());
        String traceId = MDC.get("traceId");
        if (traceId == null) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }

        recorder.record(null, null, action, resourceType, resourceId, null, null, null,
                traceId, request.getRemoteAddr(), AuditResult.FAIL, errorCode);
        metrics.observeWrite(resourceType, action, AuditResult.FAIL, errorCode);
        log.info("审计兜底（处理器未被调用）traceId={} action={} resource={}/{} status={} code={} path={}",
                traceId, action, resourceType, resourceId, response.getStatus(), errorCode, uri);
        request.setAttribute(AUDITED_ATTR, Boolean.TRUE);
    }

    /** HTTP 状态 → 稳定的错误码（与 GlobalExceptionHandler 的映射保持一致）。 */
    private static String errorCodeOf(int status) {
        return switch (status) {
            case 400 -> "SYS_VALIDATION_ERROR";
            case 401 -> "IAM_UNAUTHENTICATED";
            case 403 -> "IAM_FORBIDDEN";
            case 404 -> "SYS_NOT_FOUND";
            case 405 -> "SYS_METHOD_NOT_ALLOWED";
            case 409 -> "SYS_DUPLICATE_REQUEST";
            default -> status >= 500 ? "SYS_INTERNAL_ERROR" : "HTTP_" + status;
        };
    }

    /**
     * 资源类型：优先取"匹配到的处理器"所在 Controller 的类名（与切面完全同一套规则），
     * 拿不到时退回路径首段。
     *
     * <p><b>为什么必须与切面保持同一套推导</b>：两条路径若得出不同资源类型
     * （切面给 {@code PRODUCT}、兜底给 {@code PRODUCTS}），同一业务域的审计会被拆成
     * 两个维度 —— 指标看板上"商品失败数"永远只统计到一半，而两个维度都"有数"，
     * 没人会怀疑。本泳道实测踩到：校验失败的请求被记成 {@code PRODUCTS}。
     */
    private static String resourceTypeOf(HttpServletRequest request) {
        Object handler = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
        if (handler instanceof org.springframework.web.method.HandlerMethod method) {
            String simpleName = method.getBeanType().getSimpleName();
            int marker = simpleName.indexOf("$$");
            if (marker > 0) {
                simpleName = simpleName.substring(0, marker);
            }
            if (simpleName.endsWith("Controller")) {
                simpleName = simpleName.substring(0, simpleName.length() - "Controller".length());
            }
            if (!simpleName.isEmpty()) {
                return simpleName.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                        .toUpperCase(java.util.Locale.ROOT);
            }
        }
        return firstSegmentOf(request.getRequestURI());
    }

    private static String firstSegmentOf(String uri) {
        String rest = uri.startsWith(API_PREFIX) ? uri.substring(API_PREFIX.length()) : uri;
        rest = rest.replaceFirst("^/+", "");
        int slash = rest.indexOf('/');
        String segment = slash < 0 ? rest : rest.substring(0, slash);
        return segment.isEmpty() ? "UNKNOWN" : segment.toUpperCase(java.util.Locale.ROOT);
    }

    private static String trailingNumericSegment(String uri) {
        String trimmed = uri.endsWith("/") && uri.length() > 1
                ? uri.substring(0, uri.length() - 1) : uri;
        int slash = trimmed.lastIndexOf('/');
        String last = slash < 0 ? trimmed : trimmed.substring(slash + 1);
        return !last.isEmpty() && last.chars().allMatch(Character::isDigit) ? last : null;
    }
}
