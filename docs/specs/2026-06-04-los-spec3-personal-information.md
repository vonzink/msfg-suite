# MSFG LOS — Spec 3: Personal Information & PII

> The 1003's **Personal Information** section — the first real borrower-data capture, and where the
> **NPI encryption built in Spec 1 finally goes live**. Builds on the multi-tenant base (Spec 2): all
> new entities are tenant-scoped, all loads are org-safe.

## Context
Captures everything on UWM EASE's Personal Information page for each borrower: identity (names, **SSN**,
**DOB**, marital status, dependents, citizenship, veteran), contact (phones, email), and **address
history** (present/previous/mailing + 4506-C tax-filing addresses). `BorrowerParty` already exists (Spec 1,
now `extends TenantScopedEntity`); this fills it in and adds two tables. SSN is the first field to use the
`EncryptedStringConverter` — this validates the Spec-1 NPI pipeline end to end.

## Locked decisions
| Area | Decision |
|---|---|
| Scope | **Full Personal Information page** incl. address history + 4506-C tax addresses |
| SSN/DOB | **SSN AES-256-GCM encrypted at rest** (`EncryptedStringConverter`), **masked by default** (`ssnLast4`), full value only via an **audited reveal** endpoint; **DOB stored as a `LocalDate`** |
| API | **Granular** — extend borrower add/PATCH with PII; **addresses as a typed CRUD sub-resource** |
| Tenancy | New entities **extend `TenantScopedEntity`** (auto `org_id` + RLS); **loads use `findByIdAndOrgId`** (Spec-2 rule: `@TenantId` does not filter find-by-PK) |
| Deferred | Alternate names; "Same As" address copy; DOB masking (kept as a returned date) |

## Data model

### `BorrowerParty` (extend — `parties`)
Add columns (org_id/loanId/primary/ordinal/firstName/lastName already present):
- **Identity:** `middleName`, `suffix`, **`ssn` 🔒** (`@Convert(EncryptedStringConverter.class)`),
  `dateOfBirth` (`LocalDate`), `maritalStatus` (enum `MARRIED/SEPARATED/UNMARRIED`),
  `dependentsCount` (Integer), `dependentAges` (String — free text per the wireframe),
  `citizenshipType` (enum `US_CITIZEN/PERMANENT_RESIDENT_ALIEN/NON_PERMANENT_RESIDENT_ALIEN/FOREIGN_NATIONAL`, ULAD),
  `veteran` (boolean), `unmarriedAddendumSpousalRights` (Boolean), `joinedToBorrowerId` (UUID, nullable — co-borrower link).
- **Contact:** `homePhone`, `cellPhone`, `workPhone`, `workPhoneExt`, `email`, `noEmail` (boolean).

### `BorrowerAddress` (new — `parties`, **extends `TenantScopedEntity`**)
`borrowerId` (FK) · `addressType` (enum **PRESENT/PREVIOUS/MAILING/TAX_FILING_CURRENT/TAX_FILING_PREVIOUS**) ·
`ordinal` (orders multiple PREVIOUS) · `addressLine1` · `addressLine2` (unit #) · `city` ·
`state` (`UsStateCode` enum) · `postalCode` · `country` (default "US"). Residency fields (PRESENT/PREVIOUS
only, else null): `ownershipType` (enum `OWN/RENT/LIVING_RENT_FREE`), `residencyDurationYears`,
`residencyDurationMonths`, `rentAmount` (BigDecimal), `rentVerified` (boolean).

### `PiiAccessLog` (new — `platform`, **extends `TenantScopedEntity`**)
Records every NPI reveal (reusable across future NPI fields): `subjectType` (e.g. "BORROWER") · `subjectId`
(UUID) · `field` (e.g. "SSN") · `reason` (nullable). Who/when come from the audit columns (`createdBy` =
accessor, `createdAt` = timestamp). A `PiiAccessRecorder` service (platform) writes these.

### `UsStateCode` (new enum — `platform`, reusable)
50 states + DC + US territories (2-char ULAD codes). Used by addresses now; REO/subject-property later.

## SSN / NPI handling (the headline)
- `ssn` carries `@Convert(EncryptedStringConverter.class)` → ciphertext at rest. **First live use of the
  Spec-1 cipher** (proves the converter + `NpiCipher` bean wiring against real Postgres).
- **Input:** SSN normalized to 9 digits + format-validated; stored encrypted.
- **Output:** `BorrowerResponse` exposes only `ssnLast4` (+ a `•••-••-1234` display) — **never the full SSN**.
- **Reveal:** `POST /api/loans/{loanId}/borrowers/{borrowerId}/reveal-ssn` `{reason}` → returns the full
  `123-45-6789` **and writes one `PiiAccessLog` row** (loan-authorized; org-stamped).
- **DOB** stored as `LocalDate`, returned to authorized users (enables age calc).

## API (extends the borrower endpoints; all tenant + loan scoped)
- `POST/PATCH /api/loans/{loanId}/borrowers[/{id}]` — add/update extended with all PII + contact fields.
- `GET …/borrowers[/{id}]` — `BorrowerResponse` now includes PII (**SSN masked**, DOB shown).
- `POST …/borrowers/{id}/reveal-ssn` `{reason}` — full SSN + audit.
- `POST/GET/PATCH/DELETE …/borrowers/{id}/addresses[/{addressId}]` — typed address CRUD.

## Validation
SSN (9 digits, reject 000/666/9xx area + all-zero groups, normalize) · DOB (past, year ≥ 1900) ·
phone (10-digit US) · email (when `!noEmail`) · ZIP (5 or 9) · `state` via enum. PII stays **optional** at
this stage (early data entry; required-for-submission enforced later).

## Testing
- **Crown jewel — "SSN is ciphertext at rest":** create a borrower with an SSN (in tenant context), then
  read the raw `ssn` column via JDBC (superuser → RLS-bypassed, sees the row) and assert it is **NOT** the
  plaintext → proves the NPI pipeline works end-to-end against real Postgres.
- Round-trip: SSN decrypts correctly on read; `ssnLast4` masking; reveal returns plaintext **and** writes
  exactly one `PiiAccessLog` row; masked everywhere else.
- Address CRUD + typing; **tenant+loan-scoped access** (cross-org → 404 via `findByIdAndOrgId`, cross-loan → 403).
- Validation (SSN/DOB/phone/email/ZIP → 400). Unit: SSN normalization, masking, DOB, `UsStateCode`.
- MockMvc API contracts. All Spec-1/2 tests stay green.

## Migration `V6`
- `ALTER borrower_party ADD COLUMN …` (the PII + contact columns; `org_id` already present).
- `CREATE TABLE borrower_address (… org_id …)` + index + FK to `borrower_party`; **enable RLS FORCE +
  `WITH CHECK` `tenant_isolation` policy** (consistent with Spec-2 tenant tables).
- `CREATE TABLE pii_access_log (… org_id …)` + index; **RLS FORCE + policy**.

## Module placement
- **`parties`:** `BorrowerParty` (extend), `BorrowerAddress` (entity/repo/service/controller/DTOs), the
  reveal-ssn endpoint + masking in `BorrowerResponse`.
- **`platform`:** `PiiAccessLog` (entity/repo) + `PiiAccessRecorder` (reusable), `UsStateCode` enum.
- **`app`:** `V6` migration.

## Out of scope / deferred
Alternate names; "Same As" address copy; DOB masking; required-for-submission validation; income/assets/REO
(later specs).

**Implementation plan:** `docs/superpowers/plans/2026-06-04-los-spec3-personal-information.md` (next).
