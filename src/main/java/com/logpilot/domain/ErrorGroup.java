package com.logpilot.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 异常聚合组：相同指纹（异常类 + 归一化消息 + 顶层堆栈）的事件归为一组，
 * 平台围绕"组"做分析、修复建议与闭环管理。
 *
 * <p>状态流转：OPEN（待处理）→ ANALYZED（已分析/处理中）→ RESOLVED（已修复）/ IGNORED（已忽略）
 */
@Entity
@Table(name = "error_group", uniqueConstraints = @UniqueConstraint(columnNames = "fingerprint"))
@Getter
@Setter
public class ErrorGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String fingerprint;

    /** 异常类名，如 java.lang.NullPointerException */
    private String exceptionClass;

    /** 归一化后的消息模板（数字/ID/UUID/IP 已替换为占位符） */
    @Column(length = 600)
    private String template;

    /** 顶层业务堆栈帧 class#method，用于定位代码 */
    private String topFrame;

    private String level;

    private LocalDateTime firstSeen;

    private LocalDateTime lastSeen;

    private Long totalCount = 0L;

    /** OPEN / ANALYZED / RESOLVED / IGNORED */
    private String status = "OPEN";

    /** 命中的知识库规则 */
    private Long knowledgeRuleId;

    private String knowledgeName;

    /** 内置知识库给出的修复建议 */
    @Column(length = 6000)
    private String suggestion;

    private String severity;

    /** 出现该异常的日志源 id 列表（逗号分隔） */
    private String sourceIds = "";

    private Boolean aiAnalyzed = false;

    @Column(length = 8000)
    private String aiAnalysis;

    private String aiModel;

    private LocalDateTime aiAnalyzedAt;

    /** 修复说明（闭环记录） */
    @Column(length = 2000)
    private String fixNote;

    /** 处理人 */
    @Column(length = 60)
    private String assignee;

    /** 标签，逗号分隔，如：数据库,支付链路 */
    @Column(length = 200)
    private String tags;

    private LocalDateTime fixedAt;

    private LocalDateTime updatedAt = LocalDateTime.now();
}
