# -*- coding: utf-8 -*-
"""清理 PSC 转换后的遗留代码（V2-7）。

三个阶段各做一件事：
  ① `);} catch` → 换行缩进（脚本拼接时把两行挤到了一起）
  ② 删掉遗留的 `Number id = keys.getKey(); / if (id == null) {…}` 与 `return id.longValue();`
  ③ 删掉遗留的 `KeyHolder keys = new GeneratedKeyHolder();` 与不再使用的 import
"""
import re
from pathlib import Path

ROOT = Path(r"D:\erp-build-v27\backend\server\src\main\java\com\erp")

TAIL = re.compile(
    r"\n([ \t]*)Number\s+id\s*=\s*keys\.getKey\(\);\s*\n"
    r"(?:\1if\s*\(\s*id\s*==\s*null\s*\)\s*\{\s*\n(?:[^\n]*\n)*?\1\}\s*\n)?"
    r"(?:\1return\s+id\.longValue\(\);\s*\n)?")

touched = 0
for f in sorted(ROOT.rglob("*.java")):
    t = f.read_text(encoding="utf-8")
    o = t
    t = t.replace(");} catch", ");\n        } catch")
    t = TAIL.sub("\n", t)
    t = t.replace("        KeyHolder keys = new GeneratedKeyHolder();\n", "")
    if "GeneratedKeyHolder" not in t:
        t = t.replace("import org.springframework.jdbc.support.GeneratedKeyHolder;\n", "")
    if not re.search(r"\bKeyHolder\b", t):
        t = t.replace("import org.springframework.jdbc.support.KeyHolder;\n", "")
    if t != o:
        f.write_text(t, encoding="utf-8")
        touched += 1
print("files touched: " + str(touched))
