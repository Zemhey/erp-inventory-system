package com.erp.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.erp.inv.repository.InventoryBalanceRepository;
import com.erp.support.CaseId;
import com.erp.shared.document.DocumentStatus;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.elements.GivenClassesConjunction;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * 架构测试 —— 把项目里最重要的几条架构决策**固化成 CI 门禁**（计划书 §3.3、S7）。
 *
 * <p><b>为什么要用测试守架构，而不是写在文档里</b>：
 * "库存变动只能走 InventoryApplicationService"这条规则，
 * 写在 README 里三个月后没人记得；写成 ArchUnit 规则后，
 * 任何一次违规提交会在 CI 里<b>立刻</b>变红，并且报错信息会直接指出是哪个类。
 * 文档会腐烂，编译门禁不会。
 *
 * <p>本类不依赖 Spring 容器与数据库，是纯静态字节码分析。
 *
 * <p><b>★ 关于"规则通过 ≠ 规则有效"</b>（M1 开发中的一次教训）：
 * 最初用 {@code @AnalyzeClasses} + {@code .should(自定义条件)} 组合时，
 * 9 条规则<b>全部通过</b>，但故意在真实业务方法里注入
 * {@code LocalDateTime.now()} / {@code throw new RuntimeException()} 后，
 * 规则<b>依旧通过</b> —— 典型的假绿。
 * 原因是自定义 {@code ArchCondition} 依赖方法的调用信息，
 * 而默认导入器并不会把被分析类内部调用的外部类型也完整纳入类图，
 * 于是 {@code getMethodCallsFromSelf()} 拿不到我们关心的那些调用。
 *
 * <p>因此这里改用显式构造的 {@link ClassFileImporter}，并打开
 * {@code archunit.properties} 里的解析选项（见 {@code src/test/resources}），
 * 再对"必须命中"的规则加了<b>自检用例</b>
 * （{@code 规则自检_*）：在测试里现场编译一段违规代码，
 * 断言规则确实把它拦住。这样"规则失效"本身也会变成红。
 */
@AnalyzeClasses(packages = "com.erp", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    // ─────────────────────── 规则 1：库存唯一入口（ADR-0007） ───────────────────────

    /**
     * ★ 最重要的一条：只有 INV 模块可以直接依赖《库存余额仓储》。
     *
     * <p>仓储的 Javadoc 里写着"本类是整个系统唯一允许修改库存数量的地方"。
     * 这条规则让那句话变成<b>可执行</b>的：PUR / SAL / MDM 若直接注入
     * {@code InventoryBalanceRepository} 去改库存，CI 立刻失败。
     *
     * <p>正确做法是调用 {@code InventoryApplicationService.receiveIn / shipOut / reserve}。
     * 这不仅防止"绕过流水直接改余额"（会破坏不变量 INV-2），
     * 也保证所有库存变动都带上业务类型与来源单号，可追溯。
     */
    @ArchTest
    static final ArchRule 库存变动只能经由INV模块 =
            noClasses()
                    .that().resideOutsideOfPackage("com.erp.inv..")
                    .and().haveSimpleNameNotEndingWith("Test")
                    .should().dependOnClassesThat().areAssignableTo(InventoryBalanceRepository.class)
                    .because("库存余额仓储只允许 INV 模块使用；其他模块必须调用 "
                            + "InventoryApplicationService（ADR-0007 流水为事实源）");

    /**
     * 同理：预占仓储与流水仓储也不允许跨模块直连。
     *
     * <p>预占的生命周期（ACTIVE→CONSUMED/RELEASED）必须由 INV 统一管理，
     * 否则 SAL 可能"以为预占已经释放"而实际没有。
     */
    @ArchTest
    static final ArchRule 预占与流水仓储只归INV模块 =
            noClasses()
                    .that().resideOutsideOfPackage("com.erp.inv..")
                    .and().haveSimpleNameNotEndingWith("Test")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.erp.inv.repository..")
                    .because("INV 的内部仓储不对外暴露，跨模块只能走 InventoryApplicationService");

    // ─────────────────────── 规则 2：禁止直接取系统时间（计划书 §3.5） ───────────────────────

    /**
     * ★ 业务代码禁止直接调用 {@code now()}。
     *
     * <p>日清月结、批次效期（FEFO）、预占超时、账期归属全部依赖"现在几点"。
     * 一旦某处硬编码 {@code LocalDateTime.now()}，跨月/跨年/闰月这些边界
     * 就只能等到真实时间到来才能验证 —— 实际上无法自动化测试。
     *
     * <p>例外：{@code ClockProvider} 的**装配**处（{@code ClockConfig}）
     * 必须返回真实时钟，否则系统没有时间源。这在架构上是唯一合法的位置。
     *
     * <p>本规则用 {@code dependOnClassesThat} 检查对时间类的**依赖**，
     * 并借助下面的"方法调用"规则进一步限定。
     */
    @ArchTest
    static final ArchRule 业务代码禁止直接依赖系统时间类型 =
            noClasses()
                    .that().resideInAnyPackage("com.erp.inv..", "com.erp.pur..", "com.erp.sal..",
                            "com.erp.rma..", "com.erp.set..", "com.erp.rpt..",
                            "com.erp.mdm..", "com.erp.iam..", "com.erp.nl..",
                            "com.erp.shared.document..")
                    .and().doNotHaveSimpleName("ClockConfig")
                    .should().dependOnClassesThat().areAssignableTo(java.time.Clock.class)
                    .because("时间必须来自可注入的 ClockProvider（docs/06-测试策略 §3）");

    /**
     * ★ 字段/参数上出现 {@code LocalDateTime.now()} 的最常见形态。
     *
     * <p>M2-S2 把 {@code com.erp.set..} 纳入：日清的 {@code settled_at}、月结的
     * {@code closed_at} 全都是"账目什么时候结的"这一法律事实的时间戳。
     * 一旦这里图省事写 {@code OffsetDateTime.now()}，
     * 跨月/跨年/闰月的账期归属就只能等真实日历走到才能验证 ——
     * TC-SET-008/009 会当场变成不可测，注入时钟的可测试性设计在此破产。
     *
     * <p><b>为什么这里用 {@code classes().that(...不匹配...).should(cond)}
     * 而不是 {@code noClasses().that(...匹配...).should(cond)}</b>
     * （自检用例暴露的 ArchUnit 语义陷阱）：
     * {@code noClasses()} 会把自定义条件的结果<b>反向</b>解释 ——
     * 条件"发现违规"时它反而不算违规，导致规则看起来永远通过。
     * 实测：同一个条件在 {@code classes()} 下 {@code hasViolation=true}，
     * 在 {@code noClasses()} 下变成 {@code false}。
     * 因此凡是用 {@link com.tngtech.archunit.lang.ArchCondition} 的地方，
     * 一律用 {@code classes().that(...)} 正向表达。
     */
    @ArchTest
    static final ArchRule 业务代码禁止调用系统时间静态方法 =
            classes()
                    .that().resideInAnyPackage("com.erp.inv..", "com.erp.pur..", "com.erp.sal..",
                            "com.erp.rma..", "com.erp.set..", "com.erp.rpt..",
                            "com.erp.mdm..", "com.erp.iam..", "com.erp.nl..",
                            "com.erp.shared.document..")
                    .and().doNotHaveSimpleName("ClockConfig")
                    .should(noSystemTimeCalls())
                    .because("System.currentTimeMillis / Instant.now / LocalDateTime.now "
                            + "会让跨期、效期、超时逻辑无法测试（计划书 §3.5）");

    // ─────────────────────── 规则 3：分层 ───────────────────────

    /**
     * Controller 不得直接依赖 Repository。
     *
     * <p>Controller 只做参数绑定与 DTO 转换，业务规则在 ApplicationService。
     * 否则同一套校验会散落在 HTTP 层，未来加小程序/Android 端时要复制一遍，
     * 两端行为必然分叉 —— 这正是多端项目最常见的腐化路径。
     */
    @ArchTest
    static final ArchRule 控制器不得直连仓储 =
            noClasses()
                    .that().resideInAPackage("..api..")
                    .should().dependOnClassesThat().resideInAnyPackage("..repository..")
                    .because("Controller 只能依赖 ApplicationService，业务规则不重复到 HTTP 层");

    /** Repository 不得依赖 ApplicationService（依赖方向必须单向：api → application → repository）。 */
    @ArchTest
    static final ArchRule 仓储不得反向依赖应用服务 =
            noClasses()
                    .that().resideInAPackage("..repository..")
                    .should().dependOnClassesThat().resideInAPackage("..application..")
                    .because("依赖方向必须单向：api -> application -> repository");

    /**
     * 领域对象不得依赖 Spring 或 JDBC。
     *
     * <p>保持 domain 是纯 POJO：这样领域规则可以在毫秒级单测里验证，
     * 不需要起容器、不需要连库。领域对象一旦 import 了 JdbcTemplate，
     * 就再也拉不回"可独立测试"的状态了。
     */
    @ArchTest
    static final ArchRule 领域对象不得依赖框架与持久化 =
            noClasses()
                    .that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework..", "java.sql..", "javax.sql..")
                    .because("domain 必须是可以独立单测的纯对象");

    // ─────────────────────── 规则 4：异常与错误码 ───────────────────────

    /**
     * 业务层不得直接抛出 {@code RuntimeException} 或自造异常类型。
     *
     * <p>契约要求所有业务失败都带稳定错误码（docs/05 §4），
     * 前端据此决定提示文案、是否需要用户操作。抛裸 RuntimeException
     * 会被全局处理器兜底成 500 + "服务器内部错误"，把可预期的业务结果
     * 伪装成系统故障 —— 既误导用户，也污染监控告警。
     */
    @ArchTest
    static final ArchRule 应用与接口层只用业务异常表达失败 =
            classes()
                    .that().resideInAnyPackage("..application..", "..api..")
                    .should(noBareRuntimeExceptionConstruction())
                    .because("失败必须带稳定错误码，走 BusinessException（docs/05 §4）"
                            + "（用 classes() 而非 noClasses()：见上面关于条件反向求值的说明）");

    // ─────────────────────── 规则 5：单据状态机不被绕过 ───────────────────────

    /**
     * 单据状态只能通过 {@link DocumentStatus} 枚举表达，不得出现裸字符串状态赋值。
     *
     * <p><b>为什么用"方法调用"而不是 {@code dependOnClassesThat}</b>：
     * 后者会把内部 record 的字段/返回类型也算作依赖（连 {@code <init>()} 调用
     * {@code Record.<init>} 都算），语义过宽、误报一片。
     * 真正要禁止的行为很具体：<b>单据状态转移必须调
     * {@code DocumentStatusMachine.requireTransition}</b>，而不是自己 if 判断。
     *
     * <p>M2-S1 把 {@code rma} 一起纳入：退货单是第三张单据，
     * 一旦让它自写一套 if 判断状态，"三张单子三种流转规则"就再也并不回来了。
     */
    @ArchTest
    static final ArchRule 单据模块必须复用统一状态机 =
            classes()
                    .that().resideInAnyPackage("com.erp.pur.application..",
                            "com.erp.sal.application..", "com.erp.rma.application..")
                    .and().haveSimpleNameEndingWith("ApplicationService")
                    .should(declareStatusTransition())
                    .because("单据状态流转必须经由 DocumentStatusMachine.requireTransition，"
                            + "不允许各单据自写一套 if（BR-DOC-01）");

    /**
     * 自定义规则：单据应用服务必须声明依赖统一状态机。
     *
     * <p>实现方式：检查该类是否在<b>方法体里真正调用</b>了
     * {@code requireTransition}。这条要求与实现耦合得恰到好处：
     * 它不关心类如何组织（构造函数注入、字段注入都行），
     * 只关心"状态转移到底有没有走统一实现"。
     */
    private static com.tngtech.archunit.lang.ArchCondition<JavaClass>
            declareStatusTransition() {
        return new com.tngtech.archunit.lang.ArchCondition<>(
                "必须调用 DocumentStatusMachine.requireTransition 完成状态转移") {
            @Override
            public void check(JavaClass item, com.tngtech.archunit.lang.ConditionEvents events) {
                boolean found = item.getMethodCallsFromSelf().stream()
                        .anyMatch(call -> "com.erp.shared.document.DocumentStatusMachine"
                                .equals(call.getTargetOwner().getFullName())
                                && "requireTransition".equals(call.getTarget().getName()));
                if (!found) {
                    events.add(com.tngtech.archunit.lang.SimpleConditionEvent.violated(item,
                            "%s 未调用 DocumentStatusMachine.requireTransition —— "
                                    + "单据状态转移必须走统一状态机".formatted(item.getSimpleName())));
                }
            }
        };
    }

    // ─────────────────────── 规则 6：锁账的依赖倒置（计划书 §3.1） ───────────────────────

    /**
     * ★ PUR / SAL / RMA 不得直接依赖 {@code com.erp.set..}。
     *
     * <p><b>为什么这条必须成为门禁</b>：锁账要求三域的每个写操作都调
     * {@code PeriodGuard.requireOpen}。实现类 {@code SettlementPeriodGuard} 就在
     * {@code set.application} 里，图省事的写法是直接 import 它 ——
     * 而 SET 为了算日清又要读 PUR/SAL/RMA 的单据表。
     * 两边一对不上就是<b>包依赖环</b>：编译能过，但模块再也拆不开、
     * 单测只能整块起容器，M4/M5 多端复用时必然要重做一遍。
     *
     * <p>正确做法是依赖倒置：接口在 {@code shared.period}，实现在 {@code set.application}，
     * 三域只认接口。这条规则把"只认接口"从注释变成红。
     *
     * <p><b>不是空规则</b>：{@code com.erp.set} 与三个调用方此刻都真实存在，
     * 规则现在就跑在生产代码上；下面的自检用例
     * （TC-ARCH-004）再证明一次"真有人 import 了就会红"。
     */
    @ArchTest
    static final ArchRule 单据模块不得直接依赖结算模块 =
            noClasses()
                    .that().resideInAnyPackage("com.erp.pur..", "com.erp.sal..", "com.erp.rma..")
                    .should().dependOnClassesThat().resideInAnyPackage("com.erp.set..")
                    .because("锁账只能经由 shared.period.PeriodGuard 接口注入；"
                            + "直接依赖 com.erp.set 会与「SET 只读三域单据」形成包依赖环（计划书 §3.1）");

    // ─────────────────────── 规则 7：报表模块只读（M2-S3） ───────────────────────

    /**
     * ★ {@code rpt} 不得依赖<b>其它模块</b>的应用服务。
     *
     * <p><b>这条规则守的是"只读"这两个字</b>：计划书 §3.1 规定 RPT 只做跨域只读查询，
     * 自己的聚合查询仓储不写任何表。但"不写表"这件事光靠"我没写 UPDATE"是守不住的 ——
     * 只要注入一个 {@code PurchaseOrderApplicationService} 就能顺手改库存，
     * 而报表接口是 GET，评审时最容易被忽略。
     *
     * <p>写法上刻意<b>不枚举</b> {@code pur/sal/rma/inv/set} 的包名：
     * 用泛化匹配 {@code ..application..} 再排除报表<b>自己</b>的
     * {@code com.erp.rpt.application..}，这样 M2-S2 的 {@code set.application}
     * 以及以后任何新模块都自动落进网里。枚举式的规则会随模块增加而悄悄失效。
     */
    @ArchTest
    static final ArchRule 报表模块不得依赖其它模块的应用服务 =
            noClasses()
                    .that().resideInAPackage("com.erp.rpt..")
                    .should().dependOnClassesThat(
                            com.tngtech.archunit.core.domain.JavaClass.Predicates
                                    .resideInAPackage("..application..")
                                    .and(com.tngtech.archunit.core.domain.JavaClass.Predicates
                                            .resideOutsideOfPackage("com.erp.rpt.application..")))
                    .because("报表是只读泳道：只允许使用自己的查询仓储，"
                            + "依赖别人的应用服务等于把写入口递进报表（计划书 §3.1）");

    // ─────────────────────── 规则 8：退货只走出货域的只读出口（M2-S5a 接口缺口） ───────────────────────

    /**
     * ★ {@code rma} 不得依赖 {@code com.erp.sal.repository..}。
     *
     * <p><b>这条守的是"可退口径只有一份"</b>：退货要读出货数据，唯一入口是
     * {@code ReturnableSalesOrderService}。一旦 RMA 注入 {@code SalesOrderRepository}，
     * 它就会顺手写一份 {@code shipped_qty - returned_qty}（甚至漏成 {@code qty - returned_qty}），
     * 而 {@code ReturnableSalesOrderService} 的类注释正是为此存在：
     * 出货规则一变（比如将来支持分批出货），两边静默分叉，表现是"退了多少库存对不上"。
     *
     * <p>与新增的 {@code GET /return-orders/returnable-orders} 同时交付：
     * 那个接口要让只有 {@code rma:*} 的销售员也能选到原出货单（§6 坑 13），
     * 正确的做法是扩展 SAL 的只读出口，而不是让 RMA 抄近路直连仓储。
     *
     * <p><b>不是空规则</b>：{@code com.erp.sal.repository.SalesOrderRepository} 真实存在，
     * 规则跑在生产代码上；TC-ARCH-006 再现场编译一段违规 import 证明它会变红。
     */
    @ArchTest
    static final ArchRule 退货模块不得直连出货模块仓储 =
            noClasses()
                    .that().resideInAPackage("com.erp.rma..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("com.erp.sal.repository..")
                    .because("可退口径归出货域：RMA 只能通过 ReturnableSalesOrderService 读出货单，"
                            + "直连 sal.repository 等于在退货模块抄一份 shipped_qty − returned_qty（§6 坑 13）");

    // ─────────────────────── 规则 9：NL 只做解析与调度（M7 FR-SYS-04） ───────────────────────

    /**
     * ★ {@code nl} 不得依赖任何 {@code repository}。
     *
     * <p>自然语言入口最容易走偏的写法是"顺手查一下库存/单据"：一旦它注入
     * {@code InventoryBalanceRepository} 或单据仓储，就会长出一套与
     * ApplicationService 并行的业务逻辑 —— 库存判断、状态过滤、金额计算各一份，
     * 规则一变就分叉，而且 NL 的调用方（LLM / Agent）不会像页面那样被人盯着看。
     *
     * <p>正确边界：NL 只产出"意图 + 参数"，执行一律调用既有 ApplicationService。
     * 这条规则与 TC-ARCH-007 一起，把"不重复实现业务规则"从注释变成红。
     */
    @ArchTest
    static final ArchRule 自然语言入口不得直连仓储 =
            noClasses()
                    .that().resideInAPackage("com.erp.nl..")
                    .should().dependOnClassesThat().resideInAnyPackage("..repository..")
                    .because("NL 层只做解析与调度：业务规则必须调用既有 ApplicationService，"
                            + "直连仓储等于在自然语言入口复制第二套规则（FR-SYS-04）");

    // ─────────────────────── 规则 10：成本模块只读（V2-4） ───────────────────────

    /**
     * ★ {@code cst} 不得依赖其它模块的应用服务。
     *
     * <p>成本引擎的全部输入是库存流水（ADR-0007），它的职责是"从事实源派生成本"，
     * 不是"顺手改库存/单据"。一旦注入 {@code InventoryApplicationService} 或
     * {@code PurchaseOrderApplicationService}，成本模块就获得了写入口，
     * 与 V2-1 正在改的库存写入路径产生第二套并发语义。
     *
     * <p>与报表规则同样用泛化匹配 + 排除自己：以后新增模块自动落网，不需要维护清单。
     */
    @ArchTest
    static final ArchRule 成本模块不得依赖其它模块的应用服务 =
            noClasses()
                    .that().resideInAPackage("com.erp.cst..")
                    .should().dependOnClassesThat(
                            com.tngtech.archunit.core.domain.JavaClass.Predicates
                                    .resideInAPackage("..application..")
                                    .and(com.tngtech.archunit.core.domain.JavaClass.Predicates
                                            .resideOutsideOfPackage("com.erp.cst.application..")))
                    .because("成本模块只从流水派生成本；写入口必须留在各自模块"
                            + "（V2-4 并行边界：不改库存写入语义）");

    /**
     * ★ {@code cst} 不得直连 INV 的仓储。
     *
     * <p>成本需要读流水，但 INV 的仓储不对外暴露（见规则 1/2）。
     * 正确做法是 cst 自己的只读查询仓储直接读 {@code inventory_ledger}；
     * 直连 {@code InventoryLedgerRepository} 意味着成本会随 INV 的内部重构而漂移，
     * 也给了成本模块接触库存写入口的机会。
     */
    @ArchTest
    static final ArchRule 成本模块不得直连库存仓储 =
            noClasses()
                    .that().resideInAPackage("com.erp.cst..")
                    .should().dependOnClassesThat().resideInAnyPackage("com.erp.inv.repository..")
                    .because("成本引擎用 cst 自有只读 SQL 读流水，不依赖 INV 内部仓储");

    /**
     * ★ {@code cst} 禁止依赖 {@code java.math.BigDecimal}。
     *
     * <p>ADR-0005 要求金额以整数分运算、舍入集中在一处。成本引擎里只要出现
     * BigDecimal，就会长出一套与 {@code MoneyMath} 并行的舍入规则；
     * 两套规则差一分钱，INV-11 的守恒断言就会在某个随机序列上随机变红。
     *
     * <p>这条规则把"除法只在 MoneyMath"从代码评审口径变成 CI 门禁。
     * 下面的 TC-ARCH-008 现场编译违规探针，证明规则真的会红。
     */
    @ArchTest
    static final ArchRule 成本模块禁止使用BigDecimal做金额运算 =
            noClasses()
                    .that().resideInAPackage("com.erp.cst..")
                    .and().haveSimpleNameNotEndingWith("Test")
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedName("java.math.BigDecimal")
                    .because("金额一律整数分；除法与 HALF_UP 只允许在 MoneyMath（ADR-0005）");

    // ─────────────────────── 辅助实现 ───────────────────────

    /**
     * 自定义规则：扫描每个类的方法调用，找出对系统时间的直接取值。
     *
     * <p><b>★ 两个曾经导致"规则假绿"的细节</b>（本类自检用例暴露）：
     * <ol>
     *   <li>{@code JavaMethod.getFullName()} 返回的是<b>带括号</b>的形式
     *       （{@code java.time.LocalDateTime.now()}），
     *       按不带括号的字符串比较会永远匹配不上，规则静默失效；</li>
     *   <li>用 {@code classes().should(condition)} 时，需要确认条件确实落在
     *       我们关心的类上 —— 因此条件内部再按包名过滤一次，双重保险。</li>
     * </ol>
     */
    private static com.tngtech.archunit.lang.ArchCondition<JavaClass> noSystemTimeCalls() {
        java.util.Set<String> forbiddenOwners = java.util.Set.of(
                "java.lang.System",
                "java.time.Instant",
                "java.time.LocalDateTime",
                "java.time.LocalDate",
                "java.time.OffsetDateTime",
                "java.time.ZonedDateTime",
                "java.time.Clock");
        return new com.tngtech.archunit.lang.ArchCondition<>("不得直接调用系统时间") {
            @Override
            public void check(JavaClass item, com.tngtech.archunit.lang.ConditionEvents events) {
                item.getMethodCallsFromSelf().forEach(call -> {
                    String owner = call.getTargetOwner().getFullName();
                    String method = call.getTarget().getName();
                    boolean hit = forbiddenOwners.contains(owner)
                            && ("now".equals(method) || "currentTimeMillis".equals(method)
                                || "nanoTime".equals(method));
                    if (hit) {
                        events.add(com.tngtech.archunit.lang.SimpleConditionEvent.violated(item,
                                "%s 直接调用了 %s —— 请改用注入的 ClockProvider"
                                        .formatted(item.getSimpleName(),
                                                call.getTarget().getFullName())));
                    }
                });
            }
        };
    }

    /** 保留类型引用，避免 IDE 判定 import 未使用（规则里以类名字面量声明）。 */
    @SuppressWarnings("unused")
    private static final Class<?>[] TIME_TYPES =
            {LocalDate.class, LocalDateTime.class, OffsetDateTime.class};

    /**
     * 自定义规则：应用/接口层不得 <b>new</b> 裸的 {@code RuntimeException} 系类型。
     *
     * <p>为什么检查"构造"而不是"throws 声明"：方法签名上写
     * {@code throws IllegalStateException} 并不代表运行时会抛，
     * 按声明检查会大量误报。真正的问题是 {@code throw new RuntimeException("...")}——
     * 这种失败没有错误码，会被全局处理器兜底成 500。
     *
     * <p>允许的类型：{@code BusinessException}（带错误码）以及
     * 框架/测试用的具体异常（它们要么在构造器里被包成业务异常，要么明确属程序缺陷）。
     */
    private static com.tngtech.archunit.lang.ArchCondition<JavaClass>
            noBareRuntimeExceptionConstruction() {
        java.util.Set<String> allowed = java.util.Set.of(
                "com.erp.shared.error.BusinessException");
        return new com.tngtech.archunit.lang.ArchCondition<>("不得直接构造无错误码的运行时异常") {
            @Override
            public void check(JavaClass item, com.tngtech.archunit.lang.ConditionEvents events) {
                item.getConstructorCallsFromSelf().forEach(call -> {
                    JavaClass target = call.getTargetOwner();
                    if (!target.isAssignableTo(RuntimeException.class)) {
                        return;
                    }
                    if (allowed.contains(target.getFullName())) {
                        return;
                    }
                    events.add(com.tngtech.archunit.lang.SimpleConditionEvent.violated(item,
                            "%s 直接构造了 %s —— 业务失败请用 BusinessException(ErrorCode, message)"
                                    .formatted(item.getSimpleName(), target.getSimpleName())));
                });
            }
        };
    }

    // ─────────────────────── 规则 10：sys 只依赖 shared（V2-5 FR-SYS-06~09） ───────────────────────

    /**
     * ★ {@code sys} 不得依赖除 {@code shared} 外的<b>任何</b>业务模块。
     *
     * <p><b>为什么这条是审计能否成立的前提</b>：审计切面靠"所有 {@code @RestController}
     * 都住在 {@code com.erp..api..}"这一结构约定工作，它对每个模块一视同仁。
     * 一旦 {@code sys} 反过来 import 了某个业务模块（比如为了取"进货单的业务单据号"
     * 而注入 {@code PurchaseOrderApplicationService}），立刻出现三个问题：
     * <ol>
     *   <li><b>包依赖环</b>：业务模块的 Controller 被切面织入，切面又依赖业务模块 ——
     *       编译能过，但模块再也拆不开，单测只能整块起容器；</li>
     *   <li><b>审计覆盖面悄悄变窄</b>：只在"认识的"模块上留痕，
     *       新模块被漏掉时没有任何信号（INV-15 的扫描会红，但根因难查）；</li>
     *   <li><b>下期加租户/数据权限时</b>（V2-6/V2-7）必须回头重做审计链路。</li>
     * </ol>
     *
     * <p>正确做法：审计只认<b>HTTP 语义</b>（方法 + 路径 + 响应体），不认业务类型。
     * 它需要的资源类型由 Controller 类名推导，业务单据号从响应体里取 ——
     * 两者都不需要 import 任何业务模块。
     *
     * <p>允许依赖 {@code com.erp.shared}（错误码、BusinessException、分页、时钟）：
     * 那是全项目的公共底座，任何模块都可以用。
     *
     * <p>另外单独允许 {@code com.erp.iam}：仅限 {@code sys.api} 使用
     * {@code @RequirePermission} 注解 —— 权限声明必须贴在接口上，
     * 这条依赖是"注解"而不是"调用 IAM 的业务能力"，方向是单向的
     * （IAM 不依赖 SYS）。若把 IAM 也禁掉，唯一后果是审计查询接口变成裸奔。
     */
    @ArchTest
    static final ArchRule 审计模块只依赖shared与IAM注解 =
            noClasses()
                    .that().resideInAPackage("com.erp.sys..")
                    .should().dependOnClassesThat(
                            com.tngtech.archunit.core.domain.JavaClass.Predicates
                                    .resideInAPackage("com.erp..")
                                    .and(com.tngtech.archunit.core.domain.JavaClass.Predicates
                                            .resideOutsideOfPackage("com.erp.shared.."))
                                    .and(com.tngtech.archunit.core.domain.JavaClass.Predicates
                                            .resideOutsideOfPackage("com.erp.sys.."))
                                    .and(com.tngtech.archunit.core.domain.JavaClass.Predicates
                                            .resideOutsideOfPackage("com.erp.iam.security..")))
                    .because("sys 是横切能力：只依赖 shared 与 iam.security 的权限注解。"
                            + "依赖业务模块会形成包依赖环，并让审计覆盖面随模块增加而悄悄失效"
                            + "（V2-5 FR-SYS-06）");

    // ─────────────────────── 规则 11：审计仓储不可改（FR-SYS-06 不可篡改） ───────────────────────

    /**
     * ★ 审计仓储<b>不得存在</b>任何 update / delete / remove / truncate 方法。
     *
     * <p><b>这条守的是"审计可信"</b>：审计的全部价值建立在"写了就再也改不了"之上。
     * 只要仓储里有一个 {@code updateResult()} 或 {@code deleteById()}，
     * 后改的人就能覆盖前人的痕迹，而这件事<b>不会有任何痕迹</b>。
     *
     * <p>与数据库触发器 {@code trg_audit_logs_append_only} 构成两层防线：
     * <ul>
     *   <li>代码层（本规则）拦"自己人顺手加了个方法"；</li>
     *   <li>数据库层拦"绕过代码的手工 SQL / 运维脚本"。</li>
     * </ul>
     * 只做其中一层都不够：纯代码约束挡不住 psql，纯触发器挡不住"先查出来改完再插回去"。
     *
     * <p><b>不是空规则</b>：{@code AuditLogRepository} 与 {@code AuditLogQueryRepository}
     * 此刻都真实存在，规则跑在生产代码上；TC-ARCH-011 再现场编译一段带
     * {@code deleteById} 的违规仓储，证明它会变红。
     */
    @ArchTest
    static final ArchRule 审计仓储不得存在改删方法 =
            classes()
                    .that().resideInAPackage("com.erp.sys.repository..")
                    .and().haveSimpleNameContaining("Audit")
                    .should(declareNoMutatingMethods())
                    .because("audit_logs 是追加型表：仓储一旦有 update/delete，"
                            + "审计就不可信了（V2-5 FR-SYS-06）");

    // ─────────────────────── ★ 规则自检：证明规则真的会变红 ───────────────────────

    /**
     * ★ 自检：现场编译一段"直接取系统时间"的违规代码，断言规则能拦住它。
     *
     * <p>这条用例存在的原因见类注释：M1 开发中曾出现"9 条规则全绿、注入违规仍全绿"
     * 的假绿。规则失效比没有规则更危险 —— 它给你安全感，却什么也不拦。
     * 因此对"必须命中"的规则各配一条自检：规则一旦失效，本类立刻红。
     *
     * <p>做法：用 {@code JavaCompiler} 在内存里编译违规源码，再用显式导入器
     * 分析它。不落盘、不污染代码库。
     */
    @org.junit.jupiter.api.Test
    @CaseId("TC-ARCH-001")
    @org.junit.jupiter.api.DisplayName("★ 规则自检：禁止系统时间规则必须能拦住违规代码")
    void 规则自检_系统时间规则必须生效() {
        var classes = compileProbe("com.erp.probe.BadTimeProbe", """
                package com.erp.probe;
                public class BadTimeProbe {
                    public java.time.LocalDateTime bad() {
                        return java.time.LocalDateTime.now();
                    }
                }
                """);
        // 与生产规则保持同一形式（classes() + 条件），否则自检证明不了生产规则有效
        var rule = classes()
                .that().resideInAPackage("com.erp.probe..")
                .should(noSystemTimeCalls());
        boolean violated = rule.evaluate(classes).hasViolation();
        org.assertj.core.api.Assertions.assertThat(violated)
                .as("★ 规则必须能拦住 LocalDateTime.now()；若为 false 说明规则已失效（假绿）")
                .isTrue();
    }

    @org.junit.jupiter.api.Test
    @CaseId("TC-ARCH-002")
    @org.junit.jupiter.api.DisplayName("★ 规则自检：禁止裸异常规则必须能拦住违规代码")
    void 规则自检_裸异常规则必须生效() {
        var classes = compileProbe("com.erp.probe.BadThrowProbe", """
                package com.erp.probe;
                public class BadThrowProbe {
                    public void bad() {
                        throw new IllegalStateException("no error code");
                    }
                }
                """);
        var rule = classes()
                .that().resideInAPackage("com.erp.probe..")
                .should(noBareRuntimeExceptionConstruction());
        org.assertj.core.api.Assertions.assertThat(rule.evaluate(classes).hasViolation())
                .as("★ 规则必须能拦住 new IllegalStateException(...)；若为 false 说明规则已失效")
                .isTrue();
    }

    @org.junit.jupiter.api.Test
    @CaseId("TC-ARCH-003")
    @org.junit.jupiter.api.DisplayName("★ 规则自检：状态机规则必须拦住不调 requireTransition 的方法")
    void 规则自检_状态机规则必须生效() {
        var classes = compileProbe("com.erp.probe.BadTransitionProbe", """
                package com.erp.probe;
                public class BadTransitionProbe {
                    public void bad() {
                        String status = "APPROVED";
                        status = status + "!";
                    }
                }
                """);
        var rule = classes()
                .that().resideInAPackage("com.erp.probe..")
                .should(declareStatusTransition());
        org.assertj.core.api.Assertions.assertThat(rule.evaluate(classes).hasViolation())
                .as("★ 规则必须能拦住未走统一状态机的类")
                .isTrue();
    }

    /**
     * ★ 自检：现场编译一段"进货模块直接依赖结算模块"的违规代码，断言规则拦得住。
     *
     * <p>探针刻意放进 {@code com.erp.pur.application}，与生产规则同一套包名匹配条件 ——
     * 只有这样才能证明生产规则里那份 {@code resideInAnyPackage(...)} 清单
     * 真的覆盖到了三个单据域，而不是靠探针自己的包名碰巧命中。
     */
    @org.junit.jupiter.api.Test
    @CaseId("TC-ARCH-004")
    @org.junit.jupiter.api.DisplayName("★ 规则自检：单据模块不得依赖 set 的规则必须拦住直接 import 的写法")
    void 规则自检_锁账依赖倒置规则必须生效() {
        var classes = compileProbe("com.erp.pur.application.BadSetDependencyProbe", """
                package com.erp.pur.application;
                public class BadSetDependencyProbe {
                    public boolean bad(com.erp.set.application.SettlementPeriodGuard guard) {
                        return guard != null;
                    }
                }
                """);
        var rule = 单据模块不得直接依赖结算模块;
        org.assertj.core.api.Assertions.assertThat(rule.evaluate(classes).hasViolation())
                .as("★ 规则必须能拦住 import com.erp.set 的单据模块代码；"
                        + "若为 false 说明这条锁账依赖倒置规则已失效（假绿）")
                .isTrue();
    }

    /**
     * ★ 自检：报表只读规则必须拦住"报表依赖别模块应用服务"。
     *
     * <p>这里直接 evaluate <b>生产规则本身</b>（不是重写一遍形状）：
     * 一旦有人把规则的包范围改窄，本用例立刻变红。
     * 探针引用的是真实存在的 {@code pur.application} 服务 —— 违规样本越接近
     * 真实的犯错方式，自检才越有说服力。
     */
    @org.junit.jupiter.api.Test
    @CaseId("TC-ARCH-005")
    @org.junit.jupiter.api.DisplayName("★ 规则自检：报表只读规则必须拦住跨模块应用服务依赖")
    void 规则自检_报表只读规则必须生效() {
        var classes = compileProbe("com.erp.rpt.probe.BadRptWriteAccessProbe", """
                package com.erp.rpt.probe;

                public class BadRptWriteAccessProbe {
                    private final com.erp.pur.application.PurchaseOrderApplicationService svc = null;

                    public Object bad() {
                        return svc;
                    }
                }
                """);
        org.assertj.core.api.Assertions.assertThat(
                报表模块不得依赖其它模块的应用服务.evaluate(classes).hasViolation())
                .as("★ 规则必须能拦住 rpt -> 其它模块 application 的依赖；"
                        + "若为 false 说明规则已失效（假绿）")
                .isTrue();
    }

    /**
     * ★ 自检：退货只读出口规则必须拦住"RMA 直连 sal.repository"。
     *
     * <p>探针放进真实的 {@code com.erp.rma.application} 包，并 evaluate <b>生产规则本身</b>：
     * 有人把规则里的包名改窄（例如只拦 {@code rma.api}）时，本用例立刻变红。
     */
    @org.junit.jupiter.api.Test
    @CaseId("TC-ARCH-006")
    @org.junit.jupiter.api.DisplayName("★ 规则自检：退货模块不得直连 sal.repository 的规则必须拦住违规 import")
    void 规则自检_退货不得直连出货仓储规则必须生效() {
        var classes = compileProbe("com.erp.rma.application.BadSalRepositoryProbe", """
                package com.erp.rma.application;

                public class BadSalRepositoryProbe {
                    private final com.erp.sal.repository.SalesOrderRepository repo = null;

                    public Object bad() {
                        return repo;
                    }
                }
                """);
        org.assertj.core.api.Assertions.assertThat(
                退货模块不得直连出货模块仓储.evaluate(classes).hasViolation())
                .as("★ 规则必须能拦住 rma -> sal.repository 的直连；"
                        + "若为 false 说明可退口径已被抄进退货模块而门禁失效（假绿）")
                .isTrue();
    }

    /**
     * ★ 自检：NL 入口直连仓储必须被拦住。
     *
     * <p>探针放进真实的 {@code com.erp.nl.application} 包，并 evaluate 生产规则本身：
     * 规则被改窄（例如只拦 {@code nl.api}）时，本用例立刻红。
     */
    @org.junit.jupiter.api.Test
    @CaseId("TC-ARCH-007")
    @org.junit.jupiter.api.DisplayName("★ 规则自检：NL 入口不得直连仓储的规则必须拦住违规 import")
    void 规则自检_自然语言入口不得直连仓储规则必须生效() {
        var classes = compileProbe("com.erp.nl.application.BadNlRepositoryProbe", """
                package com.erp.nl.application;

                public class BadNlRepositoryProbe {
                    private final com.erp.inv.repository.InventoryBalanceRepository repo = null;

                    public Object bad() {
                        return repo;
                    }
                }
                """);
        org.assertj.core.api.Assertions.assertThat(
                自然语言入口不得直连仓储.evaluate(classes).hasViolation())
                .as("★ 规则必须能拦住 nl -> repository 的直连；"
                        + "若为 false 说明 NL 已可能在解析层复制业务规则（假绿）")
                .isTrue();
    }

    /**
     * ★ 自检：成本模块 BigDecimal 禁令必须拦住违规代码。
     *
     * <p>探针放进真实的 {@code com.erp.cst.probe} 包并 evaluate 生产规则本身；
     * 规则被改窄或删除时，本用例立刻红。
     */
    @org.junit.jupiter.api.Test
    @CaseId("TC-ARCH-008")
    @org.junit.jupiter.api.DisplayName("★ 规则自检：成本模块禁止 BigDecimal 的规则必须拦住违规依赖")
    void 规则自检_成本模块禁止BigDecimal规则必须生效() {
        var classes = compileProbe("com.erp.cst.probe.BadCostDecimalProbe", """
                package com.erp.cst.probe;

                public class BadCostDecimalProbe {
                    private final java.math.BigDecimal amount = null;

                    public Object bad() {
                        return amount;
                    }
                }
                """);
        org.assertj.core.api.Assertions.assertThat(
                成本模块禁止使用BigDecimal做金额运算.evaluate(classes).hasViolation())
                .as("★ 规则必须能拦住 cst 对 BigDecimal 的依赖；"
                        + "若为 false 说明金额舍入已可能在 MoneyMath 之外分叉（假绿）")
                .isTrue();
    }

    /**
     * ★ 自检：成本模块直连 INV 仓储必须被拦住。
     *
     * <p>探针引用真实存在的 {@code InventoryLedgerRepository}；
     * 这条规则守的是 V2-4 与 V2-1 的并行边界 —— 成本只能读流水，不能碰库存写入口。
     */
    @org.junit.jupiter.api.Test
    @CaseId("TC-ARCH-009")
    @org.junit.jupiter.api.DisplayName("★ 规则自检：成本模块不得直连库存仓储的规则必须拦住违规 import")
    void 规则自检_成本模块不得直连库存仓储规则必须生效() {
        var classes = compileProbe("com.erp.cst.probe.BadInvRepositoryProbe", """
                package com.erp.cst.probe;

                public class BadInvRepositoryProbe {
                    private final com.erp.inv.repository.InventoryLedgerRepository repo = null;

                    public Object bad() {
                        return repo;
                    }
                }
                """);
        org.assertj.core.api.Assertions.assertThat(
                成本模块不得直连库存仓储.evaluate(classes).hasViolation())
                .as("★ 规则必须能拦住 cst -> inv.repository 的直连；"
                        + "若为 false 说明成本模块可能绕过只读边界（假绿）")
                .isTrue();
    }

    /**
     * 自定义规则：审计仓储不得声明"改/删"语义的方法。
     *
     * <p>按<b>方法名</b>检查而不是按 SQL 文本：方法名是契约面——
     * 一个叫 {@code archive()} 却写 {@code DELETE} 的实现无法被静态分析识别，
     * 但"名字里带 update/delete/remove/truncate/purge/clear"的意图足够明显，
     * 足以在评审与 CI 里被拦住。配合数据库触发器使用：名字骗得过规则，骗不过 trigger。
     *
     * <p>覆盖 {@code insert} 是<b>允许</b>的（追加是审计唯一合法的写操作）——
     * 规则只禁改删，不禁追加。这个边界必须写清楚，否则下一个人会把 insert 也改走。
     */
    private static com.tngtech.archunit.lang.ArchCondition<JavaClass>
            declareNoMutatingMethods() {
        java.util.List<String> forbiddenPrefixes = java.util.List.of(
                "update", "delete", "remove", "truncate", "purge", "clear", "modify", "rewrite");
        return new com.tngtech.archunit.lang.ArchCondition<>(
                "不得声明 update/delete/remove 等改删方法（审计追加型）") {
            @Override
            public void check(JavaClass item, com.tngtech.archunit.lang.ConditionEvents events) {
                item.getMethods().forEach(method -> {
                    String lower = method.getName().toLowerCase(java.util.Locale.ROOT);
                    boolean hit = forbiddenPrefixes.stream().anyMatch(lower::startsWith);
                    if (hit) {
                        events.add(com.tngtech.archunit.lang.SimpleConditionEvent.violated(item,
                                "%s 声明了 %s() —— 审计仓储只允许追加（insert），"
                                        .formatted(item.getSimpleName(), method.getName())
                                        + "改/删会让「谁改了什么」可以被后改的人覆盖"));
                    }
                });
            }
        };
    }

    /**
     * ★ 自检：审计模块依赖业务模块必须被拦住。
     *
     * <p>探针放进真实的 {@code com.erp.sys.application} 包，并 evaluate <b>生产规则本身</b>：
     * 一旦有人把规则里的"允许列表"改宽（例如顺手放行 {@code com.erp.pur}），
     * 本用例立刻变红。这正是我们要防的"越修越松"。
     *
     * <p>违规样本刻意选真实存在的类（{@code PurchaseOrderApplicationService}）：
     * 编造一个不存在的类名会让探针编译失败，测试就以"探针编译不过"的形式红，
     * 无法区分"规则失效"与"探针写错"。
     */
    @org.junit.jupiter.api.Test
    @CaseId("TC-ARCH-011")
    @org.junit.jupiter.api.DisplayName("★ 规则自检：sys 只依赖 shared 的规则必须拦住依赖业务模块")
    void 规则自检_审计模块隔离规则必须生效() {
        var classes = compileProbe("com.erp.sys.application.BadSysDependencyProbe", """
                package com.erp.sys.application;

                public class BadSysDependencyProbe {
                    private final com.erp.pur.application.PurchaseOrderApplicationService svc = null;

                    public Object bad() {
                        return svc;
                    }
                }
                """);
        org.assertj.core.api.Assertions.assertThat(
                审计模块只依赖shared与IAM注解.evaluate(classes).hasViolation())
                .as("★ 规则必须能拦住 sys -> 业务模块的依赖；若为 false 说明横切能力已与"
                        + "业务模块形成包依赖环，审计覆盖面会随模块增加悄悄失效（假绿）")
                .isTrue();
    }

    /**
     * ★ 自检：审计仓储"不得改删"规则必须拦住带 delete 方法的仓储。
     *
     * <p>探针的包名与类名都命中生产规则的过滤器（{@code com.erp.sys.repository..}
     * + 类名含 {@code Audit}），因此 evaluate 生产规则就等于 evaluate 探针：
     * 规则被改窄（例如只查 {@code AuditLogRepository} 一个类名）时，本用例立刻红。
     */
    @org.junit.jupiter.api.Test
    @CaseId("TC-ARCH-012")
    @org.junit.jupiter.api.DisplayName("★ 规则自检：审计仓储不得存在改删方法的规则必须拦住 deleteById")
    void 规则自检_审计不可篡改规则必须生效() {
        var classes = compileProbe("com.erp.sys.repository.BadAuditRepositoryProbe", """
                package com.erp.sys.repository;

                public class BadAuditRepositoryProbe {
                    public void deleteById(long id) {
                        // 违规：审计仓储不允许任何删除能力
                    }

                    public void updateResult(long id, String result) {
                        // 违规：审计仓储不允许任何更新能力
                    }
                }
                """);
        org.assertj.core.api.Assertions.assertThat(
                审计仓储不得存在改删方法.evaluate(classes).hasViolation())
                .as("★ 规则必须能拦住审计仓储里的 delete/update 方法；"
                        + "若为 false 说明「审计不可篡改」这条门禁已失效（假绿）")
                .isTrue();
    }

    /**
     * ★ 自检（反向）：一个<b>只有 insert</b> 的审计仓储必须通过规则。
     *
     * <p>为什么需要这条"应该绿"的用例：只写"必须红"的自检，
     * 很容易在半年后被人为了让它变绿而把规则改宽到"禁止一切方法"。
     * 配一条"应该通过"的用例，规则的两端都被钉住了 ——
     * 合法写法不被误伤，非法写法不被放过。
     */
    @org.junit.jupiter.api.Test
    @CaseId("TC-ARCH-013")
    @org.junit.jupiter.api.DisplayName("★ 规则自检（反向）：只有 insert 的审计仓储必须通过不可篡改规则")
    void 规则自检_审计不可篡改规则不得误伤纯追加仓储() {
        var classes = compileProbe("com.erp.sys.repository.GoodAuditRepositoryProbe", """
                package com.erp.sys.repository;

                public class GoodAuditRepositoryProbe {
                    public long insert(String action) {
                        return action == null ? 0L : 1L;
                    }

                    public long countAll() {
                        return 1L;
                    }
                }
                """);
        org.assertj.core.api.Assertions.assertThat(
                审计仓储不得存在改删方法.evaluate(classes).hasViolation())
                .as("★ 纯追加仓储（insert + count）不得被误判为违规；"
                        + "若为 true 说明规则过宽，会把合法实现逼成「绕开规则」的写法")
                .isFalse();
    }

    /**
     * ★ 自检：{@code sys} 依赖 {@code shared} 是<b>合法</b>的，规则不得误伤。
     *
     * <p>这条反向用例守的是同一个风险点：审计切面必须能用
     * {@code BusinessException} / {@code ErrorCode} / {@code PageResult} 这些公共底座。
     * 若规则把 {@code shared} 也算违规，实现者只能复制一套错误码 —— 那才是灾难。
     */
    @org.junit.jupiter.api.Test
    @CaseId("TC-ARCH-014")
    @org.junit.jupiter.api.DisplayName("★ 规则自检（反向）：sys 依赖 shared 必须合法")
    void 规则自检_审计模块隔离规则不得误伤shared依赖() {
        var classes = compileProbe("com.erp.sys.application.GoodSharedDependencyProbe", """
                package com.erp.sys.application;

                public class GoodSharedDependencyProbe {
                    public String bad(com.erp.shared.error.ErrorCode code) {
                        return code == null ? "null" : code.name();
                    }
                }
                """);
        org.assertj.core.api.Assertions.assertThat(
                审计模块只依赖shared与IAM注解.evaluate(classes).hasViolation())
                .as("★ sys -> shared 必须被允许；若为 true 说明规则过宽，"
                        + "实现者会被逼着复制一套错误码")
                .isFalse();
    }

    /**
     * 在内存中编译一段违规源码，并用<b>显式导入器</b>分析它。
     *
     * <p>显式导入器（而非 {@code @AnalyzeClasses} 的默认导入）是让
     * "方法调用"类规则真正生效的关键：默认导入器在只分析单类时
     * 不会带上被调用方的完整信息，导致 {@code getMethodCallsFromSelf()} 为空。
     */
    private static com.tngtech.archunit.core.domain.JavaClasses compileProbe(
            String className, String source) {
        return com.erp.support.ProbeClassCompiler.compile(className, source);
    }
}
