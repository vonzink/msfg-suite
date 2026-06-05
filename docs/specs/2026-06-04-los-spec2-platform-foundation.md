# MSFG LOS — Spec 2: Platform Foundation (Multi-tenancy + Portability seams)

> Lands **before** the 1003 sections, while the schema is tiny (loan / borrower_party /
> loan_status_history) — cheap to retrofit now, a costly rebuild later. Makes the system a
> **multi-tenant SaaS** (many lender companies, small→large) and establishes the swappable-adapter
> seams. MSFG is tenant #1.

## Context
Spec 1 built a single-tenant core. We're turning it into a platform that serves many companies, each
with isolated data, users, config, and (later) integrations + AI keys. The two foundational decisions:
**shared DB + `org_id` on every row + Postgres RLS** (two isolation layers), and **everything external
behind ports** so the backend is a cloud-agnostic Docker image (Cognito becomes a swappable auth adapter).

## Locked decisions
| Area | Decision |
|---|---|
| Tenancy model | Shared DB; **`org_id` on every domain row**; app-level Hibernate filter **+ Postgres RLS** (both, this spec) |
| User ↔ company | **One company per user** (single `org_id` JWT claim); multi-org deferrable without rework |
| Portability | All external services behind **ports**; Cognito = current auth adapter; Docker-anywhere |
| Admin | A **`PLATFORM_ADMIN`** role (above company `ADMIN`) provisions/manages companies |
| Config | Per-company `settings` (JSONB) now; encrypted per-company **secret store** deferred to when AI/integrations land |

---

## Architecture

### New module: `tenancy` (depends on `platform`)
`Organization` aggregate + admin. Holds the company registry + provisioning. `app` depends on it;
`loan-core`/`parties` do **not** (they only need the `org_id` primitive from `platform`).

### Tenant primitives (in `platform`)
- **`TenantScopedEntity`** (abstract, extends `AuditableEntity`): adds `@Column(name="org_id", nullable=false, updatable=false) UUID orgId`. `Loan`, `BorrowerParty`, `LoanStatusHistory` switch to extend it.
- **`TenantContext`** (sibling to `CurrentUser`): `UUID orgId()` from the JWT `org_id` claim (claim name configurable, default `org_id`); `boolean isPlatformAdmin()`.
- **`@FilterDef`/`@Filter` `tenantFilter`** (`org_id = :orgId`) on `TenantScopedEntity` — auto-scopes every SELECT.
- A **request hook** that, per request/transaction: enables `tenantFilter` with `TenantContext.orgId()` **and** executes `SET LOCAL app.current_org = '<orgId>'` (for RLS). Platform-admin/system scope bypasses (no filter) for cross-tenant org management.

### Two isolation layers
1. **App layer (primary correctness):** Hibernate `tenantFilter` on reads; `orgId` stamped on writes from `TenantContext` (via `@PrePersist` / service). A loan in another org is simply **not returned** → `get()` → 404.
2. **DB layer (defense-in-depth):** **Postgres RLS** policy per tenant-data table:
   `USING (org_id = current_setting('app.current_org', true)::uuid)`.
   ⚠️ **Gotcha (must handle):** RLS is bypassed for superusers and table owners → use
   **`ALTER TABLE … FORCE ROW LEVEL SECURITY`** so it applies to the app's owner role (and the
   Testcontainers superuser). Unset session var → comparison is NULL → **deny-all** (fail-closed).
   The `organization` table is **not** tenant-RLS'd (it's the registry; guarded by `PLATFORM_ADMIN` at the app layer).

### Identity / auth (one company per user)
- JWT carries an **`org_id`** claim (Cognito custom attribute; claim name configurable for the port).
- `PLATFORM_ADMIN` role → manage companies + cross-tenant; ordinary company roles (`ADMIN/LO/…`) are org-scoped.
- **Cognito stays the adapter** behind the existing JWT resource-server config (issuer/claims configurable).
- **Local-dev**: `LocalDevSecurityConfig` dev principal gets `org_id = DEFAULT_ORG` (+ `PLATFORM_ADMIN`), so `bootRun` works.

### Ports convention
Establish the `ports` package + the **auth seam** (validate-any-RS256-JWT + map claims → principal+org;
Cognito is the issuer config). Storage / AI / email / payments / webhook ports are defined **with their
features** — no empty abstractions now (YAGNI).

## Data model

- **`Organization`** (`tenancy`): `id`, `name`, `slug` (unique), `status` (ACTIVE/SUSPENDED), `settings` (JSONB), audit + `@Version`.
- **`org_id`** added to `loan`, `borrower_party`, `loan_status_history` (and every future domain table).

## API
| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/api/admin/organizations` | `PLATFORM_ADMIN` | Create a company `{name, slug}` |
| GET | `/api/admin/organizations` | `PLATFORM_ADMIN` | List companies |
| GET | `/api/admin/organizations/{id}` | `PLATFORM_ADMIN` | Get one |
| PATCH | `/api/admin/organizations/{id}` | `PLATFORM_ADMIN` | Update `{name, status, settings}` |

Existing loan/borrower endpoints: **no signature change**, but now **auto-scoped to the caller's company**
(pipeline + access naturally filter by `org_id`; cross-org access → 404).

## Migration `V3`
1. `create table organization (…)`; seed a fixed **DEFAULT_ORG** (MSFG, ACTIVE).
2. For `loan`, `borrower_party`, `loan_status_history`: `add column org_id uuid` → `update … set org_id = DEFAULT_ORG` → `alter … set not null` + FK → `org_id` → `organization(id)` + index.
3. RLS: per tenant-data table `enable row level security` + **`force row level security`** + policy
   `using (org_id = current_setting('app.current_org', true)::uuid)`.

## Testing (crown jewels)
- **Cross-tenant isolation (app):** org-A user creates a loan; org-B user's pipeline omits it and `GET /{A-id}` → 404; borrowers likewise.
- **RLS (DB):** a native query with `app.current_org` set to org-B returns only org-B rows though org-A rows exist; **unset → returns nothing** (fail-closed). Proves FORCE-RLS applies to the app role.
- `org_id` auto-stamped on create = caller's org.
- `PLATFORM_ADMIN` can create/list orgs; a plain company `ADMIN` → 403 on `/api/admin/organizations`.
- Local-dev default org boots + serves (bootRun smoke).
- **All Spec-1 tests still green** (test JWT helpers updated to include an `org_id` claim).

## Module changes
- **New `tenancy`** module (Organization + admin).
- **`platform`**: `TenantScopedEntity`, `TenantContext`, `tenantFilter`, RLS/transaction hook, tenant request filter.
- **`loan-core`/`parties`**: entities extend `TenantScopedEntity`; minimal service change (filter is automatic); access guards rely on org-scoping + existing owner checks.
- **`app`**: wire `tenancy`, the RLS hook, `PLATFORM_ADMIN` mapping, default-org seed; update `LocalDevSecurityConfig` + `AbstractIntegrationTest` JWT helpers to carry `org_id`.

## Out of scope (deferred)
- Encrypted per-company **secret store** (AI keys / integration creds) → with the AI/integrations specs.
- Storage / AI / email / payments / webhook **adapters** → with their features.
- Multi-org users, per-tenant subdomain routing, tenant onboarding UI/branding.
- Promoting a large tenant to a dedicated schema/DB (the model allows it; not built now).

**Implementation plan:** `docs/superpowers/plans/2026-06-04-los-spec2-platform-foundation.md` (next).
