package com.msfg.los.platform.tenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class TenantContextFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        try {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth instanceof JwtAuthenticationToken jwt) {
                Object claim = jwt.getToken().getClaim("org_id");
                if (claim != null && !claim.toString().isBlank()) {
                    try {
                        TenantContextHolder.set(UUID.fromString(claim.toString().trim()));
                    } catch (IllegalArgumentException ignored) {
                        // Malformed org_id: set the NIL sentinel EXPLICITLY so the fail-closed intent is
                        // visible at this layer (NIL -> @TenantId queries match no rows), rather than
                        // relying on OrgTenantResolver silently substituting NIL for an unset tenant.
                        // The authoritative fail-closed reject for the real non-local JWT path is
                        // OrgScopedJwtAuthenticationConverter; this guard only prevents a raw 500 if any path
                        // (e.g. a test post-processor) ever supplies a present-but-invalid claim.
                        TenantContextHolder.set(OrgTenantResolver.NIL);
                    }
                }
            }
            chain.doFilter(req, res);
        } finally {
            TenantContextHolder.clear();
        }
    }
}
