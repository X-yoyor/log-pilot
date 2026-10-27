package com.logpilot.web;

import com.logpilot.domain.ErrorEvent;
import com.logpilot.domain.ErrorGroup;
import com.logpilot.repo.ErrorEventRepository;
import com.logpilot.repo.ErrorGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * CSV 导出：异常组列表（Excel 兼容，带 BOM）、单个异常组的事件明细。
 */
@RestController
@RequiredArgsConstructor
public class ExportController {

    private final ErrorGroupRepository groupRepo;
    private final ErrorEventRepository eventRepo;

    @GetMapping("/errors/export")
    public void exportGroups(@RequestParam(defaultValue = "OPEN") String status,
                             @RequestParam(required = false) String q,
                             @RequestParam(required = false) String assignee,
                             @RequestParam(required = false) String tag,
                             HttpServletResponse response) throws IOException {
        Specification<ErrorGroup> spec = (root, query, cb) -> {
            var ps = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            if (status != null && !status.isBlank() && !"ALL".equals(status)) {
                ps.add(cb.equal(root.get("status"), status));
            }
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase() + "%";
                ps.add(cb.or(
                        cb.like(cb.lower(root.get("exceptionClass")), like),
                        cb.like(cb.lower(root.get("template")), like),
                        cb.like(cb.lower(root.get("topFrame")), like)));
            }
            if (assignee != null && !assignee.isBlank()) {
                ps.add(cb.like(root.get("assignee"), "%" + assignee.trim() + "%"));
            }
            if (tag != null && !tag.isBlank()) {
                ps.add(cb.like(root.get("tags"), "%" + tag.trim() + "%"));
            }
            return cb.and(ps.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
        List<ErrorGroup> groups = groupRepo.findAll(spec, Sort.by(Sort.Direction.DESC, "lastSeen"));

        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF');
        sb.append("ID,级别,异常类,消息模板,代码位置,状态,处理人,标签,次数,首次出现,最近出现,知识库初判\n");
        DateTimeFormatter fm = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        for (ErrorGroup g : groups) {
            sb.append(csv(g.getId())).append(',')
                    .append(csv(g.getLevel())).append(',')
                    .append(csv(g.getExceptionClass())).append(',')
                    .append(csv(g.getTemplate())).append(',')
                    .append(csv(g.getTopFrame())).append(',')
                    .append(csv(g.getStatus())).append(',')
                    .append(csv(g.getAssignee())).append(',')
                    .append(csv(g.getTags())).append(',')
                    .append(csv(g.getTotalCount())).append(',')
                    .append(csv(g.getFirstSeen() == null ? null : g.getFirstSeen().format(fm))).append(',')
                    .append(csv(g.getLastSeen() == null ? null : g.getLastSeen().format(fm))).append(',')
                    .append(csv(g.getKnowledgeName())).append('\n');
        }
        writeCsv(response, "error-groups-" + LocalDate.now() + ".csv", sb);
    }

    @GetMapping("/errors/{id}/export")
    public void exportEvents(@PathVariable Long id, HttpServletResponse response) throws IOException {
        ErrorGroup group = groupRepo.findById(id).orElse(null);
        if (group == null) {
            response.sendError(404);
            return;
        }
        List<ErrorEvent> events = eventRepo.findTop200ByGroupIdOrderByCreatedAtDesc(id);
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF');
        sb.append("发生时间,级别,线程,Logger,消息,堆栈,文件\n");
        DateTimeFormatter fm = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        for (ErrorEvent e : events) {
            sb.append(csv(e.getOccurredAt() == null ? null : e.getOccurredAt().format(fm))).append(',')
                    .append(csv(e.getLevel())).append(',')
                    .append(csv(e.getThread())).append(',')
                    .append(csv(e.getLogger())).append(',')
                    .append(csv(e.getMessage())).append(',')
                    .append(csv(e.getStackTrace())).append(',')
                    .append(csv(e.getFilePath())).append('\n');
        }
        writeCsv(response, "error-group-" + id + "-events.csv", sb);
    }

    private void writeCsv(HttpServletResponse response, String filename, StringBuilder sb) throws IOException {
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        response.getOutputStream().write(sb.toString().getBytes(StandardCharsets.UTF_8));
        response.getOutputStream().flush();
    }

    private String csv(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v);
        boolean needQuote = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        if (needQuote) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }
}
