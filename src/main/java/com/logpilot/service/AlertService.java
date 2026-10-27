package com.logpilot.service;

import com.logpilot.domain.AlertRecord;
import com.logpilot.domain.AlertRule;
import com.logpilot.domain.LogSource;
import com.logpilot.domain.NotifyChannel;
import com.logpilot.domain.Silence;
import com.logpilot.repo.AlertRecordRepository;
import com.logpilot.repo.AlertRuleRepository;
import com.logpilot.repo.ErrorEventRepository;
import com.logpilot.repo.ErrorGroupRepository;
import com.logpilot.repo.NotifyChannelRepository;
import com.logpilot.repo.SilenceRepository;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * 告警：每次采集后评估规则（窗口内事件数 / 新增异常组数），
 * 触发后按 Webhook / 邮件（直填或引用通知渠道）通知并记录，冷却期内不重复触发。
 * 静默窗口内的日志源不参与评估；全局规则统计时排除静默源的事件。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertService {

    private final AlertRuleRepository ruleRepo;
    private final AlertRecordRepository recordRepo;
    private final ErrorEventRepository eventRepo;
    private final ErrorGroupRepository groupRepo;
    private final SettingService settings;
    private final SilenceRepository silenceRepo;
    private final NotifyChannelRepository channelRepo;

    private final RestTemplate restTemplate = new RestTemplate();

    @Transactional
    public void evaluateAfterCollect(LogSource source) {
        List<AlertRule> rules = ruleRepo.findByEnabledTrue();
        if (rules.isEmpty()) return;
        LocalDateTime now = LocalDateTime.now();

        List<Silence> activeSilences = silenceRepo.findByUntilAtAfter(now);
        boolean thisSourceSilenced = activeSilences.stream()
                .anyMatch(s -> Objects.equals(source.getId(), s.getSourceId()));
        if (thisSourceSilenced) return;
        List<Long> silencedSourceIds = activeSilences.stream()
                .map(Silence::getSourceId).filter(Objects::nonNull).toList();

        for (AlertRule rule : rules) {
            try {
                if (rule.getSourceId() != null && !rule.getSourceId().equals(source.getId())) continue;

                if (rule.getLastTriggerAt() != null) {
                    LocalDateTime cooldownEnd = rule.getLastTriggerAt()
                            .plusMinutes(rule.getCooldownMinutes() == null ? 60 : rule.getCooldownMinutes());
                    if (now.isBefore(cooldownEnd)) continue;
                }

                int window = rule.getWindowMinutes() == null ? 30 : rule.getWindowMinutes();
                LocalDateTime windowStart = now.minusMinutes(window);

                long metricValue;
                if ("NEW_GROUP".equals(rule.getMetric())) {
                    metricValue = groupRepo.countByFirstSeenAfter(windowStart);
                } else if (rule.getSourceId() == null && !silencedSourceIds.isEmpty()) {
                    metricValue = eventRepo.countByCreatedAtAfterAndSourceIdNotIn(windowStart, silencedSourceIds);
                } else if (rule.getSourceId() == null) {
                    metricValue = eventRepo.countByCreatedAtAfter(windowStart);
                } else {
                    metricValue = eventRepo.countByCreatedAtAfterAndSourceId(windowStart, rule.getSourceId());
                }

                int threshold = rule.getThreshold() == null ? 50 : rule.getThreshold();
                if (metricValue >= threshold) {
                    fire(rule, metricValue, threshold, window, now);
                }
            } catch (Exception e) {
                log.warn("告警规则评估失败 {}: {}", rule.getName(), e.getMessage());
            }
        }
    }

    private void fire(AlertRule rule, long value, int threshold, int window, LocalDateTime now) {
        String title = String.format("[LogPilot告警] %s：%d 分钟内%s=%d，超过阈值 %d",
                rule.getName(), window,
                "NEW_GROUP".equals(rule.getMetric()) ? "新增异常类型" : "异常事件数",
                value, threshold);
        String content = String.format(
                "告警规则: %s\n指标: %s\n窗口: 最近 %d 分钟\n当前值: %d（阈值 %d）\n触发时间: %s\n\n请登录 LogPilot 查看异常详情并处理。",
                rule.getName(), rule.getMetric(), window, value, threshold, now);

        List<String> webhookTargets = new ArrayList<>();
        List<String> emailTargets = new ArrayList<>();
        if (rule.getWebhookUrl() != null && !rule.getWebhookUrl().isBlank()) webhookTargets.add(rule.getWebhookUrl().trim());
        if (rule.getEmailTo() != null && !rule.getEmailTo().isBlank()) emailTargets.add(rule.getEmailTo().trim());
        if (rule.getChannelId() != null) {
            channelRepo.findById(rule.getChannelId())
                    .filter(c -> Boolean.TRUE.equals(c.getEnabled()))
                    .ifPresent(c -> {
                        if ("EMAIL".equalsIgnoreCase(c.getType())) emailTargets.add(c.getTarget());
                        else webhookTargets.add(c.getTarget());
                    });
        }

        if (webhookTargets.isEmpty() && emailTargets.isEmpty()) {
            record(rule, "WEBHOOK", "SKIP", title, content, "未配置通知渠道，仅记录");
        } else {
            for (String url : webhookTargets) {
                sendWebhookUrl(rule, url, title, content, now);
            }
            for (String to : emailTargets) {
                try {
                    String msg = sendMail(to.split("[,，]"), title, content);
                    record(rule, "EMAIL", "OK", title, content, msg);
                } catch (Exception e) {
                    record(rule, "EMAIL", "FAIL", title, content, "邮件发送失败: " + e.getMessage());
                }
            }
        }

        rule.setLastTriggerAt(now);
        rule.setTriggerCount((rule.getTriggerCount() == null ? 0 : rule.getTriggerCount()) + 1);
        ruleRepo.save(rule);
    }

    private void sendWebhookUrl(AlertRule rule, String url, String title, String content, LocalDateTime now) {
        try {
            Map<String, Object> payload = Map.of(
                    "source", "logpilot",
                    "title", title,
                    "content", content,
                    "time", now.toString(),
                    "level", "ERROR");
            restTemplate.postForObject(url, payload, String.class);
            record(rule, "WEBHOOK", "OK", title, content, "Webhook 通知成功: " + url);
        } catch (Exception e) {
            record(rule, "WEBHOOK", "FAIL", title, content, "Webhook 通知失败 " + url + ": " + e.getMessage());
        }
    }

    /** 通用邮件发送（告警/报告/测试共用）。 */
    public String sendMail(String[] to, String subject, String content) {
        if (!settings.mailEnabled()) {
            throw new IllegalStateException("SMTP 未配置（系统设置→邮件）");
        }
        try {
            JavaMailSenderImpl sender = new JavaMailSenderImpl();
            sender.setHost(settings.get(SettingService.MAIL_HOST, ""));
            sender.setPort(settings.getInt(SettingService.MAIL_PORT, 465));
            sender.setUsername(settings.get(SettingService.MAIL_USERNAME, ""));
            sender.setPassword(settings.get(SettingService.MAIL_PASSWORD, ""));
            sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
            Properties props = sender.getJavaMailProperties();
            props.put("mail.transport.protocol", "smtp");
            if (settings.getBool(SettingService.MAIL_SSL, true)) {
                props.put("mail.smtp.ssl.enable", "true");
            } else {
                props.put("mail.smtp.starttls.enable", "true");
            }
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.connectiontimeout", "10000");
            props.put("mail.smtp.timeout", "15000");

            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(settings.get(SettingService.MAIL_FROM, ""));
            helper.setTo(InternetAddress.parse(String.join(",", to)));
            helper.setSubject(subject);
            helper.setText(content, false);
            sender.send(message);
            return "邮件已发送至 " + String.join(",", to);
        } catch (Exception e) {
            throw new IllegalStateException("邮件发送失败: " + e.getMessage(), e);
        }
    }

    /** Webhook 连通性测试。 */
    public String testWebhook(String url) {
        try {
            Map<String, Object> payload = Map.of(
                    "source", "logpilot",
                    "title", "[LogPilot] 渠道测试",
                    "content", "这是一条 LogPilot 通知渠道测试消息。\n时间: " + LocalDateTime.now(),
                    "time", LocalDateTime.now().toString(),
                    "level", "INFO");
            restTemplate.postForObject(url, payload, String.class);
            return "Webhook 测试成功";
        } catch (Exception e) {
            throw new IllegalStateException("Webhook 测试失败: " + e.getMessage(), e);
        }
    }

    /** 邮件配置测试。 */
    public String testMail(String to) {
        return sendMail(new String[]{to}, "[LogPilot] 邮件配置测试",
                "这是一封 LogPilot 测试邮件，收到即配置成功。\n时间: " + LocalDateTime.now());
    }

    private void record(AlertRule rule, String channel, String status, String title, String content, String detail) {
        AlertRecord r = new AlertRecord();
        r.setRuleId(rule.getId());
        r.setRuleName(rule.getName());
        r.setTitle(title);
        r.setContent(content);
        r.setChannel(channel);
        r.setStatus(status);
        r.setDetail(detail);
        recordRepo.save(r);
        log.info("告警触发 [{}] {} -> {} {}", rule.getName(), channel, status, detail);
    }
}
