package com.logpilot.service;

import com.logpilot.domain.CollectLog;
import com.logpilot.domain.LogSource;
import com.logpilot.repo.CollectLogRepository;
import com.logpilot.repo.ErrorEventRepository;
import com.logpilot.repo.LogSourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 采集调度：心跳扫描到期日志源，线程池异步执行采集；每日凌晨清理过期数据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleService {

    private final LogSourceRepository sourceRepo;
    private final CollectService collectService;
    private final ErrorEventRepository eventRepo;
    private final CollectLogRepository collectLogRepo;
    private final SettingService settings;

    @Value("${logpilot.collect.ticker-seconds:30}")
    private int tickerSeconds;

    private ExecutorService executor;

    @PostConstruct
    public void init() {
        executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "log-collect");
            t.setDaemon(true);
            return t;
        });
        // 启动时把所有启用源的首次采集安排在 10 秒内（nextCollectTime 为空视为到期）
        List<LogSource> sources = sourceRepo.findByEnabledTrue();
        for (LogSource s : sources) {
            if (s.getNextCollectTime() == null) {
                s.setNextCollectTime(LocalDateTime.now().plusSeconds(10));
                sourceRepo.save(s);
            }
        }
        log.info("采集调度就绪，心跳 {}s，启用日志源 {} 个", tickerSeconds, sources.size());
    }

    @PreDestroy
    public void destroy() {
        if (executor != null) executor.shutdownNow();
    }

    @Scheduled(fixedDelayString = "#{${logpilot.collect.ticker-seconds:30} * 1000}", initialDelay = 5000)
    public void ticker() {
        List<LogSource> due = sourceRepo.findByEnabledTrueAndNextCollectTimeLessThanEqual(LocalDateTime.now());
        for (LogSource s : due) {
            if (collectService.isRunning(s.getId())) continue;
            executor.submit(() -> {
                try {
                    CollectLog cl = collectService.collectNow(s.getId());
                    if (!"OK".equals(cl.getStatus())) {
                        log.warn("日志源[{}]采集失败: {}", s.getName(), cl.getMessage());
                    }
                } catch (Exception e) {
                    log.error("日志源[{}]采集异常: {}", s.getName(), e.getMessage());
                }
            });
        }
    }

    /** 每日凌晨 3 点清理过期数据。 */
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanup() {
        int days = Math.max(3, settings.getInt(SettingService.RETENTION_DAYS, 30));
        LocalDateTime before = LocalDateTime.now().minusDays(days);
        long events = eventRepo.deleteByCreatedAtBefore(before);
        long logs = collectLogRepo.deleteByStartAtBefore(LocalDateTime.now().minusDays(7));
        log.info("数据清理完成：删除 {} 天前事件 {} 条，7 天前采集记录 {} 条", days, events, logs);
    }
}
