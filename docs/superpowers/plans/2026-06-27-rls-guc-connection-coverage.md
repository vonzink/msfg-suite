# Plan — RLS GUC coverage for ALL DB access (option B: connection-level `app.current_org`)

**Created:** 2026-06-27 · **Status:** TODO (start in a fresh session) · **Owner-chosen:** option B (connection-provider), TDD + redeploy.

## The bug (root-caused, with live evidence)
Postgres RLS policy on the tenant tables is **fail-closed**:
`tenant_isolation: org_id = NULLIF(current_setting('app.current_org', true), '')::uuid`
— if the session GUC `app.current_org` is unset, the policy matches **0 rows**.

Prod runs the app as **`app_user` (non-owner)** on `los.msfgco.com` → **RLS is engaged at runtime**. The ONLY place the app sets the GUC is `platform/.../tenancy/TenantRlsAspect.java`, an `@Around` on **`@Transactional`** methods: `select set_config('app.current_org', :org, true)` (transaction-local) from `TenantContextHolder.get()`.

**Gap:** any DB query that runs **outside** a `@Transactional` boundary gets NO GUC → RLS hides rows. Confirmed live (Chrome-MCP, 2026-06-27, borrower `vonzink@gmail.com`, sub `19f979ee…`, org `…00aa`):
- `PUT/GET /api/loans/{id}/application` → **200** (works — `BorrowerApplicationService.get/upsert` do resolve+guard+write inside ONE `@Transactional` method, so the GUC is set for all their queries).
- `GET /api/loans/{id}` summary → **403** and `GET /api/me/loans` → **empty**, even though the borrower IS correctly linked (DB owner-view: 4 `borrower_party` rows with `user_id=sub`, `org_id=…00aa`). These fail because the borrower/agent **linkage guard checks run OUTSIDE a transaction**: `LoanController.get` calls `accessGuard.assertReadable(loan)` at the **controller** layer (after the `@Transactional` `service.get`), and `assertReadable → loanLinkageResolver.isBorrowerOnLoan → BorrowerRepository.existsByLoanIdAndUserId` then executes on an auto-commit connection with no GUC → RLS → false. Same for `/me/loans`'s `findLoanIdsByUserId`.
- **Staff are unaffected** (the staff branch `isStaffOrOwningLo` is role/loanOfficerId-based — no `borrower_party`/`loan_agent` query), which is why this stayed latent until the first real borrower-portal read.

Pre-existing (predates the Stage-2 borrower-self work). NOT the submit bug (that was a wrong-deploy-box issue, fixed 2026-06-27, commit `3a2b7e4` + redeploy to `52.2.71.106`).

## Fix (option B — durable, covers every path)
Set the GUC at **connection acquisition** so EVERY statement (transactional or not) is tenant-scoped.

Recommended: a Hibernate **`MultiTenantConnectionProvider`** + **`CurrentTenantIdentifierResolver`** (resolver returns `TenantContextHolder.get()` as the tenant id, or a sentinel when null):
- `getConnection(tenantId)`: `try (var s = c.createStatement()) { s.execute("select set_config('app.current_org', '<tenantId-or-empty>', false)"); }` (session-level `false` — persists on the pooled connection for the whole checkout).
- `releaseConnection(tenantId, c)`: **RESET** before returning to the pool — `s.execute("reset app.current_org")` (or `set_config(..., '', false)`) — ⚠️ MANDATORY with Hikari pooling, else a returned connection leaks a stale org to the next request → cross-tenant exposure.
- Wire via `spring.jpa.properties.hibernate.multiTenancy=DISCRIMINATOR` won't apply here (we already use `@TenantId` discriminator at the app layer); instead register the providers as beans and set `hibernate.multi_tenant_connection_provider` / `hibernate.tenant_identifier_resolver`. Validate the combination with `@TenantId` (the app-layer discriminator) — they must coexist (app-layer filter + DB RLS belt-and-suspenders). If Hibernate forbids combining `@TenantId` with connection-multitenancy, fall back to a **DataSource proxy** (wrap `getConnection()` to set the GUC from `TenantContextHolder`, reset on `close()`), which is provider-agnostic and avoids Hibernate's multitenancy mode entirely.
- Keep or retire `TenantRlsAspect`: once the connection-level GUC covers everything, the aspect is redundant; leave it as defense-in-depth or remove after the ITs prove coverage. Don't run BOTH set mechanisms with conflicting scopes (`true` vs `false`) without checking precedence.

## Tests (TDD — the failing case first)
Existing RLS ITs (`app/src/test/java/com/msfg/los/**/*RlsIT.java`: Reo, Aus, AssetsLiabilities, UserAccount, VerificationRequest, LoanAgent) set the GUC MANUALLY via `set_config` per op — they prove the policy, not the app wiring. Add a NEW IT that does NOT set the GUC manually and instead relies on `TenantContextHolder` + the connection provider, asserting:
1. A linked BORROWER reads `GET /api/loans/{id}` (summary) → 200, `GET /api/me/loans` → their loan present (the currently-failing case — RED first).
2. The non-transactional guard path (`assertReadable`/`isBorrowerOnLoan`) returns true for a linked borrower under `app_user`.
3. **Connection-reuse isolation**: two sequential requests with different orgs on the same pooled connection don't leak (org A can't read org B) — pin the release-reset.
4. Existing RLS ITs stay green; cross-tenant denial still holds.
Run as the non-owner role to actually exercise RLS (the ITs' Testcontainers Postgres + the app_user role).

## Deploy
`EC2_HOST=ubuntu@52.2.71.106 ./deploy-suite.sh` (script default now points there). No new Flyway migration. Re-verify live: a fresh funnel borrower → `/me/loans` shows their loan + `GET /loans/{id}` 200.

## Cleanup (separate)
Test data created during the 2026-06-27 live test (org `…00aa`), safe to delete: loans `b656f863`(LN 1000000003), `23daa7ad`(1000000005), `cc3773db`, `351e1e41` + their leads/borrower rows (all linked to sub `19f979ee…`).
