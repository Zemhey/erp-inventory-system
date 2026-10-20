plugins {
    java
    jacoco
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.erp"
version = "0.1.0-M0"

/**
 * ★ 覆盖 Spring Boot BOM 管理的 Testcontainers 版本。
 *
 * <p><b>为什么必须覆盖</b>：Spring Boot 3.3.5 的 BOM 把 Testcontainers 锁在 1.19.8，
 * 其内置 docker-java 3.3.6 以过旧的 Docker API 版本访问守护进程。
 * 本机 Docker Desktop 29.5.2 的服务端 API 版本为 1.54，对过旧版本直接返回 HTTP 400，
 * 表现为 Testcontainers 报 "Could not find a valid Docker environment" —— 这个报错极具误导性，
 * 实际与"找不到 Docker"毫无关系。
 *
 * <p><b>为什么不用 resolutionStrategy.force</b>：本项目用了
 * io.spring.dependency-management 插件，其 dependencyManagement 优先级高于
 * Gradle 的 force（实测 force 只对传递依赖 docker-java 生效，对 testcontainers 本身无效）。
 * 正确做法是通过额外的项目属性覆盖 BOM 里的同名版本属性。
 */
ext["testcontainers.version"] = "1.21.4"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    // 国内镜像优先（加速），mavenCentral 兜底
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    maven { url = uri("https://maven.aliyun.com/repository/spring") }
    mavenCentral()
}

/**
 * 依赖版本强制解析（docker-java）。
 *
 * <p>与 {@code ext["testcontainers.version"]} 配合：Testcontainers 1.21.4 自带 docker-java 3.4.x，
 * 这里再显式钉住，避免 Gradle 解析到旧版本导致 API 版本协商再次失败。
 */
configurations.all {
    resolutionStrategy {
        force("com.github.docker-java:docker-java-api:3.4.1")
        force("com.github.docker-java:docker-java-transport:3.4.1")
        force("com.github.docker-java:docker-java-transport-zerodep:3.4.1")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // @RequirePermission 的切面实现需要 AOP 支持（spring-boot-starter-aop 引入 aspectjweaver）
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // ── IAM（FR-IAM-01/02）──
    // spring-boot-starter-security：提供过滤器链与密码编码器
    // jjwt：签发/校验JWT。选它而非自写 HS256，是因为密钥轮换、时钟偏移、
    //       算法混淆攻击（alg=none）这些细节自己写几乎必然出错。
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    // ArchUnit：用测试守住架构约束（计划书 §3.3、S7）
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

/**
 * 统一测试执行配置。
 *
 * <p>Testcontainers 需要在<b>测试 JVM 进程</b>里拿到 DOCKER_HOST；
 * 用 {@code environment(...)} 注入，而不是依赖父进程环境变量继承。
 */

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // 并发防超卖测试会产生大量连接，给足内存
    maxHeapSize = "1g"
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }

    /**
     * ★ 中文路径兼容（Windows 必读）
     *
     * <p>问题：Gradle 在 Windows 上会以系统 ANSI 代码页（本机为 936/GBK）编码
     * 传给 test worker 的 classpath。当项目路径含中文（如 `自选`）时，
     * worker 收到的路径字节与 JVM 以 UTF-8 解析类文件的方式不一致，
     * 结果测试类全部报 {@code ClassNotFoundException}，
     * 而错误信息极具误导性（看起来像"类没编译"）。
     *
     * <p>解决：显式让 Gradle 守护进程与测试 JVM 都使用 UTF-8：
     * {@code file.encoding} 控制 JVM 默认字符集，{@code sun.jnu.encoding}
     * 控制文件名/路径编码 —— 两者都要设，只设一个无效。
     */
    jvmArgs("-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8", "-Duser.language=en", "-Duser.country=US")

    // ── Windows + Docker Desktop 下的 Testcontainers 配置 ──
    // ① 显式指定 Docker 端点，避免 Testcontainers 在新版 Docker Desktop 上自动发现失败。
    val dockerHost = System.getenv("DOCKER_HOST")
        ?: "npipe:////./pipe/dockerDesktopLinuxEngine"
    environment("DOCKER_HOST", dockerHost)
    // ② ★ 必须用 IPv4 回环地址，不能用 localhost。
    //    本机 localhost 解析到 ::1（IPv6），而 Docker Desktop 的端口映射只监听 IPv4，
    //    导致 Testcontainers 连 Ryuk / 映射端口时失败（表现为
    //    "Could not connect to Ryuk at host.docker.internal:6664"）。
    environment("TESTCONTAINERS_HOST_OVERRIDE", "127.0.0.1")
    // ③ Docker 29 会拒绝过旧的 API 版本（返回 HTTP 400），显式钉住受支持的版本
    environment("DOCKER_API_VERSION", "1.54")
}

jacoco {
    toolVersion = "0.8.12"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

/**
 * 覆盖率门禁。M0 阶段只覆盖一条纵向切片，阈值先设低，
 * 随 M1 功能落地逐步提高（见 docs/06-测试策略.md）。
 */
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.30".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
