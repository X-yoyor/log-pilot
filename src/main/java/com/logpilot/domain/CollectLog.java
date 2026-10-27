package com.logpilot.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 采集执行记录（审计）。
 */
@Entity
@Table(name = "collect_log")
@Getter
@Setter
public class CollectLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long sourceId;

    private String sourceName;

    private LocalDateTime startAt = LocalDateTime.now();

    private LocalDateTime endAt;

    /** OK / FAIL */
    private String status;

    private Integer files = 0;

    private Long lines = 0L;

    private Integer events = 0;

    /** 耗时毫秒 */
    private Long costMs = 0L;

    @Column(length = 2000)
    private String message;
}
