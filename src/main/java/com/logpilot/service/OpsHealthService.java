package com.logpilot.service;

import com.logpilot.domain.CollectLog;
import com.logpilot.domain.LogSource;
import com.logpilot.repo.CollectLogRepository;
import com.logpilot.repo.ErrorEventRepository;
import com.logpilot.repo.LogSourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 平台自监控：JVM、磁盘、数据库水位 + 各日志源采集健康（最近状态、连续失败、调度延迟）。
 */
@Service
@RequiredArgsConstructor
public class OpsHealthService {

    private final CollectLogRepository collectLogRepo;
    private final LogSourceRepository sourceRepo;
    private final ErrorEventRepository eventRepo;

    public record SourceHealth(Long sourceId, String name, boolean enabled, String lastStatus,
                               LocalDateTime lastRun, LocalDateTime nextRun, boolean overdue,
                               int failStreak, long eventsToday) {
    }

    public record HealthReport(int heapPct, long heapUsedMb, long heapMaxMb, double cpuPct, long uptimeHours,
                               int diskPct, long diskFreeGb, long diskTotalGb, long dataDirMb, long totalEvents,
                               int failingSources, int overdueSources, List<SourceHealth> sources) {
    }

    public HealthReport report() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();

        // JVM
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long heapUsed = heap.getUsed(), heapMax = heap.getMax();
        int heapPct = heapMax > 0 ? (int) (heapUsed * 100 / heapMax) : 0;
        double cpuPct = -1;
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            double load = os.getProcessCpuLoad();
            if (load >= 0) cpuPct = Math.round(load * 1000) / 10.0;
        }
        long uptimeHours = ManagementFactory.getRuntimeMXBean().getUptime() / 3_600_000;

        // 磁盘（应用运行目录所在盘）
        long diskFree = 0, diskTotal = 0;
        try {
            var fileStore = Files.getFileStore(Path.of("").toAbsolutePath());
            diskFree = fileStore.getUsableSpace();
            diskTotal = fileStore.getTotalSpace();
        } catch (IOException ignore) {
            // 文件系统信息不可用时保持 0
        }
        int diskPct = diskTotal > 0 ? (int) ((diskTotal - diskFree) * 100 / diskTotal) : 0;

        // 数据库目录大小
        long dataBytes = dirSize(Path.of("data"));

        // 采集健康：按源聚合最近采集记录（连续失败次数）
        Map<Long, CollectLog> latest = new HashMap<>();
        Map<Long, Integer> failStreak = new HashMap<>();
        java.util.Set<Long> streakClosed = new java.util.HashSet<>();
        for (CollectLog cl : collectLogRepo.findTop400ByOrderByStartAtDesc()) {
            Long sid = cl.getSourceId();
            if (sid == null) continue;
            latest.putIfAbsent(sid, cl);
            if (streakClosed.contains(sid)) continue;
            if ("OK".equals(cl.getStatus())) {
                failStreak.putIfAbsent(sid, 0);
                streakClosed.add(sid);
            } else {
                failStreak.merge(sid, 1, Integer::sum);
            }
        }

        int failing = 0, overdue = 0;
        List<SourceHealth> sources = new ArrayList<>();
        for (LogSource s : sourceRepo.findAll(
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.ASC, "id"))) {
            CollectLog last = latest.get(s.getId());
            String lastStatus = last == null ? "未采集" : last.getStatus();
            int streak = failStreak.getOrDefault(s.getId(), 0);
            boolean overdueFlag = Boolean.TRUE.equals(s.getEnabled()) && s.getNextCollectTime() != null
                    && s.getNextCollectTime().isBefore(now.minusMinutes(5));
            long eventsToday = s.getId() == null ? 0 : eventRepo.countByCreatedAtAfterAndSourceId(todayStart, s.getId());
            if (Boolean.TRUE.equals(s.getEnabled())) {
                if (streak > 0) failing++;
                if (overdueFlag) overdue++;
            }
            sources.add(new SourceHealth(s.getId(), s.getName(), Boolean.TRUE.equals(s.getEnabled()),
                    lastStatus, last == null ? null : last.getStartAt(), s.getNextCollectTime(),
                    overdueFlag, streak, eventsToday));
        }

        return new HealthReport(heapPct, heapUsed / 1048576, heapMax / 1048576, cpuPct, uptimeHours,
                diskPct, diskFree / 1073741824, diskTotal / 1073741824, dataBytes / 1048576,
                eventRepo.count(), failing, overdue, sources);
    }

    private long dirSize(Path dir) {
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException e) {
            return 0;
        }
    }
}
