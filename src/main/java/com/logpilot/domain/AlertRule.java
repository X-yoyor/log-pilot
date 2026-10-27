package com.logpilot.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 告警规则。
 *
 * <p>指标类型：
 * <ul>
 *   <li>EVENT_COUNT：时间窗口内异常事件总数 ≥ 阈值</li>
 *   <li>NEW_GROUP：时间窗口内新增异常组数量 ≥ 阈值</li>
 * </ul>
 */
@Entity
@Table(name = "alert_rule")
@Getter
@Setter
public class AlertRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    /** EVENT_COUNT / NEW_GROUP */
    private String metric = "EVENT_COUNT";

    /** 作用范围：null = 全部日志源，否则为 log_source.id */
    private Long sourceId;

    private Integer threshold = 50;

    /** 统计窗口（分钟） */
    private Integer windowMinutes = 30;

    /** 冷却时间（分钟），避免重复轰炸 */
    private Integer cooldownMinutes = 60;

    /** Webhook 地址（POST JSON，企业微信/钉钉/飞书机器人可转换） */
    @Column(length = 1000)
    private String webhookUrl;

    /** 告警邮件接收人（逗号分隔，需在系统设置中配置 SMTP） */
    private String emailTo;

    /** 引用的通知渠道（通知渠道页维护）；与上方直填字段可共存 */
    private Long channelId;

    private Boolean enabled = true;

    private LocalDateTime lastTriggerAt;

    private Long triggerCount = 0L;

    private LocalDateTime createdAt = LocalDateTime.now();
}
