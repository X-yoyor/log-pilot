package com.logpilot.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 内网服务器节点（SSH 方式采集时使用；本地日志源无需服务器）。
 */
@Entity
@Table(name = "server_node")
@Getter
@Setter
public class ServerNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 显示名称，如 "订单服务-生产01" */
    @Column(nullable = false)
    private String name;

    private String host;

    private Integer port = 22;

    private String username;

    /** PASSWORD / KEY */
    private String authType = "PASSWORD";

    /** AES 加密存储 */
    @Column(length = 4000)
    private String password;

    /** 私钥 PEM（KEY 认证），AES 加密存储 */
    @Column(length = 8000)
    private String privateKey;

    /** SSH / LOCAL */
    private String type = "SSH";

    private String remark;

    private Boolean enabled = true;

    private LocalDateTime lastTestTime;

    private Boolean lastTestOk;

    private String lastTestMsg;

    private LocalDateTime createdAt = LocalDateTime.now();

    @Transient
    public String getDisplay() {
        return name + " (" + (isLocal() ? "本地" : host + ":" + port) + ")";
    }

    @Transient
    public boolean isLocal() {
        return "LOCAL".equalsIgnoreCase(type);
    }
}
