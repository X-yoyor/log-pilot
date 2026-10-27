package com.logpilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * LogPilot —— 内网日志采集与智能分析平台
 *
 * <p>核心能力：
 * <ul>
 *   <li>配置内网服务器（SSH）与日志路径，定时增量采集日志</li>
 *   <li>解析多行堆栈，识别 ERROR/FATAL/Exception，指纹聚合去重</li>
 *   <li>内置 Java 常见异常知识库，自动给出原因与修复建议</li>
 *   <li>可选接入 OpenAI 兼容的大模型，生成根因分析与修复方案</li>
 *   <li>阈值告警（Webhook / 邮件），异常修复闭环（待处理→已分析→已修复）</li>
 * </ul>
 */
@SpringBootApplication
@EnableScheduling
public class LogPilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(LogPilotApplication.class, args);
    }
}
