# Contract-Nits Batch — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development + test-driven-development.
> Spec: `docs/specs/2026-06-09-contract-nits-batch.md`. **Additive only; no migration.** Keep `/v3/api-docs` green.

**Goal:** four additive loan-contract refinements (§3.1–§3.4) the frontend's typed client needs.

**Architecture:** mostly `loan-core` (DTOs/service/lifecycle/controller); §3.2 adds a `PrimaryBorrowerNameResolver`
port (interface in loan-core, `@Component` adapter in `parties`). No new tables.

## Analogs / facts
- `LoanController`/`LoanService`/`LoanLifecycle`/`CreateLoanRequest`/`LoanSummaryResponse`/`LoanListItemResponse`
  live in `loan-core`. `CurrentUser` (`platform.security`, `id():Optional<String>`, `roles():Set<String>`,
  `isAdmin()`). `Role` (`platform.security`, `.authority()` → `"ROLE_X"`). `BorrowerParty` has `isPrimary()`,
  `getFirstName()`, `getLastName()`, `getLoanId()`. `loan-core` does NOT depend on `parties` (port required).
- IT base: `app/src/test/java/com/msfg/los/support/AbstractIntegrationTest.java` (`DEFAULT_ORG`, JWT helper).
  Existing loan ITs in `app/src/test/.../loan/web/` show the create/PATCH/JWT patterns.

---

## Task 1: §3.1 — `LoanSummaryResponse` +6 fields
- [ ] Append to the record (after `numberOfUnits`): `LienPriorityType lienPriority, AmortizationType
  amortizationType, String addressLine1, String addressLine2, String postalCode, BigDecimal estimatedValue`.
  In `from(Loan l)`: `l.getLienPriority()`, `l.getAmortizationType()`, and (null-safe on `sp`)
  `sp.getAddressLine1()`, `sp.getAddressLine2()`, `sp.getPostalCode()`, `sp.getEstimatedValue()`.
- [ ] Fix any other constructor caller (only `from` should construct it; `LoanServiceIT` may build it positionally
  — extend with the 6 new args if so).
- [ ] `./gradlew :app:test --tests "*Loan*"` compiles + green. Commit `feat(loan-core): LoanSummaryResponse +lien/amortization/address/estimatedValue (§3.1)`.

## Task 2: §3.3 — `loanOfficerId` defaults to principal
- [ ] `CreateLoanRequest`: change `@NotNull UUID loanOfficerId` → `UUID loanOfficerId` (drop the annotation + import if now unused).
- [ ] `LoanService`: inject `CurrentUser`. In `create(req)`, before building the loan:
```java
UUID officer = req.loanOfficerId();
if (officer == null) {
    officer = currentUser.id().map(s -> { try { return java.util.UUID.fromString(s); } catch (RuntimeException e) { return null; } }).orElse(null);
}
if (officer == null) throw new ValidationException("loanOfficerId is required");
```
  then set `officer` on the loan (replace the previous `req.loanOfficerId()` usage). `import com.msfg.los.platform.error.ValidationException;`
- [ ] **IT** (extend a loan IT or add `LoanOfficerDefaultIT`): `POST /api/loans` with body OMITTING `loanOfficerId`
  → 201, and `$.data.loanOfficerId` equals the caller's principal id. (Under the test JWT, set a known subject and assert it.) With an explicit `loanOfficerId` → that value is used.
- [ ] `./gradlew :app:test --tests "*Loan*"` green. Commit `feat(loan-core): default loanOfficerId to authenticated principal (§3.3)`.

## Task 3: §3.4 — allowed-transitions endpoint
- [ ] **`LoanLifecycle`** add:
```java
public java.util.List<LoanStatus> allowedTransitions(LoanStatus from, java.util.Set<String> authorities) {
    java.util.List<LoanStatus> out = new java.util.ArrayList<>();
    FORWARD.getOrDefault(from, java.util.Set.of()).stream()
        .sorted(java.util.Comparator.comparingInt(Enum::ordinal))
        .forEach(out::add);
    if (!from.isTerminal()) { out.add(LoanStatus.WITHDRAWN); out.add(LoanStatus.CANCELLED); }
    return out.stream().filter(to -> {
        Role required = ENTRY_ROLE.get(to);
        return required == null || authorities.contains(required.authority()) || authorities.contains(Role.ADMIN.authority());
    }).toList();
}
```
  (Don't change `assertTransition`.) **Unit test** in `loan-core/src/test/.../domain/`: `STARTED` + LO-authorities
  → `[APPLICATION_IN_PROGRESS, WITHDRAWN, CANCELLED]`; `IN_UNDERWRITING` + LO → `[WITHDRAWN, CANCELLED]` (gated
  targets filtered); `IN_UNDERWRITING` + `ROLE_UNDERWRITER` → includes `APPROVED_WITH_CONDITIONS, DENIED, SUSPENDED`;
  a terminal status → `[]`.
- [ ] **DTO** `loan-core/.../web/dto/TransitionsResponse.java`: `record TransitionsResponse(LoanStatus currentStatus, java.util.List<LoanStatus> allowedTransitions) {}`.
- [ ] **`LoanController`**: inject `LoanLifecycle lifecycle` (add to constructor). Add:
```java
@GetMapping("/{id}/status/transitions")
public ApiResponse<TransitionsResponse> transitions(@PathVariable UUID id) {
    Loan loan = service.get(id);
    accessGuard.assertCanAccess(loan);
    return ApiResponse.ok(new TransitionsResponse(loan.getStatus(),
        lifecycle.allowedTransitions(loan.getStatus(), currentUser.roles())));
}
```
- [ ] **IT**: `GET /api/loans/{id}/status/transitions` on a fresh (`STARTED`) loan as an LO → `$.data.currentStatus == "STARTED"`, `$.data.allowedTransitions` contains `APPLICATION_IN_PROGRESS`, `WITHDRAWN`, `CANCELLED` (and NOT a gated target); cross-org JWT → 404; no token → 401.
- [ ] `./gradlew :app:test --tests "*Loan*" --tests "*Lifecycle*"` green. Commit `feat(loan-core): role-aware GET /loans/{id}/status/transitions (§3.4)`.

## Task 4: §3.2 — pipeline enrichment via port resolver
- [ ] **Port (loan-core)** `loan-core/.../service/PrimaryBorrowerNameResolver.java`:
```java
package com.msfg.los.loan.service;
import java.util.*;
public interface PrimaryBorrowerNameResolver {
    Map<UUID, String> primaryBorrowerNamesByLoanIds(Collection<UUID> loanIds);
}
```
- [ ] **Repo (parties)** add to `BorrowerRepository`: `java.util.List<BorrowerParty> findByLoanIdInAndPrimaryTrue(java.util.Collection<UUID> loanIds);`
- [ ] **Adapter (parties)** `parties/.../service/PrimaryBorrowerNameAdapter.java`:
```java
package com.msfg.los.parties.service;
import com.msfg.los.loan.service.PrimaryBorrowerNameResolver;
import com.msfg.los.parties.domain.BorrowerParty;
import com.msfg.los.parties.repo.BorrowerRepository;
import org.springframework.stereotype.Component;
import java.util.*;
@Component
public class PrimaryBorrowerNameAdapter implements PrimaryBorrowerNameResolver {
    private final BorrowerRepository borrowers;
    public PrimaryBorrowerNameAdapter(BorrowerRepository borrowers) { this.borrowers = borrowers; }
    @Override public Map<UUID, String> primaryBorrowerNamesByLoanIds(Collection<UUID> loanIds) {
        Map<UUID, String> out = new HashMap<>();
        if (loanIds == null || loanIds.isEmpty()) return out;
        for (BorrowerParty b : borrowers.findByLoanIdInAndPrimaryTrue(loanIds)) {
            out.putIfAbsent(b.getLoanId(), name(b));   // first primary per loan wins
        }
        return out;
    }
    private static String name(BorrowerParty b) {
        String f = b.getFirstName() == null ? "" : b.getFirstName();
        String l = b.getLastName() == null ? "" : b.getLastName();
        String full = (f + " " + l).trim();
        return full.isEmpty() ? null : full;
    }
}
```
- [ ] **`LoanListItemResponse`** → `record LoanListItemResponse(UUID id, String loanNumber, LoanStatus status, UUID loanOfficerId, String primaryBorrowerName, String propertyCity, String propertyState, java.time.Instant updatedAt)` with `from(Loan l, String primaryBorrowerName)` (propertyCity/State null-safe from `sp`, `updatedAt = l.getUpdatedAt()`). Remove/replace the old `from(Loan)`.
- [ ] **`LoanService`**: inject `PrimaryBorrowerNameResolver resolver`. Change `pipeline(...)` to return
  `Page<LoanListItemResponse>`: get the `Page<Loan>`, `var names = resolver.primaryBorrowerNamesByLoanIds(page.map(Loan::getId).getContent())`, return `page.map(l -> LoanListItemResponse.from(l, names.get(l.getId())))`.
- [ ] **`LoanController.pipeline`**: `Page<LoanListItemResponse> result = service.pipeline(me, status, currentUser.isAdmin(), PageRequest.of(page, size)); return ApiResponse.ok(PagedResponse.from(result));` (drop the `.map(LoanListItemResponse::from)`).
- [ ] **IT (crown jewel)**: create a loan, PATCH subject-property city/state, add a primary borrower ("Abbas Hussein"); `GET /api/loans` → find that row → `primaryBorrowerName == "Abbas Hussein"`, `propertyCity`/`propertyState` set, `updatedAt` non-null; a second loan with no borrower → its `primaryBorrowerName` is null.
- [ ] `./gradlew :app:test --tests "*Loan*"` + `./gradlew :app:test --tests "*OpenApiDocsIT"` green. Commit `feat(loan-core,parties): enrich pipeline list with primary borrower name + property + updatedAt via resolver port (§3.2)`.

## Task 5: Full build + finish
- [ ] FULL `./gradlew build` → SUCCESSFUL (all green incl. `OpenApiDocsIT`). Report total test count.
- [ ] Update `docs/ROADMAP.md` note if applicable. Then **superpowers:finishing-a-development-branch**.

## Self-Review
Additive only (new fields/endpoint, relaxed-and-defaulted field) — no renames/removals. No migration. Port keeps
the loan-core→parties dependency direction intact. Role-aware transitions. `/v3/api-docs` stays collision-free
(unique `TransitionsResponse`). Crown-jewel ITs assert real values. ✓
