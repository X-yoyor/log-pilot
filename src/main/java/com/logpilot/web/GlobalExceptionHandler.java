package com.logpilot.web;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

import java.util.Map;

/**
 * 全局异常处理：API/XHR 返回 JSON，页面返回错误页。
 */
@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(Exception.class)
    public Object handle(Exception e, HttpServletRequest request) {
        log.error("请求处理失败 {} : {}", request.getRequestURI(), e.getMessage(), e);
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String uri = request.getRequestURI();
        boolean wantsJson = uri.startsWith("/api")
                || "XMLHttpRequest".equals(request.getHeader("X-Requested-With"));
        if (wantsJson) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("ok", false, "message", msg));
        }
        ModelAndView mv = new ModelAndView("error");
        mv.addObject("message", msg);
        return mv;
    }
}
