package com.logpilot.web;

import com.logpilot.common.ApiResponse;
import com.logpilot.domain.AlertRule;
import com.logpilot.domain.KnowledgeRule;
import com.logpilot.repo.AlertRuleRepository;
import com.logpilot.repo.KnowledgeRuleRepository;
import com.logpilot.service.AiService;
import com.logpilot.service.AlertService;
import com.logpilot.service.AuditService;
import com.logpilot.service.KnowledgeService;
import com.logpilot.service.SettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 知识库、告警、系统设置管理。
 */
@Controller
@RequiredArgsConstructor
public class ManageController {

    private final KnowledgeRuleRepository knowledgeRepo;
    private final KnowledgeService knowledgeService;
    private final AlertRuleRepository alertRepo;
    private final AlertService alertService;
    private final SettingService settingService;
    private final AiService aiService;
    private final AuditService auditService;

    // ==================== 知识库 ====================

    @PostMapping("/knowledge/save")
    public String saveRule(@RequestParam(required = false) Long id,
                           @RequestParam String name,
                           @RequestParam String pattern,
                           @RequestParam(required = false, defaultValue = "MID") String severity,
                           @RequestParam(required = false) String cause,
                           @RequestParam(required = false) String suggestion,
                           RedirectAttributes ra) {
        try {
            java.util.regex.Pattern.compile(pattern);
        } catch (Exception e) {
            ra.addFlashAttribute("error", "正则表达式无效: " + e.getMessage());
            return "redirect:/knowledge";
        }
        KnowledgeRule r = (id != null) ? knowledgeRepo.findById(id).orElse(new KnowledgeRule()) : new KnowledgeRule();
        r.setName(name);
        r.setPattern(pattern);
        r.setSeverity(severity);
        r.setCause(cause);
        r.setSuggestion(suggestion);
        r.setBuiltin(false);
        knowledgeRepo.save(r);
        knowledgeService.reload();
        auditService.record("保存知识规则", "RULE", r.getId(), name, pattern);
        ra.addFlashAttribute("msg", "规则已保存并生效");
        return "redirect:/knowledge";
    }

    @PostMapping("/knowledge/{id}/toggle")
    public String toggleRule(@PathVariable Long id, RedirectAttributes ra) {
        KnowledgeRule r = knowledgeRepo.findById(id).orElse(null);
        if (r != null) {
            r.setEnabled(!Boolean.TRUE.equals(r.getEnabled()));
            knowledgeRepo.save(r);
            knowledgeService.reload();
            ra.addFlashAttribute("msg", (Boolean.TRUE.equals(r.getEnabled()) ? "已启用" : "已停用") + ": " + r.getName());
        }
        return "redirect:/knowledge";
    }

    @PostMapping("/knowledge/{id}/delete")
    public String deleteRule(@PathVariable Long id, RedirectAttributes ra) {
        KnowledgeRule r = knowledgeRepo.findById(id).orElse(null);
        if (r != null) {
            if (Boolean.TRUE.equals(r.getBuiltin())) {
                ra.addFlashAttribute("error", "内置规则不可删除，可停用");
                return "redirect:/knowledge";
            }
            knowledgeRepo.deleteById(id);
            knowledgeService.reload();
            ra.addFlashAttribute("msg", "规则已删除");
        }
        return "redirect:/knowledge";
    }

    // ==================== 告警 ====================

    @PostMapping("/alerts/save")
    public String saveAlert(@RequestParam(required = false) Long id,
                            @RequestParam String name,
                            @RequestParam(required = false, defaultValue = "EVENT_COUNT") String metric,
                            @RequestParam(required = false) Long sourceId,
                            @RequestParam(required = false, defaultValue = "50") Integer threshold,
                            @RequestParam(required = false, defaultValue = "30") Integer windowMinutes,
                            @RequestParam(required = false, defaultValue = "60") Integer cooldownMinutes,
                            @RequestParam(required = false) String webhookUrl,
                            @RequestParam(required = false) String emailTo,
                            @RequestParam(required = false) Long channelId,
                            RedirectAttributes ra) {
        AlertRule r = (id != null) ? alertRepo.findById(id).orElse(new AlertRule()) : new AlertRule();
        r.setName(name);
        r.setMetric("NEW_GROUP".equals(metric) ? "NEW_GROUP" : "EVENT_COUNT");
        r.setSourceId(sourceId);
        r.setThreshold(Math.max(1, threshold));
        r.setWindowMinutes(Math.max(1, windowMinutes));
        r.setCooldownMinutes(Math.max(1, cooldownMinutes));
        r.setWebhookUrl(webhookUrl);
        r.setEmailTo(emailTo);
        r.setChannelId(channelId);
        alertRepo.save(r);
        auditService.record("保存告警规则", "ALERT", r.getId(), name,
                "指标 " + r.getMetric() + "，阈值 " + r.getThreshold() + "/" + r.getWindowMinutes() + " 分钟");
        ra.addFlashAttribute("msg", "告警规则已保存");
        return "redirect:/alerts";
    }

    @PostMapping("/alerts/{id}/delete")
    public String deleteAlert(@PathVariable Long id, RedirectAttributes ra) {
        alertRepo.deleteById(id);
        ra.addFlashAttribute("msg", "告警规则已删除");
        return "redirect:/alerts";
    }

    @PostMapping("/alerts/{id}/toggle")
    public String toggleAlert(@PathVariable Long id, RedirectAttributes ra) {
        AlertRule r = alertRepo.findById(id).orElse(null);
        if (r != null) {
            r.setEnabled(!Boolean.TRUE.equals(r.getEnabled()));
            alertRepo.save(r);
            ra.addFlashAttribute("msg", Boolean.TRUE.equals(r.getEnabled()) ? "已启用" : "已停用");
        }
        return "redirect:/alerts";
    }

    @PostMapping("/alerts/test-mail")
    @ResponseBody
    public ApiResponse<String> testMail(@RequestParam String to) {
        try {
            return ApiResponse.ok(alertService.testMail(to));
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    // ==================== 系统设置 ====================

    @PostMapping("/settings/save")
    public String saveSettings(@RequestParam(required = false, defaultValue = "false") boolean aiEnabled,
                               @RequestParam(required = false) String aiBaseUrl,
                               @RequestParam(required = false) String aiApiKey,
                               @RequestParam(required = false) String aiModel,
                               @RequestParam(required = false, defaultValue = "60") Integer aiTimeout,
                               @RequestParam(required = false) String aiPrompt,
                               @RequestParam(required = false) String mailHost,
                               @RequestParam(required = false, defaultValue = "465") Integer mailPort,
                               @RequestParam(required = false) String mailUsername,
                               @RequestParam(required = false) String mailPassword,
                               @RequestParam(required = false) String mailFrom,
                               @RequestParam(required = false, defaultValue = "true") boolean mailSsl,
                               @RequestParam(required = false, defaultValue = "30") Integer retentionDays,
                               @RequestParam(required = false) String opsActor,
                               RedirectAttributes ra) {
        settingService.put(SettingService.AI_ENABLED, String.valueOf(aiEnabled));
        if (aiBaseUrl != null) settingService.put(SettingService.AI_BASE_URL, aiBaseUrl);
        // API Key 留空表示不修改
        if (aiApiKey != null && !aiApiKey.isBlank()) settingService.put(SettingService.AI_API_KEY, aiApiKey);
        if (aiModel != null) settingService.put(SettingService.AI_MODEL, aiModel);
        settingService.put(SettingService.AI_TIMEOUT, String.valueOf(Math.max(10, aiTimeout)));
        if (aiPrompt != null) settingService.put(SettingService.AI_PROMPT, aiPrompt);

        if (mailHost != null) settingService.put(SettingService.MAIL_HOST, mailHost);
        settingService.put(SettingService.MAIL_PORT, String.valueOf(mailPort));
        if (mailUsername != null) settingService.put(SettingService.MAIL_USERNAME, mailUsername);
        if (mailPassword != null && !mailPassword.isBlank()) settingService.put(SettingService.MAIL_PASSWORD, mailPassword);
        if (mailFrom != null) settingService.put(SettingService.MAIL_FROM, mailFrom);
        settingService.put(SettingService.MAIL_SSL, String.valueOf(mailSsl));
        settingService.put(SettingService.RETENTION_DAYS, String.valueOf(Math.max(3, retentionDays)));
        if (opsActor != null) settingService.put("ops.actor", opsActor.trim());

        auditService.record("保存系统设置", "SETTING", null, null, "AI=" + aiEnabled + ", 保留期=" + retentionDays + " 天, 操作人=" + opsActor);
        ra.addFlashAttribute("msg", "设置已保存");
        return "redirect:/settings";
    }

    @PostMapping("/settings/test-ai")
    @ResponseBody
    public ApiResponse<String> testAi() {
        try {
            String reply = aiService.test();
            return ApiResponse.ok("AI 连通正常，模型回复: " + reply);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }
}
