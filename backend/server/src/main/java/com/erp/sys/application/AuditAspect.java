package com.erp.sys.application;

import com.erp.sys.domain.AuditAction;
import com.erp.sys.domain.AuditResult;
import com.erp.shared.error.BusinessException;
import com.erp.shared.idem.IdempotencyReplay;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 操作审计切面（FR-SYS-06）—— <b>全写接口自动留痕，不改任何现有 Controller</b>。
 *
 * <p><b>★ 为什么切 Controller 层而不切 ApplicationService</b>：
 * <ol>
 *   <li>切 Service 意味着要逐个模块认方法名（本批有 6 条泳道同时在改
 *       {@code InventoryApplicationService}），切入点会变成冲突热点；</li>
 *   <li>审计要记录的是"<b>哪个 HTTP 写操作</b>被谁调用、结果如何"。
 *       Service 方法没有 HTTP 上下文（路径、IP、响应体），凑不出来；</li>
 *   <li>Controller 层是**唯一能同时看到请求与响应**的位置，after_json 必须从响应体取。</li>
 * </ol>
 * 因此本切面依赖的是"所有 {@code @RestController} 都住在 {@code com.erp..api..}"这一结构约定，
 * 该约定由 {@code ArchitectureTest} 的规则守着（TC-ARCH-010）。
 *
 * <p><b>★ 三条铁律</b>（每一条都对应一个曾经踩过或差点踩到的坑）：
 * <ol>
 *   <li><b>审计写入必须在业务事务之外</b>（{@link AuditRecorder} 用 {@code REQUIRES_NEW}）。
 *       业务回滚时若审计跟着回滚，"失败的写操作"就永远查不到 —— 而失败最需要审计。
 *       本切面因此在 {@code proceed()} <b>返回之后</b>才写（此时 Service 的事务已提交）。</li>
 *   <li><b>取 {@code biz_ref_no} 等字段失败绝不抛异常</b>。审计是旁路能力，
 *       任何取值异常都退化成 {@code null}，业务照常返回。</li>
 *   <li><b>切面自身也不能让业务变慢或变红</b>：整段 after-逻辑包在 try/catch 里，
 *       最坏情况只是少一条审计（并计入 {@code erp_audit_write_failed_total}）。</li>
 * </ol>
 *
 * <p>顺序上设为 {@link Ordered#HIGHEST_PRECEDENCE} + 10，<b>包在</b>
 * {@code PermissionAspect} 之外：越权被拒（403）本身就是要审计的事件，
 * 若切面在里面就看不到这次失败了。
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);

    /** 只有这四种方法会改变状态；GET/HEAD/OPTIONS 不审计（否则审计表被查询淹没）。 */
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    /**
     * 测试后门不审计：{@code /__test__/*} 只在 test profile 存在，
     * 它的写操作是"造数据"而不是"业务操作"，审计它只会污染断言。
     * 该前缀同时在 INV-15 完备性扫描里被排除，两处保持一致。
     */
    private static final String TEST_BACKDOOR_PREFIX = "/__test__";

    private static final String API_PREFIX = "/api/v1";

    /**
     * 响应体里优先取的"业务单据号"字段名（按优先级）。
     *
     * <p><b>刻意不含 {@code code}</b>：主数据（商品/供应商/客户/仓库）的 {@code code}
     * 是"编码"，不是"业务单据号"。一旦把它当单据号，
     * {@code audit_logs.biz_ref_no} 会被主数据编码填满，
     * "按单据追溯这张单被谁动过"这个查询立刻失去意义 ——
     * 而数据看起来是"有值"的，只是全错。
     */
    private static final String[] BIZ_REF_KEYS =
            {"orderNo", "bizRefNo", "docNo", "settlementNo", "period"};

    private static final Pattern CAMEL = Pattern.compile("([a-z0-9])([A-Z])");

    private final AuditRecorder recorder;
    private final MetricsRecorder metrics;
    private final ObjectMapper json;

    public AuditAspect(AuditRecorder recorder, MetricsRecorder metrics, ObjectMapper json) {
        this.recorder = recorder;
        this.metrics = metrics;
        this.json = json;
    }

    /**
     * 切入点：{@code com.erp.*.api} 下的所有 public 方法。
     *
     * <p>刻意<b>不用</b> {@code @annotation(PostMapping)} 之类的注解匹配：
     * 那样只能命中直接标注的方法，而 Spring 允许把 {@code @RequestMapping}
     * 放在类上、方法上只写 {@code @GetMapping}。用包匹配 + 运行时读 HTTP 方法，
     * 才是"这个请求到底是不是写操作"的唯一可靠判据。
     */
    @Around("execution(public * com.erp..api..*.*(..))")
    public Object audit(ProceedingJoinPoint pjp) throws Throwable {
        HttpServletRequest request = currentRequest();
        if (request == null || !isAudited(request)) {
            // 非 HTTP 调用（单测直接调 Controller）或读请求：原样放行，不产生审计
            return pjp.proceed();
        }

        String method = request.getMethod();
        String path = request.getRequestURI();
        AuditAction action = AuditAction.of(method, path);
        String resourceType = resourceTypeOf(pjp, request);
        // 路径里的 id 是"变更前"就能确定的资源标识（POST/PUT /products/{id}）
        String pathResourceId = pathVariablePathId(request);
        String beforeJson = pathResourceId == null ? null
                : recorder.beforeSnapshot(resourceType, pathResourceId);

        Object result;
        try {
            result = pjp.proceed();
        } catch (Throwable ex) {
            // 业务失败也必须留痕（★ 铁律 1 的另一半：这正是 REQUIRES_NEW 存在的理由）
            recordSafely(request, action, resourceType, pathResourceId, beforeJson, null,
                    AuditResult.FAIL, errorCodeOf(ex));
            throw ex;
        }

        if (IdempotencyReplay.isReplayed(request)) {
            // 幂等重放：业务侧一行都没写、一个序列都没推进，留痕只会把"谁改了什么"
            // 稀释成"客户端重试了几次"。日志仍然记录，可观测性没有黑洞。
            // ★ 但仍然要置 AUDITED_ATTR：否则 AuditSafetyNetFilter 会认为"切面没跑"，
            //   补一条 result=FAIL 的兜底审计 —— 那比重复更糟，一次成功的重放被记成失败。
            request.setAttribute(AuditSafetyNetFilter.AUDITED_ATTR, Boolean.TRUE);
            log.info("幂等重放，跳过审计留痕 path={} actor={}", path, actorNameOf(request));
            return result;
        }

        recordSafely(request, action, resourceType,
                firstNonNull(resourceIdOf(result), pathResourceId),
                beforeJson, afterJsonOf(result), AuditResult.SUCCESS, null);
        return result;
    }

    /**
     * 统一的"安全记录"入口：任何异常都在这里被吞掉。
     *
     * <p>测试要求"审计失败绝不影响业务"，因此本方法的契约是<b>不抛任何异常</b>。
     */
    private void recordSafely(HttpServletRequest request, AuditAction action, String resourceType,
                              String resourceId, String beforeJson, String afterJson,
                              AuditResult result, String errorCode) {
        try {
            String bizRefNo = bizRefNoOf(request, afterJson);
            String traceId = MDC.get("traceId");
            Long actorId = actorIdOf(request);
            String actorName = actorNameOf(request);
            recorder.record(actorId, actorName, action, resourceType, resourceId, bizRefNo,
                    beforeJson, afterJson, traceId, ipOf(request), result, errorCode);
            // ★ FR-SYS-08：成功路径也必须有一行带 traceId 的日志。
            //   否则"按 traceId 捞这次请求的全部日志"在成功请求上会捞到空 ——
            //   而成功请求恰恰是最常见的那一类。
            log.info("审计留痕 traceId={} actor={} action={} resource={}/{} result={} path={}",
                    traceId, actorName, action, resourceType, resourceId, result,
                    request.getRequestURI());
            metrics.observeWrite(resourceType, action, result, errorCode);
        } catch (RuntimeException ex) {
            // ★ 铁律 2：审计写不进去也绝不影响业务，只记日志与计数
            log.error("审计留痕失败（业务不受影响）path={} action={} resourceType={}",
                    request.getRequestURI(), action, resourceType, ex);
        } finally {
            // 与 AuditSafetyNetFilter 协调：标记"本请求已由切面审计"，
            // 避免兜底过滤器再补一条重复记录（重复审计会稀释"谁改了什么"的信号）
            request.setAttribute(AuditSafetyNetFilter.AUDITED_ATTR, Boolean.TRUE);
        }
    }

    // ─────────────────────────── 取值辅助（全部容错） ───────────────────────────

    private static boolean isAudited(HttpServletRequest request) {
        if (!WRITE_METHODS.contains(request.getMethod())) {
            return false;
        }
        String uri = request.getRequestURI();
        return uri != null && !uri.startsWith(TEST_BACKDOOR_PREFIX);
    }

    private static HttpServletRequest currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
    }

    /**
     * 资源类型：由 Controller 类名推导（{@code PurchaseOrderController} → {@code PURCHASE_ORDER}）。
     *
     * <p>为什么不按路径首段推导：{@code /inventory/reserve} 与 {@code /inventory/receipts}
     * 同属 {@code INVENTORY}，但将来 {@code /inventory/...} 可能被拆到别的控制器；
     * 类名与模块的一一对应关系比 URL 结构稳定，且改动时编译期就能看见。
     */
    private static String resourceTypeOf(ProceedingJoinPoint pjp, HttpServletRequest request) {
        try {
            String simpleName = pjp.getTarget().getClass().getSimpleName();
            // CGLIB 代理类名形如 ProductController$$SpringCGLIB$$0
            int marker = simpleName.indexOf("$$");
            if (marker > 0) {
                simpleName = simpleName.substring(0, marker);
            }
            if (simpleName.endsWith("Controller")) {
                simpleName = simpleName.substring(0, simpleName.length() - "Controller".length());
            }
            if (!simpleName.isEmpty()) {
                return CAMEL.matcher(simpleName).replaceAll("$1_$2").toUpperCase(java.util.Locale.ROOT);
            }
        } catch (RuntimeException ex) {
            log.debug("资源类型推导失败，回退到路径首段", ex);
        }
        return firstSegmentOf(request.getRequestURI());
    }

    private static String firstSegmentOf(String uri) {
        if (uri == null) {
            return "UNKNOWN";
        }
        String rest = uri.startsWith(API_PREFIX) ? uri.substring(API_PREFIX.length()) : uri;
        rest = rest.replaceFirst("^/+", "");
        int slash = rest.indexOf('/');
        String segment = slash < 0 ? rest : rest.substring(0, slash);
        return segment.isEmpty() ? "UNKNOWN" : segment.toUpperCase(java.util.Locale.ROOT);
    }

    /** 路径上的 {@code {id}}：仅当最后一段是纯数字时返回（{@code /products/42}）。 */
    private static String pathVariablePathId(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return null;
        }
        String trimmed = uri.endsWith("/") && uri.length() > 1
                ? uri.substring(0, uri.length() - 1) : uri;
        int slash = trimmed.lastIndexOf('/');
        String last = slash < 0 ? trimmed : trimmed.substring(slash + 1);
        return !last.isEmpty() && last.chars().allMatch(Character::isDigit) ? last : null;
    }

    /** 响应体里的资源主键（{@code data.id} / {@code data.reservationId}）。 */
    private static String resourceIdOf(Object response) {
        Map<?, ?> data = dataOf(response);
        if (data == null) {
            return null;
        }
        for (String key : new String[]{"id", "reservationId", "settlementId"}) {
            Object v = data.get(key);
            if (v != null) {
                return String.valueOf(v);
            }
        }
        return null;
    }

    /** 变更后快照：直接取响应体的 {@code data}（契约里 data 就是资源当前的可读形态）。 */
    private String afterJsonOf(Object response) {
        Map<?, ?> data = dataOf(response);
        return data == null ? null : AuditJson.snapshot(data, json);
    }

    private static Map<?, ?> dataOf(Object response) {
        if (response instanceof ResponseEntity<?> entity) {
            Object body = entity.getBody();
            return body instanceof Map<?, ?> map && map.get("data") instanceof Map<?, ?> data
                    ? data : null;
        }
        return null;
    }

    /**
     * 业务单据号。按"响应体 → 请求参数"的顺序取，取不到返回 null。
     *
     * <p>★ 本方法<b>不允许</b>抛异常：{@code biz_ref_no} 只是查询便利字段，
     * 为了它让一张已经成功的订单失败是荒谬的。因此整段包在 try/catch 里。
     */
    private static String bizRefNoOf(HttpServletRequest request, String afterJson) {
        try {
            if (afterJson != null) {
                for (String key : BIZ_REF_KEYS) {
                    String found = extractJsonString(afterJson, key);
                    if (found != null && !found.isBlank()) {
                        return found;
                    }
                }
            }
            String fromQuery = request.getParameter("bizRefNo");
            return fromQuery == null || fromQuery.isBlank() ? null : fromQuery;
        } catch (RuntimeException ex) {
            log.debug("biz_ref_no 取值失败，按 null 处理", ex);
            return null;
        }
    }

    /**
     * 从 JSON 文本里取一层字符串字段（只用于 {@code biz_ref_no} 这类便利字段）。
     *
     * <p>不用 {@code ObjectMapper.readTree} 再遍历：这里只关心固定几个键，
     * 已经序列化过一次的文本再解析一遍纯属浪费（审计在热路径上）。
     * 用最朴素的 {@code "key":"value"} 匹配，失败返回 null —— 反正有 try/catch 兜底。
     */
    private static String extractJsonString(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static Long actorIdOf(HttpServletRequest request) {
        try {
            var auth = org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication();
            if (auth == null || auth.getDetails() == null) {
                return null;
            }
            return auth.getDetails() instanceof Long id ? id : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String actorNameOf(HttpServletRequest request) {
        try {
            var auth = org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication();
            return auth == null || auth.getPrincipal() == null
                    ? null : String.valueOf(auth.getPrincipal());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String ipOf(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
        }
        return request.getRemoteAddr();
    }

    private static String errorCodeOf(Throwable ex) {
        if (ex instanceof BusinessException business) {
            return business.getCode().name();
        }
        // 非业务异常没有稳定错误码，用异常类名而不是编一个假的码：
        // 编造 "SYS_INTERNAL_ERROR" 会让审计与 GlobalExceptionHandler 的实际响应不一致
        return ex.getClass().getSimpleName();
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }
}
