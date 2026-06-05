# LOS Spec 4 — Employment & Income Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this
> plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Capture each borrower's employment + income (URLA §1b–1e) on the multi-tenant/loan-scoped spine,
expose a loan-level income grid with a TOTAL, and plant a doc-less verification port (stubbed).

**Architecture:** New `income` Gradle module mirroring `parties` (domain/repo/service/web/dto). Two core
entities — `Employment` and a unified `IncomeItem` (ULAD `IncomeType` + `monthlyAmount`, nullable
`employmentId`) — plus a thin `IncomeVerification` tracker behind `IncomeVerificationPort`. All entities
extend `TenantScopedEntity`; all single-entity loads use `findByIdAndOrgId`; every endpoint is loan-scoped
via `LoanAccessGuard`. No new crypto. Migration `V7`.

**Tech Stack:** Java 21 · Spring Boot 3.3.5 · Gradle (Kotlin DSL) · Postgres 16 + Flyway · JPA/Hibernate 6.5
(`@TenantId` + RLS) · Testcontainers · MockMvc.

**Spec:** `docs/specs/2026-06-05-los-spec4-employment-income.md`

---

## Reference analogs (READ THESE FIRST — copy their exact shape)
- **Entity:** `parties/.../domain/BorrowerAddress.java` (tenant-scoped, `loanId`/`borrowerId` denormalized, lombok `@Getter/@Setter`).
- **Repo:** `parties/.../repo/BorrowerAddressRepository.java` (`findByIdAndOrgId`, `findByX_OrderBy`, `countBy`).
- **Service:** `parties/.../service/BorrowerAddressService.java` — **the canonical pattern**: `org()` helper,
  `assertBorrowerInLoan(loanId, borrowerId)` (guard + borrower-in-loan check), guard-first methods, `apply(...)` patch helper.
- **Controller:** `parties/.../web/BorrowerAddressController.java` (ApiResponse envelope, 201 on POST, 204 on DELETE).
- **DTOs:** `parties/.../web/dto/{AddAddressRequest,UpdateAddressRequest,AddressResponse}.java` (Java records; `Response.from(entity)`).
- **Migration RLS:** `app/src/main/resources/db/migration/V6__personal_information.sql` (enable+force RLS, `tenant_isolation` policy with `nullif(current_setting('app.current_org',true),'')::uuid`, grants).
- **IT base:** `app/src/test/java/com/msfg/los/support/AbstractIntegrationTest.java` (`DEFAULT_ORG`, Testcontainers, stub JwtDecoder). **Tenant JWT helper** used in every IT:
  ```java
  jwt().jwt(j -> j.subject(LO).claim("org_id", DEFAULT_ORG)).authorities(new SimpleGrantedAuthority("ROLE_LO"))
  ```
- **Cross-module note:** the `income` module depends on `:parties`, so `IncomeSummaryService` may import
  `BorrowerParty` / `BorrowerRepository` directly.

## File Structure
```
settings.gradle.kts                         (modify: add "income")
income/build.gradle.kts                      (create)
app/build.gradle.kts                         (modify: implementation(project(":income")))
income/src/main/java/com/msfg/los/income/
  domain/IncomeType.java EmploymentStatusType.java EmploymentClassificationType.java
         OwnershipInterestType.java VerificationType.java VerificationStatus.java
         Employment.java IncomeItem.java IncomeVerification.java
  repo/EmploymentRepository.java IncomeItemRepository.java IncomeVerificationRepository.java
  service/EmploymentService.java IncomeService.java IncomeSummaryService.java IncomeVerificationService.java
  verification/IncomeVerificationPort.java StubIncomeVerificationAdapter.java
               OrderIncomeVerificationCommand.java IncomeVerificationResult.java
  web/EmploymentController.java IncomeController.java IncomeSummaryController.java IncomeVerificationController.java
  web/dto/AddEmploymentRequest.java UpdateEmploymentRequest.java EmploymentResponse.java
          AddIncomeRequest.java UpdateIncomeRequest.java IncomeItemResponse.java
          IncomeSummaryResponse.java IncomeSummaryRow.java
          OrderVerificationRequest.java IncomeVerificationResponse.java
income/src/test/java/com/msfg/los/income/domain/IncomeTypeTest.java   (unit)
app/src/main/resources/db/migration/V7__employment_income.sql        (create)
app/src/test/java/com/msfg/los/income/web/
  EmploymentControllerIT.java IncomeControllerIT.java IncomeSummaryIT.java IncomeVerificationIT.java
app/src/test/java/com/msfg/los/income/EmploymentIncomeRlsIT.java
```

---

## Task 0: Scaffold the `income` module

**Files:** Modify `settings.gradle.kts`, `app/build.gradle.kts`; create `income/build.gradle.kts`.
(Branch `spec-4-employment-income` already exists with the spec committed.)

- [ ] **Step 1:** `settings.gradle.kts` — add `income` to the include list:
```kotlin
rootProject.name = "msfg-los"
include("platform", "app", "loan-core", "parties", "tenancy", "income")
```
- [ ] **Step 2:** Create `income/build.gradle.kts`:
```kotlin
dependencies {
    implementation(project(":platform"))
    implementation(project(":loan-core"))
    implementation(project(":parties"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
}
```
- [ ] **Step 3:** `app/build.gradle.kts` — add after the `:parties` line:
```kotlin
    implementation(project(":income"))
```
- [ ] **Step 4:** `./gradlew :income:classes :app:compileJava` → BUILD SUCCESSFUL (empty module wires up; app sees it).
- [ ] **Step 5:** Commit `chore(income): scaffold income module (deps platform, loan-core, parties)`.

---

## Task 1: Enums + `IncomeType.isEmployment()` (TDD)

**Files:** Create the 6 enums under `income/.../domain/`. **Test:** `income/src/test/java/com/msfg/los/income/domain/IncomeTypeTest.java`.

- [ ] **Step 1: Failing test** `IncomeTypeTest.java`:
```java
package com.msfg.los.income.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class IncomeTypeTest {
    @Test void employmentTypesArePartitioned() {
        assertThat(IncomeType.BASE.isEmployment()).isTrue();
        assertThat(IncomeType.OVERTIME.isEmployment()).isTrue();
        assertThat(IncomeType.SELF_EMPLOYMENT_INCOME.isEmployment()).isTrue();
        assertThat(IncomeType.OTHER_EMPLOYMENT.isEmployment()).isTrue();
        assertThat(IncomeType.SOCIAL_SECURITY.isEmployment()).isFalse();
        assertThat(IncomeType.CHILD_SUPPORT.isEmployment()).isFalse();
        assertThat(IncomeType.OTHER.isEmployment()).isFalse();
    }
    @Test void selfEmploymentMayBeNegativeOthersMayNot() {
        assertThat(IncomeType.SELF_EMPLOYMENT_INCOME.allowsNegative()).isTrue();
        assertThat(IncomeType.BASE.allowsNegative()).isFalse();
        assertThat(IncomeType.SOCIAL_SECURITY.allowsNegative()).isFalse();
    }
}
```
- [ ] **Step 2: Run** `./gradlew :income:test --tests "*IncomeTypeTest"` → FAIL (IncomeType not defined).
- [ ] **Step 3:** `domain/IncomeType.java`:
```java
package com.msfg.los.income.domain;

public enum IncomeType {
    // Employment income (attaches to an Employment of the same borrower)
    BASE(true), OVERTIME(true), BONUS(true), COMMISSION(true),
    MILITARY_BASE_PAY(true), MILITARY_ENTITLEMENTS(true),
    SELF_EMPLOYMENT_INCOME(true, true),   // may be negative (a loss)
    OTHER_EMPLOYMENT(true),
    // Other-source income (no employer; employmentId must be null)
    ALIMONY(false), CHILD_SUPPORT(false), SOCIAL_SECURITY(false), PENSION(false),
    DISABILITY(false), DIVIDENDS_INTEREST(false), NOTES_RECEIVABLE(false), ROYALTIES(false),
    TRUST(false), UNEMPLOYMENT(false), VA_BENEFITS_NON_EDUCATIONAL(false), PUBLIC_ASSISTANCE(false),
    FOSTER_CARE(false), SEPARATE_MAINTENANCE(false), AUTOMOBILE_ALLOWANCE(false), BOARDER_INCOME(false),
    HOUSING_ALLOWANCE(false), CAPITAL_GAINS(false), OTHER(false);

    private final boolean employment;
    private final boolean allowsNegative;
    IncomeType(boolean employment) { this(employment, false); }
    IncomeType(boolean employment, boolean allowsNegative) {
        this.employment = employment; this.allowsNegative = allowsNegative;
    }
    public boolean isEmployment() { return employment; }
    public boolean allowsNegative() { return allowsNegative; }
}
```
- [ ] **Step 4:** The other 5 enums (plain):
```java
// EmploymentStatusType.java
package com.msfg.los.income.domain;
public enum EmploymentStatusType { CURRENT, PREVIOUS }
```
```java
// EmploymentClassificationType.java
package com.msfg.los.income.domain;
public enum EmploymentClassificationType { PRIMARY, SECONDARY }
```
```java
// OwnershipInterestType.java
package com.msfg.los.income.domain;
public enum OwnershipInterestType { LESS_THAN_25, GREATER_OR_EQUAL_25 }
```
```java
// VerificationType.java
package com.msfg.los.income.domain;
public enum VerificationType { VOI, TAX_TRANSCRIPT }
```
```java
// VerificationStatus.java
package com.msfg.los.income.domain;
public enum VerificationStatus { NOT_ORDERED, ORDERED, IN_PROGRESS, COMPLETED, FAILED }
```
- [ ] **Step 5: Run** `./gradlew :income:test --tests "*IncomeTypeTest"` → PASS.
- [ ] **Step 6:** Commit `feat(income): IncomeType (ULAD, isEmployment/allowsNegative) + employment/verification enums`.

---

## Task 2: `V7` migration (schema first, so entity tasks validate cleanly)

**Files:** Create `app/src/main/resources/db/migration/V7__employment_income.sql`.

- [ ] **Step 1:** Write `V7__employment_income.sql` (mirror V6's RLS + grant idiom exactly):
```sql
create table employment (
    id uuid primary key,
    version bigint not null default 0,
    org_id uuid not null references organization(id),
    loan_id uuid not null,
    borrower_id uuid not null references borrower_party(id),
    ordinal int not null default 0,
    employer_name varchar(255),
    employer_phone varchar(30),
    employer_address_line1 varchar(255),
    employer_address_line2 varchar(255),
    employer_city varchar(120),
    employer_state varchar(2),
    employer_postal_code varchar(10),
    position_title varchar(150),
    employment_status varchar(20),
    classification varchar(20),
    self_employed boolean,
    ownership_share varchar(30),
    employed_by_party_to_transaction boolean,
    start_date date,
    end_date date,
    months_in_line_of_work int,
    created_at timestamp(6) with time zone, created_by varchar(120),
    updated_at timestamp(6) with time zone, updated_by varchar(120)
);
create index idx_employment_org_borrower on employment(org_id, borrower_id);
create index idx_employment_org_loan on employment(org_id, loan_id);

create table income_item (
    id uuid primary key,
    version bigint not null default 0,
    org_id uuid not null references organization(id),
    loan_id uuid not null,
    borrower_id uuid not null references borrower_party(id),
    employment_id uuid references employment(id) on delete cascade,
    income_type varchar(40) not null,
    monthly_amount numeric(15,2),
    description varchar(255),
    ordinal int not null default 0,
    created_at timestamp(6) with time zone, created_by varchar(120),
    updated_at timestamp(6) with time zone, updated_by varchar(120)
);
create index idx_income_item_org_borrower on income_item(org_id, borrower_id);
create index idx_income_item_org_loan on income_item(org_id, loan_id);
create index idx_income_item_org_employment on income_item(org_id, employment_id);

create table income_verification (
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
create index idx_income_verification_org_loan on income_verification(org_id, loan_id);

alter table employment enable row level security;
alter table employment force row level security;
create policy tenant_isolation on employment
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

alter table income_item enable row level security;
alter table income_item force row level security;
create policy tenant_isolation on income_item
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

alter table income_verification enable row level security;
alter table income_verification force row level security;
create policy tenant_isolation on income_verification
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

grant select, insert, update, delete on employment to app_user;
grant select, insert, update, delete on income_item to app_user;
grant select, insert, update, delete on income_verification to app_user;
```
- [ ] **Step 2:** `./gradlew :app:test --tests "*LosApplicationTests"` → PASS (migration applies cleanly; existing entities still validate). Commit `feat(app): V7 migration — employment, income_item, income_verification + RLS`.

---

## Task 3: `Employment` entity + repo

**Files:** Create `domain/Employment.java`, `repo/EmploymentRepository.java`.

- [ ] **Step 1:** `domain/Employment.java` (extends `TenantScopedEntity`; lombok; `@Enumerated(STRING)` enums):
```java
package com.msfg.los.income.domain;

import com.msfg.los.platform.domain.TenantScopedEntity;
import com.msfg.los.platform.reference.UsStateCode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "employment")
@Getter
@Setter
public class Employment extends TenantScopedEntity {

    @Column(nullable = false) private UUID loanId;
    @Column(nullable = false) private UUID borrowerId;
    @Column(nullable = false) private int ordinal;

    private String employerName;
    private String employerPhone;
    private String employerAddressLine1;
    private String employerAddressLine2;
    private String employerCity;
    @Enumerated(EnumType.STRING) private UsStateCode employerState;
    private String employerPostalCode;
    private String positionTitle;

    @Enumerated(EnumType.STRING) private EmploymentStatusType employmentStatus;
    @Enumerated(EnumType.STRING) private EmploymentClassificationType classification;
    private Boolean selfEmployed;
    @Enumerated(EnumType.STRING) private OwnershipInterestType ownershipShare;
    private Boolean employedByPartyToTransaction;

    private LocalDate startDate;
    private LocalDate endDate;
    private Integer monthsInLineOfWork;
}
```
- [ ] **Step 2:** `repo/EmploymentRepository.java`:
```java
package com.msfg.los.income.repo;

import com.msfg.los.income.domain.Employment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmploymentRepository extends JpaRepository<Employment, UUID> {
    List<Employment> findByBorrowerIdOrderByOrdinalAsc(UUID borrowerId);
    List<Employment> findByLoanIdOrderByOrdinalAsc(UUID loanId);
    Optional<Employment> findByIdAndOrgId(UUID id, UUID orgId);
    long countByBorrowerId(UUID borrowerId);
}
```
- [ ] **Step 3:** `./gradlew :app:test --tests "*LosApplicationTests"` → PASS (entity validates against the V7 `employment` table). Commit `feat(income): Employment entity + repository`.

---

## Task 4: `IncomeItem` entity + repo

**Files:** Create `domain/IncomeItem.java`, `repo/IncomeItemRepository.java`.

- [ ] **Step 1:** `domain/IncomeItem.java`:
```java
package com.msfg.los.income.domain;

import com.msfg.los.platform.domain.TenantScopedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "income_item")
@Getter
@Setter
public class IncomeItem extends TenantScopedEntity {

    @Column(nullable = false) private UUID loanId;
    @Column(nullable = false) private UUID borrowerId;
    private UUID employmentId;                 // null = other-source income

    @Enumerated(EnumType.STRING)
    @Column(nullable = false) private IncomeType incomeType;

    private BigDecimal monthlyAmount;
    private String description;
    @Column(nullable = false) private int ordinal;
}
```
- [ ] **Step 2:** `repo/IncomeItemRepository.java`:
```java
package com.msfg.los.income.repo;

import com.msfg.los.income.domain.IncomeItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IncomeItemRepository extends JpaRepository<IncomeItem, UUID> {
    List<IncomeItem> findByBorrowerIdOrderByOrdinalAsc(UUID borrowerId);
    List<IncomeItem> findByLoanIdOrderByOrdinalAsc(UUID loanId);
    Optional<IncomeItem> findByIdAndOrgId(UUID id, UUID orgId);
    long countByBorrowerId(UUID borrowerId);
}
```
- [ ] **Step 3:** `./gradlew :app:test --tests "*LosApplicationTests"` → PASS. Commit `feat(income): IncomeItem entity + repository`.

---

## Task 5: `EmploymentService` + controller + DTOs + IT

**Files:** Create `web/dto/{AddEmploymentRequest,UpdateEmploymentRequest,EmploymentResponse}.java`,
`service/EmploymentService.java`, `web/EmploymentController.java`. **Test:**
`app/src/test/java/com/msfg/los/income/web/EmploymentControllerIT.java`.

- [ ] **Step 1:** DTOs (Java records). `AddEmploymentRequest` / `UpdateEmploymentRequest` carry the same
  fields (all optional — patch semantics — except `AddEmploymentRequest` needs nothing required at capture):
  `employerName, employerPhone, employerAddressLine1, employerAddressLine2, employerCity,
  UsStateCode employerState, employerPostalCode, positionTitle, EmploymentStatusType employmentStatus,
  EmploymentClassificationType classification, Boolean selfEmployed, OwnershipInterestType ownershipShare,
  Boolean employedByPartyToTransaction, LocalDate startDate, LocalDate endDate, Integer monthsInLineOfWork`.
  `EmploymentResponse` mirrors all fields + `UUID id, UUID borrowerId, int ordinal`, with
  `static EmploymentResponse from(Employment e)` (copy each getter — same style as `AddressResponse.from`).
- [ ] **Step 2:** `EmploymentService` — **mirror `BorrowerAddressService` exactly** (`org()`,
  `assertBorrowerInLoan(loanId, borrowerId)` injecting `EmploymentRepository`, `BorrowerRepository`,
  `LoanService`, `LoanAccessGuard`, `TenantContext`). Methods:
  - `add(loanId, borrowerId, req)`: `assertBorrowerInLoan`; new `Employment`; set `loanId`+`borrowerId`;
    `ordinal = (int) employments.countByBorrowerId(borrowerId)`; `applyAndValidate(e, req...)`; `save`.
  - `list(loanId, borrowerId)`: `assertBorrowerInLoan`; `findByBorrowerIdOrderByOrdinalAsc`.
  - `update(loanId, borrowerId, employmentId, req)`: `assertBorrowerInLoan`;
    `employments.findByIdAndOrgId(employmentId, org()).filter(x -> x.getBorrowerId().equals(borrowerId)).orElseThrow(new NotFoundException("Employment", employmentId))`; `applyAndValidate`.
  - `delete(loanId, borrowerId, employmentId)`: same load; `employments.delete(e)` (DB cascades its income_items).
  - `applyAndValidate(Employment e, ...fields...)` — null-skip patch each field (like `apply` in
    `BorrowerAddressService`), then enforce the **self-employment + previous rules** at the end:
    ```java
    boolean se = Boolean.TRUE.equals(e.getSelfEmployed());
    if (!se && e.getOwnershipShare() != null)
        throw new ValidationException("ownershipShare is only valid when selfEmployed");
    if (se && e.getOwnershipShare() == null)
        throw new ValidationException("ownershipShare is required when selfEmployed");
    if (!se && (e.getEmployerName() == null || e.getEmployerName().isBlank()))
        throw new ValidationException("employerName is required unless selfEmployed");
    if (e.getEmploymentStatus() == EmploymentStatusType.PREVIOUS) {
        if (e.getEndDate() == null) throw new ValidationException("endDate is required for PREVIOUS employment");
        if (e.getStartDate() != null && e.getEndDate().isBefore(e.getStartDate()))
            throw new ValidationException("endDate must be on or after startDate");
    }
    ```
    (`import com.msfg.los.platform.error.ValidationException;` — constructor takes a message → HTTP 400.)
- [ ] **Step 3:** `EmploymentController` `@RequestMapping("/api/loans/{loanId}/borrowers/{borrowerId}/employments")`
  — **mirror `BorrowerAddressController`**: POST→201 `ApiResponse.ok(EmploymentResponse.from(...))`, GET list,
  PATCH `/{employmentId}`, DELETE `/{employmentId}`→204.
- [ ] **Step 4: IT** `EmploymentControllerIT extends AbstractIntegrationTest` (mirror
  `BorrowerAddressControllerIT`: `createLoan()`, `addBorrower(loanId)` helpers via MockMvc + JsonPath). Cover:
  - add CURRENT employment → 201, fields echoed, `ordinal=0`; list → length 1.
  - self-employed without `ownershipShare` → **400**; self-employed with `ownershipShare` → 201.
  - non-self-employed without `employerName` → **400**.
  - PREVIOUS without `endDate` → **400**; PREVIOUS with `endDate` before `startDate` → **400**.
  - PATCH updates a field, leaves others unchanged; DELETE → 204 then list length 0.
  - other-company JWT (random `org_id`) add → **404**; no token → **401**.
- [ ] **Step 5:** `./gradlew :app:test --tests "*EmploymentControllerIT"` → PASS, then `./gradlew :income:test` → PASS. Commit `feat(income): Employment CRUD (service, controller, DTOs, IT) with self-employment + previous rules`.

---

## Task 6: `IncomeService` + controller + DTOs + IT (consistency + cascade)

**Files:** Create `web/dto/{AddIncomeRequest,UpdateIncomeRequest,IncomeItemResponse}.java`,
`service/IncomeService.java`, `web/IncomeController.java`. **Test:** `app/src/test/.../income/web/IncomeControllerIT.java`.

- [ ] **Step 1:** DTOs. `AddIncomeRequest(@NotNull IncomeType incomeType, @NotNull BigDecimal monthlyAmount,
  UUID employmentId, String description)`. `UpdateIncomeRequest(IncomeType incomeType, BigDecimal monthlyAmount,
  UUID employmentId, String description)` (all nullable). `IncomeItemResponse(UUID id, UUID borrowerId,
  UUID employmentId, IncomeType incomeType, BigDecimal monthlyAmount, String description, int ordinal)` +
  `from(IncomeItem)`.
- [ ] **Step 2:** `IncomeService` — inject `IncomeItemRepository income`, `EmploymentRepository employments`,
  `BorrowerRepository borrowers`, `LoanService`, `LoanAccessGuard`, `TenantContext`. Reuse the
  `org()` + `assertBorrowerInLoan(loanId, borrowerId)` pattern. The **consistency + amount rules** live in a
  private `validate(borrowerId, IncomeType type, BigDecimal amount, UUID employmentId)`:
  ```java
  private void validate(UUID borrowerId, IncomeType type, BigDecimal amount, UUID employmentId) {
      if (type.isEmployment()) {
          if (employmentId == null)
              throw new ValidationException("employmentId is required for employment income (" + type + ")");
          employments.findByIdAndOrgId(employmentId, org())
              .filter(e -> e.getBorrowerId().equals(borrowerId))
              .orElseThrow(() -> new ValidationException("employmentId must reference an employment of this borrower"));
      } else if (employmentId != null) {
          throw new ValidationException("employmentId must be null for non-employment income (" + type + ")");
      }
      if (amount != null && !type.allowsNegative() && amount.signum() < 0)
          throw new ValidationException("monthlyAmount must be >= 0 for " + type);
  }
  ```
  - `add(loanId, borrowerId, req)`: `assertBorrowerInLoan`; `validate(borrowerId, req.incomeType(), req.monthlyAmount(), req.employmentId())`;
    new `IncomeItem`, set loanId/borrowerId/incomeType/monthlyAmount/employmentId/description,
    `ordinal = (int) income.countByBorrowerId(borrowerId)`; `save`.
  - `list(loanId, borrowerId)`: `assertBorrowerInLoan`; `findByBorrowerIdOrderByOrdinalAsc`.
  - `update(loanId, borrowerId, incomeId, req)`: `assertBorrowerInLoan`; load via
    `income.findByIdAndOrgId(incomeId, org()).filter(x -> x.getBorrowerId().equals(borrowerId)).orElseThrow(new NotFoundException("Income", incomeId))`;
    compute effective type/amount/employmentId (null-skip from req over the existing values), then
    `validate(...)` on the effective values, then apply.
  - `delete(loanId, borrowerId, incomeId)`: load (same) → `income.delete(item)`.
- [ ] **Step 3:** `IncomeController` `@RequestMapping("/api/loans/{loanId}/borrowers/{borrowerId}/income")` — mirror the address controller (POST→201, GET list, PATCH `/{incomeId}`, DELETE `/{incomeId}`→204).
- [ ] **Step 4: IT** `IncomeControllerIT` cover:
  - add employment first; add `BASE` income with that `employmentId` → 201; list → length 1.
  - add `BASE` income with **null** `employmentId` → **400**.
  - add `SOCIAL_SECURITY` income with an `employmentId` → **400**; with null → 201.
  - add `BASE` income whose `employmentId` belongs to a **different borrower** → **400**.
  - add `SELF_EMPLOYMENT_INCOME` with a negative `monthlyAmount` (linked to a self-employed employment) → 201;
    add `BASE` with a negative amount → **400**.
  - **cascade:** create an employment + a `BASE` income on it; `DELETE` the employment; then `GET` the
    borrower's income list → that income row is **gone** (DB `ON DELETE CASCADE`).
    *(Note: the cascade IT must re-list after deletion in a fresh request so the new transaction sees it.)*
  - cross-org JWT → **404**; no token → **401**.
- [ ] **Step 5:** `./gradlew :app:test --tests "*IncomeControllerIT"` → PASS. Commit `feat(income): IncomeItem CRUD with employment/other consistency + sign rules; employment delete cascades income`.

---

## Task 7: `IncomeSummaryService` + endpoint + DTO + IT (crown jewel — TOTAL across borrowers)

**Files:** Create `web/dto/{IncomeSummaryResponse,IncomeSummaryRow}.java`, `service/IncomeSummaryService.java`,
`web/IncomeSummaryController.java`. **Test:** `app/src/test/.../income/web/IncomeSummaryIT.java`.

- [ ] **Step 1:** DTOs:
```java
// IncomeSummaryRow.java
package com.msfg.los.income.web.dto;
import com.msfg.los.income.domain.IncomeType;
import java.math.BigDecimal;
import java.util.UUID;
public record IncomeSummaryRow(UUID borrowerId, String borrowerName, IncomeType incomeType,
                               String employerName, BigDecimal monthlyAmount) {}
```
```java
// IncomeSummaryResponse.java
package com.msfg.los.income.web.dto;
import java.math.BigDecimal;
import java.util.List;
public record IncomeSummaryResponse(List<IncomeSummaryRow> rows, BigDecimal totalMonthlyIncome) {}
```
- [ ] **Step 2:** `IncomeSummaryService` (loan-scoped aggregate; `@TenantId` auto-filters the `findByLoanId`
  queries, so every list is already tenant-scoped):
```java
package com.msfg.los.income.service;

import com.msfg.los.income.domain.Employment;
import com.msfg.los.income.domain.IncomeItem;
import com.msfg.los.income.repo.EmploymentRepository;
import com.msfg.los.income.repo.IncomeItemRepository;
import com.msfg.los.income.web.dto.IncomeSummaryResponse;
import com.msfg.los.income.web.dto.IncomeSummaryRow;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.parties.domain.BorrowerParty;
import com.msfg.los.parties.repo.BorrowerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class IncomeSummaryService {

    private final IncomeItemRepository income;
    private final EmploymentRepository employments;
    private final BorrowerRepository borrowers;
    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;

    public IncomeSummaryService(IncomeItemRepository income, EmploymentRepository employments,
                                BorrowerRepository borrowers, LoanService loanService, LoanAccessGuard accessGuard) {
        this.income = income; this.employments = employments; this.borrowers = borrowers;
        this.loanService = loanService; this.accessGuard = accessGuard;
    }

    @Transactional(readOnly = true)
    public IncomeSummaryResponse summarize(UUID loanId) {
        accessGuard.assertCanAccess(loanService.get(loanId));   // 404 cross-org, 403 not owner

        Map<UUID, String> borrowerNames = borrowers.findByLoanIdOrderByOrdinalAsc(loanId).stream()
            .collect(Collectors.toMap(BorrowerParty::getId, IncomeSummaryService::fullName));
        Map<UUID, String> employerNames = employments.findByLoanIdOrderByOrdinalAsc(loanId).stream()
            .collect(Collectors.toMap(Employment::getId, e ->
                e.getEmployerName() != null ? e.getEmployerName()
                    : (Boolean.TRUE.equals(e.getSelfEmployed()) ? "Self-Employed" : null)));

        List<IncomeItem> items = income.findByLoanIdOrderByOrdinalAsc(loanId);
        List<IncomeSummaryRow> rows = items.stream().map(i -> new IncomeSummaryRow(
            i.getBorrowerId(),
            borrowerNames.get(i.getBorrowerId()),
            i.getIncomeType(),
            i.getEmploymentId() == null ? null : employerNames.get(i.getEmploymentId()),
            i.getMonthlyAmount()
        )).toList();

        BigDecimal total = items.stream()
            .map(IncomeItem::getMonthlyAmount)
            .filter(a -> a != null)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new IncomeSummaryResponse(rows, total);
    }

    private static String fullName(BorrowerParty b) {
        String first = b.getFirstName() == null ? "" : b.getFirstName();
        String last = b.getLastName() == null ? "" : b.getLastName();
        return (first + " " + last).trim();
    }
}
```
  *(Confirm `BorrowerRepository` exposes `findByLoanIdOrderByOrdinalAsc(UUID)` — it does, used by `BorrowerService.list`.)*
- [ ] **Step 3:** `IncomeSummaryController`:
```java
package com.msfg.los.income.web;

import com.msfg.los.income.service.IncomeSummaryService;
import com.msfg.los.income.web.dto.IncomeSummaryResponse;
import com.msfg.los.platform.web.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/loans/{loanId}/income")
public class IncomeSummaryController {
    private final IncomeSummaryService service;
    public IncomeSummaryController(IncomeSummaryService service) { this.service = service; }

    @GetMapping("/summary")
    public ApiResponse<IncomeSummaryResponse> summary(@PathVariable UUID loanId) {
        return ApiResponse.ok(service.summarize(loanId));
    }
}
```
- [ ] **Step 4: IT (crown jewel)** `IncomeSummaryIT` — one loan, **two** borrowers; borrower A: an employment +
  `BASE` 5000 + `OVERTIME` 500; borrower B: `SOCIAL_SECURITY` 1200 (no employer). Then:
  - `GET /api/loans/{loanId}/income/summary` → 200; `$.data.rows.length() == 3`;
    `$.data.totalMonthlyIncome == 6700`; assert a `BASE` row for borrower A carries the employer name and the
    `SOCIAL_SECURITY` row has a null `employerName`.
  - **assert against an independent JDBC sum** (`SELECT COALESCE(SUM(monthly_amount),0) FROM income_item`
    via `JdbcTemplate` in tenant context) — the endpoint total equals the DB sum. This proves the loan-level
    aggregate over the tenant-scoped rows.
  - cross-org JWT → **404**; no token → **401**.
  *(Use a `BigDecimal`/`int` JSON compare that tolerates `6700` vs `6700.00` — e.g. `jsonPath("$.data.totalMonthlyIncome").value(6700)` or compare `new BigDecimal(...)` via `comparesEqualTo`.)*
- [ ] **Step 5:** `./gradlew :app:test --tests "*IncomeSummaryIT"` → PASS. Commit `feat(income): loan-level income summary grid + TOTAL across borrowers`.

---

## Task 8: `IncomeVerification` — entity + repo + port + stub adapter + service + controller + DTOs + IT

**Files:** Create `domain/IncomeVerification.java`, `repo/IncomeVerificationRepository.java`,
`verification/{IncomeVerificationPort,StubIncomeVerificationAdapter,OrderIncomeVerificationCommand,IncomeVerificationResult}.java`,
`service/IncomeVerificationService.java`, `web/dto/{OrderVerificationRequest,IncomeVerificationResponse}.java`,
`web/IncomeVerificationController.java`. **Test:** `app/src/test/.../income/web/IncomeVerificationIT.java`.

- [ ] **Step 1:** `domain/IncomeVerification.java` (extends `TenantScopedEntity`): `UUID loanId` (not null),
  `UUID borrowerId` (nullable), `@Enumerated(STRING) VerificationType verificationType`,
  `@Enumerated(STRING) VerificationStatus status`, `String provider`, `String referenceNumber`,
  `Instant orderedAt`, `Instant completedAt`. (lombok `@Getter/@Setter`.)
- [ ] **Step 2:** `repo/IncomeVerificationRepository.java`: `extends JpaRepository<IncomeVerification, UUID>` —
  `List<IncomeVerification> findByLoanIdOrderByOrderedAtDesc(UUID loanId)`,
  `Optional<IncomeVerification> findByIdAndOrgId(UUID id, UUID orgId)`.
- [ ] **Step 3:** The port seam (plain types, no Spring in the port interface):
```java
// verification/OrderIncomeVerificationCommand.java
package com.msfg.los.income.verification;
import com.msfg.los.income.domain.VerificationType;
import java.util.UUID;
public record OrderIncomeVerificationCommand(UUID loanId, UUID borrowerId, VerificationType verificationType) {}
```
```java
// verification/IncomeVerificationResult.java
package com.msfg.los.income.verification;
import com.msfg.los.income.domain.VerificationStatus;
public record IncomeVerificationResult(VerificationStatus status, String provider, String referenceNumber) {}
```
```java
// verification/IncomeVerificationPort.java
package com.msfg.los.income.verification;
public interface IncomeVerificationPort {
    IncomeVerificationResult order(OrderIncomeVerificationCommand command);
}
```
```java
// verification/StubIncomeVerificationAdapter.java
package com.msfg.los.income.verification;
import com.msfg.los.income.domain.VerificationStatus;
import org.springframework.stereotype.Component;
import java.util.UUID;
@Component
public class StubIncomeVerificationAdapter implements IncomeVerificationPort {
    @Override public IncomeVerificationResult order(OrderIncomeVerificationCommand c) {
        // No real vendor yet — record an immediate ORDERED with a synthetic reference.
        return new IncomeVerificationResult(VerificationStatus.ORDERED, "STUB",
            "STUB-" + UUID.randomUUID().toString().substring(0, 8));
    }
}
```
- [ ] **Step 4:** `service/IncomeVerificationService` — inject `IncomeVerificationRepository`,
  `IncomeVerificationPort port`, `LoanService`, `LoanAccessGuard`, `TenantContext`.
  - `order(loanId, OrderVerificationRequest req)`: `accessGuard.assertCanAccess(loanService.get(loanId))`;
    `IncomeVerificationResult r = port.order(new OrderIncomeVerificationCommand(loanId, req.borrowerId(), req.verificationType()))`;
    new `IncomeVerification`; set loanId, borrowerId, verificationType, `status = r.status()`,
    `provider = r.provider()`, `referenceNumber = r.referenceNumber()`, `orderedAt = Instant.now()`; `save`.
    *(`Instant.now()` is fine in app code — only workflow scripts forbid it.)*
  - `list(loanId)`: `assertCanAccess`; `findByLoanIdOrderByOrderedAtDesc(loanId)`.
- [ ] **Step 5:** DTOs: `OrderVerificationRequest(@NotNull VerificationType verificationType, UUID borrowerId)`;
  `IncomeVerificationResponse(UUID id, UUID loanId, UUID borrowerId, VerificationType verificationType,
  VerificationStatus status, String provider, String referenceNumber, Instant orderedAt, Instant completedAt)`
  + `from(IncomeVerification)`.
- [ ] **Step 6:** `IncomeVerificationController` `@RequestMapping("/api/loans/{loanId}/income/verifications")`:
  `POST` (body `OrderVerificationRequest`) → 201 `ApiResponse.ok(IncomeVerificationResponse.from(...))`;
  `GET` → list.
- [ ] **Step 7: IT** `IncomeVerificationIT`: POST `{verificationType:"VOI"}` → 201, `status == "ORDERED"`,
  `referenceNumber` starts with `"STUB-"`, `provider == "STUB"`; GET → length 1. POST `TAX_TRANSCRIPT` →
  list length 2. cross-org JWT POST → **404**; no token → **401**.
- [ ] **Step 8:** `./gradlew :app:test --tests "*IncomeVerificationIT"` → PASS. Commit `feat(income): doc-less income verification tracker behind IncomeVerificationPort (stub adapter)`.

---

## Task 9: RLS coverage + full build + boot smoke + finish

**Files:** Create `app/src/test/java/com/msfg/los/income/EmploymentIncomeRlsIT.java`.

- [ ] **Step 1:** **RLS IT** — copy `app/src/test/java/com/msfg/los/tenancy/RlsIT.java`'s mechanism **verbatim**
  (`@Autowired DataSource`/`JdbcTemplate`; seed two FRESH orgs `ORG_X`/`ORG_Y` via `insert into organization …
  on conflict do nothing`; one `Connection` with `set role app_user`; `setOrg(c, org)` = `select set_config(
  'app.current_org', ?, false)`; `RESET` via `reset app.current_org`; `reset role` in `finally`). Fresh orgs
  give **exact** counts. All inserts go through this connection **under matching GUC** so RLS `WITH CHECK` passes:
  - Insert one `borrower_party` under `ORG_X` (read its NOT-NULL columns from V2 + V6 — at minimum
    `id, version, org_id, loan_id, is_primary, ordinal`; `loan_id` is a plain uuid here, use a random one),
    then one `employment` (ORG_X, that `borrower_id`), one `income_item` (ORG_X, that borrower + employment),
    one `income_verification` (ORG_X, `loan_id` random, `verification_type='VOI'`, `status='ORDERED'`) — match
    each table's exact V7 columns.
  - Assert: with GUC = `ORG_Y`, `select count(*)` over `employment`, `income_item`, `income_verification` is
    each **0** (wrong tenant sees nothing); with GUC = `ORG_X`, each is **≥ 1**. (`ORG_X`/`ORG_Y` are fresh, so
    these hold regardless of other tests.) This proves the V7 RLS policies engage for all three new tables.
- [ ] **Step 2:** `./gradlew :app:test --tests "*EmploymentIncomeRlsIT"` → PASS.
- [ ] **Step 3:** **Full build:** `./gradlew build` → BUILD SUCCESSFUL, all modules, every test green (Spec 1/2/3 suites unaffected). Note the new total test count.
- [ ] **Step 4:** **Boot smoke:** `docker compose up -d` then
  `./gradlew :app:bootRun --args='--spring.profiles.active=local'`; confirm startup (V7 applies), hit
  `GET /swagger-ui/index.html` (or the OpenAPI JSON) and verify the new `employments` / `income` /
  `income/summary` / `income/verifications` routes are listed. Stop bootRun.
- [ ] **Step 5:** Commit `test(income): RLS coverage for employment/income/verification tables`.
- [ ] **Step 6:** Update `docs/ROADMAP.md` (mark S4 ✅ + merge commit once merged) and `CLAUDE.md` Status.
  Then invoke **superpowers:finishing-a-development-branch**.

---

## Self-Review
- **Spec coverage:** Employment (T3/T5) · unified IncomeItem + consistency/sign rules (T4/T6) · loan-level
  grid + TOTAL (T7) · doc-less verification port + stub (T8) · `V7` + RLS (T2/T9) · tenant/loan scoping in
  every service (T5–T8). Income Calculator / DTI / real vendor explicitly deferred (spec). ✓
- **Type consistency:** `IncomeType.isEmployment()`/`allowsNegative()` (T1) used by `IncomeService.validate` (T6);
  `EmploymentRepository.findByLoanIdOrderByOrdinalAsc` (T3) used by `IncomeSummaryService` (T7);
  `IncomeVerificationPort.order(OrderIncomeVerificationCommand)→IncomeVerificationResult` (T8) consistent
  across port/adapter/service; `findByIdAndOrgId` everywhere a single entity is loaded. ✓
- **No placeholders:** novel logic (enums, entities, migration, summary aggregate, validation, port/adapter,
  crown-jewel ITs) is full code; pure CRUD boilerplate points at the exact in-repo analog
  (`BorrowerAddressService`/`Controller`, `AddressResponse.from`) with explicit field lists. ✓
- **Tenancy correctness:** all single-entity loads via `findByIdAndOrgId`; list/aggregate queries are
  `findByLoanId…`/`findByBorrowerId…` (auto-filtered by `@TenantId`); RLS proven in T9. ✓
```
