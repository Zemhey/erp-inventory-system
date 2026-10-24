package com.erp.support;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * 按 traceId 抓取日志行的测试工具（FR-SYS-08 的"链路断言"）。
 *
 * <p><b>为什么需要它</b>：验证"traceId 贯穿日志"不能靠"看代码里有没有
 * {@code MDC.put}"——那是实现细节，不是行为。真正的断言是：
 * <b>拿着一个 traceId，能把这次请求产生的日志全部捞出来</b>。
 * 这也是排障时人真正会做的事，所以测试就该按这个方式写。
 *
 * <p>做法：临时往根 logger 挂一个 {@link ListAppender}，执行一段动作，
 * 然后只保留 {@code MDC.traceId} 与目标值相同的日志行。
 * 抓完立刻摘下 appender —— 否则它会吃掉后续所有测试的日志并泄漏内存。
 */
public final class TraceLogCapture {

    private TraceLogCapture() {
    }

    /**
     * 执行 {@code action}，返回其中 {@code MDC.traceId == traceId} 的日志行。
     *
     * @param action  要执行的动作（通常是发一次 HTTP 请求）
     * @param traceId 目标 traceId
     */
    public static List<String> capture(Callable<?> action, String traceId) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.addAppender(appender);
        try {
            action.call();
        } catch (Exception ex) {
            throw new IllegalStateException("抓取日志时动作失败", ex);
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }

        List<String> lines = new ArrayList<>();
        for (ILoggingEvent event : appender.list) {
            String mdcTrace = event.getMDCPropertyMap().get("traceId");
            if (mdcTrace != null && mdcTrace.equals(traceId)) {
                lines.add(event.getFormattedMessage());
            }
        }
        return lines;
    }
}
