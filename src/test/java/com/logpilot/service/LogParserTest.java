package com.logpilot.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 日志解析器核心逻辑测试。
 */
class LogParserTest {

    private final LogParser parser = new LogParser();

    @Test
    void parseStandardLogbackMultiLine() {
        String log = """
                2026-10-26 10:00:00.123 [http-nio-8080-exec-3] INFO  com.demo.App - started
                2026-10-26 10:00:01.456 [http-nio-8080-exec-3] ERROR com.demo.OrderService - create failed orderId=88123
                java.lang.NullPointerException: Cannot invoke \"String.length()\" because \"name\" is null
                \tat com.demo.OrderService.create(OrderService.java:42)
                \tat java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)
                Caused by: java.lang.IllegalStateException: customer missing
                \tat com.demo.OrderService.findCustomer(OrderService.java:88)
                2026-10-26 10:00:02.000 [main] INFO  com.demo.App - done
                """;

        List<LogParser.ParsedRecord> records = parser.parseAuto(log.lines().map(String::toString).toList());

        assertEquals(3, records.size());

        LogParser.ParsedRecord err = records.get(1);
        assertTrue(err.isError());
        assertEquals("ERROR", err.getLevel());
        assertEquals("java.lang.NullPointerException", err.getExceptionClass());
        assertNotNull(err.getTimestamp());
        assertEquals(LocalDateTime.of(2026, 10, 26, 10, 0, 1, 456_000_000), err.getTimestamp());
        assertTrue(err.getStackTrace().contains("at com.demo.OrderService.create"));
        assertTrue(err.getStackTrace().contains("Caused by:"));
        assertTrue(err.getRaw().split("\n").length >= 4, "多行堆栈应合并为一条记录");

        assertFalse(records.get(0).isError());
        assertFalse(records.get(2).isError());
    }

    @Test
    void fingerprintAggregatesSimilarErrors() {
        String msg1 = "查询用户失败 userId=1001 耗时 300ms";
        String msg2 = "查询用户失败 userId=98765 耗时 4210ms";
        String stack = "\tat com.demo.user.UserService.query(UserService.java:77)\n";

        String fp1 = FingerprintUtil.fingerprint("java.lang.RuntimeException",
                FingerprintUtil.normalizeTemplate(msg1), FingerprintUtil.topFrame(stack));
        String fp2 = FingerprintUtil.fingerprint("java.lang.RuntimeException",
                FingerprintUtil.normalizeTemplate(msg2), FingerprintUtil.topFrame(stack));
        assertEquals(fp1, fp2, "数字不同的同构消息应聚为一组");

        String fp3 = FingerprintUtil.fingerprint("java.sql.SQLException",
                FingerprintUtil.normalizeTemplate(msg1), FingerprintUtil.topFrame(stack));
        assertNotEquals(fp1, fp3, "不同异常类应分开");
    }

    @Test
    void topFrameSkipsFrameworkPackages() {
        String stack = """
                \tat java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)
                \tat org.springframework.web.method.support.InvocableHandlerMethod.doInvoke(InvocableHandlerMethod.java:205)
                \tat com.demo.order.OrderController.createOrder(OrderController.java:75)
                """;
        assertEquals("com.demo.order.OrderController#createOrder", FingerprintUtil.topFrame(stack));
    }

    @Test
    void rawModeDetectsKeywordLines() {
        List<LogParser.ParsedRecord> records = parser.parseRaw(List.of(
                "2026-10-26 10:00:00 something Exception: boom",
                "2026-10-26 10:00:01 all good"
        ));
        assertEquals(2, records.size());
        assertTrue(records.get(0).isError());
        assertFalse(records.get(1).isError());
    }
}
