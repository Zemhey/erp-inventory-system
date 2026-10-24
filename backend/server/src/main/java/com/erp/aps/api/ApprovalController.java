package com.erp.aps.api;

import com.erp.aps.application.ApprovalApplicationService;
import com.erp.aps.domain.ApprovalFlow;
import com.erp.aps.domain.ApprovalInstance;
import com.erp.aps.domain.ApprovalStatus;
import com.erp.aps.domain.ApprovalStep;
import com.erp.iam.security.RequirePermission;
import com.erp.shared.approval.ApprovalGate;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.security.ActorContext;
import com.erp.shared.web.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审批流接口（FR-APS-01 定义查询 + FR-APS-02 实例流转）。
 *
 * <p><b>五个动作各用各的权限码</b>，与单据侧 submit/approve/ship 分开同一做法：
 * <ul>
 *   <li>{@code aps:submit} —— 发起审批（采购员/销售员有）；</li>
 *   <li>{@code aps:approve} —— 同意/驳回/转办（主管、财务有；发起人<b>没有</b>）；</li>
 *   <li>{@code aps:withdraw} —— 撤回（只有发起人本人的审批才会走到，权限码是必要条件而非充分条件）；</li>
 *   <li>{@code aps:read} —— 看待办与详情；</li>
 *   <li>{@code aps:flow:write} —— 启停流程定义（管理员）。</li>
 * </ul>
 * 注意：{@code aps:approve} 只是第一道门。真正的判定在
 * {@link ApprovalApplicationService} 里按<b>节点</b>的 {@code approverPermission} 逐节点校验 ——
 * 否则一个"能审批"的人就能审所有节点，"金额阈值触发上级"就失去意义。
 */
@RestController
@RequestMapping("/api/v1/approvals")
public class ApprovalController {

    private final ApprovalApplicationService approvals;
    private final ActorContext actors;

    public ApprovalController(ApprovalApplicationService approvals, ActorContext actors) {
        this.approvals = approvals;
        this.actors = actors;
    }

    // ─────────────────────────── 请求体 ───────────────────────────

    public record SubmitRequest(
            @NotBlank String bizType,
            @NotNull Long bizId,
            @NotBlank @Size(max = 64) String bizRefNo,
            @NotNull Long amount) {
    }

    public record DecisionRequest(@Size(max = 200) String remark) {
    }

    public record TransferRequest(@NotNull Long targetUserId,
                                  @Size(max = 200) String remark) {
    }

    // ─────────────────────────── 实例流转 ───────────────────────────

    /**
     * 提交审批。
     *
     * <p>{@code amount} 由调用方（单据页/服务）传入<b>而不是服务端回查单据</b>：
     * 审批模块不允许读单据表（ArchUnit 规则），而且"金额"必须与用户看到的、
     * 单据上的金额是同一个数 —— 服务端回查会引入"提交时单据已改"的竞态。
     */
    @PostMapping
    @RequirePermission("aps:submit")
    public ResponseEntity<Map<String, Object>> submit(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @Valid @RequestBody SubmitRequest req) {
        ApprovalInstance instance = approvals.submit(new ApprovalApplicationService.SubmitCommand(
                parseBizType(req.bizType()), req.bizId(), req.bizRefNo(), req.amount(), requestId));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponses.ok(toMap(instance)));
    }

    @GetMapping
    @RequirePermission("aps:read")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String bizType,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        var found = approvals.page(parseStatus(status),
                bizType == null || bizType.isBlank() ? null : parseBizType(bizType),
                page, size);
        List<Map<String, Object>> items = new ArrayList<>(found.items().size());
        for (ApprovalInstance i : found.items()) {
            items.add(toMap(i));
        }
        return ResponseEntity.ok(ApiResponses.page(new com.erp.shared.web.PageResult<>(
                items, found.total(), found.page(), found.size())));
    }

    @GetMapping("/{id}")
    @RequirePermission("aps:read")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(approvals.require(id))));
    }

    @PostMapping("/{id}/approve")
    @RequirePermission("aps:approve")
    public ResponseEntity<Map<String, Object>> approve(
            @PathVariable long id, @RequestBody(required = false) DecisionRequest body) {
        return ResponseEntity.ok(ApiResponses.ok(
                toMap(approvals.approve(id, body == null ? null : body.remark()))));
    }

    @PostMapping("/{id}/reject")
    @RequirePermission("aps:approve")
    public ResponseEntity<Map<String, Object>> reject(
            @PathVariable long id, @RequestBody(required = false) DecisionRequest body) {
        return ResponseEntity.ok(ApiResponses.ok(
                toMap(approvals.reject(id, body == null ? null : body.remark()))));
    }

    /**
     * 撤回。
     *
     * <p>权限码 {@code aps:withdraw} 是必要条件，{@code "只有发起人本人"}
     * 的判定在服务层 —— 两层都要，因为权限码只回答"这个角色能不能撤回"，
     * 回答不了"这一张能不能由你撤回"。
     */
    @PostMapping("/{id}/withdraw")
    @RequirePermission("aps:withdraw")
    public ResponseEntity<Map<String, Object>> withdraw(
            @PathVariable long id, @RequestBody(required = false) DecisionRequest body) {
        return ResponseEntity.ok(ApiResponses.ok(
                toMap(approvals.withdraw(id, body == null ? null : body.remark()))));
    }

    /**
     * 转办。
     *
     * <p>目标人是否具备节点权限，由服务层通过 {@link ActorContext} 校验 ——
     * 否则可以把审批转给完全没有审批权限的账号，等于绕开 RBAC。
     */
    @PostMapping("/{id}/transfer")
    @RequirePermission("aps:approve")
    public ResponseEntity<Map<String, Object>> transfer(
            @PathVariable long id, @Valid @RequestBody TransferRequest req) {
        ApprovalInstance instance = approvals.transfer(id, req.targetUserId(), req.remark(),
                targetId -> actors.actorOf(targetId)
                        .map(a -> a.hasPermission("aps:approve"))
                        .orElse(false));
        return ResponseEntity.ok(ApiResponses.ok(toMap(instance)));
    }

    @PostMapping("/{id}/accept-transfer")
    @RequirePermission("aps:approve")
    public ResponseEntity<Map<String, Object>> acceptTransfer(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(approvals.acceptTransfer(id))));
    }

    // ─────────────────────────── 流程定义 ───────────────────────────

    /** 全部流程定义（含节点），供审批设置页只读展示。 */
    @GetMapping("/flows")
    @RequirePermission("aps:read")
    public ResponseEntity<Map<String, Object>> listFlows() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (ApprovalFlow f : approvals.listFlows()) {
            items.add(toMap(f));
        }
        return ResponseEntity.ok(ApiResponses.ok(Map.of("items", items)));
    }

    /**
     * 启停流程。
     *
     * <p><b>没有"建流程"接口</b>：见 {@code ApprovalFlow} 类注释 —— 不做流程设计器。
     * 流程由迁移种子给出，线上只允许启停。要加新流程就是一次迁移，
     * 这样"流程定义改了但没人 review"的风险被降到一个可审计的 SQL 文件里。
     */
    @PostMapping("/flows/{id}/enabled")
    @RequirePermission("aps:flow:write")
    public ResponseEntity<Map<String, Object>> setFlowEnabled(
            @PathVariable long id, @RequestParam boolean enabled) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(approvals.setFlowEnabled(id, enabled))));
    }

    // ─────────────────────────── 参数解析 ───────────────────────────

    private static ApprovalGate.BizType parseBizType(String raw) {
        try {
            return ApprovalGate.BizType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "bizType 取值非法：%s（允许 PURCHASE_ORDER | SALES_ORDER | RETURN_ORDER）"
                            .formatted(raw));
        }
    }

    private static ApprovalStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return ApprovalStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "status 取值非法：%s（允许 %s）"
                            .formatted(raw, java.util.Arrays.toString(ApprovalStatus.values())));
        }
    }

    // ─────────────────────────── 响应映射 ───────────────────────────

    private static Map<String, Object> toMap(ApprovalInstance i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.id());
        m.put("flowId", i.flowId());
        m.put("bizType", i.bizType());
        m.put("bizId", i.bizId());
        m.put("bizRefNo", i.bizRefNo());
        m.put("amount", i.amount());
        m.put("currentStep", i.currentStep());
        m.put("status", i.status().name());
        m.put("initiatorId", i.initiatorId());
        List<Map<String, Object>> steps = new ArrayList<>();
        for (ApprovalStep s : i.steps()) {
            steps.add(toMap(s));
        }
        m.put("steps", steps);
        return m;
    }

    private static Map<String, Object> toMap(ApprovalStep s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stepNo", s.stepNo());
        m.put("name", s.name());
        m.put("approverPermission", s.approverPermission());
        m.put("approveMode", s.approveMode().name());
        m.put("requiredApprovals", s.requiredApprovals());
        m.put("thresholdAmount", s.thresholdAmount());
        m.put("status", s.status().name());
        m.put("approvedBy", s.approvedBy());
        m.put("decidedAt", s.decidedAt() == null ? null : s.decidedAt().toString());
        m.put("decisionBy", s.decisionBy());
        m.put("remark", s.remark());
        return m;
    }

    private static Map<String, Object> toMap(ApprovalFlow f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.id());
        m.put("code", f.code());
        m.put("name", f.name());
        m.put("bizType", f.bizType());
        m.put("description", f.description());
        m.put("enabled", f.enabled());
        List<Map<String, Object>> steps = new ArrayList<>();
        for (ApprovalStep s : f.steps()) {
            steps.add(toMap(s));
        }
        m.put("steps", steps);
        return m;
    }
}
