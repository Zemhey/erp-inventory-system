# -*- coding: utf-8 -*-
"""把 PreparedStatementCreator 形态的 INSERT 改成"文本可见"形态（V2-7）。

为什么必须改：这些调用点把 SQL 文本藏在 lambda 里，TenantAwareJdbcTemplate
拿不到文本 → 无法追加 tenant_id 列 → 这些 INSERT 会绕过租户过滤。

★ 做法：用**括号配平**定位 lambda 体（字符串/文本块里的括号不算），
把体里的文本块与 ps.setXxx(n, …) 按位号抽出来，生成等价调用；
原 catch 分支在 lambda 之外，原样保留。
★ 文本块内部**原样保留**，只在结束定界符前插 RETURNING id。
"""
import re
from pathlib import Path

ROOT = Path(r"D:\erp-build-v27\backend\server\src\main\java\com\erp")

SET_RE = re.compile(
    r"ps\.set(?P<kind>String|Long|Int|Boolean|Object|Double|BigDecimal)\s*"
    r"\(\s*(?P<pos>\d+)\s*,\s*(?P<expr>.+?)\s*\)\s*;", re.S)
IF_NULL_RE = re.compile(
    r"if\s*\(\s*(?P<val>\w+)\s*==\s*null\s*\)\s*\{\s*ps\.setNull\(\s*(?P<n1>\d+)\s*,[^)]*\)\s*;\s*\}\s*"
    r"else\s*\{\s*ps\.set\w+\(\s*(?P<n2>\d+)\s*,\s*(?P=val)\s*\)\s*;\s*\}",
    re.S)


def find_lambda_body(text, start):
    i = text.index("{", start) + 1
    depth, k = 1, i
    in_str = in_block = False
    while k < len(text):
        if in_block:
            if text.startswith('"""', k):
                in_block = False
                k += 3
                continue
            k += 1
            continue
        if in_str:
            if text[k] == "\\":
                k += 2
                continue
            if text[k] == '"':
                in_str = False
            k += 1
            continue
        if text.startswith('"""', k):
            in_block = True
            k += 3
            continue
        c = text[k]
        if c == '"':
            in_str = True
        elif c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return i, k
        k += 1
    raise SystemExit("lambda 体括号不配平")


def transform(path):
    text = path.read_text(encoding="utf-8")
    changed = 0
    while True:
        m = re.search(r"[ \t]*jdbc\.update\((con|connection)\s*->\s*\{", text)
        if not m:
            break
        indent = re.match(r"[ \t]*", m.group(0)).group(0)
        body_start, body_end = find_lambda_body(text, m.end() - 1)
        body = text[body_start:body_end]

        pm = re.search(r'prepareStatement\(\s*("""(?:.|\n)*?""")', body)
        if not pm:
            raise SystemExit(path.name + ": lambda 里没有文本块")
        sql_block = pm.group(1)

        entries = {}
        normalized = IF_NULL_RE.sub(
            lambda mm: "ps.setLong(" + mm.group("n1") + ", " + mm.group("val") + ");", body)
        for sm in SET_RE.finditer(normalized):
            entries[int(sm.group("pos"))] = sm.group("expr").strip()
        positions = sorted(entries)
        if positions != list(range(1, len(positions) + 1)):
            raise SystemExit(path.name + ": 参数下标不连续 " + str(positions))
        args = [entries[p] for p in positions]

        inner = sql_block[3:-3]
        inner_lines = inner.split("\n")
        close_indent = re.match(r"[ \t]*", inner_lines[-1]).group(0)
        body_lines = inner_lines[:-1]
        new_block = ('"""' + "\n".join(body_lines) + "\n" + close_indent
                     + "RETURNING id\n" + close_indent + '"""')

        replacement = (indent + "return com.erp.shared.tenant.TenantJdbc.insertReturningId(jdbc,\n"
                       + indent + "        " + new_block + ",\n"
                       + indent + "        new Object[]{" + ", ".join(args) + "});")

        after = text[body_end + 1:]
        tail = re.match(r"\s*,\s*keys\s*\)\s*;\s*", after)
        if not tail:
            raise SystemExit(path.name + ": lambda 之后不是 , keys);")
        rest = after[tail.end():]
        rest = re.sub(r"^[ \t]*Number\s+id\s*=\s*keys\.getKey\(\)\s*;\s*\n", "", rest)
        rest = re.sub(r"^[ \t]*if\s*\(\s*id\s*==\s*null\s*\)\s*\{\s*\n(?:[^\n]*\n)*?[ \t]*\}\s*\n",
                      "", rest)
        rest = re.sub(r"^[ \t]*return\s+id\.longValue\(\)\s*;\s*\n", "", rest)

        text = text[:body_start - 1].rstrip() + "\n" + replacement + rest
        if "keys.getKey()" not in text:
            text = re.sub(r"[ \t]*KeyHolder\s+keys\s*=\s*new\s+GeneratedKeyHolder\(\)\s*;\s*\n",
                          "", text)
        if "GeneratedKeyHolder" not in text:
            text = text.replace(
                "import org.springframework.jdbc.support.GeneratedKeyHolder;\n", "")
        if "KeyHolder" not in text:
            text = text.replace("import org.springframework.jdbc.support.KeyHolder;\n", "")
        changed += 1

    if changed:
        path.write_text(text, encoding="utf-8")
    return changed


total = 0
for f in sorted(ROOT.rglob("*.java")):
    n = transform(f)
    if n:
        print(f.name + ": " + str(n))
        total += n
print("total=" + str(total))
