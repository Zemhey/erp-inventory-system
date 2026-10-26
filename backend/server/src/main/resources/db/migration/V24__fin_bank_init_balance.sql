-- ============================================================
-- V24：银行账户的"期初余额"与"现余额"必须分开存，并把日记账余额链重建一次
--
-- 为什么要改（实测，不是设想）：
--   fin_bank_account 只有一个 balance 列，它同时承担三种角色——
--     ① 建户时填的"期初余额"落在这里；
--     ② 每写一笔日记账，代码用"上一笔（按 biz_date 取最新）的 balance ± 本笔金额"
--        覆盖它，并把这个值原样写进新行；
--     ③ 编辑账户时 PUT /fin/bank-accounts/{id} 又把请求里的 initBalance 直接写回它。
--   三条路径互相踩：补录一笔**过去日期**的分录，新行的"余额"是拿**最新**那笔的余额算的，
--   于是账户余额被一个不属于链尾的数字覆盖，后面所有行的余额也全都错位；
--   期初更是第一笔流水一到就永久丢失。
--   实测后果：演示库里招行基本户建户期初 500,000 元，第一笔收款后 balance 就变成
--   1,240.92（期初凭空消失），最后 60 笔流水把它刷成负数；
--   同一时刻财务首页"资金余额"83,917.46，而资产负债表 1002 银行存款 -102,146.45。
--
-- 本迁移做三件事：
--   ① 新增 init_balance 列，专门存期初（此后 balance 只作为现余额，由期初+流水算出）；
--   ② 存量回填：期初 = 当前 balance − 该账户流水净额（对链没被打乱的账户是精确解）；
--   ③ 用窗口函数按 (biz_date, id) 重建每一行的逐笔余额，再把现余额刷成 期初+累计净额。
--   已经被补录打乱过的库（比如上面那个演示库）第②步只能得到一个自洽但偏离事实的期初，
--   那种库要靠业务动作纠正：在银行账户页把期初改成真实数字即可（改完余额自动重算）。
-- ============================================================

ALTER TABLE fin_bank_account
    ADD COLUMN IF NOT EXISTS init_balance NUMERIC(18, 2) NOT NULL DEFAULT 0;

COMMENT ON COLUMN fin_bank_account.init_balance IS
    '期初余额（元）。建户/编辑时填的就是它；现余额 balance = 期初 + Σ借 − Σ贷，由服务端重算，不接受外部直接写入';
COMMENT ON COLUMN fin_bank_account.balance IS
    '现余额（元）。派生值：每次写日记账或改期初后按 期初 + 流水净额 重算，不要手工 UPDATE';

-- ② 存量回填期初
UPDATE fin_bank_account a
   SET init_balance = a.balance - COALESCE(
           (SELECT SUM(CASE WHEN j.direction = 'DEBIT' THEN j.amount ELSE -j.amount END)
              FROM fin_cash_journal j
             WHERE j.account_id = a.id), 0);

-- ③ 逐笔余额链重建（每行 = 该账户期初 + 到这一行为止的累计净额）
UPDATE fin_cash_journal j
   SET balance = a.init_balance + c.signed_run
  FROM (SELECT id,
               account_id,
               SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END)
                   OVER (PARTITION BY account_id ORDER BY biz_date, id
                         ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS signed_run
          FROM fin_cash_journal) c
  JOIN fin_bank_account a ON a.id = c.account_id
 WHERE j.id = c.id;

-- ③' 现余额刷成 期初 + 累计净额
UPDATE fin_bank_account a
   SET balance = a.init_balance + COALESCE(
           (SELECT SUM(CASE WHEN j.direction = 'DEBIT' THEN j.amount ELSE -j.amount END)
              FROM fin_cash_journal j
             WHERE j.account_id = a.id), 0);
