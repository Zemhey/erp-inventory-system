package com.erp.sys;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestDataFactory;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 登录日志对「客户端可控字段长度」的容忍度（TC-SYS-092~093）。
 *
 * <p><b>起因是线上真实故障</b>：微信小程序登录 100% 返回 500。它的 User-Agent 长
 * 300~400 字符（含 {@code wechatdevtools/<版本>}、{@code MicroMessenger}、{@code sid} 等段），
 * 而 {@code sys_login_log.user_agent} 原本是 VARCHAR(256) —— INSERT 违反长度约束后，
 * 一条辅助日志把整个 {@code /auth/login} 打挂。浏览器 UA 短，所以这条路径在 Web 端从未暴露。
 *
 * <p>与 V19.1（tenant_id NOT NULL 同样把登录打成 500）同源，因此这里同时守住两层修复：
 * <ol>
 *   <li>V30 把 {@code user_agent} 放宽到 512、{@code ip} 放宽到 128；</li>
 *   <li>{@code LoginLogRecorder} 写入前按列宽截断，且写失败只告警、不再冒泡影响登录结果。</li>
 * </ol>
 * 只要有一条超长 UA 让登录返回 500，本用例就会红。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("clock")
@DisplayName("登录日志 · 客户端可控字段长度容忍")
class LoginLogClientFieldsIT extends PostgresTestBase {

    /** 真机小程序 UA 的放大版（约 1.9K 字符，远超列宽），确保截断逻辑不被绕过。 */
    private static final String HUGE_USER_AGENT =
            ("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Mobile/15E148 MicroMessenger/8.0.44(0x18002c2f) "
                    + "NetType/WIFI Language/zh_CN miniProgram wxdevtools/2.02.2608080, ").repeat(20);

    private static final int USER_AGENT_COLUMN_WIDTH = 512;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder encoder;

    private TestDataFactory data;
    private String username;

    @BeforeEach
    void setUp() {
        data = new TestDataFactory(jdbc);
        data.truncateAll();
        username = "ua_" + UUID.randomUUID().toString().substring(0, 8);
        data.user(username, "Correct1!", "VIEWER", encoder);
    }

    @Test
    @CaseId("TC-SYS-092")
    @DisplayName("★ 超长 User-Agent 的失败登录：返回 401 业务码，绝不能是 500")
    void failedLoginWithHugeUserAgent_returns401Not500() throws Exception {
        assertThat(HUGE_USER_AGENT.length())
                .as("用例前提：UA 必须显著超过列宽，否则测不到这条路径")
                .isGreaterThan(USER_AGENT_COLUMN_WIDTH * 2);

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("User-Agent", HUGE_USER_AGENT)
                        .content("{\"username\": \"%s\", \"password\": \"WrongPass1!\"}"
                                .formatted(username)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("IAM_BAD_CREDENTIALS"));

        Integer storedLength = jdbc.queryForObject(
                "SELECT max(length(user_agent)) FROM sys_login_log WHERE username = ?",
                Integer.class, username);

        assertThat(storedLength)
                .as("超长 UA 应被截断后落库，而不是整条日志写不进去")
                .isNotNull();
        assertThat(storedLength).isLessThanOrEqualTo(USER_AGENT_COLUMN_WIDTH);
    }

    @Test
    @CaseId("TC-SYS-093")
    @DisplayName("★ 超长 User-Agent 的成功登录：照样拿到 token，日志同步落库")
    void successLoginWithHugeUserAgent_stillIssuesToken() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("User-Agent", HUGE_USER_AGENT)
                        .content("{\"username\": \"%s\", \"password\": \"Correct1!\"}"
                                .formatted(username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty());

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sys_login_log WHERE username = ? AND success",
                Integer.class, username))
                .as("成功登录也要留下日志，且不能因为 UA 长就丢")
                .isEqualTo(1);
    }
}
