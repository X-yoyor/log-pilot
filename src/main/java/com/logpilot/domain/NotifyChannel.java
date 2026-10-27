package com.logpilot.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 通知渠道（字典）：告警规则引用渠道而非各自配置地址，便于统一维护。
 * type = WEBHOOK 时 target 为地址；type = EMAIL 时 target 为收件人（逗号分隔）。
 */
@Entity
@Table(name = "notify_channel")
@Getter
@Setter
public class NotifyChannel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    /** WEBHOOK / EMAIL */
    private String type = "WEBHOOK";

    @Column(length = 1000)
    private String target;

    @Column(length = 300)
    private String remark;

    private Boolean enabled = true;

    private LocalDateTime createdAt = LocalDateTime.now();
}
