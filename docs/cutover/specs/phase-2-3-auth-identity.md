# Cutover Phase 2/3 — Auth & Identity (spec)

**Goal:** parity with mortgage-app's auth/identity (see its map), adapted **multi-tenant + staff-only**.
Decisions (Zack): **add a tenant-scoped users table** (materialize-on-first-`/me`) and **add a MANAGER role**.
Migration takes **V20** (V19 was Phase 1 T5). Branch `feat/cutover-phase2-auth`.

## Determined design
- **Role mapping** (new LOS Cognito pool emits the exact `Role` enum names; the allowlist `CognitoRolesConverter`
  already drops anything else): mortgage-app `Admin → ADMIN`, `Manager → MANAGER` (new), `LO → LO`,
  `Processor → PROCESSOR`. msfg-suite's `UNDERWRITER`/`CLOSER` have no mortgage-app source (kept).
  `Borrower`/`RealEstateAgent`/`External` **dropped** (staff-only). Drop the dead `/borrowers/invite` +
  `/agents/assign` matchers (mortgage-app had matchers but no handlers).
- **Principal identity** = Cognito `sub` (a UUID), as today. `loanOfficerId` on a loan already = the LO's `sub`.
  So the users table PK **is** the `sub` — `loanOfficerId` joins straight to `user_account.id`.

## Build tasks (each TDD'd, own commit on `feat/cutover-phase2-auth`)

### T1 — MANAGER role + access + admin-catalog gating fix
- `platform/.../security/Role.java`: add `MANAGER` → `{LO, PROCESSOR, UNDERWRITER, CLOSER, MANAGER, ADMIN, PLATFORM_ADMIN}`.
  (`CognitoRolesConverter` auto-allowlists all enum names — MANAGER becomes accepted automatically.)
- `loan-core/.../service/LoanAccessGuard.java`: add `MANAGER` to `ORG_WIDE_AUTHORITIES` (org-wide loan
  read; same tier as PROCESSOR/UNDERWRITER/CLOSER). MANAGER = full org-wide loan read/write, NOT catalog/platform admin.
  Add a `MANAGER`-aware helper if loan write/delete gating needs it (mortgage-app: Manager in every staff
  write/delete set). Keep PLATFORM_ADMIN excluded from loan files.
- **Admin-catalog gating fix** (Phase 1 flag): in `app/.../config/SecurityConfig`, gate
  `/api/admin/document-types/**` and `/api/admin/folder-templates/**` to **`hasRole("ADMIN")`** (tenant admin,
  matching mortgage-app's Admin-manages-catalogs) — added BEFORE the broad `/api/admin/** → PLATFORM_ADMIN`
  rule (more specific matcher first). Update the Phase-1 catalog ITs that authenticated as `PLATFORM_ADMIN`
  to use `ADMIN`; keep a `PLATFORM_ADMIN`-only test for `/api/admin/organizations`. Mirror the rule in
  `LocalDevSecurityConfig` if it has per-path rules.

### T2 — users table + materialization + GET /me + /me/loans
- **Migration V20** `user_account` (tenant-scoped): `id uuid pk` (= Cognito sub, assigned — NOT generated),
  `org_id uuid not null references organization(id)`, `version`, `email varchar(320) not null`,
  `name varchar(255)`, `initials varchar(10)`, `role varchar(40)`, audit cols. `unique(org_id, email)`.
  FORCE RLS (`tenant_isolation`, the V13 form) + `grant … to app_user`. (No FK from loan.loan_officer_id —
  it stays a bare sub UUID; the join is logical.)
- **`UserAccount` entity** (new module `identity`, OR in `parties`/`platform` — prefer a small new `identity`
  module to keep boundaries clean; mirror an existing module's build.gradle). Assigned `@Id` (sub). org_id +
  audit via the tenancy base, but with an ASSIGNED id (not generated) — set id = sub on materialize.
- **`UserAccountService.resolveOrCreate()`** (materialize-on-first-call): from `CurrentUser` read sub/email/
  name/roles. Lookup by `findByIdAndOrgId(sub, org)` then `findByOrgIdAndEmail(org, email)` (⚠️ load-by-PK
  does NOT honor `@TenantId` — use `findByIdAndOrgId`). If found: refresh name/role if changed. If not:
  insert id=sub, org_id (from JWT claim), email (synthesize `sub@unknown.local` only if blank), name
  (`name` claim → `given_name`+`family_name` → email), initials (derive), role (primary group by priority
  `ADMIN > MANAGER > UNDERWRITER > CLOSER > PROCESSOR > LO`, lowercased or enum). Tenant-scoped throughout.
- **`GET /api/me`** (`MeController`): `resolveOrCreate`, return `ApiResponse<MeResponse>` —
  `{id, email, name, initials, role, orgId, roles[]}`. 401 if unauthenticated (existing entry point).
- **`GET /api/me/loans`**: role-scoped loan list reusing `LoanAccessGuard.hasOrgWideView` (ADMIN/MANAGER/
  PROCESSOR/UNDERWRITER/CLOSER → all org loans; LO → `loanOfficerId == sub`). Return the slim pipeline DTO
  (reuse `LoanListItemResponse` or a `MeLoanItem`), enriched with assigned-LO name via a batched resolver
  over `user_account` (mirror `PrimaryBorrowerNameResolver`) — optional if cheap, else defer.
- ITs: `/me` materializes once + is idempotent (second call no dup); `/me` projects JWT claims + persisted
  role; `/me/loans` returns all for an org-wide role and only-own for an LO; tenant isolation (org A's
  user_account invisible to org B); RLS IT for `user_account`.

### T3 — auth port (M1)
- `platform/.../security/PrincipalPort.java` (interface): `Optional<String> id()`, `email()`,
  `Optional<String> name()`, `Optional<UUID> orgId()`, `Set<String> roles()` — provider-neutral.
- `app/.../config/JwtPrincipalAdapter` (or platform if no Spring-web dep issue): the Cognito/JWT impl,
  reading `sub`/`email`/`name`/`org_id`/authorities from the SecurityContext, with the Cognito claim names
  centralized here. `CurrentUser` delegates to `PrincipalPort` (keeps its existing API for callers).
- Update **CLAUDE.md**: auth IS now port-backed (the M1 doc note flips from "not yet a port" to "principal
  port + Cognito adapter; SecurityConfig + converters remain the Cognito wiring"). Keep behavior identical
  (org_id fail-closed + role allowlist unchanged).

### T4 — verify + merge
Full `./gradlew clean build` green (RLS/OpenAPI/ArchUnit incl.), then merge `feat/cutover-phase2-auth` → main,
push, checkpoint with Zack. Update PARITY-CHECKLIST Auth/Identity items → ✅.

## Staff-only drops (do NOT build): Borrower/RealEstateAgent roles, borrower/agent self-scoping
(`borrowers.user_id`, `loan_agents`), invite/assign endpoints, borrower-visible filters.
