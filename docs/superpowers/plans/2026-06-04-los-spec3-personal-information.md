# LOS Spec 3 — Personal Information & PII Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Capture the 1003 Personal Information for each borrower — identity (incl. encrypted **SSN**, **DOB**), contact, citizenship, and **address history** — with NPI encryption live (masked SSN + audited reveal), all tenant-scoped.

**Architecture:** Extend `BorrowerParty` with PII columns (SSN via `@Convert(EncryptedStringConverter)`), add a tenant-scoped `BorrowerAddress` table (typed CRUD sub-resource), and a reusable tenant-scoped `PiiAccessLog` + `PiiAccessRecorder` (platform) that records SSN reveals. SSN is masked (`ssnLast4`) in all responses; full value only via an audited `reveal-ssn` endpoint. New entities extend `TenantScopedEntity`; loads use `findByIdAndOrgId`.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Hibernate 6.5 (`@TenantId`, `@Convert`), Postgres 16 (RLS), Flyway, Testcontainers.

**Repo:** `/Users/zacharyzink/MSFG/msfg-suite` · **Base package:** `com.msfg.los` · **DEFAULT_ORG:** `00000000-0000-0000-0000-0000000000aa`

> **Branch:** `spec-3-personal-information` (Task 0); bundle the spec + this plan as the first commit. Use `./gradlew`; Docker must be running. If a hook redirects build/test to `mcp__plugin_context-mode_context-mode__ctx_execute`, comply. **Multi-tenancy rules (Spec 2):** new entities extend `TenantScopedEntity`; **load tenant-scoped entities by `findByIdAndOrgId`, never `findById`**; the new tables get RLS (`FORCE`/`WITH CHECK`).

---

## File Structure
```
platform/.../error/ValidationException.java        NEW  DomainException → 400
platform/.../pii/SsnSupport.java                    NEW  normalize / validate / last4 / maskedDisplay
platform/.../pii/PiiAccessLog.java                  NEW  @Entity extends TenantScopedEntity
platform/.../pii/PiiAccessLogRepository.java        NEW
platform/.../pii/PiiAccessRecorder.java             NEW  @Service: record(subjectType, subjectId, field, reason)
platform/.../reference/UsStateCode.java             NEW  enum (50 + DC + territories)

parties/.../domain/BorrowerParty.java               MOD  + PII + contact columns; ssn @Convert
parties/.../domain/{MaritalStatus,CitizenshipType}.java   NEW enums
parties/.../domain/{BorrowerAddress,AddressType,OwnershipType}.java  NEW
parties/.../repo/BorrowerAddressRepository.java     NEW
parties/.../repo/BorrowerRepository.java            MOD  + findByIdAndOrgId
parties/.../service/BorrowerService.java            MOD  PII in add/update; revealSsn(...)
parties/.../service/BorrowerAddressService.java     NEW
parties/.../web/BorrowerController.java             MOD  + POST /{id}/reveal-ssn
parties/.../web/BorrowerAddressController.java       NEW  /borrowers/{id}/addresses
parties/.../web/dto/*                                MOD/NEW  Add/UpdateBorrowerRequest (+PII),
                                                     BorrowerResponse (masked SSN +PII), RevealSsnRequest/Response,
                                                     Add/UpdateAddressRequest, AddressResponse

app/src/main/resources/db/migration/V6__personal_information.sql   NEW
app/src/test/java/com/msfg/los/parties/...          NEW/MOD  PII + address + reveal ITs
```

---

## Task 0: Branch + bundle spec/plan
- [ ] `git checkout -b spec-3-personal-information`
- [ ] `git add docs/specs/2026-06-04-los-spec3-personal-information.md docs/superpowers/plans/2026-06-04-los-spec3-personal-information.md && git -c user.name=Vonzink -c user.email=vonzink@gmail.com commit -m "chore(spec-3): bundle Personal Information spec & plan"`

---

## Task 1: platform — ValidationException, SsnSupport, UsStateCode (TDD)

**Files:** Create `error/ValidationException.java`, `pii/SsnSupport.java`, `reference/UsStateCode.java`. **Test:** `pii/SsnSupportTest.java`.

- [ ] **Step 1:** `error/ValidationException.java`:
```java
package com.msfg.los.platform.error;
import org.springframework.http.HttpStatus;
public class ValidationException extends DomainException {
    public ValidationException(String message) { super(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message); }
}
```
- [ ] **Step 2: Failing test** `pii/SsnSupportTest.java`:
```java
package com.msfg.los.platform.pii;
import com.msfg.los.platform.error.ValidationException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SsnSupportTest {
    @Test void normalizesDashedAndPlain() {
        assertThat(SsnSupport.normalize("123-45-6789")).isEqualTo("123456789");
        assertThat(SsnSupport.normalize(" 123 45 6789 ")).isEqualTo("123456789");
    }
    @Test void rejectsBadFormatAndInvalidAreas() {
        assertThatThrownBy(() -> SsnSupport.normalize("12-345-6789")).isInstanceOf(ValidationException.class); // wrong shape
        assertThatThrownBy(() -> SsnSupport.normalize("000-12-3456")).isInstanceOf(ValidationException.class); // area 000
        assertThatThrownBy(() -> SsnSupport.normalize("666-12-3456")).isInstanceOf(ValidationException.class); // area 666
        assertThatThrownBy(() -> SsnSupport.normalize("900-12-3456")).isInstanceOf(ValidationException.class); // area 9xx
        assertThatThrownBy(() -> SsnSupport.normalize("123-00-6789")).isInstanceOf(ValidationException.class); // group 00
        assertThatThrownBy(() -> SsnSupport.normalize("123-45-0000")).isInstanceOf(ValidationException.class); // serial 0000
    }
    @Test void last4AndMask() {
        assertThat(SsnSupport.last4("123456789")).isEqualTo("6789");
        assertThat(SsnSupport.maskedDisplay("123456789")).isEqualTo("•••-••-6789");
        assertThat(SsnSupport.last4(null)).isNull();
        assertThat(SsnSupport.maskedDisplay(null)).isNull();
    }
}
```
Run `./gradlew :platform:test --tests "*SsnSupportTest"` → FAIL.
- [ ] **Step 3:** `pii/SsnSupport.java`:
```java
package com.msfg.los.platform.pii;
import com.msfg.los.platform.error.ValidationException;

public final class SsnSupport {
    private SsnSupport() {}
    /** Strip non-digits, validate, return the 9-digit SSN. Throws ValidationException if invalid. */
    public static String normalize(String input) {
        if (input == null) throw new ValidationException("SSN is required");
        String d = input.replaceAll("\\D", "");
        if (d.length() != 9) throw new ValidationException("SSN must be 9 digits");
        String area = d.substring(0, 3), group = d.substring(3, 5), serial = d.substring(5);
        if (area.equals("000") || area.equals("666") || area.charAt(0) == '9')
            throw new ValidationException("Invalid SSN area number");
        if (group.equals("00")) throw new ValidationException("Invalid SSN group number");
        if (serial.equals("0000")) throw new ValidationException("Invalid SSN serial number");
        return d;
    }
    public static String last4(String ssn9) { return ssn9 == null ? null : ssn9.substring(ssn9.length() - 4); }
    public static String maskedDisplay(String ssn9) { return ssn9 == null ? null : "•••-••-" + last4(ssn9); }
    /** Format a stored 9-digit SSN as 123-45-6789 for the reveal endpoint. */
    public static String formatDashed(String ssn9) {
        return ssn9 == null ? null : ssn9.substring(0,3) + "-" + ssn9.substring(3,5) + "-" + ssn9.substring(5);
    }
}
```
- [ ] **Step 4:** `reference/UsStateCode.java` — enum of the 2-letter codes:
```java
package com.msfg.los.platform.reference;
public enum UsStateCode {
    AL, AK, AZ, AR, CA, CO, CT, DE, FL, GA, HI, ID, IL, IN, IA, KS, KY, LA, ME, MD, MA, MI, MN, MS, MO,
    MT, NE, NV, NH, NJ, NM, NY, NC, ND, OH, OK, OR, PA, RI, SC, SD, TN, TX, UT, VT, VA, WA, WV, WI, WY,
    DC, AS, GU, MP, PR, VI   // DC + territories (American Samoa, Guam, N. Mariana, Puerto Rico, US Virgin Islands)
}
```
- [ ] **Step 5:** Run `./gradlew :platform:test --tests "*SsnSupportTest"` → PASS; then `./gradlew :platform:test` → all green.
- [ ] **Step 6:** Commit `feat(platform): ValidationException, SsnSupport (NPI), UsStateCode`.

---

## Task 2: V6 migration (schema first, so later entity tasks validate cleanly)

**Files:** Create `app/src/main/resources/db/migration/V6__personal_information.sql`.

> Writing the migration BEFORE the entity changes keeps `ddl-auto: validate` green at every step
> (Hibernate ignores extra/unmapped columns; entities are added against an existing schema).

- [ ] **Step 1:** Write `V6__personal_information.sql`:
```sql
-- Borrower PII + contact (org_id already on borrower_party from V3)
alter table borrower_party
    add column middle_name varchar(120),
    add column suffix varchar(20),
    add column ssn varchar(512),                 -- AES-GCM ciphertext (base64), not the 9 digits
    add column date_of_birth date,
    add column marital_status varchar(20),
    add column dependents_count int,
    add column dependent_ages varchar(200),
    add column citizenship_type varchar(40),
    add column veteran boolean,
    add column unmarried_addendum_spousal_rights boolean,
    add column joined_to_borrower_id uuid,
    add column home_phone varchar(30),
    add column cell_phone varchar(30),
    add column work_phone varchar(30),
    add column work_phone_ext varchar(10),
    add column email varchar(255),
    add column no_email boolean;

create table borrower_address (
    id uuid primary key,
    version bigint not null default 0,
    org_id uuid not null references organization(id),
    borrower_id uuid not null references borrower_party(id),
    address_type varchar(30) not null,
    ordinal int not null default 0,
    address_line1 varchar(255),
    address_line2 varchar(255),
    city varchar(120),
    state varchar(2),
    postal_code varchar(10),
    country varchar(2) default 'US',
    ownership_type varchar(20),
    residency_duration_years int,
    residency_duration_months int,
    rent_amount numeric(15,2),
    rent_verified boolean,
    created_at timestamp(6) with time zone, created_by varchar(120),
    updated_at timestamp(6) with time zone, updated_by varchar(120)
);
create index idx_borrower_address_borrower on borrower_address(borrower_id);
create index idx_borrower_address_org on borrower_address(org_id);

create table pii_access_log (
    id uuid primary key,
    version bigint not null default 0,
    org_id uuid not null references organization(id),
    subject_type varchar(40) not null,
    subject_id uuid not null,
    field varchar(40) not null,
    reason varchar(500),
    created_at timestamp(6) with time zone, created_by varchar(120),
    updated_at timestamp(6) with time zone, updated_by varchar(120)
);
create index idx_pii_access_org on pii_access_log(org_id);
create index idx_pii_access_subject on pii_access_log(subject_id);

-- RLS on the new tenant tables (consistent with V3/V5)
alter table borrower_address enable row level security;
alter table borrower_address force row level security;
create policy tenant_isolation on borrower_address
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

alter table pii_access_log enable row level security;
alter table pii_access_log force row level security;
create policy tenant_isolation on pii_access_log
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

grant select, insert, update, delete on borrower_address, pii_access_log to app_user;
```
- [ ] **Step 2:** `./gradlew :app:test --tests "*LosApplicationTests"` → PASS (migration applies; existing entities still validate). Commit `feat(app): V6 migration — borrower PII columns, borrower_address, pii_access_log + RLS`.

---

## Task 3: platform — PiiAccessLog + recorder

**Files:** Create `pii/PiiAccessLog.java`, `pii/PiiAccessLogRepository.java`, `pii/PiiAccessRecorder.java`. (Behavior verified by the reveal IT in Task 6.)

- [ ] **Step 1:** `pii/PiiAccessLog.java`:
```java
package com.msfg.los.platform.pii;
import com.msfg.los.platform.domain.TenantScopedEntity;
import jakarta.persistence.*;
import lombok.Getter; import lombok.Setter;
import java.util.UUID;

@Entity @Table(name = "pii_access_log")
@Getter @Setter
public class PiiAccessLog extends TenantScopedEntity {
    @Column(nullable = false) private String subjectType;
    @Column(nullable = false) private UUID subjectId;
    @Column(nullable = false) private String field;
    @Column(length = 500) private String reason;
}
```
- [ ] **Step 2:** `pii/PiiAccessLogRepository.java`:
```java
package com.msfg.los.platform.pii;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;
public interface PiiAccessLogRepository extends JpaRepository<PiiAccessLog, UUID> {
    List<PiiAccessLog> findBySubjectIdOrderByCreatedAtDesc(UUID subjectId);
}
```
- [ ] **Step 3:** `pii/PiiAccessRecorder.java`:
```java
package com.msfg.los.platform.pii;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
public class PiiAccessRecorder {
    private final PiiAccessLogRepository repo;
    public PiiAccessRecorder(PiiAccessLogRepository repo) { this.repo = repo; }
    /** Records one NPI access. createdBy/createdAt (who/when) are set by JPA auditing; org_id by @TenantId. */
    @Transactional
    public void record(String subjectType, UUID subjectId, String field, String reason) {
        PiiAccessLog log = new PiiAccessLog();
        log.setSubjectType(subjectType);
        log.setSubjectId(subjectId);
        log.setField(field);
        log.setReason(reason);
        repo.save(log);
    }
}
```
- [ ] **Step 4:** `./gradlew :app:test --tests "*LosApplicationTests"` → PASS (entity validates against the V6 table). Commit `feat(platform): PiiAccessLog + PiiAccessRecorder (NPI reveal audit)`.

---

## Task 4: parties — BorrowerParty PII columns + SSN encryption

**Files:** Modify `BorrowerParty.java`; create `domain/MaritalStatus.java`, `domain/CitizenshipType.java`; modify `repo/BorrowerRepository.java`.

- [ ] **Step 1:** Enums:
```java
package com.msfg.los.parties.domain;
public enum MaritalStatus { MARRIED, SEPARATED, UNMARRIED }
```
```java
package com.msfg.los.parties.domain;
public enum CitizenshipType { US_CITIZEN, PERMANENT_RESIDENT_ALIEN, NON_PERMANENT_RESIDENT_ALIEN, FOREIGN_NATIONAL }
```
- [ ] **Step 2:** Extend `BorrowerParty` (add fields after `lastName`; `@Convert` the SSN):
```java
// imports: com.msfg.los.platform.crypto.EncryptedStringConverter; jakarta.persistence.Convert; java.time.LocalDate; java.math.BigDecimal not needed here
    private String middleName;
    private String suffix;

    @Convert(converter = EncryptedStringConverter.class)
    private String ssn;                 // stored as ciphertext; holds the normalized 9 digits in memory

    private LocalDate dateOfBirth;
    @Enumerated(EnumType.STRING) private MaritalStatus maritalStatus;
    private Integer dependentsCount;
    private String dependentAges;
    @Enumerated(EnumType.STRING) private CitizenshipType citizenshipType;
    private Boolean veteran;
    private Boolean unmarriedAddendumSpousalRights;
    private UUID joinedToBorrowerId;
    private String homePhone;
    private String cellPhone;
    private String workPhone;
    private String workPhoneExt;
    private String email;
    private Boolean noEmail;
```
- [ ] **Step 3:** `BorrowerRepository.java` — add the tenant-safe load:
```java
Optional<BorrowerParty> findByIdAndOrgId(UUID id, UUID orgId);
```
(keep existing methods; add imports `java.util.Optional`, `java.util.UUID` if missing.)
- [ ] **Step 4:** `./gradlew :app:test` → all green (existing borrower tests still pass; new columns validate; `@Convert` compiles). Commit `feat(parties): BorrowerParty PII + contact fields; SSN encrypted via @Convert`.

---

## Task 5: parties — BorrowerAddress (entity + repo + service + controller + DTOs + ITs)

**Files:** Create `domain/{BorrowerAddress,AddressType,OwnershipType}.java`, `repo/BorrowerAddressRepository.java`, `service/BorrowerAddressService.java`, `web/BorrowerAddressController.java`, `web/dto/{AddAddressRequest,UpdateAddressRequest,AddressResponse}.java`. **Test:** `app/src/test/.../parties/web/BorrowerAddressControllerIT.java`.

- [ ] **Step 1:** Enums + entity:
```java
package com.msfg.los.parties.domain;
public enum AddressType { PRESENT, PREVIOUS, MAILING, TAX_FILING_CURRENT, TAX_FILING_PREVIOUS }
```
```java
package com.msfg.los.parties.domain;
public enum OwnershipType { OWN, RENT, LIVING_RENT_FREE }
```
```java
package com.msfg.los.parties.domain;
import com.msfg.los.platform.domain.TenantScopedEntity;
import com.msfg.los.platform.reference.UsStateCode;
import jakarta.persistence.*;
import lombok.Getter; import lombok.Setter;
import java.math.BigDecimal; import java.util.UUID;

@Entity @Table(name = "borrower_address")
@Getter @Setter
public class BorrowerAddress extends TenantScopedEntity {
    @Column(nullable = false) private UUID borrowerId;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private AddressType addressType;
    @Column(nullable = false) private int ordinal;
    private String addressLine1;
    private String addressLine2;
    private String city;
    @Enumerated(EnumType.STRING) private UsStateCode state;
    private String postalCode;
    private String country = "US";
    @Enumerated(EnumType.STRING) private OwnershipType ownershipType;
    private Integer residencyDurationYears;
    private Integer residencyDurationMonths;
    private BigDecimal rentAmount;
    private Boolean rentVerified;
}
```
- [ ] **Step 2:** `repo/BorrowerAddressRepository.java`:
```java
package com.msfg.los.parties.repo;
import com.msfg.los.parties.domain.BorrowerAddress;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List; import java.util.Optional; import java.util.UUID;
public interface BorrowerAddressRepository extends JpaRepository<BorrowerAddress, UUID> {
    List<BorrowerAddress> findByBorrowerIdOrderByAddressTypeAscOrdinalAsc(UUID borrowerId);
    Optional<BorrowerAddress> findByIdAndOrgId(UUID id, UUID orgId);   // tenant-safe load (Spec-2 rule)
    long countByBorrowerIdAndAddressType(UUID borrowerId, com.msfg.los.parties.domain.AddressType type);
}
```
- [ ] **Step 3:** DTOs (records) in `web/dto/`:
```java
package com.msfg.los.parties.web.dto;
import com.msfg.los.parties.domain.*;
import com.msfg.los.platform.reference.UsStateCode;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
public record AddAddressRequest(@NotNull AddressType addressType, String addressLine1, String addressLine2,
    String city, UsStateCode state, String postalCode, String country, OwnershipType ownershipType,
    Integer residencyDurationYears, Integer residencyDurationMonths, BigDecimal rentAmount, Boolean rentVerified) {}
```
```java
package com.msfg.los.parties.web.dto;
import com.msfg.los.parties.domain.OwnershipType;
import com.msfg.los.platform.reference.UsStateCode;
import java.math.BigDecimal;
public record UpdateAddressRequest(String addressLine1, String addressLine2, String city, UsStateCode state,
    String postalCode, String country, OwnershipType ownershipType, Integer residencyDurationYears,
    Integer residencyDurationMonths, BigDecimal rentAmount, Boolean rentVerified) {}
```
```java
package com.msfg.los.parties.web.dto;
import com.msfg.los.parties.domain.*;
import com.msfg.los.platform.reference.UsStateCode;
import java.math.BigDecimal; import java.util.UUID;
public record AddressResponse(UUID id, UUID borrowerId, AddressType addressType, int ordinal,
    String addressLine1, String addressLine2, String city, UsStateCode state, String postalCode, String country,
    OwnershipType ownershipType, Integer residencyDurationYears, Integer residencyDurationMonths,
    BigDecimal rentAmount, Boolean rentVerified) {
    public static AddressResponse from(BorrowerAddress a) {
        return new AddressResponse(a.getId(), a.getBorrowerId(), a.getAddressType(), a.getOrdinal(),
            a.getAddressLine1(), a.getAddressLine2(), a.getCity(), a.getState(), a.getPostalCode(), a.getCountry(),
            a.getOwnershipType(), a.getResidencyDurationYears(), a.getResidencyDurationMonths(),
            a.getRentAmount(), a.getRentVerified());
    }
}
```
- [ ] **Step 4:** `service/BorrowerAddressService.java` — mirrors `BorrowerService`'s guard pattern. Inject `BorrowerAddressRepository addresses`, `LoanService loanService`, `LoanAccessGuard accessGuard`, `BorrowerService borrowerService` (to verify the borrower belongs to the loan), `TenantContext tenantContext`. Each method first `accessGuard.assertCanAccess(loanService.get(loanId))` (404 if loan not in caller's org), then verifies the borrower belongs to the loan, then operates. Loads of a single address use `addresses.findByIdAndOrgId(id, tenantContext.orgId().orElseThrow())` (then check it belongs to the borrower). `add`: ordinal = `countByBorrowerIdAndAddressType` for that type; set `borrowerId`. `list`/`update`/`delete` follow `BorrowerService`'s patterns (see Spec-1 `BorrowerService` in-repo for the exact shape).
- [ ] **Step 5:** `web/BorrowerAddressController.java` `@RequestMapping("/api/loans/{loanId}/borrowers/{borrowerId}/addresses")` — POST→201, GET list, PATCH `/{addressId}`, DELETE `/{addressId}`→204, all returning the `ApiResponse` envelope (mirror `BorrowerController`).
- [ ] **Step 6: IT** `BorrowerAddressControllerIT` (in `app/src/test`, extends `AbstractIntegrationTest`) — use the Spec-2 tenant jwt helper (`jwt().jwt(j->j.subject(LO).claim("org_id", DEFAULT_ORG)).authorities(new SimpleGrantedAuthority("ROLE_LO"))`):
  - create loan + borrower, add a PRESENT address with `state=CO, ownershipType=RENT` → 201 + correct fields.
  - list addresses → contains it.
  - cross-org user → 404 on the loan path; cross-loan borrower/address → 404/403.
Run `./gradlew :app:test --tests "*BorrowerAddressControllerIT"` → GREEN. Commit `feat(parties): BorrowerAddress typed CRUD sub-resource`.

---

## Task 6: parties — SSN masking + reveal + audit + borrower DTO PII (the headline)

**Files:** Modify `web/dto/{AddBorrowerRequest,UpdateBorrowerRequest,BorrowerResponse}.java`, create `web/dto/{RevealSsnRequest,RevealSsnResponse}.java`; modify `service/BorrowerService.java`, `web/BorrowerController.java`. **Tests:** `app/src/test/.../parties/...` (ciphertext-at-rest, reveal+audit, masked response).

- [ ] **Step 1:** Extend `AddBorrowerRequest` + `UpdateBorrowerRequest` with the PII + contact fields (all optional except existing): `middleName, suffix, ssn (String), dateOfBirth (LocalDate), maritalStatus, dependentsCount, dependentAges, citizenshipType (enum), veteran, unmarriedAddendumSpousalRights, joinedToBorrowerId, homePhone, cellPhone, workPhone, workPhoneExt, email, noEmail`. (SSN validated/normalized in the service, not the DTO.)
- [ ] **Step 2:** `BorrowerResponse` — add the PII fields but **SSN masked**: replace any raw ssn with `String ssnLast4` + `String ssnMasked`; include `dateOfBirth`, `maritalStatus`, `citizenshipType`, contact, etc. `from(BorrowerParty b)` computes `SsnSupport.last4(b.getSsn())` and `SsnSupport.maskedDisplay(b.getSsn())` (both null-safe). **Never put the full SSN in this response.**
- [ ] **Step 3:** `RevealSsnRequest` (`record(String reason)`) + `RevealSsnResponse` (`record(String ssn)`).
- [ ] **Step 4:** `BorrowerService`:
  - In `add`/`update`: when `req.ssn() != null`, set `b.setSsn(SsnSupport.normalize(req.ssn()))` (throws 400 on bad SSN). Set the other PII/contact fields conditionally (mirror the existing partial-update pattern). Use `findByIdAndOrgId` for any borrower load-by-id (replace `findById`).
  - Add:
```java
@Transactional(readOnly = true)
public String revealSsn(UUID loanId, UUID borrowerId, String reason) {
    accessGuard.assertCanAccess(loanService.get(loanId));         // 404 cross-org, 403 not owner
    BorrowerParty b = borrowers.findByIdAndOrgId(borrowerId, tenantContext.orgId().orElseThrow())
        .filter(x -> x.getLoanId().equals(loanId))
        .orElseThrow(() -> new NotFoundException("Borrower", borrowerId));
    if (b.getSsn() == null) throw new NotFoundException("SSN", borrowerId);
    piiAccessRecorder.record("BORROWER", borrowerId, "SSN", reason);   // audited
    return SsnSupport.formatDashed(b.getSsn());
}
```
  Inject `TenantContext tenantContext` and `PiiAccessRecorder piiAccessRecorder` (constructor). Note: `revealSsn` is `readOnly=true` but `piiAccessRecorder.record` is its own `@Transactional` write — fine (separate RW tx).
- [ ] **Step 5:** `BorrowerController` — add:
```java
@PostMapping("/{borrowerId}/reveal-ssn")
public ApiResponse<RevealSsnResponse> revealSsn(@PathVariable UUID loanId, @PathVariable UUID borrowerId,
        @RequestBody RevealSsnRequest req) {
    return ApiResponse.ok(new RevealSsnResponse(service.revealSsn(loanId, borrowerId, req.reason())));
}
```
- [ ] **Step 6: Tests** (in `app/src/test/.../parties/`):
  - **`SsnEncryptionIT` (crown jewel):** create a loan + borrower with `ssn="123-45-6789"` via MockMvc (tenant jwt). Then via `@Autowired JdbcTemplate`, `select ssn from borrower_party where id = ?` (superuser → RLS-bypassed) and assert the raw value **does NOT equal** `"123-45-6789"` and **does not contain** `"123456789"` (it's ciphertext). Then GET the borrower → `$.data.ssnLast4 == "6789"`, and **no full SSN** in the body.
  - **reveal + audit:** `POST /reveal-ssn {"reason":"underwriting"}` → 200, `$.data.ssn == "123-45-6789"`; assert exactly one `pii_access_log` row for that subject (`@Autowired PiiAccessLogRepository`).
  - **validation:** add a borrower with `ssn="000-12-3456"` → 400.
Run `./gradlew :app:test` → all green. Commit `feat(parties): SSN masking + audited reveal-ssn endpoint; borrower PII DTOs`.

---

## Task 7: Full build + boot smoke + finish
- [ ] `./gradlew build` → all modules green.
- [ ] Boot smoke (local profile): `docker compose up -d`, run the app, then via curl: create loan → add borrower with PII incl. SSN → GET borrower shows `ssnLast4` (not full SSN) → `POST reveal-ssn {"reason":"test"}` returns the full SSN → add a PRESENT address. Stop app + `docker compose down`.
- [ ] Commit `chore(spec-3): Personal Information complete` (if any final tweaks). Branch finishes via the finishing-a-development-branch flow.

---

## Self-Review

**Spec coverage:** SSN encrypt+mask+reveal+audit → Tasks 1,3,4,6. DOB-as-date → Task 4. BorrowerParty PII/contact → Task 4. Citizenship/marital enums → Task 4. BorrowerAddress typed CRUD + residency → Task 5. PiiAccessLog (platform, tenant-scoped) → Tasks 2,3. UsStateCode → Task 1. Granular API (PATCH + /addresses) → Tasks 5,6. Validation → Tasks 1,6. Migration + RLS on new tables → Task 2. Tenant-safe loads (`findByIdAndOrgId`) → Tasks 4,5,6. Ciphertext-at-rest crown jewel → Task 6. ✓

**Placeholder scan:** No logic placeholders. Task 5 step 4/5 reference the in-repo Spec-1 `BorrowerService`/`BorrowerController` patterns for the mechanical CRUD shape (concrete, reviewed code in the repo) rather than re-listing every getter — all novel/load-bearing code (SSN util, entities, migration, reveal, the ciphertext + audit tests) is complete.

**Type consistency:** `SsnSupport.{normalize,last4,maskedDisplay,formatDashed}` used identically across Tasks 1/6. `findByIdAndOrgId` signatures match across `BorrowerRepository`/`BorrowerAddressRepository`. `PiiAccessRecorder.record(String,UUID,String,String)` consistent (Tasks 3/6). `DEFAULT_ORG` + the Spec-2 tenant jwt helper reused in all ITs. SSN stored as normalized 9 digits, `@Convert`-encrypted, formatted dashed only on reveal — consistent everywhere.

**Execution note:** schema-first ordering (Task 2 before the entity tasks) keeps `ddl-auto: validate` green throughout — no intentional red window this time.
