package com.logpilot.service;

import com.logpilot.domain.ErrorEvent;
import com.logpilot.domain.ErrorGroup;
import com.logpilot.domain.LogSource;
import com.logpilot.repo.ErrorEventRepository;
import com.logpilot.repo.ErrorGroupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 异常分析：将解析出的异常记录按指纹聚合入库，关联知识库建议。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyzeService {

    private final ErrorEventRepository eventRepo;
    private final ErrorGroupRepository groupRepo;
    private final KnowledgeService knowledgeService;

    /**
     * 处理一次采集批次中的所有异常记录。
     *
     * @return 入库事件数
     */
    @Transactional
    public int process(List<LogParser.ParsedRecord> errors, LogSource source, Long serverId, String filePath) {
        if (errors == null || errors.isEmpty()) return 0;
        LocalDateTime now = LocalDateTime.now();
        Map<String, List<LogParser.ParsedRecord>> byFingerprint = new LinkedHashMap<>();

        List<ErrorEvent> events = new ArrayList<>(errors.size());
        List<ErrorGroup> groups = new ArrayList<>();

        for (LogParser.ParsedRecord rec : errors) {
            String excClass = rec.getExceptionClass() != null ? rec.getExceptionClass()
                    : FingerprintUtil.exceptionClass(rec.getMessage(), rec.getStackTrace());
            String template = FingerprintUtil.normalizeTemplate(rec.getMessage());
            String topFrame = FingerprintUtil.topFrame(rec.getStackTrace());
            String fp = FingerprintUtil.fingerprint(excClass, template, topFrame);
            byFingerprint.computeIfAbsent(fp, k -> new ArrayList<>()).add(rec);

            ErrorEvent e = new ErrorEvent();
            e.setSourceId(source.getId());
            e.setServerId(serverId);
            e.setFilePath(filePath);
            e.setOccurredAt(rec.getTimestamp() != null ? rec.getTimestamp() : now);
            e.setCreatedAt(now);
            e.setLevel(rec.getLevel() != null ? rec.getLevel() : "ERROR");
            e.setLogger(rec.getLogger());
            e.setThread(rec.getThread());
            e.setMessage(rec.getMessage());
            e.setStackTrace(rec.getStackTrace() != null && rec.getStackTrace().length() > 7900
                    ? rec.getStackTrace().substring(0, 7900) : rec.getStackTrace());
            e.setFingerprint(fp);
            e.setRaw(rec.getRaw() != null && rec.getRaw().length() > 11000
                    ? rec.getRaw().substring(0, 11000) : rec.getRaw());
            events.add(e);
        }

        for (Map.Entry<String, List<LogParser.ParsedRecord>> en : byFingerprint.entrySet()) {
            List<LogParser.ParsedRecord> recs = en.getValue();
            LogParser.ParsedRecord sample = recs.get(0);

            String excClass = sample.getExceptionClass() != null ? sample.getExceptionClass()
                    : FingerprintUtil.exceptionClass(sample.getMessage(), sample.getStackTrace());
            String template = FingerprintUtil.normalizeTemplate(sample.getMessage());
            String topFrame = FingerprintUtil.topFrame(sample.getStackTrace());

            Optional<ErrorGroup> exist = groupRepo.findByFingerprint(en.getKey());
            ErrorGroup g = exist.orElseGet(() -> createGroup(en.getKey(), excClass, template, topFrame, sample, now, source.getId()));

            LocalDateTime latest = recs.stream().map(r -> r.getTimestamp() != null ? r.getTimestamp() : now)
                    .max(LocalDateTime::compareTo).orElse(now);
            if (g.getLastSeen() == null || latest.isAfter(g.getLastSeen())) g.setLastSeen(latest);
            g.setTotalCount((g.getTotalCount() == null ? 0 : g.getTotalCount()) + recs.size());
            String srcIds = g.getSourceIds() == null ? "" : g.getSourceIds();
            if (!Arrays.asList(srcIds.split(",")).contains(String.valueOf(source.getId()))) {
                g.setSourceIds(srcIds.isEmpty() ? String.valueOf(source.getId()) : srcIds + "," + source.getId());
            }
            g.setUpdatedAt(now);
            // 修复后若再次出现，重开问题
            if ("RESOLVED".equals(g.getStatus()) && g.getFixedAt() != null && latest.isAfter(g.getFixedAt())) {
                g.setStatus("OPEN");
                g.setFixNote((g.getFixNote() == null ? "" : g.getFixNote() + "\n") + "[" + now + "] 修复后再次出现，已重开");
            }
            groups.add(g);
        }

        groupRepo.saveAll(groups);

        // 事件关联组 id：同指纹的事件全部挂到对应组
        Map<String, Long> fpToGroup = new HashMap<>();
        for (ErrorGroup g : groups) fpToGroup.put(g.getFingerprint(), g.getId());
        for (ErrorEvent e : events) {
            e.setGroupId(fpToGroup.get(e.getFingerprint()));
        }
        eventRepo.saveAll(events);
        return events.size();
    }

    private ErrorGroup createGroup(String fp, String excClass, String template, String topFrame,
                                   LogParser.ParsedRecord sample, LocalDateTime now, Long sourceId) {
        ErrorGroup g = new ErrorGroup();
        g.setFingerprint(fp);
        g.setExceptionClass(excClass);
        g.setTemplate(template);
        g.setTopFrame(topFrame);
        g.setLevel(sample.getLevel() != null ? sample.getLevel() : "ERROR");
        g.setFirstSeen(sample.getTimestamp() != null ? sample.getTimestamp() : now);
        g.setLastSeen(g.getFirstSeen());
        g.setTotalCount(0L);
        g.setStatus("OPEN");
        g.setSourceIds(String.valueOf(sourceId));

        // 匹配知识库
        KnowledgeService.KnowledgeHit hit = knowledgeService.match(excClass, template, topFrame,
                sample.getStackTrace() != null ? sample.getStackTrace() : "",
                sample.getMessage() != null ? sample.getMessage() : "");
        if (hit != null) {
            g.setKnowledgeRuleId(hit.ruleId());
            g.setKnowledgeName(hit.name());
            g.setSuggestion(hit.suggestion());
            g.setSeverity(hit.severity());
        }
        return g;
    }
}
