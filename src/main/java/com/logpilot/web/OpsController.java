package com.logpilot.web;

import com.logpilot.common.ApiResponse;
import com.logpilot.domain.NotifyChannel;
import com.logpilot.domain.Silence;
import com.logpilot.repo.ErrorGroupRepository;
import com.logpilot.repo.NotifyChannelRepository;
import com.logpilot.repo.SilenceRepository;
import com.logpilot.service.AlertService;
import com.logpilot.service.AuditService;
import com.logpilot.service.ReportService;
import com.logpilot.service.SettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;

/**
 * 运维扩展操作：静默窗口、通知渠道、报告邮件发送。
 */
@Controller
@RequiredArgsConstructor
public class OpsController {

    private final SilenceRepository silenceRepo;
    private final NotifyChannelRepository channelRepo;
    private final ErrorGroupRepository groupRepo;
    private final AlertService alertService;
    private final ReportService reportService;
    private final AuditService auditService;

    // ==================== 静默窗口 ====================

    @PostMapping("/silence/save")
    public String saveSilence(@RequestParam String targetType,
                              @RequestParam(required = false) Long sourceId,
                              @RequestParam(required = false) Long groupId,
                              @RequestParam(defaultValue = "60") Integer minutes,
                              @RequestParam(required = false) String reason,
                              RedirectAttributes ra) {
        Silence s = new Silence();
        if ("GROUP".equalsIgnoreCase(targetType)) {
            if (groupId == null) {
                ra.addFlashAttribute("error", "静默异常组必须填写组 ID");
                return "redirect:/alerts";
            }
            s.setGroupId(groupId);
        } else {
            if (sourceId == null) {
                ra.addFlashAttribute("error", "静默日志源必须选择目标");
                return "redirect:/alerts";
            }
            s.setSourceId(sourceId);
        }
        s.setReason(reason);
        s.setUntilAt(LocalDateTime.now().plusMinutes(Math.max(5, minutes)));
        silenceRepo.save(s);
        String target = s.isSourceSilence() ? "日志源#" + sourceId : "异常组#" + groupId;
        auditService.record("创建静默", "SILENCE", s.getId(), target,
                "时长 " + minutes + " 分钟，原因: " + (reason == null ? "" : reason));
        ra.addFlashAttribute("msg", "静默已生效：" + target + " 至 " + s.getUntilAt());
        return "redirect:/alerts";
    }

    @PostMapping("/silence/{id}/end")
    public String endSilence(@PathVariable Long id, RedirectAttributes ra) {
        Silence s = silenceRepo.findById(id).orElse(null);
        if (s != null) {
            s.setUntilAt(LocalDateTime.now());
            silenceRepo.save(s);
            auditService.record("提前结束静默", "SILENCE", id, s.isSourceSilence() ? "日志源#" + s.getSourceId() : "异常组#" + s.getGroupId(), null);
            ra.addFlashAttribute("msg", "静默已结束");
        }
        return "redirect:/alerts";
    }

    @PostMapping("/silence/{id}/delete")
    public String deleteSilence(@PathVariable Long id, RedirectAttributes ra) {
        silenceRepo.deleteById(id);
        auditService.record("删除静默记录", "SILENCE", id, null, null);
        ra.addFlashAttribute("msg", "静默记录已删除");
        return "redirect:/alerts";
    }

    // ==================== 通知渠道 ====================

    @PostMapping("/channels/save")
    public String saveChannel(@RequestParam(required = false) Long id,
                              @RequestParam String name,
                              @RequestParam String type,
                              @RequestParam String target,
                              @RequestParam(required = false) String remark,
                              RedirectAttributes ra) {
        NotifyChannel c = (id != null) ? channelRepo.findById(id).orElse(new NotifyChannel()) : new NotifyChannel();
        c.setName(name);
        c.setType("EMAIL".equalsIgnoreCase(type) ? "EMAIL" : "WEBHOOK");
        c.setTarget(target.trim());
        c.setRemark(remark);
        channelRepo.save(c);
        auditService.record("保存通知渠道", "CHANNEL", c.getId(), name, c.getType() + ": " + c.getTarget());
        ra.addFlashAttribute("msg", "通知渠道已保存");
        return "redirect:/alerts";
    }

    @PostMapping("/channels/{id}/delete")
    public String deleteChannel(@PathVariable Long id, RedirectAttributes ra) {
        NotifyChannel c = channelRepo.findById(id).orElse(null);
        channelRepo.deleteById(id);
        auditService.record("删除通知渠道", "CHANNEL", id, c == null ? null : c.getName(), null);
        ra.addFlashAttribute("msg", "通知渠道已删除");
        return "redirect:/alerts";
    }

    @PostMapping("/channels/{id}/toggle")
    public String toggleChannel(@PathVariable Long id, RedirectAttributes ra) {
        NotifyChannel c = channelRepo.findById(id).orElse(null);
        if (c != null) {
            c.setEnabled(!Boolean.TRUE.equals(c.getEnabled()));
            channelRepo.save(c);
            auditService.record("切换通知渠道状态", "CHANNEL", id, c.getName(), Boolean.TRUE.equals(c.getEnabled()) ? "启用" : "停用");
            ra.addFlashAttribute("msg", Boolean.TRUE.equals(c.getEnabled()) ? "已启用" : "已停用");
        }
        return "redirect:/alerts";
    }

    @PostMapping("/channels/{id}/test")
    @ResponseBody
    public ApiResponse<String> testChannel(@PathVariable Long id) {
        NotifyChannel c = channelRepo.findById(id).orElse(null);
        if (c == null) return ApiResponse.error("渠道不存在");
        try {
            if ("EMAIL".equalsIgnoreCase(c.getType())) {
                String msg = alertService.sendMail(c.getTarget().split("[,，]"),
                        "[LogPilot] 通知渠道测试", "这是一条 LogPilot 渠道测试邮件。\n时间: " + LocalDateTime.now());
                return ApiResponse.ok(msg);
            }
            return ApiResponse.ok(alertService.testWebhook(c.getTarget()));
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    // ==================== 报告 ====================

    @PostMapping("/reports/send")
    @ResponseBody
    public ApiResponse<String> sendReport(@RequestParam(defaultValue = "day") String range,
                                          @RequestParam String to) {
        try {
            ReportService.ReportData data = reportService.build(range);
            String subject = data.title() + " " + data.from().toLocalDate() + " ~ " + data.to().toLocalDate();
            String msg = alertService.sendMail(to.split("[,，]"), subject, data.text());
            auditService.record("发送报告邮件", "REPORT", null, subject, to);
            return ApiResponse.ok(msg);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }
}
