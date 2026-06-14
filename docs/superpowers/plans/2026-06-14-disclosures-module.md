# Disclosures Module (TRID LE/CD) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.
> Spec: `docs/specs/2026-06-14-disclosures-module.md` (read its **Verified regulatory floor** first — the ★ corrections are binding). Migration **V17**. Money- AND compliance-critical → opus pass before merge (orchestrated by the controller after Task 13).

**Goal:** TRID Loan Estimate + Closing Disclosure — issue/track LE & CD with an in-house business-day timing engine, tolerance bucketing, and a 3-trigger reset detector, while delegating APR computation + regulated-form rendering to a stub-first `DisclosureVendorPort`.

**Architecture:** new `disclosures` module (deps `:platform :loan-core :fees :coc :documents :qualification`). We own: `BusinessDayCalculator` (crown jewel), deadline/reset logic, `TolerancePolicy`, coverage gate, the issuance/audit persistence. We delegate: APR/finance-charge/TIP + H-24/H-25 rendering behind `DisclosureVendorPort` (deterministic stub). Append-only `disclosure_event` audit mirrors `lock_event`/`aus_run`.

**Tech Stack:** Java 21 · Spring Boot 3.3 · Flyway V17 · JUnit5/AssertJ/MockMvc/Testcontainers (Docker required) · `./gradlew` from the worktree root.

**⚠️ Worktree protocol (NEVER `git checkout`/migrate in the shared `/Users/zacharyzink/MSFG/msfg-suite`):**
```bash
cd /Users/zacharyzink/MSFG/msfg-suite
git worktree add ~/.config/superpowers/worktrees/msfg-suite/disclosures -b feat/disclosures main
cd ~/.config/superpowers/worktrees/msfg-suite/disclosures   # ALL work here
```
**META for every implementer:** commit as soon as green, BEFORE composing your final report; keep reports value-free (no secret-like literals). Use `./gradlew` from the worktree root. Docker is running.

**Shared type contracts (single source of truth — later tasks use these EXACTLY):**
- Enums (`disclosures/src/main/java/com/msfg/los/disclosures/domain/`): `DisclosureKind {LOAN_ESTIMATE, CLOSING_DISCLOSURE}` · `DisclosureStatus {PENDING, SENT, RECEIVED, ERROR}` · `DeliveryMethod {IN_PERSON, MAIL, EMAIL, COURIER}` · `ReceivedBasis {ACTUAL, CONSTRUCTIVE_PLUS_3}` · `ToleranceBucket {ZERO, TEN_PERCENT, UNLIMITED}` · `PaidTo {CREDITOR, AFFILIATE, UNAFFILIATED, GOVERNMENT}` · `ResetReason {APR_INACCURATE, PRODUCT_CHANGED, PREPAYMENT_PENALTY_ADDED}` · `BusinessDayType {GENERAL, PRECISE}` · `DisclosureEventType {LE_ISSUED, CD_ISSUED, REVISED_LE_ISSUED, RECEIPT_RECORDED, RESET_TRIGGERED, REVISED_LE_DEADLINE_SET, TOLERANCE_CHECK, COVERAGE_EVALUATED}`.
- Timing (`disclosures/.../timing/`): `record GeneralBusinessDayConfig(Set<DayOfWeek> openDays)` with `static GeneralBusinessDayConfig DEFAULT = new GeneralBusinessDayConfig(EnumSet.of(MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY))`. `BusinessDayCalculator` (Task 4 API).
- jsonb records (`domain/`): `DisclosureCostRow(String section, String label, BigDecimal amount, ToleranceBucket bucket)` · `CashToCloseRow(String label, BigDecimal amount)` · `DisclosureSnapshot(List<DisclosureCostRow> costRows, List<CashToCloseRow> cashToClose, BigDecimal loanAmount, BigDecimal interestRate, Integer termMonths)`.
- Port records (`service/`): Task 6.

---

### Task 1: Scaffold + enums + DocumentType.{LOAN_ESTIMATE,CLOSING_DISCLOSURE}
**Files:** `settings.gradle.kts` (+`"disclosures"`) · `disclosures/build.gradle.kts` · `app/build.gradle.kts` (+`implementation(project(":disclosures"))`) · `documents/.../domain/DocumentType.java` (add `LOAN_ESTIMATE,` and `CLOSING_DISCLOSURE,` on the lines before `OTHER` — additive, never reorder) · the 9 enums above (one file each, `com.msfg.los.disclosures.domain`).
- [ ] `disclosures/build.gradle.kts`: copy `aus/build.gradle.kts` verbatim, then ensure deps are exactly `:platform :loan-core :fees :coc :documents :qualification` (+ the web/data-jpa/validation starters aus has). Each enum is a plain `public enum`.
- [ ] `./gradlew :disclosures:compileJava :app:compileJava :documents:compileJava --console=plain` → SUCCESS.
- [ ] Commit: `chore(disclosures): scaffold module + enums + DocumentType LE/CD`.

### Task 2: Migration V17
**Files:** Create `app/src/main/resources/db/migration/V17__disclosures.sql`
- [ ] Read `V15__aus_credit.sql` for the exact RLS/grant/audit-column phrasing + `V16__contacts.sql`. Confirm no V17 exists. Content:
```sql
-- V17: Disclosures (TRID LE/CD) — disclosure_issuance, disclosure_event + additive fee/loan columns

CREATE TABLE disclosure_issuance (
    id                         uuid PRIMARY KEY,
    org_id                     uuid NOT NULL REFERENCES organization(id),
    loan_id                    uuid NOT NULL,
    kind                       varchar(20) NOT NULL,   -- LOAN_ESTIMATE | CLOSING_DISCLOSURE
    version                    int NOT NULL DEFAULT 1,
    status                     varchar(20) NOT NULL,    -- PENDING|SENT|RECEIVED|ERROR
    apr                        numeric(9,5),
    finance_charge             numeric(15,2),
    amount_financed            numeric(15,2),
    total_of_payments          numeric(15,2),
    tip                        numeric(9,5),
    apr_irregular_basis        boolean NOT NULL DEFAULT false,
    prepayment_penalty         boolean NOT NULL DEFAULT false,
    product_description        varchar(120),
    delivery_method            varchar(20),             -- IN_PERSON|MAIL|EMAIL|COURIER
    delivered_at               timestamptz,
    received_at                timestamptz,
    received_basis             varchar(24),             -- ACTUAL|CONSTRUCTIVE_PLUS_3
    computed_received_date     date,
    earliest_consummation_date date,
    document_id                uuid,
    vendor_reference           varchar(120),
    snapshot                   jsonb NOT NULL DEFAULT '{}',
    trigger_coc_id             uuid,
    reset_triggered            boolean NOT NULL DEFAULT false,
    reset_reasons              jsonb NOT NULL DEFAULT '[]',
    requested_by               varchar(120),
    requested_at               timestamptz,
    error_message              varchar(1000),
    created_by                 varchar(120),
    updated_by                 varchar(120),
    version_lock               bigint NOT NULL DEFAULT 0,  -- NOTE: optimistic @Version col; name to match entity @Column
    created_at                 timestamptz NOT NULL DEFAULT now(),
    updated_at                 timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_disclosure_issuance_loan ON disclosure_issuance (org_id, loan_id, kind, version);

CREATE TABLE disclosure_event (
    id            uuid PRIMARY KEY,
    org_id        uuid NOT NULL REFERENCES organization(id),
    loan_id       uuid NOT NULL,
    disclosure_id uuid,
    event_type    varchar(40) NOT NULL,
    detail        jsonb NOT NULL DEFAULT '{}',
    actor         varchar(120),
    occurred_at   timestamptz NOT NULL DEFAULT now(),
    created_by    varchar(120),
    updated_by    varchar(120),
    version_lock  bigint NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_disclosure_event_loan ON disclosure_event (org_id, loan_id, occurred_at);

ALTER TABLE fee_line_item ADD COLUMN paid_to varchar(40);
ALTER TABLE fee_line_item ADD COLUMN consumer_can_shop boolean;
ALTER TABLE fee_line_item ADD COLUMN on_written_list boolean;

ALTER TABLE loan ADD COLUMN consummation_date date;
```
Then append a V15-style RLS block for `disclosure_issuance` (full CRUD grant to app_user) and for `disclosure_event` (**SELECT, INSERT only** — append-only, mirror `lock_event`'s grant). ENABLE+FORCE RLS, `tenant_isolation` policy USING+WITH CHECK `org_id = nullif(current_setting('app.current_org', true), '')::uuid`. *(The ALTERed `fee_line_item`/`loan` already have RLS — adding columns doesn't change it.)*

⚠️ **Audit-column check (learned in AUS V15):** Spring entities extend `TenantScopedEntity`→`AuditableEntity` which map `created_by`/`updated_by` (and the `@Version` column). The V15-family tables include them. Both new tables above already declare `created_by`/`updated_by`. For the `@Version` column, name it to match whatever `BaseEntity`/`AuditableEntity` expects — **read `pricing/.../RateLock.java` + its table in `V14` to confirm the exact version column name** (it is likely `version`, not `version_lock`; if so, rename in this migration before running). Boot the context to validate.
- [ ] `./gradlew :app:test --tests '*OpenApiDocsIT' --console=plain` (applies V1..V17 in a fresh Testcontainer + validates entity mapping once entities exist — for now just confirm the migration applies: it will run at context boot). Expect SUCCESSFUL. If a column-name mismatch surfaces in Task 3, fix it here.
- [ ] Commit: `feat(disclosures): V17 — disclosure_issuance + disclosure_event (RLS, append-only audit) + fee/loan columns`.

### Task 3: Entities + repositories + jsonb records + fee/loan field wiring
**Files:** `disclosures/.../domain/{DisclosureIssuance,DisclosureEvent}.java` + the 3 jsonb records · `disclosures/.../repo/{DisclosureIssuanceRepository,DisclosureEventRepository}.java` · **Modify** `fees/.../domain/FeeLineItem.java` (+3 nullable fields), `fees/.../web/dto/{AddFeeRequest,UpdateFeeRequest,FeeLineItemResponse}.java` (additive optional fields), `fees/.../service/FeeService.java` (carry them on add/upsert/patch) · **Modify** `loan-core/.../domain/Loan.java` (+`LocalDate consummationDate`), `loan-core/.../web/dto/{UpdateLoanRequest,LoanSummaryResponse}.java` (additive), `loan-core/.../service/LoanService.update` (set when non-null).
- [ ] Read analogs: `pricing/.../domain/RateLock.java` (TenantScopedEntity + @Version usage), `coc/.../domain/CocDraft.java` (`@JdbcTypeCode(SqlTypes.JSON)`), `aus/.../domain/AusRun.java` (enum STRING + jsonb List). Entities extend `TenantScopedEntity`; enums `@Enumerated(STRING)`; jsonb fields `@JdbcTypeCode(SqlTypes.JSON)` with column names matching V17 (`snapshot`, `reset_reasons`). Do NOT redeclare inherited id/orgId/version/audit columns.
- [ ] `DisclosureSnapshot` stored as the `snapshot` jsonb; `reset_reasons` as `@JdbcTypeCode(SqlTypes.JSON) List<ResetReason>`.
- [ ] Repos (extend `JpaRepository<X,UUID>`):
```java
// DisclosureIssuanceRepository
List<DisclosureIssuance> findByLoanIdOrderByRequestedAtDescIdDesc(UUID loanId);
Optional<DisclosureIssuance> findByIdAndOrgId(UUID id, UUID orgId);
Optional<DisclosureIssuance> findTopByLoanIdAndKindOrderByVersionDesc(UUID loanId, DisclosureKind kind);
// DisclosureEventRepository
List<DisclosureEvent> findByLoanIdOrderByOccurredAtDescIdDesc(UUID loanId);
```
- [ ] Fee additive fields: `FeeLineItem` gains `@Enumerated(STRING) PaidTo paidTo` (nullable), `Boolean consumerCanShop`, `Boolean onWrittenList` (import `PaidTo` from `com.msfg.los.disclosures.domain` — fees must dep `:disclosures`? NO, that inverts the dep. **Put `PaidTo` enum in fees' own domain OR store `paid_to` as a String on FeeLineItem and map to `PaidTo` inside disclosures' `TolerancePolicy`.**). **Decision: store `paidTo` as `String` on `FeeLineItem`** (no cross-module enum dep); `TolerancePolicy` (Task 5, in disclosures) parses it. `AddFeeRequest`/`UpdateFeeRequest`/`FeeLineItemResponse` gain `String paidTo, Boolean consumerCanShop, Boolean onWrittenList` (all optional/nullable — additive, FE ignores). `FeeService` add/upsert/patch carry them (patch = provided-field-wins; null = leave).
- [ ] `Loan` gains `LocalDate consummationDate`; `UpdateLoanRequest` + `LoanSummaryResponse` gain it (additive); `LoanService.update` sets it when the request field is non-null (mirror the other PATCH fields).
- [ ] `./gradlew :disclosures:compileJava :fees:compileJava :loan-core:compileJava --console=plain` then `:app:test --tests '*OpenApiDocsIT'` → green (validates mappings vs V17). Commit: `feat(disclosures): entities/repos + additive fee tolerance-fact + loan consummationDate fields`.

### Task 4: BusinessDayCalculator (CROWN JEWEL — pure, TDD, edge-case table)
**Files:** `disclosures/.../timing/BusinessDayCalculator.java` · Test `disclosures/src/test/java/com/msfg/los/disclosures/timing/BusinessDayCalculatorTest.java`
**API (final):**
```java
@Component
public class BusinessDayCalculator {
    /** Federal legal holidays per 5 USC 6103(a), with observed-date shifting (Sat→prior Fri, Sun→following Mon). */
    public boolean isFederalHoliday(LocalDate d) { ... }
    /** PRECISE (1026.2(a)(6)): every calendar day except Sundays + observed federal holidays (Saturdays COUNT).
        GENERAL: a day in cfg.openDays() that is not an observed federal holiday. */
    public boolean isBusinessDay(LocalDate d, BusinessDayType type, GeneralBusinessDayConfig cfg) { ... }
    /** The n-th business day STRICTLY AFTER `from` (count starts the day after `from`). n>=0; n==0 returns `from`. */
    public LocalDate addBusinessDays(LocalDate from, int n, BusinessDayType type, GeneralBusinessDayConfig cfg) { ... }
    /** Count of business days in (a, b]; 0 if b<=a. */
    public int businessDaysBetween(LocalDate a, LocalDate b, BusinessDayType type, GeneralBusinessDayConfig cfg) { ... }
}
```
- [ ] **Step 1 (RED):** write the test table. Federal-holiday assertions for **2026** (observed dates): New Year `2026-01-01`(Thu); MLK `2026-01-19`(Mon); Washington `2026-02-16`(Mon); Memorial `2026-05-25`(Mon); Juneteenth `2026-06-19`(Fri); Independence observed `2026-07-03`(Fri, since Jul 4 is Sat) **and** Jul 4 itself is NOT a business day either (it's a Saturday→already non-precise? NO, Saturdays count in PRECISE — but the *observed* holiday is Jul 3; assert `isFederalHoliday(2026-07-03)==true` and `isFederalHoliday(2026-07-04)==false`); Labor `2026-09-07`(Mon); Columbus `2026-10-12`(Mon); Veterans `2026-11-11`(Wed); Thanksgiving `2026-11-26`(Thu); Christmas `2026-12-25`(Fri). PRECISE/GENERAL day classification: `isBusinessDay(2026-06-20 /*Sat*/, PRECISE, DEFAULT)==true`, `(..., GENERAL, DEFAULT)==false`; `isBusinessDay(2026-06-21 /*Sun*/, PRECISE)==false`; `isBusinessDay(2026-06-19 /*Juneteenth Fri*/, PRECISE)==false`. **addBusinessDays worked examples (verify the convention against the CFPB "Loan Estimate"/"Closing Disclosure" timeline examples via WebSearch, and adjust ALL of these + the convention if CFPB counts differently — document the source):**
  - `addBusinessDays(2026-06-15 /*Mon*/, 3, PRECISE, DEFAULT)` → `2026-06-18` (Tue16,Wed17,Thu18 — no holiday/Sunday).
  - `addBusinessDays(2026-06-17 /*Wed*/, 3, PRECISE, DEFAULT)` → `2026-06-22` (Thu18; Fri19 Juneteenth skip; **Sat20 counts**; Sun21 skip; Mon22).
  - `addBusinessDays(2026-06-15 /*Mon*/, 3, GENERAL, DEFAULT)` → `2026-06-18` (Mon–Fri config, no Sat).
  - `addBusinessDays(2026-06-15 /*Mon*/, 7, PRECISE, DEFAULT)` → compute by hand: Tue16(1),Wed17(2),Thu18(3),Fri19 Juneteenth skip,Sat20(4),Sun21 skip,Mon22(5),Tue23(6),Wed24(7) → `2026-06-24`.
  - mailbox +3: `addBusinessDays(2026-06-15, 3, PRECISE, DEFAULT)` == `2026-06-18` (reused — the constructive-receipt rule).
  Run `./gradlew :disclosures:test --tests '*BusinessDayCalculatorTest'` → RED (class absent).
- [ ] **Step 2 (GREEN):** implement. Holiday set computed per year (nth-weekday rules + fixed-date observed shifting). `addBusinessDays` loops day-by-day from `from.plusDays(1)`, counting `isBusinessDay`, until `n` counted. `isFederalHoliday` must apply observed shifting (a fixed-date holiday on Sat is observed the prior Fri; on Sun the following Mon — and the actual Sat/Sun date is then NOT the holiday). **Before finalizing, WebSearch the CFPB TRID timeline worked examples and confirm the count-starts-day-after convention; if CFPB differs, fix the convention + the expected dates and note the source in a comment.**
- [ ] Green; commit: `feat(disclosures): BusinessDayCalculator — both 1026.2(a)(6) defs, federal-holiday set, CFPB-verified counting`.

### Task 5: TolerancePolicy + bucketing (TDD unit)
**Files:** `disclosures/.../tolerance/TolerancePolicy.java` · `disclosures/.../tolerance/ToleranceComparison.java` (record) · Test `disclosures/src/test/.../tolerance/TolerancePolicyTest.java`
- [ ] **Step 1 (RED):** unit test for `ToleranceBucket bucket(String feeSection, String paidTo, Boolean consumerCanShop, Boolean onWrittenList)` per the verified rules (spec §tolerance):
  - section A (origination) or `paidTo` CREDITOR/AFFILIATE → `ZERO`; transfer taxes (section E "Transfer Taxes" label) → `ZERO`; non-shoppable third-party (`consumerCanShop==false`, UNAFFILIATED) → `ZERO`.
  - recording fees (section E "Recording Fees") → `TEN_PERCENT`; shoppable + (`onWrittenList==true` OR `consumerCanShop` true but `onWrittenList` null/"didn't shop") → `TEN_PERCENT`.
  - shoppable + chose **off-list** (`consumerCanShop==true && onWrittenList==false`) → `UNLIMITED`; prepaids/escrow (sections F, G) → `UNLIMITED`; property insurance/taxes → `UNLIMITED`.
  Plus `ToleranceComparison compare(List<DisclosureCostRow> baseline, List<DisclosureCostRow> current)` → `record ToleranceComparison(BigDecimal zeroBucketExcess, BigDecimal tenPercentBaselineSum, BigDecimal tenPercentCurrentSum, BigDecimal tenPercentExcess, boolean withinTolerance)`: ZERO = sum over zero-bucket rows of max(0, current−baseline) **per matching (section,label)**; TEN_PERCENT excess = max(0, currentSum − baselineSum×1.10) over the 10% bucket; `withinTolerance = zeroBucketExcess==0 && tenPercentExcess==0`. Assert a hand-computed scenario.
  Run `:disclosures:test --tests '*TolerancePolicyTest'` → RED.
- [ ] **Step 2 (GREEN):** implement (BigDecimal scale 2, HALF_UP; the 1.10 factor exact). Comments cite `1026.19(e)(3)(i)-(iii)` + the retention/off-list rules.
- [ ] Green; commit: `feat(disclosures): TolerancePolicy bucketing + good-faith comparison (zero per-item, 10% cumulative)`.

### Task 6: DisclosureVendorPort + deterministic stub (TDD unit)
**Files:** `disclosures/.../service/` records + `DisclosureVendorPort.java` · `disclosures/.../adapter/StubDisclosureVendorAdapter.java` · Test `disclosures/src/test/.../adapter/StubDisclosureVendorAdapterTest.java`
**Records:**
```java
public interface DisclosureVendorPort {
    DisclosureGenerationResult generate(DisclosureGenerationRequest request);
    DeliveryResult send(DeliveryRequest request);
    DeliveryStatus getStatus(String vendorReference);
    // version-aware UCD (MISMO v3.3.0) — STUB ONLY, deferred:
    UcdExportResult exportUcd(UUID loanId, UUID disclosureId);
}
public record DisclosureGenerationRequest(DisclosureKind kind, UUID loanId, int version, String loanNumber,
    BigDecimal loanAmount, BigDecimal interestRate, Integer termMonths, BigDecimal monthlyPrincipalInterest,
    BigDecimal totalClosingCosts, BigDecimal prepaidFinanceCharges, boolean prepaymentPenalty,
    String productDescription, List<DisclosureCostRow> costTable, List<CashToCloseRow> cashToClose) {}
public record DisclosureGenerationResult(BigDecimal apr, BigDecimal financeCharge, BigDecimal amountFinanced,
    BigDecimal totalOfPayments, BigDecimal tip, boolean aprIrregularBasis,
    byte[] renderedBytes, String renderedContentType, String vendorReference) {}
public record DeliveryRequest(UUID loanId, UUID disclosureId, DisclosureKind kind, DeliveryMethod method) {}
public record DeliveryResult(String vendorReference, ReceivedBasis basis) {}
public record DeliveryStatus(DisclosureStatus status, java.time.LocalDate actualReceiptDate) {}
public record UcdExportResult(String vendorReference, String mismoVersion, byte[] ucdXml) {}
```
Javadoc the port with the real-wire contract: real adapters = DocMagic/IDS/Docutech "generate disclosure package" (MISMO 3.4 in, computed APR/finance-charge + H-24/H-25 PDF out; **creditor remains liable**); `send` requires ESIGN `15 USC 7001(c)` consent evidenced first; `getStatus` returns the e-view/e-sign timestamp that flips basis to ACTUAL; `exportUcd` = **MISMO v3.3.0** UCD for GSE delivery (deferred).
- [ ] **Step 1 (RED):** stub tests — deterministic per (loanId, kind, version): `generate` twice → equal `vendorReference` (prefix `DV-`) + equal `apr`; **APR direction:** higher `prepaidFinanceCharges` → strictly higher `apr` (so reset tests can cross tolerance); `amountFinanced == loanAmount − prepaidFinanceCharges`; `financeCharge`/`totalOfPayments` internally consistent (`totalOfPayments ≈ monthlyPrincipalInterest × termMonths`, `financeCharge ≈ totalOfPayments − amountFinanced`, both ≥ 0); `aprIrregularBasis == false`; `renderedContentType == "text/html"`, bytes contain the loan number + "PLACEHOLDER — not a conforming H-24/H-25"; `send` → `DeliveryResult` basis `CONSTRUCTIVE_PLUS_3`, ref prefix `DV-`; `getStatus` deterministic. Run → RED.
- [ ] **Step 2 (GREEN):** implement `@Component`. Determinism via `new Random(loanId.getMostSignificantBits() ^ kind.ordinal() ^ version)`. `apr = interestRate + prepaidFinanceCharges.divide(max(loanAmount,1), scale10).multiply(100)` rounded scale 5 (a monotone-in-prepaid stub; comment: real APR is Appendix-J actuarial behind the real adapter). Placeholder HTML escaped via `documents` `HtmlText.escape`.
- [ ] Green; commit: `feat(disclosures): DisclosureVendorPort + deterministic stub adapter (APR monotone in prepaids; placeholder form)`.

### Task 7: Coverage gate (TDD via IT)
**Files:** `disclosures/.../service/CoverageService.java` · `disclosures/.../web/CoverageController.java` · `disclosures/.../web/dto/CoverageResponse.java` · Test `app/src/test/java/com/msfg/los/disclosures/web/CoverageIT.java`
- [ ] `record CoverageResponse(boolean covered, List<String> reasons)`. `GET /api/loans/{loanId}/disclosures/coverage`. 5-part gate from `Loan`: closed-end consumer mortgage secured by real property OR co-op, not reverse, not exempt. **Read `Loan` for the available fields** (loanPurpose, mortgageType, property type/occupancy). v1 rule: covered unless the loan is flagged HELOC/reverse/business or non-owner-non-consumer; **co-ops are covered** (don't gate on a real-property check). Since the loan model may lack explicit HELOC/reverse flags, v1 returns `covered=true` with `reasons=["Closed-end consumer mortgage — TRID applies"]` for standard purchase/refi, and `covered=false` with the disqualifying reason if `loanPurpose`/`mortgageType` indicates an out-of-scope product (enumerate what's available; if no such enum value exists yet, covered defaults true and a reason notes "no exclusion signal in loan model — v1 assumes covered"). Guard `assertCanAccess`.
- [ ] **RED → GREEN IT:** standard purchase loan → `covered=true`; cross-org → 404; no-token → 401. (Co-op/HELOC cases only if the loan model exposes the signal — assert what the model supports; document gaps.)
- [ ] Commit: `feat(disclosures): TRID coverage gate endpoint`.

### Task 8: LE issuance (TDD via IT — crown-jewel E2E)
**Files:** `disclosures/.../service/DisclosureAssemblyService.java` (gather cost table + terms + payments) · `disclosures/.../service/DisclosureIssuanceService.java` · `disclosures/.../service/DisclosureTimingService.java` · `disclosures/.../web/DisclosureController.java` · `disclosures/.../web/dto/{IssueDisclosureRequest,DisclosureResponse}.java` · Test `app/src/test/java/com/msfg/los/disclosures/web/LoanEstimateIT.java`
- [ ] `DisclosureAssemblyService.assemble(loanId, kind)`: pull fee rows via `fees` `FeeLineItemRepository.findByLoanIdOrderByOrdinalAscIdAsc` → map to `DisclosureCostRow` (bucket via `TolerancePolicy`); pull terms from `Loan` (loanNumber, noteAmount, interestRate, **getLoanTermMonths**, prepaymentPenalty if present else false, productDescription = mortgageType+purpose); monthly P&I via `qualification` `MortgageMath`/`LoanCalculationService` (read its API; if it needs more than the loan has, pass what's available and let figures be null-tolerant); build `CashToCloseRow` list (down payment, closing costs, deposit, seller credits — from fees/loan; v1 may include the subset available). Returns a `DisclosureGenerationRequest` + a `DisclosureSnapshot`.
- [ ] `DisclosureTimingService`: `LocalDate leDeliveryDeadline(applicationDate)` = `calc.addBusinessDays(applicationDate, 3, GENERAL, DEFAULT)`; `LocalDate earliestConsummationForLe(deliveredDate)` = `addBusinessDays(deliveredDate, 7, PRECISE, DEFAULT)` (★ keyed to delivery, NOT receipt, NO +3 stack); `LocalDate earliestConsummationForCd(computedReceivedDate)` = `addBusinessDays(computedReceivedDate, 3, PRECISE, DEFAULT)`; `LocalDate constructiveReceived(deliveredDate)` = `addBusinessDays(deliveredDate, 3, PRECISE, DEFAULT)`. Application-date proxy = `loan.getCreatedAt().toLocalDate()` (documented v1 simplification).
- [ ] `DisclosureIssuanceService.issue(loanId, kind, IssueDisclosureRequest)`: guard once; `assemble` → `port.generate` → `documentService.storeGenerated(loanId, kind==LOAN_ESTIMATE?LOAN_ESTIMATE:CLOSING_DISCLOSURE, "disclosure", filename, result.renderedContentType(), result.renderedBytes())`; `port.send` → basis; version = `findTopByLoanIdAndKind...`.map(v+1).orElse(1); persist `DisclosureIssuance` (figures from result, status SENT, delivered_at now, received_basis CONSTRUCTIVE_PLUS_3, computed_received_date = constructiveReceived(today), earliest_consummation_date per kind, document_id, snapshot, requested_by, requested_at=now); write a `disclosure_event` (LE_ISSUED/CD_ISSUED) — **append via a REQUIRES_NEW recorder is NOT needed here (happy path in same tx); only the error path needs it (Task 10 pattern)**; for CD, run the reset detector (Task 10). Return `DisclosureResponse`.
- [ ] `record IssueDisclosureRequest(DeliveryMethod deliveryMethod, UUID triggerCocId)` (both optional; default method EMAIL). `DisclosureResponse` mirrors the issuance row (id, kind, version, status, apr, financeCharge, amountFinanced, totalOfPayments, tip, deliveryMethod, deliveredAt, receivedBasis, computedReceivedDate, earliestConsummationDate, documentId, resetTriggered, resetReasons, requestedBy, requestedAt). `POST /api/loans/{loanId}/disclosures/loan-estimate` → 201.
- [ ] **RED → GREEN crown-jewel IT:** seed loan + fees (with tolerance facts) → POST loan-estimate → 201: apr present, version 1, document downloadable via `GET .../documents/{documentId}/content` (200, body contains the loan number), `earliestConsummationDate` = hand-computed `addBusinessDays(today+? )`... (assert it equals `delivered+7 precise` using the SAME calculator in the test, or a fixed expected if you pin `delivered`); JDBC: 1 `disclosure_issuance` row + ≥1 `disclosure_event`. cross-org 404; no-token 401.
- [ ] Commit: `feat(disclosures): Loan Estimate issuance — assemble, vendor generate+send, store form, timing, audit`.

### Task 9: Receipt + timing + tolerance endpoints
**Files:** extend `DisclosureController` + services · dtos `{TimingResponse,ToleranceResponse,RecordReceiptRequest}` · Test `app/src/test/java/com/msfg/los/disclosures/web/DisclosureReadIT.java`
- [ ] `POST /api/loans/{loanId}/disclosures/{disclosureId}/receipt` `{ LocalDate receivedAt }` → load issuance (findByIdAndOrgId + loanId filter → 404), set received_at, received_basis=ACTUAL, computed_received_date=receivedAt, recompute earliest_consummation_date (CD: +3 precise from receivedAt), status=RECEIVED; write RECEIPT_RECORDED event; return updated `DisclosureResponse`.
- [ ] `GET /api/loans/{loanId}/disclosures/timing` → `TimingResponse`: latest LE + latest CD (via repo), each kind's deadlines, the **overall earliest consummation** = max of the LE-7 and CD-3 constraints present, `consummationDate` from loan, `consummationSatisfiesTiming` boolean (advisory), and any revised-LE deadline from an accepted CoC (Task 11). 
- [ ] `GET /api/loans/{loanId}/disclosures/tolerance` → `ToleranceResponse`: bucketed totals from the current fees (group by `TolerancePolicy.bucket`), + the good-faith `ToleranceComparison` vs the **baseline = latest LE snapshot** (if any). Advisory.
- [ ] `GET /api/loans/{loanId}/disclosures` (history newest-first) + `GET /api/loans/{loanId}/disclosures/{id}` (detail).
- [ ] **RED → GREEN IT:** issue LE → POST receipt → basis ACTUAL + recomputed date; GET timing → earliest consummation present; GET tolerance → bucket totals match a hand sum; receipt cross-loan-same-org → 404.
- [ ] Commit: `feat(disclosures): receipt (basis flip) + timing + tolerance + history endpoints`.

### Task 10: CD issuance + reset detector (TDD via IT — crown jewel)
**Files:** `disclosures/.../service/ResetDetector.java` + `DisclosureIssuanceErrorRecorder.java` (REQUIRES_NEW) · extend `DisclosureIssuanceService` + `DisclosureController` · Test `app/src/test/java/com/msfg/los/disclosures/web/ClosingDisclosureIT.java`
- [ ] `ResetDetector.detect(DisclosureIssuance priorCd, DisclosureGenerationResult newResult, String newProduct, boolean newPrepayPenalty)` → `List<ResetReason>`: APR_INACCURATE if `|newApr − priorApr|` exceeds the **symmetric** band (0.125 if `!aprIrregularBasis` else 0.25) **AND not** within the conditional `1026.22(a)(5)(ii)` overstatement relief (v1: relief applies only when newApr < priorApr AND finance charge also overstated within tolerance — model conservatively: treat any over-band move, in either direction, as a trigger UNLESS it's a pure overstatement meeting the relief condition; comment the cite); PRODUCT_CHANGED if `!newProduct.equals(priorCd.productDescription)`; PREPAYMENT_PENALTY_ADDED if `newPrepayPenalty && !priorCd.isPrepaymentPenalty()`.
- [ ] `POST /api/loans/{loanId}/disclosures/closing-disclosure` → same issuance flow as LE; if a prior CD exists, run `ResetDetector` → set `reset_triggered` + `reset_reasons`, write a RESET_TRIGGERED event when non-empty. Error path: wrap `port.generate/send` so a RuntimeException persists an ERROR issuance row via `DisclosureIssuanceErrorRecorder` (`@Transactional(REQUIRES_NEW)`, mirror `aus`'s `AusRunErrorRecorder`) then rethrows.
- [ ] **RED → GREEN crown-jewel IT:** issue CD v1; then (i) bump fees' prepaid finance charge enough to push stub APR past 0.125 → CD v2 `reset_triggered` with `APR_INACCURATE`; (ii) change product → `PRODUCT_CHANGED`; (iii) add prepay penalty → `PREPAYMENT_PENALTY_ADDED`; a tiny within-band change → **no reset**. Independently recompute the band in the test. JDBC: version increments; reset_reasons jsonb correct.
- [ ] Commit: `feat(disclosures): Closing Disclosure issuance + 3-trigger reset detector (symmetric APR band) + ERROR-row REQUIRES_NEW`.

### Task 11: CoC → revised-LE clock wiring
**Files:** `disclosures/.../service/RevisedLeClockService.java` · extend `TimingResponse` · Test `app/src/test/java/com/msfg/los/disclosures/web/RevisedLeClockIT.java`
- [ ] Read `coc` `CocHistoryEntry`/`CocDecision`/`CocReason`. `RevisedLeClockService.revisedLeDeadline(loanId)`: find the latest **ACCEPTED** CoC that changed settlement charges (decision ACCEPT); deadline = `addBusinessDays(decisionDate, 3, GENERAL, DEFAULT)` (`1026.19(e)(3)(iv)`/`(e)(4)`). Surface in `GET .../disclosures/timing` (a `revisedLeDeadline` field, nullable). Write a REVISED_LE_DEADLINE_SET event when first computed (or compute on read — simplest: compute on read, no write; an event only when an actual revised LE is issued with `triggerCocId`).
- [ ] **RED → GREEN IT:** submit + ACCEPT a settlement-charge CoC (reuse `coc` test helpers) → GET timing → `revisedLeDeadline` = decisionDate + 3 general business days (assert with the calculator).
- [ ] Commit: `feat(disclosures): revised-LE 3-day clock from accepted CoC (e)(3)(iv)`.

### Task 12: Negative/role coverage sweep
**Files:** extend the IT classes
- [ ] PLATFORM_ADMIN → 403 on `GET .../disclosures/coverage` (access-model pin); random-subject PROCESSOR → 200 on coverage + can issue an LE (org-wide); a different LO → 403; bad kind/enum in a body → 400 `VALIDATION_ERROR`; issuing for a loan with no fees → still 201 (empty cost table, figures present-or-null, no 500).
- [ ] Run all disclosures IT classes green. Commit: `test(disclosures): role/negative coverage — PLATFORM_ADMIN pin, ops org-wide, enum 400s, empty-fees`.

### Task 13: RLS IT + full build + sweep
**Files:** `app/src/test/java/com/msfg/los/disclosures/DisclosuresRlsIT.java`
- [ ] Mirror `app/src/test/java/com/msfg/los/aus/AusRlsIT.java` (fresh org UUIDs not colliding — grep existing RLS ITs; SET ROLE app_user; GUC isolation both ways; RESET fail-closed; WITH CHECK rejection) for `disclosure_issuance` + `disclosure_event` (the event table: also assert app_user can INSERT+SELECT but the grant lacks UPDATE/DELETE — append-only, mirror how `aus` proved its append tables).
- [ ] Duplicate-simple-name sweep for every new type (`DisclosureIssuance DisclosureEvent DisclosureKind DisclosureStatus DeliveryMethod ReceivedBasis ToleranceBucket PaidTo ResetReason BusinessDayType DisclosureEventType BusinessDayCalculator GeneralBusinessDayConfig TolerancePolicy ToleranceComparison DisclosureVendorPort StubDisclosureVendorAdapter DisclosureGenerationRequest DisclosureGenerationResult DeliveryRequest DeliveryResult DeliveryStatus UcdExportResult CoverageService CoverageResponse DisclosureAssemblyService DisclosureIssuanceService DisclosureTimingService DisclosureController IssueDisclosureRequest DisclosureResponse TimingResponse ToleranceResponse RecordReceiptRequest ResetDetector RevisedLeClockService DisclosureCostRow CashToCloseRow DisclosureSnapshot`) — require clean.
- [ ] FULL `./gradlew build --console=plain` → SUCCESSFUL (~440+ tests). Foreign-file failures → STOP, report.
- [ ] Commit: `test(disclosures): RLS coverage + name sweep`.

## Post-build (controller orchestrates — NOT implementer tasks)
opus **compliance + security** review (timing-math vs CFPB, tolerance-bucket correctness, symmetric-APR reset, secrets-free, tenancy/RLS, append-only audit) → merge per worktree protocol → full build on merged tree → restart bootRun + verify `/v3/api-docs` markers (`/disclosures`, `DisclosureResponse`, `coverage`) → FE handoff dated section + `BACKEND-PROGRESS.md`/`frontend-integration.md` + CLAUDE.md/ROADMAP/memory (V17, ~440 tests).

## Self-Review
Spec coverage: scaffold+DocTypes (T1) ✓ V17 2-tables+2-ALTERs (T2) ✓ entities/repos+fee/loan fields (T3) ✓ BusinessDayCalculator (T4) ✓ TolerancePolicy+comparison (T5) ✓ vendor port+stub (T6) ✓ coverage gate (T7) ✓ LE issuance+assembly+timing (T8) ✓ receipt/timing/tolerance endpoints (T9) ✓ CD issuance+reset detector+ERROR-row (T10) ✓ CoC→revised-LE clock (T11) ✓ role/negative (T12) ✓ RLS+sweep+build (T13) ✓. Deferred items (UCD real, e-sign real, hard gate, cure execution, ARM, in-house APR) correctly absent. Types defined once in the header/Task 6; `addBusinessDays`/`bucket`/`ResetReason` names consistent across T4/T5/T8/T10. Cross-module dep avoided (PaidTo stored as String on FeeLineItem). One delegated judgment flagged WITH its rule: the CFPB day-count convention (T4 — implementer verifies + adjusts the table). No placeholders.
