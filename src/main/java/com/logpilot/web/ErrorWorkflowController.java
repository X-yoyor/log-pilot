package com.logpilot.web;

import com.logpilot.common.ApiResponse;
import com.logpilot.domain.ErrorEvent;
import com.logpilot.domain.ErrorGroup;
import com.logpilot.repo.ErrorEventRepository;
import com.logpilot.repo.ErrorGroupRepository;
import com.logpilot.service.AiService;
import com.logpilot.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 异常组修复闭环：状态流转、处理人/标签、AI 分析。
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class ErrorWorkflowController {

    private final ErrorGroupRepository groupRepo;
    private final ErrorEventRepository eventRepo;
    private final AiService aiService;
    private final AuditService auditService;

    /** 更新状态、处理人、标签与修复说明。 */
    @PostMapping("/errors/{id}/status")
    public String updateStatus(@PathVariable Long id,
                               @RequestParam String status,
                               @RequestParam(required = false) String fixNote,
                               @RequestParam(required = false) String assignee,
                               @RequestParam(required = false) String tags,
                               RedirectAttributes ra) {
        ErrorGroup g = groupRepo.findById(id).orElse(null);
        if (g != null) {
            String s = status.toUpperCase();
            if (!List.of("OPEN", "ANALYZED", "RESOLVED", "IGNORED").contains(s)) {
                ra.addFlashAttribute("error", "非法状态: " + status);
                return "redirect:/errors/" + id;
            }
            StringBuilder changes = new StringBuilder();
            if (!s.equals(g.getStatus())) changes.append("状态 ").append(g.getStatus()).append("→").append(s).append("; ");
            g.setStatus(s);
            if (assignee != null) {
                String newAssignee = assignee.trim();
                if (!newAssignee.equals(g.getAssignee() == null ? "" : g.getAssignee())) {
                    changes.append("处理人 ").append(g.getAssignee()).append("→").append(newAssignee).append("; ");
                }
                g.setAssignee(newAssignee.isEmpty() ? null : newAssignee);
            }
            if (tags != null) {
                String newTags = tags.trim();
                if (!newTags.equals(g.getTags() == null ? "" : g.getTags())) {
                    changes.append("标签 →").append(newTags).append("; ");
                }
                g.setTags(newTags.isEmpty() ? null : newTags);
            }
            if (fixNote != null && !fixNote.isBlank()) {
                g.setFixNote(fixNote.trim());
            }
            if ("RESOLVED".equals(s)) {
                g.setFixedAt(LocalDateTime.now());
                if (g.getFixNote() == null || g.getFixNote().isBlank()) {
                    g.setFixNote("[" + LocalDateTime.now() + "] 标记为已修复");
                }
            }
            g.setUpdatedAt(LocalDateTime.now());
            groupRepo.save(g);
            auditService.record("更新异常状态", "GROUP", id,
                    g.getExceptionClass() + " " + (g.getTemplate() == null ? "" : g.getTemplate()),
                    changes.isEmpty() ? "仅更新备注" : changes.toString());
            ra.addFlashAttribute("msg", "状态已更新为 " + s);
        }
        return "redirect:/errors/" + id;
    }

    /** 触发 AI 分析（同步，可能耗时数秒到数十秒）。 */
    @PostMapping("/errors/{id}/ai")
    @ResponseBody
    public ApiResponse<String> aiAnalyze(@PathVariable Long id) {
        ErrorGroup g = groupRepo.findById(id).orElse(null);
        if (g == null) return ApiResponse.error("异常组不存在");
        try {
            ErrorEvent latest = eventRepo.findFirstByGroupIdOrderByCreatedAtDesc(id);
            String answer = aiService.analyzeGroup(g, latest == null ? List.of() : List.of(latest));
            auditService.record("AI根因分析", "GROUP", id, g.getExceptionClass(), "模型: " + g.getAiModel());
            return ApiResponse.ok(answer);
        } catch (AiService.AiDisabledException | AiService.AiCallException e) {
            return ApiResponse.error(e.getMessage());
        } catch (Exception e) {
            log.error("AI 分析失败: groupId={}", id, e);
            return ApiResponse.error("AI 分析失败: " + e.getMessage());
        }
    }

    /** 批量 AI 分析待处理异常（最多 20 个），异步执行。 */
    @PostMapping("/errors/ai-batch")
    @ResponseBody
    public ApiResponse<String> aiBatch() {
        List<ErrorGroup> pending = groupRepo
                .findTop500ByAiAnalyzedFalseAndStatusInOrderByTotalCountDesc(List.of("OPEN", "ANALYZED"));
        if (pending.isEmpty()) return ApiResponse.ok("没有待 AI 分析的异常组");
        if (!aiService.enabled()) return ApiResponse.error("AI 未启用：请在系统设置中配置");
        int limit = Math.min(pending.size(), 20);
        auditService.record("批量AI分析", "GROUP", null, null, "已提交 " + limit + " 个异常组");
        new Thread(() -> {
            for (int i = 0; i < limit; i++) {
                ErrorGroup g = pending.get(i);
                try {
                    ErrorEvent latest = eventRepo.findFirstByGroupIdOrderByCreatedAtDesc(g.getId());
                    aiService.analyzeGroup(groupRepo.findById(g.getId()).orElse(g),
                            latest == null ? List.of() : List.of(latest));
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception ignore) {
                    // 单组失败继续下一组
                }
            }
        }, "ai-batch").start();
        return ApiResponse.ok("已开始批量分析 " + limit + " 个异常组，稍后刷新查看结果");
    }
}
