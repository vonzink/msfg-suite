# Phase F — Identity & Access Foundation (msfg-suite)

**Date:** 2026-06-19
**Status:** 📋 Plan — awaiting owner sign-off (no code until approved)
**Spec:** `docs/superpowers/specs/2026-06-18-unified-integration-architecture-design.md` (§7, §13–14)
**Built via:** multi-agent workflow (5 code readers → synthesis → 3 adversarial review lenses). The
security lens REJECTED the naive draft (privilege-escalation + NPI leak); this plan is the restructure.

---

## Goal

Extend msfg-suite so the **mortgage-app frontend can call suite directly with a BORROWER or
REAL_ESTATE_AGENT Cognito token and see only their own loans** — without weakening the existing
staff/tenant model. This is the serial foundation that unblocks all parallel integration work.

## Decisions baked in

- **D-link (borrower):** verified-email auto-link **+** staff override. Stamp `borrower_party.user_id`
  on first `/me` **only if** `email_verified=true` AND exactly one in-org borrower matches AND no
  existing link; never overwrite; `>1` match → stamp none. Plus a staff-gated manual assign/override.
- **D-agent:** build the full agent path — `loan_agent` table + guard + a **staff-gated assign
  endpoint** + a **limited, NPI-free** agent read surface (assigned-loan status/summary only).

## The access model (the critical restructure)

The draft widened `LoanAccessGuard.assertCanAccess` — the shared chokepoint for ~40 read **and write**
call sites (income, financials, parties, conditions, notes, reveal-SSN). That would let a borrower
modify the loan and read a co-borrower's SSN/income, and an agent read all borrower NPI. Replaced with:

1. **Deny-by-default at `SecurityConfig`.** Borrower/agent authorities match ONLY a small allowlist of
   **read** endpoints. Everything else under `/api/**` requires staff authorities.
   - Borrower allowlist: `GET /api/me`, `GET /api/me/loans`, `GET /api/loans/{id}` (summary),
     `GET /api/loans/{id}/status/transitions` (read), borrower-visible documents.
   - Agent allowlist: `GET /api/me/loans`, `GET /api/loans/{id}` (status/summary only). **No documents,
     no NPI.**
   - Explicitly denied for borrower/agent: all writes (POST/PATCH/DELETE), `reveal-ssn`, income,
     financials, declarations/demographics, fees, conditions/notes writes.
2. **`assertCanAccess` stays a READ predicate** enforcing the per-loan link on the allowed endpoints; a
   new **`assertCanModify`** (staff/LO only; borrower/agent never pass) guards writes — defense in depth
   behind the matcher.
3. **The per-loan link is the ONLY borrower/agent isolation.** Within the single MSFG tenant `org_id` is
   constant → RLS/`@TenantId` give zero separation between borrowers. Every borrower/agent read relies on
   the fail-closed link check; linkage queries carry an explicit `org_id` predicate and re-load the loan
   via `findByIdAndOrgIdAndDeletedAtIsNull` (cross-tenant → **404**, not 403).
4. **LO branch gated on `ROLE_LO`** (not a bare `sub == loanOfficerId`, which a borrower could collide
   with). Branch order is exhaustive: org-wide(staff) → LO → borrower → agent → **deny** (no fall-through).
5. **Cross-module seam via ports** (mirror `PrimaryBorrowerNameResolver`): `LoanLinkageResolver` iface in
   loan-core + a `parties` adapter; a `BorrowerUserLinker` seam for the identity→parties stamp. loan-core
   and identity never import `parties` (ArchUnit-enforced).

## org_id acceptance — no code change

`OrgScopedJwtAuthenticationConverter` already fail-closes on missing/invalid `org_id`;
`JwtPrincipalAdapter` parses it. The Phase C pre-token Lambda emits a **constant MSFG `org_id`** for
borrower/agent tokens; `LocalDevSecurityConfig` sets a dev org; ITs supply it via the existing
`jwt()` authority/claim post-processor. Phase F adds tests, not converter code.

## Data model — single migration V24 (re-verify head is V23 before authoring)

- `borrower_party` ADD `user_id uuid NULL` + index `(org_id, user_id)`. Nullable keeps co-borrowers valid.
- CREATE `loan_agent` (`id, org_id, loan_id, user_id, agent_role varchar NOT NULL DEFAULT 'BUYERS_AGENT',
  ordinal, version, created/updated audit`), UNIQUE `(org_id, loan_id, user_id)`, indexes, **FORCE RLS +
  WITH CHECK + grants to `app_user`** (template from V16/V20). `agent_role` in SCREAMING_SNAKE
  (`BUYERS_AGENT, LISTING_AGENT, DUAL_AGENT`) to match the `ContactRole` convention.
- `user_id` is the Cognito `sub` (no FK; cross-service identity).

## Tasks (TDD — tests first; additive only; Flyway is a strict ordered sequence)

**Pre-flight:** confirm migration head is `V23`; run existing ArchUnit (`ModuleBoundaryTest`) to pin
allowed dependency directions before T4/T5; confirm the pool's exact group strings (`Borrower`,
`RealEstateAgent`) for T1.

| # | Task | Deps | Tests-first (RED) | Acceptance |
|---|---|---|---|---|
| **T1** | Roles + converter **alias map**: add `BORROWER`,`REAL_ESTATE_AGENT` to `Role`; map the pool's real group strings to enum authorities (`Borrower→BORROWER`, `RealEstateAgent→REAL_ESTATE_AGENT`, reconcile `Admin/Manager/Processor` casing). New roles NOT in `ORG_WIDE_AUTHORITIES`. | — | Each real pool group → correct authority; unknown dropped; casing handled by alias | `CognitoRolesConverterTest` green; allowlist fail-closed |
| **T2** | **V24** migration (above) | — | RLS fail-closed under `app_user`; schema reaches V24 | applies clean; no existing migration edited |
| **T3** | `LoanAgent` entity/repo/service (loan-core): `isAgentOnLoan`, `loanIdsForAgent` — explicit `org_id` predicate | T2 | match-only true; two-org no cross-match; org-scoped load | green; org-scoped |
| **T4** | `LoanLinkageResolver` port (loan-core, UUID-only sig) + `parties` adapter + `BorrowerParty.userId` mapping/queries | T2 | same-org match only; diff-org/null → none; ArchUnit passes | no loan-core→parties import; green |
| **T5** | Borrower link materialization (**verified-email auto-link + staff override**) via `BorrowerUserLinker` seam; add new roles to `ROLE_PRIORITY` (lowest) | T1,T4 | email_verified=false→no stamp; 0-match→none; 1-match→stamp; >1→none; pre-existing→unchanged; cross-org→unchanged; **takeover (unverified/attacker email)→denied**; staff assign sets link; borrower/agent cannot assign (403) | `/me` 200 + role; staff unchanged; identity does not import parties |
| **T6** | Access model: `SecurityConfig` deny-by-default borrower/agent **read allowlist**; `assertCanAccess` read-predicate + new `assertCanModify`; LO branch gated on `ROLE_LO`; exhaustive branch | T3,T4 | borrower/agent 403 on writes, reveal-ssn, income, financials, declarations; allowed reads 200 only when linked; borrower `sub==loanOfficerId` still denied write; PLATFORM_ADMIN denied; staff/LO regression | `RoleAccessIT` extended; prior cases green |
| **T7** | Role-aware `/me/loans`: additive pipeline overload `notDeleted().and(idIn(linkedIds))` (does NOT compose `callerScope`); branch org-wide→LO→borrower→agent→empty | T3,T4 | borrower/agent see only their ids; **empty set→zero loans**; soft-deleted absent; cross-org id omitted; pagination; staff/LO unchanged (`MeIT`) | additive; `MeResponse` shape unchanged |
| **T8** | Agent staff-assign endpoint (`POST /api/loans/{id}/agents`, staff-gated) + **NPI-free** agent read surface | T3 | staff assigns; agent sees assigned summary/status only; agent denied SSN/income/financials/docs (403); agent cannot assign; non-assigned agent 404 | green; agent ceiling enforced |
| **T9** | Contract pin + CORS: `@Operation(operationId=…)` on LoanController + MeController; add `app.msfgco.com`+`los.msfgco.com` to `los.cors.allowed-origins` | — | operationIds stable (`listLoans`,`getMyLoans`,…); both origins echo, non-listed don't, no wildcard; per-profile | `OpenApiDocsIT`+`CorsIT` green |
| **T10** | Security E2E ITs (IDOR + cross-tenant + NPI ceiling); seed links via JDBC | T5,T6,T7,T8 | linked 200; unlinked 403 envelope; `/me/loans` scoped; **cross-tenant 404 + omitted**; borrower can't read co-borrower SSN/income; agent can't read any NPI; staff/LO/PLATFORM_ADMIN regression | flat error envelope asserted; **full build green** |

## Borrower read surface (Phase F scope)

Loan **summary** (may show co-borrower *names* — acceptable on a joint app), **status timeline**, and the
borrower's **own/visible documents + conditions**. Reading own income/asset/declaration *detail* is a
later additive whitelist extension (deferred). **Never** co-borrower SSN/financial NPI.

## Risks (carried from the review)

- Widening the shared guard = privilege escalation → mitigated by the SecurityConfig deny-by-default + read/modify split (T6).
- Email auto-link = takeover → mitigated by `email_verified` + single-match + override (T5).
- ArchUnit boundary (loan-core/identity → parties) → ports only (T4/T5).
- `callerScope` is binary → explicit id-set spec + empty-set test (T7).
- Within MSFG org, `org_id` is not a per-borrower boundary → per-loan link is the sole isolation; org-scoped queries + reload-by-id (T6/T10).
- springdoc duplicate-simple-name 500 → `LoanAgent` name verified unique; grep any new web type.
- operationId churn breaks the FE client once → coordinate one FE regen post-merge (T9).
- V24 only, not parallel; suite migrations single-threaded through this conductor.

## Deferred / open

- Borrower own-NPI read sections (income/assets/declarations) — later additive whitelist.
- Borrower invite/claim-token flow — not needed given verified-email + staff override.
- Agent portal UX richness — Phase A (frontend).
- Cross-system contract: the Phase C Lambda must emit `org_id` + the exact groups `Borrower`/`RealEstateAgent` — pin in the deploy seam doc.
