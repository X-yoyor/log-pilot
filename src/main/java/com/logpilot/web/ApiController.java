package com.logpilot.web;

import com.logpilot.common.ApiResponse;
import com.logpilot.repo.ErrorGroupRepository;
import com.logpilot.repo.ErrorEventRepository;
import com.logpilot.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 只读 JSON API（供外部系统/看板集成）：/api/**
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ApiController {

    private final DashboardService dashboardService;
    private final ErrorGroupRepository groupRepo;
    private final ErrorEventRepository eventRepo;

    @GetMapping("/dashboard/summary")
    public ApiResponse<Map<String, Object>> summary() {
        return ApiResponse.ok(dashboardService.summary());
    }

    @GetMapping("/dashboard/trend")
    public ApiResponse<Object> trend(@RequestParam(defaultValue = "7") int days) {
        return ApiResponse.ok(dashboardService.trend(Math.min(90, Math.max(1, days))));
    }

    @GetMapping("/dashboard/top-groups")
    public ApiResponse<Object> topGroups(@RequestParam(defaultValue = "7") int days,
                                         @RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(dashboardService.topGroups(Math.min(90, Math.max(1, days)), Math.min(50, Math.max(1, limit))));
    }

    @GetMapping("/dashboard/recent-events")
    public ApiResponse<Object> recentEvents(@RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(dashboardService.recentEvents(Math.min(200, Math.max(1, limit))));
    }

    @GetMapping("/groups")
    public ApiResponse<Object> groups(@RequestParam(required = false) String status,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        PageRequest pr = PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)),
                Sort.by(Sort.Direction.DESC, "lastSeen"));
        Page<com.logpilot.domain.ErrorGroup> p = (status == null || status.isBlank())
                ? groupRepo.findAll(pr)
                : groupRepo.findByStatus(status, pr);
        return ApiResponse.ok(Map.of(
                "content", p.getContent(),
                "totalElements", p.getTotalElements(),
                "page", p.getNumber()));
    }

    @GetMapping("/events")
    public ApiResponse<Object> events(@RequestParam(required = false) Long groupId,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "30") int size) {
        Page<com.logpilot.domain.ErrorEvent> p = groupId == null
                ? eventRepo.findAllByOrderByCreatedAtDesc(PageRequest.of(Math.max(0, page), Math.min(200, Math.max(1, size))))
                : eventRepo.findByGroupId(groupId, PageRequest.of(Math.max(0, page), Math.min(200, Math.max(1, size))));
        return ApiResponse.ok(Map.of(
                "content", p.getContent(),
                "totalElements", p.getTotalElements(),
                "page", p.getNumber()));
    }

    @GetMapping("/health")
    public ApiResponse<Object> health() {
        return ApiResponse.ok(Map.of("status", "UP", "app", "log-pilot"));
    }
}
