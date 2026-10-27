package com.logpilot.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 操作审计：记录关键操作（配置变更、状态流转、AI 分析、采集触发等），
 * 回答"谁在什么时候做了什么"。
 */
@Entity
@Table(name = "audit_log", indexes = {
        @Index(name = "idx_audit_created", columnList = "createdAt"),
        @Index(name = "idx_audit_actor", columnList = "actor")
})
@Getter
@Setter
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 操作人（来自系统设置 ops.actor） */
    private String actor;

    /** 动作，如 保存服务器 / 更新异常状态 / AI分析 */
    private String action;

    /** 对象类型：SERVER / SOURCE / GROUP / RULE / ALERT / SETTING / CHANNEL / SILENCE */
    private String targetType;

    private Long targetId;

    private String targetName;

    @Column(length = 1000)
    private String detail;

    private LocalDateTime createdAt = LocalDateTime.now();
}
