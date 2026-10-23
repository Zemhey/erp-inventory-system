#!/usr/bin/env bash
# ════════════════════════════════════════════════════════════════════════════
# gen-index：生成 index/ 代码索引（★ 全部产物由脚本生成，禁止手写）
#
# 依据：docs/ADR-0009（代码索引脚本生成，不手写）
#
# 生成的产物：
#   index/modules.md        模块清单与规模（扫源码目录）
#   index/api-map.md        接口清单（解析 openapi.yaml）
#   index/deps.json         依赖图（gradle dependencies 解析）
#   index/test-map.md       测试用例 ↔ 需求（扫 @CaseId 注解）
#   index/coverage-map.md   覆盖率（读 JaCoCo XML，CI 产物）
#
# 用法：
#   ./scripts/gen-index.sh            # 重新生成
#   ./scripts/gen-index.sh --check    # CI 模式：有差异则失败（防文档腐化）
# ════════════════════════════════════════════════════════════════════════════
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
INDEX_DIR="$ROOT_DIR/index"
CONTRACT="$ROOT_DIR/packages/api-contract/openapi.yaml"
SERVER_DIR="$ROOT_DIR/backend/server"
CHECK_MODE=false

# ★ 产物确定性：默认**不写生成时间戳**。
#   原因：索引文件是要提交进仓库、并由 CI 用 `git diff` 校验的。
#   若每次生成都写入当前时间，那么即使源码毫无变化，CI 的 index-check 也会 100% 失败
#   —— 这会让门禁形同虚设（团队很快学会忽略它）。
#   需要人读的时间戳时用 --with-timestamp 显式开启（仅本地查看，不要提交）。
STAMP=""
case "${1:-}" in
  --check)          CHECK_MODE=true ;;
  --with-timestamp) STAMP="$(date -u '+%Y-%m-%dT%H:%M:%SZ')" ;;
esac

# 追加生成时间（仅当 STAMP 非空）
append_stamp() {
  [ -n "$STAMP" ] && echo "生成时间：${STAMP}" >> "$1"
  return 0
}

mkdir -p "$INDEX_DIR"

echo "=== 生成代码索引 ==="
echo "root: $ROOT_DIR"
[[ "$CHECK_MODE" == true ]] && echo "模式: --check（有差异则失败）"

# ─────────────────────────── 1. modules.md ───────────────────────────
gen_modules() {
  local out="$INDEX_DIR/modules.md"
  {
    echo "# 模块索引（自动生成，请勿手写）"
    echo ""
    echo "> 生成脚本：\`scripts/gen-index.sh\` · 来源：\`backend/server/src/main/java/com/erp/\`"
    echo ""
    echo "| 模块 | 职责 | Java 文件 | 代码行 | 测试文件 |"
    echo "|---|---|---|---|---|"
  } > "$out"

  if [ -d "$SERVER_DIR/src/main/java/com/erp" ]; then
    for dir in "$SERVER_DIR"/src/main/java/com/erp/*/; do
      [ -d "$dir" ] || continue
      local mod
      mod="$(basename "$dir")"
      local files lines tests
      # `|| true` 兜底：find 在目录不存在/无匹配时返回 1，配合 set -e 会中断脚本
      files=$( { find "$dir" -name '*.java' 2>/dev/null || true; } | wc -l | tr -d ' ')
      lines=$( { find "$dir" -name '*.java' -exec cat {} + 2>/dev/null || true; } | wc -l | tr -d ' ')
      # ★ 注意：模块可能尚无测试目录，此时 find 会返回退出码 1。
      # 在 set -e + pipefail 下这会直接中断整个脚本（且无任何报错，极难排查）。
      # 因此此处必须 `|| true` 兜底。
      tests=$( { find "$SERVER_DIR/src/test/java/com/erp/$mod" -name '*.java' 2>/dev/null || true; } | wc -l | tr -d ' ')
      # 职责取自 docs/03-架构与整体结构.md 的模块表
      local duty
      case "$mod" in
        iam) duty="认证、RBAC、数据权限" ;;
        mdm) duty="商品/SKU/供应商/客户/仓库" ;;
        inv) duty="库存余额、流水、预占、调拨、FEFO" ;;
        pur) duty="采购进货单与入库" ;;
        sal) duty="销售出货单与出库" ;;
        rma) duty="退货入库（逆向）" ;;
        set) duty="日清月结、对账" ;;
        rpt) duty="统计报表（只读）" ;;
        nl) duty="自然语言解析与意图调度（不承载业务规则）" ;;
        sys) duty="幂等、审计、测试钩子" ;;
        shared) duty="通用：时间、金额、错误码" ;;
        *) duty="（未登记，请补充 docs/03 模块表）" ;;
      esac
      echo "| \`$mod\` | $duty | $files | $lines | $tests |" >> "$out"
    done
  else
    echo "| （源码目录尚未创建） | — | 0 | 0 | 0 |" >> "$out"
  fi

  echo "" >> "$out"
  append_stamp "$out"
  echo "  ✓ modules.md"
}

# ─────────────────────────── 2. api-map.md ───────────────────────────
gen_api_map() {
  local out="$INDEX_DIR/api-map.md"
  {
    echo "# 接口索引（自动生成，请勿手写）"
    echo ""
    echo "> 来源：\`packages/api-contract/openapi.yaml\`（单一事实源，ADR-0008）"
    echo ""
    echo "| 方法 | 路径 | operationId | 摘要 |"
    echo "|---|---|---|---|"
  } > "$out"

  if [ -f "$CONTRACT" ]; then
    # 解析 OpenAPI，输出接口清单。
    # 要点：YAML 里 **summary 常出现在 operationId 之前**（本例就是），
    # 因此不能在读到 summary 时立刻输出（那时 op 还是空的）——
    # 正确做法：两个字段都收集齐，等到该操作的最后一个字段（responses）出现时再输出。
    awk '
      /^  \/[^ ]*:$/ { path=$1; sub(/:$/,"",path); gsub(/^ +/,"",path); next }
      /^    (get|post|put|patch|delete):$/ {
        method=toupper($1); sub(/:$/,"",method); inop=1; op=""; sum=""; next
      }
      inop && /^ +operationId:/ { line=$0; sub(/^ +operationId: */,"",line); op=line; next }
      inop && /^ +summary:/     { line=$0; sub(/^ +summary: */,"",line); sum=line; next }
      inop && /^ +responses:$/ {
        printf "| %s | `%s` | `%s` | %s |\n", method, path, op, sum;
        inop=0; next
      }
    ' "$CONTRACT" >> "$out"
  else
    echo "| — | — | — | （契约尚未创建） |" >> "$out"
  fi

  echo "" >> "$out"
  append_stamp "$out"
  echo "  ✓ api-map.md"
}

# ─────────────────────────── 3. test-map.md ───────────────────────────
# 从测试源码里抽取 @CaseId("TC-XXX-000") 与紧随其后的 @DisplayName
gen_test_map() {
  local out="$INDEX_DIR/test-map.md"
  {
    echo "# 测试用例索引（自动生成，请勿手写）"
    echo ""
    echo "> 来源：扫源码中的 \`@CaseId\` 注解 + 追踪矩阵（docs/00-索引与追踪矩阵.md）"
    echo ""
    echo "| 用例ID | 用例名称 | 所在文件 |"
    echo "|---|---|---|"
  } > "$out"

  local found=0
  if [ -d "$SERVER_DIR/src/test/java" ]; then
    while IFS= read -r f; do
      awk -v file="${f#$ROOT_DIR/}" '
        /@CaseId\(/ {
          match($0, /"[^"]+"/); cid=substr($0, RSTART+1, RLENGTH-2); next
        }
        cid != "" && /DisplayName[ \t]*\(/ {
          # ★ 匹配 DisplayName 而不是 @DisplayName：
          #   注解可能写成全限定形式 @org.junit.jupiter.api.DisplayName(...)，
          #   要求 @ 紧邻 DisplayName 会静默丢弃这类用例
          #   （M1 真实踩到：3 条架构自检用例没进索引）。
          match($0, /"[^"]+"/); name=substr($0, RSTART+1, RLENGTH-2);
          printf "| `%s` | %s | `%s` |\n", cid, name, file;
          cid=""; found++;
        }
      ' "$f" >> "$out"
    done < <(find "$SERVER_DIR/src/test/java" -name '*.java' 2>/dev/null | sort)

    # 注意：grep -c 无匹配时返回退出码 1，在 set -e 下会中断脚本，
    # 故用 `|| true` 兜底（这是 bash 的经典陷阱）。
    found=$(grep -c '^| `TC-' "$out" || true)
    found=${found:-0}
  fi

  if [ "${found:-0}" -eq 0 ]; then
    echo "| （尚未登记测试用例） | — | — |" >> "$out"
  fi

  echo "" >> "$out"
  echo "共 $found 条用例。" >> "$out"
  append_stamp "$out"
  echo "  ✓ test-map.md（$found 条）"
}

# ─────────────────────────── 4. coverage-map.md ───────────────────────────
gen_coverage() {
  local out="$INDEX_DIR/coverage-map.md"
  local jacoco="$SERVER_DIR/build/reports/jacoco/test/jacocoTestReport.xml"

  # ★ 与 deps.json 同理：覆盖率报告是"测试运行的副产品"，
  #   没有它就不应生成产物 —— 否则"有报告的机器"与"没报告的机器"
  #   会产出不同内容，让 index-check 产生假警报（CI 上真实踩过）。
  if [ ! -f "$jacoco" ]; then
    echo "  ⊘ coverage-map.md（跳过：无 JaCoCo 报告；先跑 ./gradlew jacocoTestReport）"
    return 0
  fi

  {
    echo "# 覆盖率索引（自动生成，请勿手写）"
    echo ""
    echo "> 来源：JaCoCo 报告（由 CI 或本地 \`./gradlew jacocoTestReport\` 产出）"
    echo ""
    echo "| 指标 | 覆盖 / 总数 | 比例 |"
    echo "|---|---|---|"
  } > "$out"

  # ★ 关键：JaCoCo 导出的 XML **整个文件是一行**（无换行）。
  #   因此不能用 awk 按行匹配（那样只会命中第一个 counter，误报成某个类的覆盖率）。
  #   正确做法：grep -o 抽出全部该类型的 counter，取**最后一个** ——
  #   报告级总计在 <report> 收尾处，是文件中该类型的最后一次出现。
  for t in LINE BRANCH; do
    last=$(grep -o "<counter type=\"$t\"[^>]*/>" "$jacoco" | tail -1)
    [ -n "$last" ] || continue
    missed=$(printf '%s' "$last" | sed -n 's/.*missed="\([0-9]*\)".*/\1/p')
    covered=$(printf '%s' "$last" | sed -n 's/.*covered="\([0-9]*\)".*/\1/p')
    total=$((missed + covered))
    if [ "$t" = "LINE" ]; then label="行覆盖"; else label="分支覆盖"; fi
    pct=$(awk -v c="$covered" -v t="$total" 'BEGIN{printf "%.1f", (t>0? c*100/t : 0)}')
    echo "| $label | $covered / $total | ${pct}% |" >> "$out"
  done
  echo "" >> "$out"
  echo "原始报告：\`backend/server/build/reports/jacoco/test/html/index.html\`" >> "$out"

  echo "" >> "$out"
  append_stamp "$out"
  echo "  ✓ coverage-map.md"
}

# ─────────────────────────── 5. deps.json ───────────────────────────
#
# ★ 设计要点：**缺少前置条件时"跳过"，而不是写占位内容**。
#
# 原因（CI 上真实踩过）：本产物需要 JDK + gradlew，而 index-check 的
# ubuntu runner 默认不装 JDK。早期实现会在缺 JDK 时写入一个占位 JSON，
# 结果"CI 生成的占位版"与"开发者本地生成的真实版"永远不同，
# 于是 index-check 必然报漂移 —— 一个假警报。
#
# 正确做法：生成不了就不写，并明确打印"已跳过"。这样：
#   - 产物只由源码决定，任何环境结果一致；
#   - 需要 deps.json 的场景（本地或装了 JDK 的 CI）仍能正常生成。
gen_deps() {
  local out="$INDEX_DIR/deps.json"
  if [ "${SKIP_DEPS_INDEX:-0}" = "1" ]; then
    echo "  ⊘ deps.json（跳过：SKIP_DEPS_INDEX=1）"
    return 0
  fi
  if [ ! -x "$SERVER_DIR/gradlew" ]; then
    echo "  ⊘ deps.json（跳过：无 gradlew）"
    return 0
  fi
  if ! command -v java >/dev/null 2>&1; then
    echo "  ⊘ deps.json（跳过：缺少 JDK，本产物需要 java）"
    return 0
  fi
  if ! command -v python3 >/dev/null 2>&1; then
    echo "  ⊘ deps.json（跳过：缺少 python3）"
    return 0
  fi

  local tree
  tree="$(cd "$SERVER_DIR" && ./gradlew dependencies --configuration runtimeClasspath -q 2>/dev/null || true)"
  if [ -z "$tree" ]; then
    echo "  ⊘ deps.json（跳过：gradle 未返回依赖树）"
    return 0
  fi

  {
    echo "{"
    echo "  \"generatedAt\": \"${STAMP}\","
    echo "  \"configuration\": \"runtimeClasspath\","
    echo "  \"source\": \"gradle dependencies\","
    echo "  \"raw\": $(printf '%s' "$tree" | python3 -c 'import sys,json; print(json.dumps(sys.stdin.read()))' 2>/dev/null || echo '""')"
    echo "}"
  } > "$out"
  echo "  ✓ deps.json"
}

# ─────────────────────────── 执行 ───────────────────────────
gen_modules
gen_api_map
gen_test_map
gen_coverage
gen_deps

# ─────────────────────────── --check 模式 ───────────────────────────
#
# ★ 只校验"纯由源码决定"的产物。
#
# 反面教材（CI 上真实发生）：最初这里对**整个 index/ 目录**做 git diff，
# 而 deps.json / coverage-map.md 依赖 JDK 与测试报告 ——
# 没有 JDK 的 runner 生成不出同样内容，于是每次都被判定"索引过期"。
# 结果是门禁必红 → 门禁失去信任 → 真漂移也拦不住。
#
# 现在：只比对由源码/契约唯一确定的三份产物；其余产物由本地开发者
# 按需生成与提交，不参与 CI 漂移判定。
if [[ "$CHECK_MODE" == true ]]; then
  echo ""
  echo "--- 校验索引与已提交内容是否一致 ---"
  if command -v git >/dev/null 2>&1 && git -C "$ROOT_DIR" rev-parse --git-dir >/dev/null 2>&1; then
    CHECKED_FILES=(
      "index/modules.md"
      "index/api-map.md"
      "index/test-map.md"
    )
    DRIFT=0
    for rel in "${CHECKED_FILES[@]}"; do
      if ! git -C "$ROOT_DIR" diff --quiet -- "$rel"; then
        echo "✗ 已过期: $rel" >&2
        DRIFT=1
      fi
    done
    if [ "$DRIFT" -ne 0 ]; then
      echo "" >&2
      echo "请运行 ./scripts/gen-index.sh 并提交结果。" >&2
      git -C "$ROOT_DIR" --no-pager diff --stat -- "${CHECKED_FILES[@]}" >&2
      exit 1
    fi
    echo "✓ 索引与已提交内容一致（校验 ${#CHECKED_FILES[@]} 份确定性产物）"
  else
    echo "（非 git 仓库，跳过校验）"
  fi
fi

echo ""
echo "完成。产物目录：index/"
