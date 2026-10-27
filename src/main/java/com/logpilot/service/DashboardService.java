package com.logpilot.service;

import com.logpilot.domain.ErrorEvent;
import com.logpilot.domain.ErrorGroup;
import com.logpilot.domain.LogSource;
import com.logpilot.repo.ErrorEventRepository;
import com.logpilot.repo.ErrorGroupRepository;
import com.logpilot.repo.LogSourceRepository;
import com.logpilot.repo.ServerNodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 仪表盘统计聚合。
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final ErrorEventRepository eventRepo;
    private final ErrorGroupRepository groupRepo;
    private final ServerNodeRepository serverRepo;
    private final LogSourceRepository sourceRepo;

    public Map<String, Object> summary() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("todayEvents", eventRepo.countByCreatedAtBetween(todayStart, now));
        m.put("totalEvents", eventRepo.count());
        m.put("openGroups", groupRepo.countByStatus("OPEN") + groupRepo.countByStatus("ANALYZED"));
        m.put("resolvedGroups", groupRepo.countByStatus("RESOLVED"));
        m.put("ignoredGroups", groupRepo.countByStatus("IGNORED"));
        m.put("newGroupsToday", groupRepo.countByFirstSeenAfter(todayStart));
        m.put("servers", serverRepo.count());
        m.put("sources", sourceRepo.count());
        m.put("enabledSources", sourceRepo.findByEnabledTrue().size());
        m.put("yesterdayEvents", eventRepo.countByCreatedAtBetween(todayStart.minusDays(1), todayStart));
        return m;
    }

    /** 近 N 天每日异常事件数。 */
    public List<Map<String, Object>> trend(int days) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = days - 1; i >= 0; i--) {
            LocalDate d = LocalDate.now().minusDays(i);
            long c = eventRepo.countByCreatedAtBetween(d.atStartOfDay(), d.atStartOfDay().plusDays(1));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", d.toString());
            item.put("count", c);
            result.add(item);
        }
        return result;
    }

    /** 近 N 天 Top 异常组（按事件数）。 */
    public List<Map<String, Object>> topGroups(int days, int limit) {
        LocalDateTime since = LocalDate.now().minusDays(days - 1).atStartOfDay();
        List<Object[]> rows = eventRepo.countByGroupSince(since);
        List<Map<String, Object>> result = new ArrayList<>();
        int n = 0;
        for (Object[] row : rows) {
            if (n++ >= limit) break;
            Long gid = (Long) row[0];
            ErrorGroup g = groupRepo.findById(gid).orElse(null);
            if (g == null) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", g.getId());
            item.put("exceptionClass", g.getExceptionClass());
            item.put("template", g.getTemplate());
            item.put("severity", g.getSeverity());
            item.put("status", g.getStatus());
            item.put("knowledgeName", g.getKnowledgeName());
            item.put("count", row[1]);
            result.add(item);
        }
        return result;
    }

    public List<ErrorEvent> recentEvents(int limit) {
        return eventRepo.findAll(PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();
    }

    public List<Map<String, Object>> sourceStats(int days) {
        LocalDateTime since = LocalDate.now().minusDays(days - 1).atStartOfDay();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : eventRepo.countBySourceSince(since)) {
            Long sid = (Long) row[0];
            String name = sourceRepo.findById(sid).map(LogSource::getName).orElse("已删除源#" + sid);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sourceId", sid);
            m.put("name", name);
            m.put("count", row[1]);
            result.add(m);
        }
        return result;
    }
}
