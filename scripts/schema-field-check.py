"""字段级契约门禁 —— openapi.yaml 的 schema 字段与边界 ⇔ 后端 DTO / Controller 双向核对。

## 为什么需要它（V2-C3 · 2026-10-24）

`scripts/contract-check.py` 与 `ApiContractMappingIT` 都停在**操作级**：
一个查幂等键声明与 operationId 唯一，另一个比对「路径 + 方法」集合。
于是 `.plan/V2-C2-报告.md` §7.3 登记的三类**字段级**缺陷，两者一个都发现不了：

| 类 | 病 | 本脚本靠哪一条抓到 |
|---|---|---|
| H | 实现收发某字段，契约 schema 里没有它（`Warehouse.priority`、`Sku.seasonCode`…） | ② 请求体字段 ⇔ record 组件；④ 响应 `data` ⇔ Controller 的 `toMap` |
| L | 契约的 `minimum`/`maximum` 比实现宽（`discountRate` 写 0，实现 `@Min(1)`） | ③ 数值/长度边界 ⇔ Bean Validation 注解 |
| M | 悬空 `$ref`（`#/components/schemas/PurchaseOrder` 被引用但从未定义） | ① 全树 `$ref` 必须可解引用 |

`ApiContractMappingIT` 看不见 H 的原因是结构性的：**它只比操作，不比字段**。
所以 `UNDOCUMENTED_DEBT` 清零也拦不住 —— 那 28 个操作都在契约里，缺的是字段。

## 与 contract-check.py 的分工

| | contract-check.py | 本脚本 |
|---|---|---|
| 粒度 | 操作 / 幂等键 / operationId | 字段 / 边界 / `$ref` 图 / query 参数 |
| 实现侧事实源 | Controller 方法签名 | record 组件 + Bean Validation 注解 + `toMap` 的 put 键 |

## 判据取向：以实现为事实源，两个方向都报

- 契约**缺**字段、边界**宽于**实现 → 错误。照契约写的前端会漏传字段、发出越界值。
- 契约**多**字段、边界**严于**实现 → 同样错误。这是「契约承诺了实现没有的东西」，
  与 V2-C2 报告 §7.1-A 那条幂等键的教训同构。

## 响应侧怎么读「载荷形状」（这一步比请求侧难得多）

请求体的形状直接从 record 组件取；响应体是 Controller 里现拼的 `Map<String,Object>`，
所以本脚本对 `return` 表达式做了一个**只服务于"长什么样"的迷你求值器**
（`EvalCtx`），认这几种写法：

| 写法 | 例子 |
|---|---|
| 信封剥壳 | `ResponseEntity.ok(ApiResponses.ok(…))` / 私有别名 `ok(Object)` |
| 分页 | `ApiResponses.page(new PageResult<>(items, …))`、`ApiResponses.page(svc.page(…))` |
| 就地构造 | `Map<String,Object> m = new LinkedHashMap<>(); m.put("id", …)` |
| 合并 | `m.putAll(toTotalsMap(t.totals()))`（`SettlementController.toItemMap`） |
| 元素循环 | `List<Map<String,Object>> lines = new ArrayList<>(); … lines.add(lm); m.put("lines", lines)` |
| 流映射 | `xs.stream().map(Ctrl::toMap).toList()` |
| 常量 Map | `Map.of("items", items)` |

读不出来的子树（契约元素写成裸 `{}`、或载荷由 service 层拼装）记进末尾的
「未比对缺口」并计数，**既不算红也不算绿** —— 这是防"假绿"的关键：
静默跳过等于给门禁开后门。

## 四个实现细节（都是踩过坑之后定的）

1. **先抹注释与字符串，再数括号**。`contract-check.py` 曾用「往后 N 行」窗口判方法体，
   被相邻方法的注解串味过一次。这里改成单遍词法扫描：把 `//`、`/* */`、`"…"`、`'…'`、
   `\"\"\"…\"\"\"` 的内容抹成**等长空格**，括号配平在抹好的文本上做，取值回到原文同一下标 ——
   长度不变所以两套下标一一对应，`@PostMapping("/{id}/void")` 里的 `{` `}` 骗不到人。
2. **record 名按文件作用域优先解析**。`CreateRequest` 这种名字全仓有 8 份
   （`WarehouseController.CreateRequest` 与 `SkuController.CreateRequest` 是两回事），
   只按简单名全局索引会张冠李戴。
3. **同名 `toMap` 重载要按「参数类型 → 限定类名 → 契约 schema 名」三级消歧**。
   `ApprovalController` 有 `toMap(ApprovalInstance)` 与 `toMap(ApprovalStep)`，
   `ReportController` 有 `toMap(ReportRow)` 与 `toMap(InventorySummaryRow)`；
   按名字取第一个会让整块响应字段错位（第一版就这样造出过上百条误报）。
4. **`allOf` 里同名属性要递归并集，不是后者覆盖前者**。
   `allOf: [ApiPageResponse, {data: {properties: {items: [...]}}}]` 是常见写法，
   直接 `dict.update` 会让 `total/page/size` 被误判成"实现多返回了字段"。

`--selftest` 再兜一层：现场注入**四类**漂移（含"契约声明了实现没有的字段"这种反向漂移），
证明这些检查真的会红，而不是一个永远绿的空校验器。

用法：
    python scripts/schema-field-check.py                # 校验当前契约
    python scripts/schema-field-check.py --spec <路径>   # 在旧契约副本上复现历史漂移
    python scripts/schema-field-check.py --selftest      # 注入漂移，证明它不是永绿的空校验器
    python scripts/schema-field-check.py --show-gaps     # 逐条打印未比对缺口

退出码 0 = 通过，1 = 失败。与 `contract-check.py` 一样是 CI job 的本地等价物，可直接被调。
"""
import sys
import re
import copy
import argparse
import pathlib

# ── Windows GBK 控制台保护（与 contract-check.py 同款）───────────────────
try:
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
except Exception:  # 老版本 Python 或非标准流
    pass

OK = '\u2713'   # ✓
NG = '\u2717'   # ✗

try:
    import yaml
except ImportError:
    print('缺少 pyyaml，请先 pip install pyyaml')
    sys.exit(1)

ROOT = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_CONTRACT = ROOT / 'packages/api-contract/openapi.yaml'
SRC_ROOT = ROOT / 'backend/server/src/main/java'
HTTP_METHODS = ('get', 'post', 'put', 'patch', 'delete')
MAX_DEPTH = 6
REQUIRED_ANNS = {'NotNull', 'NotBlank', 'NotEmpty'}
JDK_TYPES = {'String', 'Long', 'Integer', 'Boolean', 'Double', 'Float', 'Short', 'Byte',
             'BigDecimal', 'LocalDate', 'LocalDateTime', 'LocalTime', 'Instant', 'OffsetDateTime',
             'UUID', 'Object', 'Map', 'List', 'Set', 'Collection', 'Page', 'DocumentStatus'}

AMBIGUOUS = object()


# ══════════════════════════════════════════════════════════════════════
# 1. Java 侧扫描：抹噪声 → 配平 → record / Controller / Map 构造器
# ══════════════════════════════════════════════════════════════════════

def _scan(text, keep_strings):
    """返回需要抹成空格的字符下标集合。

    keep_strings=False：注释 + 字符串/字符字面量全抹（用于括号配平、结构扫描）
    keep_strings=True ：只抹注释（用于取 @RequestMapping 路径、m.put 的键名）
    """
    out = set()
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        nxt2 = text[i:i + 2]
        if c == '/' and nxt2 == '//':
            j = text.find('\n', i)
            j = n if j < 0 else j
            out.update(range(i, j))
            i = j
        elif c == '/' and nxt2 == '/*':
            j = text.find('*/', i + 2)
            j = n if j < 0 else j + 2
            out.update(range(i, j))
            i = j
        elif text[i:i + 3] == '"""':
            j = text.find('"""', i + 3)
            j = n if j < 0 else j + 3
            if keep_strings:
                out.update(range(i, j))
            else:
                out.update(range(i + 3, max(i + 3, j - 3)))
            i = j
        elif c in '"\'':
            j = i + 1
            while j < n:
                if text[j] == '\\':
                    j += 2
                    continue
                if text[j] == c:
                    break
                j += 1
            if not keep_strings:
                out.update(range(i + 1, min(j, n)))
            i = min(j + 1, n)
        else:
            i += 1
    return out


def masked(text, blanked):
    out = list(text)
    for k in blanked:
        if out[k] != '\n':
            out[k] = ' '
    return ''.join(out)


def match_paren(text, open_pos):
    depth = 0
    for k in range(open_pos, len(text)):
        if text[k] == '(':
            depth += 1
        elif text[k] == ')':
            depth -= 1
            if depth == 0:
                return k
    return -1


def match_brace(text, open_pos):
    depth = 0
    for k in range(open_pos, len(text)):
        if text[k] == '{':
            depth += 1
        elif text[k] == '}':
            depth -= 1
            if depth == 0:
                return k
    return -1


def line_of(raw_text, pos):
    return raw_text.count('\n', 0, pos) + 1


def split_top_level(text):
    """按顶层逗号切分；`()`/`[]`/`<>` 内部的逗号不算分隔符（`@Size(min = 2, max = 32)` 靠它）。"""
    parts, cur, depth = [], [], 0
    for ch in text:
        if ch in '([{<':
            depth += 1
        elif ch in ')]}>':
            depth -= 1
        if ch == ',' and depth == 0:
            parts.append(''.join(cur))
            cur = []
        else:
            cur.append(ch)
    if ''.join(cur).strip():
        parts.append(''.join(cur))
    return parts


ANN_RE = re.compile(r'@([A-Za-z]\w*)\s*(\((?:[^()]|\([^()]*\))*\))?')
NUM_RE = re.compile(r'-?\d+')


def ann_int(body):
    if not body:
        return None
    m = NUM_RE.search(body)
    return int(m.group()) if m else None


def ann_kwarg(body, key):
    m = re.search(rf'\b{key}\s*=\s*(-?\d+)', body or '')
    return int(m.group(1)) if m else None


class Field:
    """可比对的字段信息；DTO 侧与契约侧共用同一个形状，比对代码只写一遍。"""
    __slots__ = ('name', 'java_type', 'minimum', 'maximum', 'min_length', 'max_length',
                 'required', 'not_empty_list', 'source')

    def __init__(self, name, java_type='', minimum=None, maximum=None,
                 min_length=None, max_length=None, required=False,
                 not_empty_list=False, source=''):
        self.name = name
        self.java_type = java_type
        self.minimum = minimum
        self.maximum = maximum
        self.min_length = min_length
        self.max_length = max_length
        self.required = required
        self.not_empty_list = not_empty_list
        self.source = source   # 人类可读的事实源，如 '@Min(1) @Max(10000)'


def field_from_component(comp):
    """`@Min(1) @Max(10000) Integer discountRate` → Field。"""
    anns = [(m.group(1), (m.group(2) or '')[1:-1]) for m in ANN_RE.finditer(comp)]
    decl = ' '.join(ANN_RE.sub(' ', comp).split())
    m = re.match(r'^(.*\S)\s+([A-Za-z_]\w*)$', decl)
    if not m:
        return None
    java_type, name = m.group(1), m.group(2)
    # `java.time.LocalDate expiryDate` 这种全限定写法：只留简单名。
    # 不剥的话下面 `^[A-Z<]` 直接判否，字段被**静默丢掉**（第一版就漏了
    # `InventoryController.ReceiveRequest` 的 productionDate / expiryDate 两个字段）。
    java_type = re.sub(r'\b(?:[a-z]\w*\.)+([A-Z]\w*)', r'\1', java_type)
    if not re.match(r'^[A-Z<]', java_type):
        return None
    f = Field(name, java_type)
    marks = []
    for an, body in anns:
        if an == 'Min':
            f.minimum = ann_int(body)
        elif an == 'Max':
            f.maximum = ann_int(body)
        elif an == 'Positive':
            f.minimum = 1
        elif an == 'PositiveOrZero':
            f.minimum = 0
        elif an == 'Negative':
            f.maximum = -1
        elif an == 'NegativeOrZero':
            f.maximum = 0
        elif an == 'Range':
            f.minimum = ann_kwarg(body, 'min')
            f.maximum = ann_kwarg(body, 'max')
        elif an == 'Size':
            f.min_length = ann_kwarg(body, 'min')
            f.max_length = ann_kwarg(body, 'max')
        elif an in REQUIRED_ANNS:
            f.required = True
            if an == 'NotEmpty' and java_type.startswith(('List', 'Set', 'Collection')):
                f.not_empty_list = True
        if an not in ('Valid',):
            marks.append(an)
    f.source = ' '.join('@' + x for x in marks)
    return f


def nested_record_name(java_type):
    """`List<LineRequest>` → ('LineRequest', True)；`Long` → (None, False)。"""
    plain = re.sub(r'<[^<>]*>', lambda m: ' ' + m.group(0), java_type)
    names = [t for t in re.findall(r'[A-Z]\w*', plain) if t not in JDK_TYPES]
    is_collection = java_type.startswith(('List', 'Set', 'Collection')) or '[]' in java_type
    for n in names:
        return n, is_collection
    return None, is_collection


class JavaSource:
    """一个 .java：record 组件表 + Controller 处理器表 + Map 构造器表。"""

    def __init__(self, path):
        self.path = path
        self.raw = path.read_text(encoding='utf-8', errors='ignore')
        self.nc = masked(self.raw, _scan(self.raw, keep_strings=True))    # 留字符串
        self.clean = masked(self.raw, _scan(self.raw, keep_strings=False))  # 字符串也抹
        self.records = {}
        self.builders = {}
        self.page_methods = {}
        self.handlers = []
        self._parse_records()
        self._parse_builders()
        self._parse_page_methods()
        if path.name.endswith('Controller.java'):
            self._parse_handlers()

    # ── PageResult<T> 分页方法 ────────────────────────────────────────
    def _parse_page_methods(self):
        """`PageResult<Supplier> page(...)` → 元素类型 `Supplier`。

        `GET /suppliers` 这类列表端点把分页交给应用服务，Controller 只写
        `ApiResponses.page(suppliers.page(...))`；元素字段从 record `Supplier`
        推不出来（服务层用的是 `PageResult<Supplier>`），只能从这里取。
        """
        for m in re.finditer(r'\bPageResult\s*<\s*([A-Z]\w*)\s*>\s+(\w+)\s*\(', self.clean):
            self.page_methods.setdefault(m.group(2), m.group(1))

    # ── record ────────────────────────────────────────────────────────
    def _parse_records(self):
        for m in re.finditer(r'\brecord\s+([A-Z]\w*)\s*(?:<[^>]*>)?\s*\(', self.clean):
            close = match_paren(self.clean, m.end() - 1)
            if close < 0:
                continue
            fields = []
            for comp in split_top_level(self.clean[m.end():close]):
                if comp.strip():
                    f = field_from_component(comp)
                    if f:
                        fields.append(f)
            self.records[m.group(1)] = fields

    # ── Map<String, Object> 构造器 ─────────────────────────────────────
    def _parse_builders(self):
        # ★ 按 (名字, 参数类型) 存成重载表，不能只按名字。
        #   `ApprovalController` 里 `toMap(ApprovalInstance)` 与 `toMap(ApprovalStep)` 同名，
        #   第一版用 name 做键，后一个把前一个**覆盖**掉，
        #   于是 18 个操作的响应字段整体错位、凭空造出上百条误报。
        for m in re.finditer(r'Map<String,\s*Object>\s+(\w+)\s*\(', self.nc):
            close = match_paren(self.nc, m.end() - 1)
            if close < 0:
                continue
            params = self.nc[m.end():close]
            # 只取**第一个**形参的类型：`toMap(CustomerReceivable r, LocalDate today)`
            # 这种多参构造器同样是一次响应映射，第二参（当前日期）不影响字段集合。
            parts = split_top_level(params)
            first = parts[0].strip() if parts else ''
            pt = re.match(r'^([A-Z]\w*)(?:<[^>]*>)?\s+\w+\s*$', first)
            brace = self.nc.find('{', close)
            end = match_brace(self.nc, brace) if brace >= 0 else -1
            if end < 0:
                continue
            body = self.nc[brace + 1:end]
            ret = re.search(r'\breturn\s+(\w+)\s*;', body)
            if not ret:
                continue
            self.builders.setdefault(m.group(1), []).append(
                Builder(pt.group(1) if pt else '', body, ret.group(1)))

    # ── Controller 处理器 ──────────────────────────────────────────────
    def _parse_handlers(self):
        prefix = ''
        for m in re.finditer(r'@RequestMapping\b', self.clean):
            span = annotation_args(self.clean, self.nc, m)
            if span:
                paths = re.findall(r'"([^"]*)"', self.nc[span[0]:span[1]])
                if paths:
                    prefix = paths[0]
                    break

        class_pos = self.clean.find('class ')
        for m in re.finditer(r'@(Get|Post|Put|Patch|Delete)Mapping\b', self.clean):
            if class_pos >= 0 and m.start() < class_pos:
                continue
            span = annotation_args(self.clean, self.nc, m)
            rel = ''
            if span:
                paths = re.findall(r'"([^"]*)"', self.nc[span[0]:span[1]])
                rel = paths[0] if paths else ''
            # ★ 方法名前那个左括号，不是注解的左括号。
            #   本项目每个处理器都写成 @GetMapping → @RequirePermission("mdm:read") → 方法声明，
            #   直接 find('(') 会抓到 @RequirePermission 的参数表，
            #   于是 @RequestBody / @RequestParam 一个都看不见（第一版 161 个操作全瞎）。
            after = self._skip_annotations(self.clean, span[1] + 1 if span else m.end())
            if after < 0:
                continue
            close = match_paren(self.clean, after)
            if close < 0:
                continue
            params = self.nc[after + 1:close]
            brace = self.clean.find('{', close)
            end = match_brace(self.clean, brace) if brace >= 0 else -1
            body = self.nc[brace + 1:end] if end > 0 else ''
            self.handlers.append(Handler(
                m.group(1).upper(), (prefix + rel) if rel else prefix,
                request_body_type(params), query_param_names(params), params, body,
                line_of(self.raw, m.start())))

    @staticmethod
    def _skip_annotations(text, pos):
        """跳过 `@RequirePermission("x")` 这类注解，落到**方法声明的参数表**左括号。

        顺序是：@映射注解 → 若干其它注解 → `public 返回类型 方法名` → `(`。
        所以遇到非 @ 时要继续往前扫到第一个 `(`；泛型返回类型里的 `<...>` 不含括号，
        方法名后面那个 `(` 才是参数表。
        """
        n = len(text)
        while pos < n:
            while pos < n and text[pos] in ' \t\n\r':
                pos += 1
            if pos >= n:
                return -1
            if text[pos] == '(':
                return pos
            if text[pos] == '@':
                m = re.match(r'@\w+', text[pos:])
                if not m:
                    return -1
                pos += m.end()
                while pos < n and text[pos] in ' \t\n\r':
                    pos += 1
                if pos < n and text[pos] == '(':
                    close = match_paren(text, pos)
                    if close < 0:
                        return -1
                    pos = close + 1
                continue
            nxt = text.find('(', pos)
            return nxt if nxt >= 0 else -1
        return -1


def annotation_args(clean, nc, match):
    """注解后紧跟的 `(…)` 的区间（在两份文本里下标一致）。"""
    k = match.end()
    while k < len(clean) and clean[k] in ' \t\n\r':
        k += 1
    if k >= len(clean) or clean[k] != '(':
        return None
    close = match_paren(clean, k)
    return (k + 1, close) if close > 0 else None


def request_body_type(params):
    """`@Valid @RequestBody CreateRequest req` → `CreateRequest`。

    `@RequestBody(required = false) DecisionRequest body` 也要认：
    注解自身的参数表 `(required = false)` 会挡在类型声明前面，不剥掉就匹配失败，
    该操作的请求体于是静默"没比"（`ApprovalController` 三个决策端点就是这样漏的）。
    """
    m = re.search(r'@RequestBody\b', params)
    if not m:
        return None
    seg = params[m.end():]
    if seg.lstrip().startswith('('):
        close = match_paren(seg, seg.index('('))
        if close > 0:
            seg = seg[close + 1:]
    cut = len(seg)
    for sep in (',',):
        p = seg.find(sep)
        if p >= 0:
            cut = min(cut, p)
    decl = ' '.join(ANN_RE.sub(' ', seg[:cut]).split())
    t = re.match(r'^([A-Z]\w*)(?:\s*<.*>)?\s+[a-z_]\w*$', decl)
    return t.group(1) if t else None


def query_param_names(params):
    """`@RequestParam` 的对外参数名：显式 name/value 优先，否则取形参名。"""
    out = []
    for seg in split_top_level(params):
        if not re.search(r'@RequestParam\b', seg):
            continue
        ann = re.search(r'@RequestParam\s*(\((?:[^()]|\([^()]*\))*\))?', seg)
        explicit = None
        if ann and ann.group(1):
            inner = ann.group(1)[1:-1]
            named = re.search(r'\b(?:name|value)\s*=\s*"([^"]+)"', inner)
            lit = re.search(r'^\s*"([^"]+)"', inner)
            explicit = named.group(1) if named else (lit.group(1) if lit else None)
        decl = ' '.join(ANN_RE.sub(' ', seg).split())
        parts = decl.replace(',', ' ').split()
        if parts:
            out.append(explicit or parts[-1])
    return out


TYPE_BEFORE_VAR = r'\b([A-Z]\w*)(?:<[^>]*>)?\s+%s\b'


def resolve_type(scope, expr):
    """在一段方法体里找 `expr` 这个变量的声明类型（含 `for (Type expr : …)`）。

    只认「类型 变量名」这种局部声明；推导不出来就返回 None，由调用方降级处理，
    绝不瞎猜一个重载 —— 猜错的代价是造出成片假红。
    """
    if not re.fullmatch(r'\w+', expr or ''):
        return None
    m = TYPE_BEFORE_VAR % re.escape(expr)
    found = re.findall(m, scope)
    return found[0] if found else None


class Val:
    """一个响应载荷的静态形状。

    - `kind`  ：`obj`（Map 载荷）或 `list`（数组载荷）
    - `shape` ：已识别的键路径集合
    - `blind` ：**认得出来但静态看不到字段**的子树前缀（如 `items[].`）——
      元素形状由 service 层决定时用它把"没比"和"比过且对得上"分开，
      这些前缀下的字段既不报红也不冒充通过，而是计入「未比对缺口」。
    """
    __slots__ = ('kind', 'shape', 'blind')

    def __init__(self, kind, shape, blind=None):
        self.kind = kind
        self.shape = shape
        self.blind = set(blind or ())

    def __repr__(self):
        return f'Val({self.kind},{sorted(self.shape)},{sorted(self.blind)})'


class Builder:
    """一个 `Map<String,Object> toMap(X x)` 构造器：参数类型 + 方法体 + 返回变量名。"""
    __slots__ = ('ptype', 'body', 'root')

    def __init__(self, ptype, body, root):
        self.ptype = ptype
        self.body = body
        self.root = root


def unwrap_call(expr):
    """`a.b(...)` / `ResponseEntity.status(X).body(...)` → (去限定名的函数名, 顶层实参)。"""
    e = expr.strip()
    if not e.endswith(')'):
        return None
    depth = 0
    for i in range(len(e) - 1, -1, -1):
        if e[i] == ')':
            depth += 1
        elif e[i] == '(':
            depth -= 1
            if depth == 0:
                name = e[:i].strip()
                if ')' in name:            # 链式调用：只留最后一段 `.body`
                    name = name.rsplit(')', 1)[-1].strip()
                name = name.lstrip('.')
                if not name or not re.fullmatch(r'[\w.$:<>,\[\] ]+', name):
                    return None
                return name, split_top_level(e[i + 1:-1])
    return None


def short_name(name):
    """`com.erp.shared.web.PageResult<>` → `PageResult`；`A.b` → (`A`, `b`)。

    `::` 的判定必须在 `.` 之前：`ReportController::toMap` 里既有 `::` 也有 `.`，
    反过来切会把 `ReportController:` 当成限定名、把 `:toMap` 当成方法名。
    """
    n = re.sub(r'<.*', '', name).strip()
    if '::' in n:
        qual, _, last = n.rpartition('::')
        return qual, last
    if '.' in n:
        qual, _, last = n.rpartition('.')
        return qual, last
    return '', n


METHOD_REF_RE = re.compile(
    r'\.stream\(\)\s*\.map\(\s*([\w.$]+)::(\w+)\s*\)\s*\.toList\(\)\s*$')
PAGE_KEYS = {'items', 'total', 'page', 'size'}


def _split_hints(raw):
    """把 `schema 名 \x00 元素名` 这种打包串拆回元组（EvalCtx 往下传线索用）。"""
    return tuple(x for x in (raw or '').split('\x00') if x)


def _type_qualified(qual):
    """限定名的**最后一段**首字母大写 —— 说明它是类/接口（静态方法），不是服务实例。"""
    return bool(qual) and qual.rsplit('.', 1)[-1][:1].isupper()


class EvalCtx:
    """一段方法体里的变量形状表 + 求值器（只为「响应载荷长什么样」这一个问题服务）。"""

    def __init__(self, impl, src, scope, depth=0, seen=(), schema_name=''):
        self.impl = impl
        self.src = src
        self.scope = scope
        self.depth = depth
        self.seen = seen
        # 契约给的**类型线索**：data 的 schema 名 + 数组元素的 schema 名。
        # 它们只用于同名重载（`toMap(ReportRow)` vs `toMap(InventorySummaryRow)`）消歧。
        self.hints = tuple(h for h in (_split_hints(schema_name)) if h)
        self.schema_name = self.hints[0] if self.hints else ''
        self.vars = {}

    # ── 变量与语句 ────────────────────────────────────────────────────
    def scan(self, body):
        events = []
        for m in re.finditer(r'(?:Map<String,\s*Object>|List<Map<String,\s*Object>>)\s+'
                             r'(\w+)\s*=\s*([^;]+);', body):
            events.append((m.start(), 'decl', m.group(1), m.group(2)))
        for m in re.finditer(r'\bvar\s+(\w+)\s*=\s*([^;]+);', body):
            events.append((m.start(), 'decl', m.group(1), m.group(2)))
        for m in re.finditer(r'\b(\w+)\.put\(\s*"([^"]*)"\s*,\s*([^;]*?)\)\s*;', body):
            events.append((m.start(), 'put', m.group(1), (m.group(2), m.group(3))))
        for m in re.finditer(r'\b(\w+)\.putAll\(\s*([^;]*?)\)\s*;', body):
            events.append((m.start(), 'putall', m.group(1), m.group(2)))
        for m in re.finditer(r'\b(\w+)\.add\(\s*([^;]*?)\)\s*;', body):
            events.append((m.start(), 'add', m.group(1), m.group(2)))
        for pos, kind, name, arg in sorted(events, key=lambda e: e[0]):
            if kind == 'decl':
                val = self.decl_val(arg)
                if val is not None:
                    self.vars[name] = val
            elif kind == 'put':
                key, val_expr = arg
                target = self.vars.get(name)
                if target is None or target.kind != 'obj':
                    target = self.vars[name] = Val('obj', set())
                target.shape.add(key)
                val = self.val_of(val_expr)
                if val is not None:
                    link = f'{key}.' if val.kind == 'obj' else f'{key}[].'
                    target.shape |= {link + p for p in val.shape}
                    target.blind |= {link.rstrip('.') + p for p in val.blind}
                elif re.fullmatch(r'\w+', val_expr.strip()):
                    # `data.put("items", items)`：items 是就地循环填的 List<Map>，
                    # 循环体里的 put 已经记在 `items` 名下（kind=list）。
                    # 元素字段不在这里接上，整块 items 就会"看不见"（假绿）。
                    elems = self.vars.get(val_expr.strip())
                    if elems is not None and elems.kind == 'list':
                        target.shape |= {f'{key}[].{p}' for p in elems.shape}
                        target.blind |= {f'{key}[]' + p for p in elems.blind}
            elif kind == 'putall':
                target = self.vars.get(name)
                val = self.val_of(arg)
                if target is not None and target.kind == 'obj' and val is not None \
                        and val.kind == 'obj':
                    target.shape |= val.shape
            elif kind == 'add':
                target = self.vars.get(name)
                if target is None or target.kind != 'list':
                    target = self.vars[name] = Val('list', set())
                val = self.val_of(arg)
                if val is not None and val.kind == 'obj':
                    target.shape |= val.shape
        return self

    def decl_val(self, expr):
        e = expr.strip()
        if re.match(r'new\s+LinkedHashMap\s*<>?\s*\(', e):
            inner = e[e.index('(') + 1:e.rindex(')')] if '(' in e else ''
            val = self.val_of(inner) if inner.strip() else None
            return Val('obj', set(val.shape) if val is not None and val.kind == 'obj'
                       else set())
        if re.match(r'new\s+(ArrayList|LinkedHashSet|HashSet)\s*<>?\s*\(', e):
            return Val('list', set())
        return self.val_of(e)

    # ── 表达式求值 ────────────────────────────────────────────────────
    def val_of(self, expr, depth=None):
        depth = self.depth if depth is None else depth
        e = (expr or '').strip()
        if depth > MAX_DEPTH or not e:
            return None
        if re.fullmatch(r'\w+', e):
            return self.vars.get(e)
        m = METHOD_REF_RE.search(e)
        if m:
            return self.stream_val(m.group(1), m.group(2))
        call = unwrap_call(e)
        if call:
            name, args = call
            if '::' in name:
                qual, last = short_name(name)
                return self.builder_val(qual, last, None)
            qual, last = short_name(name)
            # 「大写限定名 = 类型名」：ApiResponses.ok / PageResult / ReportController.items
            # 才是信封与包装；`suppliers.page(...)` 这种小写限定名是**服务方法**，原样当载荷。
            # 不带限定名的裸调用（`ok(data)`、InventoryController 自己的私有别名）也算包装。
            wrap = (not qual) or _type_qualified(qual)
            if wrap and last in ('ok', 'body'):     # 响应信封，载荷是它的实参
                return self.val_of(args[0], depth + 1) if args else None
            if wrap and (last in ('page', 'pageOf') or last == 'PageResult'):
                return self.page_val(args, depth)
            if wrap and last == 'items' and len(args) == 1:   # ReportController.items(List)
                elems = self.val_of(args[0], depth + 1)
                if elems is None or elems.kind != 'list' or not elems.shape:
                    return None
                shape = {'items'}
                shape |= {'items[].' + p for p in elems.shape}
                blind = {'items[]' + p for p in elems.blind}
                return Val('obj', shape, blind)
            if last == 'of' and name.endswith('Map.of'):
                return self.map_of_val(args, depth)
            if last in self.src.builders or last in self.impl.builder_owners:
                return self.builder_val(qual, last, args[0] if args else None, depth)
            if wrap and len(args) == 1:            # 未知包装函数：剥一层继续
                return self.val_of(args[0], depth + 1)
        if re.match(r'new\s+\w*PageResult\s*<>?\s*\(', e):
            inner = e[e.index('(') + 1:e.rindex(')')]
            return self.page_val(split_top_level(inner), depth)
        return None

    def map_of_val(self, args, depth):
        if len(args) % 2:
            return None
        shape, blind = set(), set()
        for i in range(0, len(args), 2):
            key = re.match(r'\s*"([^"]*)"', args[i])
            if not key:
                return None
            k = key.group(1)
            shape.add(k)
            val = self.val_of(args[i + 1], depth + 1)
            if val is not None:
                link = f'{k}.' if val.kind == 'obj' else f'{k}[].'
                shape |= {link + p for p in val.shape}
                blind |= {link.rstrip('.') + p for p in val.blind}
        return Val('obj', shape, blind)

    def page_val(self, args, depth):
        """分页信封：`data.items/total/page/size`。

        契约里分页 `items` 多半是裸 `{}`（`ApiPageResponse.data.items`），
        所以元素字段**不进比对**（`blind` 兜住，计入「未比对缺口」）；
        但元素形状能静态取到时仍会展开，用来多抓一层漂移。

        `ApiResponses.page(svc.page(...))` 的元素类型取服务方法返回值的
        `PageResult<T>`（`Controller._parse_page_methods`）—— 只看 Controller 里的
        Map 构造器是取不到它的。
        """
        if not args:
            return None
        first = args[0].strip()
        elems = None
        if re.match(r'new\s+\w*PageResult\s*<>?\s*\(', first):
            inner = first[first.index('(') + 1:first.rindex(')')]
            elems = self.val_of(split_top_level(inner)[0], depth + 1)
        else:
            elems = self.val_of(first, depth + 1)
        shape, blind = set(PAGE_KEYS), set()
        if elems is not None and elems.shape:
            shape |= {'items[].' + p for p in elems.shape}
            blind |= {'items[]' + p for p in elems.blind}
        else:
            blind.add('items[]')      # 元素形状不可得：items 下所有字段都算未比对
        return Val('obj', shape, blind)

    def stream_val(self, qual, method):
        """`xs.stream().map(C::toMap).toList()` → 元素形状是一张 Map，本值是一个**数组**。

        这里**必须**把契约 schema 名当消歧依据：`ReportController` 的
        `toMap(ReportRow)` 与 `toMap(InventorySummaryRow)` 同名且都只有一个参数类型、
        都不在当前方法体里出现变量声明，只靠限定名会解析成 `AMBIGUOUS` ——
        而契约在 `/reports/purchase` 里写的元素类型本来就是 `ReportRow`。
        """
        elems = self.builder_val(qual, method, None)
        if elems is None:
            for hint in self.hints[1:] + self.hints[:1]:
                elems = self.impl.schema_named_builder(qual, method, hint)
                if elems is not None:
                    break
        return Val('list', set(elems.shape), elems.blind) if elems is not None else None

    def builder_val(self, qual, method, arg_expr, depth=0):
        if depth > MAX_DEPTH:
            return None
        want = resolve_type(self.scope, (arg_expr or '').strip()) \
            if arg_expr and re.fullmatch(r'\w+', arg_expr.strip()) else None
        src, b, seen = self.impl.resolve_builder(qual, method, want, self.src, self.seen,
                                                 self.hints)
        if src is None or b is None or b is AMBIGUOUS:
            return None
        key = (str(src.path), method, b.ptype)
        cache = self.impl.builder_shapes
        if key not in cache:
            cache[key] = None       # 占位，防递归
            sub = EvalCtx(self.impl, src, b.body, depth + 1,
                          self.seen + (key,), '\x00'.join(self.hints)).scan(b.body)
            root = sub.vars.get(b.root)
            cache[key] = root if root is not None and root.kind == 'obj' else None
        got = cache[key]
        return Val(got.kind, set(got.shape), set(got.blind)) if got is not None else None


def impl_response_paths(handler, src, schema_name, item_name=''):
    """响应侧的实现事实源 = handler 交出去的那块载荷的形状（已剥掉 `{code,message,data}` 信封）。

    返回 (形状 | None, 说明)。形状是 Val；None 表示「静态识别的载荷形状不可得」——
    调用方必须把它记成**未比对缺口**，而不是当成"零字段通过"。
    """
    scope = handler.params + '\n' + handler.body
    ctx = EvalCtx(handler.impl, src, scope,
                  schema_name='\x00'.join(x for x in (schema_name, item_name) if x)
                  ).scan(handler.body)
    rets = re.findall(r'\breturn\s+([^;]+);', handler.body)
    if not rets:
        return None, '方法体里没有 return'
    if len(rets) > 1:
        return None, f'方法体有 {len(rets)} 个 return，静态形状不唯一'
    val = ctx.val_of(rets[0])
    if val is None:
        return None, '返回值不是可静态识别的 Map 构造'
    if val.kind == 'list':
        return None, '载荷是数组，元素形状不可由 Map 构造还原'
    if not val.shape and not val.blind:
        return None, '载荷 Map 一个键都没识别出来'
    return val, ''


class Handler:
    __slots__ = ('http', 'path', 'body_type', 'query_params', 'params', 'body', 'line',
                 'impl')

    def __init__(self, http, path, body_type, query_params, params, body, line):
        self.http = http
        self.path = path
        self.body_type = body_type
        self.query_params = query_params
        self.params = params
        self.body = body
        self.line = line
        self.impl = None


def normalize_path(path):
    """`/{id}`、`/{skuId}` 归一为 `/{}` —— 只比结构，与 ApiContractMappingIT 同规则。"""
    normalized = re.sub(r'\{[^}]*}', '{}', path or '')
    if len(normalized) > 1 and normalized.endswith('/'):
        normalized = normalized[:-1]
    return normalized


class Impl:
    """全仓 record / handler 的联合视图，record 解析带文件作用域优先级。"""

    def __init__(self):
        self.sources = []
        self.record_owners = {}
        self.handlers = {}
        self.by_file = {}
        self.builder_owners = {}
        self.builder_shapes = {}
        if not SRC_ROOT.exists():
            raise SystemExit(f'{NG} 找不到后端源码目录：{SRC_ROOT}')
        for f in sorted(SRC_ROOT.rglob('*.java')):
            if any(part in ('build', 'out', 'target') for part in f.parts):
                continue
            src = JavaSource(f)
            self.sources.append(src)
            self.by_file[str(f.relative_to(SRC_ROOT))] = src
            for name in src.records:
                self.record_owners.setdefault(name, []).append(src)
            for name in src.builders:
                self.builder_owners.setdefault(name, []).append(src)
            for h in src.handlers:
                h.impl = self
                self.handlers[f'{h.http} {normalize_path(h.path)}'] = (h, src)

    def record_fields(self, name, owner):
        if owner is not None and name in owner.records:
            return owner.records[name]
        cands = self.record_owners.get(name) or []
        if len(cands) == 1:
            return cands[0].records[name]
        if len(cands) > 1:
            return AMBIGUOUS
        return None

    def resolve_builder(self, qual, method, want_type, owner, seen=(), hints=()):
        """找 `Map<String,Object> method(X x)` 的定义。

        消歧顺序：限定类名 → 文件作用域 → 契约 schema 名的类型 → 全局唯一。
        最后那条是**契约参与消歧**，用于同名重载（`ApprovalController` 的
        `toMap(ApprovalInstance)` / `toMap(ApprovalStep)`）：契约那边写的 schema 名
        本来就是被比对的那一个，拿它当第三判据只减少误判，不会掩盖漂移。

        ★ 限定名与当前文件不是同一个类时**不能**用文件作用域：
          `SalesQuoteController.convert` 里的 `SalesOrderController.toMap(order)` 与它自己的
          `toMap(SalesQuote)` 同名，第一版按文件作用域拿到后者，于是 17 条字段整体错位。
        """
        cls = qual.rsplit('.', 1)[-1] if qual else ''
        if cls and cls != owner.path.stem:
            pool = []
            for src in self.builder_owners.get(method) or []:
                if src.path.stem == cls:
                    pool = [(src, b) for b in src.builders[method]]
                    break
            if pool:
                return pool[0][0], self._pick([b for _, b in pool], want_type, seen,
                                              hints), seen
        if method in owner.builders:
            cands = owner.builders[method]
            return owner, self._pick(cands, want_type, seen, hints), seen
        pool = []
        for src in self.builder_owners.get(method) or []:
            if qual and src.path.stem == qual.rsplit('.', 1)[-1]:
                pool = [(src, b) for b in src.builders[method]]
                break
        if not pool:
            for src in self.builder_owners.get(method) or []:
                pool += [(src, b) for b in src.builders[method]]
            names = {str(s.path) for s, _ in pool}
            if len(names) > 1:
                return None, AMBIGUOUS, seen
        if not pool:
            return None, None, seen
        src = pool[0][0]
        return src, self._pick([b for _, b in pool], want_type, seen, hints), seen

    @staticmethod
    def _pick(cands, want_type, seen, hints=()):
        chosen = [b for b in cands if want_type and b.ptype == want_type]
        for hint in hints:
            if chosen:
                break
            chosen = [b for b in cands if b.ptype == hint]
        if not chosen and len(cands) == 1:
            chosen = cands
        if not chosen:
            return AMBIGUOUS
        live = [b for b in chosen if (id(b),) not in seen]
        return (live or chosen)[0]

    def schema_named_builder(self, qual, method, schema_name):
        """按**契约 schema 名**直接取构造器形状（`::toMap` 的重载消歧最后一道）。"""
        if not schema_name:
            return None
        cands = []
        for src in self.builder_owners.get(method) or []:
            if qual and src.path.stem != qual.rsplit('.', 1)[-1]:
                continue
            cands += [(src, b) for b in src.builders[method]]
        hit = [(src, b) for src, b in cands if b.ptype == schema_name]
        if len(hit) != 1:
            return None
        src, b = hit[0]
        key = (str(src.path), method, b.ptype)
        if key not in self.builder_shapes:
            self.builder_shapes[key] = None
            sub = EvalCtx(self, src, b.body, 0).scan(b.body)
            root = sub.vars.get(b.root)
            self.builder_shapes[key] = root if root is not None and root.kind == 'obj' else None
        got = self.builder_shapes[key]
        return Val(got.kind, set(got.shape), set(got.blind)) if got is not None else None

    def handler(self, method, contract_path, base):
        """契约 path 不带 base（前缀在 servers 里），实现带 —— 拼回去再查。"""
        key = f'{method} {normalize_path(base + contract_path)}'
        got = self.handlers.get(key)
        if got:
            return got
        return self.handlers.get(f'{method} {normalize_path(contract_path)}')


# ══════════════════════════════════════════════════════════════════════
# 2. 契约侧：$ref 解引用 / allOf 合并 / 字段树
# ══════════════════════════════════════════════════════════════════════

def lookup(spec, ref):
    cur = spec
    for part in ref[2:].split('/'):
        part = part.replace('~1', '/').replace('~0', '~')
        if isinstance(cur, dict) and part in cur:
            cur = cur[part]
        else:
            return None
    return cur


def deref(spec, node, depth=0):
    while isinstance(node, dict) and '$ref' in node:
        if depth > MAX_DEPTH:
            return None
        target = lookup(spec, node['$ref']) if str(node['$ref']).startswith('#/') else None
        if target is None:
            return None
        node = target
        depth += 1
    return node


def merge_props(dst, src):
    """allOf 合并同名属性：两边都是对象时**递归并集**，而不是后者覆盖前者。

    契约里 `allOf: [ApiPageResponse, {properties: {data: {properties: {items: [...]}}}}]`
    这种写法很常见（分页信封 + 元素类型细化）。按 JSON Schema 语义两条约束**同时生效**，
    所以 data 的有效字段是并集；早年这里直接 `props.update`，后一个 data 把前一个整个盖掉，
    于是 total/page/size 被误判成"实现多返回了字段"。
    """
    for k, v in src.items():
        if k not in dst:
            dst[k] = v
            continue
        a, b = dst[k], v
        if isinstance(a, dict) and isinstance(b, dict):
            merged = dict(a)
            merged.update(b)
            if isinstance(a.get('properties'), dict) and isinstance(b.get('properties'), dict):
                props = dict(a['properties'])
                merge_props(props, b['properties'])
                merged['properties'] = props
            req = list(a.get('required') or [])
            req += [x for x in (b.get('required') or []) if x not in req]
            if req:
                merged['required'] = req
            dst[k] = merged
        else:
            dst[k] = v


def flatten(spec, node, depth=0):
    """跟着 $ref 走、把 allOf 的 properties/required 并起来（同名属性递归并集）。"""
    if depth > MAX_DEPTH:
        return {}
    node = deref(spec, node, depth)
    if not isinstance(node, dict):
        return {}
    if 'allOf' not in node:
        return node
    props, required, merged = {}, [], {}
    for sub in node['allOf'] or []:
        f = flatten(spec, sub, depth + 1)
        merge_props(props, f.get('properties') or {})
        required += f.get('required') or []
        for k in ('type', 'minimum', 'maximum', 'minLength', 'maxLength',
                  'minItems', 'maxItems', 'items', 'enum', 'format'):
            if k in f and k not in merged:
                merged[k] = f[k]
    merge_props(props, node.get('properties') or {})
    required += node.get('required') or []
    merged.update(node)
    merged['properties'] = props
    merged['required'] = required
    merged.pop('allOf', None)
    return merged


def contract_tree(spec, schema, out=None, prefix='', depth=0, blind=None):
    """契约 schema → {path: Field}，嵌套对象用 `a.b`、数组元素用 `a[]`。

    `blind` 收集**契约自己没写清**的子树前缀（如 `items[]`）：
    数组元素写成裸 `{}`（`ApiPageResponse.data.items` 就是这样）时，
    元素字段在契约里根本不存在可比对象 —— 不能拿它反推实现"多了字段"。
    """
    out = {} if out is None else out
    blind = set() if blind is None else blind
    if depth > MAX_DEPTH:
        return out
    node = flatten(spec, schema, depth)
    props = node.get('properties') or {}
    required = set(node.get('required') or [])
    for name, raw_sub in props.items():
        sub = flatten(spec, raw_sub, depth + 1)
        path = prefix + name
        out[path] = Field(name,
                          java_type=sub.get('type') or '',
                          minimum=sub.get('minimum'),
                          maximum=sub.get('maximum'),
                          min_length=sub.get('minLength'),
                          max_length=sub.get('maxLength'),
                          required=name in required,
                          not_empty_list=(sub.get('minItems') or 0) >= 1,
                          source='openapi.yaml')
        if sub.get('properties'):
            contract_tree(spec, sub, out, path + '.', depth + 1, blind)
        items = sub.get('items')
        if isinstance(items, dict):
            it = flatten(spec, items, depth + 1)
            if it.get('properties'):
                # 元素路径 = `lines[].skuId`，与 paths_from_body 的写法逐字一致
                contract_tree(spec, it, out, path + '[].', depth + 1, blind)
            elif sub.get('type') == 'array' or items == {} or not it:
                blind.add(f'{path}[]')      # 元素是裸 `{}`：契约没写字段
    return out


def dto_tree(spec_unused, impl, fields, owner, out=None, prefix='', depth=0, seen=()):
    """record 组件 → {path: Field}，与 contract_tree 同一种路径写法。"""
    out = {} if out is None else out
    if depth > MAX_DEPTH:
        return out
    for f in fields:
        path = prefix + f.name
        out[path] = f
        name, is_collection = nested_record_name(f.java_type)
        if not name:
            continue
        key = (id(owner), name)
        if key in seen:
            continue
        sub = impl.record_fields(name, owner)
        if sub is None or sub is AMBIGUOUS:
            continue
        link = f'{path}[].' if is_collection else f'{path}.'
        sub_owner = owner if name in (owner.records if owner else {}) else None
        dto_tree(None, impl, sub, sub_owner, out, link, depth + 1, seen + (key,))
    return out


# ══════════════════════════════════════════════════════════════════════
# 3. 五条检查
# ══════════════════════════════════════════════════════════════════════

def check_refs(spec):
    """① 契约图内部：每个 $ref 都必须解得开（M 这类当场变红）。"""
    errors = []

    def walk(node, where):
        if isinstance(node, dict):
            ref = node.get('$ref')
            if isinstance(ref, str):
                if not ref.startswith('#/'):
                    errors.append(f'{where}: 外部 $ref {ref} —— 本契约承诺单文件自洽')
                elif lookup(spec, ref) is None:
                    errors.append(f'{where}: 悬空 $ref {ref} —— 被引用但契约里没有这个定义')
            for k, v in node.items():
                if k == '$ref':
                    continue
                walk(v, f'{where}.{k}' if k != '$ref' else where)
        elif isinstance(node, list):
            for i, v in enumerate(node):
                walk(v, f'{where}[{i}]')

    for section in ('paths', 'components'):
        if section in spec:
            walk(spec[section], section)
    return errors


def _fmt(v):
    return '（未声明）' if v is None else str(v)


def compare_fields(where, impl_fields, contract_fields, errors, *, check_required):
    """字段名双向核对；两侧都是 Field，边界与必填一并比。"""
    for path, f in sorted(impl_fields.items()):
        c = contract_fields.get(path)
        if c is None:
            errors.append(f'{where}: 实现有 `{path}`（{f.java_type} {f.source}），'
                          f'契约 schema 里没有 —— 照契约写的前端看不见它')
            continue
        # ③ 边界：契约不得宽于实现
        if f.minimum is not None:
            if c.minimum is None or c.minimum < f.minimum:
                errors.append(f'{where}: `{path}` 契约 minimum={_fmt(c.minimum)} 宽于实现 '
                              f'{f.source or "@Min"} 的 minimum={f.minimum}')
        if f.maximum is not None:
            if c.maximum is None or c.maximum > f.maximum:
                errors.append(f'{where}: `{path}` 契约 maximum={_fmt(c.maximum)} 宽于实现 '
                              f'{f.source or "@Max"} 的 maximum={f.maximum}')
        if f.min_length is not None:
            if c.min_length is None or c.min_length < f.min_length:
                errors.append(f'{where}: `{path}` 契约 minLength={_fmt(c.min_length)} 宽于'
                              f'实现 @Size(min={f.min_length})')
        if f.max_length is not None:
            if c.max_length is None or c.max_length > f.max_length:
                errors.append(f'{where}: `{path}` 契约 maxLength={_fmt(c.max_length)} 宽于'
                              f'实现 @Size(max={f.max_length})')
        if f.not_empty_list and not c.not_empty_list:
            errors.append(f'{where}: `{path}` 实现 @NotEmpty 要求非空数组，'
                          f'契约没有 minItems>=1 —— 照契约发空数组会 400')
        if check_required:
            if f.required and not c.required:
                errors.append(f'{where}: `{path}` 实现标了 '
                              f'{f.source or "@NotNull"}，契约未列为 required')
            elif c.required and not f.required:
                errors.append(f'{where}: 契约把 `{path}` 列为 required，'
                              f'实现（{f.java_type} {f.source or "无注解"}）并不强制')

    for path, c in sorted(contract_fields.items()):
        if path not in impl_fields:
            errors.append(f'{where}: 契约声明了 `{path}`，实现的 DTO 没有这个字段 '
                          f'—— 契约承诺了实现不接受的东西')


def check_contract(spec, impl):
    errors = []
    stats = {'request': 0, 'response': 0, 'query': 0}
    gaps = []
    base = ''
    try:
        url = (spec.get('servers') or [{}])[0].get('url', '')
        base = re.sub(r'^(https?://[^/]+)?', '', url).rstrip('/')
    except Exception:
        pass

    for path, item in (spec.get('paths') or {}).items():
        for method, op in (item or {}).items():
            if method.lower() not in HTTP_METHODS or not isinstance(op, dict):
                continue
            method = method.upper()
            where = f'{method} {path}'
            got = impl.handler(method, path, base)
            if not got:
                gaps.append(f'{where}：找不到对应的 Controller 方法'
                            f'（操作级缺口由 ApiContractMappingIT 负责，这里只报覆盖不到）')
                continue
            handler, src = got
            loc = f'{src.path.name}:{handler.line}'

            # ② 请求体字段 ⇔ record 组件
            rb = (op.get('requestBody') or {})
            schema = None
            for media in (rb.get('content') or {}).values():
                schema = media.get('schema')
                break
            if schema is not None:
                fields = request_body_type_of(impl, handler, src)
                if fields is None:
                    gaps.append(f'{where}：@RequestBody 类型 {handler.body_type} '
                                f'没解析成 record（{loc}）—— 字段没比')
                elif fields is AMBIGUOUS:
                    gaps.append(f'{where}：@RequestBody 类型 {handler.body_type} '
                                f'在多个文件里重名（{loc}）—— 不敢猜，没比')
                else:
                    cf = contract_tree(spec, schema)
                    owner = src
                    dt = dto_tree(None, impl, fields, owner)
                    compare_fields(f'{where} ← {handler.body_type}（{loc}）',
                                   dt, cf, errors, check_required=True)
                    stats['request'] += 1

            # ④ 响应 data ⇔ Map 构造器（只对「契约已定型」的 data 生效）
            typed = typed_data_schemas(spec, op)
            if typed:
                shape, why = impl_response_paths(handler, src, typed[0][0], typed[0][3])
                if shape is None:
                    gaps.append(f'{where}：契约把 data 定成了 {typed[0][0]}，'
                                f'但静态形状不可得（{why}）—— 字段没比（{loc}）')
                else:
                    for schema_name, contract_paths, contract_blind, item_name in typed:
                        compare_paths(f'{where} → {schema_name}（{loc}）',
                                      shape, contract_paths, errors, gaps,
                                      contract_blind | shape.blind)
                    stats['response'] += 1
                    if shape.blind:
                        gaps.append(f'{where} → {typed[0][0]}（{loc}）：'
                                    f'{len(shape.blind)} 处子树静态不可见'
                                    f'（{", ".join(sorted(shape.blind))}）—— 其下字段没比')

            # ⑤ query 参数 ⇔ @RequestParam
            declared = declared_query_names(spec, op)
            if declared is not None:
                impl_names = set(handler.query_params)
                missing = sorted(impl_names - declared)
                extra = sorted(declared - impl_names)
                for n in missing:
                    errors.append(f'{where}: 实现接受 query 参数 `{n}`（{loc}），'
                                  f'契约未声明 —— 前端照契约传不到')
                for n in extra:
                    errors.append(f'{where}: 契约声明了 query 参数 `{n}`，'
                                  f'实现的签名里没有（{loc}）')
                stats['query'] += 1

    return errors, stats, gaps


def request_body_type_of(impl, handler, src):
    if not handler.body_type:
        return None
    return impl.record_fields(handler.body_type, src)


def typed_data_schemas(spec, op):
    """该操作里所有「data 已定型」的 2xx 响应：[(schema 名, 契约字段路径集合)]。

    `ApiResponse.data` 是裸的 `{}`（未定型）→ 不进比对，也不算缺口：
    契约本来就允许"信封套任意载荷"，硬比会造出一堆假红。

    元素是裸 `{}` 的数组（如 `ApiPageResponse.data.items`）同样**只比到 `items` 这一层**：
    元素字段契约没写，就不能拿它判实现"多了字段"。
    """
    out = []
    for status, resp in (op.get('responses') or {}).items():
        if not str(status).startswith('2'):
            continue
        r = flatten(spec, resp)
        for media in (r.get('content') or {}).values():
            node = flatten(spec, media.get('schema'))
            data = (node.get('properties') or {}).get('data')
            if not isinstance(data, dict):
                continue
            d = flatten(spec, data)
            if not d.get('properties'):
                continue
            name = str(data.get('$ref') or '').rsplit('/', 1)[-1] or f'{status} 内联 data'
            blind = set()
            tree = contract_tree(spec, d, blind=blind)
            out.append((name, set(tree), blind, item_schema_name(spec, d)))
    return out


def item_schema_name(spec, data_node):
    """`data.items` 若指向具名 schema（`$ref`），返回该 schema 名 —— 供 `::toMap` 重载消歧。"""
    items = ((flatten(spec, data_node).get('properties') or {}).get('items'))
    if not isinstance(items, dict):
        return ''
    elem = items.get('items')          # `items: {type: array, items: {$ref: …}}` 里层才是元素
    if not isinstance(elem, dict):
        return ''
    ref = elem.get('$ref') or (flatten(spec, elem).get('$ref'))
    if isinstance(ref, str) and ref.startswith('#/components/schemas/'):
        return ref.rsplit('/', 1)[-1]
    return ''


def declared_query_names(spec, op):
    """契约声明的 query 参数名集合；操作没写 parameters 时返回空集（不是 None）。"""
    raw = op.get('parameters')
    if raw is None:
        return set()
    names = set()
    for p in raw:
        node = flatten(spec, p)
        if node.get('in') == 'query' and node.get('name'):
            names.add(node['name'])
    return names


def compare_paths(where, shape, contract_paths, errors, gaps, blind):
    """响应侧只做**字段名**双向核对：Map 的 put 键没有类型/边界信息可比。

    `blind` 里的子树（如 `items[]`）**静态看不到元素字段**（实现侧是 service 层拼的，
    或契约侧元素写成裸 `{}`）：既不许报红，也不许当成对得上 —— 跳过并计入 gaps。
    """
    impl_paths = shape.shape
    for path in sorted(impl_paths - contract_paths):
        if any(path.startswith(b) for b in blind):
            continue
        errors.append(f'{where}: 实现返回 `{path}`，契约 schema 里没有 —— '
                      f'照契约写的前端看不见它')
    for path in sorted(contract_paths - impl_paths):
        if any(path.startswith(b) for b in blind):
            continue
        errors.append(f'{where}: 契约声明了 `{path}`，实现的响应里没有 —— '
                      f'前端照契约取值得到 undefined')


# ══════════════════════════════════════════════════════════════════════
# 4. --selftest：注入漂移，证明门禁不是永绿的空校验器
# ══════════════════════════════════════════════════════════════════════

def selftest(spec, impl):
    """在契约的内存副本上造四类漂移，逐一断言变红。

    ★ 取材全部来自**当前真实干净**的 (schema, 字段) 对：
      哪天覆盖塌了、或解析器退化成"什么都不比"，这里会因为找不到注入点而直接失败，
      而不是"注入成功但断言了个寂寞"。这就是"不许写永绿的空校验器"的机器化说法。

    第四类是 V2-C3 新增的**反向漂移**：把实现要多传的字段从契约里删掉之外，
    还要证明「契约声明了一个实现根本没有的字段」也会红（V2-C2 §7.1-B 的教训：
    契约承诺了实现做不到的事，比少写更危险）。
    """
    print('== --selftest：注入四类漂移，逐条断言会变红 ==')
    drop_target = find_probe_field(spec, impl, need_bound=False)
    wide_target = find_probe_field(spec, impl, need_bound=True)
    plan = []

    def mutate_ref(probe):
        probe.setdefault('components', {}).setdefault('schemas', {})['__probe__'] = \
            {'$ref': '#/components/schemas/__NO_SUCH_SCHEMA__'}
        return '__NO_SUCH_SCHEMA__'

    plan.append(('M 类｜悬空 $ref', mutate_ref))

    if drop_target is None:
        print(f'  {NG} 找不到可注入的 (schema, 字段) 对 —— 覆盖已经塌了，门禁名存实亡')
        return 1

    def mutate_drop(probe, target=drop_target):
        schema_name, field = target
        props = probe['components']['schemas'][schema_name].get('properties') or {}
        props.pop(field, None)
        return f'`{field}`'

    plan.append((f'H 类｜删掉 {drop_target[0]}.{drop_target[1]}', mutate_drop))

    def mutate_phantom(probe, target=drop_target):
        schema_name, field = target
        probe['components']['schemas'][schema_name].setdefault('properties', {})[
            '__phantom_field__'] = {'type': 'string'}
        return '`__phantom_field__`'

    plan.append((f'H 类（反向）｜给 {drop_target[0]} 塞一个实现没有的字段',
                 mutate_phantom))

    if wide_target is None:
        print(f'  {NG} 找不到带 Bean Validation 边界的字段 —— ③ 边界检查没有事实源')
        return 1

    def mutate_wide(probe, target=wide_target):
        schema_name, field = target
        node = probe['components']['schemas'][schema_name]['properties'][field]
        if node.get('minimum') is not None or node.get('maxLength') is None:
            node['minimum'] = -987654321
        else:
            node['maxLength'] = 987654321
        return f'`{field}`'

    plan.append((f'L 类｜把 {wide_target[0]}.{wide_target[1]} 的边界放宽到实现之外',
                 mutate_wide))

    failed = 0
    for label, mutate in plan:
        probe = copy.deepcopy(spec)
        expect = mutate(probe)
        errors = run_checks(probe, impl)
        hit = [e for e in errors if expect in e]
        if hit:
            print(f'  {OK} {label}\n       → {hit[0]}')
        else:
            print(f'  {NG} {label} → 门禁没报警（{len(errors)} 条错误里没有它）')
            failed += 1

    # 反向对照：没注入之前，这几处必须是干净的（否则 selftest 证明不了任何事）
    baseline = run_checks(spec, impl)
    for label, target in (('H', drop_target), ('L', wide_target)):
        noise = [e for e in baseline if f'`{target[1]}`' in e]
        if noise:
            print(f'  {NG} 基线不干净：{label} 的目标字段 {target[1]} 本来就有错（{noise[0]}）'
                  f'—— selftest 的对照失效')
            failed += 1
    if any('`__phantom_field__`' in e for e in baseline):
        print(f'  {NG} 基线不干净：`__phantom_field__` 本来就在契约里 —— selftest 的对照失效')
        failed += 1

    if failed:
        print(f'\n{NG} selftest 失败：{failed} 项没通过。')
        return 1
    print(f'{OK} 四类漂移全部当场变红，基线对这几处干净 —— 门禁有效。')
    return 0


def find_probe_field(spec, impl, need_bound):
    """找一个「契约与实现当前对得上」的具名 schema 顶层字段，作为注入靶子。"""
    base = spec_base(spec)
    for path, item in (spec.get('paths') or {}).items():
        for method, op in (item or {}).items():
            if method.lower() not in HTTP_METHODS or not isinstance(op, dict):
                continue
            got = impl.handler(method.upper(), path, base)
            if not got:
                continue
            handler, src = got
            fields = request_body_type_of(impl, handler, src)
            if not isinstance(fields, list):
                continue
            ref = request_body_ref(op)
            if not isinstance(ref, str) or not ref.startswith('#/components/schemas/'):
                continue          # 只挑具名组件 schema，改动面最小、最不容易误伤
            schema_name = ref.rsplit('/', 1)[-1]
            props = ((spec.get('components', {}).get('schemas', {})
                      .get(schema_name) or {}).get('properties') or {})
            for f in fields:
                if f.name not in props:
                    continue
                if need_bound and f.minimum is None and f.max_length is None:
                    continue
                return schema_name, f.name
    return None


def request_body_ref(op):
    schema = None
    for media in ((op.get('requestBody') or {}).get('content') or {}).values():
        schema = media.get('schema')
        break
    return schema.get('$ref') if isinstance(schema, dict) else None


def spec_base(spec):
    try:
        url = (spec.get('servers') or [{}])[0].get('url', '')
        return re.sub(r'^(https?://[^/]+)?', '', url).rstrip('/')
    except Exception:
        return ''


# ══════════════════════════════════════════════════════════════════════
# 5. 入口
# ══════════════════════════════════════════════════════════════════════

def run_checks(spec, impl):
    return check_refs(spec) + check_contract(spec, impl)[0]


def main() -> int:
    ap = argparse.ArgumentParser(description='字段级契约门禁（openapi.yaml ⇔ 后端 DTO）')
    ap.add_argument('--spec', default=str(DEFAULT_CONTRACT), help='契约 YAML 路径')
    ap.add_argument('--selftest', action='store_true', help='注入漂移，证明门禁真的会红')
    ap.add_argument('--show-gaps', action='store_true', help='打印未进入比对的清单')
    args = ap.parse_args()

    spec_path = pathlib.Path(args.spec)
    if not spec_path.exists():
        print(f'{NG} 找不到契约文件：{spec_path}')
        return 1
    with open(spec_path, encoding='utf-8') as f:
        spec = yaml.safe_load(f)
    if not isinstance(spec, dict):
        print(f'{NG} 契约解析结果不是映射：{spec_path}')
        return 1

    impl = Impl()
    print(f'契约：{spec_path}')
    print(f'实现：{len(impl.sources)} 个 .java / {len(impl.handlers)} 个 Controller 处理器 / '
          f'{sum(len(s.records) for s in impl.sources)} 个 record\n')

    # 解析器自己塌了的时候，所有检查都会"零错误通过" —— 那正是永绿空校验器。
    # 所以先断言事实源本身非空，再谈比对结果。
    if not impl.handlers:
        print(f'{NG} 一个 Controller 处理器都没解析出来：本脚本的 Java 解析器坏了，'
              f'结果不可信（拒绝以"0 错误"冒充"通过"）')
        return 1
    if len(impl.handlers) < 20:
        print(f'{NG} 只解析出 {len(impl.handlers)} 个处理器，远低于本仓规模，'
              f'解析器疑似退化（拒绝以偏概全）')
        return 1

    if args.selftest:
        return selftest(spec, impl)

    ref_errors = check_refs(spec)
    field_errors, stats, gaps = check_contract(spec, impl)

    for title, errs in (('① $ref 可解引用（M 类）', ref_errors),
                        ('②③④⑤ 字段 / 边界 / query（H、L 类）', field_errors)):
        if errs:
            print(f'{title}：{len(errs)} 条')
            for e in errs:
                print(f'  {NG} {e}')
            print()

    total = len(ref_errors) + len(field_errors)
    print(f'覆盖：请求体 {stats["request"]} 个 / 响应 data {stats["response"]} 个 / '
          f'query {stats["query"]} 个操作已进入比对')
    if gaps:
        print(f'未比对缺口：{len(gaps)} 条'
              f'{"（--show-gaps 查看）" if not args.show_gaps else ""}')
        if args.show_gaps:
            for g in gaps:
                print(f'  ! {g}')

    if total:
        print(f'\n{NG} 字段级契约门禁失败：共 {total} 条错误')
        return 1
    print(f'\n{OK} 字段级契约门禁通过：$ref 全部可解引用，'
          f'字段/边界/query 与实现逐项对上')
    return 0


if __name__ == '__main__':
    sys.exit(main())
