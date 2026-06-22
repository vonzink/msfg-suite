# Local End-to-End Funnel Harness — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire msfg.us → mortgage-app → msfg-suite so a test borrower can apply and have the loan created in suite, then read it back through the re-pointed mortgage-app FE and the staff console — all running locally with no AWS.

**Architecture:** Suite gains a cross-module `POST /api/loans/intake` (in the `origination` module, which already spans loan-core + parties) that idempotently creates a loan + a primary borrower row linked to the caller's `sub`. Suite's `local` profile gains a dev-header identity bridge so we can act as a borrower without Cognito. mortgage-app's `createFromIntake` calls suite via a new `SuiteClient` (strangler dual-write: keep the local row, store + return the suite loan id). Config + a runbook tie the apps together.

**Tech Stack:** suite = Java 21 / Spring Boot 3.3 / Gradle / Postgres 16 / Flyway / Testcontainers. mortgage-app = Java 17 / Spring Boot 3.5 / Maven / H2(dev) / Flyway + React CRA. msfg.us = Next.js 16 (config only).

**Shared dev constants** (used identically across repos for the local walk):
- Dev org id: `00000000-0000-0000-0000-0000000000aa` (existing suite seed `DEV_ORG_ID`)
- Dev borrower sub: `00000000-0000-0000-0000-0000000000b0`
- Dev borrower email: `borrower@dev.local`

**Execution order & ownership:** Part A (suite) is conductor-owned and lands first (Flyway is serialized). Part B (mortgage-app) depends on A's endpoint existing but its unit tests mock suite, so B can be built in parallel and integrated after A. Part C is config/docs. Commits are owner-gated per project convention — the commit steps below are the intended units; confirm before running them.

---

## File Structure

**suite (this repo):**
- Modify: `loan-core/.../loan/domain/Loan.java` — add `sourceLeadId` field.
- Create: `app/src/main/resources/db/migration/V25__loan_source_lead_id.sql`
- Modify: `loan-core/.../loan/repo/LoanRepository.java` — add `findBySourceLeadIdAndDeletedAtIsNull`.
- Modify: `loan-core/.../loan/service/LoanService.java` — add `findBySourceLeadId` + `tagSourceLead`.
- Modify: `app/.../config/LocalDevSecurityConfig.java` — dev-header bridge.
- Modify: `loan-core/.../loan/service/BorrowerUserLinker.java` — add `linkById`.
- Modify: `parties/.../parties/service/BorrowerUserLinkAdapter.java` — implement `linkById`.
- Create: `origination/.../origination/web/dto/IntakeRequest.java`, `IntakeResult.java`
- Create: `origination/.../origination/service/IntakeService.java`
- Create: `origination/.../origination/web/IntakeController.java`
- Modify: `app/src/main/resources/application-local.yml` — add `:3001` origin.
- Modify: `app/.../config/CorsConfig.java` — allow `X-Dev-*` headers.
- Test: `app/.../app/.../IntakeControllerIT.java`, `LocalDevHeaderBridgeIT.java`, plus a `linkById` unit test in parties.

**mortgage-app (`WebProjects/mortgage-app`):**
- Create: `backend/.../mortgage/integration/SuiteClient.java`, `SuiteIntakeResponse.java`
- Create: `backend/.../mortgage/config/SuiteClientConfig.java` (WebClient bean)
- Create: `backend/.../mortgage/config/LocalSecurityConfig.java` (`@Profile("local")`)
- Modify: `backend/.../mortgage/config/SecurityConfig.java` — add `@Profile("!local")`.
- Modify: `backend/.../mortgage/service/LoanApplicationService.java` — `createFromIntake` calls suite.
- Modify: `backend/.../mortgage/controller/LoanApplicationController.java` — return suite loan id.
- Modify: `backend/.../mortgage/model/LoanApplication.java` — add `suiteLoanId`.
- Create: `backend/src/main/resources/db/migration/V<next>__add_suite_loan_id.sql`
- Modify: `backend/src/main/resources/application.properties` — add `suite.api.base-url`.
- Create: `backend/src/main/resources/application-local.properties`
- Modify: `frontend/.env` — port + suite base + dev headers.
- Modify: `frontend/src/services/apiClient.js` — attach dev headers in local mode.

**msfg.us (`WebProjects/msfg.us`):** Modify `.env.local` only.

**docs:** Create `docs/local-e2e.md` (runbook + walkthrough) in this repo.

---

# PART A — suite (conductor-owned, serialized)

## Task A1: `source_lead_id` on the loan (idempotency key)

**Files:**
- Modify: `loan-core/src/main/java/com/msfg/los/loan/domain/Loan.java`
- Create: `app/src/main/resources/db/migration/V25__loan_source_lead_id.sql`
- Modify: `loan-core/src/main/java/com/msfg/los/loan/repo/LoanRepository.java`
- Modify: `loan-core/src/main/java/com/msfg/los/loan/service/LoanService.java`
- Test: `app/src/test/java/com/msfg/los/loan/LoanSourceLeadIT.java` (new)

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/msfg/los/loan/LoanSourceLeadIT.java`. Mirror an existing loan IT for setup (Testcontainers + `local`-ish tenant bound). The test creates a loan, tags it, and looks it up:

```java
package com.msfg.los.loan;

import com.msfg.los.loan.domain.LoanPurposeType;
import com.msfg.los.loan.domain.Loan;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.loan.web.dto.CreateLoanRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class LoanSourceLeadIT extends AbstractTenantBoundTest {   // reuse the project's tenant-bound IT base

    @Autowired LoanService loans;

    @Test
    void tagAndFindBySourceLead() {
        UUID officer = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Loan loan = loans.create(new CreateLoanRequest(LoanPurposeType.PURCHASE, null, null, null, null, officer));
        loans.tagSourceLead(loan.getId(), "lead-123");

        Optional<Loan> found = loans.findBySourceLeadId("lead-123");
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(loan.getId());
        assertThat(loans.findBySourceLeadId("nope")).isEmpty();
    }
}
```

> If `AbstractTenantBoundTest` doesn't exist under that name, find the existing IT base used by other loan ITs (look in `app/src/test/java/com/msfg/los/loan/`) and extend that instead — it must bind a tenant context so `@TenantId` reads/writes work.

- [ ] **Step 2: Run it — expect compile failure** (`tagSourceLead`/`findBySourceLeadId` don't exist):

Run: `./gradlew :app:test --tests 'com.msfg.los.loan.LoanSourceLeadIT'`
Expected: FAIL (compilation: cannot find symbol `tagSourceLead`).

- [ ] **Step 3: Add the entity field.** In `Loan.java`, after the `loanNumber` field block, add:

```java
    // External idempotency key from the msfg.us apply funnel (the upstream lead id). Stamped only
    // by POST /api/loans/intake so a retried hand-off resolves to the existing loan. Unique per org.
    @Column(name = "source_lead_id", length = 100)
    private String sourceLeadId;
```

- [ ] **Step 4: Create the migration** `V25__loan_source_lead_id.sql`:

```sql
-- V25: external idempotency key for the borrower funnel hand-off (msfg.us → mortgage-app → suite).
-- Nullable (app-created loans have none); unique PER ORG so a retried intake is idempotent within a
-- tenant without colliding across tenants. loan already has RLS + grants (V3) — do NOT re-grant.
alter table loan add column source_lead_id varchar(100) null;
create unique index uq_loan_org_source_lead on loan (org_id, source_lead_id)
    where source_lead_id is not null;
```

- [ ] **Step 5: Add the repo finder.** In `LoanRepository.java` add (tenant-filtered automatically by `@TenantId` on the derived query):

```java
    java.util.Optional<Loan> findBySourceLeadIdAndDeletedAtIsNull(String sourceLeadId);
```

- [ ] **Step 6: Add the service methods.** In `LoanService.java`, after `create(...)`:

```java
    /** Idempotency lookup for the funnel hand-off (Phase A). Tenant-scoped, not-deleted. */
    @Transactional(readOnly = true)
    public java.util.Optional<Loan> findBySourceLeadId(String sourceLeadId) {
        if (sourceLeadId == null || sourceLeadId.isBlank()) return java.util.Optional.empty();
        return loans.findBySourceLeadIdAndDeletedAtIsNull(sourceLeadId);
    }

    /** Stamp the upstream lead id on a loan (dirty-checked within the tx). 404 if missing/deleted. */
    @Transactional
    public Loan tagSourceLead(UUID loanId, String sourceLeadId) {
        Loan loan = get(loanId);
        loan.setSourceLeadId(sourceLeadId);
        return loan;
    }
```

- [ ] **Step 7: Run the test — expect PASS:**

Run: `./gradlew :app:test --tests 'com.msfg.los.loan.LoanSourceLeadIT'`
Expected: PASS.

- [ ] **Step 8: Commit** (owner-gated):

```bash
git add loan-core/src/main/java/com/msfg/los/loan/domain/Loan.java \
        app/src/main/resources/db/migration/V25__loan_source_lead_id.sql \
        loan-core/src/main/java/com/msfg/los/loan/repo/LoanRepository.java \
        loan-core/src/main/java/com/msfg/los/loan/service/LoanService.java \
        app/src/test/java/com/msfg/los/loan/LoanSourceLeadIT.java
git commit -m "feat(loan): source_lead_id idempotency key + finder/tag (V25)"
```

---

## Task A2: dev-header identity bridge (`local` profile only)

**Files:**
- Modify: `app/src/main/java/com/msfg/los/config/LocalDevSecurityConfig.java`
- Test: `app/src/test/java/com/msfg/los/config/LocalDevHeaderBridgeIT.java` (new)

- [ ] **Step 1: Write the failing test.** A `local`-profile slice test hitting any authenticated endpoint with and without dev headers. Reuse the project's pattern for `local`-profile web tests (look for an existing `@ActiveProfiles("local")` MockMvc test; if none, use `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@ActiveProfiles("local")` + TestRestTemplate).

```java
package com.msfg.los.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class LocalDevHeaderBridgeIT {

    @Autowired MockMvc mvc;

    @Test
    void noHeaders_actsAsAdmin_canListPipeline() throws Exception {
        // /api/loans pipeline is an org-wide (staff/admin) read — ADMIN default must reach it.
        mvc.perform(get("/api/loans?page=0&size=1")).andExpect(status().isOk());
    }

    @Test
    void borrowerHeaders_meLoansReturnsOk_scopedEmpty() throws Exception {
        // A borrower with no linked loans must get 200 + an empty page (NEVER all loans, NEVER 403).
        mvc.perform(get("/api/me/loans?page=0&size=10")
                        .header("X-Dev-Sub", "00000000-0000-0000-0000-0000000000b0")
                        .header("X-Dev-Roles", "Borrower")
                        .header("X-Dev-Org", "00000000-0000-0000-0000-0000000000aa"))
                .andExpect(status().isOk());
    }
}
```

- [ ] **Step 2: Run it — expect the borrower test to FAIL** (today every request is ADMIN, so `/api/me/loans` runs the admin branch, not the borrower branch — but it should still 200; the meaningful failure is that without the bridge the principal is ADMIN regardless of headers). Verify the bridge wiring by asserting behavior after Step 3. Run:

Run: `./gradlew :app:test --tests 'com.msfg.los.config.LocalDevHeaderBridgeIT'`
Expected: the `noHeaders` test passes; the borrower test passes only after the bridge resolves a borrower principal (before the change it runs as ADMIN, which for `/me/loans` may return all-org or admin behavior — confirm against `MeController`'s ADMIN branch; if ADMIN returns 200 anyway, strengthen the assertion in Step 4).

- [ ] **Step 3: Implement the bridge.** Replace the `DevPrincipalFilter` inner class body in `LocalDevSecurityConfig.java` with a header-aware version (keep the outer class, `@Profile("local")`, constants, and `localFilterChain` unchanged):

```java
    /**
     * Injects a dev principal so CurrentUser + access guards work locally WITHOUT Cognito.
     * Honors optional dev headers so we can act as a borrower/agent/LO for cross-system testing:
     *   X-Dev-Sub   : UUID subject        (default DEV_USER_ID)
     *   X-Dev-Roles : CSV Cognito groups  (default "Admin")  e.g. "Borrower"
     *   X-Dev-Org   : UUID org id         (default DEV_ORG_ID)
     * Absent headers → the original fixed dev ADMIN behavior (backward compatible).
     *
     * SECURITY: trust-the-header is a LOCAL-ONLY test seam. This class is @Profile("local") and is
     * NEVER wired in dev/prod/test (those use SecurityConfig + real Cognito JWT). Do not lift it out.
     */
    static class DevPrincipalFilter extends OncePerRequestFilter {
        // Cognito group string -> Spring role authority. Mirror of CognitoRolesConverter aliases.
        private static java.util.List<String> rolesFor(java.util.List<String> groups) {
            java.util.List<String> auth = new java.util.ArrayList<>();
            for (String g : groups) {
                switch (g.trim()) {
                    case "Admin" -> { auth.add("ROLE_ADMIN"); auth.add("ROLE_PLATFORM_ADMIN"); }
                    case "Manager" -> auth.add("ROLE_MANAGER");
                    case "LO" -> auth.add("ROLE_LO");
                    case "Processor" -> auth.add("ROLE_PROCESSOR");
                    case "Borrower" -> auth.add("ROLE_BORROWER");
                    case "RealEstateAgent" -> auth.add("ROLE_REAL_ESTATE_AGENT");
                    default -> { /* unknown dev role ignored */ }
                }
            }
            return auth;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            String sub = headerOr(req, "X-Dev-Sub", DEV_USER_ID);
            String org = headerOr(req, "X-Dev-Org", DEV_ORG_ID);
            String rolesCsv = headerOr(req, "X-Dev-Roles", "Admin");
            java.util.List<String> groups = java.util.Arrays.stream(rolesCsv.split(","))
                    .map(String::trim).filter(s -> !s.isBlank()).toList();

            Jwt jwt = Jwt.withTokenValue("local-dev")
                .header("alg", "none")
                .subject(sub)
                .claim("cognito:groups", groups)
                .claim("org_id", org)
                .claim("email_verified", true)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
            java.util.List<SimpleGrantedAuthority> authorities = rolesFor(groups).stream()
                    .map(SimpleGrantedAuthority::new).toList();
            var auth = new JwtAuthenticationToken(jwt, authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);
            chain.doFilter(req, res);
        }

        private static String headerOr(HttpServletRequest req, String name, String dflt) {
            String v = req.getHeader(name);
            return (v == null || v.isBlank()) ? dflt : v.trim();
        }
    }
```

> Note: the org claim must flow to `TenantContextFilter` (already added after `DevPrincipalFilter` in `localFilterChain`) so reads/writes are stamped with `X-Dev-Org`. No change needed there — it reads `org_id` from the authentication.

- [ ] **Step 4: Run the test — expect PASS:**

Run: `./gradlew :app:test --tests 'com.msfg.los.config.LocalDevHeaderBridgeIT'`
Expected: PASS. (If the ADMIN-vs-borrower distinction for `/me/loans` is not observable via status alone, the real proof lands in A4's IT, which seeds a linked loan and asserts the borrower sees exactly it.)

- [ ] **Step 5: Commit** (owner-gated):

```bash
git add app/src/main/java/com/msfg/los/config/LocalDevSecurityConfig.java \
        app/src/test/java/com/msfg/los/config/LocalDevHeaderBridgeIT.java
git commit -m "feat(local): dev-header identity bridge (act as borrower/agent locally)"
```

---

## Task A3: deterministic borrower link by id

**Files:**
- Modify: `loan-core/src/main/java/com/msfg/los/loan/service/BorrowerUserLinker.java`
- Modify: `parties/src/main/java/com/msfg/los/parties/service/BorrowerUserLinkAdapter.java`
- Test: `parties/src/test/java/com/msfg/los/parties/service/BorrowerUserLinkAdapterIT.java` (extend if it exists, else create)

- [ ] **Step 1: Write the failing test.** Create a borrower row (unlinked), call `linkById`, assert it becomes linked and a second call is a no-op. Reuse the parties IT base (Testcontainers + tenant bound + `BorrowerService.add`).

```java
    @Test
    void linkById_stampsOnce_thenNoOp() {
        UUID loanId = UUID.randomUUID();
        var b = borrowerService.add(loanId, new com.msfg.los.parties.web.dto.AddBorrowerRequest(
                "Ann", "Buyer", true, null, null, null, null, null, null, null, null, null, null,
                null, null, "555-0100", null, null, "borrower@dev.local", null));
        UUID sub = UUID.fromString("00000000-0000-0000-0000-0000000000b0");

        assertThat(linker.linkById(b.getId(), sub)).isEqualTo(BorrowerUserLinker.LinkResult.LINKED);
        assertThat(linker.linkById(b.getId(), sub)).isEqualTo(BorrowerUserLinker.LinkResult.NO_OP);
        assertThat(resolver.isBorrowerOnLoan(loanId, sub)).isTrue();
    }
```

> Inject `BorrowerUserLinker linker`, `BorrowerService borrowerService`, `LoanLinkageResolver resolver`. If the parties IT module can't see `LoanLinkageResolver`, assert via `borrowers.existsByLoanIdAndUserId(loanId, sub)` instead.

- [ ] **Step 2: Run it — expect compile failure** (`linkById` undefined):

Run: `./gradlew :parties:test --tests '*BorrowerUserLinkAdapterIT'`
Expected: FAIL (cannot find symbol `linkById`).

- [ ] **Step 3: Add to the port.** In `BorrowerUserLinker.java`, add the method + javadoc:

```java
    /**
     * Stamps {@code user_id = :userId} on the EXACT borrower row {@code borrowerId}, only while it is
     * still unlinked ({@code user_id IS NULL}). Deterministic counterpart to {@link #linkByVerifiedEmail}
     * for the case where the caller just created the row (Phase A intake) and knows its id. Idempotent;
     * a second call (or a race) is a {@link LinkResult#NO_OP}.
     *
     * @param borrowerId the borrower row to link; {@code null} never links
     * @param userId     the Cognito sub to stamp; {@code null} never links
     */
    LinkResult linkById(UUID borrowerId, UUID userId);
```

- [ ] **Step 4: Implement in the adapter.** In `BorrowerUserLinkAdapter.java`, add:

```java
    /** {@inheritDoc} */
    @Override
    @Transactional
    public LinkResult linkById(UUID borrowerId, UUID userId) {
        if (borrowerId == null || userId == null) {
            return LinkResult.NO_OP;
        }
        int updated = borrowers.linkUserIfUnlinked(borrowerId, userId);
        return updated == 1 ? LinkResult.LINKED : LinkResult.NO_OP;
    }
```

- [ ] **Step 5: Run the test — expect PASS:**

Run: `./gradlew :parties:test --tests '*BorrowerUserLinkAdapterIT'`
Expected: PASS.

- [ ] **Step 6: Commit** (owner-gated):

```bash
git add loan-core/src/main/java/com/msfg/los/loan/service/BorrowerUserLinker.java \
        parties/src/main/java/com/msfg/los/parties/service/BorrowerUserLinkAdapter.java \
        parties/src/test/java/com/msfg/los/parties/service/BorrowerUserLinkAdapterIT.java
git commit -m "feat(parties): deterministic linkById for funnel intake"
```

---

## Task A4: `POST /api/loans/intake` (origination)

**Files:**
- Create: `origination/src/main/java/com/msfg/los/origination/web/dto/IntakeRequest.java`
- Create: `origination/src/main/java/com/msfg/los/origination/web/dto/IntakeResult.java`
- Create: `origination/src/main/java/com/msfg/los/origination/service/IntakeService.java`
- Create: `origination/src/main/java/com/msfg/los/origination/web/IntakeController.java`
- Modify: `app/.../config/SecurityConfig.java` — allow `Borrower` to POST `/api/loans/intake` (verify path rule).
- Test: `app/src/test/java/com/msfg/los/origination/IntakeControllerIT.java` (new)

- [ ] **Step 1: Write the failing IT** (the crown-jewel test — proves the whole keystone). Run under `local` profile; act as the dev borrower via headers; POST intake twice (idempotency); then read `/api/me/loans` as the same borrower and assert the loan appears.

```java
package com.msfg.los.origination;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class IntakeControllerIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    private static final String SUB = "00000000-0000-0000-0000-0000000000b0";
    private static final String ORG = "00000000-0000-0000-0000-0000000000aa";

    private String borrowerHeaderPost(String body) throws Exception {
        MvcResult r = mvc.perform(post("/api/loans/intake")
                        .header("X-Dev-Sub", SUB).header("X-Dev-Roles", "Borrower").header("X-Dev-Org", ORG)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loanId").exists())
                .andReturn();
        return om.readTree(r.getResponse().getContentAsString()).at("/data/loanId").asText();
    }

    @Test
    void intake_createsLoan_linksBorrower_isIdempotent_andBorrowerCanRead() throws Exception {
        String body = """
            {"sourceLeadId":"lead-A4","loanPurpose":"PURCHASE",
             "borrower":{"firstName":"Ann","lastName":"Buyer","email":"borrower@dev.local","phone":"555-0100"},
             "property":{"addressLine1":"1 Main St","city":"Denver","state":"CO","postalCode":"80202","estimatedValue":350000}}
            """;
        String loanId1 = borrowerHeaderPost(body);
        String loanId2 = borrowerHeaderPost(body);                 // retry → same loan (idempotent)
        assertThat(loanId2).isEqualTo(loanId1);

        // The borrower can now see exactly this loan via /me/loans.
        mvc.perform(get("/api/me/loans?page=0&size=10")
                        .header("X-Dev-Sub", SUB).header("X-Dev-Roles", "Borrower").header("X-Dev-Org", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.id=='" + loanId1 + "')]").exists());
    }
}
```

> Confirm the `/me/loans` envelope path (`$.data.items[].id`) against `MeController`'s response DTO; adjust the JSONPath if the field/shape differs.

- [ ] **Step 2: Run it — expect FAIL** (no `/api/loans/intake` yet):

Run: `./gradlew :app:test --tests 'com.msfg.los.origination.IntakeControllerIT'`
Expected: FAIL (404 / no handler).

- [ ] **Step 3: Create the request DTO** `IntakeRequest.java`:

```java
package com.msfg.los.origination.web.dto;

import com.msfg.los.loan.domain.LoanPurposeType;
import com.msfg.los.loan.domain.MortgageType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Borrower funnel intake (msfg.us → mortgage-app → suite). Minimal 1003; nullable tolerates partial. */
public record IntakeRequest(
        @NotBlank String sourceLeadId,
        @NotNull LoanPurposeType loanPurpose,
        MortgageType mortgageType,
        Borrower borrower,
        Property property) {

    public record Borrower(String firstName, String lastName, String email, String phone) {}
    public record Property(String addressLine1, String city, String state, String postalCode,
                           BigDecimal estimatedValue) {}
}
```

- [ ] **Step 4: Create the result DTO** `IntakeResult.java`:

```java
package com.msfg.los.origination.web.dto;

import java.util.UUID;

public record IntakeResult(UUID loanId, String loanNumber) {}
```

- [ ] **Step 5: Create the service** `IntakeService.java` (mirrors `CloneService`'s cross-module style — services only, one `@Transactional` unit):

```java
package com.msfg.los.origination.service;

import com.msfg.los.loan.domain.Loan;
import com.msfg.los.loan.service.BorrowerUserLinker;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.loan.web.dto.CreateLoanRequest;
import com.msfg.los.loan.web.dto.UpdateLoanRequest;
import com.msfg.los.origination.web.dto.IntakeRequest;
import com.msfg.los.origination.web.dto.IntakeResult;
import com.msfg.los.parties.domain.BorrowerParty;
import com.msfg.los.parties.service.BorrowerService;
import com.msfg.los.parties.web.dto.AddBorrowerRequest;
import com.msfg.los.platform.error.ValidationException;
import com.msfg.los.platform.security.CurrentUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Borrower funnel intake: idempotently create a loan + a primary borrower row linked to the caller's
 * Cognito sub. Cross-module (loan-core + parties) so it lives in {@code origination} (a leaf consumer),
 * going through SERVICES only — never another module's repository (ArchUnit boundary).
 */
@Service
public class IntakeService {

    private final LoanService loanService;
    private final BorrowerService borrowerService;
    private final BorrowerUserLinker borrowerUserLinker;
    private final CurrentUser currentUser;
    /** NOT NULL on the loan row; the borrower's link (not this) grants borrower access. */
    private final UUID defaultLoanOfficerId;

    public IntakeService(LoanService loanService, BorrowerService borrowerService,
                         BorrowerUserLinker borrowerUserLinker, CurrentUser currentUser,
                         @Value("${los.intake.default-loan-officer-id:00000000-0000-0000-0000-000000000001}")
                         String defaultLoanOfficerId) {
        this.loanService = loanService;
        this.borrowerService = borrowerService;
        this.borrowerUserLinker = borrowerUserLinker;
        this.currentUser = currentUser;
        this.defaultLoanOfficerId = UUID.fromString(defaultLoanOfficerId);
    }

    @Transactional
    public IntakeResult intake(IntakeRequest req) {
        // 1) Idempotency: a retried hand-off resolves to the existing loan (no duplicate, no relink).
        var existing = loanService.findBySourceLeadId(req.sourceLeadId());
        if (existing.isPresent()) {
            Loan loan = existing.get();
            return new IntakeResult(loan.getId(), loan.getLoanNumber());
        }

        // 2) Create the loan (explicit default officer — never the borrower).
        Loan loan = loanService.create(new CreateLoanRequest(
                req.loanPurpose(), req.mortgageType(), null, null, null, defaultLoanOfficerId));
        loanService.tagSourceLead(loan.getId(), req.sourceLeadId());

        // 3) Carry the subject property (so the FE detail screen shows address/value).
        IntakeRequest.Property p = req.property();
        if (p != null) {
            loanService.update(loan.getId(), new UpdateLoanRequest(
                    req.mortgageType(), null, null, null,
                    p.addressLine1(), null, p.city(), p.state(), p.postalCode(), p.estimatedValue(),
                    null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null));
        }

        // 4) Primary borrower row + link to the caller's sub (deterministic).
        IntakeRequest.Borrower b = req.borrower();
        if (b == null || b.firstName() == null || b.lastName() == null) {
            throw new ValidationException("borrower.firstName and borrower.lastName are required");
        }
        BorrowerParty party = borrowerService.add(loan.getId(), new AddBorrowerRequest(
                b.firstName(), b.lastName(), true, null, null, null, null, null, null, null, null,
                null, null, null, null, b.phone(), null, null, b.email(), null));

        UUID callerSub = currentUser.id().map(IntakeService::toUuid).orElse(null);
        if (callerSub != null) {
            borrowerUserLinker.linkById(party.getId(), callerSub);
        }
        return new IntakeResult(loan.getId(), loan.getLoanNumber());
    }

    private static UUID toUuid(String s) {
        try { return UUID.fromString(s); } catch (RuntimeException e) { return null; }
    }
}
```

> Verify the `UpdateLoanRequest` constructor arity/order against `loan-core/.../web/dto/UpdateLoanRequest.java` and the usage in `CloneService.createTargetLoan` (lines 146-174 carry 27 args). Match it exactly — the snippet above passes `mortgageType, lienPriority, amortizationType, noteAmount, addressLine1, addressLine2, city, state, postalCode, estimatedValue, …` then nulls through `consummationDate`. If the arity differs, copy the exact null-padded shape from `CloneService`.

- [ ] **Step 6: Create the controller** `IntakeController.java` (mirror `CloneController`):

```java
package com.msfg.los.origination.web;

import com.msfg.los.origination.service.IntakeService;
import com.msfg.los.origination.web.dto.IntakeRequest;
import com.msfg.los.origination.web.dto.IntakeResult;
import com.msfg.los.platform.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/loans/intake — borrower funnel hand-off. Idempotent on sourceLeadId; 200 (a retry returns
 * the existing loan, so "Created" would be wrong). Role gate (Borrower + staff) lives in SecurityConfig.
 */
@RestController
@RequestMapping("/api/loans/intake")
public class IntakeController {

    private final IntakeService service;

    public IntakeController(IntakeService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<IntakeResult>> intake(@Valid @RequestBody IntakeRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.intake(req)));
    }
}
```

- [ ] **Step 7: Authorize the route.** Open `app/.../config/SecurityConfig.java` and find the request-matcher rules. Ensure `POST /api/loans/intake` is permitted for `Borrower` (and staff). If rules are coarse (e.g. `/api/loans/**` already requires authenticated with role check), add an explicit matcher BEFORE any stricter `/api/loans/**` rule:

```java
        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/loans/intake")
            .hasAnyRole("BORROWER", "REAL_ESTATE_AGENT", "LO", "PROCESSOR", "MANAGER", "ADMIN")
```

> Read the existing matcher block first; match its role-name convention (suite uses `ROLE_`-stripped names like `BORROWER`). Under the `local` profile this is bypassed (permitAll), but it must be correct for dev/prod.

- [ ] **Step 8: Run the IT — expect PASS:**

Run: `./gradlew :app:test --tests 'com.msfg.los.origination.IntakeControllerIT'`
Expected: PASS (loan created, idempotent retry returns same id, borrower reads it back).

- [ ] **Step 9: Full module build** to catch ArchUnit/boundary regressions:

Run: `./gradlew :origination:build :app:test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 10: Commit** (owner-gated):

```bash
git add origination/src/main/java/com/msfg/los/origination/ \
        app/src/main/java/com/msfg/los/config/SecurityConfig.java \
        app/src/test/java/com/msfg/los/origination/IntakeControllerIT.java
git commit -m "feat(origination): POST /api/loans/intake — idempotent loan+borrower-link hand-off"
```

---

## Task A5: suite CORS for the local walk

**Files:**
- Modify: `app/src/main/resources/application-local.yml`
- Modify: `app/src/main/java/com/msfg/los/config/CorsConfig.java`
- Test: extend the existing `CorsIT` (find it under `app/src/test`)

- [ ] **Step 1: Add the FE origin.** In `application-local.yml`, change the allowed-origins line to add `:3001` (mortgage-app FE):

```yaml
    allowed-origins: "http://localhost:5173,http://localhost:3000,http://localhost:3001"
```

- [ ] **Step 2: Allow the dev headers.** In `CorsConfig.java`, change the allowed-headers line:

```java
        c.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Dev-Sub", "X-Dev-Roles", "X-Dev-Org"));
```

- [ ] **Step 3: Assert in CorsIT.** Add a preflight assertion that `http://localhost:3001` is allowed and the dev headers are in `Access-Control-Allow-Headers` (mirror the existing CorsIT request style). Run:

Run: `./gradlew :app:test --tests '*CorsIT'`
Expected: PASS.

- [ ] **Step 4: Commit** (owner-gated):

```bash
git add app/src/main/resources/application-local.yml app/src/main/java/com/msfg/los/config/CorsConfig.java app/src/test/**/CorsIT.java
git commit -m "chore(cors): allow :3001 + dev headers for local funnel walk"
```

---

# PART B — mortgage-app (`WebProjects/mortgage-app`)

> Build/test with Maven from `backend/`: `mvn -q test -Dtest=<ClassName>`. Latest mortgage-app migration: check with `ls backend/src/main/resources/db/migration | sort -V | tail -1` and use the next integer.

## Task B1: `SuiteClient`

**Files:**
- Create: `backend/src/main/java/com/msfg/mortgage/config/SuiteClientConfig.java`
- Create: `backend/src/main/java/com/msfg/mortgage/integration/SuiteClient.java`
- Modify: `backend/src/main/resources/application.properties`
- Test: `backend/src/test/java/com/msfg/mortgage/integration/SuiteClientTest.java`

- [ ] **Step 1: Write the failing test** using OkHttp `MockWebServer` (already transitively available via Spring; if not, use `okhttp3.mockwebserver` — add the test-scope dep). Assert SuiteClient POSTs to `/api/loans/intake`, maps loanPurpose, attaches dev headers, and returns the suite loan id:

```java
package com.msfg.mortgage.integration;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

class SuiteClientTest {

    MockWebServer server;
    SuiteClient client;

    @BeforeEach void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        WebClient wc = WebClient.builder().baseUrl(server.url("/").toString().replaceAll("/$", "")).build();
        client = new SuiteClient(wc);
    }
    @AfterEach void tearDown() throws Exception { server.shutdown(); }

    @Test
    void postsIntake_withDevHeaders_returnsLoanId() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"success\":true,\"data\":{\"loanId\":\"11111111-1111-1111-1111-111111111111\",\"loanNumber\":\"LN-1\"}}"));

        SuiteClient.SuiteLoanRef ref = client.createIntake(
                new SuiteClient.IntakePayload("lead-9", "Purchase", "Ann", "Buyer",
                        "borrower@dev.local", "555", "1 Main", "Denver", "CO", "80202", null),
                "00000000-0000-0000-0000-0000000000b0", "Borrower",
                "00000000-0000-0000-0000-0000000000aa");

        assertThat(ref.loanId()).isEqualTo("11111111-1111-1111-1111-111111111111");
        RecordedRequest rr = server.takeRequest();
        assertThat(rr.getPath()).isEqualTo("/api/loans/intake");
        assertThat(rr.getHeader("X-Dev-Sub")).isEqualTo("00000000-0000-0000-0000-0000000000b0");
        assertThat(rr.getHeader("X-Dev-Roles")).isEqualTo("Borrower");
        assertThat(rr.getBody().readUtf8()).contains("\"loanPurpose\":\"PURCHASE\"");
    }
}
```

- [ ] **Step 2: Run it — expect compile failure:**

Run: `cd backend && mvn -q test -Dtest=SuiteClientTest`
Expected: FAIL (SuiteClient not found).

- [ ] **Step 3: Create the WebClient bean** `SuiteClientConfig.java` (constructor-injected base URL — do NOT repeat the GoHighLevelService no-arg-constructor bug):

```java
package com.msfg.mortgage.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class SuiteClientConfig {

    @Bean
    WebClient suiteWebClient(@Value("${suite.api.base-url:http://localhost:8080}") String baseUrl) {
        return WebClient.builder().baseUrl(baseUrl).build();
    }
}
```

- [ ] **Step 4: Create `SuiteClient.java`:**

```java
package com.msfg.mortgage.integration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Server-to-server client to msfg-suite. Creates the loan in suite (the system of record). */
@Service
public class SuiteClient {

    private final WebClient suite;

    public SuiteClient(@Qualifier("suiteWebClient") WebClient suite) {
        this.suite = suite;
    }

    public record IntakePayload(String sourceLeadId, String loanPurpose, String firstName, String lastName,
                                String email, String phone, String addressLine1, String city, String state,
                                String postalCode, BigDecimal estimatedValue) {}

    public record SuiteLoanRef(String loanId, String loanNumber) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Envelope(boolean success, SuiteLoanRef data) {}

    /** mortgage-app loanPurpose ("Purchase|Refinance|CashOut") → suite LoanPurposeType name. */
    private static String mapPurpose(String p) {
        if (p == null) return "OTHER";
        return switch (p) {
            case "Purchase" -> "PURCHASE";
            case "Refinance", "CashOut" -> "REFINANCE";   // suite has no CASH_OUT constant today
            default -> "OTHER";
        };
    }

    public SuiteLoanRef createIntake(IntakePayload in, String devSub, String devRoles, String devOrg) {
        Map<String, Object> borrower = new LinkedHashMap<>();
        borrower.put("firstName", in.firstName());
        borrower.put("lastName", in.lastName());
        borrower.put("email", in.email());
        borrower.put("phone", in.phone());

        Map<String, Object> property = new LinkedHashMap<>();
        property.put("addressLine1", in.addressLine1());
        property.put("city", in.city());
        property.put("state", in.state());
        property.put("postalCode", in.postalCode());
        property.put("estimatedValue", in.estimatedValue());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sourceLeadId", in.sourceLeadId());
        body.put("loanPurpose", mapPurpose(in.loanPurpose()));
        body.put("borrower", borrower);
        body.put("property", property);

        WebClient.RequestBodySpec req = suite.post().uri("/api/loans/intake");
        if (devSub != null)   req = (WebClient.RequestBodySpec) req.header("X-Dev-Sub", devSub);
        if (devRoles != null) req = (WebClient.RequestBodySpec) req.header("X-Dev-Roles", devRoles);
        if (devOrg != null)   req = (WebClient.RequestBodySpec) req.header("X-Dev-Org", devOrg);

        Envelope env = req.bodyValue(body)
                .retrieve()
                .bodyToMono(Envelope.class)
                .block(Duration.ofSeconds(8));
        return env == null ? null : env.data();
    }
}
```

- [ ] **Step 5: Add the config property.** In `application.properties`, after the `ghl.api.*` block, add:

```properties
# msfg-suite (system of record) — borrower funnel hand-off target.
suite.api.base-url=${SUITE_API_BASE:http://localhost:8080}
```

- [ ] **Step 6: Run the test — expect PASS:**

Run: `cd backend && mvn -q test -Dtest=SuiteClientTest`
Expected: PASS.

- [ ] **Step 7: Commit** (owner-gated):

```bash
git add backend/src/main/java/com/msfg/mortgage/config/SuiteClientConfig.java \
        backend/src/main/java/com/msfg/mortgage/integration/SuiteClient.java \
        backend/src/main/resources/application.properties \
        backend/src/test/java/com/msfg/mortgage/integration/SuiteClientTest.java
git commit -m "feat(integration): SuiteClient — create loan in msfg-suite"
```

---

## Task B2: `createFromIntake` writes to suite + returns the suite loan id

**Files:**
- Modify: `backend/src/main/java/com/msfg/mortgage/model/LoanApplication.java`
- Create: `backend/src/main/resources/db/migration/V<next>__add_suite_loan_id.sql`
- Modify: `backend/src/main/java/com/msfg/mortgage/service/LoanApplicationService.java`
- Modify: `backend/src/main/java/com/msfg/mortgage/controller/LoanApplicationController.java`
- Test: `backend/src/test/java/com/msfg/mortgage/service/CreateFromIntakeSuiteTest.java`

- [ ] **Step 1: Write the failing test.** Mock `SuiteClient`; assert `createFromIntake` calls suite once, stores the returned id on the local row, and is not re-fired on an idempotent retry. Use Mockito (already on the classpath via spring-boot-starter-test).

```java
package com.msfg.mortgage.service;

import com.msfg.mortgage.dto.IntakeRequest;
import com.msfg.mortgage.integration.SuiteClient;
import com.msfg.mortgage.model.LoanApplication;
import com.msfg.mortgage.model.User;
import com.msfg.mortgage.repository.LoanApplicationRepository;
import com.msfg.mortgage.repository.LoanStatusHistoryRepository;
import com.msfg.mortgage.repository.UserRepository;
import com.msfg.mortgage.mapper.LoanApplicationMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class CreateFromIntakeSuiteTest {

    @Test
    void createFromIntake_callsSuite_storesSuiteLoanId() {
        var repo = mock(LoanApplicationRepository.class);
        var hist = mock(LoanStatusHistoryRepository.class);
        var mapper = mock(LoanApplicationMapper.class);
        var users = mock(UserRepository.class);
        var suite = mock(SuiteClient.class);
        var devProps = new com.msfg.mortgage.config.DevIdentityProperties(
                "00000000-0000-0000-0000-0000000000b0", "Borrower", "00000000-0000-0000-0000-0000000000aa");

        when(repo.findBySourceLeadId("lead-B2")).thenReturn(Optional.empty());
        when(repo.save(any(LoanApplication.class))).thenAnswer(i -> i.getArgument(0));
        when(suite.createIntake(any(), anyString(), anyString(), anyString()))
                .thenReturn(new SuiteClient.SuiteLoanRef("22222222-2222-2222-2222-222222222222", "LN-9"));

        var service = new LoanApplicationService(repo, hist, mapper, users, suite, devProps);

        IntakeRequest req = new IntakeRequest();
        req.setSourceLeadId("lead-B2");
        req.setLoanPurpose("Purchase");
        IntakeRequest.BorrowerInfo bi = new IntakeRequest.BorrowerInfo();
        bi.setFirstName("Ann"); bi.setLastName("Buyer"); bi.setEmail("borrower@dev.local");
        req.setBorrower(bi);

        User caller = new User(); caller.setId(1);
        LoanApplication app = service.createFromIntake(req, caller);

        assertThat(app.getSuiteLoanId()).isEqualTo("22222222-2222-2222-2222-222222222222");
        verify(suite, times(1)).createIntake(any(), eq("00000000-0000-0000-0000-0000000000b0"), eq("Borrower"), anyString());
    }
}
```

> This test pins a new `LoanApplicationService` constructor signature (adds `SuiteClient` + a `DevIdentityProperties`). Adjust the other constructor args to match the real field list if it has drifted.

- [ ] **Step 2: Run it — expect compile failure:**

Run: `cd backend && mvn -q test -Dtest=CreateFromIntakeSuiteTest`
Expected: FAIL (no `SuiteClient`/`DevIdentityProperties` constructor arg; no `getSuiteLoanId`).

- [ ] **Step 3: Add the entity column.** In `LoanApplication.java`, after the `sourceLeadId` field, add (plain getter/setter — this entity has no Lombok):

```java
    /** The loan's id in msfg-suite (the system of record). Set by the funnel intake hand-off. */
    @Column(name = "suite_loan_id", length = 64)
    private String suiteLoanId;
```

And add the accessor pair near the other getters/setters:

```java
    public String getSuiteLoanId() { return suiteLoanId; }
    public void setSuiteLoanId(String suiteLoanId) { this.suiteLoanId = suiteLoanId; }
```

- [ ] **Step 4: Create the migration.** Determine the next version (`ls backend/src/main/resources/db/migration | sort -V | tail -1`) and create `V<next>__add_suite_loan_id.sql`:

```sql
-- Store the suite (system-of-record) loan id on the local strangler row.
alter table loan_applications add column suite_loan_id varchar(64) null;
```

- [ ] **Step 5: Create `DevIdentityProperties`** `backend/src/main/java/com/msfg/mortgage/config/DevIdentityProperties.java`:

```java
package com.msfg.mortgage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Local-only dev identity forwarded to suite on the funnel hand-off. Bound from suite.dev.* config. */
@ConfigurationProperties(prefix = "suite.dev")
public class DevIdentityProperties {
    private String sub;
    private String roles = "Borrower";
    private String org;

    public DevIdentityProperties() {}
    public DevIdentityProperties(String sub, String roles, String org) {
        this.sub = sub; this.roles = roles; this.org = org;
    }
    public String getSub() { return sub; }
    public void setSub(String sub) { this.sub = sub; }
    public String getRoles() { return roles; }
    public void setRoles(String roles) { this.roles = roles; }
    public String getOrg() { return org; }
    public void setOrg(String org) { this.org = org; }
}
```

Register it by adding `@ConfigurationPropertiesScan` to `MortgageApplication.java` (or `@EnableConfigurationProperties(DevIdentityProperties.class)` on a config class) — check which the app already uses.

- [ ] **Step 6: Wire suite into the service.** In `LoanApplicationService.java`: add `private final SuiteClient suiteClient;` and `private final DevIdentityProperties devIdentity;` fields. Since the class uses `@RequiredArgsConstructor`, adding `final` fields extends the generated constructor — the test's explicit constructor call must match field order (repo, hist, mapper, users, suiteClient, devIdentity). Then, inside `createFromIntake`, AFTER the local `loanApplicationRepository.save(app)` succeeds (keep the strangler local row), create the loan in suite and stamp the id. Replace the final `try { return loanApplicationRepository.save(app); } catch (...)` block with:

```java
        LoanApplication saved;
        try {
            saved = loanApplicationRepository.save(app);
        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
            // Idempotent: a concurrent intake already created it — return the existing row untouched.
            return loanApplicationRepository.findBySourceLeadId(req.getSourceLeadId()).orElseThrow(() -> dup);
        }

        // Strangler hand-off: create the loan in suite (system of record) and store its id locally.
        // Only when not already linked (idempotent on the local row too).
        if (saved.getSuiteLoanId() == null) {
            SuiteClient.IntakePayload payload = new SuiteClient.IntakePayload(
                    req.getSourceLeadId(), req.getLoanPurpose(),
                    bi == null ? null : bi.getFirstName(), bi == null ? null : bi.getLastName(),
                    bi == null ? null : bi.getEmail(), bi == null ? null : bi.getPhone(),
                    pi == null ? null : pi.getAddressLine(), pi == null ? null : pi.getCity(),
                    pi == null ? null : pi.getState(), pi == null ? null : pi.getZipCode(),
                    pi == null ? null : pi.getPropertyValue());
            SuiteClient.SuiteLoanRef ref = suiteClient.createIntake(
                    payload, devIdentity.getSub(), devIdentity.getRoles(), devIdentity.getOrg());
            if (ref != null && ref.loanId() != null) {
                saved.setSuiteLoanId(ref.loanId());
                saved = loanApplicationRepository.save(saved);
            }
        }
        return saved;
```

> `bi` and `pi` are the local variables already declared earlier in `createFromIntake` (`IntakeRequest.BorrowerInfo bi`, `IntakeRequest.PropertyInfo pi`). They are in scope at the end of the method.
>
> In prod (no dev identity, real Cognito) this would forward the borrower Bearer instead of dev headers — out of scope here; `devIdentity.getSub()` is null in non-local profiles, and the dev headers are simply absent (suite then runs real-JWT auth). For this local pass, `suite.dev.*` is set only in `application-local.properties`.

- [ ] **Step 7: Return the suite id from the controller** so the msfg.us deep-link resolves against suite. In `LoanApplicationController.intake`, change the response to prefer the suite loan id:

```java
        LoanApplication app = loanApplicationService.createFromIntake(req, caller);
        Map<String, Object> out = new LinkedHashMap<>();
        // Prefer the suite loan id (UUID) so the FE deep-link /applications/{id} resolves against suite's
        // GET /api/loans/{id}. Fall back to the local id only if the suite hand-off was unavailable.
        out.put("applicationId", app.getSuiteLoanId() != null ? app.getSuiteLoanId() : app.getId());
```

- [ ] **Step 8: Run the test — expect PASS:**

Run: `cd backend && mvn -q test -Dtest=CreateFromIntakeSuiteTest`
Expected: PASS.

- [ ] **Step 9: Commit** (owner-gated):

```bash
git add backend/src/main/java/com/msfg/mortgage/model/LoanApplication.java \
        backend/src/main/resources/db/migration/ \
        backend/src/main/java/com/msfg/mortgage/config/DevIdentityProperties.java \
        backend/src/main/java/com/msfg/mortgage/service/LoanApplicationService.java \
        backend/src/main/java/com/msfg/mortgage/controller/LoanApplicationController.java \
        backend/src/test/java/com/msfg/mortgage/service/CreateFromIntakeSuiteTest.java
git commit -m "feat(intake): create loan in suite, store+return suite loan id (strangler)"
```

---

## Task B3: mortgage-app `local` security profile (dev borrower principal)

**Files:**
- Modify: `backend/src/main/java/com/msfg/mortgage/config/SecurityConfig.java` — add `@Profile("!local")`.
- Create: `backend/src/main/java/com/msfg/mortgage/config/LocalSecurityConfig.java`
- Create: `backend/src/main/resources/application-local.properties`

- [ ] **Step 1: Exclude the Cognito chain under `local`.** In `SecurityConfig.java`, add the annotation:

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@org.springframework.context.annotation.Profile("!local")
public class SecurityConfig {
```

- [ ] **Step 2: Create `LocalSecurityConfig.java`** — permit all + inject a fixed dev BORROWER principal (mirror suite's `LocalDevSecurityConfig`):

```java
package com.msfg.mortgage.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * LOCAL DEV ONLY (@Profile("local")). Boots mortgage-app without Cognito: every request runs as a
 * fixed dev BORROWER, so the msfg.us → /loan-applications/intake hand-off and CurrentUserService work
 * offline. NEVER active in dev/prod (those use SecurityConfig + real Cognito JWT).
 */
@Configuration
@Profile("local")
public class LocalSecurityConfig {

    public static final String DEV_BORROWER_SUB = "00000000-0000-0000-0000-0000000000b0";

    @Bean
    SecurityFilterChain localFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(org.springframework.security.config.Customizer.withDefaults())
            .csrf(c -> c.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(reg -> reg.anyRequest().permitAll())
            .addFilterAfter(new DevBorrowerFilter(), SecurityContextHolderFilter.class);
        return http.build();
    }

    static class DevBorrowerFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            Jwt jwt = Jwt.withTokenValue("local-dev")
                .header("alg", "none")
                .subject(DEV_BORROWER_SUB)
                .claim("email", "borrower@dev.local")
                .claim("cognito:groups", List.of("Borrower"))
                .claim("email_verified", true)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
            var auth = new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_Borrower")));
            SecurityContextHolder.getContext().setAuthentication(auth);
            chain.doFilter(req, res);
        }
    }
}
```

> `CurrentUserService.resolveOrCreate` reads `email`, `sub`, and `cognito:groups` off the JWT — all present above — so it materializes a dev borrower `User` row in H2 on first call. The user's `getId()` (Integer) is the local owner; the borrower's *suite* linkage uses `suite.dev.sub` (set in Step 3), which equals `DEV_BORROWER_SUB`.

- [ ] **Step 3: Create `application-local.properties`** (activated alongside `dev`):

```properties
# Local funnel-walk profile (run as: SPRING_PROFILES_ACTIVE=dev,local). Inherits dev's H2 datasource.
# Forward this dev identity to suite on the intake hand-off (matches suite's dev-header bridge +
# the borrower sub used by the FE so /me/loans returns the loan).
suite.api.base-url=http://localhost:8080
suite.dev.sub=00000000-0000-0000-0000-0000000000b0
suite.dev.roles=Borrower
suite.dev.org=00000000-0000-0000-0000-0000000000aa
```

- [ ] **Step 4: Verify boot under the combined profile.** This is a runtime check (no unit test):

Run: `cd backend && SPRING_PROFILES_ACTIVE=dev,local mvn -q spring-boot:run` (Ctrl-C after it logs "Started MortgageApplication").
Expected: starts cleanly on :8081. If it fails fetching Cognito JWKS at startup, add to `application-local.properties`:
`spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration`

- [ ] **Step 5: Commit** (owner-gated):

```bash
git add backend/src/main/java/com/msfg/mortgage/config/SecurityConfig.java \
        backend/src/main/java/com/msfg/mortgage/config/LocalSecurityConfig.java \
        backend/src/main/resources/application-local.properties
git commit -m "feat(local): mortgage-app dev-borrower security profile for offline funnel walk"
```

---

# PART C — config + runbook

## Task C1: front-end + msfg.us env wiring

**Files:**
- Modify: `WebProjects/mortgage-app/frontend/.env`
- Modify: `WebProjects/mortgage-app/frontend/src/services/apiClient.js`
- Modify: `WebProjects/msfg.us/.env.local`

- [ ] **Step 1: mortgage-app FE `.env`** — point reads at suite, move off :3000, add dev identity:

```
PORT=3001
REACT_APP_API_URL=http://localhost:8080/api
REACT_APP_DEV_SUB=00000000-0000-0000-0000-0000000000b0
REACT_APP_DEV_ROLES=Borrower
REACT_APP_DEV_ORG=00000000-0000-0000-0000-0000000000aa
```

- [ ] **Step 2: Attach dev headers in `apiClient.js`** (local only — when the dev env vars are present). Find where the request interceptor sets `Authorization` (around line 33-44) and add, after it:

```javascript
    // LOCAL-ONLY: when REACT_APP_DEV_SUB is set (no real Cognito locally), send suite dev headers so
    // suite's local-profile bridge scopes us as the borrower. No-op in any build without these vars.
    if (process.env.REACT_APP_DEV_SUB) {
      config.headers['X-Dev-Sub'] = process.env.REACT_APP_DEV_SUB;
      config.headers['X-Dev-Roles'] = process.env.REACT_APP_DEV_ROLES || 'Borrower';
      config.headers['X-Dev-Org'] = process.env.REACT_APP_DEV_ORG || '';
    }
```

> Match the actual interceptor style (axios `config.headers` vs fetch wrapper). Read the file first; adapt the assignment to its shape.

- [ ] **Step 3: msfg.us `.env.local`** — point the hand-off at the local mortgage-app backend and the deep-link at the local FE:

```
DATABASE_URL=postgresql://dev:dev@localhost:5434/msfg_web?schema=public
LOS_API_BASE=http://localhost:8081
NEXT_PUBLIC_APP_URL=http://localhost:3001
```

> `LOS_API_BASE` has no trailing `/api` — msfg.us's `losClient` appends `/api/loan-applications/intake`. Brain/AI vars stay optional (the AI chat is off the critical funnel path).

- [ ] **Step 4: Commit** (owner-gated; note these are in three different repos — commit each in its own repo):

```bash
# in mortgage-app
git -C ~/MSFG/WebProjects/mortgage-app add frontend/.env frontend/src/services/apiClient.js
git -C ~/MSFG/WebProjects/mortgage-app commit -m "chore(fe): local funnel walk — suite base + dev headers + :3001"
# in msfg.us
git -C ~/MSFG/WebProjects/msfg.us add .env.local
git -C ~/MSFG/WebProjects/msfg.us commit -m "chore(env): local funnel walk — handoff to :8081, deep-link to :3001"
```

> `.env`/`.env.local` are often git-ignored. If so, skip the commit and rely on the runbook (C2) to document the values.

---

## Task C2: the runbook + walkthrough

**Files:**
- Create: `docs/local-e2e.md` (this repo)

- [ ] **Step 1: Write `docs/local-e2e.md`** with the boot order, env, and the click-by-click walk. Content:

````markdown
# Local end-to-end funnel walk (msfg.us → mortgage-app → msfg-suite)

Runs the whole borrower funnel locally with NO AWS, using the dev-header identity bridge.
Shared dev constants: org `…00aa`, borrower sub `…00b0`, borrower email `borrower@dev.local`.

## Ports
| App | URL | DB |
|---|---|---|
| msfg-suite | http://localhost:8080 (Swagger /swagger-ui.html) | PG :5432 |
| mortgage-app backend | http://localhost:8081/api | H2 (in-mem) |
| mortgage-app FE | http://localhost:3001 | — |
| msfg.us | http://localhost:3000 | PG :5434 |
| msfg-rag (optional) | http://localhost:8090 | PG :5433 |

## Boot order
1. **suite** — `cd ~/MSFG/msfg-suite && docker compose up -d && ./gradlew :app:bootRun --args='--spring.profiles.active=local'`
2. **mortgage-app backend** — `cd ~/MSFG/WebProjects/mortgage-app/backend && SPRING_PROFILES_ACTIVE=dev,local mvn spring-boot:run`
3. **mortgage-app FE** — `cd ~/MSFG/WebProjects/mortgage-app/frontend && npm install && npm start`  (serves :3001)
4. **msfg.us** — `cd ~/MSFG/WebProjects/msfg.us && npm run db:up && npm run db:migrate && npm run dev`  (serves :3000)

## Smoke test (no browser) — proves the keystone
```bash
# Create a loan in suite as the dev borrower:
curl -s -X POST localhost:8080/api/loans/intake \
  -H 'Content-Type: application/json' \
  -H 'X-Dev-Sub: 00000000-0000-0000-0000-0000000000b0' \
  -H 'X-Dev-Roles: Borrower' -H 'X-Dev-Org: 00000000-0000-0000-0000-0000000000aa' \
  -d '{"sourceLeadId":"smoke-1","loanPurpose":"PURCHASE",
       "borrower":{"firstName":"Ann","lastName":"Buyer","email":"borrower@dev.local","phone":"555-0100"},
       "property":{"addressLine1":"1 Main St","city":"Denver","state":"CO","postalCode":"80202","estimatedValue":350000}}'
# → {"success":true,"data":{"loanId":"<uuid>","loanNumber":"..."}}

# Read it back as the borrower:
curl -s 'localhost:8080/api/me/loans?page=0&size=10' \
  -H 'X-Dev-Sub: 00000000-0000-0000-0000-0000000000b0' -H 'X-Dev-Roles: Borrower' \
  -H 'X-Dev-Org: 00000000-0000-0000-0000-0000000000aa'
# → data.items[] includes the loan above.

# Idempotency: re-run the first curl with the same sourceLeadId → same loanId.
```

## Browser walk
1. Open http://localhost:3000 (msfg.us) → start the apply wizard → complete to the finish step.
2. Finish posts the lead; the server hand-off calls mortgage-app `:8081/api/loan-applications/intake`,
   which creates the loan in **suite** and returns the suite loan id; the browser deep-links to
   `:3001/applications/<suiteLoanId>`.
3. The mortgage-app FE detail screen loads `GET :8080/api/loans/<suiteLoanId>` (from suite) — the loan shows.
4. Go to `:3001/applications` (the list) → `GET :8080/api/me/loans` → the loan is listed.
5. Open the staff console (msfg-suite-web) → the loan is visible to staff (run it as ADMIN: no dev headers,
   or run suite-web against suite :8080).

## Still gated (not part of this pass)
- Real Cognito token E2E (depends on the owner's `org_id` pre-token Lambda).
- Deploy / DNS (`api.msfgco.com`, `los.msfgco.com`) — Phase C, human-gated.
- msfg-rag corpus population (AI chat degrades to "unavailable" without it).
````

- [ ] **Step 2: Execute the smoke test** from the runbook against the running stack and confirm the JSON matches. This is the acceptance gate for the whole plan.

Expected: intake returns a `loanId`; `/me/loans` lists it; a repeat intake returns the same `loanId`.

- [ ] **Step 3: Commit** (owner-gated):

```bash
git add docs/local-e2e.md
git commit -m "docs: local end-to-end funnel walk runbook"
```

---

## Self-Review (completed)

- **Spec coverage:** Dev-header bridge → A2. Keystone intake→suite → A4 (suite) + B1/B2 (mortgage-app). Borrower linkage → A3 + A4. Strangler dual-write (D-LE2) → B2. Ports (D-LE3) → C1 + A5 (CORS :3001) + B3. msfg-rag optional (D-LE4) → noted in C2. Runbook/walkthrough → C2. All spec sections map to a task.
- **Type consistency:** `IntakeResult(loanId, loanNumber)`, `SuiteClient.SuiteLoanRef(loanId, loanNumber)`, `IntakePayload` fields, and `LoanService.findBySourceLeadId/tagSourceLead` signatures are consistent across A1/A4/B1/B2. `LoanPurposeType` (PURCHASE/REFINANCE/CONSTRUCTION/OTHER) drives `SuiteClient.mapPurpose`. `BorrowerUserLinker.linkById` used in A4 is defined in A3.
- **Known verify-at-build points (call out, don't guess):** the exact `UpdateLoanRequest` arity (copy from `CloneService.createTargetLoan`), the `/me/loans` JSON envelope path, the `LoanApplicationService` `@RequiredArgsConstructor` field order, mortgage-app's next Flyway version, and whether `apiClient.js` uses axios or fetch. Each step says to read the real source first.
````