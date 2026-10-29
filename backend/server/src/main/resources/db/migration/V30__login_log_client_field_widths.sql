-- ═══════════════════════════════════════════════════════════════════════════
-- V30 · 登录日志的客户端可控字段宽度
--
-- 现象：微信小程序登录 100% 返回 500「服务器内部错误」，Web 浏览器登录正常。
-- 根因：sys_login_log.user_agent 是 VARCHAR(256)，而微信小程序 / 开发者工具发来的
--       User-Agent 普遍 300~400 字符（含 wechatdevtools/<版本>、MicroMessenger、
--       Language、webview、sid 等段），INSERT 直接违反长度约束：
--         ERROR: value too long for type character varying(256)
--       登录日志写在认证链路里，于是辅助记录把主流程打挂。
--       浏览器 UA 短，所以这条路径从未被暴露 —— 属于"只在真实客户端上才炸"的缺陷。
--
-- 与 V19.1 同源：那次是 tenant_id NOT NULL 把 /auth/login 打成 500。
-- 教训一致 —— 登录日志是辅助数据，它失败不允许影响登录结果。
-- 因此本迁移只负责"给合理宽度"，真正的防线在代码侧：
--   LoginLogRecorder 写入前按列宽截断，并捕获 DataAccessException 只告警不上抛。
--
-- 只放宽、不收窄、不动数据，存量库可直接平滑升级。
-- ═══════════════════════════════════════════════════════════════════════════

ALTER TABLE sys_login_log ALTER COLUMN user_agent TYPE VARCHAR(512);
ALTER TABLE sys_login_log ALTER COLUMN ip         TYPE VARCHAR(128);

COMMENT ON COLUMN sys_login_log.user_agent IS
    '客户端 User-Agent；写入前由 LoginLogRecorder 截断到 512（小程序 UA 可达 400+）';
COMMENT ON COLUMN sys_login_log.ip IS
    '来源 IP，取 X-Forwarded-For 首段；写入前截断到 128';
