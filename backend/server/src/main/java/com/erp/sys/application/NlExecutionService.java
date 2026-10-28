package com.erp.sys.application;

import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.web.PageResult;
import com.erp.sys.domain.NlAuditFilter;
import com.erp.sys.domain.NlExecution;
import com.erp.sys.domain.NlExecutionStatus;
import com.erp.sys.domain.NlExecutionView;
import com.erp.sys.repository.NlExecutionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 自然语言操作的记录服务（FR-V4-03 · S5/S6）：待确认队列 + 审计查询。
 *
 * <p><b>为什么不叫 "AuditService"</b>：它管的是一件事的**生命周期**，
 * 而 {@code AuditQueryService} 管的是"已经发生的事实"（append-only）。
 * 两者的写权限完全不同，混在一个类里，后改的人很容易把 update 加到审计那一侧去。
 *
 * <p><b>入参全是字符串和基本类型</b>，不出现 {@code NlPlan} / {@code NlReview}：
 * sys 模块按架构规则只依赖 shared，解析 JSON 这种"装配视图"的活在
 * {@link #view(NlExecution)} 里用 Jackson 做，不需要知道 NL 层的类型。
 */
@Service
public class NlExecutionService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final NlExecutionRepository repository;
    private final ObjectMapper json;
    private final long ttlSeconds;

    public NlExecutionService(NlExecutionRepository repository,
                              ObjectMapper json,
                              @Value("${erp.nl.confirm.ttl-seconds:300}") int ttlSeconds) {
        this.repository = repository;
        this.json = json;
        this.ttlSeconds = Math.max(30, ttlSeconds);
    }

    // ─────────────────────────── 写 ───────────────────────────

    /**
     * 记录一次"打算做什么"。
     *
     * <p>状态在这里判定，而不是让调用方各自决定：三个调用点（页面 / MCP / 脚本）
     * 各写一遍判断，迟早出现"页面标了 BLOCKED、MCP 标了 PENDING"。规则只有一条：
     * <b>参数不全 → BLOCKED；复核否决 → VETOED；其余 → PENDING。</b>
     *
     * @param executable 计划是否完整到可以执行
     * @param verdict    复核结论（APPROVE / NEED_HUMAN / REJECT）
     */
    @Transactional
    public long recordPlan(String requestId, long actorId, String actorName, String source,
                           String commandText, String intent, boolean write, boolean executable,
                           String verdict, String reviewSource, String summary,
                           String planJson, String reviewJson) {
        NlExecutionStatus status;
        if (!executable) {
            status = NlExecutionStatus.BLOCKED;
        } else if ("REJECT".equalsIgnoreCase(verdict)) {
            status = NlExecutionStatus.VETOED;
        } else {
            status = NlExecutionStatus.PENDING;
        }
        // 只有 PENDING 需要截止时间；BLOCKED / VETOED 永远不会被确认，签个时间没意义
        Instant expiresAt = status == NlExecutionStatus.PENDING
                ? Instant.now().plusSeconds(ttlSeconds)
                : null;
        return repository.insert(new NlExecutionRepository.NewExecution(
                requestId, actorId, actorName, source, commandText, intent, write,
                status, verdict, reviewSource, summary, planJson, reviewJson, expiresAt));
    }

    /** 执行成功：回填单号。找不到对应记录（比如只读意图）时就静默跳过——那不是异常。 */
    public void markExecuted(String requestId, String docType, Long docId, String docNo,
                             String resultJson, long actorId, String actorName) {
        repository.markExecuted(requestId, docType, docId, docNo, resultJson, actorId, actorName);
    }

    /** 执行报错：把原因留在档案上。它同样回答了"AI 想干什么、为什么没干成"。 */
    public void markFailed(String requestId, String errorMessage) {
        repository.markFailed(requestId, errorMessage);
    }

    @Transactional
    public void dismiss(long id, long actorId, String actorName) {
        if (repository.dismiss(id, actorId, actorName) == 0) {
            NlExecution record = repository.findById(id)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NL_EXECUTION_NOT_FOUND,
                            "操作记录不存在：id=%d".formatted(id)));
            throw new BusinessException(ErrorCode.NL_EXECUTION_NOT_PENDING,
                    "这条记录当前是「%s」，不能再驳回".formatted(record.status().label()));
        }
    }

    // ─────────────────────────── 读 ───────────────────────────

    @Transactional
    public PageResult<NlExecutionView> pagePending(int page, int size) {
        repository.expireOverdue();
        PageResult<NlExecution> rows = repository.pagePending(page, size);
        return new PageResult<>(rows.items().stream().map(this::view).toList(),
                rows.total(), rows.page(), rows.size());
    }

    @Transactional
    public long countPending() {
        repository.expireOverdue();
        return repository.countPending();
    }

    /** 取一条待确认记录；不存在或已不是 PENDING 都给出明确错误，而不是让调用方自己猜。 */
    @Transactional
    public NlExecutionView requirePending(long id) {
        repository.expireOverdue();
        NlExecution record = repository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NL_EXECUTION_NOT_FOUND,
                        "操作记录不存在：id=%d".formatted(id)));
        if (!record.status().pending()) {
            throw new BusinessException(ErrorCode.NL_EXECUTION_NOT_PENDING,
                    "这条记录当前是「%s」，不能执行".formatted(record.status().label()));
        }
        return view(record);
    }

    @Transactional
    public PageResult<NlExecutionView> pageAudit(NlAuditFilter filter, int page, int size) {
        repository.expireOverdue();
        PageResult<NlExecution> rows = repository.page(filter, page, size);
        return new PageResult<>(rows.items().stream().map(this::view).toList(),
                rows.total(), rows.page(), rows.size());
    }

    @Transactional
    public NlExecutionView require(long id) {
        return view(repository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NL_EXECUTION_NOT_FOUND,
                        "操作记录不存在：id=%d".formatted(id))));
    }

    // ─────────────────────────── 装配 ───────────────────────────

    /**
     * 把一行拼成界面要的形状。
     *
     * <p>JSON 解析失败时给空 Map 而不是抛异常：档案里存的可能是旧版本结构，
     * 一条坏数据不该让整个队列页 500 —— 页面还有 {@code commandText} 可看。
     */
    private NlExecutionView view(NlExecution record) {
        return new NlExecutionView(
                record.id(), record.requestId(), record.actorId(), record.actorName(),
                record.source(), record.commandText(), record.intent(), record.write(),
                record.status().name(), record.status().label(),
                record.verdict(), record.reviewSource(), record.summary(),
                parse(record.planJson()), parse(record.reviewJson()),
                record.docType(), record.docId(), record.docNo(),
                record.errorMessage(), record.createdAt(), record.expiresAt(),
                record.decidedAt(), record.decidedBy(), record.decidedByName());
    }

    private Map<String, Object> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = json.readValue(raw, MAP_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }
}
