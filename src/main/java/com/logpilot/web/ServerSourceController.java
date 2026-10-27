package com.logpilot.web;

import com.logpilot.common.ApiResponse;
import com.logpilot.common.CryptoSupport;
import com.logpilot.domain.CollectLog;
import com.logpilot.domain.LogSource;
import com.logpilot.domain.ServerNode;
import com.logpilot.repo.LogSourceRepository;
import com.logpilot.repo.ServerNodeRepository;
import com.logpilot.repo.SourceFileOffsetRepository;
import com.logpilot.service.AuditService;
import com.logpilot.service.CollectService;
import com.logpilot.service.SshService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;

/**
 * 服务器与日志源管理。
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class ServerSourceController {

    private final ServerNodeRepository serverRepo;
    private final LogSourceRepository sourceRepo;
    private final SourceFileOffsetRepository offsetRepo;
    private final SshService sshService;
    private final CollectService collectService;
    private final CryptoSupport crypto;
    private final AuditService auditService;

    // ==================== 服务器 ====================

    @PostMapping("/servers/save")
    public String saveServer(@RequestParam(required = false) Long id,
                             @RequestParam String name,
                             @RequestParam(required = false) String type,
                             @RequestParam(required = false) String host,
                             @RequestParam(required = false, defaultValue = "22") Integer port,
                             @RequestParam(required = false) String username,
                             @RequestParam(required = false, defaultValue = "PASSWORD") String authType,
                             @RequestParam(required = false) String password,
                             @RequestParam(required = false) String privateKey,
                             @RequestParam(required = false) String remark,
                             RedirectAttributes ra) {
        try {
            ServerNode s = (id != null) ? serverRepo.findById(id).orElse(new ServerNode()) : new ServerNode();
            s.setName(name);
            s.setType("LOCAL".equalsIgnoreCase(type) ? "LOCAL" : "SSH");
            s.setHost(host);
            s.setPort(port == null ? 22 : port);
            s.setUsername(username);
            s.setAuthType(authType);
            if (password != null && !password.isBlank()) {
                s.setPassword(crypto.encrypt(password.trim()));
            }
            if (privateKey != null && !privateKey.isBlank()) {
                s.setPrivateKey(crypto.encrypt(privateKey.trim()));
            }
            s.setRemark(remark);
            serverRepo.save(s);
            auditService.record("保存服务器", "SERVER", s.getId(), name, s.getType() + " " + host);
            ra.addFlashAttribute("msg", "服务器已保存");
        } catch (Exception e) {
            ra.addFlashAttribute("error", "保存失败: " + e.getMessage());
        }
        return "redirect:/servers";
    }

    @PostMapping("/servers/{id}/delete")
    public String deleteServer(@PathVariable Long id, RedirectAttributes ra) {
        ServerNode s = serverRepo.findById(id).orElse(null);
        if (s != null) {
            long refs = sourceRepo.findByServerId(id).size();
            if (refs > 0) {
                ra.addFlashAttribute("error", "该服务器下仍有 " + refs + " 个日志源，请先删除或调整");
                return "redirect:/servers";
            }
            serverRepo.deleteById(id);
            auditService.record("删除服务器", "SERVER", id, s.getName(), null);
            ra.addFlashAttribute("msg", "服务器已删除");
        }
        return "redirect:/servers";
    }

    @PostMapping("/servers/{id}/test")
    @ResponseBody
    public ApiResponse<String> testServer(@PathVariable Long id) {
        ServerNode s = serverRepo.findById(id).orElse(null);
        if (s == null) return ApiResponse.error("服务器不存在");
        if (s.isLocal()) {
            s.setLastTestTime(LocalDateTime.now());
            s.setLastTestOk(true);
            s.setLastTestMsg("本地节点无需测试");
            serverRepo.save(s);
            return ApiResponse.ok("本地节点，无需连接测试");
        }
        try {
            long cost = sshService.test(s);
            s.setLastTestTime(LocalDateTime.now());
            s.setLastTestOk(true);
            s.setLastTestMsg("连通正常，耗时 " + cost + "ms");
            serverRepo.save(s);
            return ApiResponse.ok(s.getLastTestMsg());
        } catch (Exception e) {
            s.setLastTestTime(LocalDateTime.now());
            s.setLastTestOk(false);
            s.setLastTestMsg(e.getMessage());
            serverRepo.save(s);
            return ApiResponse.error(e.getMessage());
        }
    }

    // ==================== 日志源 ====================

    @PostMapping("/sources/save")
    public String saveSource(@RequestParam(required = false) Long id,
                             @RequestParam String name,
                             @RequestParam(required = false) String mode,
                             @RequestParam(required = false) Long serverId,
                             @RequestParam String logPath,
                             @RequestParam(required = false, defaultValue = "AUTO") String formatType,
                             @RequestParam(required = false, defaultValue = "UTF-8") String encoding,
                             @RequestParam(required = false, defaultValue = "5") Integer intervalMinutes,
                             @RequestParam(required = false) String remark,
                             RedirectAttributes ra) {
        try {
            boolean sshMode = !"LOCAL".equalsIgnoreCase(mode);
            if (sshMode && serverId == null) {
                throw new IllegalArgumentException("SSH 模式必须选择服务器");
            }
            LogSource s = (id != null) ? sourceRepo.findById(id).orElse(new LogSource()) : new LogSource();
            s.setName(name);
            s.setMode(sshMode ? "SSH" : "LOCAL");
            s.setServerId(sshMode ? serverId : null);
            s.setLogPath(logPath.trim());
            s.setFormatType("RAW".equalsIgnoreCase(formatType) ? "RAW" : "AUTO");
            s.setEncoding(encoding == null || encoding.isBlank() ? "UTF-8" : encoding.trim());
            s.setIntervalMinutes(Math.max(1, intervalMinutes == null ? 5 : intervalMinutes));
            s.setRemark(remark);
            sourceRepo.save(s);
            auditService.record("保存日志源", "SOURCE", s.getId(), name, s.getMode() + " " + s.getLogPath());
            ra.addFlashAttribute("msg", "日志源已保存，下次采集时间: " +
                    (s.getNextCollectTime() == null ? "尽快" : s.getNextCollectTime()));
        } catch (Exception e) {
            ra.addFlashAttribute("error", "保存失败: " + e.getMessage());
        }
        return "redirect:/sources";
    }

    @PostMapping("/sources/{id}/delete")
    public String deleteSource(@PathVariable Long id, RedirectAttributes ra) {
        if (sourceRepo.existsById(id)) {
            LogSource s = sourceRepo.findById(id).orElse(null);
            sourceRepo.deleteById(id);
            offsetRepo.findBySourceId(id).forEach(offsetRepo::delete);
            auditService.record("删除日志源", "SOURCE", id, s == null ? null : s.getName(), null);
            ra.addFlashAttribute("msg", "日志源已删除（历史异常数据保留）");
        }
        return "redirect:/sources";
    }

    @PostMapping("/sources/{id}/toggle")
    public String toggleSource(@PathVariable Long id, RedirectAttributes ra) {
        LogSource s = sourceRepo.findById(id).orElse(null);
        if (s != null) {
            s.setEnabled(!Boolean.TRUE.equals(s.getEnabled()));
            if (Boolean.TRUE.equals(s.getEnabled()) && s.getNextCollectTime() == null) {
                s.setNextCollectTime(LocalDateTime.now().plusSeconds(10));
            }
            sourceRepo.save(s);
            auditService.record("切换日志源状态", "SOURCE", id, s.getName(),
                    Boolean.TRUE.equals(s.getEnabled()) ? "启用" : "停用");
            ra.addFlashAttribute("msg", (Boolean.TRUE.equals(s.getEnabled()) ? "已启用" : "已停用") + ": " + s.getName());
        }
        return "redirect:/sources";
    }

    /** 立即采集（同步执行，返回结果）。 */
    @PostMapping("/sources/{id}/collect")
    @ResponseBody
    public ApiResponse<CollectLog> collectNow(@PathVariable Long id) {
        try {
            CollectLog cl = collectService.collectNow(id);
            auditService.record("手动采集", "SOURCE", id, cl.getSourceName(),
                    "状态 " + cl.getStatus() + "，行数 " + cl.getLines() + "，新增异常 " + cl.getEvents());
            return ApiResponse.ok(cl);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /** 重置采集位点（从头重新采集）。 */
    @PostMapping("/sources/{id}/reset")
    public String resetOffset(@PathVariable Long id, RedirectAttributes ra) {
        if (!sourceRepo.existsById(id)) return "redirect:/sources";
        LogSource s = sourceRepo.findById(id).orElse(null);
        offsetRepo.findBySourceId(id).forEach(o -> {
            o.setPosition(0L);
            offsetRepo.save(o);
        });
        auditService.record("重置采集位点", "SOURCE", id, s == null ? null : s.getName(), null);
        ra.addFlashAttribute("msg", "采集位点已重置，将从文件头重新采集");
        return "redirect:/sources";
    }
}
