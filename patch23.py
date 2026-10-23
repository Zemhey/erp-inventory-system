p='backend/server/src/main/java/com/erp/aps/application/ApprovalApplicationService.java'
s=open(p,encoding='utf-8').read()

# add ClockProvider field+ctor param
s=s.replace("import com.erp.shared.security.ActorContext;",
"""import com.erp.shared.security.ActorContext;
import com.erp.shared.time.ClockProvider;""",1)

s=s.replace("""    private final ActorContext actors;

    public ApprovalApplicationService(ApprovalFlowRepository flows,
                                      ApprovalInstanceRepository instances,
                                      ApprovalStepRepository steps,
                                      ActorContext actors) {
        this.flows = flows;
        this.instances = instances;
        this.steps = steps;
        this.actors = actors;
    }""",
"""    private final ActorContext actors;
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
    }""",1)

old_now="""    /**
     * 审批的结论时刻统一取"数据库会话时间"。
     *
     * <p>这里没有用 {@code ClockProvider}：审批的 {@code decided_at} 是<b>审计时间</b>
     * （"谁在什么时候签的字"），必须与真实墙钟一致，不能随业务时钟穿越
     * —— 测试里把业务时钟拨到 2025 年，不该让审计记录显示 2025 年签字。
     * 与业务日期（{@code biz_date}、账期归属）是两个不同性质的量，
     * 后者才必须来自可注入时钟（计划书 §3.5）。
     */
    private static java.time.OffsetDateTime clockNow() {
        return java.time.OffsetDateTime.now();
    }"""
new_now="""    /**
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
    }"""
assert old_now in s
s=s.replace(old_now,new_now,1)
open(p,'w',encoding='utf-8').write(s)
print("aps uses ClockProvider now")
