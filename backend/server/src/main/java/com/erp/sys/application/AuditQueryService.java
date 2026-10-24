package com.erp.sys.application;

import com.erp.sys.domain.AuditLog;
import com.erp.sys.domain.AuditQueryFilter;
import com.erp.sys.repository.AuditLogQueryRepository;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.web.PageResult;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审计查询服务（FR-SYS-07）—— <b>只读</b>。
 *
 * <p><b>"只读不可改"怎么被守住</b>：本类只有 {@code page / require / timeline / byTrace}
 * 四个查询方法，没有一行 INSERT/UPDATE/DELETE；
 * {@code ArchitectureTest} 另有一条规则断言审计仓储不存在 update/delete 方法（TC-ARCH-009），
 * 数据库触发器再拦一道。三层里任意两层同时失效，第三层仍然拦得住。
 *
 * <p>审计查询故意<b>不做</b>数据权限过滤（谁能看谁的审计）：本期只要求"按人/单据/时间/
 * 操作类型"能查，按仓/按人的数据权限属 V2-6 的范围。给它加个半成品过滤
 * 比不加更危险 —— 会让评审误以为越权已被拦住。
 */
@Service
public class AuditQueryService {

    private final AuditLogQueryRepository repository;

    public AuditQueryService(AuditLogQueryRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public PageResult<AuditLog> page(AuditQueryFilter filter, int page, int size) {
        return repository.page(filter, page, size);
    }

    @Transactional(readOnly = true)
    public AuditLog require(long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.SYS_AUDIT_NOT_FOUND,
                        "审计记录不存在：id=%d".formatted(id)));
    }

    /**
     * 某张业务单据的完整轨迹（按时间正序）。
     *
     * <p>正序而不是倒序：读这张单的经历时，人是从"建单"往下看的。
     * 契约里 {@code GET /audits} 的列表默认倒序（最新在前），
     * 单据轨迹用正序，两种顺序各有各的用途。
     */
    @Transactional(readOnly = true)
    public List<AuditLog> timeline(String bizRefNo) {
        if (bizRefNo == null || bizRefNo.isBlank()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR, "单据号不能为空");
        }
        return repository.findByBizRefNo(bizRefNo.trim());
    }

    /** 按 traceId 取该请求产生的全部审计（traceId 贯穿的落库侧证据）。 */
    @Transactional(readOnly = true)
    public List<AuditLog> byTrace(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR, "traceId 不能为空");
        }
        return repository.findByTraceId(traceId.trim());
    }
}
