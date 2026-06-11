package com.msfg.los.platform.security;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class CurrentUser {
    public Optional<String> id() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            Jwt token = jwt.getToken();
            return Optional.ofNullable(token.getSubject());
        }
        return Optional.empty();
    }
    public Optional<String> email() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            Jwt token = jwt.getToken();
            return Optional.ofNullable(token.getClaimAsString("email"));
        }
        return Optional.empty();
    }
    public Set<String> roles() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return Set.of();
        return auth.getAuthorities().stream().map(Object::toString).collect(Collectors.toSet());
    }
    public boolean isAdmin() { return roles().contains(Role.ADMIN.authority()); }
}
