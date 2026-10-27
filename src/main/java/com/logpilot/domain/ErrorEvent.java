package com.logpilot.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 一条异常事件（从日志中解析出的 ERROR/FATAL/异常记录）。
 */
@Entity
@Table(name = "error_event", indexes = {
        @Index(name = "idx_event_group", columnList = "groupId"),
        @Index(name = "idx_event_created", columnList = "createdAt"),
        @Index(name = "idx_event_source", columnList = "sourceId")
})
@Getter
@Setter
public class ErrorEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long groupId;

    private Long sourceId;

    private Long serverId;

    private String filePath;

    /** 日志中解析出的发生时间（解析失败则为采集时间） */
    private LocalDateTime occurredAt;

    /** 采集（入库）时间 */
    private LocalDateTime createdAt = LocalDateTime.now();

    private String level;

    private String logger;

    private String thread;

    @Column(length = 2000)
    private String message;

    @Column(length = 8000)
    private String stackTrace;

    private String fingerprint;

    /** 原始日志文本（截断保存） */
    @Column(length = 12000)
    private String raw;
}
