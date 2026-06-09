# MSFG LOS — Spec 7: Declarations + Demographic Information (HMDA)

> The **final two 1003 sections** — URLA §5 (Declarations: per-borrower legal/financial yes-no questions) and
> §8 (Demographic Information: HMDA ethnicity/race/sex). Both **1:1 per borrower** (not grids). **This completes
> the full 1003.**

## Context
Each borrower has one `BorrowerDeclarations` row and one `BorrowerDemographics` row. New **`declarations` module**
(deps `:platform`,`:loan-core`,`:parties`), borrower-scoped (mirrors `BorrowerAddressService`'s
`assertBorrowerInLoan`). Multi-select fields (bankruptcy types, ethnicity, race) are stored as a **converted
`Set<enum>` ↔ comma-delimited `String`** column on the parent row (JPA `AttributeConverter`) — no join tables, so
the data stays on the tenant-scoped parent row (org_id + RLS cover it). No column-encryption (HMDA data is
sensitive but protected by tenant + loan/borrower scoping + auth, consistent with the rest of the system).

## Locked decisions
| Area | Decision |
|---|---|
| Scope | URLA **§5 Declarations** + **§8 Demographic Information (HMDA)**, 1:1 per borrower |
| Module | New **`declarations`** module (deps platform/loan-core/parties); borrower-scoped |
| Multi-selects | **`Set<enum>` via `AttributeConverter`** (comma-joined names) on the parent row — NO `@ElementCollection`/join tables |
| API | **1:1 PUT-upsert** per borrower: `GET`/`PUT …/declarations` and `…/demographics` (GET returns current or empty; PUT upserts the single row) |
| Tenancy | Entities **extend `TenantScopedEntity`** (org_id + RLS); loads use `findByIdAndOrgId`/`findByBorrowerId` (`@TenantId`-filtered); unique `(org_id, borrower_id)` enforces 1:1 |
| Crypto | None |
| Deferred | Per-question explanatory text/attachments; conditional UI validation; MISMO/HMDA-LAR export |

## Data model (module `declarations`, package `com.msfg.los.declarations`)

### `BorrowerDeclarations` (new — **extends `TenantScopedEntity`**, 1:1 borrower)
`loanId` · `borrowerId` (unique per org). **Nullable `Boolean`** (yes/no/unanswered) for each §5 question:
`occupyAsPrimaryResidence`, `hadOwnershipInterestLast3Years`, `familyOrBusinessAffiliationWithSeller`,
`borrowingUndisclosedMoney`, `applyingForOtherMortgageOnProperty`, `applyingForNewCreditBeforeClosing`,
`subjectToPriorityLienPace`, `coSignerOrGuarantorOnUndisclosedDebt`, `outstandingJudgments`,
`delinquentOrDefaultOnFederalDebt`, `partyToLawsuit`, `conveyedTitleInLieuLast7Years`,
`completedPreForeclosureShortSaleLast7Years`, `propertyForeclosedLast7Years`, `declaredBankruptcyLast7Years`.
Follow-ups: `priorPropertyUsage` (enum `com.msfg.los.loan.domain.OccupancyType`, nullable),
`priorPropertyTitleType` (enum `PriorPropertyTitleType`, nullable),
`bankruptcyTypes` (**`Set<BankruptcyType>`** via `BankruptcyTypeSetConverter`).

### `BorrowerDemographics` (new — **extends `TenantScopedEntity`**, 1:1 borrower)
`loanId` · `borrowerId` (unique per org). `ethnicity` (**`Set<Ethnicity>`** via converter) · `race`
(**`Set<Race>`** via converter) · `sex` (enum `Sex`) · `collectedByVisualObservationOrSurname` (Boolean) ·
`applicationTakenMethod` (enum `ApplicationTakenMethod`).

### Enums (module `declarations`)
- `PriorPropertyTitleType` { SOLE, JOINT_WITH_SPOUSE, JOINT_WITH_OTHER }
- `BankruptcyType` { CHAPTER_7, CHAPTER_11, CHAPTER_12, CHAPTER_13 }
- `Ethnicity` { HISPANIC_OR_LATINO, MEXICAN, PUERTO_RICAN, CUBAN, OTHER_HISPANIC_OR_LATINO,
  NOT_HISPANIC_OR_LATINO, DO_NOT_WISH_TO_PROVIDE }
- `Race` { AMERICAN_INDIAN_OR_ALASKA_NATIVE, ASIAN, ASIAN_INDIAN, CHINESE, FILIPINO, JAPANESE, KOREAN,
  VIETNAMESE, OTHER_ASIAN, BLACK_OR_AFRICAN_AMERICAN, NATIVE_HAWAIIAN_OR_PACIFIC_ISLANDER, NATIVE_HAWAIIAN,
  GUAMANIAN_OR_CHAMORRO, SAMOAN, OTHER_PACIFIC_ISLANDER, WHITE, DO_NOT_WISH_TO_PROVIDE }
- `Sex` { MALE, FEMALE, DO_NOT_WISH_TO_PROVIDE }
- `ApplicationTakenMethod` { FACE_TO_FACE, MAIL, TELEPHONE, INTERNET }
- `OccupancyType` reused from `loan-core` for `priorPropertyUsage`.

### `EnumSetConverter<E>` (reusable base — module `declarations`)
`abstract class EnumSetConverter<E extends Enum<E>> implements AttributeConverter<Set<E>, String>`:
`convertToDatabaseColumn` = null/empty → null, else comma-joined `Enum::name`; `convertToEntityAttribute` =
null/blank → empty `LinkedHashSet`, else split on `,` + `Enum.valueOf`. Concrete `@Converter` subclasses:
`BankruptcyTypeSetConverter`, `EthnicitySetConverter`, `RaceSetConverter` (each passes its enum class).

## API (borrower-scoped, 1:1 PUT-upsert)
- `GET /api/loans/{loanId}/borrowers/{borrowerId}/declarations` → current row, or an all-null/empty
  `DeclarationsResponse` if none exists yet (200, not 404).
- `PUT /api/loans/{loanId}/borrowers/{borrowerId}/declarations` `{...all fields...}` → upsert (create if absent,
  else replace), 200, returns the saved row.
- `GET`/`PUT /api/loans/{loanId}/borrowers/{borrowerId}/demographics` — same upsert semantics.
- All `assertBorrowerInLoan(loanId, borrowerId)` first (cross-org → 404, no token → 401).

## Validation
Light — these are mostly free-form answers. The **multi-select converter** round-trips cleanly; reject unknown
enum values at deserialization (Spring returns 400 for a bad enum in the JSON body). `bankruptcyTypes` non-empty
only meaningful when `declaredBankruptcyLast7Years == true` — capture as-is (no hard cross-field rule; optional).

## Testing
- **Converter round-trip (unit + IT):** PUT demographics with `ethnicity=[HISPANIC_OR_LATINO, MEXICAN]`,
  `race=[ASIAN, CHINESE, WHITE]` → GET returns the same sets (order preserved); the DB column holds the
  comma-joined string (assert via JDBC `select ethnicity from borrower_demographics` = `"HISPANIC_OR_LATINO,MEXICAN"`).
- **Upsert:** GET before any PUT → 200 empty; PUT → 200; second PUT (different values) → replaces (one row, not two —
  assert via `count(*) where borrower_id=?` = 1).
- Declarations booleans round-trip (yes/no/null distinct); `bankruptcyTypes` set round-trip.
- Tenant/loan scope (cross-org 404, no-token 401); **RLS** for `borrower_declarations` + `borrower_demographics`
  (mirror the Spec-2 `RlsIT` pattern).
- Unit: `EnumSetConverter` (null/empty/multi round-trip; unknown value handling).

## Migration `V10__declarations_hmda.sql`
- `CREATE TABLE borrower_declarations (… org_id …, borrower_id uuid not null references borrower_party(id), loan_id uuid not null, <15 boolean cols>, prior_property_usage varchar(40), prior_property_title_type varchar(40), bankruptcy_types varchar(60), …audit)`; **unique `(org_id, borrower_id)`**; index `(org_id, loan_id)`; RLS FORCE + tenant_isolation + grants.
- `CREATE TABLE borrower_demographics (… org_id …, borrower_id uuid not null references borrower_party(id), loan_id uuid not null, ethnicity varchar(255), race varchar(512), sex varchar(30), collected_by_visual_observation_or_surname boolean, application_taken_method varchar(30), …audit)`; **unique `(org_id, borrower_id)`**; RLS + grants. (Multi-selects are plain `varchar` — the converter joins them.)

## Module placement
- **`declarations` (new):** entities + enums + `EnumSetConverter` + 3 concrete converters; repos;
  `DeclarationsService` + `DemographicsService` (borrower-scoped upsert); controllers + DTOs.
- **`app`:** `include("declarations")` + dep; `V10` migration.

## Out of scope / deferred
Per-question explanations/attachments; conditional/required-for-submission validation; HMDA-LAR / MISMO export;
demographic data-retention policy automation.

→ **With this, the full 1003 (URLA) is complete** — Personal Info, Employment & Income, Assets & Liabilities,
Loan Information, REO, the qualification calc engine, Declarations, and HMDA demographics.

**Implementation plan:** `docs/superpowers/plans/2026-06-05-los-spec7-declarations-hmda.md` (next).
