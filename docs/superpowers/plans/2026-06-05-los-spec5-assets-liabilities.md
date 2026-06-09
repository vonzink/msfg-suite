# LOS Spec 5 — Assets & Liabilities Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use `- [ ]`.

**Goal:** Capture each borrower's assets + liabilities (URLA §2) on the multi-tenant/loan-scoped spine, expose
loan-level grids with totals, and carry the DTI include/exclude inputs the Spec-6 engine consumes.

**Architecture:** New `financials` Gradle module that is a near-clone of the Spec-4 `income` module. `Asset` ≈
`IncomeItem`; `Liability` ≈ `IncomeItem` + DTI fields; `AssetVerification` ≈ `IncomeVerification`; the two
summaries ≈ `IncomeSummaryService`. All entities extend `TenantScopedEntity`; single-entity loads use
`findByIdAndOrgId`; every endpoint is loan-scoped via `LoanAccessGuard`. No new crypto. Migration `V8`.

**Tech Stack:** Java 21 · Spring Boot 3.3.5 · Gradle (Kotlin DSL) · Postgres 16 + Flyway · Hibernate 6.5
(`@TenantId` + RLS) · Testcontainers · MockMvc.

**Spec:** `docs/specs/2026-06-05-los-spec5-assets-liabilities.md`

## THE TEMPLATE — the `income` module is your verbatim reference. Map 1:1:
| Spec 5 (`financials`) | Copy from (`income`) |
|---|---|
| `Asset` entity / repo | `domain/IncomeItem.java` / `repo/IncomeItemRepository.java` |
| `Liability` entity / repo | `domain/IncomeItem.java` (+ DTI fields) / `repo/IncomeItemRepository.java` |
| `AssetService` / `AssetController` / DTOs | `service/IncomeService.java` / `web/IncomeController.java` / `web/dto/{Add,Update}IncomeRequest,IncomeItemResponse` |
| `LiabilityService` (DTI pairing) | `service/EmploymentService.java` (the `applyAndValidate` paired-field pattern + SE→W2 clear) |
| `AssetSummaryService` / `LiabilitySummaryService` | `service/IncomeSummaryService.java` (+ `IncomeSummaryController`, `IncomeSummaryRow/Response`) |
| `AssetVerification` + port + stub + service + controller + DTOs | the entire `income/.../verification/` + `IncomeVerificationService` + `IncomeVerificationController` + `OrderVerificationRequest`/`IncomeVerificationResponse` |
| `EmploymentIncomeRlsIT`-style RLS IT | `app/src/test/java/com/msfg/los/income/EmploymentIncomeRlsIT.java` |

**Read the mapped income file before writing each financials file and copy its structure/conventions exactly**
(constructor injection, `org()`, `assertBorrowerInLoan`, `findByIdAndOrgId().filter().orElseThrow`,
`ApiResponse` envelope, POST→201 / DELETE→204, record DTOs with `from(entity)`). Error envelope is FLAT
(`$.message`).

## File Structure
```
settings.gradle.kts                          (modify: add "financials")
financials/build.gradle.kts                  (create — copy income/build.gradle.kts)
app/build.gradle.kts                         (modify: implementation(project(":financials")))
financials/src/main/java/com/msfg/los/financials/
  domain/AssetType.java LiabilityType.java DtiExclusionReason.java AssetVerificationType.java VerificationStatus.java
         Asset.java Liability.java AssetVerification.java
  repo/AssetRepository.java LiabilityRepository.java AssetVerificationRepository.java
  service/AssetService.java LiabilityService.java AssetSummaryService.java LiabilitySummaryService.java AssetVerificationService.java
  verification/AssetVerificationPort.java StubAssetVerificationAdapter.java OrderAssetVerificationCommand.java AssetVerificationResult.java
  web/AssetController.java LiabilityController.java AssetSummaryController.java LiabilitySummaryController.java AssetVerificationController.java
  web/dto/AddAssetRequest.java UpdateAssetRequest.java AssetResponse.java
          AddLiabilityRequest.java UpdateLiabilityRequest.java LiabilityResponse.java
          AssetSummaryRow.java AssetSummaryResponse.java LiabilitySummaryRow.java LiabilitySummaryResponse.java
          OrderAssetVerificationRequest.java AssetVerificationResponse.java
financials/src/test/java/com/msfg/los/financials/domain/EnumPartitionTest.java   (unit)
app/src/main/resources/db/migration/V8__assets_liabilities.sql                   (create)
app/src/test/java/com/msfg/los/financials/web/
  AssetControllerIT.java LiabilityControllerIT.java AssetSummaryIT.java LiabilitySummaryIT.java AssetVerificationIT.java
app/src/test/java/com/msfg/los/financials/AssetsLiabilitiesRlsIT.java
```

---

## Task 0: Scaffold the `financials` module
**Files:** modify `settings.gradle.kts`, `app/build.gradle.kts`; create `financials/build.gradle.kts`.
- [ ] **Step 1:** `settings.gradle.kts` → `include("platform", "app", "loan-core", "parties", "tenancy", "income", "financials")`.
- [ ] **Step 2:** `financials/build.gradle.kts` — identical to `income/build.gradle.kts` (deps `:platform`, `:loan-core`, `:parties`, spring-boot-starter-web/data-jpa/validation).
- [ ] **Step 3:** `app/build.gradle.kts` — add `implementation(project(":financials"))` after the `:income` line.
- [ ] **Step 4:** `./gradlew :financials:classes :app:compileJava` → SUCCESS.
- [ ] **Step 5:** Commit `chore(financials): scaffold financials module`.

## Task 1: Enums + partition helpers (TDD)
**Files:** create the 5 enums under `financials/.../domain/`. **Test:** `financials/src/test/java/com/msfg/los/financials/domain/EnumPartitionTest.java`.
- [ ] **Step 1: Failing test** `EnumPartitionTest`:
```java
package com.msfg.los.financials.domain;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class EnumPartitionTest {
    @Test void assetAccountPartition() {
        assertThat(AssetType.CHECKING.isAccount()).isTrue();
        assertThat(AssetType.RETIREMENT.isAccount()).isTrue();
        assertThat(AssetType.GIFT.isAccount()).isFalse();
        assertThat(AssetType.EARNEST_MONEY.isAccount()).isFalse();
    }
    @Test void liabilityExpensePartition() {
        assertThat(LiabilityType.REVOLVING.isExpense()).isFalse();
        assertThat(LiabilityType.MORTGAGE_LOAN.isExpense()).isFalse();
        assertThat(LiabilityType.ALIMONY.isExpense()).isTrue();
        assertThat(LiabilityType.CHILD_SUPPORT.isExpense()).isTrue();
    }
}
```
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** `AssetType.java` (boolean-arg pattern like `income`'s `IncomeType`):
```java
package com.msfg.los.financials.domain;
public enum AssetType {
    // accounts (isAccount = true)
    CHECKING(true), SAVINGS(true), MONEY_MARKET(true), CERTIFICATE_OF_DEPOSIT(true), MUTUAL_FUND(true),
    STOCKS(true), BONDS(true), RETIREMENT(true), TRUST_ACCOUNT(true), BRIDGE_LOAN_NOT_DEPOSITED(true),
    CASH_VALUE_OF_LIFE_INSURANCE(true), INDIVIDUAL_DEVELOPMENT_ACCOUNT(true),
    // other assets & credits (isAccount = false)
    EARNEST_MONEY(false), EMPLOYER_ASSISTANCE(false), GIFT(false), GIFT_OF_EQUITY(false), GRANT(false),
    PROCEEDS_FROM_SALE_OF_NON_REAL_ESTATE(false), PROCEEDS_FROM_SALE_OF_REAL_ESTATE(false),
    SECURED_BORROWED_FUNDS(false), UNSECURED_BORROWED_FUNDS(false), RENT_CREDIT(false), SWEAT_EQUITY(false),
    TRADE_EQUITY(false), OTHER(false);
    private final boolean account;
    AssetType(boolean account) { this.account = account; }
    public boolean isAccount() { return account; }
}
```
- [ ] **Step 4:** `LiabilityType.java`:
```java
package com.msfg.los.financials.domain;
public enum LiabilityType {
    // credit liabilities (isExpense = false)
    REVOLVING(false), INSTALLMENT(false), LEASE(false), OPEN_30_DAY(false), MORTGAGE_LOAN(false),
    HELOC(false), TAXES(false),
    // other liabilities & expenses (isExpense = true)
    ALIMONY(true), CHILD_SUPPORT(true), SEPARATE_MAINTENANCE(true), JOB_RELATED_EXPENSES(true), OTHER(true);
    private final boolean expense;
    LiabilityType(boolean expense) { this.expense = expense; }
    public boolean isExpense() { return expense; }
}
```
- [ ] **Step 5:** Plain enums:
```java
// DtiExclusionReason.java
package com.msfg.los.financials.domain;
public enum DtiExclusionReason { PAID_AT_OR_BEFORE_CLOSING, PAID_BY_OTHER_PARTY, LESS_THAN_10_MONTHS_REMAINING, OMITTED_DUPLICATE, OTHER }
```
```java
// AssetVerificationType.java
package com.msfg.los.financials.domain;
public enum AssetVerificationType { VOA }
```
```java
// VerificationStatus.java
package com.msfg.los.financials.domain;
public enum VerificationStatus { NOT_ORDERED, ORDERED, IN_PROGRESS, COMPLETED, FAILED }
```
- [ ] **Step 6:** `./gradlew :financials:test --tests "*EnumPartitionTest"` → PASS. Commit `feat(financials): AssetType/LiabilityType (ULAD partitions) + DTI/verification enums`.

## Task 2: `V8` migration (schema first)
**Files:** `app/src/main/resources/db/migration/V8__assets_liabilities.sql`. Mirror `V7__employment_income.sql`'s RLS+grant idiom EXACTLY.
- [ ] **Step 1:** Write `V8`:
```sql
create table asset (
    id uuid primary key,
    version bigint not null default 0,
    org_id uuid not null references organization(id),
    loan_id uuid not null,
    borrower_id uuid not null references borrower_party(id),
    ordinal int not null default 0,
    asset_type varchar(50) not null,
    financial_institution varchar(255),
    account_number varchar(80),
    cash_or_market_value numeric(15,2),
    verified boolean,
    created_at timestamp(6) with time zone, created_by varchar(120),
    updated_at timestamp(6) with time zone, updated_by varchar(120)
);
create index idx_asset_org_borrower on asset(org_id, borrower_id);
create index idx_asset_org_loan on asset(org_id, loan_id);

create table liability (
    id uuid primary key,
    version bigint not null default 0,
    org_id uuid not null references organization(id),
    loan_id uuid not null,
    borrower_id uuid not null references borrower_party(id),
    ordinal int not null default 0,
    liability_type varchar(40) not null,
    creditor_name varchar(255),
    account_number varchar(80),
    unpaid_balance numeric(15,2),
    monthly_payment numeric(15,2),
    include_in_dti boolean not null default true,
    exclusion_reason varchar(40),
    months_remaining int,
    created_at timestamp(6) with time zone, created_by varchar(120),
    updated_at timestamp(6) with time zone, updated_by varchar(120)
);
create index idx_liability_org_borrower on liability(org_id, borrower_id);
create index idx_liability_org_loan on liability(org_id, loan_id);

create table asset_verification (
    id uuid primary key,
    version bigint not null default 0,
    org_id uuid not null references organization(id),
    loan_id uuid not null,
    borrower_id uuid,
    verification_type varchar(20) not null,
    status varchar(20) not null,
    provider varchar(120),
    reference_number varchar(120),
    ordered_at timestamp(6) with time zone,
    completed_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone, created_by varchar(120),
    updated_at timestamp(6) with time zone, updated_by varchar(120)
);
create index idx_asset_verification_org_loan on asset_verification(org_id, loan_id);

alter table asset enable row level security;
alter table asset force row level security;
create policy tenant_isolation on asset
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

alter table liability enable row level security;
alter table liability force row level security;
create policy tenant_isolation on liability
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

alter table asset_verification enable row level security;
alter table asset_verification force row level security;
create policy tenant_isolation on asset_verification
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

grant select, insert, update, delete on asset to app_user;
grant select, insert, update, delete on liability to app_user;
grant select, insert, update, delete on asset_verification to app_user;
```
- [ ] **Step 2:** `./gradlew :app:test --tests "*LosApplicationTests"` → PASS. Commit `feat(app): V8 migration — asset, liability, asset_verification + RLS`.

## Task 3: `Asset` + `Liability` entities + repos
**Files:** `domain/Asset.java`, `domain/Liability.java`, `repo/AssetRepository.java`, `repo/LiabilityRepository.java`.
- [ ] **Step 1:** `Asset.java` (extends `TenantScopedEntity`, lombok `@Getter/@Setter`, `@Enumerated(STRING)`):
```java
package com.msfg.los.financials.domain;
import com.msfg.los.platform.domain.TenantScopedEntity;
import jakarta.persistence.*;
import lombok.Getter; import lombok.Setter;
import java.math.BigDecimal; import java.util.UUID;
@Entity @Table(name = "asset") @Getter @Setter
public class Asset extends TenantScopedEntity {
    @Column(nullable = false) private UUID loanId;
    @Column(nullable = false) private UUID borrowerId;
    @Column(nullable = false) private int ordinal;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private AssetType assetType;
    private String financialInstitution;
    private String accountNumber;
    private BigDecimal cashOrMarketValue;
    private Boolean verified;
}
```
- [ ] **Step 2:** `Liability.java`:
```java
package com.msfg.los.financials.domain;
import com.msfg.los.platform.domain.TenantScopedEntity;
import jakarta.persistence.*;
import lombok.Getter; import lombok.Setter;
import java.math.BigDecimal; import java.util.UUID;
@Entity @Table(name = "liability") @Getter @Setter
public class Liability extends TenantScopedEntity {
    @Column(nullable = false) private UUID loanId;
    @Column(nullable = false) private UUID borrowerId;
    @Column(nullable = false) private int ordinal;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private LiabilityType liabilityType;
    private String creditorName;
    private String accountNumber;
    private BigDecimal unpaidBalance;
    private BigDecimal monthlyPayment;
    @Column(nullable = false) private boolean includeInDti = true;
    @Enumerated(EnumType.STRING) private DtiExclusionReason exclusionReason;
    private Integer monthsRemaining;
}
```
- [ ] **Step 3:** Repos — copy `IncomeItemRepository` shape for each (`findByBorrowerIdOrderByOrdinalAsc`, `findByLoanIdOrderByOrdinalAsc`, `findByIdAndOrgId`, `countByBorrowerId`):
```java
// AssetRepository.java
package com.msfg.los.financials.repo;
import com.msfg.los.financials.domain.Asset;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface AssetRepository extends JpaRepository<Asset, UUID> {
    List<Asset> findByBorrowerIdOrderByOrdinalAsc(UUID borrowerId);
    List<Asset> findByLoanIdOrderByOrdinalAsc(UUID loanId);
    Optional<Asset> findByIdAndOrgId(UUID id, UUID orgId);
    long countByBorrowerId(UUID borrowerId);
}
```
(`LiabilityRepository` identical with `Liability`.)
- [ ] **Step 4:** `./gradlew :app:test --tests "*LosApplicationTests"` → PASS (entities validate vs V8). Commit `feat(financials): Asset + Liability entities + repositories`.

## Task 4: `Asset` CRUD (service + controller + DTOs + IT)
**Files:** `web/dto/{AddAssetRequest,UpdateAssetRequest,AssetResponse}.java`, `service/AssetService.java`, `web/AssetController.java`. **Test:** `app/src/test/java/com/msfg/los/financials/web/AssetControllerIT.java`.
- [ ] **Step 1:** DTOs (records). `AddAssetRequest(@NotNull AssetType assetType, String financialInstitution, String accountNumber, BigDecimal cashOrMarketValue, Boolean verified)`; `UpdateAssetRequest` (all nullable); `AssetResponse(UUID id, UUID borrowerId, AssetType assetType, int ordinal, String financialInstitution, String accountNumber, BigDecimal cashOrMarketValue, Boolean verified)` + `from(Asset)`.
- [ ] **Step 2:** `AssetService` — **mirror `IncomeService` exactly** (constructor: `AssetRepository`, `LoanService`, `LoanAccessGuard`, `TenantContext`, `BorrowerRepository`; `org()`; `assertBorrowerInLoan`; guard-first; single load `findByIdAndOrgId(id, org()).filter(x -> x.getBorrowerId().equals(borrowerId)).orElseThrow(new NotFoundException("Asset", id))`; `ordinal = countByBorrowerId`). The ONLY validation: `cashOrMarketValue`, when non-null, must be ≥ 0 (`signum() < 0` → `ValidationException("cashOrMarketValue must be >= 0")`). No employment-link logic.
- [ ] **Step 3:** `AssetController` `@RequestMapping("/api/loans/{loanId}/borrowers/{borrowerId}/assets")` — mirror `IncomeController` (POST→201, GET list, PATCH `/{assetId}`, DELETE `/{assetId}`→204).
- [ ] **Step 4: IT** `AssetControllerIT` (mirror `IncomeControllerIT` helpers): add asset → 201 + list=1; negative `cashOrMarketValue` → 400 (`$.message` ~ "cashOrMarketValue"); 2nd asset → ordinal 1; PATCH updates value leaves others; DELETE → 204 + list 0; cross-org → 404; no token → 401.
- [ ] **Step 5:** `./gradlew :app:test --tests "*AssetControllerIT"` → PASS; `./gradlew :financials:test` → PASS. Commit `feat(financials): Asset CRUD (service, controller, DTOs, IT)`.

## Task 5: `Liability` CRUD with DTI pairing (service + controller + DTOs + IT)
**Files:** `web/dto/{AddLiabilityRequest,UpdateLiabilityRequest,LiabilityResponse}.java`, `service/LiabilityService.java`, `web/LiabilityController.java`. **Test:** `app/src/test/.../financials/web/LiabilityControllerIT.java`.
- [ ] **Step 1:** DTOs. `AddLiabilityRequest(@NotNull LiabilityType liabilityType, String creditorName, String accountNumber, BigDecimal unpaidBalance, BigDecimal monthlyPayment, Boolean includeInDti, DtiExclusionReason exclusionReason, Integer monthsRemaining)`; `UpdateLiabilityRequest` (all nullable); `LiabilityResponse` (all fields + `id, borrowerId, ordinal`) + `from`.
- [ ] **Step 2:** `LiabilityService` — mirror `IncomeService` for the CRUD skeleton, with a `validateAndApplyDti` step modeled on `EmploymentService.applyAndValidate` (the paired-field pattern). Compute the EFFECTIVE `includeInDti`/`exclusionReason` (null-skip merge over the entity on update), then:
  - On a default-new add, `includeInDti` defaults to `true` (entity default) unless the request sets it false.
  - **Pairing rule:** if effective `includeInDti == false` and effective `exclusionReason == null` → `ValidationException("exclusionReason is required when a liability is excluded from DTI")`. If effective `includeInDti == true` and effective `exclusionReason != null` → **clear it** (recoverable — set `exclusionReason = null`), NOT a 400 (mirror the Spec-4 SE→W2 clear: an explicit `includeInDti=true` zeroes the paired field).
  - Value rules: `monthlyPayment`/`unpaidBalance`/`monthsRemaining`, when non-null, ≥ 0 (each its own `if`/throw — do NOT collapse into one `&&`).
  - Apply order: set type/creditor/account/balance/payment/monthsRemaining (null-skip), set `includeInDti` if provided (and if it becomes true, `exclusionReason = null`), then set `exclusionReason` if provided. Validate the effective state before persisting/returning.
- [ ] **Step 3:** `LiabilityController` `@RequestMapping("/api/loans/{loanId}/borrowers/{borrowerId}/liabilities")` — mirror the asset controller.
- [ ] **Step 4: IT** `LiabilityControllerIT`: add INSTALLMENT incl. monthlyPayment → 201; **exclude w/o reason** (`includeInDti=false`, no reason) → 400 (`$.message` ~ "exclusionReason"); exclude WITH reason → 201; add with `includeInDti=true` AND a reason → 201 with `$.data.exclusionReason` absent (cleared); **PATCH excluded→included** (`includeInDti=true`) → 200 and `exclusionReason` doesNotExist; negative `monthlyPayment` → 400; ordinal increments; DELETE → 204; cross-org → 404; no token → 401.
- [ ] **Step 5:** `./gradlew :app:test --tests "*LiabilityControllerIT"` → PASS. Commit `feat(financials): Liability CRUD with DTI include/exclude pairing (recoverable on PATCH)`.

## Task 6: Asset + Liability summaries (crown jewels)
**Files:** `web/dto/{AssetSummaryRow,AssetSummaryResponse,LiabilitySummaryRow,LiabilitySummaryResponse}.java`, `service/{AssetSummaryService,LiabilitySummaryService}.java`, `web/{AssetSummaryController,LiabilitySummaryController}.java`. **Tests:** `app/src/test/.../financials/web/{AssetSummaryIT,LiabilitySummaryIT}.java`.
- [ ] **Step 1:** DTOs:
```java
// AssetSummaryRow.java
package com.msfg.los.financials.web.dto;
import com.msfg.los.financials.domain.AssetType;
import java.math.BigDecimal; import java.util.UUID;
public record AssetSummaryRow(UUID borrowerId, String borrowerName, AssetType assetType,
                              String financialInstitution, BigDecimal cashOrMarketValue) {}
// AssetSummaryResponse.java
package com.msfg.los.financials.web.dto;
import java.math.BigDecimal; import java.util.List;
public record AssetSummaryResponse(List<AssetSummaryRow> rows, BigDecimal totalAssets) {}
// LiabilitySummaryRow.java
package com.msfg.los.financials.web.dto;
import com.msfg.los.financials.domain.LiabilityType;
import java.math.BigDecimal; import java.util.UUID;
public record LiabilitySummaryRow(UUID borrowerId, String borrowerName, LiabilityType liabilityType,
                                  String creditorName, BigDecimal monthlyPayment, boolean includeInDti) {}
// LiabilitySummaryResponse.java
package com.msfg.los.financials.web.dto;
import java.math.BigDecimal; import java.util.List;
public record LiabilitySummaryResponse(List<LiabilitySummaryRow> rows, BigDecimal totalMonthlyPayments,
                                       BigDecimal dtiMonthlyPayments, BigDecimal totalUnpaidBalance) {}
```
- [ ] **Step 2:** `AssetSummaryService` — mirror `IncomeSummaryService` (guard first, `borrowerId→fullName` map from `BorrowerRepository.findByLoanIdOrderByOrdinalAsc`, rows from `assets.findByLoanIdOrderByOrdinalAsc`, `totalAssets` = null-safe `BigDecimal` sum of `cashOrMarketValue`). `@Transactional(readOnly=true)`. **Use a `HashMap` for the name map** if any value could be null (borrower fullName is non-null, so `toMap` is fine here — same as income).
- [ ] **Step 3:** `LiabilitySummaryService` — same shape; compute THREE totals over `liabilities.findByLoanIdOrderByOrdinalAsc(loanId)`:
```java
BigDecimal totalMonthlyPayments = items.stream().map(Liability::getMonthlyPayment)
    .filter(a -> a != null).reduce(BigDecimal.ZERO, BigDecimal::add);
BigDecimal dtiMonthlyPayments = items.stream().filter(Liability::isIncludeInDti)
    .map(Liability::getMonthlyPayment).filter(a -> a != null).reduce(BigDecimal.ZERO, BigDecimal::add);
BigDecimal totalUnpaidBalance = items.stream().map(Liability::getUnpaidBalance)
    .filter(a -> a != null).reduce(BigDecimal.ZERO, BigDecimal::add);
```
  Rows carry `includeInDti` so the UI can render the toggle.
- [ ] **Step 4:** Controllers: `AssetSummaryController` `@GetMapping` on `@RequestMapping("/api/loans/{loanId}/assets")` path `/summary`; `LiabilitySummaryController` on `/api/loans/{loanId}/liabilities` path `/summary`. Both `ApiResponse.ok(...)`. (Mirror `IncomeSummaryController`.)
- [ ] **Step 5: ITs (crown jewels):**
  - `AssetSummaryIT`: 2 borrowers, several assets → `rows.length()` correct, `totalAssets` `compareTo`-equals `jdbc.queryForObject("select coalesce(sum(cash_or_market_value),0) from asset where loan_id = ?::uuid", BigDecimal.class, loanId)`; cross-org → 404; no token → 401.
  - `LiabilitySummaryIT`: 2 borrowers; mix of **included + excluded** liabilities (e.g. included payment 500 + 300, excluded payment 200 with a reason). Assert `totalMonthlyPayments` = 1000 and `dtiMonthlyPayments` = 800 (excluded one omitted), each `compareTo` an independent JDBC sum (`... where loan_id=?` and `... where loan_id=? and include_in_dti=true`); assert the excluded liability IS present in `rows` with `includeInDti=false`; `totalUnpaidBalance` vs JDBC; cross-org 404; no token 401.
- [ ] **Step 6:** `./gradlew :app:test --tests "*SummaryIT"` → PASS. Commit `feat(financials): asset + liability summaries (TOTAL ASSETS, all-vs-DTI payment totals)`.

## Task 7: Asset verification (entity + repo + port + stub + service + controller + DTOs + IT)
**Files:** the whole verification slice — **copy the entire `income/.../verification/` package + `IncomeVerificationService` + `IncomeVerificationController` + `OrderVerificationRequest`/`IncomeVerificationResponse`**, renaming Income→Asset / income→asset and swapping the type enum to `AssetVerificationType`.
- [ ] **Step 1:** `domain/AssetVerification.java` (extends `TenantScopedEntity`): `loanId` (not null), `borrowerId` (nullable), `@Enumerated(STRING) AssetVerificationType verificationType`, `@Enumerated(STRING) VerificationStatus status`, `provider`, `referenceNumber`, `Instant orderedAt`, `Instant completedAt`.
- [ ] **Step 2:** `repo/AssetVerificationRepository.java` — `findByLoanIdOrderByOrderedAtDesc(UUID)`, `findByIdAndOrgId(UUID,UUID)`.
- [ ] **Step 3:** `verification/` — `OrderAssetVerificationCommand(UUID loanId, UUID borrowerId, AssetVerificationType verificationType)`; `AssetVerificationResult(VerificationStatus status, String provider, String referenceNumber)`; `AssetVerificationPort` (interface, `order(...)`); `StubAssetVerificationAdapter` (`@Component`, returns `ORDERED`/`"STUB"`/`"STUB-"+8charuuid`).
- [ ] **Step 4:** `service/AssetVerificationService.java` — mirror `IncomeVerificationService` **including the Spec-4 fix**: inject `BorrowerRepository` + `TenantContext`; `order(loanId, req)` guards loan, then **if `req.borrowerId() != null` validate membership** (`borrowers.findByIdAndOrgId(req.borrowerId(), org()).filter(b -> b.getLoanId().equals(loanId)).orElseThrow(new ValidationException("borrowerId must reference a borrower of this loan"))`), then `port.order(...)`, persist with result fields + `Instant.now()`. `list` guards then `findByLoanIdOrderByOrderedAtDesc`.
- [ ] **Step 5:** DTOs `OrderAssetVerificationRequest(@NotNull AssetVerificationType verificationType, UUID borrowerId)`, `AssetVerificationResponse(...all fields...)` + `from`. Controller `@RequestMapping("/api/loans/{loanId}/assets/verifications")` POST→201 / GET list.
- [ ] **Step 6: IT** `AssetVerificationIT`: POST `{verificationType:"VOA"}` → 201 (`status=ORDERED`, `provider=STUB`, `referenceNumber` startsWith "STUB-"); GET length 1; **foreign borrowerId → 400**; cross-org → 404; no token → 401.
- [ ] **Step 7:** `./gradlew :app:test --tests "*AssetVerificationIT"` → PASS. Commit `feat(financials): doc-less asset verification (VOA) behind AssetVerificationPort (stub)`.

## Task 8: RLS coverage + full build + finish
**Files:** `app/src/test/java/com/msfg/los/financials/AssetsLiabilitiesRlsIT.java`.
- [ ] **Step 1:** **RLS IT** — copy `app/src/test/java/com/msfg/los/income/EmploymentIncomeRlsIT.java` VERBATIM and adapt: fresh orgs `…00e1`/`…00e2`; seed a `loan` per org (FK anchor) + a `borrower_party` per org; insert one `asset`, one `liability`, one `asset_verification` under `ORG_X` (matching GUC, as `app_user`) — read exact columns from `V8`. Assert GUC=`ORG_Y` → count 0 over all three tables; GUC=`ORG_X` → ≥1 each; fail-closed (RESET → 0) on `asset`.
- [ ] **Step 2:** `./gradlew :app:test --tests "*AssetsLiabilitiesRlsIT"` → PASS.
- [ ] **Step 3:** FULL `./gradlew build` → BUILD SUCCESSFUL, all modules green (Spec 1–4 + CORS unaffected). Report the new total test count.
- [ ] **Step 4:** Commit `test(financials): RLS coverage for asset/liability/asset_verification tables`.
- [ ] **Step 5:** Update `docs/ROADMAP.md` (S5 ✅) + `CLAUDE.md` Status, then invoke **superpowers:finishing-a-development-branch**.

## Self-Review
- **Spec coverage:** Asset (T3/T4) · Liability + DTI pairing (T3/T5) · both summaries incl. all-vs-DTI totals (T6) · VOA tracker behind port (T7) · V8 + RLS (T2/T8) · tenant/loan scoping every service. REO + DTI ratio deferred (spec). ✓
- **Type consistency:** `AssetType.isAccount()` / `LiabilityType.isExpense()` (T1) used in tests; `Liability.isIncludeInDti()` (lombok boolean getter) used in `LiabilitySummaryService` (T6); `findByIdAndOrgId` everywhere; verification port/adapter/service triad consistent (T7). ✓
- **No placeholders:** novel logic (enums, V8, entities, DTI pairing, dual-total summary, crown-jewel ITs) is full code; pure CRUD points at the exact `income` analog. ✓
- **Lessons applied:** DTI pairing recoverable on PATCH; each value rule its own `if`; `$.message` field assertions; summary crown jewels vs JDBC; verification validates borrower membership. ✓
```
