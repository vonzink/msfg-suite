# Fees Module — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development + test-driven-development.
> Spec: `docs/specs/2026-06-09-fees-module.md`. **Money-critical totals** — TDD the totals; assert vs JDBC.

**Goal:** loan-scoped fee line items (id-based CRUD) + invoice entries (upsert) + server-computed totals.

**Architecture:** new `fees` module, **loan-scoped** (mirror `reo`: guard `accessGuard.assertCanAccess(loanService.get(loanId))`, no borrower scope). Migration `V11`.

## Templates to mirror
- Loan-scoped entity/service/controller: `reo/.../{domain/RealEstateOwned, service/ReoService, web/ReoController, web/dto/*}`.
- Loan-scoped summary (totals analog): `reo/.../service/ReoSummaryService` + `financials/.../service/LiabilitySummaryService` (null-safe BigDecimal sums).
- V11 RLS idiom: `V10__declarations_hmda.sql` / `V9`. RLS IT: `financials/.../AssetsLiabilitiesRlsIT.java`.
- `fees/build.gradle.kts` = deps `:platform`, `:loan-core`, spring web/data-jpa/validation (NO parties).

---

## Task 0: Scaffold `fees` module + `FeeSection` + `V11`
- [ ] settings/app/build wiring (mirror reo, but deps = platform + loan-core only).
- [ ] `fees/.../domain/FeeSection.java`:
```java
package com.msfg.los.fees.domain;
public enum FeeSection { SELLER_CONCESSIONS, A, B, C, E, F, G, PRORATIONS, H, K, L, REC }
```
- [ ] `app/.../db/migration/V11__fees.sql`:
```sql
create table fee_line_item (
    id uuid primary key,
    version bigint not null default 0,
    org_id uuid not null references organization(id),
    loan_id uuid not null,
    ordinal int not null default 0,
    section varchar(40) not null,
    label varchar(255) not null,
    amount numeric(15,2),
    seller_concession numeric(15,2),
    percent numeric(9,4),
    created_at timestamp(6) with time zone, created_by varchar(120),
    updated_at timestamp(6) with time zone, updated_by varchar(120),
    constraint uq_fee_line_item unique (org_id, loan_id, section, label)
);
create index idx_fee_line_item_org_loan on fee_line_item(org_id, loan_id);

create table invoice_entry (
    id uuid primary key,
    version bigint not null default 0,
    org_id uuid not null references organization(id),
    loan_id uuid not null,
    fee_label varchar(255) not null,
    amount_disclosed numeric(15,2),
    invoice_amount numeric(15,2),
    borrower_poc numeric(15,2),
    finalized boolean not null default false,
    comment varchar(1000),
    created_at timestamp(6) with time zone, created_by varchar(120),
    updated_at timestamp(6) with time zone, updated_by varchar(120),
    constraint uq_invoice_entry unique (org_id, loan_id, fee_label)
);
create index idx_invoice_entry_org_loan on invoice_entry(org_id, loan_id);

alter table fee_line_item enable row level security;
alter table fee_line_item force row level security;
create policy tenant_isolation on fee_line_item
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

alter table invoice_entry enable row level security;
alter table invoice_entry force row level security;
create policy tenant_isolation on invoice_entry
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

grant select, insert, update, delete on fee_line_item to app_user;
grant select, insert, update, delete on invoice_entry to app_user;
```
- [ ] `./gradlew :fees:classes :app:compileJava` + `./gradlew :app:test --tests "*LosApplicationTests"` → PASS. Commit `chore(fees): scaffold module + FeeSection + V11 migration`.

## Task 1: Entities + repos
- [ ] `FeeLineItem` (extends `TenantScopedEntity`, lombok): `UUID loanId`(not null), `int ordinal`(not null), `@Enumerated(STRING) FeeSection section`(not null), `String label`, `BigDecimal amount`, `BigDecimal sellerConcession`, `BigDecimal percent`.
- [ ] `InvoiceEntry` (extends `TenantScopedEntity`): `UUID loanId`(not null), `String feeLabel`, `BigDecimal amountDisclosed`, `BigDecimal invoiceAmount`, `BigDecimal borrowerPoc`, `boolean finalized`(not null, default false), `String comment`. (lombok `@Getter/@Setter` → `isFinalized()`/`setFinalized()`.)
- [ ] `FeeLineItemRepository`: `findByLoanIdOrderByOrdinalAsc(UUID)`, `findByIdAndOrgId(UUID,UUID)`, `existsByLoanIdAndSectionAndLabel(UUID, FeeSection, String)`, `countByLoanId(UUID)`.
- [ ] `InvoiceEntryRepository`: `findByLoanIdOrderByFeeLabelAsc(UUID)`, `findByLoanIdAndFeeLabel(UUID, String)` (Optional), `findByIdAndOrgId(UUID,UUID)`.
- [ ] `./gradlew :app:test --tests "*LosApplicationTests"` → PASS. Commit `feat(fees): FeeLineItem + InvoiceEntry entities + repos`.

## Task 2: Fee line-item CRUD (id-based, 409 on dup)
- [ ] DTOs: `AddFeeRequest(@NotNull FeeSection section, @NotNull String label, BigDecimal amount, BigDecimal sellerConcession, BigDecimal percent)`; `UpdateFeeRequest(BigDecimal amount, BigDecimal sellerConcession, BigDecimal percent)` (section/label NOT patchable — identity); `FeeLineItemResponse(UUID id, FeeSection section, String label, BigDecimal amount, BigDecimal sellerConcession, BigDecimal percent, int ordinal)` + `from`.
- [ ] `FeeService` — mirror `ReoService` (org(), `accessGuard.assertCanAccess(loanService.get(loanId))` first, `findByIdAndOrgId(id,org()).filter(x->x.getLoanId().equals(loanId)).orElseThrow(new NotFoundException("Fee", id))`, `ordinal = countByLoanId`).
  - `add`: validate (amount/sellerConcession/percent ≥ 0, each own `if` → `ValidationException`); **if `fees.existsByLoanIdAndSectionAndLabel(loanId, req.section(), req.label())` → `throw new ConflictException("Fee already exists for section " + req.section() + " / " + req.label())`** (409); set fields + ordinal; save.
  - `list`, `update` (patch amount/sellerConcession/percent; re-validate ≥0), `delete`.
  - (`import com.msfg.los.platform.error.{ValidationException,ConflictException,NotFoundException};`)
- [ ] `FeeController` `@RequestMapping("/api/loans/{loanId}/fees")`: POST→201, GET list, PATCH `/{feeId}`, DELETE `/{feeId}`→204 (mirror ReoController).
- [ ] **IT** `FeeControllerIT`: add → 201 ordinal 0; **dup section+label → 409**; 2nd distinct → ordinal 1; PATCH amount (section/label unchanged); negative amount → 400 (`$.message` ~ "amount"); DELETE → 204 + gone; cross-org → 404; no token → 401.
- [ ] `./gradlew :app:test --tests "*FeeControllerIT"` + `:fees:test` → PASS. Commit `feat(fees): fee line-item CRUD (id-based, 409 on duplicate section+label)`.

## Task 3: Totals (crown jewel — money-critical)
- [ ] DTOs:
```java
// FeeTotalsResponse.java
package com.msfg.los.fees.web.dto;
import java.math.BigDecimal; import java.util.Map;
public record FeeTotalsResponse(Map<String, BigDecimal> sectionTotals, CategoryTotals categoryTotals) {
    public record CategoryTotals(BigDecimal origination, BigDecimal didNotShop, BigDecimal didShop,
                                 BigDecimal taxesGov, BigDecimal escrowPrepaids) {}
}
```
- [ ] `FeeTotalsService` (loan-scoped, `@Transactional(readOnly=true)`, guard first): load `fees.findByLoanIdOrderByOrdinalAsc(loanId)`. Build `Map<String,BigDecimal> sectionTotals` with **every `FeeSection` present, default ZERO**, summing `nz(amount)` per section (key = `section.name()`). Then:
```java
java.util.function.Function<FeeSection,BigDecimal> S = sec -> sectionTotals.get(sec.name());
var category = new CategoryTotals(
    S.apply(FeeSection.A), S.apply(FeeSection.B), S.apply(FeeSection.C), S.apply(FeeSection.E),
    S.apply(FeeSection.F).add(S.apply(FeeSection.G)));   // escrowPrepaids = F + G
```
  (`nz(x)=x==null?ZERO:x`; money scale 2.) Controller `GET /api/loans/{loanId}/fees/totals` → `ApiResponse.ok(...)`. (Spring resolves literal `/totals` before `/{feeId}` — verify no clash in the IT.)
- [ ] **IT (crown jewel)** `FeeTotalsIT` (`@Autowired JdbcTemplate`): seed A=100+200, B=50, C=75, E=30, F=400, G=600 via the API; `GET …/fees/totals` → `$.data.sectionTotals.A==300`, `.F==400`, `.G==600`; `$.data.categoryTotals.origination==300`, `.taxesGov==30`, **`.escrowPrepaids==1000`**; assert `escrowPrepaids` `compareTo` `jdbc.queryForObject("select coalesce(sum(amount),0) from fee_line_item where loan_id=?::uuid and section in ('F','G')", BigDecimal.class, loanId)`; and `sectionTotals.A` vs the JDBC sum for section A. Empty loan → all sectionTotals 0, categoryTotals 0 (no 500).
- [ ] `./gradlew :app:test --tests "*FeeTotalsIT"` → PASS. Commit `feat(fees): server-computed fee totals (per-section + LE categories, escrowPrepaids=F+G)`.

## Task 4: Invoices (list + upsert; the `final` keyword)
- [ ] DTOs (note `@JsonProperty("final")` on the `finalized` component — the wire field is `final`):
```java
// UpsertInvoiceRequest.java
package com.msfg.los.fees.web.dto;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
public record UpsertInvoiceRequest(@NotNull String feeLabel, BigDecimal amountDisclosed, BigDecimal invoiceAmount,
                                   BigDecimal borrowerPoc, @JsonProperty("final") boolean finalized, String comment) {}
```
```java
// InvoiceEntryResponse.java
package com.msfg.los.fees.web.dto;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.msfg.los.fees.domain.InvoiceEntry;
import java.math.BigDecimal; import java.util.UUID;
public record InvoiceEntryResponse(UUID id, String feeLabel, BigDecimal amountDisclosed, BigDecimal invoiceAmount,
                                   BigDecimal borrowerPoc, @JsonProperty("final") boolean finalized, String comment) {
    public static InvoiceEntryResponse from(InvoiceEntry e) {
        return new InvoiceEntryResponse(e.getId(), e.getFeeLabel(), e.getAmountDisclosed(), e.getInvoiceAmount(),
            e.getBorrowerPoc(), e.isFinalized(), e.getComment());
    }
}
```
- [ ] `InvoiceService` (loan-scoped guard): `list(loanId)` → `findByLoanIdOrderByFeeLabelAsc`. `upsert(loanId, req)`: guard; validate amounts ≥0; `var e = invoices.findByLoanIdAndFeeLabel(loanId, req.feeLabel()).orElseGet(() -> { var n = new InvoiceEntry(); n.setLoanId(loanId); n.setFeeLabel(req.feeLabel()); return n; });` set all fields (full replace incl. `finalized`); `save`.
- [ ] `InvoiceController` `@RequestMapping("/api/loans/{loanId}/fees/invoices")`: GET (list), PUT (`@Valid UpsertInvoiceRequest`) → 200 `InvoiceEntryResponse.from(...)`.
- [ ] **IT** `InvoiceControllerIT`: PUT `{feeLabel:"Appraisal Fee","final":true, amountDisclosed:500, invoiceAmount:475, ...}` → 200, `$.data.final == true`; GET → length 1, `$.data[0].final == true`; 2nd PUT same feeLabel different values → GET length still 1 (`jdbc count(*) where fee_label=? == 1`), values replaced; cross-org → 404; no token → 401.
- [ ] `./gradlew :app:test --tests "*InvoiceControllerIT"` → PASS. Commit `feat(fees): invoice entries (list + upsert by feeLabel; final via @JsonProperty)`.

## Task 5: RLS IT + full build + finish
- [ ] `FeesRlsIT` — copy `AssetsLiabilitiesRlsIT`; fresh orgs `…00b1`/`…00b2`; seed a loan per org; insert one `fee_line_item` + one `invoice_entry` (ORG_X) under `app_user`+GUC (read V11 NOT-NULL cols: `section`+`label` for fee, `fee_label` for invoice, `finalized`); assert ORG_Y → 0 / ORG_X → ≥1 / fail-closed on `fee_line_item`.
- [ ] `./gradlew :app:test --tests "*FeesRlsIT" --tests "*OpenApiDocsIT"` → PASS, then FULL `./gradlew build` → SUCCESSFUL (report total test count).
- [ ] Commit `test(fees): RLS coverage for fee_line_item + invoice_entry`. Update `docs/frontend-integration.md`/`ROADMAP.md`. Then **superpowers:finishing-a-development-branch**.

## Self-Review
Loan-scoped guard everywhere; id-based fee CRUD with 409 on dup `(section,label)`; invoice upsert by `feeLabel`;
totals server-computed (`escrowPrepaids=F+G`, string-keyed map → springdoc-safe) asserted vs JDBC; `final`
keyword handled via `@JsonProperty`; RLS on both tables; `OpenApiDocsIT` green; V11 sequential. Additive (new
module/endpoints). ✓
