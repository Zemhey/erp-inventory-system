p='backend/server/src/main/java/com/erp/aps/application/ApprovalApplicationService.java'
s=open(p,encoding='utf-8').read()
old="""    /** 当前未结的审批实例（供 {@code ApprovalGateService} 判定，避免它直接依赖仓储）。 */"""
new="""    /** 全部流程定义（含节点）——供接口只读展示，Controller 不直连仓储。 */
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

    /** 当前未结的审批实例（供 {@code ApprovalGateService} 判定，避免它直接依赖仓储）。 */"""
assert old in s
s=s.replace(old,new,1)
open(p,'w',encoding='utf-8').write(s)
print("service flow ops added")
