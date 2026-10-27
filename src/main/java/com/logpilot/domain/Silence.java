package com.logpilot.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 静默窗口：计划内发布/已知故障处理期间，抑制对应日志源或异常组的告警。
 * 目标二选一：sourceId（静默某日志源）或 groupId（静默某异常组）。
 */
@Entity
@Table(name = "silence")
@Getter
@Setter
public class Silence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 二选一：静默的日志源 id */
    private Long sourceId;

    /** 二选一：静默的异常组 id */
    private Long groupId;

    @Column(length = 300)
    private String reason;

    /** 静默截止时间 */
    private LocalDateTime untilAt;

    private LocalDateTime createdAt = LocalDateTime.now();

    @Transient
    public boolean isActive() {
        return untilAt != null && untilAt.isAfter(LocalDateTime.now());
    }

    /** 剩余分钟数（已结束为 0）。 */
    @Transient
    public long getRemainingMinutes() {
        if (untilAt == null) return 0;
        return Math.max(0, java.time.Duration.between(LocalDateTime.now(), untilAt).toMinutes());
    }

    @Transient
    public boolean isSourceSilence() {
        return sourceId != null;
    }
}
