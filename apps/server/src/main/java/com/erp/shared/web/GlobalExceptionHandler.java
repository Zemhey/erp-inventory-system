package com.erp.shared.web;

import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
