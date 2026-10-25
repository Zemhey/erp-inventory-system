# -*- coding: utf-8 -*-
"""删掉 19 处转换后残留的"取主键"代码（V2-7）。

转换器已经把主体换成 `return TenantJdbc.insertReturningId(...)`，
但**收尾三行**（`Number id = keys.getKey();` / `if (id == null) {…}` /
`return id.longValue();`）在某些文件里被前面的 `new Object[]{…});` 挤在同一行，
或者干脆没被匹配掉。这个脚本按"形态"清理：
  · `);} if (id == null) {`  → `);` 换行 + 删掉整个 if 与其后的 return
  · 单独的 `if (id == null) {…}` 紧跟 `return id.longValue();`
"""
import re
from pathlib import Path

ROOT = Path(r"D:\erp-build-v27\backend\server\src\main\java\com\erp")

# 1) 把 `);        if (id == null) {` 这类挤在一行的情况拆开
SPLIT = re.compile(r"\);\s*if\s*\(\s*id\s*==\s*null\s*\)\s*\{")
# 2) 删掉整个 if 块 + 紧随的 return id.longValue();
BLOCK = re.compile(
    r"\n[ \t]*if\s*\(\s*id\s*==\s*null\s*\)\s*\{\s*\n"
    r"(?:[^\n]*\n)*?"
    r"[ \t]*\}\s*\n"
    r"(?:[ \t]*return\s+id\.longValue\(\)\s*;\s*\n)?")
# 3) 单独的 return id.longValue();
RET = re.compile(r"\n[ \t]*return\s+id\.longValue\(\)\s*;\s*\n")

touched = 0
for f in sorted(ROOT.rglob("*.java")):
    t = f.read_text(encoding="utf-8")
    o = t
    t = SPLIT.sub(");\n        if (id == null) {", t)
    t = BLOCK.sub("\n", t)
    t = RET.sub("\n", t)
    if t != o:
        f.write_text(t, encoding="utf-8")
        touched += 1
        print("cleaned " + f.name)
print("files touched: " + str(touched))
