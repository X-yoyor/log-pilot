package com.logpilot.web;

import com.logpilot.domain.AuditLog;
import com.logpilot.domain.ErrorEvent;
import com.logpilot.domain.ErrorGroup;
import com.logpilot.domain.LogSource;
import com.logpilot.repo.*;
import com.logpilot.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 页面路由。
 */
@Controller
@RequiredArgsConstructor
public class PageController {

    private final DashboardService dashboardService;
    private final OpsHealthService opsHealthService;
    private final ReportService reportService;
    private final LogSearchService logSearchService;
    private final ServerNodeRepository serverRepo;
    private final LogSourceRepository sourceRepo;
    private final ErrorGroupRepository groupRepo;
    private final ErrorEventRepository eventRepo;
    private final KnowledgeRuleRepository knowledgeRepo;
    private final AlertRuleRepository alertRepo;
    private final AlertRecordRepository alertRecordRepo;
    private final CollectLogRepository collectLogRepo;
    private final SilenceRepository silenceRepo;
    private final NotifyChannelRepository channelRepo;
    private final AuditLogRepository auditRepo;
    private final SettingService settingService;
    private final AiService aiService;

    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("summary", dashboardService.summary());
        model.addAttribute("topGroups", dashboardService.topGroups(7, 8));
        model.addAttribute("recentEvents", dashboardService.recentEvents(12));
        model.addAttribute("sourceStats", dashboardService.sourceStats(7));
        OpsHealthService.HealthReport health = opsHealthService.report();
        model.addAttribute("health", health);
        return "dashboard";
    }

    @GetMapping("/servers")
    public String servers(Model model) {
        model.addAttribute("servers", serverRepo.findAll(Sort.by(Sort.Direction.DESC, "id")));
        return "servers";
    }

    @GetMapping("/sources")
    public String sources(Model model) {
        model.addAttribute("sources", sourceRepo.findAll(Sort.by(Sort.Direction.DESC, "id")));
        model.addAttribute("servers", serverRepo.findAll());
        model.addAttribute("serverNames", serverNameMap());
        model.addAttribute("collectLogs", collectLogRepo.findAll(PageRequest.of(0, 20)).getContent());
        model.addAttribute("silencedSourceIds", silencedSourceIds());
        return "sources";
    }

    // ==================== 日志检索 ====================

    @GetMapping("/search")
    public String search(@RequestParam(required = false) Long sourceId,
                         @RequestParam(required = false) String q,
                         @RequestParam(defaultValue = "2") int ctx,
                         @RequestParam(defaultValue = "50") int max,
                         @RequestParam(defaultValue = "true") boolean ignoreCase,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
                         Model model) {
        model.addAttribute("sources", sourceRepo.findByEnabledTrue());
        model.addAttribute("q", q == null ? "" : q);
        model.addAttribute("sourceId", sourceId);
        model.addAttribute("ctx", ctx);
        model.addAttribute("max", max);
        model.addAttribute("ignoreCase", ignoreCase);
        if (q != null && !q.isBlank() && sourceId != null) {
            LogSource source = sourceRepo.findById(sourceId).orElse(null);
            if (source == null) {
                model.addAttribute("error", "日志源不存在");
            } else {
                try {
                    LogSearchService.SearchResult result = logSearchService.search(
                            source, q, ignoreCase, Math.max(0, Math.min(10, ctx)),
                            Math.max(1, Math.min(200, max)), from, to);
                    model.addAttribute("result", result);
                    model.addAttribute("searched", true);
                } catch (Exception e) {
                    model.addAttribute("error", "检索失败: " + e.getMessage());
                }
            }
        }
        return "search";
    }

    // ==================== 系统健康 ====================

    @GetMapping("/health")
    public String health(Model model) {
        model.addAttribute("health", opsHealthService.report());
        return "health";
    }

    // ==================== 异常 ====================

    @GetMapping("/errors")
    public String errors(@RequestParam(defaultValue = "OPEN") String status,
                         @RequestParam(required = false) String q,
                         @RequestParam(required = false) String assignee,
                         @RequestParam(required = false) String tag,
                         @RequestParam(defaultValue = "0") int page,
                         Model model) {
        Specification<ErrorGroup> spec = (root, query, cb) -> {
            var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            if (status != null && !status.isBlank() && !"ALL".equals(status)) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("exceptionClass")), like),
                        cb.like(cb.lower(root.get("template")), like),
                        cb.like(cb.lower(root.get("topFrame")), like)));
            }
            if (assignee != null && !assignee.isBlank()) {
                predicates.add(cb.like(root.get("assignee"), "%" + assignee.trim() + "%"));
            }
            if (tag != null && !tag.isBlank()) {
                predicates.add(cb.like(root.get("tags"), "%" + tag.trim() + "%"));
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
        Page<ErrorGroup> groups = groupRepo.findAll(spec,
                PageRequest.of(Math.max(0, page), 20, Sort.by(Sort.Direction.DESC, "lastSeen")));
        model.addAttribute("groups", groups.getContent());
        model.addAttribute("page", groups);
        model.addAttribute("status", status);
        model.addAttribute("q", q == null ? "" : q);
        model.addAttribute("assignee", assignee == null ? "" : assignee);
        model.addAttribute("tag", tag == null ? "" : tag);
        model.addAttribute("assignees", groupRepo.findDistinctAssignees());
        model.addAttribute("countOpen", groupRepo.countByStatus("OPEN"));
        model.addAttribute("countAnalyzed", groupRepo.countByStatus("ANALYZED"));
        model.addAttribute("countResolved", groupRepo.countByStatus("RESOLVED"));
        model.addAttribute("countIgnored", groupRepo.countByStatus("IGNORED"));
        return "errors";
    }

    @GetMapping("/errors/{id}")
    public String errorDetail(@PathVariable Long id,
                              @RequestParam(defaultValue = "0") int page,
                              Model model) {
        ErrorGroup group = groupRepo.findById(id).orElse(null);
        if (group == null) return "redirect:/errors";
        Page<ErrorEvent> events = eventRepo.findByGroupId(id,
                PageRequest.of(Math.max(0, page), 20, Sort.by(Sort.Direction.DESC, "createdAt")));
        List<com.logpilot.domain.Silence> activeSilences =
                silenceRepo.findByGroupIdAndUntilAtAfter(id, LocalDateTime.now());
        model.addAttribute("group", group);
        model.addAttribute("events", events.getContent());
        model.addAttribute("eventPage", events);
        model.addAttribute("totalEvents", eventRepo.countByGroupId(id));
        model.addAttribute("sourceNames", sourceNameMap());
        model.addAttribute("aiEnabled", aiService.enabled());
        model.addAttribute("silencedUntil", activeSilences.isEmpty() ? null : activeSilences.get(0).getUntilAt());
        model.addAttribute("assignees", groupRepo.findDistinctAssignees());
        return "error-detail";
    }

    @GetMapping("/events")
    public String events(@RequestParam(required = false) Long groupId,
                         @RequestParam(required = false) Long sourceId,
                         @RequestParam(defaultValue = "0") int page,
                         Model model) {
        Page<ErrorEvent> events;
        if (groupId != null) {
            events = eventRepo.findByGroupId(groupId, PageRequest.of(Math.max(0, page), 30, Sort.by(Sort.Direction.DESC, "createdAt")));
        } else {
            events = eventRepo.findAllByOrderByCreatedAtDesc(PageRequest.of(Math.max(0, page), 30));
        }
        model.addAttribute("events", events.getContent());
        model.addAttribute("page", events);
        model.addAttribute("groupId", groupId);
        model.addAttribute("sourceId", sourceId);
        model.addAttribute("sourceNames", sourceNameMap());
        return "events";
    }

    @GetMapping("/knowledge")
    public String knowledge(Model model) {
        model.addAttribute("rules", knowledgeRepo.findAll(Sort.by(Sort.Direction.ASC, "sortOrder")));
        return "knowledge";
    }

    // ==================== 告警（规则/渠道/静默） ====================

    @GetMapping("/alerts")
    public String alerts(@RequestParam(defaultValue = "0") int page, Model model) {
        LocalDateTime now = LocalDateTime.now();
        List<com.logpilot.domain.Silence> silences = silenceRepo.findAll(Sort.by(Sort.Direction.DESC, "id"));
        Map<Long, String> silenceNames = new HashMap<>();
        for (var s : silences) {
            if (s.isSourceSilence()) {
                silenceNames.put(s.getId(), sourceRepo.findById(s.getSourceId())
                        .map(LogSource::getName).orElse("已删除源#" + s.getSourceId()));
            } else {
                silenceNames.put(s.getId(), groupRepo.findById(s.getGroupId())
                        .map(g -> "#" + g.getId() + " " + g.getExceptionClass()).orElse("已删除组#" + s.getGroupId()));
            }
        }
        model.addAttribute("rules", alertRepo.findAll());
        model.addAttribute("channels", channelRepo.findAll(Sort.by(Sort.Direction.DESC, "id")));
        model.addAttribute("sources", sourceRepo.findAll());
        model.addAttribute("sourceNames", sourceNameMap());
        model.addAttribute("silences", silences);
        model.addAttribute("silenceNames", silenceNames);
        model.addAttribute("records", alertRecordRepo.findAllByOrderByCreatedAtDesc(PageRequest.of(Math.max(0, page), 20)).getContent());
        model.addAttribute("mailEnabled", settingService.mailEnabled());
        return "alerts";
    }

    // ==================== 报告 ====================

    @GetMapping("/reports")
    public String reports(@RequestParam(defaultValue = "day") String range, Model model) {
        model.addAttribute("range", "week".equalsIgnoreCase(range) ? "week" : "day");
        model.addAttribute("report", reportService.build(range));
        model.addAttribute("mailEnabled", settingService.mailEnabled());
        return "reports";
    }

    // ==================== 审计 ====================

    @GetMapping("/audit")
    public String audit(@RequestParam(required = false) String actor,
                        @RequestParam(required = false) String action,
                        @RequestParam(defaultValue = "0") int page,
                        Model model) {
        Specification<AuditLog> spec = (root, query, cb) -> {
            var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            if (actor != null && !actor.isBlank()) {
                predicates.add(cb.like(root.get("actor"), "%" + actor.trim() + "%"));
            }
            if (action != null && !action.isBlank()) {
                predicates.add(cb.like(root.get("action"), "%" + action.trim() + "%"));
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
        Page<AuditLog> logs = auditRepo.findAll(spec,
                PageRequest.of(Math.max(0, page), 30, Sort.by(Sort.Direction.DESC, "createdAt")));
        model.addAttribute("logs", logs.getContent());
        model.addAttribute("page", logs);
        model.addAttribute("actor", actor == null ? "" : actor);
        model.addAttribute("action", action == null ? "" : action);
        return "audit";
    }

    // ==================== 设置 ====================

    @GetMapping("/settings")
    public String settings(Model model) {
        Map<String, String> all = settingService.all();
        model.addAttribute("settings", all);
        model.addAttribute("aiEnabled", settingService.aiEnabled());
        model.addAttribute("mailEnabled", settingService.mailEnabled());
        return "settings";
    }

    private Map<Long, String> sourceNameMap() {
        return sourceRepo.findAll().stream().collect(Collectors.toMap(LogSource::getId, LogSource::getName, (a, b) -> a));
    }

    private Map<Long, String> serverNameMap() {
        return serverRepo.findAll().stream().collect(Collectors.toMap(com.logpilot.domain.ServerNode::getId,
                com.logpilot.domain.ServerNode::getName, (a, b) -> a));
    }

    private List<Long> silencedSourceIds() {
        return silenceRepo.findByUntilAtAfter(LocalDateTime.now()).stream()
                .map(com.logpilot.domain.Silence::getSourceId).filter(java.util.Objects::nonNull).toList();
    }
}
