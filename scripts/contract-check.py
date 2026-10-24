"""契约校验的本地可执行版 —— CI job `contract-check` 的等价物。

## 为什么需要这个脚本

`.github/workflows/ci.yml` 的 `contract-check` job 是一段**内联 Python**。
而本项目自 M1 起从未 push（实测 main 领先 origin/main 154 个提交），
所以那道门禁**一天都没在真实 runner 上执行过**。

结果是：契约校验一直红着，而没人知道 —— 直到 2026-10-24 契约清偿时把它搬成本地脚本才发现。

本脚本补上"本地等价物"这个缺口。它与 CI 的**校验项**一致，但做了两处必要的改进：

1. **编码安全**：Windows 控制台默认 GBK，直接 print `✗`/`✓` 会抛
   `UnicodeEncodeError: 'gbk' codec can't encode character '\u2717'`。
   本脚本把 stdout 强制为 UTF-8，并在不可用时退化为 ASCII 标记。
2. **幂等键按"端点类型"校验**（见下），而不是"所有 POST 一刀切"。

## 幂等键规范（2026-10-24 建立，见 `.plan/V2-施工指挥台.md` §0.3.5）

| 端点类型 | 特征 | 幂等来源 | 是否声明 X-Request-Id |
|---|---|---|---|
| 创建型 | 每次产生新实体 | 客户端键 + DB 唯一约束 | 必须 |
| 转换型 | 有数量副作用（改库存/累计） | 客户端键 | 必须 |
| 纯开关 | 只改 enabled 标志 | 状态本身幂等 | 不声明 |
| 身份/配置管理 | login、change-password、roles、users | 无副作用或天然幂等 | 不声明 |

判据是"**幂等由谁保证**"，不是"是不是 POST"。
状态迁移型端点的 `description` 应写明"幂等由状态机保证，不消费 X-Request-Id"。

用法：
    python scripts/contract-check.py
退出码 0 = 通过，1 = 失败。
"""
import sys
import re
import pathlib

# ── Windows GBK 控制台保护 ───────────────────────────────────────────────
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

# ── 幂等键的实际判据（2026-10-24 实测得出，不是凭直觉分类）────────────────
#
# 我最初想把端点按"创建型/转换型/状态迁移型"三类分，认为状态迁移型都不需要幂等键。
# 实测推翻了这个假设：
#   `{id}/submit|approve|reject|void` 的实现**全都消费** X-Request-Id
#     —— 因为它们的幂等性来自"同 requestId 只生效一次"的 DB 唯一约束，
#        而不是靠状态机（状态机只拒绝"非法转移"，对"同一合法转移重复两次"无能为力）。
#   只有 `{id}/disable`、`{id}/enabled` 这类纯开关，以及 /login、/change-password、
#   /roles、/users 这些身份与配置管理端点才不消费。
#
# 所以判据不是"端点类型"，而是**实现到底消不消费**。
# 本脚本的策略：不猜实现，而是读实现（扫 Controller 源码看方法签名里有没有
# `@RequestHeader("X-Request-Id")`），据此向契约提出双向核对：
#   - 实现消费、契约没声明 → 错误（契约漏了幂等键）
#   - 实现不消费、契约声明了 → 错误（契约承诺了实现做不到的事）
#
# CI（ci.yml）里那条"所有 POST 都必须声明"的旧规则在语义上是错的，本脚本取代它。

SRC_ROOT = pathlib.Path(__file__).resolve().parent.parent / 'backend/server/src/main/java'


def scan_impl_consumers() -> dict:
    """扫描 Controller 源码，返回 {完整路径: 是否消费 X-Request-Id}。

    ★ 关键细节（三个都踩过）：
    1. 方法级 @PostMapping 里写的是**相对路径**（如 `/{id}/submit`），
       必须拼上类级 @RequestMapping 前缀才是完整路径。
    2. 必须跳过 `build/` 目录（编译产物里有源码副本）。
    3. **不能用一个固定的"往后 N 行"窗口判断方法体** —— 相邻方法会串味。
       本文件曾经用 `lines[i:i+12]`，结果 `approve(@PathVariable long id)`（112 行）
       的窗口覆盖到了相邻 `convert`（118 行起）的 `@RequestHeader`（122 行），
       把"不消费"误报成"消费"。**改为按括号配平扫描方法签名**。
    """
    result = {}
    if not SRC_ROOT.exists():
        return result

    for f in SRC_ROOT.rglob('*Controller.java'):
        if any(part in ('build', 'out', 'target') for part in f.parts):
            continue
        lines = f.read_text(encoding='utf-8', errors='ignore').splitlines()

        prefix = ''
        for line in lines:
            m = re.search(r'@RequestMapping\("([^"]*)"\)', line)
            if m:
                prefix = m.group(1)
                break

        for i, line in enumerate(lines):
            m = re.search(r'@PostMapping(?:\("([^"]*)"\))?', line)
            if not m:
                continue
            rel = m.group(1) or ''
            full = (prefix + rel) if rel else prefix

            # ── 按括号配平取方法签名 ──────────────────────────────────
            # ★ 坑：`@PostMapping("/{id}/void")` 这一行里的**字符串字面量**
            #   自带 `{` 和 `}`！直接数括号会在注解行上就配平为 0 并提前退出，
            #   导致签名只剩注解行、扫不到 @RequestHeader。
            #   必须先剥掉字符串字面量再数括号。
            sig_parts = []
            depth = 0
            entered_body = False
            for j in range(i, min(i + 40, len(lines))):
                seg = lines[j]
                sig_parts.append(seg)

                # 剥掉双引号内的内容（`"/{id}/void"` → `""`）
                stripped = re.sub(r'"([^"\\]|\\.)*"', '""', seg)
                stripped = re.sub(r"'([^'\\]|\\.)*'", "''", stripped)

                for ch in stripped:
                    if ch == '{':
                        depth += 1
                    elif ch == '}':
                        depth -= 1
                if depth > 0:
                    entered_body = True
                    break
            signature = '\n'.join(sig_parts)
            result[full] = 'X-Request-Id' in signature

    return result

CONTRACT = pathlib.Path(__file__).resolve().parent.parent / 'packages/api-contract/openapi.yaml'


def is_state_transition(path: str) -> bool:
    """路径末段是否为状态迁移型动作（仅用于 warnings 文案，不用于硬校验）。"""
    return path.rstrip('/').rsplit('/', 1)[-1] in STATE_TRANSITION_ACTIONS


def main() -> int:
    if not CONTRACT.exists():
        print(f'{NG} 找不到契约文件：{CONTRACT}')
        return 1

    with open(CONTRACT, encoding='utf-8') as f:
        spec = yaml.safe_load(f)

    errors = []
    warnings = []

    # ── 1. 基础结构 ──────────────────────────────────────────────────
    if not str(spec.get('openapi', '')).startswith('3.'):
        errors.append('openapi 版本必须是 3.x')
    if 'info' not in spec or 'title' not in spec.get('info', {}):
        errors.append('缺少 info.title')
    if 'paths' not in spec or not spec['paths']:
        errors.append('paths 为空')

    # ── 2. 每个操作必须有 operationId 与 responses ────────────────────
    ops = 0
    for path, item in (spec.get('paths') or {}).items():
        for method, op in (item or {}).items():
            if method.lower() not in ('get', 'post', 'put', 'patch', 'delete'):
                continue
            ops += 1
            if 'operationId' not in op:
                errors.append(f'{method.upper()} {path} 缺少 operationId')
            if not op.get('responses'):
                errors.append(f'{method.upper()} {path} 缺少 responses')

    # ── 3. 幂等键：契约声明 ↔ 实现消费 双向核对 ────────────────────────
    #
    # 这是本脚本相对 CI 的核心改进：CI 只查"契约里有没有声明"，
    # 本脚本额外查"实现到底消不消费"，两个方向都抓。
    ref = '#/components/parameters/RequestId'
    consumers = scan_impl_consumers()

    for path, item in (spec.get('paths') or {}).items():
        post = (item or {}).get('post')
        if not post:
            continue
        declared = any(p.get('$ref') == ref for p in (post.get('parameters') or []))

        # ★ 契约里的 paths 是**不带 /api/v1 前缀**的（前缀在 servers 里声明）：
        #     契约  /purchase-requisitions/{id}/submit
        #     实现  @RequestMapping("/api/v1/purchase-requisitions") + @PostMapping("/{id}/submit")
        #   所以要把实现侧的 key 去掉 /api/v1 再对照。这一步我前三版都漏了。
        impl_consumes = consumers.get('/api/v1' + path)
        if impl_consumes is None:
            impl_consumes = consumers.get(path)

        if impl_consumes is True and not declared:
            errors.append(
                f'POST {path} 实现消费了 X-Request-Id，但契约未声明 —— 契约漏了幂等键')
        elif impl_consumes is False and declared:
            errors.append(
                f'POST {path} 契约声明了 X-Request-Id，但实现不消费 —— 契约承诺了实现做不到的事')
        elif impl_consumes is None and not declared:
            # 无实现可对照时，保守要求声明
            errors.append(f'POST {path} 未声明 X-Request-Id（且无实现可对照）')

    # ── 4. operationId 全局唯一（CI 没有，但这是契约的基本要求） ──────
    seen = {}
    for path, item in (spec.get('paths') or {}).items():
        for method, op in (item or {}).items():
            if method.lower() not in ('get', 'post', 'put', 'patch', 'delete'):
                continue
            oid = op.get('operationId')
            if oid is None:
                continue
            if oid in seen:
                errors.append(f'operationId 重复：{oid}（{seen[oid]} 与 {method.upper()} {path}）')
            else:
                seen[oid] = f'{method.upper()} {path}'

    # ── 输出 ─────────────────────────────────────────────────────────
    if warnings:
        print('提示（不阻断）：')
        for w in warnings:
            print(f'  ! {w}')

    if errors:
        print('契约校验失败：')
        for e in errors:
            print(f'  {NG} {e}')
        print(f'\n共 {len(errors)} 条错误。契约文件：{CONTRACT}')
        return 1

    print(f'{OK} 契约校验通过：{ops} 个操作，{len(spec["paths"])} 个路径，'
          f'{len(spec.get("components", {}).get("schemas", {}))} 个 schema')
    return 0


if __name__ == '__main__':
    sys.exit(main())
