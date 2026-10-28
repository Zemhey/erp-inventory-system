-- ════════════════════════════════════════════════════════════════════════════
-- V29：账号补上「员工档案」字段
--
-- 背景：app_users 原本只有 username / display_name / 部门 / 岗位，
-- 用起来像"登录账号"而不是"员工"。人事视角需要的工号、联系方式、入职日期都没有，
-- 于是员工列表只能显示成"admin / 管理员"，看不出这是一家公司。
--
-- 这几列刻意留在 app_users 上，而不是新建 employee 表：
-- 本系统里"有账号的人"与"员工"没有分流（没有"只在花名册里、不登录"的人），
-- 建两张表就要处理一对一同步、离职时谁先停谁，收益不抵复杂度。
-- 如果将来真的出现"无账号员工"，再拆表也不迟。
--
-- 新增列都可空：历史账号（admin、迁移脚本建的用户）不需要回填。
-- ════════════════════════════════════════════════════════════════════════════

ALTER TABLE app_users
    ADD COLUMN IF NOT EXISTS employee_no varchar(32),
    ADD COLUMN IF NOT EXISTS phone       varchar(32),
    ADD COLUMN IF NOT EXISTS email       varchar(128),
    ADD COLUMN IF NOT EXISTS hire_date   date,
    ADD COLUMN IF NOT EXISTS remark      varchar(255);

COMMENT ON COLUMN app_users.employee_no IS '工号：人事口径的标识，同一租户内唯一（可为空）';
COMMENT ON COLUMN app_users.phone IS '手机号：用于通知与找回，不做登录凭证';
COMMENT ON COLUMN app_users.email IS '邮箱：可选，仅记录';
COMMENT ON COLUMN app_users.hire_date IS '入职日期：人事统计与试用期判断用';
COMMENT ON COLUMN app_users.remark IS '备注：离职原因、兼任岗位等自由文本';

-- 工号唯一性：只对"填了工号"的行生效（部分唯一索引），
-- 否则历史账号的 NULL 会互相冲突 —— 标准 SQL 里 NULL != NULL，
-- 但用唯一约束表达"可以多个空值"要靠部分索引才明确。
CREATE UNIQUE INDEX IF NOT EXISTS uq_app_user_employee_no
    ON app_users (tenant_id, employee_no)
    WHERE employee_no IS NOT NULL;

-- 员工列表按部门筛选是高频操作
CREATE INDEX IF NOT EXISTS idx_app_user_department
    ON app_users (tenant_id, department_id);
