# MSFG LOS — Change of Circumstance Module (frontend §2)

> Per-loan Change-of-Circumstance: an editable **draft**, **submit** into a **history** of entries, and an
> underwriter **ACCEPT/DENY** decision workflow. UI shipped behind `LocalCocAdapter`; mirrors
> `msfg-suite-web/src/features/coc/cocModel.ts`. New **`coc`** module (deps `:platform`, `:loan-core`),
> loan-scoped, migration **V12**.

## Context
A loan has one editable `CocDraft` (date of discovery, reason, structure changes, fee changes) and a list of
`CocHistoryEntry` snapshots (each PENDING → ACCEPTED/DENIED). The change arrays are stored as **`jsonb`**
(`@JdbcTypeCode(SqlTypes.JSON)` — same pattern as `tenancy/.../Organization.settings`). Reason is required on
submit → **400 with `fields.reason`** via bean validation. No money computation (fee values are stored, not summed).

## Locked decisions
| Area | Decision |
|---|---|
| Module | New **`coc`** (deps platform, loan-core); loan-scoped |
| Change arrays | **`jsonb` columns** via `@JdbcTypeCode(SqlTypes.JSON)` over `List<record>` (mirror `Organization`) |
| Draft | 1:1 per loan, `GET`/`PUT` upsert; GET-before-save → 200 empty (not 404) |
| Submit | **Body + clear draft:** `POST /coc/submit` takes the draft content, creates a **PENDING** history entry, then **deletes the saved draft**. `reason` `@NotNull` → 400 `fields.reason` |
| Decision | `POST /coc/history/{entryId}/decision {decision: ACCEPT\|DENY}` — **UNDERWRITER/ADMIN-gated** (403 else); only on a PENDING entry (409 else). Enum named **`CocDecision`** (avoid bare `Decision` springdoc collision) |
| Tenancy | entities extend `TenantScopedEntity`; loads via `findByIdAndOrgId`; RLS on both tables |
| Deferred | `feeChange.hasInvoice` → real Document Manager binding (kept as a String now); tolerance/CD/redisclosure-clock logic |

## Data model (module `coc`, package `com.msfg.los.coc`)

### Enums
- `CocReason` { BORROWER_REQUESTED, SETTLEMENT_CHARGES, RATE_LOCK_EXTENSION, ELIGIBILITY, CLERICAL, OTHER } (mirrors `REASONS`).
- `CocStatus` { PENDING, ACCEPTED, DENIED }.
- `CocDecision` { ACCEPT, DENY } (request enum; ACCEPT→ACCEPTED, DENY→DENIED).

### JSON element records (not entities)
- `StructureChange(String field, String label, String currentValue, String requestedValue)`.
- `FeeChange(String section, String label, BigDecimal currentValue, BigDecimal requestedValue, String reason, String hasInvoice)`.

### `CocDraft` (new — **extends `TenantScopedEntity`**, 1:1 per loan)
`loanId` · `dateOfDiscovery` (LocalDate, nullable) · `reason` (`@Enumerated(STRING) CocReason`, nullable) ·
`structureChanges` (`@JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition="jsonb") List<StructureChange>`) ·
`feeChanges` (jsonb `List<FeeChange>`). **Unique `(org_id, loan_id)`.** (Init lists to empty.)

### `CocHistoryEntry` (new — **extends `TenantScopedEntity`**, many per loan)
`loanId` · `dateOfDiscovery` · `reason` (`CocReason`) · `structureChanges` (jsonb) · `feeChanges` (jsonb) ·
`status` (`@Enumerated(STRING) CocStatus`, not null) · `submittedAt` (Instant) · `submittedBy` (String) ·
`decisionBy` (String, nullable) · `decisionDate` (Instant, nullable).

## API (loan-scoped; cross-org → 404, no token → 401)
- `GET /api/loans/{loanId}/coc/draft` → the draft (or an empty `CocDraftResponse`, 200) · `PUT
  /api/loans/{loanId}/coc/draft` `{dateOfDiscovery?, reason?, structureChanges[], feeChanges[]}` → upsert the 1:1
  draft, 200.
- `POST /api/loans/{loanId}/coc/submit` `{@NotNull reason, dateOfDiscovery?, structureChanges[], feeChanges[]}` →
  create a PENDING `CocHistoryEntry` (`submittedBy` = principal, `submittedAt` = now), **delete the saved draft**,
  return the entry (201). Missing `reason` → **400 `VALIDATION_ERROR`** with `fields.reason`.
- `GET /api/loans/{loanId}/coc/history` → entries newest-first (by `submittedAt` desc).
- `POST /api/loans/{loanId}/coc/history/{entryId}/decision` `{@NotNull CocDecision decision}` → if caller lacks
  `ROLE_UNDERWRITER`/`ROLE_ADMIN` → **403**; if the entry is not PENDING → **409**; else set
  `status` (ACCEPTED/DENIED) + `decisionBy` (principal) + `decisionDate` (now), return the entry.

## Validation
`reason` `@NotNull` on the submit + (drafts allow null reason). `decision` `@NotNull`. `dateOfDiscovery` past-or-
present optional. Fee/structure change records are stored as-is (no per-field rule; `currentValue`/`requestedValue`
BigDecimal ≥ 0 on fee changes is a soft nicety — skip unless trivial).

## Testing
- **Draft round-trip:** GET before save → 200 empty (`structureChanges`/`feeChanges` empty arrays); PUT a draft with
  a `StructureChange` + a `FeeChange` → 200; GET → the **jsonb arrays round-trip** (assert a nested field, e.g.
  `$.data.feeChanges[0].requestedValue`); 2nd PUT replaces (1 row — JDBC `count(*) where loan_id=? == 1`).
- **Submit — crown jewel:** `POST /coc/submit` with NO `reason` → **400**, `$.code == "VALIDATION_ERROR"`,
  `$.fields.reason` present; with a reason → 201, status PENDING, `submittedBy` = caller; `GET /coc/history` →
  length 1, that entry; **`GET /coc/draft` after submit → empty** (draft cleared).
- **Decision workflow:** submit (PENDING); decision `ACCEPT` as **LO** → **403**; as **UNDERWRITER** → 200,
  status ACCEPTED, `decisionBy` = the underwriter, `decisionDate` non-null; a 2nd decision on the now-ACCEPTED
  entry → **409**; decision on a non-existent entryId → 404; cross-org → 404.
- **RLS:** `coc_draft` + `coc_history_entry` covered by the Spec-2 `RlsIT` pattern.
- **`OpenApiDocsIT` green:** the `List<StructureChange>`/`List<FeeChange>` in the API + the new enums/records have
  unique simple names (`CocDecision`, not `Decision`); `jsonb`-mapped lists model as arrays in the spec.

## Migration `V12__coc.sql`
- `CREATE TABLE coc_draft (… org_id …, loan_id uuid not null, date_of_discovery date, reason varchar(40),
  structure_changes jsonb not null default '[]'::jsonb, fee_changes jsonb not null default '[]'::jsonb, … audit)`;
  `unique (org_id, loan_id)`; index `(org_id, loan_id)`; RLS FORCE + `tenant_isolation` + grant app_user CRUD.
- `CREATE TABLE coc_history_entry (… org_id …, loan_id uuid not null, date_of_discovery date, reason varchar(40),
  structure_changes jsonb not null default '[]'::jsonb, fee_changes jsonb not null default '[]'::jsonb, status
  varchar(20) not null, submitted_at timestamp(6) with time zone, submitted_by varchar(120), decision_by
  varchar(120), decision_date timestamp(6) with time zone, … audit)`; index `(org_id, loan_id)`; RLS + grants.

## Module placement
- **`coc` (new):** enums, `StructureChange`/`FeeChange` records, `CocDraft`/`CocHistoryEntry` entities; repos;
  `CocDraftService`, `CocService` (submit + history + decision); controllers + DTOs.
- **`app`:** `include("coc")` + dep; `V12` migration.

## Out of scope / deferred
The TRID fee-tolerance / re-disclosure-clock engine; CD generation; `hasInvoice` → real document binding;
notifications on submit/decision.

**Implementation plan:** `docs/superpowers/plans/2026-06-09-coc-module.md` (next).
