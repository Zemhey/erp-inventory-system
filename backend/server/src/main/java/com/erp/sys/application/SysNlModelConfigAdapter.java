package com.erp.sys.application;

import com.erp.shared.nl.NlModelConfigPort;
import com.erp.shared.nl.NlModelSettings;
import com.erp.sys.domain.SystemConfig;
import com.erp.sys.repository.SystemConfigRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 自然语言模型配置的存储实现（FR-SYS-04）：读写 {@code sys_config} 的 {@code nl} 组。
 *
 * <p><b>为什么不缓存</b>：{@code sys_config} 是**租户级**配置，进程内单例若缓存一份
 * {@link NlModelSettings}，A 租户填的 key 会被 B 租户的请求读到 —— 这是数据越界，
 * 不是性能优化。NL 指令是人工低频操作（一次请求查 5 行），省这一次查询毫无收益。
 * 需要缓存的话必须按 tenantId 分桶，这里刻意不做。
 *
 * <p><b>key 的存储形态</b>：{@code api-key} 落库前经 {@link SecretBox} 加密，
 * 读出来再解密。DB 里是 {@code enc:v1:...}，直接查库看不到明文。
 */
@Component
public class SysNlModelConfigAdapter implements NlModelConfigPort {

    private static final String GROUP = "nl";
    private static final String K_PARSER = "parser";
    private static final String K_REVIEWER = "reviewer";
    private static final String K_BASE_URL = "base-url";
    private static final String K_MODEL = "model";
    private static final String K_API_KEY = "api-key";

    private static final Map<String, String> REMARKS = Map.of(
            K_PARSER, "自然语言解析方式：stub=关键词规则（离线）/ openai=大模型 function calling",
            K_REVIEWER, "写操作复核方式：rule=仅确定性规则 / openai=规则+独立模型复核",
            K_BASE_URL, "OpenAI 兼容端点根地址（DeepSeek 官方为 https://api.deepseek.com/v1）",
            K_MODEL, "模型名（必须支持 function calling）",
            K_API_KEY, "模型 API Key（加密存储，接口只回显后四位）");

    private final SystemConfigRepository repository;
    private final SecretBox secretBox;

    public SysNlModelConfigAdapter(SystemConfigRepository repository, SecretBox secretBox) {
        this.repository = repository;
        this.secretBox = secretBox;
    }

    @Override
    public NlModelSettings current() {
        List<SystemConfig> rows = repository.listByGroup(GROUP);
        if (rows.isEmpty()) {
            return NlModelSettings.defaults();
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (SystemConfig row : rows) {
            values.put(row.configKey(), row.configValue() == null ? "" : row.configValue());
        }
        NlModelSettings d = NlModelSettings.defaults();
        return new NlModelSettings(
                blankTo(values.get(K_PARSER), d.parser()).toLowerCase(),
                blankTo(values.get(K_REVIEWER), d.reviewer()).toLowerCase(),
                blankTo(values.get(K_BASE_URL), d.baseUrl()),
                blankTo(values.get(K_MODEL), d.model()),
                secretBox.open(values.get(K_API_KEY)));
    }

    /**
     * 整体覆盖。缺失的行会补插，而不是静默丢弃 ——
     * 否则"某个键在某租户下不存在"会让配置看起来保存成功、实际没生效。
     */
    @Override
    @Transactional
    public void save(NlModelSettings settings) {
        Map<String, String> target = new LinkedHashMap<>();
        target.put(K_PARSER, settings.parser());
        target.put(K_REVIEWER, settings.reviewer());
        target.put(K_BASE_URL, settings.baseUrl());
        target.put(K_MODEL, settings.model());
        target.put(K_API_KEY, secretBox.seal(settings.apiKey()));

        Map<String, Long> existing = new LinkedHashMap<>();
        for (SystemConfig row : repository.listByGroup(GROUP)) {
            existing.put(row.configKey(), row.id());
        }
        target.forEach((key, value) -> {
            Long id = existing.get(key);
            if (id != null) {
                repository.updateValue(id, value);
            } else {
                repository.insert(GROUP, key, value, "STRING", REMARKS.getOrDefault(key, ""));
            }
        });
    }

    private static String blankTo(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }
}
