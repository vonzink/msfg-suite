# Role Access Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
> Spec: `docs/specs/2026-06-11-role-access-model.md`. **No migration, no schema change.** Security-critical → opus review pass before merge.

**Goal:** PROCESSOR/UNDERWRITER/CLOSER get org-wide loan access (LO stays owner-scoped, ADMIN unchanged, PLATFORM_ADMIN excluded) so underwriters can actually decide CoCs and ops roles have a pipeline.

**Architecture:** one chokepoint — `LoanAccessGuard` (loan-core) gains an org-wide-roles pass and a `hasOrgWideView()` helper; `LoanController.pipeline` threads that helper instead of `isAdmin()`. All 24 `assertCanAccess` call sites across 10 modules inherit the change. Everything else (tenancy 404s, action gates, `POST /api/loans` LO/ADMIN) is untouched.

**Tech Stack:** Java 21 · Spring Boot 3.3 · JUnit 5 + MockMvc ITs (Testcontainers — Docker must be running) · `./gradlew` (wrapper).

**⚠️ Worktree protocol (shared checkout is owned by a parallel session — NEVER `git checkout` in `/Users/zacharyzink/MSFG/msfg-suite`):**
```bash
cd /Users/zacharyzink/MSFG/msfg-suite
git worktree add ~/.config/superpowers/worktrees/msfg-suite/role-access-model -b feat/role-access-model main
cd ~/.config/superpowers/worktrees/msfg-suite/role-access-model   # ALL work happens here
```

---

### Task 1: `LoanAccessGuard` role matrix — guard change (TDD)

**Files:**
- Test (create): `loan-core/src/test/java/com/msfg/los/loan/service/LoanAccessGuardTest.java`
- Modify: `loan-core/src/main/java/com/msfg/los/loan/service/LoanAccessGuard.java`

- [ ] **Step 1: Write the failing unit test** (no Spring context; a test subclass of `CurrentUser` avoids SecurityContext setup — `CurrentUser`'s methods are non-final and `isAdmin()` delegates to `roles()`):

```java
package com.msfg.los.loan.service;

import com.msfg.los.loan.domain.Loan;
import com.msfg.los.platform.error.ForbiddenException;
import com.msfg.los.platform.security.CurrentUser;
import com.msfg.los.platform.security.Role;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoanAccessGuardTest {

    private static final UUID OWNER = UUID.randomUUID();

    private Loan loanOwnedByOwner() {
        Loan l = new Loan();
        l.setLoanOfficerId(OWNER);
        l.setLoanNumber("LN-TEST");
        return l;
    }

    private CurrentUser userWith(String subject, String... authorities) {
        return new CurrentUser() {
            @Override public Optional<String> id() { return Optional.ofNullable(subject); }
            @Override public Set<String> roles() { return Set.of(authorities); }
        };
    }

    private void assertAllowed(CurrentUser u) {
        assertThatCode(() -> new LoanAccessGuard(u).assertCanAccess(loanOwnedByOwner()))
                .doesNotThrowAnyException();
    }

    private void assertDenied(CurrentUser u) {
        assertThatThrownBy(() -> new LoanAccessGuard(u).assertCanAccess(loanOwnedByOwner()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test void owningLoAllowed()      { assertAllowed(userWith(OWNER.toString(), Role.LO.authority())); }
    @Test void otherLoDenied()        { assertDenied(userWith(UUID.randomUUID().toString(), Role.LO.authority())); }
    @Test void adminAllowed()         { assertAllowed(userWith(UUID.randomUUID().toString(), Role.ADMIN.authority())); }
    @Test void processorAllowed()     { assertAllowed(userWith(UUID.randomUUID().toString(), Role.PROCESSOR.authority())); }
    @Test void underwriterAllowed()   { assertAllowed(userWith(UUID.randomUUID().toString(), Role.UNDERWRITER.authority())); }
    @Test void closerAllowed()        { assertAllowed(userWith(UUID.randomUUID().toString(), Role.CLOSER.authority())); }
    @Test void platformAdminDenied()  { assertDenied(userWith(UUID.randomUUID().toString(), Role.PLATFORM_ADMIN.authority())); }
    @Test void noSubjectDenied()      { assertDenied(userWith(null, Role.LO.authority())); }
    @Test void garbledSubjectDenied() { assertDenied(userWith("not-a-uuid", Role.LO.authority())); }
}
```

- [ ] **Step 2: Run it — verify exactly the 3 ops-role cases fail (403 today):**

Run: `./gradlew :loan-core:test --tests '*LoanAccessGuardTest' --console=plain`
Expected: FAIL — `processorAllowed`, `underwriterAllowed`, `closerAllowed` throw `ForbiddenException`; the other 6 pass.

- [ ] **Step 3: Implement the guard change** — full new body of `LoanAccessGuard.java`:

```java
package com.msfg.los.loan.service;

import com.msfg.los.loan.domain.Loan;
import com.msfg.los.platform.error.ForbiddenException;
import com.msfg.los.platform.security.CurrentUser;
import com.msfg.los.platform.security.Role;
import org.springframework.stereotype.Component;
import java.util.Set;
import java.util.UUID;

@Component
public class LoanAccessGuard {

    // Back-office roles work the whole org's pipeline (spec 2026-06-11). PLATFORM_ADMIN
    // is deliberately absent: platform operators administer orgs, not loan files.
    private static final Set<String> ORG_WIDE_AUTHORITIES = Set.of(
            Role.PROCESSOR.authority(), Role.UNDERWRITER.authority(), Role.CLOSER.authority());

    private final CurrentUser currentUser;

    public LoanAccessGuard(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    /** True when the caller may see every loan in their org (ADMIN or a back-office role). */
    public boolean hasOrgWideView() {
        if (currentUser.isAdmin()) return true;
        return currentUser.roles().stream().anyMatch(ORG_WIDE_AUTHORITIES::contains);
    }

    public void assertCanAccess(Loan loan) {
        if (hasOrgWideView()) return;
        String me = currentUser.id().orElse(null);
        UUID meId = null;
        if (me != null) {
            try { meId = UUID.fromString(me); } catch (IllegalArgumentException ignored) {}
        }
        if (meId == null || !loan.getLoanOfficerId().equals(meId)) {
            throw new ForbiddenException("No access to loan " + loan.getLoanNumber());
        }
    }
}
```

- [ ] **Step 4: Run the unit test — all 9 pass; run the module:**

Run: `./gradlew :loan-core:test --console=plain`
Expected: BUILD SUCCESSFUL (LoanLifecycleTest + new matrix all green).

- [ ] **Step 5: Commit**

```bash
git add loan-core/src/main/java/com/msfg/los/loan/service/LoanAccessGuard.java loan-core/src/test/java/com/msfg/los/loan/service/LoanAccessGuardTest.java
git commit -m "feat(loan-core): back-office roles (PROCESSOR/UNDERWRITER/CLOSER) get org-wide loan access

One-chokepoint change per docs/specs/2026-06-11-role-access-model.md — all
assertCanAccess call sites inherit it. PLATFORM_ADMIN deliberately excluded
(pinned). LO stays owner-scoped. Full role-matrix unit test."
```

---

### Task 2: org-wide pipeline for ops roles (TDD)

**Files:**
- Test (create): `app/src/test/java/com/msfg/los/loan/web/RoleAccessIT.java`
- Modify: `loan-core/src/main/java/com/msfg/los/loan/web/LoanController.java:51`
- Modify: `loan-core/src/main/java/com/msfg/los/loan/service/LoanService.java:75-79` (param rename only)

- [ ] **Step 1: Write the failing IT** (mirrors `LoanControllerIT` helper idiom):

```java
package com.msfg.los.loan.web;

import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Back-office org-wide access (spec 2026-06-11): PROCESSOR/UNDERWRITER/CLOSER vs LO/PLATFORM_ADMIN. */
class RoleAccessIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    static final String LO_A = UUID.randomUUID().toString();

    private RequestPostProcessor as(String sub, String role) {
        return jwt().jwt(j -> j.subject(sub).claim("org_id", DEFAULT_ORG))
                .authorities(new SimpleGrantedAuthority(role));
    }

    private String createLoanOwnedByLoA() throws Exception {
        var res = mvc.perform(post("/api/loans").with(as(LO_A, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(LO_A)))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    // --- ops roles can open another LO's loan ---

    @Test
    void processorCanReadAnotherLosLoan() throws Exception {
        String id = createLoanOwnedByLoA();
        mvc.perform(get("/api/loans/{id}", id).with(as(UUID.randomUUID().toString(), "ROLE_PROCESSOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id));
    }

    @Test
    void underwriterCanReadAnotherLosLoan() throws Exception {
        String id = createLoanOwnedByLoA();
        mvc.perform(get("/api/loans/{id}", id).with(as(UUID.randomUUID().toString(), "ROLE_UNDERWRITER")))
                .andExpect(status().isOk());
    }

    @Test
    void closerCanReadAnotherLosLoan() throws Exception {
        String id = createLoanOwnedByLoA();
        mvc.perform(get("/api/loans/{id}", id).with(as(UUID.randomUUID().toString(), "ROLE_CLOSER")))
                .andExpect(status().isOk());
    }

    // --- ops pipeline is org-wide ---

    @Test
    void processorPipelineListsOtherLosLoans() throws Exception {
        String id = createLoanOwnedByLoA();
        mvc.perform(get("/api/loans?size=100").with(as(UUID.randomUUID().toString(), "ROLE_PROCESSOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].id", hasItem(id)));
    }

    // --- LO stays scoped ---

    @Test
    void loPipelineDoesNotListOtherLosLoans() throws Exception {
        String id = createLoanOwnedByLoA();
        mvc.perform(get("/api/loans?size=100").with(as(UUID.randomUUID().toString(), "ROLE_LO")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].id", not(hasItem(id))));
    }

    // --- PLATFORM_ADMIN pinned out of loan data ---

    @Test
    void platformAdminCannotReadLoans403() throws Exception {
        String id = createLoanOwnedByLoA();
        mvc.perform(get("/api/loans/{id}", id).with(as(UUID.randomUUID().toString(), "ROLE_PLATFORM_ADMIN")))
                .andExpect(status().isForbidden());
    }

    // --- role breadth never crosses the tenant wall ---

    @Test
    void processorCrossOrgStill404() throws Exception {
        String id = createLoanOwnedByLoA();
        mvc.perform(get("/api/loans/{id}", id)
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                                        .claim("org_id", "ffffffff-ffff-ffff-ffff-ffffffffffff"))
                                .authorities(new SimpleGrantedAuthority("ROLE_PROCESSOR"))))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: Run it — guard-dependent tests pass (Task 1 landed), pipeline test fails:**

Run: `./gradlew :app:test --tests '*RoleAccessIT' --console=plain`
Expected: FAIL — `processorPipelineListsOtherLosLoans` (processor pipeline is LO-scoped → empty). The three read tests + LO/platform/cross-org tests PASS already.

- [ ] **Step 3: Thread `hasOrgWideView()` through the pipeline.** In `LoanController.java` line 51 replace:

```java
        Page<LoanListItemResponse> result = service.pipeline(me, status, currentUser.isAdmin(), PageRequest.of(page, size));
```
with:
```java
        Page<LoanListItemResponse> result = service.pipeline(me, status, accessGuard.hasOrgWideView(), PageRequest.of(page, size));
```
In `LoanService.java` rename the `pipeline` parameter for honesty (logic unchanged):
```java
    public Page<LoanListItemResponse> pipeline(UUID loanOfficerId, LoanStatus status, boolean orgWideView, Pageable pageable) {
        Page<Loan> page;
        if (orgWideView) {
```
(`LoanController` already injects `accessGuard` — used at line 58.)

- [ ] **Step 4: Run the IT class — all 7 pass; loan suite stays green:**

Run: `./gradlew :app:test --tests '*RoleAccessIT' --tests '*LoanControllerIT' --tests '*LoanPipelineEnrichmentIT' --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/com/msfg/los/loan/web/RoleAccessIT.java loan-core/src/main/java/com/msfg/los/loan/web/LoanController.java loan-core/src/main/java/com/msfg/los/loan/service/LoanService.java
git commit -m "feat(loan-core): org-wide pipeline for back-office roles via LoanAccessGuard.hasOrgWideView

RoleAccessIT pins the full matrix: ops roles read+list org-wide, LO stays
scoped, PLATFORM_ADMIN 403, cross-org still 404."
```

---

### Task 3: honest distinct-subject underwriter tests (replaces the masked workaround)

**Files:**
- Modify: `app/src/test/java/com/msfg/los/coc/CocDecisionIT.java` (the `underwriter()` helper + the accept test's `decisionBy` assert)
- Modify: `app/src/test/java/com/msfg/los/loan/web/LoanControllerIT.java` (the `underwriterSeesGatedTransitionsForInUnderwritingLoan` jwt)

- [ ] **Step 1: Make the CoC underwriter a different person.** In `CocDecisionIT.java` replace the helper (currently same-subject-as-LO with a comment saying so):

```java
    // Same sub as LO (same person, same org), different role — mirrors LoanControllerIT pattern
    private RequestPostProcessor underwriter() {
        return jwt().jwt(j -> j.subject(LO).claim("org_id", DEFAULT_ORG))
                .authorities(new SimpleGrantedAuthority("ROLE_UNDERWRITER"));
    }
```
with:
```java
    // A real underwriter: DIFFERENT subject than the LO, same org. Before the 2026-06-11
    // role-access spec this was impossible (guard 403'd non-owners before the role gate).
    static final String UW = UUID.randomUUID().toString();

    private RequestPostProcessor underwriter() {
        return jwt().jwt(j -> j.subject(UW).claim("org_id", DEFAULT_ORG))
                .authorities(new SimpleGrantedAuthority("ROLE_UNDERWRITER"));
    }
```

- [ ] **Step 2: Strengthen the accept test's audit assert.** In `underwriterDecisionAcceptReturns200Accepted`, find the existing `decisionBy` assertion (currently a not-null check) and replace it with subject equality:

```java
                .andExpect(jsonPath("$.data.decisionBy").value(UW))
```
(Keep the surrounding status/decisionDate asserts as they are.)

- [ ] **Step 3: Same honesty for the transitions E2E.** In `LoanControllerIT.underwriterSeesGatedTransitionsForInUnderwritingLoan`, replace:

```java
        mvc.perform(get("/api/loans/{id}/status/transitions", id)
                .with(jwt().jwt(j -> j.subject(LO).claim("org_id", DEFAULT_ORG))
                           .authorities(new SimpleGrantedAuthority("ROLE_UNDERWRITER"))))
```
with:
```java
        mvc.perform(get("/api/loans/{id}/status/transitions", id)
                .with(user(UUID.randomUUID().toString(), "ROLE_UNDERWRITER")))
```
(`user(sub, role)` already exists in that file. Also update the test's javadoc line "same sub as LO" if present.)

- [ ] **Step 4: Run both IT classes:**

Run: `./gradlew :app:test --tests '*CocDecisionIT' --tests '*LoanControllerIT' --console=plain`
Expected: BUILD SUCCESSFUL — the distinct-subject underwriter now reaches the role gate and decides; `decisionBy` equals the UW subject.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/com/msfg/los/coc/CocDecisionIT.java app/src/test/java/com/msfg/los/loan/web/LoanControllerIT.java
git commit -m "test(coc,loan-core): underwriter ITs use a real distinct subject; assert decisionBy identity

Removes the same-subject workaround that masked the access-model gap."
```

---

### Task 4: full build + security review + finish

- [ ] **Step 1: FULL build** — `./gradlew build --console=plain` → BUILD SUCCESSFUL. Report the total test count (expect ~265+: 252 at V13 + handler/REO additions + ~17 new here). `OpenApiDocsIT` must stay green (schema is byte-identical — no new DTOs).
- [ ] **Step 2: Opus security review pass** (project convention for access-control changes): dispatch a review subagent over `git diff main...feat/role-access-model` with the spec; focus: no tenant-wall weakening (guard runs AFTER `findByIdAndOrgId` 404s), PLATFORM_ADMIN exclusion, no module bypasses the guard chokepoint, pipeline query can't leak cross-org (`@TenantId` filters `findAll`).
- [ ] **Step 3: Finish per worktree protocol** — do NOT checkout in the shared repo. From the worktree: if `main` hasn't moved: `git fetch . feat/role-access-model:main`; if it has: `git rebase main` → full build again → then the ff-fetch. Delete branch + worktree after.
- [ ] **Step 4: Close the loop** — restart local bootRun from the main checkout (behavior change; no `gen:api` needed), append a short dated section to `msfg-suite-web/docs/HANDOFF-FROM-BACKEND.md` ("underwriter/processor/closer org-wide access live — your caveat #1 / role-gated UI now works under real auth semantics"), update `docs/frontend-integration.md` roles note + CLAUDE.md status line + memory.

## Self-Review
Spec coverage: guard change (T1) ✓ pipeline (T2) ✓ PLATFORM_ADMIN pin (T1 unit + T2 IT) ✓ honest UW tests (T3) ✓ cross-org 404 (T2) ✓ no-flips verified (only ops-role 403 test is the create-gate, untouched) ✓ opus pass + loop-close (T4) ✓. No placeholders; types/signatures consistent (`hasOrgWideView` defined T1, used T2). Single plan, no decomposition needed. ✓
