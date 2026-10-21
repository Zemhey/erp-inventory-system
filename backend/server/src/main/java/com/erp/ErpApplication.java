package com.erp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 进销存系统启动类。
 *
 * <p>架构：单体模块化（Modular Monolith，见 docs/ADR-0006）。
 * 模块边界由包结构表达，跨模块只允许通过应用服务接口调用，
 * 由 {@code ModuleBoundaryTest}（ArchUnit，M1 引入）在 CI 中强制。
 *
 * <p>模块划分（docs/03-架构与整体结构.md）：
 * iam / mdm / inv / pur / sal / rma / set / rpt / sys / shared
 */
@SpringBootApplication
public class ErpApplication {

    public static void main(String[] args) {
        SpringApplication.run(ErpApplication.class, args);
    }
}
