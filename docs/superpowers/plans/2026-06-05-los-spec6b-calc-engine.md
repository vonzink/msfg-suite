# LOS Spec 6B — Qualification Calc Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development + test-driven-development.
> This is **precision-critical math** — the spec's formulas are normative. TDD the `MortgageMath` helper hard.

**Goal:** A read-only `GET /api/loans/{loanId}/calculations` that computes LTV/CLTV/TLTV, P&I, proposed housing,
net rental, and DTI front/back from existing inputs. No new table.

**Architecture:** New `qualification` module. `MortgageMath` = pure static formula helper (unit-tested in
isolation). `LoanCalculationService` orchestrates: reads `LoanService.get` (§4 + subject property),
`IncomeSummaryService.summarize` (total income), `LiabilitySummaryService.summarize` (dtiMonthlyPayments),
`RealEstateOwnedRepository.findByLoanIdOrderByOrdinalAsc` (net rental), applies the spec formulas with null-safe
edge handling.

**Spec (NORMATIVE — read it):** `docs/specs/2026-06-05-los-spec6b-calc-engine.md`

## File Structure
```
settings.gradle.kts                          (modify: add "qualification")
qualification/build.gradle.kts               (create)
app/build.gradle.kts                         (modify: implementation(project(":qualification")))
qualification/src/main/java/com/msfg/los/qualification/
  math/MortgageMath.java
  service/LoanCalculationService.java
  web/LoanCalculationController.java
  web/dto/LoanCalculationResponse.java
qualification/src/test/java/com/msfg/los/qualification/math/MortgageMathTest.java   (unit — TDD)
app/src/test/java/com/msfg/los/qualification/web/LoanCalculationIT.java             (crown jewel + edges)
```

## qualification/build.gradle.kts
```kotlin
dependencies {
    implementation(project(":platform"))
    implementation(project(":loan-core"))
    implementation(project(":parties"))
    implementation(project(":income"))
    implementation(project(":financials"))
    implementation(project(":reo"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
}
```

---

## Task 0: Scaffold the `qualification` module
- [ ] settings.gradle.kts → add `"qualification"`. Create `qualification/build.gradle.kts` (above). `app/build.gradle.kts` → `implementation(project(":qualification"))`.
- [ ] `./gradlew :qualification:classes :app:compileJava` → SUCCESS. Commit `chore(qualification): scaffold qualification module`.

## Task 1: `MortgageMath` helper (TDD — the precision core)
**Files:** Create `qualification/.../math/MortgageMath.java`. **Test FIRST:** `qualification/src/test/java/com/msfg/los/qualification/math/MortgageMathTest.java`.

- [ ] **Step 1 — failing tests** (hand-computed; assert to the cent / scale 3):
```java
package com.msfg.los.qualification.math;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;
class MortgageMathTest {
    @Test void monthlyPI_standard() {
        BigDecimal pi = MortgageMath.monthlyPrincipalInterest(new BigDecimal("300000"), new BigDecimal("6.5"), 360);
        assertThat(pi).isEqualByComparingTo("1896.20");   // 300k @ 6.5% / 30yr
    }
    @Test void monthlyPI_zeroRate() {
        BigDecimal pi = MortgageMath.monthlyPrincipalInterest(new BigDecimal("360000"), BigDecimal.ZERO, 360);
        assertThat(pi).isEqualByComparingTo("1000.00");   // principal / term
    }
    @Test void percentRatio_basic() {
        assertThat(MortgageMath.percentRatio(new BigDecimal("2506.20"), new BigDecimal("9300")))
            .isEqualByComparingTo("26.948");
    }
    @Test void percentRatio_nullOrZeroDenominator() {
        assertThat(MortgageMath.percentRatio(new BigDecimal("100"), BigDecimal.ZERO)).isNull();
        assertThat(MortgageMath.percentRatio(new BigDecimal("100"), null)).isNull();
        assertThat(MortgageMath.percentRatio(null, new BigDecimal("100"))).isNull();
    }
    @Test void money_rounds() {
        assertThat(MortgageMath.money(new BigDecimal("1.005"))).isEqualByComparingTo("1.01"); // HALF_UP
        assertThat(MortgageMath.money(null)).isNull();
    }
}
```
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3 — implement** `MortgageMath.java`:
```java
package com.msfg.los.qualification.math;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/** Pure mortgage-math helpers. Currency → scale 2 HALF_UP; ratios → percent scale 3 HALF_UP. */
public final class MortgageMath {
    private MortgageMath() {}

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal RATE_DIVISOR = new BigDecimal("1200"); // /100 (percent) /12 (months)

    /** Monthly principal+interest. Args must be non-null and term &gt; 0 (caller guards). */
    public static BigDecimal monthlyPrincipalInterest(BigDecimal principal, BigDecimal annualRatePercent, int termMonths) {
        BigDecimal c = annualRatePercent.divide(RATE_DIVISOR, MC);            // monthly rate (decimal)
        if (c.signum() == 0) {
            return principal.divide(BigDecimal.valueOf(termMonths), 2, RoundingMode.HALF_UP);
        }
        BigDecimal pow = BigDecimal.ONE.add(c).pow(termMonths, MC);           // (1+c)^n
        BigDecimal numerator = principal.multiply(c, MC).multiply(pow, MC);
        BigDecimal denominator = pow.subtract(BigDecimal.ONE);
        return numerator.divide(denominator, 2, RoundingMode.HALF_UP);
    }

    /** numerator/denominator × 100, scale 3 HALF_UP. null if either null or denominator == 0. */
    public static BigDecimal percentRatio(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) return null;
        return numerator.divide(denominator, 10, RoundingMode.HALF_UP).multiply(HUNDRED).setScale(3, RoundingMode.HALF_UP);
    }

    /** Currency scale 2 HALF_UP; null-safe. */
    public static BigDecimal money(BigDecimal x) {
        return x == null ? null : x.setScale(2, RoundingMode.HALF_UP);
    }

    /** null → ZERO (for additive aggregation). */
    public static BigDecimal nz(BigDecimal x) { return x == null ? BigDecimal.ZERO : x; }
}
```
- [ ] **Step 4:** Run `./gradlew :qualification:test --tests "*MortgageMathTest"` → PASS (verify `monthlyPI_standard` truly equals `1896.20` — if your hand value differs, RECOMPUTE and fix the assertion to the mathematically exact cent; do not weaken the test). Commit `feat(qualification): MortgageMath (amortization, percent ratio, rounding) + unit tests`.

## Task 2: `LoanCalculationService` + DTO + controller
**Files:** `service/LoanCalculationService.java`, `web/dto/LoanCalculationResponse.java`, `web/LoanCalculationController.java`.

- [ ] **DTO** `LoanCalculationResponse` (record) — every derived figure + the inputs used (transparency). Suggested fields (all `BigDecimal` unless noted):
  `totalLoanAmount, ltvBasis, ltv, cltv, tltv, monthlyPrincipalInterest, proposedHousingExpense,
  presentHousingExpense, housingExpenseDelta, baseMonthlyIncome, netRentalIncome, totalMonthlyIncome,
  dtiLiabilityPayments, netRentalDebt, totalMonthlyDebts, frontEndDti, backEndDti`,
  plus echoed inputs `baseLoanAmount, secondLoanAmount, financedFeesAmount, interestRate, Integer loanTermMonths,
  loanPurpose (String)`. No `from` — the service builds it.
- [ ] **`LoanCalculationService`** — `@Service`, inject `LoanService loanService, LoanAccessGuard accessGuard,
  IncomeSummaryService incomeSummary, LiabilitySummaryService liabilitySummary, RealEstateOwnedRepository reoRepo`
  (+ optionally `BorrowerRepository`/`BorrowerAddressRepository` for present housing — best-effort, may be null).
  Method `@Transactional(readOnly = true) LoanCalculationResponse calculate(UUID loanId)`:
  1. `Loan loan = loanService.get(loanId);` `accessGuard.assertCanAccess(loan);` (404 cross-org).
  2. Pull §4 inputs off `loan` + `loan.getSubjectProperty()`.
  3. **Implement EXACTLY the spec's normative formulas (§"Normative formulas (implement EXACTLY)") + the edge-case
     table** — use `MortgageMath.monthlyPrincipalInterest/percentRatio/money/nz`. Key points:
     - `totalLoanAmount` = `money(nz(base) + nz(financed))` but **null if base null**.
     - `ltvBasis`: PURCHASE → lesser of salesPrice/appraised (non-null fallback); else appraised (fallback estimatedValue).
     - `ltv = percentRatio(baseLoanAmount, ltvBasis)`; `cltv = percentRatio(nz(base)+nz(second), ltvBasis)`; `tltv = cltv`.
     - `monthlyPrincipalInterest`: **null** if base/interestRate/loanTermMonths null or term ≤ 0; else `MortgageMath.monthlyPrincipalInterest(...)`.
     - `proposedHousingExpense`: **null if P&I null**; else `money(PI + nz(taxes) + nz(hazIns) + nz(hoa) + nz(mi))`.
     - net rental: per REO row `net = 0.75×nz(gross) − nz(mortgagePayment)`; `netRentalIncome = Σ max(net,0)`, `netRentalDebt = Σ max(−net,0)` (money scale 2).
     - `baseMonthlyIncome = incomeSummary.summarize(loanId).totalMonthlyIncome()`; `totalMonthlyIncome = base + netRentalIncome` (null only if base null — base is non-null ZERO from the summary, so it's safe).
     - `dtiLiabilityPayments = liabilitySummary.summarize(loanId).dtiMonthlyPayments()`; `totalMonthlyDebts = dtiLiabilityPayments + netRentalDebt`.
     - `frontEndDti = percentRatio(proposedHousingExpense, totalMonthlyIncome)`; `backEndDti = percentRatio(nz(proposedHousingExpense)+totalMonthlyDebts, totalMonthlyIncome)` — but **null if proposedHousingExpense null** (don't report a DTI with no housing); and percentRatio already returns null if income 0.
     - `presentHousingExpense`: best-effort primary-borrower PRESENT-address `rentAmount` (or null); `housingExpenseDelta = proposed − present` (null-safe).
  4. Build + return the response (all currency via `money(...)`, ratios already scale 3).
  *(0.75 constant: `new BigDecimal("0.75")`. `max(net,0)`: `net.max(BigDecimal.ZERO)`. `max(−net,0)`: `net.negate().max(BigDecimal.ZERO)`.)*
- [ ] **Controller** `LoanCalculationController` `@RestController @RequestMapping("/api/loans/{loanId}")`,
  `@GetMapping("/calculations")` → `ApiResponse.ok(service.calculate(loanId))`.
- [ ] `./gradlew :app:compileJava :qualification:compileJava` → SUCCESS. Commit `feat(qualification): LoanCalculationService + /calculations endpoint`.

## Task 3: Crown-jewel + edge-case IT
**File:** `app/src/test/java/com/msfg/los/qualification/web/LoanCalculationIT.java` (extends `AbstractIntegrationTest`).

- [ ] **Crown jewel** — set up the spec's worked example via the REST API (create loan; `PATCH /api/loans/{id}`
  with §4: base 300000, rate 6.5, term 360, salesPrice 375000, appraisedValue 380000, loanPurpose PURCHASE,
  proposedTaxesMonthly 400, proposedHazardInsuranceMonthly 120, proposedHoaDuesMonthly 0,
  proposedMortgageInsuranceMonthly 90; add a borrower + employment + BASE income totalling 9000; add liabilities
  with dtiMonthlyPayments 650; add one REO gross rent 2000 / mortgage 1200). Then `GET /api/loans/{id}/calculations`
  and assert EXACTLY (compute the precise values when writing — these are the targets):
  `ltvBasis 375000`, `ltv 80.000`, `monthlyPrincipalInterest 1896.20`, `proposedHousingExpense 2506.20`,
  `netRentalIncome 300.00`, `totalMonthlyIncome 9300.00`, `totalMonthlyDebts 650.00`, `frontEndDti 26.948`,
  `backEndDti 33.938`. (Use `jsonPath(...).value(...)` with numeric compares tolerant of scale, or read + `compareTo`.)
- [ ] **Edge cases** (each `GET` returns 200 with the right nulls — NO 500):
  - **no income** (loan with no income rows) → `frontEndDti`/`backEndDti` are null; other figures still computed.
  - **refi value basis:** loanPurpose REFINANCE, appraised 380000 (no/other salesPrice) → `ltvBasis 380000` (NOT lesser-of).
  - **second loan → CLTV > LTV:** set `secondLoanAmount` → `cltv` exceeds `ltv`.
  - **negative net rental:** REO gross 1000 / mortgage 1500 → `netRentalDebt 750.00` added to debts; income NOT reduced.
  - **zero rate:** rate 0 → P&I = base/term.
  - **no rate/term:** unset → `monthlyPrincipalInterest` null → `proposedHousingExpense` null → DTI null.
  - **no value:** unset salesPrice+appraised on a purchase → `ltv` null.
  - tenant scope: cross-org JWT → 404; no token → 401.
- [ ] `./gradlew :app:test --tests "*LoanCalculationIT"` → PASS. Commit `test(qualification): calc crown-jewel (worked example) + edge cases`.

## Task 4: Full build + finish
- [ ] FULL `./gradlew build` → BUILD SUCCESSFUL, all modules green (Specs 1–6A unaffected). Report total test count.
- [ ] Update `ROADMAP.md`/`CLAUDE.md` (6B ✅). Then **superpowers:finishing-a-development-branch**.
- [ ] (No RLS IT / no migration — read-only module, no new table.)

## Self-Review
- Spec coverage: every normative formula + the edge-case table (T2) verified by unit tests (T1) + the worked-example crown jewel + edge ITs (T3). No new persisted data; loan-scoped read guard. ✓
- Precision: `MortgageMath` rounds (currency 2, ratio 3, HALF_UP); `MathContext(20)` for the amortization power. ✓
- Lessons: edge cases return null (no divide-by-zero/500); DTI null when housing/income unavailable; crown jewel asserts hand-computed values, not a snapshot. ✓
- **Reviewer focus:** the amortization formula + rounding; the lesser-of LTV basis; net-rental positive→income / negative→debt split; DTI null-when-no-housing. This is where a subtle math error would hide.
```
