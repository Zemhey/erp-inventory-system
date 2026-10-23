# ════════════════════════════════════════════════════════════════════════════
# 后端镜像（多阶段）
#
# 构建上下文：backend/server/
#   docker build -t erp-backend:local backend/server
#
# 设计取舍：
#   ① 构建与运行分离：构建阶段带 JDK + Gradle，运行阶段只有 JRE，镜像更小、
#      攻击面更少；构建缓存留在构建阶段，不进最终镜像。
#   ② 非 root 运行：应用只读文件系统上的 jar，不需要 root。
#   ③ 健康检查用 actuator/health（M0 已引入 actuator）；生产 profile 只返回
#      status，不泄露组件细节。
#   ④ base image 可用 build-arg 覆盖：国内网络/内网镜像仓库不必改文件。
# ════════════════════════════════════════════════════════════════════════════
ARG BUILD_IMAGE=eclipse-temurin:17-jdk-alpine
ARG RUNTIME_IMAGE=eclipse-temurin:17-jre-alpine
FROM ${BUILD_IMAGE} AS build

WORKDIR /workspace

# 先复制构建脚本与 wrapper，让依赖解析层可缓存；源码改动不会使这一层失效
COPY gradle ./gradle
COPY gradlew gradlew.bat gradle.properties settings.gradle.kts build.gradle.kts ./
RUN chmod +x gradlew \
    && ./gradlew --no-daemon dependencies --configuration runtimeClasspath > /dev/null

COPY src ./src

# 产物名由 Gradle 决定（erp-server-<version>.jar + -plain.jar），这里只取可执行 jar。
# 不用改 build.gradle.kts 的 archiveFileName：E2E 的 start-backend.mjs 按 build/libs/*.jar 查找。
RUN ./gradlew --no-daemon bootJar \
    && cp "$(find build/libs -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' | head -n 1)" /workspace/app.jar

# ── 运行阶段 ──────────────────────────────────────────────────────────────
FROM ${RUNTIME_IMAGE}

# tzdata：日志/排障时 TZ=Asia/Shanghai 才有意义（JVM 自身用 -Duser.timezone）
RUN apk add --no-cache tzdata \
    && addgroup -S erp \
    && adduser -S -G erp -h /app erp

WORKDIR /app
COPY --from=build /workspace/app.jar /app/app.jar
RUN chown -R erp:erp /app

USER erp
EXPOSE 8080
STOPSIGNAL SIGTERM

# MaxRAMPercentage 让堆随容器内存伸缩；OOM 直接退出交给 restart 策略，避免半死状态
ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8"

HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=5 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/actuator/health || exit 1

# 用 sh -c 展开 JAVA_OPTS；exec 让 java 成为 PID 1，能正确接收 SIGTERM
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
