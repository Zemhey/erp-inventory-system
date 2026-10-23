package com.erp.shared.web;

import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常 → 契约化错误响应（docs/05-接口契约规范.md §3、§4）。
 *
 * <p>约定：
 * <ul>
 *   <li>HTTP 状态码表达<b>类别</b>，业务错误码表达<b>具体原因</b>；</li>
 *   <li>不使用"200 包错误" —— 否则契约测试与监控都会失效；</li>
 *   <li>{@code traceId} 贯穿日志，测试失败可直接用它捞日志。</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public static final String TRACE_ID = "traceId";

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(BusinessException ex,
                                                             HttpServletRequest request) {
        String traceId = traceId(request);
        log.warn("业务异常 [{}] {} traceId={} code={}", request.getMethod(),
                request.getRequestURI(), traceId, ex.getCode(), ex);
        return ResponseEntity.status(ex.getCode().httpStatus()).body(body(
                ex.getCode().name(), ex.getMessage(), ex.getDetails(), traceId));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex,
                                                               HttpServletRequest request) {
        String traceId = traceId(request);
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");
        return ResponseEntity.badRequest().body(body(
                ErrorCode.SYS_VALIDATION_ERROR.name(), detail, null, traceId));
    }

    /**
     * 方法级参数校验失败（Spring 6.1 起的<b>另一条</b> 400 路径）。
     *
     * <p><b>为什么必须单独处理</b>（M2-S2 的 TC-API-017 实测撞出来的）：
     * Spring Framework 6.1 引入了一条新的校验通路 —— 只要被调用的处理方法上
     * 存在<b>直接带约束注解的参数</b>（本项目所有单据接口都写
     * {@code @RequestHeader("X-Request-Id") @NotBlank String requestId}），
     * 整个方法就改由 {@code HandlerMethodValidator} 做参数校验，
     * 此时连 {@code @Valid @RequestBody} 的请求体违规也会以
     * {@link HandlerMethodValidationException} 抛出，而<b>不再是</b>
     * {@link MethodArgumentNotValidException}。
     *
     * <p>于是同一件事（客户端漏传 / 传错字段）在主数据接口上是 400，
     * 在进货 / 出货 / 退货 / 结算接口上却是 500 —— 落到下面那个兜底分支。
     * 这正是本类上面 {@code MissingRequestHeaderException} 注释里点名要消灭的
     * "客户端问题被报成服务器坏了"，只是换了个异常名字又出现了一次。
     *
     * <p>没有专门 handler 时前端只会看到 {@code SYS_INTERNAL_ERROR}，
     * 既拿不到"哪个字段不合法"，也会把 5xx 告警污染成噪声。
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Map<String, Object>> handleMethodLevelValidation(
            HandlerMethodValidationException ex, HttpServletRequest request) {
        String traceId = traceId(request);
        List<String> parts = new ArrayList<>();
        for (var result : ex.getAllValidationResults()) {
            List<String> messages = new ArrayList<>();
            for (var error : result.getResolvableErrors()) {
                if (error.getDefaultMessage() != null) {
                    messages.add(error.getDefaultMessage());
                }
            }
            if (messages.isEmpty()) {
                continue;
            }
            String parameter = result.getMethodParameter().getParameterName();
            parts.add((parameter == null ? "" : parameter + " ") + String.join("; ", messages));
        }
        return ResponseEntity.badRequest().body(body(ErrorCode.SYS_VALIDATION_ERROR.name(),
                parts.isEmpty() ? "参数校验失败" : String.join("; ", parts), null, traceId));
    }

    /**
     * 缺少必需的请求头（最典型的是缺少幂等键 {@code X-Request-Id}）。
     *
     * <p><b>为什么必须单独处理</b>（M1 契约测试捕获的真实缺陷）：
     * 契约规定所有写操作必须携带 {@code X-Request-Id}。若客户端漏传，
     * Spring 抛 {@link MissingRequestHeaderException}，而它不继承
     * {@code MethodArgumentNotValidException}，于是落到兜底的 {@code Exception} 分支，
     * 返回 <b>500 服务器内部错误</b>。
     *
     * <p>这是明显的归因错误：客户端漏传参数被报成"服务器坏了"，
     * 既误导前端排查方向，也污染服务端告警与错误率监控。
     * 正确语义是 400 + 可编程判断的 {@code SYS_VALIDATION_ERROR}。
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, Object>> handleMissingHeader(MissingRequestHeaderException ex,
                                                                   HttpServletRequest request) {
        String traceId = traceId(request);
        return ResponseEntity.badRequest().body(body(ErrorCode.SYS_VALIDATION_ERROR.name(),
                "缺少必需的请求头：%s（写操作必须携带幂等键）".formatted(ex.getHeaderName()),
                null, traceId));
    }

    /**
     * 请求体缺失或 JSON 无法解析。
     *
     * <p>同样是"客户端问题"被兜底成 500 的典型场景：body 写错一个括号，
     * 前端看到"服务器内部错误"会去找后端的麻烦。
     */
    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<Map<String, Object>> handleBadRequest(Exception ex,
                                                                HttpServletRequest request) {
        String traceId = traceId(request);
        log.debug("请求无法解析 {} {} traceId={}", request.getMethod(), request.getRequestURI(),
                traceId, ex);
        return ResponseEntity.badRequest().body(body(ErrorCode.SYS_VALIDATION_ERROR.name(),
                "请求格式错误：无法解析请求体或缺少必需参数", null, traceId));
    }

    /**
     * 路径/查询参数类型不匹配（例如 {@code /products/abc} 期望 long）。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                                  HttpServletRequest request) {
        String traceId = traceId(request);
        return ResponseEntity.badRequest().body(body(ErrorCode.SYS_VALIDATION_ERROR.name(),
                "参数类型错误：%s 期望 %s".formatted(ex.getName(),
                        ex.getRequiredType() == null ? "未知" : ex.getRequiredType().getSimpleName()),
                null, traceId));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex,
                                                               HttpServletRequest request) {
        String traceId = traceId(request);
        // 未预期异常必须记录完整堆栈 —— 自动化测试失败时靠它定位
        log.error("未预期异常 {} {} traceId={}", request.getMethod(), request.getRequestURI(),
                traceId, ex);
        return ResponseEntity.internalServerError().body(body(
                "SYS_INTERNAL_ERROR",
                "服务器内部错误，请联系管理员（traceId=" + traceId + "）", null, traceId));
    }

    private Map<String, Object> body(String code, String message, Object details, String traceId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("message", message);
        if (details != null) {
            m.put("details", details);
        }
        m.put("traceId", traceId);
        m.put("timestamp", OffsetDateTime.now().toString());
        return m;
    }

    /** 优先复用过滤器写入的 traceId，保证与日志一致。 */
    private String traceId(HttpServletRequest request) {
        Object existing = request.getAttribute(TRACE_ID);
        if (existing != null) {
            return existing.toString();
        }
        String generated = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        request.setAttribute(TRACE_ID, generated);
        return generated;
    }
}
