# --- A) UserAdminService: ClockProvider instead of Instant.now/LocalDateTime.now
p='backend/server/src/main/java/com/erp/iam/application/UserAdminService.java'
s=open(p,encoding='utf-8').read()
s=s.replace("""import java.time.Instant;
import java.time.LocalDateTime;""","""import com.erp.shared.time.ClockProvider;
import java.time.Instant;
import java.time.LocalDateTime;""")
s=s.replace("""    private final PasswordEncoder encoder;
    private final AuthenticationService authentication;

    public UserAdminService(AppUserRepository users, RoleRepository roles,
                            PasswordEncoder encoder, AuthenticationService authentication) {
        this.users = users;
        this.roles = roles;
        this.encoder = encoder;
        this.authentication = authentication;
    }""",
"""    private final PasswordEncoder encoder;
    private final AuthenticationService authentication;
    /** 业务时钟（计划书 §3.5）：建账号时的密码过期时刻必须来自它。 */
    private final ClockProvider clock;

    public UserAdminService(AppUserRepository users, RoleRepository roles,
                            PasswordEncoder encoder, AuthenticationService authentication,
                            ClockProvider clock) {
        this.users = users;
        this.roles = roles;
        this.encoder = encoder;
        this.authentication = authentication;
        this.clock = clock;
    }""")
s=s.replace("""        users.updatePassword(userId, encoder.encode(rawPassword),
                authentication.passwordPolicy().expiryOf(Instant.now()), true);""",
"""        users.updatePassword(userId, encoder.encode(rawPassword),
                authentication.passwordPolicy().expiryOf(nowInstant()), true);""")
s=s.replace("""    public void lockUntil(long userId, LocalDateTime until) {
        requireUser(userId);
        users.recordLoginFailure(userId, Integer.MAX_VALUE, until, LocalDateTime.now());
    }""",
"""    public void lockUntil(long userId, LocalDateTime until) {
        requireUser(userId);
        users.recordLoginFailure(userId, Integer.MAX_VALUE, until, clock.now());
    }

    /** 注入时钟 → 绝对时刻（与 TIMESTAMPTZ 比较用）。 */
    private Instant nowInstant() {
        return clock.now().atZone(java.time.ZoneId.systemDefault()).toInstant();
    }""")
open(p,'w',encoding='utf-8').write(s)
print("A) UserAdminService clock fixed")

# --- B) ApprovalController: no repository dependency -> move flow ops into service
p='backend/server/src/main/java/com/erp/aps/api/ApprovalController.java'
s=open(p,encoding='utf-8').read()
s=s.replace("import com.erp.aps.repository.ApprovalFlowRepository;\n","")
s=s.replace("""    private final ApprovalApplicationService approvals;
    private final ApprovalFlowRepository flows;
    private final ActorContext actors;

    public ApprovalController(ApprovalApplicationService approvals,
                              ApprovalFlowRepository flows, ActorContext actors) {
        this.approvals = approvals;
        this.flows = flows;
        this.actors = actors;
    }""",
"""    private final ApprovalApplicationService approvals;
    private final ActorContext actors;

    public ApprovalController(ApprovalApplicationService approvals, ActorContext actors) {
        this.approvals = approvals;
        this.actors = actors;
    }""")
s=s.replace("for (ApprovalFlow f : flows.findAll()) {","for (ApprovalFlow f : approvals.listFlows()) {")
old_toggle=s[s.index("    @PostMapping(\"/flows/{id}/enabled\")"):s.index("    // ─────────────────────────── 参数解析 ───────────────────────────")]
new_toggle="""    @PostMapping("/flows/{id}/enabled")
    @RequirePermission("aps:flow:write")
    public ResponseEntity<Map<String, Object>> setFlowEnabled(
            @PathVariable long id, @RequestParam boolean enabled) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(approvals.setFlowEnabled(id, enabled))));
    }

"""
s=s.replace(old_toggle,new_toggle,1)
open(p,'w',encoding='utf-8').write(s)
print("B) ApprovalController decoupled from repository")
