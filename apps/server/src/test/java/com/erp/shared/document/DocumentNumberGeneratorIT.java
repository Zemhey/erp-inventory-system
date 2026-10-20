package com.erp.shared.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestDataFactory;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 单据号生成器集成测试（计划书 §3.2 坑点 1、§4.2 高价值用例 1）。
 *
 * <p>本类只测"取号"这一个动作，不测业务 —— 因为重号是**数据正确性问题**，
 * 一旦发生，下游所有"按单号反查"的追溯都会指向错误单据。
 *
 * <p>测试用真 PostgreSQL：{@code INSERT ... ON CONFLICT DO UPDATE ... RETURNING}
 * 的原子性依赖真实的行锁与唯一索引，H2 上测出来的"绿"没有意义。
 */
@SpringBootTest
@DisplayName("单据号 · 原子取号与并发唯一性")
class DocumentNumberGeneratorIT extends PostgresTestBase {

    @Autowired
    private DocumentNumberGenerator generator;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        new TestDataFactory(jdbc).truncateAll();
    }

    @Test
    @CaseId("TC-DOC-007")
    @DisplayName("单号格式：前缀 + yyyyMMdd + 4 位流水，按天重置")
    void format_shouldBePrefixDateSerial() {
        LocalDate day = LocalDate.of(2025, 6, 1);

        assertThat(generator.next(DocumentNumberGenerator.DocType.PURCHASE_ORDER, day))
                .isEqualTo("PO20250601-0001");
        assertThat(generator.next(DocumentNumberGenerator.DocType.PURCHASE_ORDER, day))
                .isEqualTo("PO20250601-0002");
        assertThat(generator.next(DocumentNumberGenerator.DocType.SALES_ORDER, day))
                .as("不同前缀各自独立计数")
                .isEqualTo("SO20250601-0001");
        assertThat(generator.next(DocumentNumberGenerator.DocType.PURCHASE_ORDER,
                day.plusDays(1)))
                .as("换一天必须从 0001 重新开始")
                .isEqualTo("PO20250602-0001");
    }

    @Test
    @CaseId("TC-DOC-008")
    @DisplayName("★ 并发建单不重号：50 线程同时取号，单号必须全不重复且恰好 50 个")
    void concurrentNext_mustNotProduceDuplicates() throws Exception {
        final int threads = 50;
        LocalDate day = LocalDate.of(2025, 6, 1);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch readyGate = new CountDownLatch(threads);
        List<String> numbers = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        List<Future<Void>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit((Callable<Void>) () -> {
                readyGate.countDown();
                startGate.await(15, TimeUnit.SECONDS);   // 同时起跑，制造真实竞争
                numbers.add(generator.next(DocumentNumberGenerator.DocType.PURCHASE_ORDER, day));
                return null;
            }));
        }
        assertThat(readyGate.await(20, TimeUnit.SECONDS)).isTrue();
        startGate.countDown();
        for (Future<Void> f : futures) {
            f.get(90, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();

        Set<String> distinct = new HashSet<>(numbers);
        assertThat(numbers).as("必须取到 50 个号，一个都不能丢").hasSize(threads);
        assertThat(distinct)
                .as("★ 单号必须全不重复 —— 这是 SELECT max(no)+1 写法必然失败的用例")
                .hasSize(threads);

        // 计数必须精确落在 50，说明没有任何一次"读到旧值再加一"
        Long currentNo = jdbc.queryForObject("""
                SELECT current_no FROM document_sequences
                 WHERE doc_prefix = 'PO' AND day_key = '20250601'
                """, Long.class);
        assertThat(currentNo).isEqualTo((long) threads);
    }

    @Test
    @CaseId("TC-DOC-009")
    @DisplayName("取号序列持久化在数据库中：不依赖进程内计数器（重启不重号）")
    void sequence_isPersistedInDatabase() {
        generator.next(DocumentNumberGenerator.DocType.PURCHASE_ORDER,
                LocalDate.of(2025, 6, 1));

        Integer rows = jdbc.queryForObject("""
                SELECT COUNT(*) FROM document_sequences
                 WHERE doc_prefix = 'PO' AND day_key = '20250601'
                """, Integer.class);
        assertThat(rows).as("序号必须落库，否则重启服务后重号").isEqualTo(1);
    }

    @Test
    @CaseId("TC-DOC-010")
    @DisplayName("流水号超过 4 位后自然进位，不被截断（第 10000 号）")
    void serial_beyondFourDigits_isNotTruncated() {
        jdbc.update("""
                INSERT INTO document_sequences (doc_prefix, day_key, current_no)
                VALUES ('PO', '20250601', 9999)
                """);

        assertThat(generator.next(DocumentNumberGenerator.DocType.PURCHASE_ORDER,
                LocalDate.of(2025, 6, 1))).isEqualTo("PO20250601-10000");
    }
}
