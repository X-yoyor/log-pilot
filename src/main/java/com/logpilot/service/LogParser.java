package com.logpilot.service;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志解析器：
 * <ol>
 *   <li>把文本块切分为"记录"：以时间戳开头的行为记录起点，后续行（at xxx / Caused by / tab 缩进等）
 *       归并为同一记录的堆栈；</li>
 *   <li>识别级别、线程、logger、消息；</li>
 *   <li>判断是否异常记录（ERROR/FATAL/Exception/Caused by 等）。</li>
 * </ol>
 * 兼容 logback/log4j2 默认 pattern 及大多数自定义格式；RAW 模式逐行处理。
 */
@Component
public class LogParser {

    /** 记录起点：yyyy-MM-dd HH:mm:ss[.SSS] 开头（兼容 ISO 的 T 分隔）。 */
    private static final Pattern RECORD_START = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}");

    private static final Pattern LEVEL = Pattern.compile(
            "(?i)(?:^|[\\s\\[])(TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL|SEVERE|CRITICAL)(?=$|[\\s\\]:])");

    private static final Pattern THREAD = Pattern.compile("\\[([^\\[\\]]{1,80})]");

    private static final Pattern LOGGER = Pattern.compile(
            "(?:^|\\s)([a-zA-Z_$][\\w$]*(?:\\.[a-zA-Z_$][\\w$]*)*)\\.[A-Z][\\w$]*");

    private static final Pattern EXCEPTION_CLASS = Pattern.compile(
            "([a-zA-Z_$][\\w$]*(?:\\.[a-zA-Z_$][\\w$]*)*(?:Exception|Error))");

    private static final Pattern CAUSED_BY = Pattern.compile("Caused by:\\s*([\\w$.]+)");

    private static final Pattern AT_FRAME = Pattern.compile("^\\s*at\\s+([\\w$.]+)\\.([\\w$]+<>?]*)\\(");

    private static final DateTimeFormatter[] TS_FORMATS = new DateTimeFormatter[]{
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
    };

    @Getter
    @Setter
    @ToString
    public static class ParsedRecord {
        private LocalDateTime timestamp;
        private String level;
        private String thread;
        private String logger;
        private String message;
        private String stackTrace;
        private String raw;
        private String exceptionClass;
        private boolean error;

        public boolean isError() {
            return error;
        }
    }

    /** AUTO 模式：合并多行记录。 */
    public List<ParsedRecord> parseAuto(List<String> lines) {
        List<ParsedRecord> records = new ArrayList<>();
        StringBuilder cur = null;
        for (String line : lines) {
            if (line == null) continue;
            if (RECORD_START.matcher(line).find(0) && looksLikeStart(line)) {
                if (cur != null) addIfPresent(records, cur.toString());
                cur = new StringBuilder(line);
            } else if (cur != null) {
                cur.append('\n').append(line);
            } else {
                cur = new StringBuilder(line);
            }
        }
        if (cur != null) addIfPresent(records, cur.toString());
        return records;
    }

    /** RECORD_START 命中且位置在行首附近（前 5 个字符内），避免把消息里的日期当成起点。 */
    private boolean looksLikeStart(String line) {
        Matcher m = RECORD_START.matcher(line);
        return m.find(0) && m.start() <= 5;
    }

    /** RAW 模式：每行一条记录。 */
    public List<ParsedRecord> parseRaw(List<String> lines) {
        List<ParsedRecord> records = new ArrayList<>();
        for (String line : lines) {
            if (line == null || line.isBlank()) continue;
            ParsedRecord r = buildRecord(line);
            if (r != null) records.add(r);
        }
        return records;
    }

    private void addIfPresent(List<ParsedRecord> records, String text) {
        ParsedRecord r = buildRecord(text);
        if (r != null) records.add(r);
    }

    private ParsedRecord buildRecord(String raw) {
        if (raw.isBlank()) return null;
        ParsedRecord r = new ParsedRecord();
        r.setRaw(raw);

        int firstNl = raw.indexOf('\n');
        String head = firstNl < 0 ? raw : raw.substring(0, firstNl);
        String stack = firstNl < 0 ? "" : raw.substring(firstNl + 1);
        r.setStackTrace(stack.isEmpty() ? null : stack);

        // 时间戳（含可选毫秒扩展 .SSS / ,SSS）
        Matcher ts = RECORD_START.matcher(head);
        int tsStart = -1, tsEnd = -1;
        if (ts.find(0) && ts.start() <= 5) {
            tsStart = ts.start();
            tsEnd = ts.end();
            String tsText = ts.group();
            Matcher ms = Pattern.compile("[.,]\\d{1,3}").matcher(head);
            if (ms.find(tsEnd) && ms.start() == tsEnd) {
                tsText += ms.group();
                tsEnd = ms.end();
            }
            r.setTimestamp(parseTs(tsText.replace("T", " ")));
        }

        // 级别
        String level = null;
        Matcher lv = LEVEL.matcher(head);
        int levelEnd = -1;
        while (lv.find()) {
            if (lv.start() < tsEnd) continue;
            level = lv.group(1).toUpperCase();
            if ("WARNING".equals(level)) level = "WARN";
            levelEnd = lv.end();
            break;
        }
        r.setLevel(level);

        // 线程 [xxx]
        Matcher th = THREAD.matcher(head);
        if (th.find(Math.max(0, tsEnd)) && th.start() > tsEnd) {
            r.setThread(truncate(th.group(1), 100));
        }

        // 消息：优先取 " - " 之后；否则取级别词之后；否则整行去掉时间戳
        String message;
        int dashIdx = head.indexOf(" - ");
        if (dashIdx >= 0 && (levelEnd < 0 || dashIdx > levelEnd)) {
            message = head.substring(dashIdx + 3);
        } else if (levelEnd >= 0) {
            message = head.substring(Math.min(levelEnd + 1, head.length()));
        } else if (tsEnd >= 0 && tsEnd < head.length()) {
            message = head.substring(tsEnd).replaceFirst("^\\s*[-:]?\\s*", "");
        } else {
            message = head;
        }
        r.setMessage(truncate(message.trim(), 1800));

        // logger：级别与消息之间的类名样 token
        int searchFrom = levelEnd >= 0 ? levelEnd : Math.max(0, tsEnd);
        int searchTo = dashIdx >= 0 ? dashIdx : head.length();
        if (searchTo > searchFrom) {
            Matcher lg = LOGGER.matcher(head.substring(searchFrom, searchTo));
            if (lg.find()) r.setLogger(truncate(lg.group(1), 200));
        }

        // 异常类：主异常优先（消息/首行/堆栈首行声明），其次 Caused by
        String excClass = null;
        Matcher ec = EXCEPTION_CLASS.matcher(message);
        if (ec.find()) excClass = ec.group(1);
        if (excClass == null) {
            ec = EXCEPTION_CLASS.matcher(head);
            if (ec.find()) excClass = ec.group(1);
        }
        if (excClass == null && !stack.isEmpty()) {
            String stackFirstLine = stack.split("\n", 2)[0].trim();
            ec = EXCEPTION_CLASS.matcher(stackFirstLine);
            if (ec.find()) excClass = ec.group(1);
        }
        if (excClass == null) {
            Matcher cb = CAUSED_BY.matcher(raw);
            if (cb.find()) excClass = cb.group(1);
        }
        r.setExceptionClass(excClass);

        // 是否异常记录
        boolean levelError = "ERROR".equals(level) || "FATAL".equals(level)
                || "SEVERE".equals(level) || "CRITICAL".equals(level);
        boolean looksException = excClass != null
                || stack.contains("Caused by:")
                || (message.contains("Exception") && message.contains(":"))
                || Pattern.compile("(?m)^\\s*at\\s+[\\w$.]+\\(").matcher(stack).find();
        r.setError(levelError || looksException);
        return r;
    }

    /** 完整时间戳（含可选毫秒），供日志检索等外部使用。 */
    private static final Pattern TS_FULL = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,3})?");

    /** 从一行文本中提取日志时间戳（仅当出现在行首附近），失败返回 null。 */
    public static LocalDateTime extractTimestamp(String line) {
        if (line == null) return null;
        Matcher m = TS_FULL.matcher(line);
        if (m.find() && m.start() <= 5) {
            return parseTsStatic(m.group().replace('T', ' ').replace(',', '.'));
        }
        return null;
    }

    private static LocalDateTime parseTsStatic(String s) {
        for (DateTimeFormatter f : TS_FORMATS) {
            try {
                return LocalDateTime.parse(s, f);
            } catch (Exception ignore) {
                // try next
            }
        }
        return null;
    }

    private LocalDateTime parseTs(String s) {
        for (DateTimeFormatter f : TS_FORMATS) {
            try {
                return LocalDateTime.parse(s, f);
            } catch (Exception ignore) {
                // try next
            }
        }
        return null;
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
