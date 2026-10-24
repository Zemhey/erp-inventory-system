# -*- coding: utf-8 -*-
"""把 PreparedStatementCreator INSERT 换成 TenantJdbc.insertReturningId（V2-7）。

19 处的结构完全相同，因此用**边界匹配**（不是自由正则）：
  起点：「KeyHolder keys = new GeneratedKeyHolder();」（可无）
  主体：「jdbc.update(con -> { … return ps; }, keys);」
  尾部：「Number id = keys.getKey(); … return id.longValue();」

做法：先定位 `jdbc.update(con ->`，用括号配平找到 lambda 体；
从体里取**文本块原文**（一字不改）与 `ps.setXxx(n, expr)` 的表达式（按 n 排序）；
然后整段替换掉 [KeyHolder 行 .. `}, keys);`] 与后续的 id 收尾代码。

★ 关键：文本块只在结束定界符前插入 RETURNING id，**内部一行都不重排缩进**。
   Java 文本块按"结束定界符的缩进"剥离前缀空白，因此内部行原样保留 =
   SQL 内容不变。第一版重排了缩进，把 SQL 变成了空白+RETURNING（实测立刻发现）。
"""
import re
from pathlib import Path

ROOT = Path(r"D:\erp-build-v27\backend\server\src\main\java\com\erp")

SET_RE = re.compile(
    r"ps\.set(?P<kind>String|Long|Int|Boolean|Object|Double|BigDecimal)\s*"
    r"\(\s*(?P<pos>\d+)\s*,\s*(?P<expr>.+?)\s*\)\s*;", re.S)
IF_NULL_RE = re.compile(
    r"if\s*\(\s*(?P<val>\w+)\s*==\s*null\s*\)\s*\{\s*ps\.setNull\(\s*(?P<n>\d+)\s*,[^)]*\)\s*;\s*\}"
    r"\s*else\s*\{\s*ps\.set\w+\(\s*(?P=n)\s*,\s*(?P=val)\s*\)\s*;\s*\}", re.S)


def lambda_end(text, start):
    """从 `con -> {` 的左花括号之后开始，返回与之配对的右花括号下标。"""
    i = text.index("{", start) + 1
    depth, k = 1, i
    in_s = in_b = False
    while k < len(text):
        if in_b:
            if text.startswith('"""', k):
                in_b = False
                k += 3
                continue
            k += 1
            continue
        if in_s:
            if text[k] == "\\":
                k += 2
                continue
            if text[k] == '"':
                in_s = False
            k += 1
            continue
        if text.startswith('"""', k):
            in_b = True
            k += 3
            continue
        c = text[k]
        if c == '"':
            in_s = True
        elif c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return k
        k += 1
    raise SystemExit("括号不配平")


def convert(path: Path) -> int:
    text = path.read_text(encoding="utf-8")
    n = 0
    while True:
        m = re.search(r"[ \t]*jdbc\.update\(con\s*->\s*\{", text)
        if not m:
            break
        indent = m.group(0)[:len(m.group(0)) - len(m.group(0).lstrip())]
        end = lambda_end(text, m.end() - 1)
        body = text[m.end():end]

        pm = re.search(r'prepareStatement\(\s*("""(?:.|\n)*?""")\s*,\s*new\s+String\[\]\{"id"\}', body)
        if not pm:
            raise SystemExit(path.name + ": 不是预期的 prepareStatement 形态")
        sql_block = pm.group(1)

        entries = {}
        norm = IF_NULL_RE.sub(
            lambda mm: "ps.setLong(" + mm.group("n") + ", " + mm.group("val") + ");", body)
        for sm in SET_RE.finditer(norm):
            entries[int(sm.group("pos"))] = sm.group("expr").strip()
        pos = sorted(entries)
        if pos != list(range(1, len(pos) + 1)):
            raise SystemExit(path.name + ": 参数下标不连续 " + str(pos))
        args = [entries[p] for p in pos]

        # 文本块：内部原样，仅在结束定界符前插 RETURNING id
        inner = sql_block[3:-3]
        lines = inner.split("\n")
        close_indent = re.match(r"[ \t]*", lines[-1]).group(0)
        new_block = ('"""' + "\n".join(lines[:-1]) + "\n"
                     + close_indent + "RETURNING id\n" + close_indent + '"""')

        replacement = (indent + "return com.erp.shared.tenant.TenantJdbc.insertReturningId(jdbc,\n"
                       + indent + "        " + new_block + ",\n"
                       + indent + "        new Object[]{" + ", ".join(args) + "});")

        # 吃掉 `}, keys);` 以及其后的 id 收尾三行
        after = text[end + 1:]
        tail = re.match(r"\s*,\s*keys\s*\)\s*;\s*", after)
        if not tail:
            raise SystemExit(path.name + ": lambda 之后不是 , keys);")
        rest = after[tail.end():]
        rest = re.sub(r"^[ \t]*Number\s+id\s*=\s*keys\.getKey\(\)\s*;\s*\n", "", rest)

        # 起始位置：把 `KeyHolder keys = new GeneratedKeyHolder();` 那一行也吃掉
        start = m.start()
        head = text[:start]
        head = re.sub(r"[ \t]*KeyHolder\s+keys\s*=\s*new\s+GeneratedKeyHolder\(\)\s*;\s*\n$", "",
                      head)

        text = head + replacement + rest
        n += 1

    # 清理不再使用的 import（只有在全文件都不再出现时才删）
    if "GeneratedKeyHolder" not in text:
        text = text.replace("import org.springframework.jdbc.support.GeneratedKeyHolder;\n", "")
    if re.search(r"\bKeyHolder\b", text) is None:
        text = text.replace("import org.springframework.jdbc.support.KeyHolder;\n", "")
    if re.search(r"\bkeys\b", text) is None:
        text = re.sub(r"^[ \t]*(?:if\s*\(\s*keys.*\n(?:[^\n]*\n)*?[ \t]*\}\s*\n)", "",
                      text, flags=re.M)
    if n:
        path.write_text(text, encoding="utf-8")
    return n


total = 0
for f in sorted(ROOT.rglob("*.java")):
    c = convert(f)
    if c:
        print(f.name + ": " + str(c))
        total += c
print("total=" + str(total))
