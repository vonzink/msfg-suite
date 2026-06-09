# LOS Spec 7 — Declarations + HMDA Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development + test-driven-development.

**Goal:** Per-borrower URLA §5 Declarations + §8 Demographics (HMDA), 1:1 PUT-upsert, multi-selects as converted
`Set<enum>` columns. Completes the 1003.

**Architecture:** New `declarations` module, borrower-scoped (mirror `parties`/`BorrowerAddressService`). Novel
bit: `EnumSetConverter`. Migration `V10`.

**Spec:** `docs/specs/2026-06-05-los-spec7-declarations-hmda.md`

## Templates to mirror
- Borrower-scoped service: `parties/.../service/BorrowerAddressService.java` (`org()`, `assertBorrowerInLoan`, guard-first, `findByIdAndOrgId`). But this is **1:1 upsert**, not collection CRUD — see Task 3.
- Controller/DTO/IT style: `parties/.../web/*` + `app/src/test/.../parties/web/BorrowerAddressControllerIT.java`.
- V10 RLS idiom: `V9__loan_info_reo.sql` / `V8`. RLS IT: `financials/.../AssetsLiabilitiesRlsIT.java`.

## File Structure
```
settings.gradle.kts (+"declarations") · declarations/build.gradle.kts · app/build.gradle.kts (+dep)
declarations/.../domain/
  PriorPropertyTitleType.java BankruptcyType.java Ethnicity.java Race.java Sex.java ApplicationTakenMethod.java
  EnumSetConverter.java BankruptcyTypeSetConverter.java EthnicitySetConverter.java RaceSetConverter.java
  BorrowerDeclarations.java BorrowerDemographics.java
declarations/.../repo/{BorrowerDeclarationsRepository,BorrowerDemographicsRepository}.java
declarations/.../service/{DeclarationsService,DemographicsService}.java
declarations/.../web/{DeclarationsController,DemographicsController}.java
declarations/.../web/dto/{DeclarationsRequest,DeclarationsResponse,DemographicsRequest,DemographicsResponse}.java
declarations/src/test/.../domain/EnumSetConverterTest.java   (unit)
app/src/main/resources/db/migration/V10__declarations_hmda.sql
app/src/test/.../declarations/web/{DeclarationsControllerIT,DemographicsControllerIT}.java
app/src/test/.../declarations/DeclarationsRlsIT.java
```

## declarations/build.gradle.kts = copy `reo/build.gradle.kts` (deps platform, loan-core, parties + web/data-jpa/validation).

---

## Task 0: Scaffold + enums + `EnumSetConverter` (TDD the converter)
- [ ] settings/app/build wiring (mirror reo). The 6 enums (plain — values per the spec). `OccupancyType` reused from loan-core (no new enum).
- [ ] **`EnumSetConverter` (TDD).** Test FIRST `declarations/src/test/.../domain/EnumSetConverterTest.java`:
```java
package com.msfg.los.declarations.domain;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
class EnumSetConverterTest {
    static class TestConv extends EnumSetConverter<BankruptcyType> { TestConv(){ super(BankruptcyType.class);} }
    final TestConv c = new TestConv();
    @Test void roundTrip() {
        Set<BankruptcyType> in = new LinkedHashSet<>(List.of(BankruptcyType.CHAPTER_7, BankruptcyType.CHAPTER_13));
        String db = c.convertToDatabaseColumn(in);
        assertThat(db).isEqualTo("CHAPTER_7,CHAPTER_13");
        assertThat(c.convertToEntityAttribute(db)).containsExactly(BankruptcyType.CHAPTER_7, BankruptcyType.CHAPTER_13);
    }
    @Test void nullAndEmpty() {
        assertThat(c.convertToDatabaseColumn(null)).isNull();
        assertThat(c.convertToDatabaseColumn(Set.of())).isNull();
        assertThat(c.convertToEntityAttribute(null)).isEmpty();
        assertThat(c.convertToEntityAttribute("")).isEmpty();
    }
}
```
- [ ] Run → FAIL. Then implement:
```java
package com.msfg.los.declarations.domain;
import jakarta.persistence.AttributeConverter;
import java.util.*;
import java.util.stream.Collectors;
public abstract class EnumSetConverter<E extends Enum<E>> implements AttributeConverter<Set<E>, String> {
    private final Class<E> type;
    protected EnumSetConverter(Class<E> type) { this.type = type; }
    @Override public String convertToDatabaseColumn(Set<E> attr) {
        return (attr == null || attr.isEmpty()) ? null
            : attr.stream().map(Enum::name).collect(Collectors.joining(","));
    }
    @Override public Set<E> convertToEntityAttribute(String db) {
        Set<E> out = new LinkedHashSet<>();
        if (db == null || db.isBlank()) return out;
        for (String s : db.split(",")) { String t = s.trim(); if (!t.isEmpty()) out.add(Enum.valueOf(type, t)); }
        return out;
    }
}
```
```java
// BankruptcyTypeSetConverter.java (+ EthnicitySetConverter, RaceSetConverter — same shape)
package com.msfg.los.declarations.domain;
import jakarta.persistence.Converter;
@Converter public class BankruptcyTypeSetConverter extends EnumSetConverter<BankruptcyType> {
    public BankruptcyTypeSetConverter() { super(BankruptcyType.class); }
}
```
- [ ] `./gradlew :declarations:test --tests "*EnumSetConverterTest"` → PASS. Commit `feat(declarations): scaffold module + §5/§8 enums + EnumSetConverter (TDD)`.

## Task 1: `V10` migration
- [ ] `app/.../db/migration/V10__declarations_hmda.sql` — two tables per the spec (each: id/version/org_id/loan_id/borrower_id + the columns; multi-selects are `varchar`). **`unique (org_id, borrower_id)`** on each; `references organization(id)` + `borrower_id references borrower_party(id)`; index `(org_id, loan_id)`; RLS `enable + force` + `tenant_isolation` policy (NULLIF-empty GUC) + `grant select,insert,update,delete to app_user`. Mirror `V9`/`V8` exactly. Verify `./gradlew :app:test --tests "*LosApplicationTests"`. Commit `feat(app): V10 migration — borrower_declarations + borrower_demographics + RLS`.

## Task 2: Entities + repos
- [ ] `BorrowerDeclarations` (extends `TenantScopedEntity`, `@Getter/@Setter`): `loanId`, `borrowerId`, the 15 `Boolean` fields, `@Enumerated(STRING) OccupancyType priorPropertyUsage`, `@Enumerated(STRING) PriorPropertyTitleType priorPropertyTitleType`, `@Convert(converter = BankruptcyTypeSetConverter.class) Set<BankruptcyType> bankruptcyTypes = new LinkedHashSet<>()`.
- [ ] `BorrowerDemographics` (extends `TenantScopedEntity`): `loanId`, `borrowerId`, `@Convert(EthnicitySetConverter.class) Set<Ethnicity> ethnicity`, `@Convert(RaceSetConverter.class) Set<Race> race`, `@Enumerated(STRING) Sex sex`, `Boolean collectedByVisualObservationOrSurname`, `@Enumerated(STRING) ApplicationTakenMethod applicationTakenMethod`.
- [ ] Repos: each `extends JpaRepository<…, UUID>` with `Optional<…> findByBorrowerId(UUID borrowerId)` (1:1; `@TenantId`-filtered) + `Optional<…> findByIdAndOrgId(UUID,UUID)`.
- [ ] `./gradlew :app:test --tests "*LosApplicationTests"` → PASS. Commit `feat(declarations): BorrowerDeclarations + BorrowerDemographics entities + repos`.

## Task 3: Upsert services + controllers + DTOs + ITs
- [ ] DTOs: `DeclarationsRequest`/`DeclarationsResponse` (all fields incl. `Set<BankruptcyType>`), `DemographicsRequest`/`DemographicsResponse` (incl. `Set<Ethnicity>`, `Set<Race>`). Records; `Response.from(entity)`.
- [ ] **`DeclarationsService`** — borrower-scoped (inject its repo, `BorrowerRepository`, `LoanService`, `LoanAccessGuard`, `TenantContext`; `org()` + `assertBorrowerInLoan` exactly like `BorrowerAddressService`). Two methods:
  - `get(loanId, borrowerId)`: `assertBorrowerInLoan`; `repo.findByBorrowerId(borrowerId).orElse(null)` → return (service returns the entity or null; controller maps null → an empty `DeclarationsResponse`).
  - `upsert(loanId, borrowerId, req)`: `assertBorrowerInLoan`; `var e = repo.findByBorrowerId(borrowerId).orElseGet(() -> { var n = new BorrowerDeclarations(); n.setLoanId(loanId); n.setBorrowerId(borrowerId); return n; });` apply ALL fields from req (full replace — this is PUT, not PATCH: set every field including nulls and the sets); `return repo.save(e);`. (`@Transactional`.)
  - `DemographicsService` is the same shape.
- [ ] Controllers: `@RestController @RequestMapping("/api/loans/{loanId}/borrowers/{borrowerId}/declarations")` — `@GetMapping` → `ApiResponse.ok(DeclarationsResponse.from-or-empty)`, `@PutMapping` (`@Valid @RequestBody DeclarationsRequest`) → `ApiResponse.ok(...)` (200). Same for `/demographics`.
- [ ] **ITs** (`app/src/test/.../declarations/web/`): mirror `BorrowerAddressControllerIT` helpers (createLoan + addBorrower).
  - `DemographicsControllerIT`: GET before PUT → 200, empty sets; PUT `ethnicity=["HISPANIC_OR_LATINO","MEXICAN"]`, `race=["ASIAN","CHINESE","WHITE"]`, `sex="MALE"` → 200; GET → same sets back (order preserved); **assert via JDBC** `select ethnicity from borrower_demographics where borrower_id=?::uuid` == `"HISPANIC_OR_LATINO,MEXICAN"`; second PUT (different values) → GET reflects replacement and `select count(*) ... = 1` (upsert, not insert); cross-org → 404; no token → 401.
  - `DeclarationsControllerIT`: PUT booleans (true/false/omitted-null) + `bankruptcyTypes=["CHAPTER_7","CHAPTER_13"]` → GET round-trips (null distinct from false); upsert replaces; cross-org 404; no token 401.
- [ ] `./gradlew :app:test --tests "*DeclarationsControllerIT" --tests "*DemographicsControllerIT"` → PASS, `./gradlew :declarations:test` → PASS. Commit `feat(declarations): §5/§8 upsert services, controllers, DTOs, ITs`.

## Task 4: RLS IT + full build + finish
- [ ] `DeclarationsRlsIT` — copy `AssetsLiabilitiesRlsIT`; fresh orgs `…00a1`/`…00a2`; seed a loan + borrower_party per org; insert one `borrower_declarations` + one `borrower_demographics` (ORG_X) under `app_user`+GUC (read V10 NOT-NULL cols); assert ORG_Y → 0 / ORG_X → ≥1 / fail-closed on `borrower_declarations`.
- [ ] `./gradlew :app:test --tests "*DeclarationsRlsIT"` → PASS, then FULL `./gradlew build` → SUCCESSFUL (report total test count).
- [ ] Commit `test(declarations): RLS coverage`. Update `ROADMAP.md`/`CLAUDE.md` (**S7 ✅ — full 1003 complete**), then **superpowers:finishing-a-development-branch**.

## Self-Review
Spec coverage: §5 Declarations (entity + upsert) + §8 Demographics (multi-select converters) + V10 + RLS; 1:1 PUT-upsert; converter round-trip proven incl. the DB-string assertion. ✓
Lessons: borrower-scoped guard; full-replace PUT semantics (nulls + sets); converter null/empty handling; cross-org 404 / 401; RLS IT. ✓
