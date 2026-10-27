package com.logpilot.service;

import com.logpilot.domain.AuditLog;
import com.logpilot.repo.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 操作审计：记录关键写操作。操作人来自系统设置 ops.actor（内网工具，暂无登录体系）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository repo;
    private final SettingService settings;

    public void record(String action, String targetType, Long targetId, String targetName, String detail) {
        try {
            AuditLog a = new AuditLog();
            a.setActor(settings.get("ops.actor", "admin"));
            a.setAction(action);
            a.setTargetType(targetType);
            a.setTargetId(targetId);
            a.setTargetName(targetName);
            a.setDetail(detail == null ? null : (detail.length() > 950 ? detail.substring(0, 950) : detail));
            repo.save(a);
        } catch (Exception e) {
            log.warn("审计记录写入失败: {}", e.getMessage());
        }
    }
}
