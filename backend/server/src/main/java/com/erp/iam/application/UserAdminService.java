package com.erp.iam.application;

import com.erp.iam.domain.AppUser;
import com.erp.iam.repository.AppUserRepository;
import com.erp.iam.repository.RoleRepository;
import com.erp.shared.db.DbErrors;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.web.PageResult;
import com.erp.shared.time.ClockProvider;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户与角色管理服务（FR-IAM-05）。
 *
 * <p><b>★ 三条自我保护的规则</b>（缺一条就会出现"管理员把自己锁在门外"）：
 * <ol>
 *   <li>不能停用自己 —— 一个手滑让全部管理员失去登录，只能改库救；</li>
 *   <li>不能清空自己的全部角色 —— 同上；</li>
 *   <li>重置密码返回的明文<b>不落日志、不入库</b>，只由调用方转交给用户。</li>
 * </ol>
 * 这三条<b>不</b>做成可配置：它们是"管理动作的边界"，不是业务策略。
 *
 * <p><b>授权即时生效</b>：改角色/改角色权限后，下一个请求就生效 —— 因为
 * {@code JwtAuthenticationFilter} 与 {@code PermissionAspect} 每请求回库重读
 * （见 {@code RoleRepository} 类注释）。这里刻意不做"重新登录"的要求。
 */
@Service
public class UserAdminService {

    private final AppUserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder encoder;
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
    }

    // ─────────────────────────── 用户 ───────────────────────────

    @Transactional(readOnly = true)
    public PageResult<AppUser> pageUsers(String keyword, int page, int size) {
        return users.page(keyword, page, size);
    }

    @Transactional(readOnly = true)
    public AppUser requireUser(long id) {
        return users.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.IAM_USER_NOT_FOUND,
                        "账号不存在：id=%d".formatted(id)));
    }

    /**
     * 建账号。
     *
     * <p><b>新账号一律置强制改密</b>：管理员知道初始密码，用户必须在首次使用前改掉。
     * 这是"初始密码只用于交接"的标准做法，也让"管理员能一直冒充下属"这件事不成立
     * —— 他没法知道对方改了什么（虽然他能再重置一次，但重置会留痕）。
     */
    @Transactional
    public long createUser(String username, String rawPassword, String displayName,
                           boolean enabled, Collection<String> roleCodes) {
        return createUser(username, rawPassword, displayName, enabled, roleCodes, null);
    }

    /**
     * 建员工账号：账号 + 人事档案一次建完。
     *
     * <p>为什么档案要在同一个事务里写：页面上是"新建员工"一张表单，
     * 只建成账号、档案留空的话，列表里会出现一排"没有部门没有工号"的账号，
     * 还得再逐个人工补 —— 那才是真正费事的做法。
     *
     * @param profile 人事档案（工号/部门/岗位/联系方式/入职日期），可为 null
     */
    @Transactional
    public long createUser(String username, String rawPassword, String displayName,
                           boolean enabled, Collection<String> roleCodes, EmployeeProfile profile) {
        String name = username == null ? "" : username.trim();
        if (name.isEmpty()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR, "用户名不能为空");
        }
        // 复用同一套策略：建账号与改密码不能有两套复杂度规则
        authentication.passwordPolicy().check(rawPassword).ifPresent(reason -> {
            throw new BusinessException(ErrorCode.IAM_PASSWORD_WEAK, reason);
        });
        long userId;
        try {
            userId = users.insert(name, encoder.encode(rawPassword),
                    displayName == null || displayName.isBlank() ? name : displayName.trim(),
                    enabled);
        } catch (DataIntegrityViolationException ex) {
            var translated = DbErrors.translate(ex, "uq_app_user_username",
                    ErrorCode.IAM_USER_DUPLICATED, "用户名已存在：%s".formatted(name));
            if (translated != null) {
                throw translated;
            }
            throw ex;
        }
        users.updatePassword(userId, encoder.encode(rawPassword),
                authentication.passwordPolicy().expiryOf(nowInstant()), true);
        assignRoles(userId, roleCodes, -1);
        if (profile != null) {
            writeProfile(userId, profile);
        }
        return userId;
    }

    /**
     * 更新员工档案（不动账号与角色）。
     *
     * <p>与"改角色"分开是刻意的：改角色影响权限、需要留痕说明；
     * 改工号/换部门只是人事信息，两者混在一个接口里，审计日志就分不清
     * 这次保存到底是"调岗"还是"提权"。
     */
    @Transactional
    public void updateEmployeeProfile(long userId, EmployeeProfile profile) {
        requireUser(userId);
        writeProfile(userId, profile);
    }

    private void writeProfile(long userId, EmployeeProfile p) {
        String employeeNo = p.employeeNo() == null || p.employeeNo().isBlank()
                ? null : p.employeeNo().trim();
        try {
            users.updateProfile(userId, employeeNo, p.departmentId(), p.positionId(),
                    blankToNull(p.phone()), blankToNull(p.email()), p.hireDate(),
                    blankToNull(p.remark()));
        } catch (DataIntegrityViolationException ex) {
            var translated = DbErrors.translate(ex, "uq_app_user_employee_no",
                    ErrorCode.SYS_VALIDATION_ERROR, "工号已存在：%s".formatted(employeeNo));
            if (translated != null) {
                throw translated;
            }
            throw ex;
        }
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    /** 员工人事档案（与账号、角色分开的三块信息之一）。 */
    public record EmployeeProfile(String employeeNo, Long departmentId, Long positionId,
                                  String phone, String email, java.time.LocalDate hireDate,
                                  String remark) {
    }

    @Transactional(readOnly = true)
    public PageResult<AppUserRepository.EmployeeRow> pageEmployees(
            String keyword, Long departmentId, Boolean enabled, int page, int size) {
        return users.pageEmployees(keyword, departmentId, enabled, page, size);
    }

    /**
     * 改角色（覆盖式）。
     *
     * @param actorId 操作人 id；用来挡住"把自己角色清空"
     */
    @Transactional
    public void updateRoles(long userId, Collection<String> roleCodes, long actorId) {
        requireUser(userId);
        assignRoles(userId, roleCodes, actorId);
    }

    private void assignRoles(long userId, Collection<String> roleCodes, long actorId) {
        users.clearRoles(userId);
        if (roleCodes == null || roleCodes.isEmpty()) {
            if (userId == actorId) {
                throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                        "不能清空自己的全部角色 —— 否则会把自己锁在系统外");
            }
            return;
        }
        for (String code : roleCodes) {
            if (code == null || code.isBlank()) {
                continue;
            }
            roles.findByCode(code.trim()).orElseThrow(() ->
                    new BusinessException(ErrorCode.IAM_ROLE_NOT_FOUND,
                            "角色不存在：%s".formatted(code)));
            users.assignRole(userId, code.trim());
        }
    }

    /** 启用/停用账号（不能停用自己）。 */
    @Transactional
    public void setEnabled(long userId, boolean enabled, long actorId) {
        requireUser(userId);
        if (!enabled && userId == actorId) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "不能停用自己的账号");
        }
        if (!users.setEnabled(userId, enabled)) {
            throw new BusinessException(ErrorCode.IAM_USER_NOT_FOUND,
                    "账号不存在：id=%d".formatted(userId));
        }
    }

    /** 管理员重置密码（强制对方下次登录先改）。 */
    @Transactional
    public void resetPassword(long userId, String newPassword) {
        authentication.resetPassword(userId, newPassword);
    }

    // ─────────────────────────── 角色 ───────────────────────────

    /**
     * 角色清单（返回<b>应用层视图</ *不返回仓储类型</b>）。
     *
     * <p>为什么不让 Controller 直接用 {@code RoleRepository.RoleRow}：
     * ArchUnit 的「控制器不得直连仓储」规则禁止 Controller 依赖 {@code ..repository..}
     * 里的任何类型 —— 包括这种只有四个字段的行记录。宽松地放行"只是数据结构"
     * 的口子，下一步就会有人 import 整个 Repository。
     */
    @Transactional(readOnly = true)
    public List<RoleView> listRoles() {
        return roles.findAll().stream()
                .map(r -> new RoleView(r.id(), r.code(), r.name(), r.description()))
                .toList();
    }

    /** 角色视图（契约 {@code Role}）。 */
    public record RoleView(long id, String code, String name, String description) {
    }

    @Transactional(readOnly = true)
    public List<String> permissionsOfRole(long roleId) {
        requireRole(roleId);
        return roles.permissionsOf(roleId);
    }

    @Transactional(readOnly = true)
    public RoleView requireRole(long roleId) {
        var row = roles.findById(roleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.IAM_ROLE_NOT_FOUND,
                        "角色不存在：id=%d".formatted(roleId)));
        return new RoleView(row.id(), row.code(), row.name(), row.description());
    }

    @Transactional
    public long createRole(String code, String name, String description) {
        String c = code == null ? "" : code.trim();
        if (c.isEmpty()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR, "角色编码不能为空");
        }
        try {
            return roles.insert(c, name == null || name.isBlank() ? c : name.trim(),
                    description);
        } catch (DataIntegrityViolationException ex) {
            var translated = DbErrors.translate(ex, "uq_app_role_code",
                    ErrorCode.SYS_VALIDATION_ERROR, "角色编码已存在：%s".formatted(c));
            if (translated != null) {
                throw translated;
            }
            throw ex;
        }
    }

    /**
     * 覆盖式设置角色权限 —— <b>授权即时生效的触发点</b>。
     *
     * <p>不校验"权限码是否已定义"：本项目刻意允许新模块先种权限码再用，
     * 一份集中式权限白名单会在八个模块并行开发时天天冲突。
     * 拼错权限码的后果是"给了没用的权限"，而不是安全漏洞。
     */
    @Transactional
    public void replacePermissions(long roleId, Collection<String> permissions) {
        requireRole(roleId);
        Set<String> cleaned = new java.util.LinkedHashSet<>();
        if (permissions != null) {
            for (String p : permissions) {
                if (p != null && !p.isBlank()) {
                    cleaned.add(p.trim());
                }
            }
        }
        roles.replacePermissions(roleId, cleaned);
    }

    /** 供登录响应与 {@code /auth/me} 展示的权限并集。 */
    @Transactional(readOnly = true)
    public Set<String> permissionsOfUser(long userId) {
        return requireUser(userId).permissions();
    }

    /** 便于测试构造"锁定到期"场景。 */
    @Transactional
    public void lockUntil(long userId, LocalDateTime until) {
        requireUser(userId);
        users.recordLoginFailure(userId, Integer.MAX_VALUE, until, clock.now());
    }

    /** 注入时钟 → 绝对时刻（与 TIMESTAMPTZ 比较用）。 */
    private Instant nowInstant() {
        return clock.now().atZone(java.time.ZoneId.systemDefault()).toInstant();
    }
}
