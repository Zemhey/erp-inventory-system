#!/usr/bin/env python3
"""契约缺口扫描 —— 实现侧真实端点 vs openapi.yaml 操作集合的差集。

★ 只读：本脚本**不写** openapi.yaml，也不改任何源码。它只读取与报告。
★ 常驻用途：ADR-0008 要求契约是单一接口事实源。本脚本是那条要求的最短可执行证明 ——
   `缺口 = 0` 就是"契约没落下任何实现接口"；`contract_only = 0` 就是"契约没吹牛"。

用法:
    python scripts/contract-gap-scan.py            # 打印报告
    python scripts/contract-gap-scan.py --json     # 只输出 JSON（给 CI / 报告引用）
    python scripts/contract-gap-scan.py --check    # 有缺口则退出码 1（可挂进门禁）

实现侧解析规则与 ApiContractMappingIT 保持一致：
  1. 取 Controller 类上的 @RequestMapping 作为 base path；
  2. 取方法上的 @Get/Post/Put/Patch/DeleteMapping path（可为空 = base path 本身）；
  3. 路径里的 {xxx} 一律归一为 {}（测试的 normalizePath 语义）；
  4. 跳过块注释与行注释 —— 否则 javadoc 里出现的 `{@code @GetMapping}`
     会被算成一个假端点（这正是 2026-10-24 实测把 133 数成 134 的原因）。

★ 不要用 `git grep -o '@GetMapping' | wc -l` 这种"数文本出现次数"的办法统计端点：
  它会把注释、字符串、javadoc 里的提及一起数进去。统计必须锚定语法结构。
"""
import json
import re
import sys
import pathlib
import collections

try:
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
except Exception:
    pass

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / 'backend' / 'server' / 'src' / 'main' / 'java'
SPEC = ROOT / 'packages' / 'api-contract' / 'openapi.yaml'
OUT = ROOT / '.plan' / 'contract-gap-scan.json'

CLASS_RM = re.compile(r'@RequestMapping\(\s*(?:value\s*=\s*)?"([^"]*)"')
METHOD_ANN = re.compile(r'@(Get|Post|Put|Patch|Delete)Mapping')


def norm(p: str) -> str:
    p = re.sub(r'\{[^}]*\}', '{}', p)
    return p[:-1] if len(p) > 1 and p.endswith('/') else p


def parse_java(path: pathlib.Path, rel: str):
    text = path.read_text(encoding='utf-8')
    # 抹掉块注释（保留行号），再逐行去掉行注释
    text = re.sub(r'/\*.*?\*/', lambda m: '\n' * m.group(0).count('\n'), text, flags=re.S)
    lines = text.split('\n')

    base = ''
    m = CLASS_RM.search(text)
    if m:
        base = m.group(1)

    out = []
    for idx, line in enumerate(lines, 1):
        code = line.split('//')[0]
        for am in METHOD_ANN.finditer(code):
            verb = am.group(1).upper()
            tail = code[am.end():]
            pm = re.match(r'\s*\(\s*(?:value\s*=\s*)?"([^"]*)"', tail)
            sub = pm.group(1) if pm else ''
            full = (base.rstrip('/') + '/' + sub.lstrip('/')).rstrip('/') or '/'
            out.append((verb, norm(full), rel, idx))
    return out


def scan():
    impl = {}
    for f in sorted(SRC.rglob('*.java')):
        if any(part in ('build', 'out', 'target') for part in f.parts):
            continue
        rel = str(f.relative_to(ROOT)).replace('\\', '/')
        for verb, p, file, line in parse_java(f, rel):
            impl.setdefault(f'{verb} {p}', {'file': file, 'line': line})

    import yaml
    from urllib.parse import urlparse
    spec = yaml.safe_load(SPEC.read_text(encoding='utf-8'))
    base_path = ''
    servers = spec.get('servers') or []
    if servers:
        base_path = urlparse(servers[0].get('url', '')).path.rstrip('/')

    contract = {}
    for p, item in (spec.get('paths') or {}).items():
        for meth in ('get', 'post', 'put', 'patch', 'delete', 'head', 'options', 'trace'):
            if meth in item:
                contract[f'{meth.upper()} {norm(base_path + p)}'] = item[meth].get('operationId', '')

    undocumented = sorted(set(impl) - set(contract))
    contract_only = sorted(set(contract) - set(impl))
    return impl, contract, undocumented, contract_only


def main() -> int:
    impl, contract, undocumented, contract_only = scan()

    if '--json' in sys.argv:
        print(json.dumps({
            'implCount': len(impl),
            'contractCount': len(contract),
            'gap': len(undocumented),
            'contractOnly': len(contract_only),
            'undocumented': {op: impl[op] for op in undocumented},
            'contractOnlyOps': contract_only,
        }, ensure_ascii=False, indent=2))
    else:
        print('=' * 78)
        print('实现侧端点:', len(impl))
        print('契约侧操作:', len(contract))
        print('缺口 (实现有/契约无):', len(undocumented))
        print('契约多余 (契约有/实现无):', len(contract_only))
        print('=' * 78)

        if undocumented:
            print('\n### A. 实现有 / 契约没有\n')
            for op in undocumented:
                print(f'{op}\n      {impl[op]["file"]}:{impl[op]["line"]}')
            print('\n### 按 Controller 归组\n')
            groups = collections.Counter(
                impl[op]['file'].split('/')[-1].replace('.java', '')
                for op in undocumented)
            for ctrl, n in groups.most_common():
                print(f'{ctrl:38s} {n}')

        if contract_only:
            print('\n### B. 契约有 / 实现没有（契约吹牛）\n')
            for op in contract_only:
                print(f'{op}  (operationId={contract[op]})')

        OUT.parent.mkdir(parents=True, exist_ok=True)
        OUT.write_text(json.dumps({
            'implCount': len(impl),
            'contractCount': len(contract),
            'gap': len(undocumented),
            'contractOnly': len(contract_only),
            'undocumented': {op: impl[op] for op in undocumented},
            'contractOnlyOps': contract_only,
        }, ensure_ascii=False, indent=2), encoding='utf-8')
        print(f'\nJSON 已写入 {OUT}')

    if '--check' in sys.argv and (undocumented or contract_only):
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
