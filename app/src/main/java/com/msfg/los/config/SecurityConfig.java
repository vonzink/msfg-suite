package com.msfg.los.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msfg.los.platform.tenancy.TenantContextFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

// Real Cognito JWT resource-server security — active in every profile EXCEPT "local".
// Local dev uses LocalDevSecurityConfig (no external IdP needed). dev/prod/test use this.
@Configuration
@Profile("!local")
@EnableMethodSecurity
public class SecurityConfig {

    private final TenantContextFilter tenantFilter;
    private final ObjectMapper objectMapper;

    public SecurityConfig(TenantContextFilter tenantFilter, ObjectMapper objectMapper) {
        this.tenantFilter = tenantFilter;
        this.objectMapper = objectMapper;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        ApiErrorAuthenticationEntryPoint entryPoint = new ApiErrorAuthenticationEntryPoint(objectMapper);
        http
            .cors(org.springframework.security.config.Customizer.withDefaults())
            .csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
            .authorizeHttpRequests(reg -> reg
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                .requestMatchers("/api/admin/**").hasRole("PLATFORM_ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/loans").hasAnyRole("LO", "ADMIN")
                .requestMatchers("/api/org/**").hasRole("ADMIN")
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll())
            .oauth2ResourceServer(o -> o
                .authenticationEntryPoint(entryPoint)
                .jwt(j -> j.jwtAuthenticationConverter(new OrgScopedJwtAuthenticationConverter())))
            .addFilterAfter(tenantFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
