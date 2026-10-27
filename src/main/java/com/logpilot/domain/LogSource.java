package com.logpilot.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 日志源：一条配置 = 某台服务器上的一组日志文件。
 *
 * <p>logPath 规则：
 * <ul>
 *   <li>含通配符（* / ?）：按 glob 匹配，如 /var/log/myapp/*.log、D:/logs/app/error*.log</li>
 *   <li>是具体文件：仅采集该文件</li>
 *   <li>是目录：采集目录下所有 *.log（不递归）</li>
 * </ul>
 */
@Entity
@Table(name = "log_source")
@Getter
@Setter
public class LogSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** SSH：关联 server_node.id；LOCAL：为空 */
    private Long serverId;

    /** SSH / LOCAL */
    private String mode = "SSH";

    @Column(nullable = false)
    private String logPath;

    /** AUTO：自动识别标准日志格式（时间戳开头的多行记录）；RAW：逐行处理 */
    private String formatType = "AUTO";

    /** 日志文件编码：UTF-8 / GBK 等 */
    private String encoding = "UTF-8";

    /** 采集间隔（分钟），最小 1 */
    private Integer intervalMinutes = 5;

    private Boolean enabled = true;

    private LocalDateTime lastCollectTime;

    private LocalDateTime nextCollectTime;

    private String remark;

    private LocalDateTime createdAt = LocalDateTime.now();

    @Transient
    public boolean isLocal() {
        return "LOCAL".equalsIgnoreCase(mode);
    }
}
