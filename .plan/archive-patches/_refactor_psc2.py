# -*- coding: utf-8 -*-
"""把 PreparedStatementCreator INSERT 转成 INSERT … RETURNING id（V2-7）。

只处理**结构完全确定**的那一种：`KeyHolder keys = new GeneratedKeyHolder();`
紧跟 `jdbc.update(con -> { … return ps; }, keys);` 和 `Number id = keys.getKey();`
/ null 检查 / `return id.longValue();`。

做法：整段替换（用正则精确匹配整段，而不是逐行重拼），替换体由脚本生成、
但**文本块原样拷贝**（含缩进），只在结束定界符之前插入 RETURNING id。
"""
import re
from pathlib import Path

ROOT = Path(r"D:\erp-build-v27\backend\server\src\main\java\com\erp")

SET_RE = re.compile(
    r"ps\.set(?P<kind>String|Long|Int|Boolean|Object|Double|BigDecimal)\s*"
    r"\(\s*(?P<pos>\d+)\s*,\s*(?P<expr>.+?)\s*\)\s*;", re.S)
IF_NULL_RE = re.compile(
    r"if\s*\(\s*(?P<val>\w+)\s*==\s*null\s*\)\s*\{\s*ps\.setNull\(\s*(?P<n>\d+)\s*,[^)]*\)\s*;\s*\}\s*"
    r"else\s*\{\s*ps\.set\w+\(\s*(?P=n)\s*,\s*(?P=val)\s*\)\s*;\s*\}", re.S)

# 整段匹配：从 'KeyHolder keys = ...' 到 'return id.longValue();'
BLOCK_RE = re.compile(
    r"(?P<ind>[ \t]*)KeyHolder\s+keys\s*=\s*new\s+GeneratedKeyHolder\(\)\s*;\s*\n"
    r"(?P<pre>(?:[ \t]*(?:try\s*\{\s*\n|[ \t]*\n))?)"
    r"[ \t]*jdbc\.update\(con\s*->\s*\{\s*\n"
    r"(?P<body>.*?)"
    r"[ \t]*\}\s*,\s*keys\s*\)\s*;\s*\n"
    r"(?P<tail>(?:.*?\n)*?)"
    r"[ \t]*return\s+id\.longValue\(\)\s*;\s*\n",
    re.S)


def convert(path: Path) -> int:
    text = path.read_text(encoding="utf-8")
    count = 0
    while True:
        m = BLOCK_RE.search(text)
        if not m:
            break
        ind = m.group("ind")
        body = m.group("body")

        pm = re.search(r'prepareStatement\(\s*("""(?:.|\n)*?""")', body)
        if not pm:
            raise SystemExit(path.name + ": 没有文本块")
        sql_block = pm.group(1)

        entries = {}
        norm = IF_NULL_RE.sub(
            lambda mm: "ps.setLong(" + mm.group("n") + ", " + mm.group("val") + ");", body)
        for sm in SET_RE.finditer(norm):
            entries[int(sm.group("pos"))] = sm.group("expr").strip()
        positions = sorted(entries)
        if positions != list(range(1, len(positions) + 1)):
            raise SystemExit(path.name + ": 参数下标不连续 " + str(positions))
        args = [entries[p] for p in positions]

        inner = sql_block[3:-3]
        lines = inner.split("\n")
        close_indent = re.match(r"[ \t]*", lines[-1]).group(0)
        new_block = ('"""' + "\n".join(lines[:-1]) + "\n"
                     + close_indent + "RETURNING id\n" + close_indent + '"""')

        call = (ind + "return com.erp.shared.tenant.TenantJdbc.insertReturningId(jdbc,\n"
                + ind + "        " + new_block + ",\n"
                + ind + "        new Object[]{" + ", ".join(args) + "});\n")

        pre = m.group("pre")
        if pre.strip() == "try {":
            # 保留 try 包裹：catch 在 tail 里
            replacement = ind + "try {\n" + call + m.group("tail")
        else:
            replacement = call + m.group("tail")

        text = text[:m.start()] + replacement + text[m.end():]
        count += 1

    if count:
        if "GeneratedKeyHolder" not in text:
            text = text.replace("import org.springframework.jdbc.support.GeneratedKeyHolder;\n", "")
        if "KeyHolder" not in text:
            text = text.replace("import org.springframework.jdbc.support.KeyHolder;\n", "")
        path.write_text(text, encoding="utf-8")
    return count


total = 0
for f in sorted(ROOT.rglob("*.java")):
    n = convert(f)
    if n:
        print(f.name + ": " + str(n))
        total += n
print("total=" + str(total))
