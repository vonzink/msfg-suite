# Change of Circumstance Module — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development + test-driven-development.
> Spec: `docs/specs/2026-06-09-coc-module.md`. Loan-scoped; `jsonb` change arrays; V12. Keep `/v3/api-docs` green.

**Goal:** per-loan CoC draft (save/load) + submit→PENDING history + underwriter ACCEPT/DENY decision.

**Architecture:** new `coc` module (deps `:platform`, `:loan-core`), loan-scoped (mirror `reo`/`fees` guard).
Change arrays as `jsonb` (mirror `tenancy/.../Organization`). Migration `V12`.

## Templates to mirror
- jsonb field mapping: `tenancy/src/main/java/com/msfg/los/tenancy/domain/Organization.java` →
  `@JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false, columnDefinition="jsonb") private List<X> ...;`
  (imports `org.hibernate.annotations.JdbcTypeCode`, `org.hibernate.type.SqlTypes`).
- Loan-scoped service guard: `fees/.../service/FeeService.java` / `reo/.../service/ReoService.java`
  (`org()`, `accessGuard.assertCanAccess(loanService.get(loanId))` first, `findByIdAndOrgId(...).filter(loanId).orElseThrow(NotFound)`).
- Role-gate pattern: `loan-core/.../domain/LoanLifecycle.java` (checks `authorities.contains(Role.X.authority())`).
  `CurrentUser` (`id():Optional<String>`, `roles():Set<String>`). `Role` (`platform.security`, `.authority()`→"ROLE_X").
- `coc/build.gradle.kts` = deps `:platform`, `:loan-core`, web/data-jpa/validation.
- Error mapping: `ValidationException`→400, `ConflictException`→409, `ForbiddenException`→403, `NotFoundException`→404. `@NotNull` on a DTO field → 400 `VALIDATION_ERROR` with `fields.<name>`.

---

## Task 0: Scaffold `coc` + enums + records + V12
- [ ] settings/app/build wiring (deps platform + loan-core). Enums `CocReason`, `CocStatus`, `CocDecision`. Records `StructureChange`, `FeeChange` (per spec).
- [ ] `app/.../db/migration/V12__coc.sql` (per spec — two tables, jsonb columns `not null default '[]'::jsonb`, `unique (org_id, loan_id)` on coc_draft, indexes, FORCE RLS + tenant_isolation + grants; mirror `V11`).
- [ ] `./gradlew :coc:classes :app:compileJava` + `:app:test --tests "*LosApplicationTests"` → PASS. Commit `chore(coc): scaffold module + enums + records + V12 migration`.

## Task 1: Entities + repos
- [ ] `CocDraft` (extends `TenantScopedEntity`): `UUID loanId`(not null), `LocalDate dateOfDiscovery`, `@Enumerated(STRING) CocReason reason`, `@JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition="jsonb",nullable=false) List<StructureChange> structureChanges = new ArrayList<>()`, same for `List<FeeChange> feeChanges`.
- [ ] `CocHistoryEntry` (extends `TenantScopedEntity`): `loanId`, `dateOfDiscovery`, `@Enumerated(STRING) CocReason reason`, jsonb `structureChanges`/`feeChanges`, `@Enumerated(STRING) CocStatus status`, `Instant submittedAt`, `String submittedBy`, `String decisionBy`, `Instant decisionDate`.
- [ ] `CocDraftRepository`: `findByLoanId(UUID)` (Optional), `findByIdAndOrgId`. `CocHistoryEntryRepository`: `findByLoanIdOrderBySubmittedAtDesc(UUID)`, `findByIdAndOrgId`.
- [ ] `:app:test --tests "*LosApplicationTests"` → PASS (entities + jsonb validate vs V12). Commit `feat(coc): CocDraft + CocHistoryEntry entities (jsonb change arrays) + repos`.

## Task 2: Draft save/load
- [ ] DTOs: `CocDraftRequest(LocalDate dateOfDiscovery, CocReason reason, List<StructureChange> structureChanges, List<FeeChange> feeChanges)` (all optional/nullable; null lists → treat as empty); `CocDraftResponse(...same...)` + `from(CocDraft)` AND a static `empty()` (all-null/empty for the no-draft GET).
- [ ] `CocDraftService` (loan-scoped guard): `get(loanId)` → `drafts.findByLoanId(loanId).orElse(null)`; `save(loanId, req)` → find-or-create by loanId (`new CocDraft(); setLoanId`), set all fields (null list → `new ArrayList<>()`), save.
- [ ] `CocDraftController` `@RequestMapping("/api/loans/{loanId}/coc")`: `@GetMapping("/draft")` → `ApiResponse.ok(service.get(...) is null ? CocDraftResponse.empty() : from(...))`; `@PutMapping("/draft")` (`@Valid CocDraftRequest`) → `ApiResponse.ok(from(saved))`.
- [ ] **IT** `CocDraftControllerIT`: GET before save → 200, empty `structureChanges`/`feeChanges`; PUT a draft with one `StructureChange` + one `FeeChange{section:"A",label:"Origination Fee",currentValue:100,requestedValue:150,reason:"Fee increase",hasInvoice:"No"}` → 200; GET → `$.data.feeChanges[0].requestedValue == 150`, `$.data.structureChanges[0].field` echoes; 2nd PUT (different) → JDBC `count(*) from coc_draft where loan_id=? == 1`; cross-org 404; no token 401.
- [ ] `:app:test --tests "*CocDraftControllerIT"` + `:coc:test` → PASS. Commit `feat(coc): draft save/load (1:1 upsert, jsonb round-trip)`.

## Task 3: Submit + history
- [ ] DTOs: `CocSubmitRequest(@NotNull CocReason reason, LocalDate dateOfDiscovery, List<StructureChange> structureChanges, List<FeeChange> feeChanges)`; `CocHistoryEntryResponse(UUID id, LocalDate dateOfDiscovery, CocReason reason, List<StructureChange> structureChanges, List<FeeChange> feeChanges, CocStatus status, Instant submittedAt, String submittedBy, String decisionBy, Instant decisionDate)` + `from`.
- [ ] `CocService` (inject draft repo + history repo + `LoanService` + `LoanAccessGuard` + `TenantContext` + `CurrentUser`): 
  - `submit(loanId, req)`: guard; build a `CocHistoryEntry` (status PENDING, fields from req, null lists → empty, `submittedBy = currentUser.id().orElse(null)`, `submittedAt = Instant.now()`); `history.save(entry)`; **delete the saved draft** (`drafts.findByLoanId(loanId).ifPresent(drafts::delete)`); return entry. (reason is `@NotNull` at the DTO → a missing reason 400s before this runs.)
  - `history(loanId)`: guard; `findByLoanIdOrderBySubmittedAtDesc`.
- [ ] `CocController` `@RequestMapping("/api/loans/{loanId}/coc")`: `@PostMapping("/submit")` (`@Valid CocSubmitRequest`) → 201 `ApiResponse.ok(from(entry))`; `@GetMapping("/history")` → list.
- [ ] **IT** `CocSubmitIT`: `POST /coc/submit` with NO reason (`{"structureChanges":[],"feeChanges":[]}`) → **400**, `$.code=="VALIDATION_ERROR"`, `$.fields.reason` exists; with `reason:"BORROWER_REQUESTED"` → 201, `$.data.status=="PENDING"`, `$.data.submittedBy` == caller; `GET /coc/history` → length 1; **`GET /coc/draft` after submit → empty** (assert the draft was cleared); cross-org 404; no token 401.
- [ ] `:app:test --tests "*CocSubmitIT"` → PASS. Commit `feat(coc): submit → PENDING history entry (clears draft; reason @NotNull→fields.reason)`.

## Task 4: Decision workflow (role + state gated)
- [ ] DTO `DecisionRequest(@NotNull CocDecision decision)`.
- [ ] `CocService.decide(loanId, entryId, decision, Set<String> authorities)`: guard (`assertCanAccess`); **role-gate** — `if (!authorities.contains(Role.UNDERWRITER.authority()) && !authorities.contains(Role.ADMIN.authority())) throw new ForbiddenException("Decision requires UNDERWRITER");`; load entry `history.findByIdAndOrgId(entryId, org()).filter(e -> e.getLoanId().equals(loanId)).orElseThrow(new NotFoundException("CoC entry", entryId))`; **state-gate** — `if (entry.getStatus() != CocStatus.PENDING) throw new ConflictException("CoC entry already " + entry.getStatus());`; set `status = decision==ACCEPT ? ACCEPTED : DENIED`, `decisionBy = currentUser.id().orElse(null)`, `decisionDate = Instant.now()`; return entry.
- [ ] `CocController` `@PostMapping("/history/{entryId}/decision")` (`@Valid DecisionRequest`): `accessGuard` via service; `service.decide(loanId, entryId, req.decision(), currentUser.roles())` → `ApiResponse.ok(from(entry))`. (Inject `CurrentUser` into the controller for `roles()`, OR have the service read it — keep roles passed from the controller like `LoanController.transition`.)
- [ ] **IT** `CocDecisionIT`: submit a PENDING entry (LO); `POST …/decision {"decision":"ACCEPT"}` as **LO** → **403**; as **UNDERWRITER** → 200, `$.data.status=="ACCEPTED"`, `$.data.decisionBy` non-null, `$.data.decisionDate` non-null; a 2nd `decision` on it (underwriter) → **409**; decision on a random `entryId` → 404; cross-org → 404; no token → 401.
- [ ] `:app:test --tests "*CocDecisionIT"` → PASS. Commit `feat(coc): underwriter ACCEPT/DENY decision (role + PENDING-state gated)`.

## Task 5: RLS IT + full build + finish
- [ ] `CocRlsIT` — copy `AssetsLiabilitiesRlsIT`; fresh orgs `…00c5`/`…00c6` (distinct from all others); seed a loan per org; insert one `coc_draft` + one `coc_history_entry` (ORG_X) under `app_user`+GUC (read V12 NOT-NULL cols incl. `status` for the entry, the jsonb cols default `'[]'`); assert ORG_Y → 0 / ORG_X → ≥1 / fail-closed on `coc_draft`.
- [ ] `:app:test --tests "*CocRlsIT" --tests "*OpenApiDocsIT"` → PASS, then FULL `./gradlew build` → SUCCESSFUL (report total test count).
- [ ] Commit `test(coc): RLS coverage for coc_draft + coc_history_entry`. Update `docs/frontend-integration.md`/`ROADMAP.md`. Then **superpowers:finishing-a-development-branch**.

## Self-Review
Loan-scoped guard everywhere; jsonb change arrays round-trip; submit clears draft + reason `@NotNull`→`fields.reason`;
decision role-gated (UNDERWRITER/ADMIN→403 else) + state-gated (PENDING→409 else); RLS both tables; `CocDecision`
(not `Decision`) avoids springdoc collision; `OpenApiDocsIT` green; V12 sequential; additive new module. ✓
