# Cutover Phase 0 — Foundation & Seam Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove MSFG-suite + msfg-suite-web run together end-to-end locally, close the `org_id` null-tenant hole so no tenant-less write is possible, and produce the cutover's parity checklist + deploy-auth design — without provisioning any AWS or building any parity feature.

**Architecture:** Two backend tasks harden the existing Spring Security resource-server chain (a custom `JwtAuthenticationConverter` that fails authentication when `org_id` is missing/invalid, plus an `AuthenticationEntryPoint` that renders 401s through the standard `ApiError` envelope). One verification task runs both apps locally and exercises the create→pipeline→open loop. Three doc tasks write the parity checklist, the deploy-Cognito seam design, and the working-model README under `docs/cutover/`.

**Tech Stack:** Java 21 · Spring Boot 3.3 · Spring Security OAuth2 resource server · Gradle (wrapper 8.10) · JUnit 5 + Testcontainers (Postgres 16) + MockMvc · React 19 / Vite 5 / TypeScript (msfg-suite-web) · openapi-typescript + React Query.

**Spec:** [docs/superpowers/specs/2026-06-15-cutover-phase-0-foundation-design.md](../specs/2026-06-15-cutover-phase-0-foundation-design.md)

---

## File Structure

**Backend (`/Users/zacharyzink/MSFG/msfg-suite`, `app` module):**
- Create: `app/src/main/java/com/msfg/los/config/OrgScopedJwtAuthenticationConverter.java` — JWT→authn converter that requires a parseable `org_id` UUID; delegates authority mapping to `CognitoRolesConverter`.
- Create: `app/src/main/java/com/msfg/los/config/ApiErrorAuthenticationEntryPoint.java` — renders 401s as `ApiError`.
- Modify: `app/src/main/java/com/msfg/los/config/SecurityConfig.java` — wire both above into the `!local` chain.
- Create (test): `app/src/test/java/com/msfg/los/config/OrgScopedJwtAuthenticationConverterTest.java` — unit test of the reject/pass logic.
- Create (test): `app/src/test/java/com/msfg/los/tenancy/UnauthenticatedEnvelopeIT.java` — IT: no-token `/api/**` → enveloped 401.

**Docs (`/Users/zacharyzink/MSFG/msfg-suite/docs/cutover/`):**
- Create: `docs/cutover/PARITY-CHECKLIST.md` (D4 — keystone), `docs/cutover/cognito-deploy-seam.md` (D3), `docs/cutover/README.md` (D5).

**Frontend (`/Users/zacharyzink/MSFG/msfg-suite-web`):** no source changes in Phase 0 — only run + verify (Task 3). `.env.local` and `public/config.json` already ship local-ready.

**Reference facts (verified 2026-06-15):**
- `SecurityConfig` is `@Profile("!local")`, `@EnableMethodSecurity`, ctor-injects `TenantContextFilter`; chain wires `.oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(jwtAuthConverter()))).addFilterAfter(tenantFilter, BearerTokenAuthenticationFilter.class)`. **No `.exceptionHandling(...)`, no `AuthenticationEntryPoint` exists today.**
- `jwtAuthConverter()` returns a `JwtAuthenticationConverter` whose authorities converter is `new CognitoRolesConverter()`.
- `TenantContextFilter` (`com.msfg.los.platform.tenancy`) reads `jwt.getToken().getClaim("org_id")`; if the claim is null it sets nothing → `OrgTenantResolver.resolveCurrentTenantIdentifier()` returns `NIL` (`new UUID(0,0)`) → writes get stamped `org_id = NIL`. **This is the hole.**
- `ApiError` (`com.msfg.los.platform.web.ApiError`): `record ApiError(boolean success, String code, String message, Map<String,String> fields, Instant timestamp)` with `static ApiError of(String code, String message, Map<String,String> fields, Instant ts)` → `new ApiError(false, code, message, fields, ts)`.
- Tests: extend `com.msfg.los.support.AbstractIntegrationTest` (`@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc`, Testcontainers Postgres singleton, `DEFAULT_ORG = "00000000-0000-0000-0000-0000000000aa"`, stub `JwtDecoder` bean). `@ActiveProfiles("test")` ≠ `local`, so ITs run the **real** `SecurityConfig` chain.
- The `jwt()` post-processor (`SecurityMockMvcRequestPostProcessors.jwt().jwt(j -> j.claim("org_id", ...)).authorities(...)`) **bypasses the converter** — so adding org_id validation to the converter does **not** regress existing ITs (they always supply `org_id`).
- Gradle: run from `/Users/zacharyzink/MSFG/msfg-suite`. One class: `./gradlew :app:test --tests "FQCN"`. One method: append `.methodName`. Full build (needs Docker): `./gradlew build`.

---

## Task 1: `org_id`-asserting JWT converter (fail-closed authentication)

**Files:**
- Create: `app/src/main/java/com/msfg/los/config/OrgScopedJwtAuthenticationConverter.java`
- Create test: `app/src/test/java/com/msfg/los/config/OrgScopedJwtAuthenticationConverterTest.java`
- Modify: `app/src/main/java/com/msfg/los/config/SecurityConfig.java`

- [ ] **Step 1: Write the failing unit test**

Create `app/src/test/java/com/msfg/los/config/OrgScopedJwtAuthenticationConverterTest.java`:

```java
package com.msfg.los.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrgScopedJwtAuthenticationConverterTest {

    private final OrgScopedJwtAuthenticationConverter converter = new OrgScopedJwtAuthenticationConverter();

    private Jwt.Builder base() {
        return Jwt.withTokenValue("t").header("alg", "none").subject("u")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
    }

    @Test
    void rejectsWhenOrgIdMissing() {
        Jwt jwt = base().claim("cognito:groups", List.of("LO")).build();
        assertThatThrownBy(() -> converter.convert(jwt))
                .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void rejectsWhenOrgIdBlank() {
        Jwt jwt = base().claim("org_id", "   ").claim("cognito:groups", List.of("LO")).build();
        assertThatThrownBy(() -> converter.convert(jwt))
                .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void rejectsWhenOrgIdNotUuid() {
        Jwt jwt = base().claim("org_id", "not-a-uuid").claim("cognito:groups", List.of("LO")).build();
        assertThatThrownBy(() -> converter.convert(jwt))
                .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void authenticatesAndMapsRolesWhenOrgIdValid() {
        String org = UUID.randomUUID().toString();
        Jwt jwt = base().claim("org_id", org).claim("cognito:groups", List.of("LO", "ADMIN")).build();
        AbstractAuthenticationToken token = converter.convert(jwt);
        assertThat(token.getAuthorities()).extracting("authority")
                .containsExactlyInAnyOrder("ROLE_LO", "ROLE_ADMIN");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails (does not compile — class missing)**

Run: `./gradlew :app:test --tests "com.msfg.los.config.OrgScopedJwtAuthenticationConverterTest"`
Expected: FAIL — compilation error, `OrgScopedJwtAuthenticationConverter` not found.

- [ ] **Step 3: Implement the converter**

Create `app/src/main/java/com/msfg/los/config/OrgScopedJwtAuthenticationConverter.java`:

```java
package com.msfg.los.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.util.UUID;

/**
 * Authenticates a Cognito JWT only if it carries a parseable {@code org_id} UUID claim, failing
 * closed otherwise. This is the single chokepoint that prevents a tenant-less token from ever
 * authenticating — which would otherwise let {@code OrgTenantResolver} fall back to NIL and stamp
 * {@code org_id = 00000000-0000-0000-0000-000000000000} on writes. Authority mapping is delegated
 * to {@link CognitoRolesConverter} (unchanged).
 */
public class OrgScopedJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final JwtAuthenticationConverter delegate;

    public OrgScopedJwtAuthenticationConverter() {
        this.delegate = new JwtAuthenticationConverter();
        this.delegate.setJwtGrantedAuthoritiesConverter(new CognitoRolesConverter());
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Object claim = jwt.getClaim("org_id");
        if (claim == null || claim.toString().isBlank()) {
            throw new InvalidBearerTokenException("missing org_id claim");
        }
        try {
            UUID.fromString(claim.toString().trim());
        } catch (IllegalArgumentException ex) {
            throw new InvalidBearerTokenException("org_id claim is not a valid UUID");
        }
        return delegate.convert(jwt);
    }
}
```

- [ ] **Step 4: Wire it into `SecurityConfig` (replace `jwtAuthConverter()`)**

In `app/src/main/java/com/msfg/los/config/SecurityConfig.java`, change the resource-server line to use the new converter and delete the old `jwtAuthConverter()` helper.

Replace:
```java
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(jwtAuthConverter())))
            .addFilterAfter(tenantFilter, BearerTokenAuthenticationFilter.class);
```
with:
```java
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(new OrgScopedJwtAuthenticationConverter())))
            .addFilterAfter(tenantFilter, BearerTokenAuthenticationFilter.class);
```
And delete the now-unused method:
```java
    private JwtAuthenticationConverter jwtAuthConverter() {
        JwtAuthenticationConverter conv = new JwtAuthenticationConverter();
        conv.setJwtGrantedAuthoritiesConverter(new CognitoRolesConverter());
        return conv;
    }
```
Remove the now-unused `import ...JwtAuthenticationConverter;` if it triggers an unused-import warning. Keep all other imports.

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :app:test --tests "com.msfg.los.config.OrgScopedJwtAuthenticationConverterTest"`
Expected: PASS (4 tests green).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/msfg/los/config/OrgScopedJwtAuthenticationConverter.java \
        app/src/test/java/com/msfg/los/config/OrgScopedJwtAuthenticationConverterTest.java \
        app/src/main/java/com/msfg/los/config/SecurityConfig.java
git commit -m "feat(security): fail-closed org_id assertion in JWT converter (no NIL-org auth)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

### Task 1 (cont.): defensive tenant-parse hardening in `TenantContextFilter`

The converter (above) is the authoritative fail-closed reject on the real non-local JWT path. But `TenantContextFilter` — where the tenant is actually bound — independently parses the same claim with an **unguarded** `UUID.fromString(claim.toString())`. Post-converter that parse only ever sees valid UUIDs on the real path, but any path that bypasses the converter (a `jwt()` test post-processor, or future auth wiring) supplying a present-but-malformed `org_id` would throw `IllegalArgumentException` **inside the filter chain → raw 500**, not the enveloped 401. Harden the parse so it can never 500 and never binds an invalid tenant.

**Files:**
- Modify: `platform/src/main/java/com/msfg/los/platform/tenancy/TenantContextFilter.java`
- Create test: `app/src/test/java/com/msfg/los/tenancy/TenantContextFilterTest.java`

- [ ] **Step 7: Write the failing filter test**

Create `app/src/test/java/com/msfg/los/tenancy/TenantContextFilterTest.java`:

```java
package com.msfg.los.tenancy;

import com.msfg.los.platform.tenancy.TenantContextFilter;
import com.msfg.los.platform.tenancy.TenantContextHolder;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class TenantContextFilterTest {

    private final TenantContextFilter filter = new TenantContextFilter();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
    }

    private Jwt jwtWithOrg(String orgClaim) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "none").subject("u")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (orgClaim != null) b.claim("org_id", orgClaim);
        return b.build();
    }

    private void authenticate(Jwt jwt) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    @Test
    void validOrgIdIsBoundDuringChainThenCleared() throws Exception {
        String org = UUID.randomUUID().toString();
        authenticate(jwtWithOrg(org));
        AtomicReference<UUID> seenDuringChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> seenDuringChain.set(TenantContextHolder.get());
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);
        assertThat(seenDuringChain.get()).isEqualTo(UUID.fromString(org));
        assertThat(TenantContextHolder.get()).isNull(); // cleared in finally
    }

    @Test
    void malformedOrgIdDoesNotThrowAndNeverBindsTenant() {
        authenticate(jwtWithOrg("not-a-uuid"));
        AtomicReference<UUID> seenDuringChain = new AtomicReference<>(UUID.randomUUID());
        FilterChain chain = (req, res) -> seenDuringChain.set(TenantContextHolder.get());
        assertThatCode(() ->
                filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain))
                .doesNotThrowAnyException();
        assertThat(seenDuringChain.get()).isNull(); // never bound an invalid tenant
    }
}
```

- [ ] **Step 8: Run the test to verify it fails**

Run: `./gradlew :app:test --tests "com.msfg.los.tenancy.TenantContextFilterTest"`
Expected: FAIL — `malformedOrgIdDoesNotThrowAndNeverBindsTenant` throws `IllegalArgumentException` (current unguarded `UUID.fromString`). `validOrgIdIsBoundDuringChainThenCleared` already passes.

- [ ] **Step 9: Harden the filter**

In `platform/src/main/java/com/msfg/los/platform/tenancy/TenantContextFilter.java`, replace the claim-binding block:

```java
            if (auth instanceof JwtAuthenticationToken jwt) {
                Object claim = jwt.getToken().getClaim("org_id");
                if (claim != null) TenantContextHolder.set(UUID.fromString(claim.toString()));
            }
```
with:
```java
            if (auth instanceof JwtAuthenticationToken jwt) {
                Object claim = jwt.getToken().getClaim("org_id");
                if (claim != null && !claim.toString().isBlank()) {
                    try {
                        TenantContextHolder.set(UUID.fromString(claim.toString().trim()));
                    } catch (IllegalArgumentException ignored) {
                        // Malformed org_id: leave tenant unset (OrgTenantResolver -> NIL -> reads match no rows).
                        // The authoritative fail-closed reject for the real non-local JWT path is
                        // OrgScopedJwtAuthenticationConverter; this guard only prevents a raw 500 if any path
                        // (e.g. a test post-processor) ever supplies a present-but-invalid claim.
                    }
                }
            }
```

- [ ] **Step 10: Run the test to verify it passes**

Run: `./gradlew :app:test --tests "com.msfg.los.tenancy.TenantContextFilterTest"`
Expected: PASS (2 tests green).

- [ ] **Step 11: Commit**

```bash
git add platform/src/main/java/com/msfg/los/platform/tenancy/TenantContextFilter.java \
        app/src/test/java/com/msfg/los/tenancy/TenantContextFilterTest.java
git commit -m "harden(tenancy): defensive org_id parse in TenantContextFilter (no raw 500)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: `ApiError` 401 entry point (enveloped, fail-closed)

A converter rejection (Task 1) and a no-token request both surface as `AuthenticationException`. Today Spring returns its default 401 body, not the `{success,code,message,...}` envelope. This task makes 401s consistent and asserts the fail-closed behavior end-to-end through the real chain.

**Files:**
- Create: `app/src/main/java/com/msfg/los/config/ApiErrorAuthenticationEntryPoint.java`
- Create test: `app/src/test/java/com/msfg/los/tenancy/UnauthenticatedEnvelopeIT.java`
- Modify: `app/src/main/java/com/msfg/los/config/SecurityConfig.java`

- [ ] **Step 1: Write the failing IT**

Create `app/src/test/java/com/msfg/los/tenancy/UnauthenticatedEnvelopeIT.java`:

```java
package com.msfg.los.tenancy;

import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A request to a protected /api/** endpoint with NO bearer token must fail closed (401)
 *  and render through the standard ApiError envelope, not Spring's default 401 body. */
class UnauthenticatedEnvelopeIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void noTokenOnProtectedApiReturnsEnvelopedUnauthorized() throws Exception {
        mvc.perform(get("/api/loans"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
}
```

- [ ] **Step 2: Run the IT to verify it fails**

Run: `./gradlew :app:test --tests "com.msfg.los.tenancy.UnauthenticatedEnvelopeIT"`
Expected: FAIL — status is 401 but body has no `$.code` (Spring default body), so the `jsonPath("$.code")` assertion fails (and `UNAUTHENTICATED` is absent). Requires Docker running for Testcontainers.

- [ ] **Step 3: Implement the entry point**

Create `app/src/main/java/com/msfg/los/config/ApiErrorAuthenticationEntryPoint.java`:

```java
package com.msfg.los.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msfg.los.platform.web.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

/** Renders authentication failures (401) through the standard ApiError envelope. */
public class ApiErrorAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper mapper;

    public ApiErrorAuthenticationEntryPoint(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiError body = ApiError.of("UNAUTHENTICATED", "Authentication required", Map.of(), Instant.now());
        mapper.writeValue(response.getOutputStream(), body);
    }
}
```

- [ ] **Step 4: Wire the entry point into `SecurityConfig`**

In `app/src/main/java/com/msfg/los/config/SecurityConfig.java`:

1. Add an `ObjectMapper` constructor dependency (Spring Boot auto-provides the configured bean). Change the field + constructor:
```java
    private final TenantContextFilter tenantFilter;
    private final ObjectMapper objectMapper;

    public SecurityConfig(TenantContextFilter tenantFilter, ObjectMapper objectMapper) {
        this.tenantFilter = tenantFilter;
        this.objectMapper = objectMapper;
    }
```
Add the import: `import com.fasterxml.jackson.databind.ObjectMapper;`

> **Preserve ALL existing imports** (`HttpMethod`, `SessionCreationPolicy`, `BearerTokenAuthenticationFilter`, `SecurityFilterChain`, `HttpSecurity`, `Profile`, `EnableMethodSecurity`, etc.) — the rewritten body in part 2 still uses them. ADD only the `ObjectMapper` import. The `JwtAuthenticationConverter` import is now unused (Task 1 deleted `jwtAuthConverter()`); leave or remove it — there is no `-Werror`/`failOnWarning` in `build.gradle.kts`, so an unused import will not fail the build.

2. Build one shared entry-point instance and register it on BOTH the general exception-handling and the resource-server (so the envelope renders for the no-credentials case AND the bad/missing-org-token case). Inside `filterChain`, set it before `return http.build();`. The chain becomes:
```java
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
```

- [ ] **Step 5: Run the IT to verify it passes**

Run: `./gradlew :app:test --tests "com.msfg.los.tenancy.UnauthenticatedEnvelopeIT"`
Expected: PASS. The no-token case is handled by the `.exceptionHandling(...)` entry point via `ExceptionTranslationFilter`; the dual registration is verified-correct (both `exceptionHandling` and `oauth2ResourceServer.authenticationEntryPoint` resolve to the same `ApiErrorAuthenticationEntryPoint`, so both the no-token path and the bad-token path — Step 6 — render the envelope). Neither path reaches MVC, so `GlobalExceptionHandler` does not interfere.

- [ ] **Step 6: Write the end-to-end bad-org-token IT (the D2 fail-closed proof)**

This is the integration proof that a *decoded* token lacking a usable `org_id` is rejected by the converter (Task 1) and rendered through the entry point (Task 2) — the exact fail-closed behavior D2 cares about. The base `AbstractIntegrationTest` stub `JwtDecoder` *throws* on use, so this IT supplies its own `@Primary` decoder that returns a no-`org_id` `Jwt`, then sends a real `Authorization: Bearer` header through the live chain.

Create `app/src/test/java/com/msfg/los/tenancy/MissingOrgTokenRejectionIT.java`:

```java
package com.msfg.los.tenancy;

import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A decoded JWT with no usable org_id must fail closed at the converter and render the ApiError
 *  envelope through the real resource-server chain (proves Task 1 + Task 2 end-to-end). */
@Import(MissingOrgTokenRejectionIT.NoOrgDecoder.class)
class MissingOrgTokenRejectionIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void tokenWithoutOrgIdIsRejectedWithEnvelopedUnauthorized() throws Exception {
        mvc.perform(get("/api/loans").header("Authorization", "Bearer any-token-value"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @TestConfiguration
    static class NoOrgDecoder {
        @Bean
        @Primary
        JwtDecoder noOrgJwtDecoder() {
            return token -> Jwt.withTokenValue(token).header("alg", "none").subject("u")
                    .claim("cognito:groups", List.of("LO"))
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                    .build(); // NOTE: deliberately no org_id claim
        }
    }
}
```

- [ ] **Step 7: Run the bad-org IT to verify it passes**

Run: `./gradlew :app:test --tests "com.msfg.los.tenancy.MissingOrgTokenRejectionIT"`
Expected: PASS. Flow: `Bearer any-token-value` → `BearerTokenAuthenticationFilter` → `JwtAuthenticationProvider` → `@Primary` decoder returns a no-`org_id` `Jwt` → `OrgScopedJwtAuthenticationConverter.convert` throws `InvalidBearerTokenException` → resource-server entry point → enveloped 401 with `$.code == UNAUTHENTICATED`. (The `@Primary` decoder shadows the base stub; the `@Import` gives this IT its own context.)

- [ ] **Step 8: Run the full build to confirm no regressions**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL; all existing tests + the new Phase-0 tests green (8 total: 4 converter-unit, 2 `TenantContextFilter`-unit, 2 ITs). Don't pin the prior count; the build's pass/fail doesn't depend on it. (Existing `jwt()`-post-processor ITs are unaffected — they bypass the converter and always supply a valid `org_id`.)

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/msfg/los/config/ApiErrorAuthenticationEntryPoint.java \
        app/src/test/java/com/msfg/los/tenancy/UnauthenticatedEnvelopeIT.java \
        app/src/test/java/com/msfg/los/tenancy/MissingOrgTokenRejectionIT.java \
        app/src/main/java/com/msfg/los/config/SecurityConfig.java
git commit -m "feat(security): enveloped ApiError 401 entry point + end-to-end no-org reject IT

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Local end-to-end verification (D1)

Prove the seam: a loan created in msfg-suite-web (local) shows in the pipeline and opens. This task runs both apps and verifies through the browser preview tooling. **No source changes** — capture any contract drift into the parity checklist (Task 4), do not patch it here.

**Files:** none modified. Evidence goes to `docs/cutover/phase-0-e2e-evidence.md` (a short note + screenshot reference).

- [ ] **Step 1: Start the backend (local profile)**

From `/Users/zacharyzink/MSFG/msfg-suite`:
```bash
docker compose up -d            # Postgres 16 on :5432 (db msfg_los / los / los)
./gradlew :app:bootRun --args='--spring.profiles.active=local'
```
Expected: app boots on `http://localhost:8080`; Flyway applies V1–V17; `local` profile = dev ADMIN auto-auth on org `00000000-0000-0000-0000-0000000000aa`. Sanity: `http://localhost:8080/v3/api-docs` returns JSON; `http://localhost:8080/swagger-ui.html` loads.

- [ ] **Step 2: Prepare + start the frontend (local auth)**

From `/Users/zacharyzink/MSFG/msfg-suite-web`:
```bash
nvm use            # Node 20 (.nvmrc)
npm install
npm run gen:api    # regenerate src/lib/api/schema.d.ts against the running backend
npm run dev        # Vite on http://localhost:5173
```
Expected: `.env.local` already has `VITE_AUTH_MODE=local` + `VITE_API_BASE_URL=http://localhost:8080`; `public/config.json` already has `authMode:"local"`. `gen:api` rewrites the typed client with the live OpenAPI (note any diff — schema drift is a checklist item). App serves at `http://localhost:5173`.

- [ ] **Step 3: Drive the create→pipeline→open loop via the preview tooling**

Use the preview tools (NOT manual asking):
1. `preview_start` against `http://localhost:5173` (or attach to the running Vite server).
2. `preview_snapshot` the landing/pipeline — confirm the app renders authenticated (local ADMIN), no `OrgNotConfigured` screen, no console `org_id` error (`preview_console_logs`).
3. Navigate to create-loan; `preview_fill` / `preview_click` to create a loan (minimum: `loanPurpose = PURCHASE`). Submit.
4. `preview_snapshot` the pipeline (`GET /api/loans`) — confirm the new loan row appears.
5. Open the loan; `preview_snapshot` the loan view — confirm it loads.
6. `preview_network` — confirm `POST /api/loans` → 201 and `GET /api/loans` → 200 with the `ApiResponse`/`PagedResponse` envelope; no CORS errors.
7. `preview_screenshot` the pipeline-with-new-loan as evidence.

**Fallback if the Claude Preview MCP isn't available to the executor:** drive the UI via the Claude-in-Chrome MCP instead, OR capture equivalent evidence headlessly — `curl -s -X POST http://localhost:8080/api/loans -H 'Content-Type: application/json' -d '{"loanPurpose":"PURCHASE"}'` (local profile auto-auths as dev ADMIN, no token needed) to get the created loan id from the `$.data` envelope, then `curl -s http://localhost:8080/api/loans` to show that loan id in the paged list — plus one browser screenshot of the pipeline if any browser tool is reachable. **Any one of {preview screenshot, Chrome-MCP screenshot, curl create+list trace} satisfies D1.**

- [ ] **Step 4: Record evidence + drift**

Create `docs/cutover/phase-0-e2e-evidence.md`: a short note stating the loop passed (date, loan id created), the screenshot reference, and a **"Contract drift observed"** bullet list (e.g. any field the UI expected that the API didn't return, any `gen:api` schema diff, any enum mismatch). If zero drift, say so explicitly. Each drift item must also be added to `PARITY-CHECKLIST.md` (Task 4) tagged with its owning phase.

- [ ] **Step 5: Commit the evidence note**

```bash
git add docs/cutover/phase-0-e2e-evidence.md
git commit -m "docs(cutover): Phase 0 local end-to-end verification evidence

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Parity Checklist (D4 — keystone artifact)

Write the authoritative, phase-tagged inventory of every `mortgage-app` behavior MSFG-suite + msfg-suite-web must reach before the flip. Seeded from the verified mortgage-app inventory.

**Files:** Create `docs/cutover/PARITY-CHECKLIST.md`.

- [ ] **Step 1: Write the checklist file**

Create `docs/cutover/PARITY-CHECKLIST.md` with this content:

````markdown
# Cutover Parity Checklist

Definition-of-done for retiring `mortgage-app/backend` + `mortgage-app/frontend`. Every item must be
**have** (✅) in MSFG-suite (backend) + msfg-suite-web (frontend) before the Phase 6 flip. Tags:
✅ have · 🟡 partial · ❌ missing · ⏭️ intentionally dropped. Phase = owning cutover phase.

> Source of truth = the `mortgage-app` backend (`com.msfg.mortgage`, 13 controllers) + its React
> frontend. MSFG-suite already covers the full 1003 data model, qualification calc, pricing/lock,
> AUS+credit, disclosures, fees, CoC, multi-tenancy, and the pipeline — those are **not** re-listed.

## Auth / role model
- ❌ `org_id` claim emitted by the deployed Cognito pool (new LOS pool) — Phase 0 design / Phase 6 provision.
- ✅ Reject tenant-less tokens (fail-closed) — **done in Phase 0 (Task 1/2)**.
- 🟡 Role reconciliation (**bidirectional**): mortgage-app groups used in `SecurityConfig` matchers are
  `Admin, Manager, LO, Processor, Borrower, RealEstateAgent` (a `CognitoJwtConverter` maps any `cognito:groups`
  value to `ROLE_*` with **no allowlist**; `External` appears in converter docs but in **no** matcher/handler —
  confirm whether it exists in the live pool). vs MSFG-suite `LO, PROCESSOR, UNDERWRITER, CLOSER, ADMIN, PLATFORM_ADMIN`
  (exact-match allowlist). Deltas both ways: **`Borrower`, `RealEstateAgent`, `Manager` have no MSFG-suite equivalent**
  (borrower/agent self-service + a manager tier), and **`UNDERWRITER`, `CLOSER` are net-new MSFG-suite staff roles**
  with no mortgage-app source. mortgage-app resolves principal by `email`→`sub`; MSFG-suite by `sub`. — Phase 2/3.
- 🟡 Per-loan access policy: mortgage-app `LoanAccessGuard.canAccess` (Admin/Manager superuser; LO/Processor
  by `assigned_lo_id`; Borrower by `borrowers.user_id`; Agent by `loan_agents.user_id`) vs MSFG-suite
  `LoanAccessGuard.hasOrgWideView`. Borrower/agent self-scoping is the gap — Phase 2/3.
- Principal identity: mortgage-app resolves by `email`→`sub`; MSFG-suite by `sub`. Note for Phase 6 (user provisioning).

## Borrower / agent self-service portal (OPEN SCOPE QUESTION)
- ❓ Does the cutover preserve borrower/agent self-service (own-loan view + status timeline + direct doc upload)?
  mortgage-app supports it via `LoanAccessGuard` on shared controllers + `GET /me`, `GET /me/loans`.
  **Decision needed before Phase 2.** If yes: borrower/agent roles + per-loan self-scoping + `/me/loans`.

## Loan / application — Phase 2
- ✅ Create loan · ✅ get one · ✅ pipeline list · ✅ status workflow + history (MSFG-suite has these).
- 🟡 Pipeline filters: mortgage-app `status[], lo, conditionsGt, closingFrom/To, stageAgeGt, loanType[], amountMin/Max, sort` — verify/extend MSFG-suite's `GET /api/loans` filter set.
- ❌ Global typeahead search (`GET /loan-applications/search?q=`) — Phase 2.
- ❌ Lookup by application number; list by status (internal) — Phase 2 (confirm if FE uses them).
- ❌ **Status backdating** (`transitionedAt` on status PATCH) — MSFG-suite has no backdating — Phase 2.
- ❌ Server-side clone ("Copy to new") — Phase 2.
- ❌ Update application (PUT) / delete application — Phase 2.

## Dashboard: terms / conditions / notes — Phase 2
- ❌ Underwriting **conditions** CRUD (`LoanCondition`: add/update/clear/delete) — MSFG-suite has no conditions module — Phase 2.
- ❌ Per-loan **notes** CRUD (`LoanNote`) — MSFG-suite has no notes module — Phase 2.
- 🟡 Aggregated dashboard payload (terms, housing, identifiers, primary borrower, property, status history, agents, closing, purchase credits, outstanding conditions) — assemble from MSFG-suite data — Phase 2.
- ❌ Edit loan terms in place (`PATCH /dashboard/terms`) — Phase 2.

## Documents — Phase 1
- ❌ **3-step direct-to-S3 presigned upload**: `POST upload-url` (presigned PUT, MIME-validated) → client PUT → `PUT {docUuid}/confirm` (S3 HEAD + size + SHA-256 + tags). MSFG-suite stores bytes in DB (`DbDocumentStorageAdapter`) — needs an S3 `DocumentStoragePort` adapter — Phase 1.
- ❌ Presigned **download** URL (`GET {docUuid}/download-url`) — Phase 1.
- ❌ **Folders** (tree, auto-seed root + a default folder set on first GET — verify the exact count/source, in-code list vs DB seed, in Phase 1; do not hard-code a number — create/rename/soft-delete, sibling-collision 400) — Phase 1.
- ❌ **Folder templates** (admin CRUD, `evalPrompt`, singleton flags) — Phase 1/3.
- ❌ **Document types catalog** (table-backed; `GET /document-types`, slug lookup) + MIME/size validation on upload — MSFG-suite has a doc-type **enum** only — Phase 1.
- ❌ **Document review workflow**: `status`, `accept`, `reject`, `request-revision`, `bulk-review`, `status-history`. `DocumentStatus` is exactly **10 states** — `PENDING_UPLOAD, UPLOADED, SCAN_PENDING, SCAN_FAILED, READY_FOR_REVIEW, NEEDS_BORROWER_ACTION, ACCEPTED, REJECTED, ARCHIVED, DELETED_SOFT` (no scan-*passed* state). Key edges: `UPLOADED→{SCAN_PENDING, READY_FOR_REVIEW}`; `SCAN_PENDING/SCAN_FAILED→READY_FOR_REVIEW`; `ACCEPTED/REJECTED/ARCHIVED→READY_FOR_REVIEW` (reopen); `NEEDS_BORROWER_ACTION→UPLOADED` (re-upload). — Phase 1.
- ❌ Document list/search facets (`folderId, unfiled, atRoot, status, documentTypeId, uploadedBy, partyRole, q, page, size`) — Phase 1.
- ❌ Patch metadata · move between folders · permanent delete — Phase 1.
- 🟡 S3 layout + Object Lock/WORM + lifecycle tags (Reg Z retention) — design in Phase 1, provision Phase 6.

## Audit log — Phase 1/3
- ❌ Per-loan audit feed (`GET .../audit-log`, filters) + per-document history (`GET .../documents/{docUuid}/history`). MSFG-suite has `AuditableEntity` timestamps but no audit-log feed/`AuditLog` table — Phase 1/3.

## Folder AI evaluation — Phase 4
- ❌ Provider-agnostic `AiPort` (Anthropic/OpenAI/DeepSeek) + registry, per-tenant provider/model — Phase 4.
- ❌ `POST .../folders/{tpl}/evaluate` + `GET .../evaluation` (11-step guardrail flow) — Phase 4.
- ❌ `app_settings` (`aiEvalEnabled` global toggle, `llmDefaultProvider`, `llmDefaultModel`; DeepSeek prod-gate) — Phase 3/4.
- ❌ PDFBox text extraction (`DocumentParser`) — Phase 4.

## Admin — Phase 3
- ❌ Doc-types admin CRUD (`/admin/document-types`) — Phase 3.
- ❌ Folder-templates admin CRUD (`/admin/folder-templates`) — Phase 3.
- ❌ App-settings admin (`/admin/app-settings`) + public projection (`/app-settings/public`) — Phase 3.
- 🟡 Org/user management — MSFG-suite has `/api/admin/organizations` (PLATFORM_ADMIN); user management UI — Phase 3.

## Identity — Phase 2/3
- ❌ `GET /me` (current-user from JWT, materialize on first call) — Phase 2/3.
- ❌ `GET /me/loans` (caller-scoped, role-filtered loan list) — Phase 2/3 (ties to borrower/agent decision).

## MISMO — Phase 5
- ❌ **MISMO 3.4 export** (`GET .../export/mismo`, `application/xml`) — **REQUIRED** (LendingPad retired but file generation needed) — Phase 5.
- ⏭️ MISMO 3.4 **import** + drift-409/`force=true` (`POST .../import/mismo`, `POST /from-mismo`) — optional/portability only (LendingPad retired) — Phase 5 or deferred.

## Integrations — Phase 5
- 🟡 GoHighLevel sync (`createContact/updateContactStatus/createOpportunity`) — **dormant/unwired** in mortgage-app (no caller; `ghlContactId` never populated; ctor-vs-@Value NPE bug). Confirm whether it's actually wanted before building — Phase 5.
- ⏭️ Borrower-invite / agent-assign routes (`/borrowers/invite`, `/agents/assign`) — referenced in mortgage-app `SecurityConfig` but **no handler exists** (planned-but-unbuilt). Decide if the new backend must provide them — Phase 2/3.

## Health / infra — Phase 6
- ✅ Health probe (MSFG-suite `/actuator/health`).
- ❌ Run app as non-owner DB role to engage RLS (deployment requirement) — Phase 6.
- ❌ Deploy MSFG-suite (Docker) + msfg-suite-web (S3/CloudFront) + DNS flip `app.msfgco.com` — Phase 6.

## No scheduled jobs / inbound webhooks
- mortgage-app has none (`@Scheduled`/`@EventListener`/webhooks absent). Nothing to port.
````

- [ ] **Step 2: Verify completeness**

Read back the file; confirm every one of mortgage-app's 13 controllers + the auth/role model + integrations is represented, and each item carries a tag (✅/🟡/❌/⏭️) and a phase. Add any contract drift found in Task 3.

- [ ] **Step 3: Commit**

```bash
git add docs/cutover/PARITY-CHECKLIST.md
git commit -m "docs(cutover): parity checklist — definition-of-done for the cutover

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Deploy-Cognito seam design doc (D3)

**Files:** Create `docs/cutover/cognito-deploy-seam.md`.

- [ ] **Step 1: Write the design doc**

Create `docs/cutover/cognito-deploy-seam.md`:

````markdown
# Deployed Cognito Seam (design — provisioned in Phase 6, not now)

The **new dedicated LOS Cognito user pool** that backs MSFG-suite + msfg-suite-web in deployed
(non-`local`) environments. Local dev needs none (dev auto-auth on org `…aa`).

## Decisions
- **New pool**, isolated from `mortgage-app`'s `us-west-1_S6iE2uego`. Staff users (re)created in it.
- **Groups** named EXACTLY (case-sensitive — backend `CognitoRolesConverter` exact-matches the `Role` enum,
  frontend `Role` union matches the same): `LO`, `PROCESSOR`, `UNDERWRITER`, `CLOSER`, `ADMIN`, `PLATFORM_ADMIN`.
- **`org_id` claim**: a **pre-token-generation Lambda** injects the **bare** `org_id` claim (not `custom:org_id`).
  - Backend reads bare `org_id` (`TenantContextFilter`, and now the `OrgScopedJwtAuthenticationConverter` which
    REQUIRES it — Phase 0). Frontend reads `org_id` then falls back to `custom:org_id`.
  - The bare claim only appears via the Lambda — a Cognito custom attribute surfaces as `custom:org_id`, which the
    backend does NOT read. So the Lambda is required; map user→org (all users → MSFG org
    `00000000-0000-0000-0000-0000000000aa` initially; the lookup generalizes for future tenants).

## Token shape — RISK TO VALIDATE at first provision (Phase 6)
- The SPA sends a bearer token via `authProvider.getToken()` (oidc-client-ts). Confirm whether that is the **id**
  or **access** token, because the pre-token-gen Lambda must inject `org_id` + `cognito:groups` into the token the
  SPA actually sends. `cognito:groups` is native to both id and access tokens; **custom claims via the V2 access-token
  trigger** are needed if the access token is the bearer. Backend validates by signature/issuer (`COGNITO_ISSUER`),
  not audience, so either token validates — but the **claims must be present in the sent token**.
- Action at provision: mint a real token, inspect it for bare `org_id` + `cognito:groups`, confirm the backend's
  `OrgScopedJwtAuthenticationConverter` accepts it and `TenantContextFilter` binds the tenant.

## Env wiring (deploy)
- Backend: `COGNITO_ISSUER=https://cognito-idp.{region}.amazonaws.com/{userPoolId}` ·
  `LOS_CORS_ALLOWED_ORIGINS=https://{spa-domain}` · datasource as the **non-owner** `app_user` role (engages RLS).
- Frontend `config.json` (uploaded per-env by `scripts/deploy.sh`): `authMode:"cognito"`, `apiBaseUrl:"https://{api-domain}"`,
  `cognito.{authority,clientId,domain,redirectUri,logoutUri,scopes}`. CDK `MsfgLosWebSpaStack` takes `cognitoDomain` (CSP) +
  `apiOrigin` as context; it does NOT create the pool.

## Out of scope here
Pool/Lambda provisioning, user migration, DNS — all Phase 6.
````

- [ ] **Step 2: Commit**

```bash
git add docs/cutover/cognito-deploy-seam.md
git commit -m "docs(cutover): deployed-Cognito seam design (new LOS pool, org_id Lambda)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: Working-model README (D5)

**Files:** Create `docs/cutover/README.md`.

- [ ] **Step 1: Write the README**

Create `docs/cutover/README.md`:

````markdown
# Cutover Program — make MSFG-suite the single backend

Replacing `mortgage-app` (`/Users/zacharyzink/MSFG/WebProjects/mortgage-app`) with **MSFG-suite**
(this repo, backend) + **msfg-suite-web** (`/Users/zacharyzink/MSFG/msfg-suite-web`, frontend).
Full cutover · greenfield (no data migration) · build-alongside, flip at the end.

## Repo ownership
This program drives **both** repos directly (the old "backend session never edits frontend" rule is retired
for cutover work). Branch convention: `cutover/phase-N-*` per phase, in each repo.

## Documents
- Roadmap + Phase 0 design: `docs/superpowers/specs/2026-06-15-cutover-phase-0-foundation-design.md`
- Phase 0 plan: `docs/superpowers/plans/2026-06-15-cutover-phase-0-foundation.md`
- Parity definition-of-done: `docs/cutover/PARITY-CHECKLIST.md`
- Deploy-auth design: `docs/cutover/cognito-deploy-seam.md`

## Local run recipe
```bash
# Backend — /Users/zacharyzink/MSFG/msfg-suite
docker compose up -d
./gradlew :app:bootRun --args='--spring.profiles.active=local'
# → http://localhost:8080 · OpenAPI /v3/api-docs · Swagger /swagger-ui.html · dev ADMIN auto-auth (org …aa)

# Frontend — /Users/zacharyzink/MSFG/msfg-suite-web
nvm use && npm install
npm run gen:api      # regen typed client against the running backend
npm run dev          # → http://localhost:5173 (VITE_AUTH_MODE=local, base http://localhost:8080)
```
`.env.local` (`VITE_AUTH_MODE=local`, `VITE_API_BASE_URL=http://localhost:8080`) and `public/config.json`
(`authMode:"local"`) already ship local-ready. Backend local CORS allows `http://localhost:5173`.

## Phase status
- Phase 0 — Foundation & seam: in progress (this plan).
- Phases 1–6: see the roadmap table in the Phase 0 design doc.
````

- [ ] **Step 2: Commit**

```bash
git add docs/cutover/README.md
git commit -m "docs(cutover): program working-model + local run recipe

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Done criteria (Phase 0 complete when all true)
1. All 8 new tests green — `OrgScopedJwtAuthenticationConverterTest` (4 unit), `TenantContextFilterTest` (2 unit), `UnauthenticatedEnvelopeIT` (1), `MissingOrgTokenRejectionIT` (1) — and `./gradlew build` green.
2. Tenant-less / no-`org_id` / malformed-`org_id` requests fail closed with the enveloped 401 (converter reject) and never bind an invalid tenant or 500 (filter guard) — proven by the converter unit test + the end-to-end `MissingOrgTokenRejectionIT`.
3. Local create→pipeline→open loop verified, with evidence captured in one of the accepted forms (preview screenshot, Chrome-MCP screenshot, or curl create+list trace); any contract drift logged into `PARITY-CHECKLIST.md`.
4. `docs/cutover/PARITY-CHECKLIST.md`, `cognito-deploy-seam.md`, `README.md`, `phase-0-e2e-evidence.md` written + committed.
