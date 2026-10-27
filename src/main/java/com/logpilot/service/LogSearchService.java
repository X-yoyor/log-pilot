package com.logpilot.service;

import com.logpilot.common.PathUtil;
import com.logpilot.domain.LogSource;
import com.logpilot.domain.ServerNode;
import com.logpilot.repo.ServerNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 原始日志检索：按日志源重新读取文件内容，关键字匹配 + 前后 N 行上下文。
 * 单文件默认最多读取 32MB（从头），超出部分标记截断；文件通常随轮转保持在该量级。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogSearchService {

    private static final long MAX_BYTES_PER_FILE = 32L * 1024 * 1024;

    private final CollectService collectService;
    private final ServerNodeRepository serverRepo;

    public record SearchHit(String filePath, int lineNo, String matchHtml,
                            List<String> before, List<String> after, LocalDateTime ts) {
    }

    public record SearchResult(int filesScanned, long bytesScanned, boolean fileTruncated,
                               List<SearchHit> hits, boolean hitTruncated) {
    }

    public SearchResult search(LogSource source, String keyword, boolean ignoreCase,
                               int contextLines, int maxHits, LocalDateTime from, LocalDateTime to) {
        if (keyword == null || keyword.isBlank()) {
            throw new IllegalArgumentException("关键字不能为空");
        }
        ServerNode server = null;
        if (!source.isLocal()) {
            if (source.getServerId() == null) throw new IllegalArgumentException("SSH 日志源缺少服务器配置");
            server = serverRepo.findById(source.getServerId())
                    .orElseThrow(() -> new IllegalArgumentException("关联服务器不存在"));
        }

        List<CollectService.LogFile> files = collectService.resolveFiles(source, server);
        Pattern kw = Pattern.compile(Pattern.quote(keyword.trim()),
                ignoreCase ? Pattern.CASE_INSENSITIVE : 0);
        Charset charset = resolveCharset(source.getEncoding());

        List<SearchHit> hits = new ArrayList<>();
        int filesScanned = 0;
        long bytesScanned = 0;
        boolean fileTruncated = false;
        boolean hitTruncated = false;

        for (CollectService.LogFile f : files) {
            if (hits.size() >= maxHits) {
                hitTruncated = true;
                break;
            }
            filesScanned++;
            long cap = Math.min(f.size(), MAX_BYTES_PER_FILE);
            if (cap < f.size()) fileTruncated = true;
            if (cap <= 0) continue;

            byte[] data;
            try {
                data = collectService.readChunk(server, f.path(), 0, cap);
            } catch (Exception e) {
                log.warn("检索读取文件失败 {}: {}", f.path(), e.getMessage());
                continue;
            }
            bytesScanned += data.length;

            String content = new String(data, charset);
            String[] lines = content.split("\r?\n", -1);
            if (lines.length > 0 && lines[lines.length - 1].isEmpty()) {
                lines = java.util.Arrays.copyOf(lines, lines.length - 1);
            }

            for (int i = 0; i < lines.length && hits.size() < maxHits; i++) {
                Matcher m = kw.matcher(lines[i]);
                if (!m.find()) continue;

                LocalDateTime ts = LogParser.extractTimestamp(lines[i]);
                if (from != null && ts != null && ts.isBefore(from)) continue;
                if (to != null && ts != null && !ts.isBefore(to)) continue;

                List<String> before = new ArrayList<>();
                for (int b = Math.max(0, i - contextLines); b < i; b++) before.add(lines[b]);
                List<String> after = new ArrayList<>();
                for (int a = i + 1; a <= Math.min(lines.length - 1, i + contextLines); a++) after.add(lines[a]);

                hits.add(new SearchHit(f.path(), i + 1, highlight(lines[i], kw), before, after, ts));
            }
        }
        return new SearchResult(filesScanned, bytesScanned, fileTruncated, hits, hitTruncated);
    }

    /** 关键字高亮：先分段转义再拼 <mark>，保证 XSS 安全。 */
    static String highlight(String line, Pattern kw) {
        Matcher m = kw.matcher(line);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(escapeRange(line, last, m.start()));
            sb.append("<mark>").append(escapeRange(line, m.start(), m.end())).append("</mark>");
            last = m.end();
            if (m.end() == m.start()) break; // 空匹配防御
        }
        sb.append(escapeRange(line, last, line.length()));
        return sb.toString();
    }

    private static String escapeRange(String s, int start, int end) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    static String normalizePath(String p) {
        return PathUtil.normalize(p);
    }

    private static Charset resolveCharset(String encoding) {
        if (encoding == null || encoding.isBlank()) return StandardCharsets.UTF_8;
        try {
            return Charset.forName(encoding.trim());
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }
}
