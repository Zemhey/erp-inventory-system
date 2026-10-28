-- ════════════════════════════════════════════════════════════════════════════
-- V26：自然语言模型接入参数（FR-SYS-04 · 页面可配）
--
-- 为什么放进 sys_config 而不是 application.yml：
--   application.yml 进 git，key 写进去等于公开发布；而且改一次要重启。
--   放进 sys_config 后：按租户隔离、页面上可改、保存即生效。
--
-- ★ api-key 默认留空，不是漏写：
--   空值 = 未配置，界面显示"未配置"，解析器自动回退关键词模式。
--   写入时会由后端加密（AES-GCM）再落库，库里看不到明文。
--   这里只插空行作为"占位"，让参数页能显示这一项。
-- ════════════════════════════════════════════════════════════════════════════

INSERT INTO sys_config (tenant_id, config_group, config_key, config_value, value_type, remark)
SELECT t.id, v.g, v.k, v.val, v.ty, v.remark
FROM tenants t
CROSS JOIN (VALUES
    ('nl', 'parser',   'stub',                          'STRING', '自然语言解析方式：stub=关键词规则（离线）/ openai=大模型 function calling'),
    ('nl', 'reviewer', 'rule',                          'STRING', '写操作复核方式：rule=仅确定性规则 / openai=规则+独立模型复核'),
    ('nl', 'base-url', 'https://api.deepseek.com/v1',   'STRING', 'OpenAI 兼容端点根地址（DeepSeek 官方为 https://api.deepseek.com/v1）'),
    ('nl', 'model',    'deepseek-chat',                 'STRING', '模型名（必须支持 function calling；deepseek-reasoner 不支持）'),
    ('nl', 'api-key',  '',                              'STRING', '模型 API Key（后端加密存储，接口只回显后四位）')
) AS v(g, k, val, ty, remark)
ON CONFLICT (tenant_id, config_group, config_key) DO NOTHING;

COMMENT ON COLUMN sys_config.config_value IS
    '系统参数（FR-V3-24）：按 valueType 解释。config_group=nl 的 api-key 由应用层 AES-GCM 加密后写入（enc:v1: 前缀）';
