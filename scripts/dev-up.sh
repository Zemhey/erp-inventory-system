#!/usr/bin/env bash
# ════════════════════════════════════════════════════════════════════════════
# dev-up：一键起本地开发环境
#
# 设计要点：
#   1. 幂等 —— 重复执行安全，已起的服务不会重复创建
#   2. 等健康检查通过才返回 —— 不靠 sleep 猜，避免"起来了但没就绪"的假象
#   3. 失败时给出可操作的信息，而不是一句 error
# ════════════════════════════════════════════════════════════════════════════
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/infra/compose/docker-compose.yml"

echo "=== 进销存系统 · 本地环境启动 ==="
echo "compose: $COMPOSE_FILE"

# ── 前置检查 ──────────────────────────────────────────────────────────────
if ! command -v docker >/dev/null 2>&1; then
  echo "✗ 未找到 docker。请先安装 Docker Desktop / Docker Engine。" >&2
  exit 1
fi

if ! docker info >/dev/null 2>&1; then
  echo "✗ Docker 守护进程未运行。请先启动 Docker Desktop。" >&2
  exit 1
fi

# ── 启动依赖服务 ──────────────────────────────────────────────────────────
echo ""
echo "--- 启动 PostgreSQL / Redis / RabbitMQ ---"
docker compose -f "$COMPOSE_FILE" up -d

# ── 等健康检查通过（最多 120s） ───────────────────────────────────────────
echo ""
echo "--- 等待服务健康 ---"
deadline=$((SECONDS + 120))
services=(erp-postgres erp-redis erp-rabbitmq)

all_healthy() {
  for svc in "${services[@]}"; do
    status=$(docker inspect -f '{{.State.Health.Status}}' "$svc" 2>/dev/null || echo "missing")
    if [ "$status" != "healthy" ]; then
      return 1
    fi
  done
  return 0
}

while ! all_healthy; do
  if [ $SECONDS -ge $deadline ]; then
    echo "✗ 等待健康检查超时（120s）。当前状态：" >&2
    for svc in "${services[@]}"; do
      status=$(docker inspect -f '{{.State.Health.Status}}' "$svc" 2>/dev/null || echo "missing")
      echo "    $svc: $status" >&2
    done
    echo "  排查：docker compose -f \"$COMPOSE_FILE\" logs --tail=50" >&2
    exit 1
  fi
  sleep 2
done

for svc in "${services[@]}"; do
  echo "  ✓ $svc healthy"
done

# ── 打印连接信息 ──────────────────────────────────────────────────────────
cat <<'EOF'

=== 环境就绪 ===
  PostgreSQL : localhost:5432  库 erp / 用户 erp / 密码 erp
  Redis      : localhost:16379 （容器内 6379；宿主用 16379，避开本机保留段 6281-6380）
  RabbitMQ   : localhost:5672  (管理台 http://localhost:15672  erp/erp)

下一步：
  后端启动   cd backend/server && ./gradlew bootRun
  API 文档   http://localhost:8080/swagger-ui.html
  跑测试     cd backend/server && ./gradlew test

停止环境：docker compose -f infra/compose/docker-compose.yml down
EOF
