package com.logpilot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 系统设置（KV）：AI 接入、SMTP 邮件、事件保留期等。
 */
@Entity
@Table(name = "system_setting")
@Getter
@Setter
public class SystemSetting {

    /** key/value 为 SQL 保留字，列名使用 setting_key / setting_value。 */
    @Id
    @Column(name = "setting_key", length = 100)
    private String key;

    @Column(name = "setting_value", length = 4000)
    private String value;

    private LocalDateTime updatedAt = LocalDateTime.now();
}
