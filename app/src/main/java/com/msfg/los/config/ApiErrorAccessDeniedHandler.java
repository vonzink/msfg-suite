package com.msfg.los.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msfg.los.platform.web.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

/**
 * Renders authorization failures (filter-layer 403) through the standard ApiError envelope.
 *
 * <p>Mirror of {@link ApiErrorAuthenticationEntryPoint} (401) so that a party token denied at the
 * SecurityConfig filter layer — e.g. a BORROWER/REAL_ESTATE_AGENT hitting a non-allowlisted path —
 * gets the same {@code {success,code,message,fields,timestamp}} body as the controller-layer 403s
 * (rather than an empty body). Wired into {@code SecurityConfig.exceptionHandling}.
 */
public class ApiErrorAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper mapper;

    public ApiErrorAccessDeniedHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiError body = ApiError.of("FORBIDDEN", "Access denied", Map.of(), Instant.now());
        mapper.writeValue(response.getOutputStream(), body);
    }
}
