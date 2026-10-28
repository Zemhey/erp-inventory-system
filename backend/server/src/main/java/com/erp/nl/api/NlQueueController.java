package com.erp.nl.api;

import com.erp.iam.security.RequirePermission;
import com.erp.nl.application.NlCommandDispatcher;
import com.erp.nl.domain.NlCommand;
import com.erp.nl.domain.NlCommandResult;
import com.erp.nl.domain.NlIntent;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.security.ActorContext;
import com.erp.shared.web.ApiResponses;
import com.erp.shared.web.PageResult;
import com.erp.sys.application.NlExecutionService;
import com.erp.sys.domain.NlAuditFilter;
import com.erp.sys.domain.NlExecutionView;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 待确认队列与操作审计（FR-V4-03 · S5/S6）。
 *
 * <p><b>为什么需要这两个接口，而不是"前端自己记着"</b>：
 * 一条计划在等人确认的时候，后端其实什么都没发生——如果不落库，
 * 用户关掉页面、换个浏览器、或者明天再看，那条计划就消失了。
 * <b>看不见的待确认 = 没审。</b>
 *
 * <p><b>为什么"确认"在这里还能再走一次执行</b>：页面上确认走的是"回执 + /nl/command"，
 * 队列里确认走这里。两条路最终都调同一个 {@link NlCommandDispatcher}，
 * 所以业务校验、权限、幂等完全一致——不存在"队列通道能绕过规则"这回事。
 *
 * <p><b>权限</b>：看队列与审计要 {@code sys:read}（与既有审计查询同一套）；
 * 真正执行时由调度器按意图再判一次（建单要 {@code pur:create} 等）。
 * 也就是说：能看队列 ≠ 能执行队列里的东西。
 *
 * <p><b>可以确认别人发起的操作吗</b>：可以。这正是队列存在的意义——
 * 发起与拍板不必是同一个人。拍板人会被记进 {@code decided_by}，
 * 所以"谁批的"永远查得到，而不是含糊地记在发起人头上。
 */
@RestController
@RequestMapping("/api/v1/nl")
public class NlQueueController {

    private final NlExecutionService executions;
    private final NlCommandDispatcher dispatcher;
    private final ActorContext actors;
    private final ObjectMapper json;

    public NlQueueController(NlExecutionService executions,
                             NlCommandDispatcher dispatcher,
                             ActorContext actors,
                             ObjectMapper json) {
        this.executions = executions;
        this.dispatcher = dispatcher;
        this.actors = actors;
        this.json = json;
    }

    /** 待确认队列。`pendingCount` 单独给一份：工作台要一个数字，不必拉整页。 */
    @GetMapping("/pending")
    @RequirePermission("sys:read")
    public ResponseEntity<Map<String, Object>> pending(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResult<NlExecutionView> rows = executions.pagePending(page, size);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", rows.items());
        body.put("total", rows.total());
        body.put("page", rows.page());
        body.put("size", rows.size());
        body.put("pendingCount", executions.countPending());
        return ResponseEntity.ok(ApiResponses.ok(body));
    }

    /**
     * 在队列里确认执行。
     *
     * <p>不需要回执：这一行本身就是"人已经点过"的证据。但仍然会：
     * ① 检查它还在待确认状态（过期 / 已执行都会被拒）；
     * ② 按意图重新判权限；
     * ③ 用**计划那次的 requestId** 执行，保持建单幂等。
     */
    @PostMapping("/pending/{id}/execute")
    @RequirePermission("sys:read")
    public ResponseEntity<Map<String, Object>> execute(
            @PathVariable long id,
            @RequestHeader("X-Request-Id") @NotBlank @Size(min = 8, max = 64) String requestId) {
        NlExecutionView record = executions.requirePending(id);
        NlCommand command = rebuild(record);
        ActorContext.Actor actor = actors.current().orElseThrow(() ->
                new BusinessException(ErrorCode.IAM_UNAUTHENTICATED, "未登录或登录已过期，请重新登录"));
        try {
            NlCommandResult result = dispatcher.execute(command, record.requestId());
            executions.markExecuted(record.requestId(), NlResultDigest.docType(command.intent()),
                    NlResultDigest.docId(result), NlResultDigest.docNo(result),
                    NlResultDigest.toJson(json, result), actor.id(), actor.username());
            return ResponseEntity.ok(ApiResponses.ok(result));
        } catch (BusinessException ex) {
            // 执行失败也留痕：审计里"想干但没干成"和"干成了"同样重要
            executions.markFailed(record.requestId(), ex.getMessage());
            throw ex;
        }
    }

    /** 驳回：不改业务数据，只是把这条计划注销，并记下是谁否掉的。 */
    @PostMapping("/pending/{id}/dismiss")
    @RequirePermission("sys:read")
    public ResponseEntity<Map<String, Object>> dismiss(@PathVariable long id) {
        ActorContext.Actor actor = actors.current().orElseThrow(() ->
                new BusinessException(ErrorCode.IAM_UNAUTHENTICATED, "未登录或登录已过期，请重新登录"));
        executions.requirePending(id);
        executions.dismiss(id, actor.id(), actor.username());
        return ResponseEntity.ok(ApiResponses.ok(Map.of("id", id, "status", "DISMISSED")));
    }

    /**
     * 审计列表。
     *
     * <p>时间参数用 ISO-8601 字符串接收再自行解析：让 Spring 直接绑 {@link Instant}
     * 时区含义取决于容器配置，而审计查询最忌讳"查出来的区间和以为的不一样"。
     */
    @GetMapping("/audit")
    @RequirePermission("sys:read")
    public ResponseEntity<Map<String, Object>> audit(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String intent,
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Boolean write,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        NlAuditFilter filter = new NlAuditFilter(status, intent, actorId, source, write,
                parseInstant(from), parseInstant(to));
        PageResult<NlExecutionView> rows = executions.pageAudit(filter, page, size);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", rows.items());
        body.put("total", rows.total());
        body.put("page", rows.page());
        body.put("size", rows.size());
        return ResponseEntity.ok(ApiResponses.ok(body));
    }

    @GetMapping("/audit/{id}")
    @RequirePermission("sys:read")
    public ResponseEntity<Map<String, Object>> auditDetail(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(executions.require(id)));
    }

    // ─────────────────────────── 内部 ───────────────────────────

    /**
     * 从档案里的计划快照重建可执行命令。
     *
     * <p>用**快照**而不是用户原话重新解析：存档时人看到的就是这份计划，
     * 重新解析可能因为解析器升级（换模型）而得到不同参数——
     * 那等于"确认的是 A，执行的是 B"。
     */
    @SuppressWarnings("unchecked")
    private static NlCommand rebuild(NlExecutionView record) {
        NlIntent intent = NlIntent.fromCode(record.intent())
                .orElseThrow(() -> new BusinessException(ErrorCode.NL_COMMAND_UNRECOGNIZED,
                        "记录里的意图已不被支持：" + record.intent()));
        Object rawArgs = record.plan().get("args");
        Map<String, Object> args = rawArgs instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
        return new NlCommand(intent, args);
    }

    private static Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.SYS_AUDIT_QUERY_INVALID,
                    "时间格式非法（应为 ISO-8601，例如 2026-10-27T00:00:00Z）：" + raw);
        }
    }
}
