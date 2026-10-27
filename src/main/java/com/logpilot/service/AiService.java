package com.logpilot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.logpilot.domain.ErrorEvent;
import com.logpilot.domain.ErrorGroup;
import com.logpilot.repo.ErrorEventRepository;
import com.logpilot.repo.ErrorGroupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 分析（可插拔）：兼容 OpenAI Chat Completions 协议的任意服务，
 * 如 OpenAI、Azure OpenAI、智谱 GLM、DeepSeek、通义千问（DashScope 兼容模式）、Ollama 等。
 * 在「系统设置」中开启并填入 baseUrl/apiKey/model 即可。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiService {

    private final SettingService settings;
    private final ErrorGroupRepository groupRepo;
    private final ErrorEventRepository eventRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public static class AiDisabledException extends IllegalStateException {
        public AiDisabledException(String msg) {
            super(msg);
        }
    }

    public static class AiCallException extends RuntimeException {
        public AiCallException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    public boolean enabled() {
        return settings.aiEnabled();
    }

    /**
     * 对异常组执行 AI 根因分析并保存结果。
     */
    public String analyzeGroup(ErrorGroup group, List<ErrorEvent> samples) {
        if (!enabled()) {
            throw new AiDisabledException("AI 未启用：请在 系统设置 中配置并开启 AI（baseUrl / apiKey / model）");
        }
        String prompt = buildPrompt(group, samples);
        String answer = chat(systemPrompt(), prompt);
        group.setAiAnalysis(answer);
        group.setAiAnalyzed(true);
        group.setAiModel(settings.get(SettingService.AI_MODEL, ""));
        group.setAiAnalyzedAt(LocalDateTime.now());
        if ("OPEN".equals(group.getStatus())) group.setStatus("ANALYZED");
        group.setUpdatedAt(LocalDateTime.now());
        groupRepo.save(group);
        return answer;
    }

    /** 连通性测试。 */
    public String test() {
        if (!enabled()) {
            throw new AiDisabledException("AI 未启用或配置不完整（baseUrl / apiKey / model 均必填）");
        }
        return chat("你是一个连通性测试器。", "请只回复两个字：正常");
    }

    private String systemPrompt() {
        String extra = settings.get(SettingService.AI_PROMPT, "");
        return "你是一名资深的 Java 后端架构师与线上稳定性专家，擅长根据日志和堆栈定位根因并给出落地方案。\n"
                + "请严格按以下 Markdown 结构用中文输出：\n"
                + "## 问题概述\n（1-2 句话说明发生了什么）\n"
                + "## 根因分析\n（列出最可能的原因，按可能性排序，给出判断依据）\n"
                + "## 修复方案\n（具体可执行步骤，涉及代码时给出关键示例代码）\n"
                + "## 预防措施\n（监控告警/编码规范/流程建议）\n"
                + (extra == null || extra.isEmpty() ? "" : "\n补充要求：\n" + extra);
    }

    private String buildPrompt(ErrorGroup group, List<ErrorEvent> samples) {
        StringBuilder sb = new StringBuilder();
        sb.append("以下是内网日志平台聚合出的一类异常，请分析并给出修复方案。\n\n");
        sb.append("- 异常类: ").append(group.getExceptionClass()).append('\n');
        sb.append("- 消息模板(已脱敏归一化): ").append(group.getTemplate()).append('\n');
        sb.append("- 业务代码位置: ").append(group.getTopFrame() == null || group.getTopFrame().isEmpty() ? "未知" : group.getTopFrame()).append('\n');
        sb.append("- 出现次数: ").append(group.getTotalCount()).append('\n');
        sb.append("- 首次出现: ").append(group.getFirstSeen()).append("，最近出现: ").append(group.getLastSeen()).append('\n');
        if (group.getKnowledgeName() != null) {
            sb.append("\n内置知识库初判: ").append(group.getKnowledgeName()).append('\n');
        }
        if (samples != null && !samples.isEmpty()) {
            sb.append("\n最近一次原始日志（可能截断）:\n```\n");
            String raw = samples.get(samples.size() - 1).getRaw();
            if (raw != null) {
                sb.append(raw.length() > 4000 ? raw.substring(0, 4000) : raw);
            }
            sb.append("\n```");
        }
        return sb.toString();
    }

    private String chat(String system, String user) {
        String baseUrl = settings.get(SettingService.AI_BASE_URL, "").replaceAll("/+$", "");
        String url = baseUrl.endsWith("/chat/completions") ? baseUrl : baseUrl + "/chat/completions";
        int timeout = Math.max(10, settings.getInt(SettingService.AI_TIMEOUT, 60));

        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", settings.get(SettingService.AI_MODEL, ""));
            body.put("temperature", 0.2);
            body.put("stream", false);
            ArrayNode messages = body.putArray("messages");
            ObjectNode m1 = messages.addObject();
            m1.put("role", "system");
            m1.put("content", system);
            ObjectNode m2 = messages.addObject();
            m2.put("role", "user");
            m2.put("content", user);

            HttpRequest.Builder hb = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(timeout))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
            String apiKey = settings.get(SettingService.AI_API_KEY, "");
            if (!apiKey.isEmpty()) {
                hb.header("Authorization", "Bearer " + apiKey);
            }

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();
            HttpResponse<String> resp = client.send(hb.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new AiCallException("AI 接口返回 HTTP " + resp.statusCode() + ": "
                        + truncate(resp.body(), 500), null);
            }
            JsonNode root = objectMapper.readTree(resp.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.asText().isBlank()) {
                throw new AiCallException("AI 返回内容为空: " + truncate(resp.body(), 500), null);
            }
            return content.asText();
        } catch (AiCallException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiCallException("AI 调用被中断", e);
        } catch (Exception e) {
            throw new AiCallException("AI 调用失败: " + e.getMessage(), e);
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
