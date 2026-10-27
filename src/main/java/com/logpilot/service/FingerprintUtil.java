package com.logpilot.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;

/**
 * 异常指纹：相同根因的异常（异常类 + 归一化消息模板 + 顶层堆栈）归为一组。
 */
public final class FingerprintUtil {

    private FingerprintUtil() {
    }

    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern IP = Pattern.compile("\\b\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?");
    private static final Pattern HEX = Pattern.compile("\\b[0-9a-fA-F]{8,}\\b");
    private static final Pattern NUM = Pattern.compile("(?<![0-9A-Za-z])\\d+(?:\\.\\d+)?");
    private static final Pattern QUOTED = Pattern.compile("\"[^\"]*\"|'[^']*'");

    /** 提取顶层业务堆栈帧：第一个 at com.xxx.Yyy.zzz( —— 排除 JDK/常见框架包。兼容 name<> 形式。 */
    private static final Pattern AT_FRAME = Pattern.compile("(?m)^\\s*at\\s+([\\w$.]+)\\.([\\w$]+(?:<>)?)\\(");
    private static final Pattern JDK_OR_FRAMEWORK = Pattern.compile(
            "^(java\\.|javax\\.|jakarta\\.|jdk\\.|sun\\.|com\\.sun\\.|org\\.springframework\\.|org\\.apache\\.(catalina|tomcat|http|ibatis)|org\\.hibernate\\.|io\\.netty\\.|reactor\\.|org\\.jboss\\.)");

    public static String exceptionClass(String message, String stackTrace) {
        if (message != null) {
            java.util.regex.Matcher m = Pattern.compile(
                    "([a-zA-Z_$][\\w$]*(?:\\.[a-zA-Z_$][\\w$]*)*(?:Exception|Error))(?::|\\s|$)").matcher(message);
            if (m.find()) return m.group(1);
        }
        if (stackTrace != null) {
            java.util.regex.Matcher m = Pattern.compile("Caused by:\\s*([\\w$.]+)").matcher(stackTrace);
            if (m.find()) return m.group(1);
            m = Pattern.compile("^\\s*([\\w$.]+(?:Exception|Error)):\\s").matcher(stackTrace);
            if (m.find()) return m.group(1);
        }
        return message != null && message.length() > 2 ? "GeneralError" : "Unknown";
    }

    /** 归一化消息模板：数字/ID/UUID/IP/引号串替换为占位符。 */
    public static String normalizeTemplate(String message) {
        if (message == null) return "";
        String s = message.split("\n")[0];
        s = QUOTED.matcher(s).replaceAll("\"S\"");
        s = UUID.matcher(s).replaceAll("UUID");
        s = IP.matcher(s).replaceAll("IP");
        s = HEX.matcher(s).replaceAll("H");
        s = NUM.matcher(s).replaceAll("N");
        s = s.trim();
        if (s.length() > 200) s = s.substring(0, 200);
        return s;
    }

    /** 顶层业务堆栈帧 class#method（跳过 JDK/框架帧，定位业务代码）。 */
    public static String topFrame(String stackTrace) {
        if (stackTrace == null || stackTrace.isEmpty()) return "";
        java.util.regex.Matcher m = AT_FRAME.matcher(stackTrace);
        while (m.find()) {
            String cls = m.group(1);
            if (!JDK_OR_FRAMEWORK.matcher(cls).find()) {
                return cls + "#" + m.group(2);
            }
        }
        return "";
    }

    public static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String fingerprint(String exceptionClass, String template, String topFrame) {
        return md5(nullSafe(exceptionClass) + "§" + nullSafe(template) + "§" + nullSafe(topFrame));
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
