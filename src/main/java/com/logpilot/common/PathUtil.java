package com.logpilot.common;

import java.util.regex.Pattern;

/**
 * 日志路径解析：支持 glob 通配符（* ?），将路径拆分为 父目录 + 文件名正则。
 * 统一使用 '/' 作为分隔符（Windows 本地路径也兼容）。
 */
public final class PathUtil {

    private PathUtil() {
    }

    public static String normalize(String path) {
        return path == null ? "" : path.trim().replace('\\', '/');
    }

    /** 返回父目录（不含通配符部分）。 */
    public static String parentDir(String normalizedPath) {
        int idx = normalizedPath.lastIndexOf('/');
        if (idx < 0) return ".";
        String parent = normalizedPath.substring(0, idx);
        return parent.isEmpty() ? "/" : parent;
    }

    /** 返回文件名部分（可能含通配符）。 */
    public static String fileName(String normalizedPath) {
        int idx = normalizedPath.lastIndexOf('/');
        return idx < 0 ? normalizedPath : normalizedPath.substring(idx + 1);
    }

    public static boolean hasWildcard(String name) {
        return name.contains("*") || name.contains("?");
    }

    /**
     * glob -> 正则（仅作用于文件名，不跨目录）。
     * 另外总是隐式支持目录形式（logPath 为目录时匹配 *.log）。
     */
    public static Pattern globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> sb.append("[^/]*");
                case '?' -> sb.append("[^/]");
                case '.' -> sb.append("\\.");
                default -> {
                    if ("\\^$.|+()[]{}".indexOf(c) >= 0) sb.append('\\');
                    sb.append(c);
                }
            }
        }
        return Pattern.compile(sb.toString());
    }
}
