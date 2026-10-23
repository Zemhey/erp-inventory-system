p='backend/server/src/test/java/com/erp/arch/ArchitectureTest.java'
s=open(p,encoding='utf-8').read()

anchor="""    // ─────────────────────── 辅助实现 ───────────────────────"""
new_rules = """    // ───────────────── A 规则 10：审批模块不得依赖单据仓储（V2-6 FR-APS-02） ─────────────────

    /**
     * ★ {@code aps} 不得依赖<b>任何单据/库存/结算模块的 repository</b>。
     *
     * <p><b>这条守的是"审批只输出可否，不改单据状态"</b>（任务书坑 2、坑 3）：
     * 审批状态机（{@code ApprovalStatus} 七态）与单据状态机（{@code DocumentStatus} 七态）
     * 是两套完全不同的东西。一旦 APS 注入 {@code PurchaseOrderRepository}，
     * 最顺手（也最致命）的写法就出现了 ——
     * 审批通过时直接 {@code UPDATE purchase_orders SET status='APPROVED'}。
     * 那会同时绕过三件事：
     * <ul>
     *   <li>{@code DocumentStatusMachine}（非法转移校验，含 V8 红冲的补偿性转移规则）；</li>
     *   <li>{@code PeriodGuard}（锁账后不允许改动期间内单据）；</li>
     *   <li>单据的乐观锁 {@code version}（并发审核会丢更新）。</li>
     * </ul>
     *
     * <p>正确边界：APS 只持 {@code bizType + bizId + bizRefNo + amount}，
     * 单据侧通过 {@code shared.approval.ApprovalGate} 把自己的信息<b>推</b>进来、
     * 再自己调 {@code DocumentStatusMachine}。依赖方向单向，两条状态机各走各路。
     *
     * <p>写成"排除 aps 自己的 repository"而不是枚举别人的包名：
     * 枚举式的规则会随新模块增加而悄悄失效（与「报表只读」规则同一个教训）。
     */
    @ArchTest
    static final ArchRule 审批模块不得依赖任何业务模块仓储 =
            noClasses()
                    .that().resideInAPackage("com.erp.aps..")
                    .should().dependOnClassesThat(
                            com.tngtech.archunit.core.domain.JavaClass.Predicates
                                    .resideInAPackage("..repository..")
                                    .and(com.tngtech.archunit.core.domain.JavaClass.Predicates
                                            .resideOutsideOfPackage("com.erp.aps.repository..")))
                    .because("审批只输出「可否」，不替单据做状态推进；"
                            + "直连单据仓储会绕过 DocumentStatusMachine / PeriodGuard / 乐观锁"
                            + "（FR-APS-02，任务书坑 3）");

    /**
     * ★ 同一条边界的另一面：{@code aps} 也不得依赖单据的<b>领域类型</b>
     * （{@code PurchaseOrder} / {@code SalesOrder} / {@code ReturnOrder}）。
     *
     * <p>只拦 repository 是不够的：{@code orders.findById(id).map(PurchaseOrder::status)}
     * 这种写法完全不碰仓储之外的写路径，但它已经让 APS 理解了"单据有状态"，
     * 下一步就会有人写 {@code if (order.status() == APPROVED) ...} —— 于是审批模块里
     * 出现第二份单据状态判断，而它并不遵守 ARchUnit 的「单据模块必须复用统一状态机」规则
     * （那条规则只覆盖 pur/sal/rma 的 ApplicationService）。
     */
    @ArchTest
    static final ArchRule 审批模块不得依赖单据领域类型 =
            noClasses()
                    .that().resideInAPackage("com.erp.aps..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.erp.pur.domain..", "com.erp.sal.domain..",
                            "com.erp.rma.domain..")
                    .because("审批不认识任何单据类型；金额/单号由调用方推入，"
                            + "否则审批里会长出第二份单据状态判断（任务书坑 3）");

    // ─────────────────────── 辅助实现 ───────────────────────"""
assert anchor in s
s=s.replace(anchor,new_rules,1)

# append self-check test at end of class (before final closing brace of class)
anchor2 = """    /**
     * 在内存中编译一段违规源码，并用<b>显式导入器</b>分析它。"""
selfcheck = """    /**
     * ★ 自检：现场编译一段"审批模块直连进货单仓储"的违规代码，断言规则拦得住。
     *
     * <p>探针放进真实的 {@code com.erp.aps.application} 包，并 evaluate
     * <b>生产规则本身</b>：规则被改窄（例如只拦 {@code aps.api}）时本用例立刻红。
     * 违规样本刻意用"最顺手的写法"—— 注入仓储并在审批通过时改单据状态，
     * 这正是这条规则要防的那一次重构。
     */
    @org.junit.jupiter.api.Test
    @CaseId("TC-ARCH-009")
    @org.junit.jupiter.api.DisplayName("★ 规则自检：审批模块不得依赖单据仓储的规则必须拦住违规 import")
    void 规则自检_审批不得依赖单据仓储规则必须生效() {
        var classes = compileProbe("com.erp.aps.application.BadApprovalRepositoryProbe", \"\"\"
                package com.erp.aps.application;

                public class BadApprovalRepositoryProbe {
                    private final com.erp.pur.repository.PurchaseOrderRepository repo = null;

                    public Object bad() {
                        return repo;
                    }
                }
                \"\"\");
        org.assertj.core.api.Assertions.assertThat(
                审批模块不得依赖任何业务模块仓储.evaluate(classes).hasViolation())
                .as("★ 规则必须能拦住 aps -> 单据仓储的直连；"
                        + "若为 false 说明审批可以绕过状态机直接改单据（假绿）")
                .isTrue();
    }

    /**
     * ★ 自检：审批模块依赖单据<b>领域类型</b>也必须被拦住。
     *
     * <p>这条与上一条一起，把"审批不认识单据类型"钉成一个双向命题：
     * 只拦仓储，`order.status() == APPROVED` 这种写法就能溜进来。
     */
    @org.junit.jupiter.api.Test
    @CaseId("TC-ARCH-010")
    @org.junit.jupiter.api.DisplayName("★ 规则自检：审批模块不得依赖单据领域类型的规则必须拦住违规引用")
    void 规则自检_审批不得依赖单据领域类型规则必须生效() {
        var classes = compileProbe("com.erp.aps.application.BadApprovalDomainProbe", \"\"\"
                package com.erp.aps.application;

                public class BadApprovalDomainProbe {
                    public boolean bad(com.erp.pur.domain.PurchaseOrder order) {
                        return order != null
                                && order.status() == com.erp.shared.document.DocumentStatus.APPROVED;
                    }
                }
                \"\"\");
        org.assertj.core.api.Assertions.assertThat(
                审批模块不得依赖单据领域类型.evaluate(classes).hasViolation())
                .as("★ 规则必须能拦住 aps -> 单据领域类型的引用；"
                        + "若为 false 说明审批里可以长出第二份单据状态判断（假绿）")
                .isTrue();
    }

    /**
     * 在内存中编译一段违规源码，并用<b>显式导入器</b>分析它。"""
assert anchor2 in s
s=s.replace(anchor2,selfcheck,1)
open(p,'w',encoding='utf-8').write(s)
print("patched ArchitectureTest")
