package com.msfg.los.platform.web;
public record ApiResponse<T>(boolean success, T data) {
    public static <T> ApiResponse<T> ok(T data) { return new ApiResponse<>(true, data); }
}
