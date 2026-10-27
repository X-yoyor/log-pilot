package com.logpilot.common;

import lombok.Getter;
import lombok.Setter;

/**
 * 统一 JSON 响应结构。
 */
@Getter
@Setter
public class ApiResponse<T> {

    private boolean ok;
    private String message;
    private T data;

    public static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> r = new ApiResponse<>();
        r.ok = true;
        r.message = "success";
        r.data = data;
        return r;
    }

    public static ApiResponse<Void> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> error(String message) {
        ApiResponse<T> r = new ApiResponse<>();
        r.ok = false;
        r.message = message;
        return r;
    }
}
