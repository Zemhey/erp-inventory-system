"""契约 ↔ 实现 幂等键双向精确核对（精确到方法签名，不用固定行窗口）。

与 main 的 scripts/contract-check.py 的差异：
  它用 `lines[i:i+12]` 猜方法体范围，会把**下一个**方法的 @RequestHeader 算进来
  （实例：PurchaseRequisitionController 的 approve 紧跟 convert，convert 收了
  @RequestHeader 在第 122 行，正好落进 approve 的 12 行窗口 → 误报）。
  本脚本按真正的括号配平切出方法签名，因此不受相邻方法干扰。
"""
import re
import sys
import pathlib
import yaml

try:
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
except Exception:
    pass

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / 'backend/server/src/main/java'
SPEC = ROOT / 'packages/api-contract/openapi.yaml'
REF = '#/components/parameters/RequestId'

CLASS_RM = re.compile(r'@RequestMapping\(\s*(?:value\s*=\s*)?"([^"]*)"')
POST_ANN = re.compile(r'@PostMapping')


def norm(p):
    p = re.sub(r'\{[^}]*\}', '{}', p)
    return p[:-1] if len(p) > 1 and p.endswith('/') else p


def scan():
    """返回 {'/api/v1/xxx': bool}，只针对 POST。"""
    consumers = {}
    for f in SRC.rglob('*.java'):
        if any(part in ('build', 'out', 'target') for part in f.parts):
            continue
        if 'Controller' not in f.name:
            continue
        raw = f.read_text(encoding='utf-8', errors='ignore')
        # 抹掉块注释（等量空行保行号），避免注释里的注解被算进来
        t = re.sub(r'/\*.*?\*/', lambda m: '\n' * m.group(0).count('\n'), raw, flags=re.S)
        prefix = ''
        m = CLASS_RM.search(t)
        if m:
            prefix = m.group(1)
        for am in POST_ANN.finditer(t):
            # 方法级路径
            tail = t[am.end():am.end() + 80]
            pm = re.match(r'\s*\(\s*(?:value\s*=\s*)?"([^"]*)"\s*\)', tail)
            rel = pm.group(1) if pm else ''
            full = prefix + rel
            # 切方法签名：从注解结束到括号配平归零后的第一个 '{'
            snippet = t[am.end():am.end() + 3000]
            depth = 0
            cut = len(snippet)
            for i, ch in enumerate(snippet):
                if ch == '(':
                    depth += 1
                elif ch == ')':
                    depth -= 1
                    if depth == 0 and i > 0:
                        # 签名括号已闭合，后面可能是 throws / 返回类型 / '{'
                        rest = snippet[i:]
                        b = rest.find('{')
                        cut = i + b if b >= 0 else len(snippet)
                        break
            sig = snippet[:cut]
            consumers[full] = '@RequestHeader' in sig and 'X-Request-Id' in sig
    return consumers


consumers = scan()
spec = yaml.safe_load(SPEC.read_text(encoding='utf-8'))

errors = []
rows = []
for path, item in spec['paths'].items():
    post = (item or {}).get('post')
    if not post:
        continue
    declared = any(p.get('$ref') == REF for p in (post.get('parameters') or []))
    key = '/api/v1' + path
    consumed = consumers.get(key)
    rows.append((path, declared, consumed))

print('=== 契约里的 POST 共 %d 个 ===' % len(rows))
only_decl = [(p, c) for p, d, c in rows if d and c is False]
only_impl = [(p, c) for p, d, c in rows if (not d) and c is True]
no_impl = [(p, c) for p, d, c in rows if c is None]
ok = [(p) for p, d, c in rows if d and c is True]

print('  双向一致（声明+消费）：%d' % len(ok))
print('  一致（都不涉及）：%d' % len([1 for p, d, c in rows if (not d) and c is False]))
print('  ★ 契约声明、实现不消费：%d' % len(only_decl))
for p, _ in only_decl:
    print('     ', p)
print('  ★ 实现消费、契约未声明：%d' % len(only_impl))
for p, _ in only_impl:
    print('     ', p)
print('  无实现可对照：%d' % len(no_impl))
for p, _ in no_impl:
    print('     ', p)

sys.exit(1 if (only_decl or only_impl or no_impl) else 0)
