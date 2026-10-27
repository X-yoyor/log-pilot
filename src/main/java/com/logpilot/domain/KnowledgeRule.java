package com.logpilot.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 异常知识库规则：按正则匹配异常文本，给出原因分析与修复建议。
 * 平台内置常见 Java 异常规则，支持在页面自定义扩展。
 */
@Entity
@Table(name = "knowledge_rule")
@Getter
@Setter
public class KnowledgeRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    /** 匹配正则（大小写敏感），作用于 异常类+消息模板+顶层堆栈 组合文本 */
    @Column(nullable = false, length = 600)
    private String pattern;

    /** HIGH / MID / LOW */
    private String severity = "MID";

    /** 原因分析 */
    @Column(length = 3000)
    private String cause;

    /** 修复建议 */
    @Column(length = 6000)
    private String suggestion;

    private Boolean enabled = true;

    /** 是否内置规则（内置规则不可被误删，可停用） */
    private Boolean builtin = false;

    private Long hitCount = 0L;

    private Integer sortOrder = 100;

    private LocalDateTime createdAt = LocalDateTime.now();
}
