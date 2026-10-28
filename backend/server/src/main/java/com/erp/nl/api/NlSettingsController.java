package com.erp.nl.api;

import com.erp.iam.security.RequirePermission;
import com.erp.nl.application.NlSettingsService;
import com.erp.shared.web.ApiResponses;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「系统 → AI 接入」配置接口（FR-SYS-04）。
 *
 * <p><b>为什么配置放在页面上而不是 {@code application.yml}</b>：
 * yml 要进 git，key 写进去等于公开发布；而且改一次要重启。
 * 放到租户级的 {@code sys_config} 后：页面上填、保存即生效、按租户隔离，
 * 也让"这台机器到底有没有在调模型"变成可查的事实，而不是靠猜。
 *
 * <p><b>权限用 {@code sys:config}</b>（与系统参数同一枚）而不是 {@code sys:read}：
 * 这里能改模型地址和 Key，等于能改"系统把数据发给谁"。
 * 让只读账号看见掩码没问题，但绝不能让他改 —— 把 key 指向自己的端点，
 * 之后每句自然语言指令都会带着业务数据打到那个地址。
 *
 * <p><b>GET 永远不返回明文 key</b>，只回 {@code ••••abcd}。这不是前端做样子，
 * 是后端根本不把它放进响应体：即使有人直接 curl，也拿不到。
 */
@RestController
@RequestMapping("/api/v1/nl")
public class NlSettingsController {

    private final NlSettingsService settings;

    public NlSettingsController(NlSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping("/settings")
    @RequirePermission("sys:config")
    public ResponseEntity<Map<String, Object>> view() {
        return ResponseEntity.ok(ApiResponses.ok(settings.view()));
    }

    /**
     * 保存。{@code apiKey} 的语义：不传或空 = 保持原值；{@code __CLEAR__} = 清空；其它 = 替换。
     * 这个语义只实现一次（{@link NlSettingsService#update}），Controller 不做二次解释。
     */
    @PutMapping("/settings")
    @RequirePermission("sys:config")
    public ResponseEntity<Map<String, Object>> update(
            @RequestBody NlSettingsService.Patch patch) {
        return ResponseEntity.ok(ApiResponses.ok(settings.update(patch)));
    }

    /**
     * 测试连接：用请求里的参数（缺省用已保存的）真发一次 function calling 请求，**不落库**。
     *
     * <p>返回体里 {@code ok=false} 也是 HTTP 200：这是用户主动发起的探测结果，
     * 不是服务端故障。用 4xx/5xx 表达会逼前端写"错误里读成功信息"的怪代码，
     * 而且失败原因（key 无效 / 地址不通 / 模型不支持工具）本来就是最有用的信息。
     */
    @PostMapping("/settings/test")
    @RequirePermission("sys:config")
    public ResponseEntity<Map<String, Object>> test(
            @RequestBody(required = false) NlSettingsService.TestRequest request) {
        return ResponseEntity.ok(ApiResponses.ok(settings.test(request)));
    }
}
