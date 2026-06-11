# Products & Pricing / Rate Lock Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** New `pricing` module (V14): rate-lock lifecycle (control-your-price / extend / rate-change / relock) + persisted pricing-breakdown snapshots from a deterministic stub `PricingEnginePort` + lock-confirmation HTML document, per the approved spec `docs/superpowers/specs/2026-06-10-pricing-lock-design.md`.

**Architecture:** Modular-monolith module mirroring `coc`/`fees`: 3 tenant-scoped tables (`rate_lock` 1:1-per-loan current state, `pricing_adjustment` quote snapshot, `lock_event` append-only audit), service-level lock state machine, pricing math behind a port (stub adapter only impl), letter storage through `documents`' `DocumentStoragePort` via a new `DocumentService.storeGenerated`. **No write-back to `Loan.interestRate`.**

**Tech Stack:** Java 21, Spring Boot 3.3, JPA + Hibernate `@TenantId`, Flyway V14, Postgres RLS, springdoc, MockMvc ITs (Testcontainers).

**Ground rules for every task (from CLAUDE.md + session lessons):**
- Build with `./gradlew` (wrapper 8.10; system Gradle is 9.x — never use it). Docker Desktop must be running.
- ⚠️ **The working tree carries unrelated uncommitted edits** in `reo/`, `coc/`, `platform/`, `qualification/` + untracked `.claude/`. **Stage files explicitly by path on every commit — never `git add -A`/`-u`/`.`**
- Tenant discipline: load by `findByIdAndOrgId` or `findByLoanId…` (never bare `findById`); new entities extend `TenantScopedEntity`.
- Validation: separate checks per rule (no `&&`-collapsed conditions); ITs assert `$.fields.<name>`.
- Response envelope: `ApiResponse.ok(...)`; errors are flat `{success,code,message,fields,timestamp}`.
- All percents 3dp; dollars 2dp `RoundingMode.HALF_UP`.

---

### Task 1: Module scaffold

**Files:**
- Modify: `settings.gradle.kts`
- Create: `pricing/build.gradle.kts`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Confirm V14 is still the next migration (parallel-session guard)**

Run: `ls app/src/main/resources/db/migration/ | sort -V | tail -2`
Expected: `V12__coc.sql` `V13__documents.sql` (no V14). If a V14 exists, STOP and renumber to the next free number throughout this plan.

- [ ] **Step 2: Register the module**

In `settings.gradle.kts`, extend the single `include(...)` line:

```kotlin
include("platform", "app", "loan-core", "parties", "tenancy", "income", "financials", "reo", "qualification", "declarations", "fees", "coc", "documents", "pricing")
```

- [ ] **Step 3: Create `pricing/build.gradle.kts`**

First run `cat qualification/build.gradle.kts` — if it contains a `testImplementation`/test block (it has unit tests like ours will), copy that block too. Base content:

```kotlin
dependencies {
    implementation(project(":platform"))
    implementation(project(":loan-core"))
    implementation(project(":qualification"))
    implementation(project(":documents"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
}
```

- [ ] **Step 4: Add to `app/build.gradle.kts`** — next to the other module deps:

```kotlin
    implementation(project(":pricing"))
```

- [ ] **Step 5: Verify it compiles**

Run: `./gradlew :pricing:compileJava :app:compileJava -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts pricing/build.gradle.kts app/build.gradle.kts
git commit -m "feat(pricing): scaffold pricing module"
```

---

### Task 2: Migration V14

**Files:**
- Create: `app/src/main/resources/db/migration/V14__pricing_lock.sql`

- [ ] **Step 1: Write the migration** (conventions copied from `V13__documents.sql`; `lock_event` gets the `pii_access_log` append-only grant treatment):

```sql
-- V14: Products & Pricing / Rate Lock — rate_lock (current, 1:1/loan),
-- pricing_adjustment (quote snapshot), lock_event (append-only audit).

create table rate_lock (
    id uuid primary key,
    version bigint not null default 0,
    org_id uuid not null references organization(id),
    loan_id uuid not null unique,
    locked_rate numeric(7,3) not null,
    commitment_days int not null,
    lock_date timestamp(6) with time zone not null,
    expiration_date date not null,
    extension_days_total int not null default 0,
    compensation_payer_type varchar(20) not null,
    locked_by varchar(120),
    interviewer_email varchar(320),
    created_at timestamp(6) with time zone,
    created_by varchar(120),
    updated_at timestamp(6) with time zone,
    updated_by varchar(120)
);
create index idx_rate_lock_org_loan on rate_lock(org_id, loan_id);

create table pricing_adjustment (
    id uuid primary key,
    version bigint not null default 0,
    org_id uuid not null references organization(id),
    loan_id uuid not null,
    ordinal int not null,
    name varchar(200) not null,
    row_type varchar(30) not null,
    adjustment_percent numeric(8,3) not null,
    dollar_amount numeric(14,2) not null,
    created_at timestamp(6) with time zone,
    created_by varchar(120),
    updated_at timestamp(6) with time zone,
    updated_by varchar(120)
);
create index idx_pricing_adjustment_org_loan on pricing_adjustment(org_id, loan_id);

create table lock_event (
    id uuid primary key,
    version bigint not null default 0,
    org_id uuid not null references organization(id),
    loan_id uuid not null,
    action varchar(30) not null,
    actor varchar(120),
    occurred_at timestamp(6) with time zone not null,
    rate numeric(7,3) not null,
    commitment_days int not null,
    expiration_date date not null,
    created_at timestamp(6) with time zone,
    created_by varchar(120),
    updated_at timestamp(6) with time zone,
    updated_by varchar(120)
);
create index idx_lock_event_org_loan on lock_event(org_id, loan_id);

-- Row-level security (FORCE + WITH CHECK, fail-closed) — same policy shape as V13.
alter table rate_lock enable row level security;
alter table rate_lock force row level security;
create policy tenant_isolation on rate_lock
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

alter table pricing_adjustment enable row level security;
alter table pricing_adjustment force row level security;
create policy tenant_isolation on pricing_adjustment
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

alter table lock_event enable row level security;
alter table lock_event force row level security;
create policy tenant_isolation on lock_event
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

grant select, insert, update, delete on rate_lock to app_user;
grant select, insert, update, delete on pricing_adjustment to app_user;
-- lock_event is an append-only audit: SELECT/INSERT only (pii_access_log precedent)
grant select, insert on lock_event to app_user;
```

- [ ] **Step 2: Boot-verify the migration applies**

Run: `./gradlew :app:test --tests "com.msfg.los.openapi.OpenApiDocsIT" -q`
Expected: PASS (context boots ⇒ Flyway applied V14 cleanly).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/resources/db/migration/V14__pricing_lock.sql
git commit -m "feat(pricing): V14 — rate_lock, pricing_adjustment, lock_event (RLS + append-only audit grants)"
```

---

### Task 3: Domain — enums, entities, repositories (+ effective-status TDD)

**Files:**
- Create: `pricing/src/main/java/com/msfg/los/pricing/domain/RateLockStatus.java`
- Create: `pricing/src/main/java/com/msfg/los/pricing/domain/LockAction.java`
- Create: `pricing/src/main/java/com/msfg/los/pricing/domain/CompensationPayerType.java`
- Create: `pricing/src/main/java/com/msfg/los/pricing/domain/PricingRowType.java`
- Create: `pricing/src/main/java/com/msfg/los/pricing/domain/RateLock.java`
- Create: `pricing/src/main/java/com/msfg/los/pricing/domain/PricingAdjustment.java`
- Create: `pricing/src/main/java/com/msfg/los/pricing/domain/LockEvent.java`
- Create: `pricing/src/main/java/com/msfg/los/pricing/repo/RateLockRepository.java`
- Create: `pricing/src/main/java/com/msfg/los/pricing/repo/PricingAdjustmentRepository.java`
- Create: `pricing/src/main/java/com/msfg/los/pricing/repo/LockEventRepository.java`
- Test: `pricing/src/test/java/com/msfg/los/pricing/domain/RateLockStatusTest.java`

- [ ] **Step 1: Write the failing effective-status test** (the expiration boundary is load-bearing: expiration day itself is still LOCKED):

```java
package com.msfg.los.pricing.domain;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.assertThat;

class RateLockStatusTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 10);

    @Test
    void expirationAfterToday_isLocked() {
        assertThat(RateLockStatus.effective(TODAY.plusDays(1), TODAY)).isEqualTo(RateLockStatus.LOCKED);
    }

    @Test
    void expirationToday_isStillLocked() {
        assertThat(RateLockStatus.effective(TODAY, TODAY)).isEqualTo(RateLockStatus.LOCKED);
    }

    @Test
    void expirationBeforeToday_isExpired() {
        assertThat(RateLockStatus.effective(TODAY.minusDays(1), TODAY)).isEqualTo(RateLockStatus.EXPIRED);
    }
}
```

- [ ] **Step 2: Run it — expect compile failure** (`RateLockStatus` missing)

Run: `./gradlew :pricing:test -q`
Expected: FAIL (cannot find symbol RateLockStatus).

- [ ] **Step 3: Implement the enums**

```java
package com.msfg.los.pricing.domain;

import java.time.LocalDate;

/** Effective lock state. Only LOCKED-state rows persist; NOT_LOCKED/EXPIRED are computed. */
public enum RateLockStatus {
    NOT_LOCKED, LOCKED, EXPIRED;

    /** Expiration day itself still counts as locked; locks expire end-of-day. */
    public static RateLockStatus effective(LocalDate expirationDate, LocalDate today) {
        return expirationDate.isBefore(today) ? EXPIRED : LOCKED;
    }
}
```

```java
package com.msfg.los.pricing.domain;

public enum LockAction { CONTROL_YOUR_PRICE, EXTEND, RATE_CHANGE, RELOCK }
```

```java
package com.msfg.los.pricing.domain;

public enum CompensationPayerType { LENDER_PAID, BORROWER_PAID }
```

```java
package com.msfg.los.pricing.domain;

/** Row kind in the pricing breakdown (FE bolds FINAL / FINAL_AFTER_COMP). */
public enum PricingRowType { BASE, ADJUSTMENT, FINAL, COMPENSATION, FINAL_AFTER_COMP }
```

- [ ] **Step 4: Run the test — expect PASS**

Run: `./gradlew :pricing:test -q`
Expected: 3 tests PASS.

- [ ] **Step 5: Implement the entities** (mirror `coc`'s entity style: `TenantScopedEntity` base, `@Enumerated(STRING)`, UUID id assigned in ctor or `@PrePersist` — copy whichever `CocHistoryEntry` uses, including its `@Id` setup, verbatim):

```java
package com.msfg.los.pricing.domain;

import com.msfg.los.platform.domain.TenantScopedEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "rate_lock")
public class RateLock extends TenantScopedEntity {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "loan_id", nullable = false, updatable = false)
    private UUID loanId;

    @Column(name = "locked_rate", nullable = false)
    private BigDecimal lockedRate;

    @Column(name = "commitment_days", nullable = false)
    private Integer commitmentDays;

    @Column(name = "lock_date", nullable = false)
    private Instant lockDate;

    @Column(name = "expiration_date", nullable = false)
    private LocalDate expirationDate;

    @Column(name = "extension_days_total", nullable = false)
    private int extensionDaysTotal = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "compensation_payer_type", nullable = false, length = 20)
    private CompensationPayerType compensationPayerType;

    @Column(name = "locked_by", length = 120)
    private String lockedBy;

    @Column(name = "interviewer_email", length = 320)
    private String interviewerEmail;

    // getters + setters for every field (no @Data/@ToString — project convention)
}
```

`PricingAdjustment` — same skeleton with fields: `UUID id`, `UUID loanId`, `int ordinal`, `String name` (length 200), `@Enumerated(STRING) PricingRowType rowType` (length 30), `BigDecimal adjustmentPercent`, `BigDecimal dollarAmount`; column names exactly as in V14.

`LockEvent` — same skeleton with fields: `UUID id`, `UUID loanId`, `@Enumerated(STRING) LockAction action` (length 30), `String actor` (length 120), `Instant occurredAt`, `BigDecimal rate`, `Integer commitmentDays`, `LocalDate expirationDate`.

⚠️ If `TenantScopedEntity`/`AuditableEntity` carries `@Version` (check the base class), do NOT redeclare it. Check `CocHistoryEntry` for whether `id` is field-initialized or `@PrePersist`-assigned and copy that exact mechanism.

- [ ] **Step 6: Implement the repositories**

```java
package com.msfg.los.pricing.repo;

import com.msfg.los.pricing.domain.RateLock;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface RateLockRepository extends JpaRepository<RateLock, UUID> {
    Optional<RateLock> findByLoanId(UUID loanId);          // @TenantId-filtered
}
```

```java
package com.msfg.los.pricing.repo;

import com.msfg.los.pricing.domain.PricingAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface PricingAdjustmentRepository extends JpaRepository<PricingAdjustment, UUID> {
    List<PricingAdjustment> findByLoanIdOrderByOrdinalAscIdAsc(UUID loanId);
    void deleteByLoanId(UUID loanId);
}
```

```java
package com.msfg.los.pricing.repo;

import com.msfg.los.pricing.domain.LockEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface LockEventRepository extends JpaRepository<LockEvent, UUID> {
    List<LockEvent> findByLoanIdOrderByOccurredAtAscIdAsc(UUID loanId);
}
```

(Deterministic `IdAsc` tiebreakers — the REO-ordinal lesson.)

- [ ] **Step 7: Compile + entity-scan check** — `app`'s entity scan must pick up `com.msfg.los.pricing.domain`. Check how `coc` entities get scanned (`@EntityScan` list or base-package convention in the app config) and add `com.msfg.los.pricing` wherever `com.msfg.los.coc` appears (same for `@EnableJpaRepositories`/component scan if explicit).

Run: `./gradlew :pricing:test :app:compileJava -q`
Expected: BUILD SUCCESSFUL, 3 tests pass.

- [ ] **Step 8: Commit**

```bash
git add pricing/src settings.gradle.kts 2>/dev/null; git add pricing/src
git status --short   # VERIFY: only pricing/** (+ any app config file you edited for scanning) staged
git commit -m "feat(pricing): domain — RateLock/PricingAdjustment/LockEvent + effective-status rule"
```

---

### Task 4: PricingEnginePort + deterministic stub adapter (TDD — the money math)

**Files:**
- Create: `pricing/src/main/java/com/msfg/los/pricing/port/PricingQuoteRequest.java`
- Create: `pricing/src/main/java/com/msfg/los/pricing/port/QuoteRow.java`
- Create: `pricing/src/main/java/com/msfg/los/pricing/port/PriceQuote.java`
- Create: `pricing/src/main/java/com/msfg/los/pricing/port/PricingEnginePort.java`
- Create: `pricing/src/main/java/com/msfg/los/pricing/engine/StubPricingEngineAdapter.java`
- Test: `pricing/src/test/java/com/msfg/los/pricing/engine/StubPricingEngineAdapterTest.java`

- [ ] **Step 1: Write the port types** (records; no Spring):

```java
package com.msfg.los.pricing.port;

import com.msfg.los.loan.domain.LoanPurposeType;
import com.msfg.los.pricing.domain.CompensationPayerType;
import java.math.BigDecimal;

/** Inputs to a pricing quote. fico/ltv/loanPurpose may be null (no-data bucket). */
public record PricingQuoteRequest(
        BigDecimal rate,
        int commitmentDays,
        CompensationPayerType compensationPayerType,
        int extensionDaysTotal,
        Integer fico,
        BigDecimal ltv,
        LoanPurposeType loanPurpose,
        BigDecimal totalLoanAmount) {}
```

```java
package com.msfg.los.pricing.port;

import com.msfg.los.pricing.domain.PricingRowType;
import java.math.BigDecimal;

public record QuoteRow(String name, PricingRowType rowType, BigDecimal percent) {}
```

```java
package com.msfg.los.pricing.port;

import java.util.List;

public record PriceQuote(List<QuoteRow> rows) {}
```

```java
package com.msfg.los.pricing.port;

/** External pricing engine seam. Stub adapter today; real vendor (Optimal Blue et al.) later. */
public interface PricingEnginePort {
    PriceQuote quote(PricingQuoteRequest request);
}
```

- [ ] **Step 2: Write the failing golden-table tests** — these values ARE the spec (§5); ITs later reuse them:

```java
package com.msfg.los.pricing.engine;

import com.msfg.los.loan.domain.LoanPurposeType;
import com.msfg.los.pricing.domain.CompensationPayerType;
import com.msfg.los.pricing.domain.PricingRowType;
import com.msfg.los.pricing.port.PricingQuoteRequest;
import com.msfg.los.pricing.port.QuoteRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StubPricingEngineAdapterTest {

    private final StubPricingEngineAdapter engine = new StubPricingEngineAdapter();

    private static PricingQuoteRequest req(String rate, int days, CompensationPayerType comp,
                                           int extDays, Integer fico, String ltv, LoanPurposeType purpose) {
        return new PricingQuoteRequest(new BigDecimal(rate), days, comp, extDays, fico,
                ltv == null ? null : new BigDecimal(ltv), purpose, new BigDecimal("300000"));
    }

    private static void assertRow(QuoteRow row, String name, PricingRowType type, String percent) {
        assertThat(row.name()).isEqualTo(name);
        assertThat(row.rowType()).isEqualTo(type);
        assertThat(row.percent()).isEqualByComparingTo(percent);
    }

    @Test
    void baselinePurchase_fico745_ltv80_30day_lenderPaid() {
        // Base: -((7.000-6.500)*0.500) - ((30-15)/15*0.125) = -0.375
        // FICO/LTV: 745 in 700-759, ltv 80 in (60,80] => 0.500 (boundary: 80 is <=80)
        // Purpose PURCHASE: 0.000 ; Final: 0.125 ; Comp LENDER_PAID: 1.000 ; FAC: 1.125
        List<QuoteRow> rows = engine.quote(
                req("6.500", 30, CompensationPayerType.LENDER_PAID, 0, 745, "80.000", LoanPurposeType.PURCHASE)).rows();
        assertThat(rows).hasSize(6);
        assertRow(rows.get(0), "Base Price", PricingRowType.BASE, "-0.375");
        assertRow(rows.get(1), "FICO/LTV Adjustment", PricingRowType.ADJUSTMENT, "0.500");
        assertRow(rows.get(2), "Purpose Adjustment", PricingRowType.ADJUSTMENT, "0.000");
        assertRow(rows.get(3), "Final Price", PricingRowType.FINAL, "0.125");
        assertRow(rows.get(4), "Compensation", PricingRowType.COMPENSATION, "1.000");
        assertRow(rows.get(5), "Final Price After Compensation", PricingRowType.FINAL_AFTER_COMP, "1.125");
    }

    @Test
    void bestBucket_fico760_ltv60_15day_borrowerPaid() {
        // Base: -((7.000-7.250)*0.500) - 0 = +0.125 ; FICO/LTV >=760 & <=60: 0.000
        // Purpose null: 0.000 ; Final: 0.125 ; Comp BORROWER_PAID: 0.000 ; FAC: 0.125
        List<QuoteRow> rows = engine.quote(
                req("7.250", 15, CompensationPayerType.BORROWER_PAID, 0, 760, "60.000", null)).rows();
        assertRow(rows.get(0), "Base Price", PricingRowType.BASE, "0.125");
        assertRow(rows.get(1), "FICO/LTV Adjustment", PricingRowType.ADJUSTMENT, "0.000");
        assertRow(rows.get(3), "Final Price", PricingRowType.FINAL, "0.125");
        assertRow(rows.get(4), "Compensation", PricingRowType.COMPENSATION, "0.000");
        assertRow(rows.get(5), "Final Price After Compensation", PricingRowType.FINAL_AFTER_COMP, "0.125");
    }

    @Test
    void worstBucket_fico699_ltv81_construction_90day() {
        // Base: -((7.000-6.000)*0.500) - ((90-15)/15*0.125) = -0.500 - 0.625 = -1.125
        // FICO/LTV <700 & >80: 1.500 ; CONSTRUCTION: 0.500 ; Final: 0.875
        List<QuoteRow> rows = engine.quote(
                req("6.000", 90, CompensationPayerType.LENDER_PAID, 0, 699, "81.000", LoanPurposeType.CONSTRUCTION)).rows();
        assertRow(rows.get(0), "Base Price", PricingRowType.BASE, "-1.125");
        assertRow(rows.get(1), "FICO/LTV Adjustment", PricingRowType.ADJUSTMENT, "1.500");
        assertRow(rows.get(2), "Purpose Adjustment", PricingRowType.ADJUSTMENT, "0.500");
        assertRow(rows.get(3), "Final Price", PricingRowType.FINAL, "0.875");
    }

    @Test
    void refinancePurpose_addsQuarterPoint() {
        List<QuoteRow> rows = engine.quote(
                req("6.500", 30, CompensationPayerType.LENDER_PAID, 0, 745, "80.000", LoanPurposeType.REFINANCE)).rows();
        assertRow(rows.get(2), "Purpose Adjustment", PricingRowType.ADJUSTMENT, "0.250");
        assertRow(rows.get(3), "Final Price", PricingRowType.FINAL, "0.375");
    }

    @Test
    void nullFicoOrLtv_usesNoDataRowAtZero() {
        List<QuoteRow> rows = engine.quote(
                req("6.500", 30, CompensationPayerType.LENDER_PAID, 0, null, "80.000", LoanPurposeType.PURCHASE)).rows();
        assertRow(rows.get(1), "FICO/LTV Adjustment (no data)", PricingRowType.ADJUSTMENT, "0.000");
    }

    @Test
    void extensionDays_emitExtensionFeeRowBeforeFinal() {
        // Extension Fee (15 days) = 15 * 0.020 = 0.300 ; Final = -0.375 + 0.500 + 0 + 0.300 = 0.425
        List<QuoteRow> rows = engine.quote(
                req("6.500", 30, CompensationPayerType.LENDER_PAID, 15, 745, "80.000", LoanPurposeType.PURCHASE)).rows();
        assertThat(rows).hasSize(7);
        assertRow(rows.get(3), "Extension Fee (15 days)", PricingRowType.ADJUSTMENT, "0.300");
        assertRow(rows.get(4), "Final Price", PricingRowType.FINAL, "0.425");
        assertRow(rows.get(6), "Final Price After Compensation", PricingRowType.FINAL_AFTER_COMP, "1.425");
    }

    @Test
    void ltvBoundary60_isInBestColumn() {
        // ltv exactly 60 => "<= 60" column ; fico 700-759 => 0.250
        List<QuoteRow> rows = engine.quote(
                req("6.500", 30, CompensationPayerType.LENDER_PAID, 0, 700, "60.000", LoanPurposeType.PURCHASE)).rows();
        assertRow(rows.get(1), "FICO/LTV Adjustment", PricingRowType.ADJUSTMENT, "0.250");
    }
}
```

- [ ] **Step 3: Run — expect FAIL** (`StubPricingEngineAdapter` missing)

Run: `./gradlew :pricing:test -q`

- [ ] **Step 4: Implement the stub** (spec §5 verbatim):

```java
package com.msfg.los.pricing.engine;

import com.msfg.los.loan.domain.LoanPurposeType;
import com.msfg.los.pricing.domain.CompensationPayerType;
import com.msfg.los.pricing.domain.PricingRowType;
import com.msfg.los.pricing.port.PriceQuote;
import com.msfg.los.pricing.port.PricingEnginePort;
import com.msfg.los.pricing.port.PricingQuoteRequest;
import com.msfg.los.pricing.port.QuoteRow;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic STUB pricing engine (spec 2026-06-10 §5). Not real pricing —
 * a real vendor adapter replaces this behind the same port, zero service change.
 */
@Component
public class StubPricingEngineAdapter implements PricingEnginePort {

    private static final BigDecimal PAR_RATE = new BigDecimal("7.000");

    @Override
    public PriceQuote quote(PricingQuoteRequest req) {
        List<QuoteRow> rows = new ArrayList<>();

        // 1. Base Price: -((7.000 - rate) * 0.500) - ((commitmentDays - 15) / 15 * 0.125)
        BigDecimal rateComponent = PAR_RATE.subtract(req.rate()).multiply(new BigDecimal("0.500"));
        BigDecimal termComponent = new BigDecimal(req.commitmentDays() - 15)
                .divide(new BigDecimal("15"), 10, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("0.125"));
        rows.add(new QuoteRow("Base Price", PricingRowType.BASE,
                p3(rateComponent.negate().subtract(termComponent))));

        // 2. FICO/LTV bucket
        if (req.fico() == null || req.ltv() == null) {
            rows.add(new QuoteRow("FICO/LTV Adjustment (no data)", PricingRowType.ADJUSTMENT, p3(BigDecimal.ZERO)));
        } else {
            rows.add(new QuoteRow("FICO/LTV Adjustment", PricingRowType.ADJUSTMENT,
                    p3(ficoLtvBucket(req.fico(), req.ltv()))));
        }

        // 3. Purpose
        rows.add(new QuoteRow("Purpose Adjustment", PricingRowType.ADJUSTMENT, p3(purposeAdj(req.loanPurpose()))));

        // 4. Extension fee (cumulative), only when present
        if (req.extensionDaysTotal() > 0) {
            BigDecimal fee = new BigDecimal(req.extensionDaysTotal()).multiply(new BigDecimal("0.020"));
            rows.add(new QuoteRow("Extension Fee (" + req.extensionDaysTotal() + " days)",
                    PricingRowType.ADJUSTMENT, p3(fee)));
        }

        // 5. Final Price = sum of everything so far
        BigDecimal finalPrice = rows.stream().map(QuoteRow::percent)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        rows.add(new QuoteRow("Final Price", PricingRowType.FINAL, p3(finalPrice)));

        // 6. Compensation
        BigDecimal comp = req.compensationPayerType() == CompensationPayerType.LENDER_PAID
                ? new BigDecimal("1.000") : BigDecimal.ZERO;
        rows.add(new QuoteRow("Compensation", PricingRowType.COMPENSATION, p3(comp)));

        // 7. Final After Comp
        rows.add(new QuoteRow("Final Price After Compensation", PricingRowType.FINAL_AFTER_COMP,
                p3(finalPrice.add(comp))));

        return new PriceQuote(List.copyOf(rows));
    }

    private static BigDecimal ficoLtvBucket(int fico, BigDecimal ltv) {
        boolean ltv60 = ltv.compareTo(new BigDecimal("60")) <= 0;
        boolean ltv80 = ltv.compareTo(new BigDecimal("80")) <= 0;
        if (fico >= 760) return ltv60 ? bd("0.000") : ltv80 ? bd("0.250") : bd("0.375");
        if (fico >= 700) return ltv60 ? bd("0.250") : ltv80 ? bd("0.500") : bd("0.750");
        return ltv60 ? bd("0.500") : ltv80 ? bd("1.000") : bd("1.500");
    }

    private static BigDecimal purposeAdj(LoanPurposeType purpose) {
        if (purpose == LoanPurposeType.REFINANCE) return bd("0.250");
        if (purpose == LoanPurposeType.CONSTRUCTION) return bd("0.500");
        return BigDecimal.ZERO;   // PURCHASE / OTHER / null
    }

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    private static BigDecimal p3(BigDecimal v) { return v.setScale(3, RoundingMode.HALF_UP); }
}
```

- [ ] **Step 5: Run — expect 7 PASS**

Run: `./gradlew :pricing:test -q`

- [ ] **Step 6: Commit**

```bash
git add pricing/src
git status --short   # only pricing/** staged
git commit -m "feat(pricing): PricingEnginePort + deterministic stub adapter (golden-table TDD)"
```

---

### Task 5: Request/response DTOs + @AllowedCommitmentDays constraint

**Files:**
- Create: `pricing/src/main/java/com/msfg/los/pricing/web/dto/AllowedCommitmentDays.java`
- Create: `pricing/src/main/java/com/msfg/los/pricing/web/dto/LockTermsRequest.java`
- Create: `pricing/src/main/java/com/msfg/los/pricing/web/dto/ExtendLockRequest.java`
- Create: `pricing/src/main/java/com/msfg/los/pricing/web/dto/RateChangeRequest.java`
- Create: `pricing/src/main/java/com/msfg/los/pricing/web/dto/PricingResponse.java`
- Create: `pricing/src/main/java/com/msfg/los/pricing/web/dto/PricingAdjustmentResponse.java`
- Create: `pricing/src/main/java/com/msfg/los/pricing/web/dto/LockEventResponse.java`

- [ ] **Step 1: Custom constraint** (bean validation ⇒ lands in `$.fields.commitmentDays` automatically):

```java
package com.msfg.los.pricing.web.dto;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.*;
import java.util.Set;

@Documented
@Constraint(validatedBy = AllowedCommitmentDays.Validator.class)
@Target(ElementType.RECORD_COMPONENT)
@Retention(RetentionPolicy.RUNTIME)
public @interface AllowedCommitmentDays {
    String message() default "must be one of 15, 30, 45, 60, 90";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<AllowedCommitmentDays, Integer> {
        private static final Set<Integer> ALLOWED = Set.of(15, 30, 45, 60, 90);
        @Override
        public boolean isValid(Integer value, ConstraintValidatorContext ctx) {
            return value == null || ALLOWED.contains(value);   // null is @NotNull's job
        }
    }
}
```

⚠️ If validation doesn't fire on the record component, change `@Target` to `{ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER}` — record components map annotations to fields/params depending on Hibernate Validator version. Verify via the Task 7 IT.

- [ ] **Step 2: Request DTOs** (style: `CocSubmitRequest` precedent — records + jakarta annotations):

```java
package com.msfg.los.pricing.web.dto;

import com.msfg.los.pricing.domain.CompensationPayerType;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

/** Body for control-your-price AND relock. */
public record LockTermsRequest(
        @NotNull @DecimalMin("0.125") @DecimalMax("25.000") BigDecimal rate,
        @NotNull @AllowedCommitmentDays Integer commitmentDays,
        @NotNull CompensationPayerType compensationPayerType) {}
```

```java
package com.msfg.los.pricing.web.dto;

import jakarta.validation.constraints.*;

public record ExtendLockRequest(
        @NotNull @Min(1) @Max(60) Integer additionalDays) {}
```

```java
package com.msfg.los.pricing.web.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record RateChangeRequest(
        @NotNull @DecimalMin("0.125") @DecimalMax("25.000") BigDecimal rate) {}
```

- [ ] **Step 3: Response DTOs**:

```java
package com.msfg.los.pricing.web.dto;

import com.msfg.los.pricing.domain.CompensationPayerType;
import com.msfg.los.pricing.domain.RateLockStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** The Products & Pricing grid. Lock fields are null when NOT_LOCKED. */
public record PricingResponse(
        RateLockStatus lockStatus,
        BigDecimal interestRate,           // locked rate if a lock row exists, else Loan.interestRate
        Integer commitmentDays,
        Instant lockDate,
        LocalDate currentExpiration,
        Integer extensionDaysTotal,
        CompensationPayerType compensationPayerType,
        String lockedBy,
        String interviewerEmail,
        BigDecimal totalLoanAmount,        // from qualification calc
        String exactRateType) {}           // Loan.amortizationType name, null if unset
```

```java
package com.msfg.los.pricing.web.dto;

import com.msfg.los.pricing.domain.PricingAdjustment;
import com.msfg.los.pricing.domain.PricingRowType;
import java.math.BigDecimal;

public record PricingAdjustmentResponse(
        int ordinal, String name, PricingRowType rowType,
        BigDecimal adjustmentPercent, BigDecimal dollarAmount) {

    public static PricingAdjustmentResponse from(PricingAdjustment a) {
        return new PricingAdjustmentResponse(a.getOrdinal(), a.getName(), a.getRowType(),
                a.getAdjustmentPercent(), a.getDollarAmount());
    }
}
```

```java
package com.msfg.los.pricing.web.dto;

import com.msfg.los.pricing.domain.LockAction;
import com.msfg.los.pricing.domain.LockEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record LockEventResponse(
        UUID id, LockAction action, String actor, Instant occurredAt,
        BigDecimal rate, Integer commitmentDays, LocalDate expirationDate) {

    public static LockEventResponse from(LockEvent e) {
        return new LockEventResponse(e.getId(), e.getAction(), e.getActor(), e.getOccurredAt(),
                e.getRate(), e.getCommitmentDays(), e.getExpirationDate());
    }
}
```

- [ ] **Step 4: Compile + commit**

Run: `./gradlew :pricing:compileJava -q` — BUILD SUCCESSFUL.

```bash
git add pricing/src
git commit -m "feat(pricing): request/response DTOs + AllowedCommitmentDays constraint"
```

---

### Task 6: Exceptions, PricingService reads, GET endpoints (TDD via IT)

**Files:**
- Create: `pricing/src/main/java/com/msfg/los/pricing/service/LockStateConflictException.java`
- Create: `pricing/src/main/java/com/msfg/los/pricing/service/LoanNotPriceableException.java`
- Create: `pricing/src/main/java/com/msfg/los/pricing/service/PricingService.java`
- Create: `pricing/src/main/java/com/msfg/los/pricing/web/PricingController.java`
- Test: `app/src/test/java/com/msfg/los/pricing/web/PricingControllerIT.java`

- [ ] **Step 1: Write the failing IT** (helpers copied from `app/src/test/java/com/msfg/los/qualification/web/LoanCalculationIT.java` — same `lo()`, `createLoan`, `patchLoan` shapes):

```java
package com.msfg.los.pricing.web;

import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.RequestPostProcessor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PricingControllerIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;

    static final String LO = UUID.randomUUID().toString();

    RequestPostProcessor lo() {
        return jwt().jwt(j -> j.subject(LO).claim("org_id", DEFAULT_ORG).claim("email", "lo@msfg.test"))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));
    }

    String createLoan() throws Exception {
        var res = mvc.perform(post("/api/loans").with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(LO)))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    void patchLoan(String loanId, String jsonBody) throws Exception {
        mvc.perform(patch("/api/loans/{id}", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andExpect(status().isOk());
    }

    /** Standard priceable loan: total 300000, ltv 80.000 (basis = min(375k sales, 380k appraised)), fico 745. */
    String priceableLoan() throws Exception {
        String id = createLoan();
        patchLoan(id, """
                {
                  "baseLoanAmount": 300000,
                  "interestRate": 6.5,
                  "loanTermMonths": 360,
                  "qualifyingCreditScore": 745,
                  "salesPrice": 375000,
                  "appraisedValue": 380000,
                  "amortizationType": "FIXED"
                }
                """);
        return id;
    }

    // ── Virgin reads ──────────────────────────────────────────────────────────

    @Test
    void getPricing_onUnlockedLoan_returnsNotLockedWithLoanRatePassthrough() throws Exception {
        String loanId = priceableLoan();
        mvc.perform(get("/api/loans/{id}/pricing", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lockStatus").value("NOT_LOCKED"))
                .andExpect(jsonPath("$.data.interestRate").value(6.5))
                .andExpect(jsonPath("$.data.totalLoanAmount").value(300000))
                .andExpect(jsonPath("$.data.exactRateType").value("FIXED"))
                .andExpect(jsonPath("$.data.lockDate").doesNotExist())
                .andExpect(jsonPath("$.data.lockedBy").doesNotExist())
                .andExpect(jsonPath("$.data.currentExpiration").doesNotExist());
    }

    @Test
    void getAdjustments_neverPriced_returnsEmptyList() throws Exception {
        String loanId = priceableLoan();
        mvc.perform(get("/api/loans/{id}/pricing/adjustments", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void getLockHistory_neverLocked_returnsEmptyList() throws Exception {
        String loanId = priceableLoan();
        mvc.perform(get("/api/loans/{id}/pricing/lock/history", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void getPricing_unknownLoan_returns404() throws Exception {
        mvc.perform(get("/api/loans/{id}/pricing", UUID.randomUUID()).with(lo()))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: Run — expect FAIL** (404 NOT_FOUND for the route / controller missing)

Run: `./gradlew :app:test --tests "com.msfg.los.pricing.web.PricingControllerIT" -q`

- [ ] **Step 3: Exceptions** (platform `DomainException` carries status+code — spec codes preserved):

```java
package com.msfg.los.pricing.service;

import com.msfg.los.platform.error.DomainException;
import org.springframework.http.HttpStatus;

public class LockStateConflictException extends DomainException {
    public LockStateConflictException(String message) {
        super(HttpStatus.CONFLICT, "LOCK_STATE_CONFLICT", message);
    }
}
```

```java
package com.msfg.los.pricing.service;

import com.msfg.los.platform.error.DomainException;
import org.springframework.http.HttpStatus;

public class LoanNotPriceableException extends DomainException {
    public LoanNotPriceableException(String message) {
        super(HttpStatus.CONFLICT, "LOAN_NOT_PRICEABLE", message);
    }
}
```

- [ ] **Step 4: PricingService (reads only for now)**:

```java
package com.msfg.los.pricing.service;

import com.msfg.los.loan.domain.Loan;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.pricing.domain.*;
import com.msfg.los.pricing.repo.LockEventRepository;
import com.msfg.los.pricing.repo.PricingAdjustmentRepository;
import com.msfg.los.pricing.repo.RateLockRepository;
import com.msfg.los.pricing.web.dto.PricingResponse;
import com.msfg.los.qualification.service.LoanCalculationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
public class PricingService {

    private final RateLockRepository locks;
    private final PricingAdjustmentRepository adjustments;
    private final LockEventRepository events;
    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;
    private final LoanCalculationService calculations;

    public PricingService(RateLockRepository locks, PricingAdjustmentRepository adjustments,
                          LockEventRepository events, LoanService loanService,
                          LoanAccessGuard accessGuard, LoanCalculationService calculations) {
        this.locks = locks;
        this.adjustments = adjustments;
        this.events = events;
        this.loanService = loanService;
        this.accessGuard = accessGuard;
        this.calculations = calculations;
    }

    @Transactional(readOnly = true)
    public PricingResponse view(UUID loanId) {
        Loan loan = loanService.get(loanId);
        accessGuard.assertCanAccess(loan);
        RateLock lock = locks.findByLoanId(loanId).orElse(null);
        var calc = calculations.calculate(loanId);
        String exactRateType = loan.getAmortizationType() == null ? null : loan.getAmortizationType().name();

        if (lock == null) {
            return new PricingResponse(RateLockStatus.NOT_LOCKED, loan.getInterestRate(),
                    null, null, null, null, null, null, null,
                    calc.totalLoanAmount(), exactRateType);
        }
        RateLockStatus status = RateLockStatus.effective(lock.getExpirationDate(), today());
        return new PricingResponse(status, lock.getLockedRate(),
                lock.getCommitmentDays(), lock.getLockDate(), lock.getExpirationDate(),
                lock.getExtensionDaysTotal(), lock.getCompensationPayerType(),
                lock.getLockedBy(), lock.getInterviewerEmail(),
                calc.totalLoanAmount(), exactRateType);
    }

    @Transactional(readOnly = true)
    public List<PricingAdjustment> adjustments(UUID loanId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        return adjustments.findByLoanIdOrderByOrdinalAscIdAsc(loanId);
    }

    @Transactional(readOnly = true)
    public List<LockEvent> history(UUID loanId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        return events.findByLoanIdOrderByOccurredAtAscIdAsc(loanId);
    }

    static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }
}
```

- [ ] **Step 5: Controller (GET endpoints)**:

```java
package com.msfg.los.pricing.web;

import com.msfg.los.platform.web.ApiResponse;
import com.msfg.los.pricing.service.PricingService;
import com.msfg.los.pricing.web.dto.LockEventResponse;
import com.msfg.los.pricing.web.dto.PricingAdjustmentResponse;
import com.msfg.los.pricing.web.dto.PricingResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/loans/{loanId}/pricing")
public class PricingController {

    private final PricingService service;

    public PricingController(PricingService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PricingResponse> pricing(@PathVariable UUID loanId) {
        return ApiResponse.ok(service.view(loanId));
    }

    @GetMapping("/adjustments")
    public ApiResponse<List<PricingAdjustmentResponse>> adjustments(@PathVariable UUID loanId) {
        return ApiResponse.ok(service.adjustments(loanId).stream()
                .map(PricingAdjustmentResponse::from).toList());
    }

    @GetMapping("/lock/history")
    public ApiResponse<List<LockEventResponse>> lockHistory(@PathVariable UUID loanId) {
        return ApiResponse.ok(service.history(loanId).stream()
                .map(LockEventResponse::from).toList());
    }
}
```

⚠️ Verify `ApiResponse`'s actual package (`grep -r "class ApiResponse" platform/src/main/java`) and fix the import if it differs.

- [ ] **Step 6: Run the IT — expect 4 PASS**

Run: `./gradlew :app:test --tests "com.msfg.los.pricing.web.PricingControllerIT" -q`

- [ ] **Step 7: Commit**

```bash
git add pricing/src app/src/test/java/com/msfg/los/pricing
git status --short   # only pricing/** + the new IT staged
git commit -m "feat(pricing): pricing/adjustments/history reads + GET endpoints"
```

---

### Task 7: CurrentUser.email() + control-your-price action (TDD via IT)

**Files:**
- Modify: the platform `CurrentUser` class (locate: `grep -rl "public class CurrentUser" platform/ loan-core/`)
- Modify: `pricing/src/main/java/com/msfg/los/pricing/service/PricingService.java`
- Modify: `pricing/src/main/java/com/msfg/los/pricing/web/PricingController.java`
- Test: append to `app/src/test/java/com/msfg/los/pricing/web/PricingControllerIT.java`

- [ ] **Step 1: Append the failing ITs** — golden dollars hand-computed on basis 300000 (percent/100 × 300000): Base −0.375 → −1125.00; FICO/LTV 0.500 → 1500.00; Purpose 0.000 → 0.00; Final 0.125 → 375.00; Comp 1.000 → 3000.00; FAC 1.125 → 3375.00.

```java
    String cypBody() {
        return """
                {"rate": 6.5, "commitmentDays": 30, "compensationPayerType": "LENDER_PAID"}
                """;
    }

    @Test
    void controlYourPrice_locksLoan_persistsGoldenAdjustments_andAppendsEvent() throws Exception {
        String loanId = priceableLoan();

        mvc.perform(post("/api/loans/{id}/pricing/lock/control-your-price", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON).content(cypBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lockStatus").value("LOCKED"))
                .andExpect(jsonPath("$.data.interestRate").value(6.5))
                .andExpect(jsonPath("$.data.commitmentDays").value(30))
                .andExpect(jsonPath("$.data.lockedBy").value(LO))
                .andExpect(jsonPath("$.data.interviewerEmail").value("lo@msfg.test"))
                .andExpect(jsonPath("$.data.lockDate").exists())
                .andExpect(jsonPath("$.data.currentExpiration").exists());

        mvc.perform(get("/api/loans/{id}/pricing/adjustments", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(6))
                .andExpect(jsonPath("$.data[0].name").value("Base Price"))
                .andExpect(jsonPath("$.data[0].rowType").value("BASE"))
                .andExpect(jsonPath("$.data[0].adjustmentPercent").value(-0.375))
                .andExpect(jsonPath("$.data[0].dollarAmount").value(-1125.00))
                .andExpect(jsonPath("$.data[1].name").value("FICO/LTV Adjustment"))
                .andExpect(jsonPath("$.data[1].adjustmentPercent").value(0.500))
                .andExpect(jsonPath("$.data[1].dollarAmount").value(1500.00))
                .andExpect(jsonPath("$.data[3].name").value("Final Price"))
                .andExpect(jsonPath("$.data[3].adjustmentPercent").value(0.125))
                .andExpect(jsonPath("$.data[3].dollarAmount").value(375.00))
                .andExpect(jsonPath("$.data[5].name").value("Final Price After Compensation"))
                .andExpect(jsonPath("$.data[5].dollarAmount").value(3375.00));

        mvc.perform(get("/api/loans/{id}/pricing/lock/history", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].action").value("CONTROL_YOUR_PRICE"))
                .andExpect(jsonPath("$.data[0].actor").value(LO))
                .andExpect(jsonPath("$.data[0].rate").value(6.5));
    }

    @Test
    void controlYourPrice_repriceWhileLocked_replacesAdjustments_secondEvent() throws Exception {
        String loanId = priceableLoan();
        mvc.perform(post("/api/loans/{id}/pricing/lock/control-your-price", loanId).with(lo())
                .contentType(MediaType.APPLICATION_JSON).content(cypBody())).andExpect(status().isOk());

        mvc.perform(post("/api/loans/{id}/pricing/lock/control-your-price", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rate\": 7.0, \"commitmentDays\": 15, \"compensationPayerType\": \"BORROWER_PAID\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.interestRate").value(7.0))
                .andExpect(jsonPath("$.data.commitmentDays").value(15));

        // Re-quote replaced (still 6 rows, not 12); rate 7.0/15d: Base = -0-0 = 0.000
        mvc.perform(get("/api/loans/{id}/pricing/adjustments", loanId).with(lo()))
                .andExpect(jsonPath("$.data.length()").value(6))
                .andExpect(jsonPath("$.data[0].adjustmentPercent").value(0.000));

        mvc.perform(get("/api/loans/{id}/pricing/lock/history", loanId).with(lo()))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    // ── Validation: one test per rule branch (assert $.fields.<name>) ─────────

    @Test
    void cyp_missingRate_400() throws Exception {
        String loanId = priceableLoan();
        mvc.perform(post("/api/loans/{id}/pricing/lock/control-your-price", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commitmentDays\": 30, \"compensationPayerType\": \"LENDER_PAID\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.rate").exists());
    }

    @Test
    void cyp_rateTooLow_400() throws Exception {
        String loanId = priceableLoan();
        mvc.perform(post("/api/loans/{id}/pricing/lock/control-your-price", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rate\": 0.05, \"commitmentDays\": 30, \"compensationPayerType\": \"LENDER_PAID\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.rate").exists());
    }

    @Test
    void cyp_missingCommitmentDays_400() throws Exception {
        String loanId = priceableLoan();
        mvc.perform(post("/api/loans/{id}/pricing/lock/control-your-price", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rate\": 6.5, \"compensationPayerType\": \"LENDER_PAID\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.commitmentDays").exists());
    }

    @Test
    void cyp_disallowedCommitmentDays_400() throws Exception {
        String loanId = priceableLoan();
        mvc.perform(post("/api/loans/{id}/pricing/lock/control-your-price", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rate\": 6.5, \"commitmentDays\": 17, \"compensationPayerType\": \"LENDER_PAID\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.commitmentDays").exists());
    }

    @Test
    void cyp_missingCompensationPayerType_400() throws Exception {
        String loanId = priceableLoan();
        mvc.perform(post("/api/loans/{id}/pricing/lock/control-your-price", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rate\": 6.5, \"commitmentDays\": 30}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.compensationPayerType").exists());
    }

    // ── Domain conflicts ──────────────────────────────────────────────────────

    @Test
    void cyp_loanWithoutBaseAmount_409_LOAN_NOT_PRICEABLE() throws Exception {
        String loanId = createLoan();   // no baseLoanAmount patched
        mvc.perform(post("/api/loans/{id}/pricing/lock/control-your-price", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON).content(cypBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOAN_NOT_PRICEABLE"));
    }

    @Test
    void cyp_terminalLoan_409() throws Exception {
        String loanId = priceableLoan();
        // Drive the loan to a terminal status via the status endpoint. Use the transitions
        // endpoint to find the path if a direct CANCELLED transition is rejected:
        mvc.perform(post("/api/loans/{id}/status", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CANCELLED\",\"reason\":\"test\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/loans/{id}/pricing/lock/control-your-price", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON).content(cypBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOCK_STATE_CONFLICT"));
    }
```

⚠️ Before running: check the real status-change endpoint shape (`grep -n "status" loan-core/src/main/java/com/msfg/los/loan/web/LoanController.java`) and adjust the `cyp_terminalLoan_409` POST (path + body + expected 2xx) to match — Spec 1 built it; the transition STARTED→CANCELLED is legal per the lifecycle.

- [ ] **Step 2: Run — expect new tests FAIL** (405/404 on the POST route)

Run: `./gradlew :app:test --tests "com.msfg.los.pricing.web.PricingControllerIT" -q`

- [ ] **Step 3: Add `email()` to `CurrentUser`** (in the file found by the grep; mirror `id()`'s structure):

```java
    public Optional<String> email() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            return Optional.ofNullable(jwt.getToken().getClaimAsString("email"));
        }
        return Optional.empty();
    }
```

- [ ] **Step 4: Implement CYP in `PricingService`** — add the collaborator + methods:

Add constructor params/fields: `PricingEnginePort engine, CurrentUser currentUser` (imports `com.msfg.los.pricing.port.*`, the `CurrentUser` package found in Step 3).

```java
    @Transactional
    public PricingResponse controlYourPrice(UUID loanId, LockTermsRequest req) {
        Loan loan = loadGuarded(loanId);
        assertNotTerminal(loan);
        RateLock lock = locks.findByLoanId(loanId).orElse(null);
        if (lock != null && RateLockStatus.effective(lock.getExpirationDate(), today()) == RateLockStatus.EXPIRED) {
            throw new LockStateConflictException("Lock is EXPIRED — use relock");
        }
        if (lock == null) {
            lock = new RateLock();
            lock.setLoanId(loanId);
        }
        applyTerms(lock, req);
        lock.setExtensionDaysTotal(0);
        requoteAndRecord(loan, lock, LockAction.CONTROL_YOUR_PRICE);
        return view(loanId);
    }

    private Loan loadGuarded(UUID loanId) {
        Loan loan = loanService.get(loanId);
        accessGuard.assertCanAccess(loan);
        return loan;
    }

    private void assertNotTerminal(Loan loan) {
        if (loan.getStatus().isTerminal()) {
            throw new LockStateConflictException("Loan status " + loan.getStatus() + " does not allow lock actions");
        }
    }

    private void applyTerms(RateLock lock, LockTermsRequest req) {
        Instant now = Instant.now();
        lock.setLockedRate(req.rate());
        lock.setCommitmentDays(req.commitmentDays());
        lock.setCompensationPayerType(req.compensationPayerType());
        lock.setLockDate(now);
        lock.setExpirationDate(LocalDate.ofInstant(now, ZoneOffset.UTC).plusDays(req.commitmentDays()));
        lock.setLockedBy(currentUser.id().orElse(null));
        lock.setInterviewerEmail(currentUser.email().orElse(null));
    }

    /** Quote via the port, replace the adjustment snapshot, persist lock, append audit event. */
    private void requoteAndRecord(Loan loan, RateLock lock, LockAction action) {
        var calc = calculations.calculate(loan.getId());
        if (calc.totalLoanAmount() == null) {
            throw new LoanNotPriceableException("Loan has no base loan amount — cannot price");
        }
        var quote = engine.quote(new PricingQuoteRequest(
                lock.getLockedRate(), lock.getCommitmentDays(), lock.getCompensationPayerType(),
                lock.getExtensionDaysTotal(), loan.getQualifyingCreditScore(), calc.ltv(),
                loan.getLoanPurpose(), calc.totalLoanAmount()));

        adjustments.deleteByLoanId(loan.getId());
        int ordinal = 1;
        for (QuoteRow row : quote.rows()) {
            PricingAdjustment a = new PricingAdjustment();
            a.setLoanId(loan.getId());
            a.setOrdinal(ordinal++);
            a.setName(row.name());
            a.setRowType(row.rowType());
            a.setAdjustmentPercent(row.percent());
            a.setDollarAmount(row.percent()
                    .divide(new BigDecimal("100"), 10, RoundingMode.HALF_UP)
                    .multiply(calc.totalLoanAmount())
                    .setScale(2, RoundingMode.HALF_UP));
            adjustments.save(a);
        }
        locks.save(lock);

        LockEvent e = new LockEvent();
        e.setLoanId(loan.getId());
        e.setAction(action);
        e.setActor(currentUser.id().orElse(null));
        e.setOccurredAt(Instant.now());
        e.setRate(lock.getLockedRate());
        e.setCommitmentDays(lock.getCommitmentDays());
        e.setExpirationDate(lock.getExpirationDate());
        events.save(e);
    }
```

(`Loan.getId()` — if the accessor differs, check `Loan.java`; imports: `java.math.RoundingMode`, `com.msfg.los.pricing.web.dto.LockTermsRequest`.)

- [ ] **Step 5: Controller endpoint**:

```java
    @PostMapping("/lock/control-your-price")
    public ApiResponse<PricingResponse> controlYourPrice(@PathVariable UUID loanId,
                                                         @Valid @RequestBody LockTermsRequest req) {
        return ApiResponse.ok(service.controlYourPrice(loanId, req));
    }
```

(imports: `jakarta.validation.Valid`, `com.msfg.los.pricing.web.dto.LockTermsRequest`)

- [ ] **Step 6: Run — expect ALL PricingControllerIT tests PASS** (incl. Task 6's four)

Run: `./gradlew :app:test --tests "com.msfg.los.pricing.web.PricingControllerIT" -q`

- [ ] **Step 7: Commit**

```bash
git add pricing/src app/src/test/java/com/msfg/los/pricing
git add $(grep -rl "public Optional<String> email()" platform loan-core --include="*.java")
git status --short   # pricing/** + IT + the ONE CurrentUser file
git commit -m "feat(pricing): control-your-price — price+lock, snapshot adjustments, audit event"
```

---

### Task 8: extend + rate-change actions (TDD via IT)

**Files:**
- Modify: `pricing/src/main/java/com/msfg/los/pricing/service/PricingService.java`
- Modify: `pricing/src/main/java/com/msfg/los/pricing/web/PricingController.java`
- Test: create `app/src/test/java/com/msfg/los/pricing/web/LockActionsIT.java`

- [ ] **Step 1: Failing ITs** — new class, same helpers as `PricingControllerIT` (copy `lo()`, `createLoan`, `patchLoan`, `priceableLoan`, `cypBody` verbatim into this class):

```java
package com.msfg.los.pricing.web;

// imports identical to PricingControllerIT

class LockActionsIT extends AbstractIntegrationTest {

    // ... copied helpers ...

    @Test
    void extend_addsDays_appendsExtensionFeeRow_resumsFinal() throws Exception {
        String loanId = priceableLoan();
        mvc.perform(post("/api/loans/{id}/pricing/lock/control-your-price", loanId).with(lo())
                .contentType(MediaType.APPLICATION_JSON).content(cypBody())).andExpect(status().isOk());

        String expBefore = com.jayway.jsonpath.JsonPath.read(
                mvc.perform(get("/api/loans/{id}/pricing", loanId).with(lo())).andReturn()
                        .getResponse().getContentAsString(), "$.data.currentExpiration");

        mvc.perform(post("/api/loans/{id}/pricing/lock/extend", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"additionalDays\": 15}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lockStatus").value("LOCKED"))
                .andExpect(jsonPath("$.data.extensionDaysTotal").value(15))
                .andExpect(jsonPath("$.data.currentExpiration")
                        .value(java.time.LocalDate.parse(expBefore).plusDays(15).toString()));

        // 7 rows now; Extension Fee 15*0.020=0.300 => $900.00 ; Final 0.425 => $1275.00 ; FAC 1.425 => $4275.00
        mvc.perform(get("/api/loans/{id}/pricing/adjustments", loanId).with(lo()))
                .andExpect(jsonPath("$.data.length()").value(7))
                .andExpect(jsonPath("$.data[3].name").value("Extension Fee (15 days)"))
                .andExpect(jsonPath("$.data[3].adjustmentPercent").value(0.300))
                .andExpect(jsonPath("$.data[3].dollarAmount").value(900.00))
                .andExpect(jsonPath("$.data[4].adjustmentPercent").value(0.425))
                .andExpect(jsonPath("$.data[6].dollarAmount").value(4275.00));

        mvc.perform(get("/api/loans/{id}/pricing/lock/history", loanId).with(lo()))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[1].action").value("EXTEND"));
    }

    @Test
    void rateChange_requotes_keepsExpirationAndLockDate() throws Exception {
        String loanId = priceableLoan();
        mvc.perform(post("/api/loans/{id}/pricing/lock/control-your-price", loanId).with(lo())
                .contentType(MediaType.APPLICATION_JSON).content(cypBody())).andExpect(status().isOk());

        String before = mvc.perform(get("/api/loans/{id}/pricing", loanId).with(lo()))
                .andReturn().getResponse().getContentAsString();
        String expBefore = com.jayway.jsonpath.JsonPath.read(before, "$.data.currentExpiration");
        String lockDateBefore = com.jayway.jsonpath.JsonPath.read(before, "$.data.lockDate");

        // 7.0 @ 30d: Base = -((7-7)*0.5) - 0.125 = -0.125 => -$375.00 ; Final 0.375 ; FAC 1.375
        mvc.perform(post("/api/loans/{id}/pricing/lock/rate-change", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rate\": 7.0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.interestRate").value(7.0))
                .andExpect(jsonPath("$.data.currentExpiration").value(expBefore))
                .andExpect(jsonPath("$.data.lockDate").value(lockDateBefore));

        mvc.perform(get("/api/loans/{id}/pricing/adjustments", loanId).with(lo()))
                .andExpect(jsonPath("$.data[0].adjustmentPercent").value(-0.125))
                .andExpect(jsonPath("$.data[0].dollarAmount").value(-375.00))
                .andExpect(jsonPath("$.data[3].adjustmentPercent").value(0.375));
    }

    @Test
    void extend_withoutLock_409() throws Exception {
        String loanId = priceableLoan();
        mvc.perform(post("/api/loans/{id}/pricing/lock/extend", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"additionalDays\": 15}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOCK_STATE_CONFLICT"));
    }

    @Test
    void rateChange_withoutLock_409() throws Exception {
        String loanId = priceableLoan();
        mvc.perform(post("/api/loans/{id}/pricing/lock/rate-change", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rate\": 7.0}"))
                .andExpect(status().isConflict());
    }

    @Test
    void extend_invalidAdditionalDays_400() throws Exception {
        String loanId = priceableLoan();
        mvc.perform(post("/api/loans/{id}/pricing/lock/control-your-price", loanId).with(lo())
                .contentType(MediaType.APPLICATION_JSON).content(cypBody())).andExpect(status().isOk());
        mvc.perform(post("/api/loans/{id}/pricing/lock/extend", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"additionalDays\": 0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.additionalDays").exists());
    }
}
```

- [ ] **Step 2: Run — expect FAIL.** `./gradlew :app:test --tests "com.msfg.los.pricing.web.LockActionsIT" -q`

- [ ] **Step 3: Service methods**:

```java
    @Transactional
    public PricingResponse extend(UUID loanId, ExtendLockRequest req) {
        Loan loan = loadGuarded(loanId);
        assertNotTerminal(loan);
        RateLock lock = requireLockInState(loanId, RateLockStatus.LOCKED, "extend");
        lock.setExpirationDate(lock.getExpirationDate().plusDays(req.additionalDays()));
        lock.setExtensionDaysTotal(lock.getExtensionDaysTotal() + req.additionalDays());
        requoteAndRecord(loan, lock, LockAction.EXTEND);
        return view(loanId);
    }

    @Transactional
    public PricingResponse rateChange(UUID loanId, RateChangeRequest req) {
        Loan loan = loadGuarded(loanId);
        assertNotTerminal(loan);
        RateLock lock = requireLockInState(loanId, RateLockStatus.LOCKED, "rate-change");
        lock.setLockedRate(req.rate());
        requoteAndRecord(loan, lock, LockAction.RATE_CHANGE);
        return view(loanId);
    }

    private RateLock requireLockInState(UUID loanId, RateLockStatus required, String action) {
        RateLock lock = locks.findByLoanId(loanId)
                .orElseThrow(() -> new LockStateConflictException(
                        "Loan is NOT_LOCKED — cannot " + action));
        RateLockStatus actual = RateLockStatus.effective(lock.getExpirationDate(), today());
        if (actual != required) {
            throw new LockStateConflictException(
                    "Lock is " + actual + " — cannot " + action);
        }
        return lock;
    }
```

- [ ] **Step 4: Controller endpoints**:

```java
    @PostMapping("/lock/extend")
    public ApiResponse<PricingResponse> extend(@PathVariable UUID loanId,
                                               @Valid @RequestBody ExtendLockRequest req) {
        return ApiResponse.ok(service.extend(loanId, req));
    }

    @PostMapping("/lock/rate-change")
    public ApiResponse<PricingResponse> rateChange(@PathVariable UUID loanId,
                                                   @Valid @RequestBody RateChangeRequest req) {
        return ApiResponse.ok(service.rateChange(loanId, req));
    }
```

- [ ] **Step 5: Run — expect PASS.** `./gradlew :app:test --tests "com.msfg.los.pricing.web.LockActionsIT" -q`

- [ ] **Step 6: Commit**

```bash
git add pricing/src app/src/test/java/com/msfg/los/pricing
git commit -m "feat(pricing): extend + rate-change lock actions"
```

---

### Task 9: relock + remaining state-matrix (TDD via IT)

**Files:**
- Modify: `pricing/src/main/java/com/msfg/los/pricing/service/PricingService.java`
- Modify: `pricing/src/main/java/com/msfg/los/pricing/web/PricingController.java`
- Test: append to `app/src/test/java/com/msfg/los/pricing/web/LockActionsIT.java`

- [ ] **Step 1: Failing ITs** — force expiration with raw SQL (`JdbcTemplate` bypasses Hibernate/`@TenantId`; owner connection bypasses RLS — fine in tests):

```java
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    void forceExpired(String loanId) {
        jdbc.update("update rate_lock set expiration_date = current_date - 1 where loan_id = ?::uuid", loanId);
    }

    @Test
    void expiredLock_readsAsExpired_andRelockResetsIt() throws Exception {
        String loanId = priceableLoan();
        mvc.perform(post("/api/loans/{id}/pricing/lock/control-your-price", loanId).with(lo())
                .contentType(MediaType.APPLICATION_JSON).content(cypBody())).andExpect(status().isOk());
        // extend so extensionDaysTotal is nonzero, then expire
        mvc.perform(post("/api/loans/{id}/pricing/lock/extend", loanId).with(lo())
                .contentType(MediaType.APPLICATION_JSON).content("{\"additionalDays\": 15}")).andExpect(status().isOk());
        forceExpired(loanId);

        mvc.perform(get("/api/loans/{id}/pricing", loanId).with(lo()))
                .andExpect(jsonPath("$.data.lockStatus").value("EXPIRED"));

        mvc.perform(post("/api/loans/{id}/pricing/lock/relock", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rate\": 6.75, \"commitmentDays\": 15, \"compensationPayerType\": \"LENDER_PAID\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lockStatus").value("LOCKED"))
                .andExpect(jsonPath("$.data.interestRate").value(6.75))
                .andExpect(jsonPath("$.data.extensionDaysTotal").value(0));

        // extension row gone after relock (6 rows again)
        mvc.perform(get("/api/loans/{id}/pricing/adjustments", loanId).with(lo()))
                .andExpect(jsonPath("$.data.length()").value(6));

        mvc.perform(get("/api/loans/{id}/pricing/lock/history", loanId).with(lo()))
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[2].action").value("RELOCK"));
    }

    @Test
    void relock_onActiveLock_409() throws Exception {
        String loanId = priceableLoan();
        mvc.perform(post("/api/loans/{id}/pricing/lock/control-your-price", loanId).with(lo())
                .contentType(MediaType.APPLICATION_JSON).content(cypBody())).andExpect(status().isOk());
        mvc.perform(post("/api/loans/{id}/pricing/lock/relock", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON).content(cypBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOCK_STATE_CONFLICT"));
    }

    @Test
    void relock_neverLocked_409() throws Exception {
        String loanId = priceableLoan();
        mvc.perform(post("/api/loans/{id}/pricing/lock/relock", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON).content(cypBody()))
                .andExpect(status().isConflict());
    }

    @Test
    void expiredLock_blocksExtendRateChangeAndCyp() throws Exception {
        String loanId = priceableLoan();
        mvc.perform(post("/api/loans/{id}/pricing/lock/control-your-price", loanId).with(lo())
                .contentType(MediaType.APPLICATION_JSON).content(cypBody())).andExpect(status().isOk());
        forceExpired(loanId);

        mvc.perform(post("/api/loans/{id}/pricing/lock/extend", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"additionalDays\": 15}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/loans/{id}/pricing/lock/rate-change", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rate\": 7.0}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/loans/{id}/pricing/lock/control-your-price", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON).content(cypBody()))
                .andExpect(status().isConflict());
    }
```

- [ ] **Step 2: Run — expect FAIL.** `./gradlew :app:test --tests "com.msfg.los.pricing.web.LockActionsIT" -q`

- [ ] **Step 3: Service relock**:

```java
    @Transactional
    public PricingResponse relock(UUID loanId, LockTermsRequest req) {
        Loan loan = loadGuarded(loanId);
        assertNotTerminal(loan);
        RateLock lock = requireLockInState(loanId, RateLockStatus.EXPIRED, "relock");
        applyTerms(lock, req);
        lock.setExtensionDaysTotal(0);
        requoteAndRecord(loan, lock, LockAction.RELOCK);
        return view(loanId);
    }
```

- [ ] **Step 4: Controller**:

```java
    @PostMapping("/lock/relock")
    public ApiResponse<PricingResponse> relock(@PathVariable UUID loanId,
                                               @Valid @RequestBody LockTermsRequest req) {
        return ApiResponse.ok(service.relock(loanId, req));
    }
```

- [ ] **Step 5: Run — expect ALL LockActionsIT PASS.**

- [ ] **Step 6: Commit**

```bash
git add pricing/src app/src/test/java/com/msfg/los/pricing
git commit -m "feat(pricing): relock + expired-state action matrix"
```

---

### Task 10: Cross-tenant isolation IT

**Files:**
- Test: append to `app/src/test/java/com/msfg/los/pricing/web/PricingControllerIT.java`

- [ ] **Step 1: Write the failing-or-passing test** (it should pass already if `loanService.get` discipline held — it documents the guarantee):

```java
    @Test
    void crossTenant_foreignOrgJwt_getsNotFoundOnReadsAndActions() throws Exception {
        String loanId = priceableLoan();
        mvc.perform(post("/api/loans/{id}/pricing/lock/control-your-price", loanId).with(lo())
                .contentType(MediaType.APPLICATION_JSON).content(cypBody())).andExpect(status().isOk());

        RequestPostProcessor foreign = jwt()
                .jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("org_id", "00000000-0000-0000-0000-0000000000bb"))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));

        mvc.perform(get("/api/loans/{id}/pricing", loanId).with(foreign))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/loans/{id}/pricing/adjustments", loanId).with(foreign))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/loans/{id}/pricing/lock/history", loanId).with(foreign))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/loans/{id}/pricing/lock/extend", loanId).with(foreign)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"additionalDays\": 15}"))
                .andExpect(status().isNotFound());
    }
```

⚠️ Check how other cross-tenant ITs register the second org (`grep -rn "0000000000bb" app/src/test` — if org `…bb` must exist in the `organization` table first, copy that setup line; coc/fees ITs have the precedent).

- [ ] **Step 2: Run; if it fails on a leak, FIX THE SERVICE (`findByIdAndOrgId` discipline), not the test.**

Run: `./gradlew :app:test --tests "com.msfg.los.pricing.web.PricingControllerIT" -q`

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/msfg/los/pricing
git commit -m "test(pricing): cross-tenant isolation coverage"
```

---

### Task 11: documents seam — LOCK_CONFIRMATION + storeGenerated refactor

**Files:**
- Modify: `documents/src/main/java/com/msfg/los/documents/domain/DocumentType.java`
- Modify: `documents/src/main/java/com/msfg/los/documents/service/DocumentService.java`

- [ ] **Step 1: Add the enum value** (additive — append before `OTHER`):

```java
public enum DocumentType {
    PRE_APPROVAL,
    INVOICE,
    APPRAISAL,
    CREDIT_REPORT,
    ASSET_STATEMENT,
    INCOME_DOC,
    INSURANCE,
    CONDITION,
    LOCK_CONFIRMATION,
    OTHER
}
```

- [ ] **Step 2: Extract `storeGenerated` in `DocumentService`** and refactor `generatePreApproval` to delegate:

```java
    /** Store a server-generated artifact (letters, confirmations) behind the storage port. */
    @Transactional
    public Document storeGenerated(UUID loanId, DocumentType type, String category,
                                   String fileName, String contentType, byte[] bytes) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        Document doc = new Document();
        doc.setLoanId(loanId);
        doc.setDocumentType(type);
        doc.setCategory(category);
        doc.setFileName(fileName);
        doc.setContentType(contentType);
        doc.setSizeBytes((long) bytes.length);
        doc.setStorageKey(UUID.randomUUID().toString());
        documents.save(doc);
        port.store(doc.getStorageKey(), bytes, doc.getContentType());
        return doc;
    }

    @Transactional
    public Document generatePreApproval(UUID loanId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        var loan = loanService.get(loanId);
        String name = borrowerNameResolver.primaryBorrowerNamesByLoanIds(List.of(loanId)).get(loanId);
        String html = generator.generate(loan, name);
        return storeGenerated(loanId, DocumentType.PRE_APPROVAL, null,
                "pre-approval-" + loan.getLoanNumber() + ".html", "text/html",
                html.getBytes(StandardCharsets.UTF_8));
    }
```

(Keep every existing field-setting identical to the current body — the double `assertCanAccess` is harmless.)

- [ ] **Step 3: Regression-run the documents tests**

Run: `./gradlew :app:test --tests "com.msfg.los.documents.*" -q`
Expected: ALL PASS (pre-approval behavior unchanged).

- [ ] **Step 4: Commit**

```bash
git add documents/src/main/java/com/msfg/los/documents/domain/DocumentType.java documents/src/main/java/com/msfg/los/documents/service/DocumentService.java
git commit -m "feat(documents): LOCK_CONFIRMATION type + storeGenerated seam (pre-approval refactored, additive)"
```

---

### Task 12: Lock-confirmation letter (TDD via IT)

**Files:**
- Create: `pricing/src/main/java/com/msfg/los/pricing/service/LockConfirmationGenerator.java`
- Modify: `pricing/src/main/java/com/msfg/los/pricing/service/PricingService.java`
- Modify: `pricing/src/main/java/com/msfg/los/pricing/web/PricingController.java`
- Test: create `app/src/test/java/com/msfg/los/pricing/web/LockConfirmationIT.java`

- [ ] **Step 1: Failing IT** (same copied helpers):

```java
package com.msfg.los.pricing.web;

// imports as in PricingControllerIT

class LockConfirmationIT extends AbstractIntegrationTest {

    // ... copied helpers: lo(), createLoan(), patchLoan(), priceableLoan(), cypBody() ...

    @Test
    void generateLockConfirmation_storesListableDownloadableHtml() throws Exception {
        String loanId = priceableLoan();
        mvc.perform(post("/api/loans/{id}/pricing/lock/control-your-price", loanId).with(lo())
                .contentType(MediaType.APPLICATION_JSON).content(cypBody())).andExpect(status().isOk());

        var res = mvc.perform(post("/api/loans/{id}/pricing/lock-confirmation", loanId).with(lo()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.documentType").value("LOCK_CONFIRMATION"))
                .andExpect(jsonPath("$.data.contentType").value("text/html"))
                .andReturn();
        String docId = com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");

        mvc.perform(get("/api/loans/{id}/documents", loanId).with(lo())
                        .param("type", "LOCK_CONFIRMATION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value(docId));

        var download = mvc.perform(get("/api/loans/{id}/documents/{docId}/content", loanId, docId).with(lo()))
                .andExpect(status().isOk()).andReturn();
        String html = download.getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(html).contains("6.500").contains("Lock Confirmation");
    }

    @Test
    void generateLockConfirmation_unlocked_409() throws Exception {
        String loanId = priceableLoan();
        mvc.perform(post("/api/loans/{id}/pricing/lock-confirmation", loanId).with(lo()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOCK_STATE_CONFLICT"));
    }
}
```

⚠️ Check the documents list response JSON shape first (`$.data.content[0]` assumes a paged envelope — `grep -n "content" app/src/test/java/com/msfg/los/documents/web/DocumentControllerIT.java` and copy its jsonPath style).

- [ ] **Step 2: Run — expect FAIL.** `./gradlew :app:test --tests "com.msfg.los.pricing.web.LockConfirmationIT" -q`

- [ ] **Step 3: Generator** (mirror `PreApprovalLetterGenerator`'s plain-HTML style — look at it once for the document skeleton/escaping helper and follow it):

```java
package com.msfg.los.pricing.service;

import com.msfg.los.loan.domain.Loan;
import com.msfg.los.pricing.domain.PricingAdjustment;
import com.msfg.los.pricing.domain.RateLock;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/** Renders the Lock Confirmation as self-contained HTML (PDF is a later milestone). */
@Component
public class LockConfirmationGenerator {

    public String generate(Loan loan, RateLock lock, List<PricingAdjustment> adjustments) {
        StringBuilder rows = new StringBuilder();
        for (PricingAdjustment a : adjustments) {
            rows.append("<tr><td>").append(a.getName())
                .append("</td><td style=\"text-align:right\">").append(a.getAdjustmentPercent())
                .append("</td><td style=\"text-align:right\">").append(a.getDollarAmount())
                .append("</td></tr>");
        }
        String term = loan.getLoanTermMonths() == null ? "" : (loan.getLoanTermMonths() / 12) + " Year";
        String product = (loan.getMortgageType() == null ? "" : loan.getMortgageType().name() + " ") + term;
        return """
                <!doctype html><html><head><meta charset="utf-8"><title>Lock Confirmation</title></head>
                <body style="font-family:sans-serif">
                <h1>Lock Confirmation</h1>
                <p>Loan %s — %s</p>
                <table>
                <tr><td>Lock Status</td><td>Locked</td></tr>
                <tr><td>Interest Rate</td><td>%s</td></tr>
                <tr><td>Commitment Period</td><td>%s Day Lock</td></tr>
                <tr><td>Lock Date</td><td>%s</td></tr>
                <tr><td>Current Expiration</td><td>%s</td></tr>
                <tr><td>Compensation Payer</td><td>%s</td></tr>
                <tr><td>Locked By</td><td>%s</td></tr>
                </table>
                <h2>Pricing Breakdown</h2>
                <table><tr><th>Adjustment Name</th><th>Adjustment %%</th><th>Dollar Amount</th></tr>%s</table>
                <p>Generated %s</p>
                </body></html>
                """.formatted(
                loan.getLoanNumber(), product,
                lock.getLockedRate(), lock.getCommitmentDays(), lock.getLockDate(),
                lock.getExpirationDate(), lock.getCompensationPayerType(),
                lock.getLockedBy() == null ? "" : lock.getLockedBy(),
                rows, LocalDate.now(ZoneOffset.UTC));
    }
}
```

- [ ] **Step 4: Service method** (inject `DocumentService documentService, LockConfirmationGenerator confirmationGenerator` via constructor):

```java
    @Transactional
    public com.msfg.los.documents.domain.Document generateLockConfirmation(UUID loanId) {
        Loan loan = loadGuarded(loanId);
        RateLock lock = requireLockInState(loanId, RateLockStatus.LOCKED, "generate a lock confirmation");
        var rows = adjustments.findByLoanIdOrderByOrdinalAscIdAsc(loanId);
        String html = confirmationGenerator.generate(loan, lock, rows);
        String fileName = "lock-confirmation-" + loan.getLoanNumber() + "-"
                + java.time.format.DateTimeFormatter.BASIC_ISO_DATE.format(today()) + ".html";
        return documentService.storeGenerated(loanId, com.msfg.los.documents.domain.DocumentType.LOCK_CONFIRMATION,
                "PRICING", fileName, "text/html", html.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
```

- [ ] **Step 5: Controller endpoint** (201 like pre-approval; reuse documents' `DocumentResponse.from`):

```java
    @PostMapping("/lock-confirmation")
    public org.springframework.http.ResponseEntity<ApiResponse<com.msfg.los.documents.web.dto.DocumentResponse>>
            lockConfirmation(@PathVariable UUID loanId) {
        var doc = service.generateLockConfirmation(loanId);
        return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                .body(ApiResponse.ok(com.msfg.los.documents.web.dto.DocumentResponse.from(doc)));
    }
```

- [ ] **Step 6: Run — expect PASS.** `./gradlew :app:test --tests "com.msfg.los.pricing.web.LockConfirmationIT" -q`

- [ ] **Step 7: Commit**

```bash
git add pricing/src app/src/test/java/com/msfg/los/pricing
git commit -m "feat(pricing): lock-confirmation letter via documents storeGenerated"
```

---

### Task 13: RLS IT for the three tables

**Files:**
- Test: create `app/src/test/java/com/msfg/los/pricing/PricingRlsIT.java`

- [ ] **Step 1: Copy `app/src/test/java/com/msfg/los/fees/FeesRlsIT.java` wholesale** as the template (same `set role app_user`, `setOrg`, fail-closed RESET checks) and adapt to insert one row per new table:

- `rate_lock`: `insert into rate_lock (id,version,org_id,loan_id,locked_rate,commitment_days,lock_date,expiration_date,extension_days_total,compensation_payer_type) values (?,0,?::uuid,?,6.500,30,now(),current_date+30,0,'LENDER_PAID')`
- `pricing_adjustment`: `insert into pricing_adjustment (id,version,org_id,loan_id,ordinal,name,row_type,adjustment_percent,dollar_amount) values (?,0,?::uuid,?,1,'Base Price','BASE',-0.375,-1125.00)`
- `lock_event`: `insert into lock_event (id,version,org_id,loan_id,action,occurred_at,rate,commitment_days,expiration_date) values (?,0,?::uuid,?,'CONTROL_YOUR_PRICE',now(),6.500,30,current_date+30)`

Assert for each table: cross-org count is 0; RESET GUC ⇒ count 0 (fail-closed).

- [ ] **Step 2: Append-only grant check for `lock_event`** — as `app_user`, UPDATE and DELETE must be denied:

```java
    @Test
    void lockEvent_isAppendOnly_forAppUser() throws Exception {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(true);
            try (var st = c.createStatement()) { st.execute("set role app_user"); }
            setOrg(c, ORG_X);
            // (insert one lock_event row for ORG_X first, as in Step 1)
            try (var st = c.createStatement()) {
                org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> st.execute("update lock_event set actor = 'x'"))
                        .hasMessageContaining("permission denied");
                org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> st.execute("delete from lock_event"))
                        .hasMessageContaining("permission denied");
            }
        }
    }
```

(Match FeesRlsIT's exact bootstrapping: how it obtains `ds`, creates the two orgs and loans — copy those blocks verbatim.)

- [ ] **Step 3: Run — expect PASS.** `./gradlew :app:test --tests "com.msfg.los.pricing.PricingRlsIT" -q`

- [ ] **Step 4: Commit**

```bash
git add app/src/test/java/com/msfg/los/pricing
git commit -m "test(pricing): RLS isolation + fail-closed + lock_event append-only grants"
```

---

### Task 14: Full build + OpenAPI guard

- [ ] **Step 1: Full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, ~282 tests (252 + ~30 new), zero failures. ⚠️ The unrelated dirty working-tree files participate in compilation — if a failure traces to `reo/coc/platform/qualification` modified files, it is NOT yours: report it, don't "fix" their files.

- [ ] **Step 2: Confirm the OpenAPI doc carries the new paths**

Run: `./gradlew :app:test --tests "com.msfg.los.openapi.OpenApiDocsIT" -q`
Expected: PASS.

- [ ] **Step 3: Duplicate-simple-name sweep** (springdoc collision guard):

Run: `for n in PricingResponse PricingAdjustmentResponse LockEventResponse LockTermsRequest ExtendLockRequest RateChangeRequest RateLockStatus LockAction CompensationPayerType PricingRowType; do c=$(grep -rl "\b\(class\|enum\|record\|interface\) $n\b" --include="*.java" . | wc -l); [ "$c" -gt 1 ] && echo "DUPLICATE: $n"; done; echo done`
Expected: just `done` (no DUPLICATE lines).

- [ ] **Step 4: Final commit if anything moved; otherwise no-op.**

---

## Post-merge protocol (NOT part of this plan's tasks — runs at finish-branch time)
1. `--no-ff` merge to `main` after 2-stage review + opus pass (money math) — **then run a full build on the merged tree** (main may have advanced).
2. Update `docs/frontend-integration.md` (pricing endpoints section) + ROADMAP current-status banner.
3. Append dated reply to `../msfg-suite-web/docs/HANDOFF-FROM-BACKEND.md` (the only FE-repo file we touch).
4. Restart local backend (`docker compose up -d` + detached bootRun) so the FE can `npm run gen:api`; verify new paths in `/v3/api-docs`.
