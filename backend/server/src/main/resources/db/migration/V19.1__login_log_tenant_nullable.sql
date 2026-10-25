-- V19.1：登录日志的 tenant_id 允许为空
--
-- 问题：V19 把 sys_login_log.tenant_id 建成 NOT NULL，但登录请求在认证完成之前
-- 还不知道租户（登录失败更无从得知），TenantAwareJdbcTemplate 此时拿到的是空上下文，
-- 于是 INSERT 抛 not-null 约束冲突，把整个 /auth/login 打成 500。
--
-- 语义上也应该允许为空：登录日志的 tenant_id 只用于事后按租户筛选，
-- 「未知租户的失败登录」本身就是一条有意义的记录，不该因为不知道租户而丢掉。
ALTER TABLE sys_login_log ALTER COLUMN tenant_id DROP NOT NULL;

-- 按租户 + 时间查询登录日志是主要用法；tenant_id 可能为空，索引仍有效（NULL 不入索引）。
CREATE INDEX IF NOT EXISTS idx_sys_login_log_tenant_time
    ON sys_login_log (tenant_id, login_at DESC);
