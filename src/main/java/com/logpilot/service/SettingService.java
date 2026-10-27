package com.logpilot.service;

import com.logpilot.domain.SystemSetting;
import com.logpilot.repo.SystemSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * 系统设置（KV）：AI 接入、SMTP 邮件、保留期等。
 */
@Service
@RequiredArgsConstructor
public class SettingService {

    public static final String AI_ENABLED = "ai.enabled";
    public static final String AI_BASE_URL = "ai.baseUrl";
    public static final String AI_API_KEY = "ai.apiKey";
    public static final String AI_MODEL = "ai.model";
    public static final String AI_TIMEOUT = "ai.timeoutSeconds";
    public static final String AI_PROMPT = "ai.prompt";
    public static final String MAIL_HOST = "mail.host";
    public static final String MAIL_PORT = "mail.port";
    public static final String MAIL_USERNAME = "mail.username";
    public static final String MAIL_PASSWORD = "mail.password";
    public static final String MAIL_FROM = "mail.from";
    public static final String MAIL_SSL = "mail.ssl";
    public static final String RETENTION_DAYS = "retention.days";

    private final SystemSettingRepository repo;

    public String get(String key, String def) {
        return repo.findById(key).map(SystemSetting::getValue).filter(v -> v != null && !v.isEmpty()).orElse(def);
    }

    public boolean getBool(String key, boolean def) {
        String v = get(key, String.valueOf(def));
        return "true".equalsIgnoreCase(v) || "1".equals(v) || "yes".equalsIgnoreCase(v);
    }

    public int getInt(String key, int def) {
        try {
            return Integer.parseInt(get(key, String.valueOf(def)).trim());
        } catch (Exception e) {
            return def;
        }
    }

    @Transactional
    public void put(String key, String value) {
        SystemSetting s = repo.findById(key).orElseGet(() -> {
            SystemSetting n = new SystemSetting();
            n.setKey(key);
            return n;
        });
        s.setValue(value == null ? "" : value.trim());
        s.setUpdatedAt(java.time.LocalDateTime.now());
        repo.save(s);
    }

    public Map<String, String> all() {
        Map<String, String> m = new HashMap<>();
        repo.findAll().forEach(s -> m.put(s.getKey(), s.getValue()));
        return m;
    }

    public boolean aiEnabled() {
        return getBool(AI_ENABLED, false)
                && !get(AI_BASE_URL, "").isEmpty()
                && !get(AI_API_KEY, "").isEmpty()
                && !get(AI_MODEL, "").isEmpty();
    }

    public boolean mailEnabled() {
        return !get(MAIL_HOST, "").isEmpty() && !get(MAIL_FROM, "").isEmpty();
    }

    @Transactional
    public void seedDefaults() {
        putIfAbsent(AI_ENABLED, "false");
        putIfAbsent(AI_BASE_URL, "https://api.openai.com/v1");
        putIfAbsent(AI_API_KEY, "");
        putIfAbsent(AI_MODEL, "gpt-4o-mini");
        putIfAbsent(AI_TIMEOUT, "60");
        putIfAbsent(AI_PROMPT, "");
        putIfAbsent(RETENTION_DAYS, "30");
        putIfAbsent(MAIL_HOST, "");
        putIfAbsent(MAIL_PORT, "465");
        putIfAbsent(MAIL_USERNAME, "");
        putIfAbsent(MAIL_PASSWORD, "");
        putIfAbsent(MAIL_FROM, "");
        putIfAbsent(MAIL_SSL, "true");
        putIfAbsent("ops.actor", "admin");
    }

    private void putIfAbsent(String key, String def) {
        if (repo.findById(key).isEmpty()) put(key, def);
    }
}
