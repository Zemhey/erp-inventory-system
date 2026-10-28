package com.erp.nl.api;

import com.erp.nl.application.NlCommandDispatcher;
import com.erp.nl.application.NlCommandParser;
import com.erp.nl.application.NlConfirmations;
import com.erp.nl.application.NlPlanService;
import com.erp.nl.application.NlReviewService;
import com.erp.nl.domain.NlCommand;
import com.erp.nl.domain.NlCommandResult;
import com.erp.nl.domain.NlPlan;
import com.erp.nl.domain.NlReview;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.security.ActorContext;
import com.erp.shared.web.ApiResponses;
import com.erp.sys.application.NlExecutionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 自然语言操作入口（FR-SYS-04，契约 {@code POST /api/v1/nl/command}）。
 *
 * <p>本层只有四步：绑定请求 → 交给解析器 → **校验人工确认凭据** → 交给调度器。
 * 它<b>不做</b>权限判断（意图级权限在调度器里）、<b>不碰</b>任何业务服务
 * —— 这样换解析器（stub/真实 LLM）时，HTTP 契约与错误码完全不变。
 *
 * <p><b>为什么确认闸门放在这一层而不是页面</b>（FR-V4-03 · S4）：
 * 页面上的"确认"是界面约定，Agent 只要绕过 {@code /nl/plan} 直接打本接口就能跳过它。
 * 闸门必须在服务端——这样无论调用方是页面、豆包还是自研脚本，写意图都必须先拿到
 * 服务端签发的回执。
 *
 * <p><b>只读意图不受影响</b>：读不审。查错了重问一次就行，
 * 给查询加确认成本等于把 AI 省下的时间又还回去。
 *
 * <p><b>执行结果会回填到档案上</b>（S5/S6）：成功记单号，失败记原因。
 * 回填用的 key 是**回执里带的计划 requestId**，不是本次请求的 id——
 * 后者每次都不一样，用它回填会永远找不到那一行。
 */
@RestController
@RequestMapping("/api/v1/nl")
public class NlCommandController {

    private final NlCommandParser parser;
    private final NlCommandDispatcher dispatcher;
    private final NlPlanService plans;
    private final NlReviewService reviews;
    private final NlConfirmations confirmations;
    private final NlExecutionService executions;
    private final ActorContext actors;
    private final ObjectMapper json;

    public NlCommandController(NlCommandParser parser,
                               NlCommandDispatcher dispatcher,
                               NlPlanService plans,
                               NlReviewService reviews,
                               NlConfirmations confirmations,
                               NlExecutionService executions,
                               ActorContext actors,
                               ObjectMapper json) {
        this.parser = parser;
        this.dispatcher = dispatcher;
        this.plans = plans;
        this.reviews = reviews;
        this.confirmations = confirmations;
        this.executions = executions;
        this.actors = actors;
        this.json = json;
    }

    public record CommandRequest(
            @NotBlank(message = "不能为空")
            @Size(max = 500, message = "长度不能超过 500")
            String command) {
    }

    @PostMapping("/command")
    public ResponseEntity<Map<String, Object>> command(
            @RequestHeader("X-Request-Id") @NotBlank @Size(min = 8, max = 64) String requestId,
            @RequestHeader(value = "X-Nl-Plan-Token", required = false) String planToken,
            @Valid @RequestBody CommandRequest req) {
        NlCommand parsed = parser.parse(req.command());
        String planRequestId = parsed.intent().write()
                ? verifyConfirmed(parsed, planToken, req.command())
                : null;
        try {
            NlCommandResult result = dispatcher.execute(parsed, requestId);
            if (planRequestId != null) {
                backfill(planRequestId, parsed, result);
            }
            return ResponseEntity.ok(ApiResponses.ok(result));
        } catch (BusinessException ex) {
            // 执行失败也留痕："AI 想干但没干成"与"干成了"同样重要
            if (planRequestId != null) {
                executions.markFailed(planRequestId, ex.getMessage());
            }
            throw ex;
        }
    }

    /**
     * 校验"人已经确认过"，并返回**计划那一次**的 requestId（用于回填档案）。
     *
     * <p>缺凭据时抛出的 409 里会附带这次的计划与复核意见：调用方（尤其是 Agent）
     * 拿到响应就能直接展示给用户，不用再发一次 {@code /nl/plan}。
     * 少一次往返，就少一次"展示的和实际执行的不是同一份"的机会。
     */
    private String verifyConfirmed(NlCommand parsed, String planToken, String commandText) {
        ActorContext.Actor actor = actors.current().orElseThrow(() ->
                new BusinessException(ErrorCode.IAM_UNAUTHENTICATED, "未登录或登录已过期，请重新登录"));
        try {
            NlConfirmations.Verified verified = confirmations.verify(planToken, parsed, actor.id());
            return verified == null ? null : verified.requestId();
        } catch (BusinessException ex) {
            if (ex.getCode() != ErrorCode.NL_CONFIRM_REQUIRED) {
                throw ex;
            }
            NlPlan plan = plans.plan(parsed);
            NlReview review = reviews.review(plan, commandText);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("plan", plan);
            details.put("review", review);
            details.put("hint", "先展示 plan.summary 与 review.findings 给人，确认后带 X-Nl-Plan-Token 重试");
            throw new BusinessException(ex.getCode(), ex.getMessage(), details);
        }
    }

    private void backfill(String planRequestId, NlCommand parsed, NlCommandResult result) {
        ActorContext.Actor actor = actors.current().orElse(null);
        executions.markExecuted(
                planRequestId,
                NlResultDigest.docType(parsed.intent()),
                NlResultDigest.docId(result),
                NlResultDigest.docNo(result),
                NlResultDigest.toJson(json, result),
                actor == null ? 0L : actor.id(),
                actor == null ? null : actor.username());
    }
}
