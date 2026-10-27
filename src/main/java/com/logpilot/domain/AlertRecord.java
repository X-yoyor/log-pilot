package com.logpilot.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 告警触发记录。
 */
@Entity
@Table(name = "alert_record")
@Getter
@Setter
public class AlertRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long ruleId;

    private String ruleName;

    private String title;

    @Column(length = 3000)
    private String content;

    /** WEBHOOK / EMAIL */
    private String channel;

    /** OK / FAIL / SKIP */
    private String status;

    @Column(length = 2000)
    private String detail;

    private LocalDateTime createdAt = LocalDateTime.now();
}
