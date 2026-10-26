package com.erp.aps.application;

import com.erp.aps.domain.ApprovalFlow;
import com.erp.aps.domain.ApprovalInstance;
import com.erp.aps.domain.ApprovalStatus;
import com.erp.aps.domain.ApprovalStatusMachine;
import com.erp.aps.domain.ApprovalStep;
import com.erp.aps.repository.ApprovalFlowRepository;
import com.erp.aps.repository.ApprovalInstanceRepository;
import com.erp.aps.repository.ApprovalStepRepository;
import com.erp.shared.approval.ApprovalGate;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.security.ActorContext;
import com.erp.shared.time.ClockProvider;
import com.erp.shared.web.PageResult;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审批实例流转服务（FR-APS-02 + FR-IAM-07）。
 *
 * <p><b>五个动作</b>：提交 / 同意 / 驳回 / 撤回 / 转办，各自对应一次状态推进。
 * 每一次推进都先过 {@link ApprovalStatusMachine}（非法转移一律拒绝），
 * 再走带乐观锁的条件 UPDATE。
 *
 * <p><b>★ 本服务绝不修改单据状态</b>（任务书坑 2、坑 3）：
 * 审批通过只把实例置 {@code APPROVED}，随后由单据侧（如
 * {@code PurchaseOrderApplicationService.approve}）自己调
 * {@code DocumentStatusMachine.requireTransition} 推进单据。
 * 单据侧在推进前会调 {@link ApprovalGate#requireApproved} 问一句"批准了没有"。
 * 这条单向依赖是靠 {@code ApprovalGate} 接口 + ArchUnit 规则守住的。
 *
 * <p><b>节点在实例化时快照</b>：提交时就按当时的金额算好"哪些节点要走"，
 * 并把定义行复制成实例行。这样流程定义被改（甚至被停用）也不会让
 * 已经在途的审批突然换了路径 —— 那会让审批人看到与发起时不同的待办。
 */
@Service
public class ApprovalApplicationService {

    private final ApprovalFlowRepository flows;
    private final ApprovalInstanceRepository instances;
    private final ApprovalStepRepository steps;
    private final ActorContext actors;
    /**
     * 业务时钟（计划书 §3.5：禁止直接取系统时间）。
     *
     * <p>{@code decided_at} 是审计时间戳，但<b>仍然必须走注入时钟</b>：
     * 架构规则不允许任何 {@code LocalDateTime.now()}，而"审计时间例外"是那种
     * 会被后人不断扩大的口子。用可注入时钟同样能得到正确结果 ——
     * 生产装配是真实时钟，测试里则可以精确断言"结论时刻等于业务时刻"。
     */
    private final ClockProvider clock;

    public ApprovalApplicationService(ApprovalFlowRepository flows,
                                      ApprovalInstanceRepository instances,
                                      ApprovalStepRepository steps,
                                      ActorContext actors,
                                      ClockProvider clock) {
        this.flows = flows;
        this.instances = instances;
        this.steps = steps;
        this.actors = actors;
        this.clock = clock;
    }

    // ─────────────────────────── 提交 ───────────────────────────

    /**
     * 提交审批：建实例 + 快照节点，并把状态推到 PENDING。
     *
     * <p><b>幂等</b>：同一 {@code requestId} 重放直接返回首次实例（不报错）；
     * 不同键但同一张单据已有未结实例时拒 {@code APS_INSTANCE_ALREADY_OPEN} ——
     * 后者是"连点两次提交"与"绕过前端重复发起"的共同防线。
     *
     * <p><b>没有启用流程时怎么办</b>：抛 {@code APS_FLOW_NOT_FOUND}。
     * 这与 {@link #requireApproved} 的"无流程则静默通过"是两件事：
     * 前者是"你主动要审批，但没人配流程"，后者是"单据审核时检查是否需要审批"。
     * 把两者混成一个取值就会出现"没配流程 → 提交审批居然成功了"的假象。
     */
    @Transactional
    public ApprovalInstance submit(SubmitCommand cmd) {
        // ① 幂等：同键重放直接返回首次实例（不报错 —— 连点两次不该让用户看见失败）
        Optional<ApprovalInstance> replay = instances.findByRequestId(cmd.requestId());
        if (replay.isPresent()) {
            return require(replay.get().id());
        }

        // ② 已有未结实例：拒绝（数据库部分唯一索引兜底，这里先给可读的错误）
        instances.findOpen(cmd.bizType(), cmd.bizId()).ifPresent(open -> {
            throw new BusinessException(ErrorCode.APS_INSTANCE_ALREADY_OPEN,
                    "单据 %s 已有未结审批（实例 %d，状态 %s），请先完成或撤回"
                            .formatted(cmd.bizRefNo(), open.id(), open.status()));
        });

        ApprovalFlow flow = flows.findEnabledByBizType(cmd.bizType())
                .orElseThrow(() -> new BusinessException(ErrorCode.APS_FLOW_NOT_FOUND,
                        "单据类型 %s 没有启用中的审批流，无法提交审批"
                                .formatted(cmd.bizType())));

        // ★ V4-3 修复：启用态流程的节点必须在**建实例之前**检查完，且不合格要响亮失败。
        //
        //   原来的写法是"取 flow → 插实例 → 遍历 flow.steps() 快照节点"。
        //   节点为空时（V14 只种了流程头、approval_steps 实际 0 行）循环跑 0 次，
        //   firstActive 停在 null，才在末尾抛 APS_FLOW_INVALID —— 实例随事务回滚，
        //   GET /approvals 恒为 total=0，看起来像"提交成功了但没生成审批"。
        //   紧接着单据侧 approve 的闸门只判断"这张单有没有实例"，于是报
        //   APS_APPROVAL_REQUIRED「需要审批但尚未提交」：一句语义误导的话，
        //   把"流程配置缺节点"说成了"用户忘了提交"，单据永久卡在 SUBMITTED，
        //   进货 → 入库 → 自动生成凭证整条链停摆。
        //
        //   为什么宁可让 submit 直接失败：静默跳过（或报一个误导性的 4xx）
        //   会让用户以为审批已经通过，带着一个永远不会动的单据去干别的事 ——
        //   这类"看起来成功"的失败比一个明确的 500 更危险，因为它不可自愈、
        //   且没有任何一层会把它报出来。配置缺陷必须由离它最近的那一次调用喊出来。
        List<ApprovalStep> definitions = flow.steps();
        if (definitions.isEmpty()) {
            throw new BusinessException(ErrorCode.APS_FLOW_NO_STEPS,
                    "审批流 %s（单据类型 %s）已启用但没有任何审批节点，无法提交审批。"
                            .formatted(flow.code(), cmd.bizType())
                    + "请在迁移里补齐该流程的 approval_steps 定义节点，"
                    + "或把这条流程关掉（POST /approvals/flows/%d/enabled?enabled=false）。"
                            .formatted(flow.id()));
        }
        // 其它配置缺陷（序号不连续、首节点阈值 > 0 导致小额单无节点可走）同样在提交前拒掉。
        // ApprovalFlow#validate() 早就写好了这些规则，但从来没有调用方 —— 一并接上。
        flow.validate().ifPresent(problem -> {
            throw new BusinessException(ErrorCode.APS_FLOW_INVALID,
                    "审批流 %s 配置非法：%s，请先修正流程定义再提交审批"
                            .formatted(flow.code(), problem));
        });

        long initiatorId = requireActor().id();
        long instanceId;
        try {
            instanceId = instances.insert(flow.id(), cmd.bizType(), cmd.bizId(),
                    cmd.bizRefNo(), cmd.amount(), initiatorId, cmd.requestId());
        } catch (DataIntegrityViolationException ex) {
            var translated = com.erp.shared.db.DbErrors.translate(ex,
                    "uq_approval_instance_open", ErrorCode.APS_INSTANCE_ALREADY_OPEN,
                    "单据 %s 已有未结审批（并发提交）".formatted(cmd.bizRefNo()));
            if (translated != null) {
                throw translated;
            }
            throw ex;
        }

        // ③ 快照节点：阈值跳过的节点留 SKIPPED 行（可追溯"这次为什么没走财务"），
        //    但当前节点只落在真正要走的第一个节点上。
        //    definitions 已在上面（建实例之前）取过并校验过，这里直接复用。
        Integer firstActive = null;
        for (ApprovalStep def : definitions) {
            boolean applies = def.appliesTo(cmd.amount());
            ApprovalStep.StepStatus status = applies
                    ? ApprovalStep.StepStatus.PENDING
                    : ApprovalStep.StepStatus.SKIPPED;
            steps.insertInstanceStep(flow.id(), instanceId, def, status);
            if (applies && firstActive == null) {
                firstActive = def.stepNo();
            }
        }
        if (firstActive == null) {
            // 定义校验保证首节点阈值必为 0，所以这里不可达；保守起见明确失败而不是建一个空转实例
            throw new BusinessException(ErrorCode.APS_FLOW_INVALID,
                    "审批流 %s 在金额 %d 下没有任何可执行节点"
                            .formatted(flow.code(), cmd.amount()));
        }

        ApprovalInstance draft = require(instanceId);
        ApprovalStatusMachine.requireTransition(draft.status(), ApprovalStatus.PENDING,
                "instance-" + instanceId);
        if (!instances.updateStatus(instanceId, ApprovalStatus.DRAFT, ApprovalStatus.PENDING,
                firstActive, draft.version())) {
            throw new BusinessException(ErrorCode.APS_STATUS_ILLEGAL,
                    "审批实例 %d 提交时发生并发冲突，请重试".formatted(instanceId));
        }
        return require(instanceId);
    }

    // ─────────────────────────── 同意 / 驳回 ───────────────────────────

    /**
     * 同意当前节点。
     *
     * <p>三种会签模式的落点都在 {@link ApprovalStep#isSatisfiedBy}：
     * <ul>
     *   <li>SINGLE / ANY_SIGN —— 一人同意即推进；</li>
     *   <li>ALL_SIGN —— 计够 {@code requiredApprovals} 个<b>不同</b>的人才推进，
     *       未凑够时实例保持 PENDING、只是多了一票（返回同一实例）。</li>
     * </ul>
     *
     * <p><b>同一个人重复同意是幂等的</b>：数据库用 {@code NOT (approved_by @> ...)}
     * 拦住重复计票，应用层据此不报错、也不多算一票。
     *
     * <p>当前节点通过后：还有下一个 PENDING 节点 → 推进到它；
     * 没有了 → 实例置 APPROVED（终态）。
     */
    @Transactional
    public ApprovalInstance approve(long instanceId, String remark) {
        ActorContext.Actor actor = requireActor();
        ApprovalInstance instance = requireInFlight(instanceId);
        ApprovalStep current = requireCurrentStep(instance);

        if (!actor.hasPermission(current.approverPermission())) {
            throw new BusinessException(ErrorCode.APS_NOT_APPROVER,
                    "当前账号无权处理审批节点「%s」：需要权限 %s，当前仅有 %s"
                            .formatted(current.name(), current.approverPermission(),
                                    actor.permissions()));
        }

        // ① 计票（同一人只计一次；重复点击不报错、不多算）
        steps.addApproval(current.id(), actor.id());
        var afterVote = steps.findInstanceStep(instanceId, current.stepNo())
                .orElseThrow(() -> new BusinessException(ErrorCode.APS_INSTANCE_NOT_FOUND,
                        "审批节点消失：instanceId=%d, stepNo=%d"
                                .formatted(instanceId, current.stepNo())));

        // ② 会签未凑够：保持 PENDING，返回当前实例（不是错误 —— 这是正常中间态）
        if (!afterVote.isSatisfiedBy(afterVote.approvedBy())) {
            return require(instanceId);
        }

        // ③ 本节点通过
        var now = clockNow();
        steps.markApproved(current.id(), actor.id(), now, trimToNull(remark));

        // ④ 找下一个真正要走的节点
        var all = steps.findInstanceSteps(instanceId);
        Optional<ApprovalStep> next = all.stream()
                .filter(s -> s.stepNo() > current.stepNo())
                .filter(s -> s.status() == ApprovalStep.StepStatus.PENDING)
                .findFirst();

        if (next.isPresent()) {
            int nextNo = next.get().stepNo();
            // ★ 这里**刻意不调** ApprovalStatusMachine.requireTransition(PENDING, PENDING)：
            //   审批状态机把自环定义为非法（"原地不动"没有业务语义），
            //   而"推进到下一节点"根本不是状态变化 —— 状态仍是 PENDING，
            //   变的只是 current_step 指针。把指针移动硬塞进状态转移校验，
            //   会得到"PENDING 不能变更为 PENDING"这种自相矛盾的报错
            //   （第一版就是这样，7 条用例全挂在同一句上）。
            //   真正的状态合法性由"当前必须在途（PENDING/TRANSFERRED）"在
            //   requireInFlight 里保证。
            if (!instances.updateStatus(instanceId, ApprovalStatus.PENDING, ApprovalStatus.PENDING,
                    nextNo, instance.version())) {
                throw new BusinessException(ErrorCode.APS_STATUS_ILLEGAL,
                        "审批实例 %d 推进节点时发生并发冲突，请重试".formatted(instanceId));
            }
            return require(instanceId);
        }

        // ⑤ 全部节点走完 → 通过（终态）
        ApprovalStatusMachine.requireTransition(instance.status(), ApprovalStatus.APPROVED,
                "instance-" + instanceId);
        if (!instances.updateStatus(instanceId, ApprovalStatus.PENDING, ApprovalStatus.APPROVED,
                current.stepNo(), instance.version())) {
            throw new BusinessException(ErrorCode.APS_STATUS_ILLEGAL,
                    "审批实例 %d 通过时发生并发冲突，请重试".formatted(instanceId));
        }
        return require(instanceId);
    }

    /**
     * 驳回当前节点：实例立即终止为 REJECTED，剩余节点统一 SKIPPED。
     *
     * <p>驳回不需要会签计数 —— 一个人反对就够了（这是审批的常规语义，
     * 也与"或签/会签只影响同意侧"一致）。
     */
    @Transactional
    public ApprovalInstance reject(long instanceId, String remark) {
        ActorContext.Actor actor = requireActor();
        ApprovalInstance instance = requireInFlight(instanceId);
        ApprovalStep current = requireCurrentStep(instance);

        if (!actor.hasPermission(current.approverPermission())) {
            throw new BusinessException(ErrorCode.APS_NOT_APPROVER,
                    "当前账号无权驳回审批节点「%s」：需要权限 %s"
                            .formatted(current.name(), current.approverPermission()));
        }

        var now = clockNow();
        steps.markRejected(current.id(), actor.id(), now, trimToNull(remark));
        steps.skipPending(instanceId);

        ApprovalStatusMachine.requireTransition(instance.status(), ApprovalStatus.REJECTED,
                "instance-" + instanceId);
        if (!instances.updateStatus(instanceId, instance.status(), ApprovalStatus.REJECTED,
                current.stepNo(), instance.version())) {
            throw new BusinessException(ErrorCode.APS_STATUS_ILLEGAL,
                    "审批实例 %d 驳回时发生并发冲突，请重试".formatted(instanceId));
        }
        return require(instanceId);
    }

    // ─────────────────────────── 撤回 / 转办 / 取消 ───────────────────────────

    /**
     * 撤回：<b>只有发起人本人</b>能撤回，且只能在通过/驳回之前。
     *
     * <p>"只有本人"不是可配置的策略：让别人替你撤回，等于让审批链上的任意一方
     * 能单方面抹掉在途审批，那与"驳回"就没区别了，却绕过了驳回留痕。
     */
    @Transactional
    public ApprovalInstance withdraw(long instanceId, String reason) {
        ActorContext.Actor actor = requireActor();
        ApprovalInstance instance = requireInFlight(instanceId);
        if (instance.initiatorId() != actor.id()) {
            throw new BusinessException(ErrorCode.APS_NOT_APPROVER,
                    "只有发起人本人能撤回审批：实例 %d 的发起人是 %d，当前账号是 %d"
                            .formatted(instanceId, instance.initiatorId(), actor.id()));
        }
        steps.skipPending(instanceId);
        ApprovalStatusMachine.requireTransition(instance.status(), ApprovalStatus.WITHDRAWN,
                "instance-" + instanceId);
        if (!instances.updateStatus(instanceId, instance.status(), ApprovalStatus.WITHDRAWN,
                instance.currentStep(), instance.version())) {
            throw new BusinessException(ErrorCode.APS_STATUS_ILLEGAL,
                    "审批实例 %d 撤回时发生并发冲突，请重试".formatted(instanceId));
        }
        return require(instanceId);
    }

    /**
     * 转办：把当前节点交给另一位具备该节点权限的人。
     *
     * <p>实例状态走 {@code PENDING -> TRANSFERRED}（不是终态），接办人仍可同意/驳回。
     * 转办只改"谁该处理"，不改节点定义，因此不必重建快照。
     *
     * <p><b>为什么转办要校验目标人具备节点权限</b>：否则可以把审批转给一个
     * 完全没有 {@code pur:approve} 的账号，然后由他"同意"—— 这就是绕开 RBAC。
     * 权限码是审批人的唯一资格来源，转办必须守住同一条线。
     */
    @Transactional
    public ApprovalInstance transfer(long instanceId, long targetUserId, String reason,
                                     java.util.function.LongPredicate targetHasPermissionCheck) {
        ActorContext.Actor actor = requireActor();
        ApprovalInstance instance = requireInFlight(instanceId);
        ApprovalStep current = requireCurrentStep(instance);

        if (!actor.hasPermission(current.approverPermission())) {
            throw new BusinessException(ErrorCode.APS_NOT_APPROVER,
                    "当前账号无权转办审批节点「%s」：需要权限 %s"
                            .formatted(current.name(), current.approverPermission()));
        }
        if (!targetHasPermissionCheck.test(targetUserId)) {
            throw new BusinessException(ErrorCode.APS_NOT_APPROVER,
                    "转办目标账号 %d 不具备节点「%s」要求的权限 %s，不能接收该审批"
                            .formatted(targetUserId, current.name(), current.approverPermission()));
        }

        // ★ 只跳过**当前节点之后**的待处理节点，当前节点本身保持 PENDING 并清零票数。
        //   若先用 skipPending 把当前节点也标成 SKIPPED，reopen 就会更新 0 行，
        //   于是接办人拿到"没有待处理的当前节点"（第一版实测就是这个错误）。
        steps.skipPendingAfter(instanceId, current.stepNo());
        steps.reopen(instanceId, current.stepNo());

        ApprovalStatusMachine.requireTransition(instance.status(), ApprovalStatus.TRANSFERRED,
                "instance-" + instanceId);
        if (!instances.updateStatus(instanceId, instance.status(), ApprovalStatus.TRANSFERRED,
                current.stepNo(), instance.version())) {
            throw new BusinessException(ErrorCode.APS_STATUS_ILLEGAL,
                    "审批实例 %d 转办时发生并发冲突，请重试".formatted(instanceId));
        }
        return require(instanceId);
    }

    /**
     * 接办人接受转办：{@code TRANSFERRED -> PENDING}，节点回到正常待办。
     *
     * <p>为什么需要单独一步而不是转办即待办：转办与接办是两个主体的动作。
     * 若转办直接把状态写回 PENDING，原处理人就可以把审批"甩"给一个不知情的人，
     * 而对方在待办里看到它时已经处于"正在审"的状态，责任归属是糊的。
     */
    @Transactional
    public ApprovalInstance acceptTransfer(long instanceId) {
        ActorContext.Actor actor = requireActor();
        ApprovalInstance instance = require(instanceId);
        if (instance.status() != ApprovalStatus.TRANSFERRED) {
            throw new BusinessException(ErrorCode.APS_STATUS_ILLEGAL,
                    "审批实例 %d 当前状态 %s 不是已转办，无需接办"
                            .formatted(instanceId, instance.status()));
        }
        ApprovalStep current = requireCurrentStep(instance);
        if (!actor.hasPermission(current.approverPermission())) {
            throw new BusinessException(ErrorCode.APS_NOT_APPROVER,
                    "当前账号无权接办审批节点「%s」：需要权限 %s"
                            .formatted(current.name(), current.approverPermission()));
        }
        ApprovalStatusMachine.requireTransition(instance.status(), ApprovalStatus.PENDING,
                "instance-" + instanceId);
        if (!instances.updateStatus(instanceId, ApprovalStatus.TRANSFERRED,
                ApprovalStatus.PENDING, instance.currentStep(), instance.version())) {
            throw new BusinessException(ErrorCode.APS_STATUS_ILLEGAL,
                    "审批实例 %d 接办时发生并发冲突，请重试".formatted(instanceId));
        }
        return require(instanceId);
    }

    /**
     * 单据作废/红冲时取消未结审批（{@link ApprovalGate#cancelOpen} 的落点）。
     *
     * <p>幂等：没有未结实例时直接返回 false，不报错 —— 作废路径不该因为
     * "本来就没有审批"而失败。已经有终态实例时也不动它（历史不可改写）。
     */
    @Transactional
    public boolean cancelOpen(ApprovalGate.BizType bizType, long bizId, String reason) {
        Optional<ApprovalInstance> open = instances.findOpen(bizType, bizId);
        if (open.isEmpty()) {
            return false;
        }
        ApprovalInstance instance = open.get();
        steps.skipPending(instance.id());
        ApprovalStatusMachine.requireTransition(instance.status(), ApprovalStatus.CANCELLED,
                "instance-" + instance.id());
        return instances.updateStatus(instance.id(), instance.status(),
                ApprovalStatus.CANCELLED, instance.currentStep(), instance.version());
    }

    // ─────────────────────────── 查询 ───────────────────────────

    @Transactional(readOnly = true)
    public ApprovalInstance require(long instanceId) {
        ApprovalInstance found = instances.findById(instanceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.APS_INSTANCE_NOT_FOUND,
                        "审批实例不存在：id=%d".formatted(instanceId)));
        return new ApprovalInstance(found.id(), found.flowId(), found.bizType(), found.bizId(),
                found.bizRefNo(), found.amount(), found.currentStep(), found.status(),
                found.initiatorId(), found.version(), steps.findInstanceSteps(instanceId));
    }

    @Transactional(readOnly = true)
    public PageResult<ApprovalInstance> page(ApprovalStatus status, ApprovalGate.BizType bizType,
                                             int page, int size) {
        PageResult<ApprovalInstance> raw = instances.page(status, bizType, page, size);
        List<ApprovalInstance> items = raw.items().stream().map(i -> require(i.id())).toList();
        return new PageResult<>(items, raw.total(), raw.page(), raw.size());
    }

    /** 某单据类型的启用流程（供前端展示"这条单据会走几级审批"）。 */
    @Transactional(readOnly = true)
    public Optional<ApprovalFlow> enabledFlow(ApprovalGate.BizType bizType) {
        return flows.findEnabledByBizType(bizType);
    }

    /** 全部流程定义（含节点）——供接口只读展示，Controller 不直连仓储。 */
    @Transactional(readOnly = true)
    public List<ApprovalFlow> listFlows() {
        return flows.findAll();
    }

    /**
     * 启停流程定义。
     *
     * <p>启用时可能撞"同类型已有启用流"的部分唯一索引 —— 翻译成
     * {@code APS_FLOW_INVALID} 而不是把约束违例原样抛成 500。
     */
    @Transactional
    public ApprovalFlow setFlowEnabled(long flowId, boolean enabled) {
        flows.findById(flowId)
                .orElseThrow(() -> new BusinessException(ErrorCode.APS_FLOW_NOT_FOUND,
                        "审批流不存在：id=%d".formatted(flowId)));
        try {
            if (!flows.setEnabled(flowId, enabled)) {
                throw new BusinessException(ErrorCode.APS_FLOW_NOT_FOUND,
                        "审批流不存在：id=%d".formatted(flowId));
            }
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            var translated = com.erp.shared.db.DbErrors.translate(ex,
                    "uq_approval_flow_enabled_biz", ErrorCode.APS_FLOW_INVALID,
                    "该单据类型已有另一条启用中的审批流，请先停用后者");
            if (translated != null) {
                throw translated;
            }
            throw ex;
        }
        return flows.findById(flowId).orElseThrow();
    }

    /** 当前未结的审批实例（供 {@code ApprovalGateService} 判定，避免它直接依赖仓储）。 */
    @Transactional(readOnly = true)
    public Optional<ApprovalInstance> findOpenInstance(ApprovalGate.BizType bizType, long bizId) {
        return instances.findOpen(bizType, bizId);
    }

    /** 最近一次审批实例（不限状态）。 */
    @Transactional(readOnly = true)
    public Optional<ApprovalInstance> findLatestInstance(ApprovalGate.BizType bizType, long bizId) {
        return instances.findLatest(bizType, bizId);
    }

    // ─────────────────────────── 内部辅助 ───────────────────────────

    private ApprovalInstance requireInFlight(long instanceId) {
        ApprovalInstance instance = require(instanceId);
        if (!instance.status().isInFlight()) {
            throw new BusinessException(ErrorCode.APS_STATUS_ILLEGAL,
                    "审批实例 %d 当前状态 %s 不可处理（仅 PENDING / TRANSFERRED 可处理）"
                            .formatted(instanceId, instance.status()));
        }
        return instance;
    }

    private ApprovalStep requireCurrentStep(ApprovalInstance instance) {
        return instance.currentNode()
                .orElseThrow(() -> new BusinessException(ErrorCode.APS_INSTANCE_NOT_FOUND,
                        "审批实例 %d 没有待处理的当前节点（currentStep=%d）"
                                .formatted(instance.id(), instance.currentStep())));
    }

    private ActorContext.Actor requireActor() {
        return actors.current().orElseThrow(() -> new BusinessException(
                ErrorCode.IAM_UNAUTHENTICATED, "未登录或登录已过期，请重新登录"));
    }

    /**
     * 审批的结论时刻。
     *
     * <p>取注入时钟而不是 {@code OffsetDateTime.now()}：架构规则
     * （{@code ArchitectureTest}「业务代码禁止调用系统时间静态方法」）不允许任何
     * {@code now()}，而"审计时间可以例外"这种口子一旦开了，就会被后续代码越用越宽。
     * 生产装配的 {@code ClockProvider} 就是真实时钟，语义不变；
     * 测试里则能精确断言"结论时刻 == 业务时刻"，不必等真实时间。
     */
    private java.time.OffsetDateTime clockNow() {
        return clock.now().atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime();
    }

    private static String trimToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    /** 提交审批的命令。 */
    public record SubmitCommand(ApprovalGate.BizType bizType, long bizId, String bizRefNo,
                                long amount, String requestId) {
    }
}
