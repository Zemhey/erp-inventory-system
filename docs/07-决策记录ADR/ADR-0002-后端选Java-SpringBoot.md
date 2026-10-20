# ADR-0002: 后端选 Java 17 + Spring Boot 3

- 状态：已采纳
- 日期：—

## 背景

课程允许后端用 Java / Node / Python 及其框架。选型需同时服务两个目标：**做商业级系统** + **支撑自动化测试求职履历**。

## 决策

**Java 17 (LTS) + Spring Boot 3.x + Gradle (Kotlin DSL)**。

## 理由

| 比较项 | Java + Spring Boot 3 | Python + FastAPI | Node + NestJS |
|---|---|---|---|
| 自动化测试岗 JD 出现率 | **最高** | 高 | 中 |
| "企业系统"气质 | 强（事务/锁/消息/权限生态成熟） | 中 | 中弱 |
| 测试栈一致性 | JUnit5+Testcontainers+REST Assured+Playwright-Java+Appium-Java 一条线 | pytest 生态最丰富 | 一般 |
| 上手速度 | 慢（样板代码多） | 快 | 中 |

**决定性理由**：目标的终点是"自动化测试岗位"，而 Java 在银行/金融/大厂测开 JD 中出现率最高。技术栈与求职方向一致，项目代码本身就是面试素材。

**为什么不用 Node 做后端**：Node 唯一优势是让后端/前端/契约/Playwright 同语言，但企业系统气质偏弱，且对测开岗匹配度不如 Java。

**为什么用 Gradle 而非 Maven**：增量构建更快；依赖树导出更适合生成 `index/deps.json`。

## 后果

- ✅ 求职匹配度最高；企业级生态成熟
- ✅ 正式"事务 + 行锁/乐观锁 + 消息幂等"这些商业系统特征最自然
- ⚠️ 样板代码多、上手慢 → 用代码生成/模板缓解；接受这个代价
- ⚠️ JVM 启动较慢 → Testcontainers 共享容器缓解测试耗时
