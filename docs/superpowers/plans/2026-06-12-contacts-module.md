# Contacts Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.
> Spec: `docs/specs/2026-06-12-contacts-module.md`. Migration **V16**. Mirror module: **`reo`** (read it before writing anything).

**Goal:** loan-scoped contacts CRUD (`role/name/company/phone/email`) — the last frontend work-order item.

**Architecture:** new `contacts` module cloned structurally from `reo` (domain/repo/service/web/dto), ordinal max+1 from day one, standard tenancy stack.

**Tech Stack:** Java 21 · Spring Boot 3.3 · Flyway V16 · Testcontainers ITs (Docker required) · `./gradlew`.

**⚠️ Worktree protocol:** `git worktree add ~/.config/superpowers/worktrees/msfg-suite/contacts -b feat/contacts main`; ALL work there; NEVER checkout in the shared repo.

### Task 1: Module + V16 + entity/repo/service/controller (TDD via IT)
**Files:** `settings.gradle.kts` (+"contacts") · `contacts/build.gradle.kts` (copy reo's) · `app/build.gradle.kts` (+project dep) · `app/src/main/resources/db/migration/V16__contacts.sql` · `contacts/src/main/java/com/msfg/los/contacts/{domain/ContactRole.java,domain/Contact.java,repo/ContactRepository.java,service/ContactService.java,web/ContactController.java,web/dto/{ContactResponse,CreateContactRequest,UpdateContactRequest}.java}` · Test `app/src/test/java/com/msfg/los/contacts/web/ContactControllerIT.java`

- [ ] V16 `contact` table: id/org_id (NOT NULL, `REFERENCES organization(id)`)/loan_id (NOT NULL)/role varchar(40) NOT NULL/name varchar(200) NOT NULL/company varchar(200)/phone varchar(40)/email varchar(200)/ordinal int NOT NULL DEFAULT 0/version/created_at/updated_at/created_by/updated_by (V15 phrasing) + `idx_contact_org_loan (org_id, loan_id)` + the V15-verbatim RLS block + app_user grants.
- [ ] Entity extends `TenantScopedEntity` (`@Enumerated(STRING) ContactRole role`); repo: `List<Contact> findByLoanIdOrderByOrdinalAscIdAsc(UUID)`, `Optional<Contact> findByIdAndOrgId(UUID,UUID)`, `Optional<Contact> findTopByLoanIdOrderByOrdinalDesc(UUID)`.
- [ ] RED ITs first (mirror `ReoControllerIT` + `RoleAccessIT` helpers): createReturns201WithOrdinalZero (echo all 5 fields) · listOrderedByOrdinal (2 rows) · patchSubsetLeavesOthers (PATCH phone only → name/role unchanged) · patchBlankNameReturns400 · deleteThenOrdinalNotReused (add,add,delete-first,add → ordinal 2) · missingRole400 + missingName400 (separate) · crossOrg404 · crossLoanSameOrg404 (create 2 loans same org; PATCH loan A's contact via loan B's path → 404) · platformAdmin403 · processorOrgWide200 (random-subject PROCESSOR lists another LO's contacts) · noToken401.
- [ ] GREEN: `ContactService` cloned from `ReoService` shape — guard every method; `load(loanId, contactId)` = `findByIdAndOrgId` + loanId filter → 404; create = nextOrdinal (max+1); PATCH = provided-field semantics, `name` provided-but-blank → `ValidationException("name must not be blank")`; DTOs: `CreateContactRequest(@NotNull ContactRole role, @NotBlank String name, String company, String phone, String email)`, `UpdateContactRequest(ContactRole role, String name, String company, String phone, String email)`, `ContactResponse(UUID id, ContactRole role, String name, String company, String phone, String email, int ordinal)`. Controller mirrors `ReoController` (201 create, 204 delete, ApiResponse envelope).
- [ ] Run `./gradlew :app:test --tests '*ContactControllerIT' --tests '*OpenApiDocsIT' --console=plain` green. Commit: `feat(contacts): loan-scoped contacts CRUD — module, V16, role enum, ordinal max+1 (TDD)`.

### Task 2: RLS IT + sweep + full build
- [ ] `app/src/test/java/com/msfg/los/contacts/ContactsRlsIT.java` mirroring `AusRlsIT` (fresh orgs, SET ROLE app_user, GUC isolation + fail-closed + WITH CHECK) for the `contact` table.
- [ ] Simple-name sweep: `ContactRole ContactResponse CreateContactRequest UpdateContactRequest Contact` — `Contact` the entity vs anything else named Contact repo-wide (grep; the FE's Contact is TS, irrelevant) → expect clean.
- [ ] FULL `./gradlew build --console=plain` → SUCCESSFUL (~385+ tests). Foreign-file failures → STOP and report.
- [ ] Commit: `test(contacts): RLS coverage + name sweep`.

## Post-merge protocol
ff-merge per worktree rules → restart bootRun → verify `/v3/api-docs` markers (`/contacts` paths) → FE handoff dated section (endpoints + role-enum mapping note + gen:api) → frontend-integration.md + CLAUDE.md + ROADMAP + memory.

## Self-Review
Spec coverage: V16/model/API/ordinal/tenancy/validation/tests all mapped to T1-T2 ✓. No placeholders; types defined once ✓. reo analog named everywhere the engineer needs it ✓.
