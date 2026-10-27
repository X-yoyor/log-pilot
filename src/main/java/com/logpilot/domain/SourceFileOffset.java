package com.logpilot.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 日志文件采集位点（增量读取）。文件变小视为轮转，自动从头采集。
 */
@Entity
@Table(name = "source_file_offset", uniqueConstraints = @UniqueConstraint(columnNames = {"sourceId", "filePath"}))
@Getter
@Setter
public class SourceFileOffset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long sourceId;

    private String filePath;

    /** 文件当前大小（字节） */
    private Long fileSize = 0L;

    /** 已读取到的字节偏移 */
    private Long position = 0L;

    private LocalDateTime lastReadAt;

    /** 本文件累计读取行数 */
    private Long totalLines = 0L;
}
