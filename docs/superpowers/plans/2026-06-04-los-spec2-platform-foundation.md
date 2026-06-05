# LOS Spec 2 — Platform Foundation (Multi-tenancy + Portability) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Make the LOS multi-tenant — every domain row carries an `org_id`, isolated by two layers
(Hibernate `@TenantId` filtering + Postgres RLS), with an `Organization` registry, a `PLATFORM_ADMIN`
role, and Cognito reduced to a swappable auth adapter — all before the 1003 sections.

**Architecture:** App-layer isolation via Hibernate 6 **`@TenantId`** on a new `TenantScopedEntity`
(auto-filters reads, auto-stamps writes) driven by a `CurrentTenantIdentifierResolver` reading a
per-request `TenantContextHolder` (set from the JWT `org_id` claim). DB-layer defense-in-depth via
**Postgres RLS** (`FORCE ROW LEVEL SECURITY` + a policy keyed to a per-transaction `app.current_org`
GUC set by an aspect). New `tenancy` module owns `Organization` + admin.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Hibernate 6.5 (`@TenantId`), Postgres 16 (RLS), Flyway,
Spring Security, Testcontainers.

**Repo:** `/Users/zacharyzink/MSFG/msfg-suite` · **Base package:** `com.msfg.los` ·
**DEFAULT_ORG_ID:** `00000000-0000-0000-0000-0000000000aa` (seeded MSFG org; used by local-dev + tests)

> **Branch:** create `spec-2-platform-foundation` (Task 0). Commit the spec + this plan as the first
> commit on it (the user wants spec+plan+code bundled). Use `./gradlew`; Docker must be running.
> If a hook redirects build/test commands to `mcp__plugin_context-mode_context-mode__ctx_execute`, comply.

---

## File Structure

```
platform/src/main/java/com/msfg/los/platform/
  tenancy/TenantContextHolder.java     ThreadLocal<UUID> current org (set per request, cleared after)
  tenancy/TenantContext.java           @Component: orgId() (from holder) + isPlatformAdmin() (from roles)
  tenancy/OrgTenantResolver.java       CurrentTenantIdentifierResolver<UUID> ← holder (NIL when unset = fail-closed)
  tenancy/TenantContextFilter.java     OncePerRequestFilter: JWT org_id claim → holder; finally clear
  tenancy/TenantRlsAspect.java         @Around @Transactional: SELECT set_config('app.current_org', org, true)
  config/TenantTransactionConfig.java  @EnableTransactionManagement(order=0) so the RLS aspect runs INSIDE the tx
  domain/TenantScopedEntity.java       @MappedSuperclass extends AuditableEntity; @TenantId org_id
  security/Role.java                   + PLATFORM_ADMIN   (MODIFY)

tenancy/ (NEW Gradle module; depends on :platform)
  build.gradle.kts
  src/main/java/com/msfg/los/tenancy/
    domain/Organization.java           @Entity (extends AuditableEntity — it IS the tenant, not tenant-scoped)
    domain/OrgStatus.java              enum ACTIVE, SUSPENDED
    repo/OrganizationRepository.java
    service/OrganizationService.java
    web/OrganizationController.java     /api/admin/organizations  (PLATFORM_ADMIN)
    web/dto/{CreateOrgRequest,UpdateOrgRequest,OrgResponse}.java

loan-core/.../domain/{Loan,LoanStatusHistory}.java   extends TenantScopedEntity (MODIFY)
parties/.../domain/BorrowerParty.java                extends TenantScopedEntity (MODIFY)

app/
  build.gradle.kts                     + implementation(project(":tenancy"))   (MODIFY)
  src/main/java/com/msfg/los/config/SecurityConfig.java        + PLATFORM_ADMIN, add TenantContextFilter, admin route (MODIFY)
  src/main/java/com/msfg/los/config/LocalDevSecurityConfig.java + org_id claim + PLATFORM_ADMIN + tenant filter (MODIFY)
  src/main/resources/db/migration/V3__multitenancy.sql         org table, seed, org_id retrofit+backfill, RLS+FORCE+policies
  src/test/java/com/msfg/los/support/AbstractIntegrationTest.java  jwt helper carries org_id; TenantContextHolder reset (MODIFY)
  src/test/java/com/msfg/los/tenancy/...                       OrganizationControllerIT, CrossTenantIsolationIT, RlsIT
  (MODIFY existing ITs' jwt() helpers to add the org_id claim)

settings.gradle.kts                    include("tenancy")   (MODIFY)
```

**Module deps:** `tenancy → platform`; `loan-core`/`parties → platform` (unchanged; gain TenantScopedEntity);
`app → platform, loan-core, parties, tenancy`.

---

## Task 0: Branch + `tenancy` module scaffold + bundle spec/plan

**Files:** `settings.gradle.kts` (MODIFY), `tenancy/build.gradle.kts` (CREATE), commit spec + plan.

- [ ] **Step 1: Branch**
```bash
cd /Users/zacharyzink/MSFG/msfg-suite && git checkout -b spec-2-platform-foundation
```
- [ ] **Step 2:** `settings.gradle.kts` → add `tenancy`:
```kotlin
rootProject.name = "msfg-los"
include("platform", "app", "loan-core", "parties", "tenancy")
```
- [ ] **Step 3:** `tenancy/build.gradle.kts`:
```kotlin
dependencies {
    implementation(project(":platform"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
}
```
`mkdir -p tenancy/src/main/java/com/msfg/los/tenancy tenancy/src/test/java/com/msfg/los/tenancy`
- [ ] **Step 4:** Verify modules resolve: `./gradlew projects` shows `tenancy`. Then bundle the planning docs:
```bash
git add settings.gradle.kts tenancy/build.gradle.kts docs/specs/2026-06-04-los-spec2-platform-foundation.md docs/superpowers/plans/2026-06-04-los-spec2-platform-foundation.md
git -c user.name=Vonzink -c user.email=vonzink@gmail.com commit -m "chore(spec-2): scaffold tenancy module + bundle Platform Foundation spec & plan"
```

---

## Task 1: `platform` tenancy primitives — holder, context, resolver, role

**Files:** Create `tenancy/TenantContextHolder.java`, `TenantContext.java`, `OrgTenantResolver.java`; modify `security/Role.java`. **Test:** `tenancy/OrgTenantResolverTest.java`.

- [ ] **Step 1:** `Role.java` — add `PLATFORM_ADMIN`:
```java
public enum Role { LO, PROCESSOR, UNDERWRITER, CLOSER, ADMIN, PLATFORM_ADMIN;
    public String authority() { return "ROLE_" + name(); }
}
```
- [ ] **Step 2:** `tenancy/TenantContextHolder.java`:
```java
package com.msfg.los.platform.tenancy;
import java.util.UUID;
public final class TenantContextHolder {
    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();
    private TenantContextHolder() {}
    public static void set(UUID orgId) { CURRENT.set(orgId); }
    public static UUID get() { return CURRENT.get(); }
    public static void clear() { CURRENT.remove(); }
}
```
- [ ] **Step 3:** `tenancy/TenantContext.java`:
```java
package com.msfg.los.platform.tenancy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import java.util.Optional;
import java.util.UUID;

@Component
public class TenantContext {
    public Optional<UUID> orgId() { return Optional.ofNullable(TenantContextHolder.get()); }
    public boolean isPlatformAdmin() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
            .anyMatch(a -> "ROLE_PLATFORM_ADMIN".equals(a.getAuthority()));
    }
}
```
- [ ] **Step 4: Write failing test** `tenancy/OrgTenantResolverTest.java`:
```java
package com.msfg.los.platform.tenancy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class OrgTenantResolverTest {
    private final OrgTenantResolver resolver = new OrgTenantResolver();
    @AfterEach void clear() { TenantContextHolder.clear(); }

    @Test void returnsCurrentOrgWhenSet() {
        UUID org = UUID.randomUUID();
        TenantContextHolder.set(org);
        assertThat(resolver.resolveCurrentTenantIdentifier()).isEqualTo(org);
    }
    @Test void returnsNilSentinelWhenUnset_failClosed() {
        assertThat(resolver.resolveCurrentTenantIdentifier()).isEqualTo(new UUID(0, 0));
    }
}
```
Run `./gradlew :platform:test --tests "*OrgTenantResolverTest"` → FAIL (class missing).
- [ ] **Step 5:** `tenancy/OrgTenantResolver.java`:
```java
package com.msfg.los.platform.tenancy;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class OrgTenantResolver implements CurrentTenantIdentifierResolver<UUID> {
    /** No tenant in context → NIL → @TenantId queries match no rows (fail-closed). */
    public static final UUID NIL = new UUID(0, 0);
    @Override public UUID resolveCurrentTenantIdentifier() {
        UUID org = TenantContextHolder.get();
        return org != null ? org : NIL;
    }
    @Override public boolean validateExistingCurrentSessions() { return false; }
}
```
- [ ] **Step 6:** Run test → PASS. Commit `feat(platform): tenant context holder, context, resolver, PLATFORM_ADMIN role`.

---

## Task 2: `TenantScopedEntity` (`@TenantId`) + retrofit existing entities

**Files:** Create `platform/.../domain/TenantScopedEntity.java`; modify `loan-core` `Loan.java`, `LoanStatusHistory.java`, `parties` `BorrowerParty.java`. (Schema validated by Task 5's migration; full IT in Task 7.)

- [ ] **Step 1:** `TenantScopedEntity.java`:
```java
package com.msfg.los.platform.domain;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.TenantId;
import java.util.UUID;

@MappedSuperclass
public abstract class TenantScopedEntity extends AuditableEntity {
    @TenantId
    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;
    public UUID getOrgId() { return orgId; }
}
```
- [ ] **Step 2:** In `loan-core`, change `Loan` and `LoanStatusHistory` to `extends TenantScopedEntity`
  (was `AuditableEntity`). Same for `BorrowerParty` in `parties`. (Import
  `com.msfg.los.platform.domain.TenantScopedEntity`; drop the `AuditableEntity` import.)
- [ ] **Step 3:** `./gradlew compileJava` → SUCCESS (no schema yet; just compiles).
- [ ] **Step 4:** Commit `feat(platform): TenantScopedEntity (@TenantId); loan/borrower/history are tenant-scoped`.

---

## Task 3: RLS aspect + transaction ordering (DB-layer defense-in-depth)

**Files:** Create `platform/.../config/TenantTransactionConfig.java`, `platform/.../tenancy/TenantRlsAspect.java`. `platform/build.gradle.kts` needs AOP + tx (already via starters: data-jpa pulls tx; add aspectj).

- [ ] **Step 1:** Ensure `platform/build.gradle.kts` has AOP: add `implementation("org.springframework.boot:spring-boot-starter-aop")`.
- [ ] **Step 2:** `config/TenantTransactionConfig.java` — make the tx advisor outermost so the aspect runs inside an open tx:
```java
package com.msfg.los.platform.config;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration
@EnableTransactionManagement(order = 0)   // tx advisor = order 0 (outer); RLS aspect = @Order(100) (inner)
public class TenantTransactionConfig {}
```
- [ ] **Step 3:** `tenancy/TenantRlsAspect.java` — set the per-transaction GUC (parameterized via `set_config`):
```java
package com.msfg.los.platform.tenancy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Aspect @Component @Order(100)
public class TenantRlsAspect {
    @PersistenceContext private EntityManager em;

    @Around("@annotation(org.springframework.transaction.annotation.Transactional)"
          + " || @within(org.springframework.transaction.annotation.Transactional)")
    public Object setCurrentOrg(ProceedingJoinPoint pjp) throws Throwable {
        UUID org = TenantContextHolder.get();
        if (org != null) {
            em.createNativeQuery("select set_config('app.current_org', :org, true)")
              .setParameter("org", org.toString())
              .getSingleResult();   // transaction-local GUC; RLS policy reads it
        }
        return pjp.proceed();
    }
}
```
> Note: `set_config(name, value, is_local=true)` is transaction-scoped and parameter-safe (no SQL injection).
> The aspect runs **inside** the transaction because the tx advisor is `order=0` and this aspect is `@Order(100)`.
- [ ] **Step 4:** `./gradlew :platform:compileJava` → SUCCESS. Commit `feat(platform): per-transaction RLS GUC aspect + tx ordering`.

(Behavior verified end-to-end by the RLS test in Task 7.)

---

## Task 4: `tenancy` module — Organization aggregate + admin API

**Files:** Create in `tenancy/`: `domain/Organization.java`, `domain/OrgStatus.java`, `repo/OrganizationRepository.java`, `service/OrganizationService.java`, `web/OrganizationController.java`, `web/dto/{CreateOrgRequest,UpdateOrgRequest,OrgResponse}.java`. **Test:** `OrganizationControllerIT` (Task 7 area; created here, run after security wiring in Task 6).

- [ ] **Step 1:** `domain/OrgStatus.java`: `public enum OrgStatus { ACTIVE, SUSPENDED }`
- [ ] **Step 2:** `domain/Organization.java` (NOT tenant-scoped — it is the tenant):
```java
package com.msfg.los.tenancy.domain;
import com.msfg.los.platform.domain.AuditableEntity;
import jakarta.persistence.*;
import lombok.Getter; import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.HashMap; import java.util.Map;

@Entity @Table(name = "organization")
@Getter @Setter
public class Organization extends AuditableEntity {
    @Column(nullable = false) private String name;
    @Column(nullable = false, unique = true) private String slug;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private OrgStatus status = OrgStatus.ACTIVE;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> settings = new HashMap<>();
}
```
- [ ] **Step 3:** `repo/OrganizationRepository.java`:
```java
package com.msfg.los.tenancy.repo;
import com.msfg.los.tenancy.domain.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional; import java.util.UUID;
public interface OrganizationRepository extends JpaRepository<Organization, UUID> {
    boolean existsBySlug(String slug);
    Optional<Organization> findBySlug(String slug);
}
```
- [ ] **Step 4:** DTOs (records) in `web/dto/`:
```java
package com.msfg.los.tenancy.web.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
public record CreateOrgRequest(@NotBlank String name,
    @NotBlank @Pattern(regexp = "[a-z0-9-]{2,100}") String slug) {}
```
```java
package com.msfg.los.tenancy.web.dto;
import com.msfg.los.tenancy.domain.OrgStatus;
import java.util.Map;
public record UpdateOrgRequest(String name, OrgStatus status, Map<String,Object> settings) {}
```
```java
package com.msfg.los.tenancy.web.dto;
import com.msfg.los.tenancy.domain.Organization;
import com.msfg.los.tenancy.domain.OrgStatus;
import java.util.Map; import java.util.UUID;
public record OrgResponse(UUID id, String name, String slug, OrgStatus status, Map<String,Object> settings) {
    public static OrgResponse from(Organization o) {
        return new OrgResponse(o.getId(), o.getName(), o.getSlug(), o.getStatus(), o.getSettings());
    }
}
```
- [ ] **Step 5:** `service/OrganizationService.java` — mirror the Spec-1 service pattern (constructor-injected repo, `@Transactional`):
  - `create(CreateOrgRequest)`: if `existsBySlug` → `ConflictException("slug taken")`; else save new Organization.
  - `get(UUID)`: `findById` or `NotFoundException("Organization", id)`.
  - `list(Pageable)`: `findAll(pageable)`.
  - `update(UUID, UpdateOrgRequest)`: load; patch non-null name/status/settings; return (dirty-checked).
  Use `com.msfg.los.platform.error.{ConflictException,NotFoundException}`.
- [ ] **Step 6:** `web/OrganizationController.java` `@RequestMapping("/api/admin/organizations")` — all methods
  return the `ApiResponse`/`PagedResponse` envelope (as in Spec 1's `LoanController`):
  - `POST` `@PreAuthorize("hasRole('PLATFORM_ADMIN')")` → 201 `ApiResponse<OrgResponse>`
  - `GET` (list, paged) `@PreAuthorize("hasRole('PLATFORM_ADMIN')")` → `ApiResponse<PagedResponse<OrgResponse>>`
  - `GET /{id}` + `PATCH /{id}` → `ApiResponse<OrgResponse>`, both `@PreAuthorize("hasRole('PLATFORM_ADMIN')")`
  (`@EnableMethodSecurity` is already on in `SecurityConfig`, so `@PreAuthorize` works.)
- [ ] **Step 7:** `./gradlew :tenancy:compileJava` → SUCCESS. Commit `feat(tenancy): Organization aggregate + admin API`.

---

## Task 5: `V3` migration — org table, seed, `org_id` retrofit, RLS

**Files:** Create `app/src/main/resources/db/migration/V3__multitenancy.sql`.

- [ ] **Step 1:** Write the migration:
```sql
-- organization registry (NOT tenant-scoped; guarded by PLATFORM_ADMIN at the app layer; no RLS)
create table organization (
    id uuid primary key,
    version bigint not null default 0,
    name varchar(200) not null,
    slug varchar(100) not null unique,
    status varchar(20) not null default 'ACTIVE',
    settings jsonb not null default '{}'::jsonb,
    created_at timestamp(6) with time zone, created_by varchar(120),
    updated_at timestamp(6) with time zone, updated_by varchar(120)
);

-- seed the default / MSFG org (fixed id used by local-dev + tests)
insert into organization (id, version, name, slug, status, settings)
values ('00000000-0000-0000-0000-0000000000aa', 0, 'MSFG', 'msfg', 'ACTIVE', '{}'::jsonb);

-- retrofit org_id onto existing tenant tables (nullable -> backfill -> not null + fk + index)
alter table loan add column org_id uuid;
update loan set org_id = '00000000-0000-0000-0000-0000000000aa' where org_id is null;
alter table loan alter column org_id set not null;
alter table loan add constraint fk_loan_org foreign key (org_id) references organization(id);
create index idx_loan_org on loan(org_id);

alter table borrower_party add column org_id uuid;
update borrower_party set org_id = '00000000-0000-0000-0000-0000000000aa' where org_id is null;
alter table borrower_party alter column org_id set not null;
alter table borrower_party add constraint fk_borrower_org foreign key (org_id) references organization(id);
create index idx_borrower_org on borrower_party(org_id);

alter table loan_status_history add column org_id uuid;
update loan_status_history set org_id = '00000000-0000-0000-0000-0000000000aa' where org_id is null;
alter table loan_status_history alter column org_id set not null;
alter table loan_status_history add constraint fk_lsh_org foreign key (org_id) references organization(id);
create index idx_lsh_org on loan_status_history(org_id);

-- RLS (defense-in-depth) on tenant-data tables. FORCE so it applies to the owner/superuser too.
-- Unset GUC -> current_setting(...,true) is NULL -> policy is false -> deny-all (fail-closed).
alter table loan enable row level security;
alter table loan force row level security;
create policy tenant_isolation on loan using (org_id = current_setting('app.current_org', true)::uuid);

alter table borrower_party enable row level security;
alter table borrower_party force row level security;
create policy tenant_isolation on borrower_party using (org_id = current_setting('app.current_org', true)::uuid);

alter table loan_status_history enable row level security;
alter table loan_status_history force row level security;
create policy tenant_isolation on loan_status_history using (org_id = current_setting('app.current_org', true)::uuid);
```
- [ ] **Step 2:** (Schema is exercised by Tasks 6–7. Don't run `:app:test` yet — security/test-helpers aren't updated.) Commit `feat(app): V3 migration — organization, org_id retrofit, RLS`.

---

## Task 6: Wire security + test infrastructure for tenancy

**Files (MODIFY):** `app` `SecurityConfig.java`, `LocalDevSecurityConfig.java`, `build.gradle.kts`, `settings`/deps; `support/AbstractIntegrationTest.java`; and every existing IT's `jwt()` helper. This is the cross-cutting wiring task.

- [ ] **Step 1:** `app/build.gradle.kts` → add `implementation(project(":tenancy"))`.
- [ ] **Step 2:** `SecurityConfig.java` (the `@Profile("!local")` chain) — register the tenant filter AFTER the
  bearer-token filter and permit the admin route to `PLATFORM_ADMIN`:
```java
// add import: org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
// constructor-inject TenantContextFilter tenantFilter
.authorizeHttpRequests(reg -> reg
    .requestMatchers("/actuator/health", "/actuator/info").permitAll()
    .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
    .requestMatchers("/api/admin/**").hasRole("PLATFORM_ADMIN")
    .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/loans").hasAnyRole("LO", "ADMIN")
    .requestMatchers("/api/**").authenticated()
    .anyRequest().denyAll())
.oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(jwtAuthConverter())))
.addFilterAfter(tenantFilter, BearerTokenAuthenticationFilter.class);
```
- [ ] **Step 3:** Create `platform/.../tenancy/TenantContextFilter.java`:
```java
package com.msfg.los.platform.tenancy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.util.UUID;

@Component
public class TenantContextFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws java.io.IOException, jakarta.servlet.ServletException {
        try {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth instanceof JwtAuthenticationToken jwt) {
                Object claim = jwt.getToken().getClaim("org_id");
                if (claim != null) TenantContextHolder.set(UUID.fromString(claim.toString()));
            }
            chain.doFilter(req, res);
        } finally {
            TenantContextHolder.clear();
        }
    }
}
```
- [ ] **Step 4:** `LocalDevSecurityConfig.java` — dev principal gets the default org + PLATFORM_ADMIN + ADMIN, and
  add the tenant filter after the dev-principal filter:
```java
// DEV_ORG_ID constant = "00000000-0000-0000-0000-0000000000aa"
Jwt jwt = Jwt.withTokenValue("local-dev").header("alg","none").subject(DEV_USER_ID)
    .claim("org_id", DEV_ORG_ID)
    .claim("cognito:groups", java.util.List.of("PLATFORM_ADMIN","ADMIN"))
    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
var auth = new JwtAuthenticationToken(jwt, java.util.List.of(
    new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"), new SimpleGrantedAuthority("ROLE_ADMIN")));
// ... in the chain: .addFilterAfter(new DevPrincipalFilter(), SecurityContextHolderFilter.class)
//                   .addFilterAfter(tenantContextFilter, com.msfg.los.platform.tenancy.DevPrincipalFilter? )
```
  Simplest: constructor-inject `TenantContextFilter` and add it `.addFilterAfter(tenantContextFilter, SecurityContextHolderFilter.class)` AFTER the `DevPrincipalFilter` (so the dev Jwt's org_id is in the context when it runs). Order: SecurityContextHolderFilter → DevPrincipalFilter → TenantContextFilter.
- [ ] **Step 5:** `AbstractIntegrationTest.java` — (a) reset tenant holder between tests; (b) the jwt() MockMvc
  helper used by ITs must carry `org_id`. Add:
```java
@org.junit.jupiter.api.AfterEach
void clearTenant() { com.msfg.los.platform.tenancy.TenantContextHolder.clear(); }

public static final String DEFAULT_ORG = "00000000-0000-0000-0000-0000000000aa";
```
- [ ] **Step 6:** Update **every existing IT** that builds a `jwt()` post-processor to add the org claim. Pattern —
  change `jwt().jwt(j -> j.subject(X))...` to `jwt().jwt(j -> j.subject(X).claim("org_id", DEFAULT_ORG))...`.
  Files: `LoanControllerIT`, `BorrowerControllerIT`, `SecurityConfigTest` (its `ROLE_LO` etc. requests). For the
  cross-tenant test (Task 7) use distinct org claims.
- [ ] **Step 7:** `LoanServiceIT` (direct service calls — no MockMvc, no filter): set the holder around each test:
```java
@org.junit.jupiter.api.BeforeEach
void setOrg() { com.msfg.los.platform.tenancy.TenantContextHolder.set(java.util.UUID.fromString(DEFAULT_ORG)); }
```
  (`AbstractIntegrationTest.clearTenant()` already clears afterward.)
- [ ] **Step 8:** Run the FULL suite: `./gradlew :app:test`. Expected: all prior tests **green** again (now tenant-aware),
  loans/borrowers created in tests carry `org_id = DEFAULT_ORG`, schema validates (org_id columns match entities).
  Fix any wiring issues (common: `@TenantId` resolver not picked up → confirm `OrgTenantResolver` is a `@Component`
  and component-scanned; the isolation test in Task 7 is the real proof). Commit `feat(app): wire tenant filter, PLATFORM_ADMIN, tenancy module, tenant-aware tests`.

---

## Task 7: Crown-jewel isolation tests (app-layer + DB-layer RLS)

**Files (CREATE in `app/src/test`):** `tenancy/OrganizationControllerIT.java`, `tenancy/CrossTenantIsolationIT.java`, `tenancy/RlsIT.java`.

- [ ] **Step 1:** `OrganizationControllerIT` — platform-admin can create/list orgs; a plain company ADMIN cannot:
```java
// extends AbstractIntegrationTest; @AutoConfigureMockMvc
// platformAdmin(): jwt().jwt(j->j.subject(UUID).claim("org_id",DEFAULT_ORG)).authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"))
// companyAdmin():  ... .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
@Test platformAdminCreatesOrg_201 -> POST /api/admin/organizations {"name":"Acme","slug":"acme"} with platformAdmin() -> 201, jsonPath $.data.slug == "acme"
@Test companyAdminForbidden_403 -> POST same with companyAdmin() -> 403
@Test listRequiresPlatformAdmin -> GET /api/admin/organizations with companyAdmin() -> 403; with platformAdmin() -> 200 + $.data.items array
```
- [ ] **Step 2:** `CrossTenantIsolationIT` (app-layer @TenantId) — **the headline test**:
```java
// orgA = DEFAULT_ORG; orgB = a second org created via the admin API (platformAdmin) OR a fixed UUID inserted.
// userA(): jwt subject=randomUUID, claim org_id=orgA, authority ROLE_LO
// userB(): jwt subject=randomUUID, claim org_id=orgB, authority ROLE_LO
@Test orgBcannotSeeOrgAloan() throws Exception {
    // userA creates a loan (POST /api/loans, loanOfficerId = userA subject)
    String aLoanId = createLoanAs(userA(), aSubject);
    // userB's pipeline does NOT include A's loan
    mvc.perform(get("/api/loans").with(userB()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[?(@.id=='" + aLoanId + "')]").doesNotExist());
    // userB GET A's loan by id -> 404 (filtered out by @TenantId, never found)
    mvc.perform(get("/api/loans/{id}", aLoanId).with(userB())).andExpect(status().isNotFound());
}
```
  (Create orgB first: `POST /api/admin/organizations` as platform-admin, capture its id, or insert a fixed second org via JdbcTemplate in `@BeforeEach`.)
- [ ] **Step 3:** `RlsIT` (DB-layer — proves RLS independent of the app filter):
```java
@Autowired javax.sql.DataSource ds;
@Test rlsBlocksCrossOrgRawQuery() throws Exception {
    // Insert one loan for orgA and one for orgB directly via JDBC (bypasses Hibernate @TenantId),
    // each with a valid org_id + minimal required columns (id, version, loan_number unique, loan_officer_id, status, org_id).
    // Then on a raw connection: set orgB, count loans -> sees only orgB's; set orgA -> only orgA's;
    // unset (NULL) -> sees ZERO (fail-closed). Use set_config to set the GUC on that connection.
    try (var c = ds.getConnection(); var st = c.createStatement()) {
        st.execute("select set_config('app.current_org','" + orgB + "', false)");
        try (var rs = st.executeQuery("select count(*) from loan")) { rs.next(); assertThat(rs.getInt(1)).isEqualTo(1); }
        st.execute("select set_config('app.current_org', NULL, false)");
        try (var rs = st.executeQuery("select count(*) from loan")) { rs.next(); assertThat(rs.getInt(1)).isEqualTo(0); }
    }
}
```
  > This proves `FORCE ROW LEVEL SECURITY` applies even to the Testcontainers superuser. If it returns all rows,
  > the `force` clause is missing/ineffective — fix the migration, do not weaken the test.
- [ ] **Step 4:** `./gradlew :app:test` → all green (full suite incl. the 3 new ITs). Commit `test(app): cross-tenant isolation + RLS crown-jewel tests`.

---

## Task 8: Full build + org-scoped boot smoke + finish

- [ ] **Step 1:** `./gradlew build` → BUILD SUCCESSFUL, all modules, all tests green.
- [ ] **Step 2:** Boot smoke (local profile = dev PLATFORM_ADMIN+ADMIN of the default org):
```bash
docker compose up -d
./gradlew :app:bootJar -q && java -jar app/build/libs/app.jar --spring.profiles.active=local &  # then poll /actuator/health
```
  Verify via curl: `POST /api/admin/organizations` (creates a 2nd org) → 201; `GET /api/admin/organizations` → both orgs;
  `POST /api/loans` (org-scoped) → 201; `GET /api/loans` → shows only the dev org's loans. Stop the app + `docker compose down`.
- [ ] **Step 3:** Commit `chore(spec-2): Platform Foundation complete — multi-tenant + RLS, boot-verified`.
  (Branch merges to `main` via the finishing-a-development-branch flow.)

---

## Self-Review

**Spec coverage:** org_id on every domain row → Tasks 2,5. Two isolation layers (@TenantId + RLS) → Tasks 2,3,5,7.
One-org-per-user (org_id claim) → Tasks 1,6. Organization + admin + PLATFORM_ADMIN → Tasks 1,4,6. Cognito-as-adapter
(claim-driven, local-dev seeded org) → Task 6. Migration retrofit+backfill+seed+RLS → Task 5. Crown-jewel isolation
tests → Task 7. Ports convention: the auth/tenant seam is the JWT-claim mapping (TenantContext/resolver) — established;
storage/AI/webhook ports deferred to their features (per spec). ✓

**Placeholder scan:** Task 4 step 5/6 and Task 7 steps describe Organization CRUD + test bodies as patterns rather than
full code — these intentionally mirror Spec-1's `LoanController`/`LoanService`/`*IT` (concrete, in-repo references), not
vague placeholders. All novel/load-bearing code (entities, resolver, RLS aspect, filter, migration, RLS test) is complete.

**Type consistency:** `TenantContextHolder.{set,get,clear}` used identically across resolver/aspect/filter/tests.
`OrgTenantResolver.NIL` = `new UUID(0,0)` consistent. `DEFAULT_ORG`/`DEV_ORG_ID` = `00000000-0000-0000-0000-0000000000aa`
everywhere. `set_config('app.current_org', …)` GUC name identical in aspect, migration policy, and RLS test.

**Execution-time confirmations (documented, not gaps):** Spring Boot must pick up the `CurrentTenantIdentifierResolver`
bean for `@TenantId` (verify via the isolation test; if not auto-wired, add `spring.jpa.properties.hibernate` config /
a `HibernatePropertiesCustomizer`). The RLS aspect's tx-ordering (`@EnableTransactionManagement(order=0)` + `@Order(100)`)
is verified by the RLS test passing with FORCE RLS.
