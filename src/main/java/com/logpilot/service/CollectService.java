package com.logpilot.service;

import com.logpilot.common.PathUtil;
import com.logpilot.domain.*;
import com.logpilot.repo.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 日志采集：按日志源配置，增量读取新增内容（字节偏移，只在完整行边界推进），
 * 解析后交给 AnalyzeService 入库聚合。支持本地文件与 SSH/SFTP 远端文件。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CollectService {

    private final LogSourceRepository sourceRepo;
    private final ServerNodeRepository serverRepo;
    private final SourceFileOffsetRepository offsetRepo;
    private final CollectLogRepository collectLogRepo;
    private final LogParser logParser;
    private final AnalyzeService analyzeService;
    private final AlertService alertService;
    private final SshService sshService;

    /** sourceId -> 正在采集标记，防止同一源并发重入 */
    private final ConcurrentHashMap<Long, Boolean> running = new ConcurrentHashMap<>();

    @Value("${logpilot.collect.max-chunk-bytes:16777216}")
    private long maxChunkBytes;

    @Value("${logpilot.collect.max-events-per-run:5000}")
    private int maxEventsPerRun;

    public boolean isRunning(Long sourceId) {
        return Boolean.TRUE.equals(running.get(sourceId));
    }

    /**
     * 同步执行一次采集。手动触发与调度共用。
     */
    public CollectLog collectNow(Long sourceId) {
        LogSource source = sourceRepo.findById(sourceId)
                .orElseThrow(() -> new IllegalArgumentException("日志源不存在: " + sourceId));
        if (running.putIfAbsent(sourceId, Boolean.TRUE) != null) {
            throw new IllegalStateException("该日志源正在采集中，请稍候");
        }
        try {
            return doCollect(source);
        } finally {
            running.remove(sourceId);
            LocalDateTime now = LocalDateTime.now();
            source.setLastCollectTime(now);
            int interval = Math.max(1, source.getIntervalMinutes() == null ? 5 : source.getIntervalMinutes());
            source.setNextCollectTime(now.plusMinutes(interval));
            sourceRepo.save(source);
        }
    }

    private CollectLog doCollect(LogSource source) {
        CollectLog cl = new CollectLog();
        cl.setSourceId(source.getId());
        cl.setSourceName(source.getName());
        cl.setStatus("OK");
        long start = System.currentTimeMillis();

        ServerNode server = null;
        if (!source.isLocal()) {
            if (source.getServerId() == null) {
                throw new IllegalArgumentException("SSH 模式必须选择服务器");
            }
            server = serverRepo.findById(source.getServerId())
                    .orElseThrow(() -> new IllegalArgumentException("关联服务器不存在"));
        }

        try {
            List<LogFile> files = resolveFiles(source, server);
            Charset charset = resolveCharset(source.getEncoding());
            boolean rawMode = "RAW".equalsIgnoreCase(source.getFormatType());

            long totalLines = 0;
            int totalEvents = 0;

            for (LogFile f : files) {
                try {
                    int[] result = collectFile(source, server, f, charset, rawMode);
                    totalLines += result[0];
                    totalEvents += result[1];
                    cl.setFiles(cl.getFiles() + 1);
                    if (totalEvents >= maxEventsPerRun) {
                        cl.setMessage("达到单次采集事件上限 " + maxEventsPerRun + "，剩余内容下次继续");
                        break;
                    }
                } catch (Exception e) {
                    log.warn("采集文件失败: {} ({})", f.path(), e.getMessage());
                    cl.setMessage((cl.getMessage() == null ? "" : cl.getMessage()) + "文件失败 " + f.path() + ": " + e.getMessage() + "; ");
                }
            }

            cl.setLines(totalLines);
            cl.setEvents(totalEvents);

            if (totalEvents > 0) {
                try {
                    alertService.evaluateAfterCollect(source);
                } catch (Exception e) {
                    log.warn("告警评估失败: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            cl.setStatus("FAIL");
            cl.setMessage(truncate(e.getMessage(), 1800));
        } finally {
            cl.setEndAt(LocalDateTime.now());
            cl.setCostMs(System.currentTimeMillis() - start);
            try {
                collectLogRepo.save(cl);
            } catch (Exception e) {
                log.error("保存采集记录失败", e);
            }
        }
        return cl;
    }

    /** 返回 [行数, 异常事件数]。 */
    private int[] collectFile(LogSource source, ServerNode server, LogFile f,
                              Charset charset, boolean rawMode) throws IOException {
        SourceFileOffset off = offsetRepo.findBySourceIdAndFilePath(source.getId(), f.path())
                .orElseGet(() -> {
                    SourceFileOffset o = new SourceFileOffset();
                    o.setSourceId(source.getId());
                    o.setFilePath(f.path());
                    o.setPosition(0L);
                    o.setFileSize(0L);
                    o.setTotalLines(0L);
                    return o;
                });

        // 轮转检测：文件比上次小 -> 从头采集
        if (f.size() < off.getPosition()) {
            off.setPosition(0L);
        }
        if (f.size() <= off.getPosition()) {
            // 无新增
            off.setFileSize(f.size());
            off.setLastReadAt(LocalDateTime.now());
            offsetRepo.save(off);
            return new int[]{0, 0};
        }

        long want = Math.min(f.size() - off.getPosition(), maxChunkBytes);
        byte[] data = readChunk(server, f.path(), off.getPosition(), want);

        // 只推进到最后一个完整行
        int lastNl = lastIndexOf(data, (byte) '\n');
        int usableLen = lastNl >= 0 ? lastNl + 1 : data.length;
        long newPosition = off.getPosition() + usableLen;

        String text = new String(data, 0, usableLen, charset);
        List<String> lines = splitLines(text);
        int lineCount = lines.size();

        List<LogParser.ParsedRecord> records = rawMode
                ? logParser.parseRaw(lines)
                : logParser.parseAuto(lines);

        List<LogParser.ParsedRecord> errors = new ArrayList<>();
        for (LogParser.ParsedRecord r : records) {
            if (r.isError()) errors.add(r);
        }
        int saved = 0;
        if (!errors.isEmpty()) {
            int cap = Math.min(errors.size(), maxEventsPerRun);
            saved = analyzeService.process(errors.subList(0, cap), source,
                    server == null ? null : server.getId(), f.path());
        }

        off.setPosition(newPosition);
        off.setFileSize(f.size());
        off.setTotalLines((off.getTotalLines() == null ? 0 : off.getTotalLines()) + lineCount);
        off.setLastReadAt(LocalDateTime.now());
        offsetRepo.save(off);

        return new int[]{lineCount, saved};
    }

    /** 解析日志源配置为目标文件列表（本机或远端），供采集与日志检索共用。 */
    public List<LogFile> resolveFiles(LogSource source, ServerNode server) {
        String path = PathUtil.normalize(source.getLogPath());
        String parent = PathUtil.parentDir(path);
        String name = PathUtil.fileName(path);

        Pattern regex = PathUtil.hasWildcard(name)
                ? PathUtil.globToRegex(name)
                : null;

        if (source.isLocal()) {
            // logPath 为已存在的目录（无通配符）时，采集目录下 *.log
            if (regex == null && !name.isEmpty()) {
                File asDir = new File(parent, name);
                if (asDir.isDirectory()) {
                    return resolveLocal(asDir.getAbsolutePath(), "", null);
                }
            }
            return resolveLocal(parent, name, regex);
        }
        List<SshService.RemoteFile> remote = sshService.listFiles(server, parent, regex);
        List<LogFile> result = new ArrayList<>();
        for (SshService.RemoteFile r : remote) {
            result.add(new LogFile(r.path(), r.size()));
        }
        return result;
    }

    private List<LogFile> resolveLocal(String parent, String name, Pattern regex) {
        File dir = new File(parent);
        if (!dir.isDirectory()) {
            throw new IllegalArgumentException("目录不存在: " + parent);
        }
        File[] all = dir.listFiles();
        List<LogFile> result = new ArrayList<>();
        if (all == null) return result;
        for (File f : all) {
            if (!f.isFile()) continue;
            boolean match;
            if (regex != null) {
                match = regex.matcher(f.getName()).matches();
            } else if (name.isEmpty()) {
                match = f.getName().toLowerCase(Locale.ROOT).endsWith(".log");
            } else {
                match = f.getName().equals(name);
            }
            if (match) result.add(new LogFile(PathUtil.normalize(f.getAbsolutePath()), f.length()));
        }
        return result;
    }

    /** 读取文件 [offset, offset+maxBytes) 区间内容，本机或远端。 */
    public byte[] readChunk(ServerNode server, String path, long offset, long maxBytes) throws IOException {
        if (server == null) {
            try (RandomAccessFile raf = new RandomAccessFile(path, "r")) {
                raf.seek(offset);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                long remaining = maxBytes;
                int n;
                while (remaining > 0 && (n = raf.read(buf, 0, (int) Math.min(buf.length, remaining))) > 0) {
                    bos.write(buf, 0, n);
                    remaining -= n;
                }
                return bos.toByteArray();
            }
        }
        return sshService.readChunk(server, path, offset, maxBytes);
    }

    private static int lastIndexOf(byte[] data, byte b) {
        for (int i = data.length - 1; i >= 0; i--) {
            if (data[i] == b) return i;
        }
        return -1;
    }

    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                int end = i;
                if (end > start && text.charAt(end - 1) == '\r') end--;
                lines.add(text.substring(start, end));
                start = i + 1;
            }
        }
        if (start < text.length()) lines.add(text.substring(start));
        return lines;
    }

    private static Charset resolveCharset(String encoding) {
        if (encoding == null || encoding.isBlank()) return StandardCharsets.UTF_8;
        try {
            return Charset.forName(encoding.trim());
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    public record LogFile(String path, long size) {
    }
}
