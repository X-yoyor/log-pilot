package com.logpilot.service;

import com.logpilot.domain.ErrorGroup;
import com.logpilot.repo.CollectLogRepository;
import com.logpilot.repo.ErrorEventRepository;
import com.logpilot.repo.ErrorGroupRepository;
import com.logpilot.repo.LogSourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 日报/周报：按时间范围聚合异常与采集情况，生成可直接粘贴到群/邮件的文本报告。
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    private final ErrorEventRepository eventRepo;
    private final ErrorGroupRepository groupRepo;
    private final LogSourceRepository sourceRepo;
    private final CollectLogRepository collectLogRepo;

    public record TopGroup(String exceptionClass, String template, Long groupId, long count, String status, String assignee) {
    }

    public record ReportData(String title, LocalDateTime from, LocalDateTime to, long events,
                             long prevEvents, int deltaPct, long newGroups, long resolved,
                             long openRemain, long collectFails, List<TopGroup> topGroups,
                             List<Map<String, Object>> topSources, String text) {
    }

    /** range = day（昨日）| week（近 7 天） */
    public ReportData build(String range) {
        LocalDateTime to = LocalDate.now().atStartOfDay();
        LocalDateTime from;
        String title;
        if ("week".equalsIgnoreCase(range)) {
            from = to.minusDays(7);
            title = "LogPilot 周报";
        } else {
            from = to.minusDays(1);
            title = "LogPilot 日报";
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime effTo = to.isAfter(now) ? now : to;

        long events = eventRepo.countByCreatedAtBetween(from, effTo);
        long prevFrom = "week".equalsIgnoreCase(range) ? 2 : 1;
        long prevEvents = eventRepo.countByCreatedAtBetween(from.minusDays(prevFrom), from);
        int deltaPct = prevEvents == 0 ? (events == 0 ? 0 : 100) : (int) Math.round((events - prevEvents) * 100.0 / prevEvents);

        long newGroups = groupRepo.countByFirstSeenBetween(from, effTo);
        long resolved = groupRepo.countByFixedAtBetween(from, effTo);
        long openRemain = groupRepo.countByStatus("OPEN") + groupRepo.countByStatus("ANALYZED");
        long collectFails = collectLogRepo.countByStatusAndStartAtBetween("FAIL", from, effTo);

        DateTimeFormatter fm = DateTimeFormatter.ofPattern("MM-dd HH:mm");
        List<TopGroup> topGroups = new ArrayList<>();
        for (Object[] row : eventRepo.countByGroupBetween(from, effTo)) {
            if (topGroups.size() >= 8) break;
            Long gid = (Long) row[0];
            ErrorGroup g = groupRepo.findById(gid).orElse(null);
            if (g == null) continue;
            topGroups.add(new TopGroup(g.getExceptionClass(), g.getTemplate(), g.getId(),
                    (Long) row[1], g.getStatus(), g.getAssignee()));
        }

        List<Map<String, Object>> topSources = new ArrayList<>();
        for (Object[] row : eventRepo.countBySourceBetween(from, effTo)) {
            if (topSources.size() >= 5) break;
            Long sid = (Long) row[0];
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", sourceRepo.findById(sid).map(s -> s.getName()).orElse("已删除源#" + sid));
            m.put("count", row[1]);
            topSources.add(m);
        }

        String text = formatText(title, range, from, effTo, events, prevEvents, deltaPct,
                newGroups, resolved, openRemain, collectFails, topGroups, topSources, fm);

        return new ReportData(title, from, effTo, events, prevEvents, deltaPct, newGroups,
                resolved, openRemain, collectFails, topGroups, topSources, text);
    }

    private String formatText(String title, String range, LocalDateTime from, LocalDateTime to,
                              long events, long prevEvents, int deltaPct, long newGroups, long resolved,
                              long openRemain, long collectFails, List<TopGroup> topGroups,
                              List<Map<String, Object>> topSources, DateTimeFormatter fm) {
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(title).append("】")
                .append(from.format(fm)).append(" ~ ").append(to.format(fm)).append('\n');
        sb.append("────────────────────\n");
        sb.append("一、总体情况\n");
        sb.append("· 异常事件 ").append(events).append(" 条")
                .append("（上期 ").append(prevEvents).append(" 条，")
                .append(deltaPct >= 0 ? "↑" : "↓").append(Math.abs(deltaPct)).append("%）\n");
        sb.append("· 新增异常类型 ").append(newGroups).append(" 个\n");
        sb.append("· 本期修复 ").append(resolved).append(" 个，当前遗留待处理 ").append(openRemain).append(" 个\n");
        sb.append("· 采集失败 ").append(collectFails).append(" 次\n");

        if (!topGroups.isEmpty()) {
            sb.append("\n二、Top 异常\n");
            int i = 1;
            for (TopGroup g : topGroups) {
                sb.append(i++).append(". [").append(g.status()).append("]")
                        .append(g.exceptionClass()).append(" — ").append(truncate(g.template(), 60))
                        .append("（").append(g.count()).append(" 次");
                if (g.assignee() != null && !g.assignee().isEmpty()) sb.append("，处理人 ").append(g.assignee());
                sb.append("）\n");
            }
        }
        if (!topSources.isEmpty()) {
            sb.append("\n三、异常最多的日志源\n");
            for (Map<String, Object> s : topSources) {
                sb.append("· ").append(s.get("name")).append("（").append(s.get("count")).append(" 条）\n");
            }
        }
        sb.append("\n四、建议\n");
        if (openRemain > 0) {
            sb.append("· 遗留待处理异常 ").append(openRemain).append(" 个，请按优先级安排修复并更新处理人\n");
        } else {
            sb.append("· 当前无遗留待处理异常\n");
        }
        if (collectFails > 0) {
            sb.append("· 存在采集失败记录，请到「系统健康」页核查日志源状态\n");
        }
        return sb.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
