package com.msfg.los.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Binds {@code los.cors.allowed-origins} from application config.
 * Default is an empty list — no cross-origin requests permitted unless explicitly configured.
 * Populate via env var {@code LOS_CORS_ALLOWED_ORIGINS} (comma-separated) or per-profile yml.
 */
@ConfigurationProperties(prefix = "los.cors")
public class CorsProperties {

    /** Exact origin allowlist. Never use wildcards — Bearer JWT auth requires exact origins. */
    private List<String> allowedOrigins = new ArrayList<>();

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }
}
