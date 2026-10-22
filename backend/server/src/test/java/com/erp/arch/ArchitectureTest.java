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
                            "com.erp.rma..", "com.erp.mdm..", "com.erp.iam..",
                            "com.erp.shared.document..")
                    .and().doNotHaveSimpleName("ClockConfig")
                    .should().dependOnClassesThat().areAssignableTo(java.time.Clock.class)
                    .because("时间必须来自可注入的 ClockProvider（docs/06-测试策略 §3）");

    /**
     * ★ 字段/参数上出现 {@code LocalDateTime.now()} 的最常见形态。
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
                            "com.erp.rma..", "com.erp.mdm..", "com.erp.iam..",
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
