# -*- coding: utf-8 -*-
"""把注入了 jdbc 的仓储字段/构造器类型改成 TenantAwareJdbcTemplate（V2-7）。

为什么需要：`TenantJdbc.insertReturningId(jdbc, …)` 的第一个参数是
`TenantAwareJdbcTemplate`（它才有"已知租户"这个语义）。而各仓储的字段声明是
`JdbcTemplate`，于是编译期类型不匹配。

★ 为什么不把 insertReturningId 的参数放宽成 `JdbcTemplate`：
那会让"某个仓储拿到的其实是朴素 JdbcTemplate（没装租户改写）"这件事
在编译期无法被发现 —— 而那正是本泳道要防的"静默绕过"。
用更窄的类型（TenantAwareJdbcTemplate）反而是一道编译期门禁：
注入不到它，就编不过。
"""
import re
from pathlib import Path

ROOT = Path(r"D:\erp-build-v27\backend\server\src\main\java\com\erp")
TARGET = "com.erp.shared.tenant.TenantAwareJdbcTemplate"

# 只改"确实调用了 TenantJdbc.*"的文件
files = [f for f in ROOT.rglob("*.java")
         if "com.erp.shared.tenant.TenantJdbc." in f.read_text(encoding="utf-8")]

for f in files:
    t = f.read_text(encoding="utf-8")
    t = t.replace("private final JdbcTemplate jdbc;",
                  "private final " + TARGET + " jdbc;")
    t = re.sub(r"public (\w+)\(JdbcTemplate jdbc\)",
               "public " + r"\1(" + TARGET + " jdbc)", t)
    t = re.sub(r"public (\w+)\(JdbcTemplate jdbc,\s*", "public " + r"\1(" + TARGET + " jdbc, ", t)
    if "org.springframework.jdbc.core.JdbcTemplate" in t and TARGET not in t:
        pass
    f.write_text(t, encoding="utf-8")
    print("narrowed " + f.name)
print("files=" + str(len(files)))
