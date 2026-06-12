# AUS + Credit Vendor Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
> Spec: `docs/specs/2026-06-12-aus-credit-module.md`. Migration **V15**. Security-critical (vendor secrets, FCRA credit data) → opus pass before merge.

**Goal:** DU/LPA AUS runs + credit-report ordering behind vendor ports shaped 1:1 to the real wire surfaces (casefile IDs / LPA Keys / creditReportIdentifier reissue), with org-default + loan-override encrypted credentials — stub adapters now, drop-in real adapters later.

**Architecture:** one new `aus` gradle module (deps: `platform`, `loan-core`, `documents`, `parties`). Two ports (`AusVendorPort`, `CreditVendorPort`) with deterministic stub `@Component`s; artifacts stored as documents via `DocumentService.storeGenerated`; credentials in a single org/loan table with AES-GCM-encrypted secret columns, masked write-only responses; per-loan settings as jsonb (CoC pattern).

**Tech Stack:** Java 21 · Spring Boot 3.3 · Flyway V15 · JUnit5/MockMvc/Testcontainers (Docker required) · `./gradlew` from the worktree root.

**⚠️ Worktree protocol (NEVER `git checkout` in /Users/zacharyzink/MSFG/msfg-suite):**
```bash
cd /Users/zacharyzink/MSFG/msfg-suite
git worktree add ~/.config/superpowers/worktrees/msfg-suite/aus-credit -b feat/aus-credit main
cd ~/.config/superpowers/worktrees/msfg-suite/aus-credit   # ALL work here
```

**Shared type contracts (single source of truth — later tasks use these EXACTLY):**
- Enums (`aus/src/main/java/com/msfg/los/aus/domain/`): `AusVendor{DU,LPA}` · `CredentialVendor{DU,LPA,CREDIT}` · `AusRecommendation{APPROVE_ELIGIBLE,APPROVE_INELIGIBLE,REFER_WITH_CAUTION,REFER_INELIGIBLE,OUT_OF_SCOPE,ACCEPT,CAUTION,ERROR}` · `AusRunStatus{PENDING,COMPLETE,ERROR}` · `CreditOrderStatus{PENDING,COMPLETE,ERROR}` · `CreditBureau{EQUIFAX,EXPERIAN,TRANSUNION}` · `CreditOrderAction{SUBMIT,FORCE_NEW,REISSUE,UPGRADE}` · `CreditRequestType{INDIVIDUAL,JOINT}` · `AusIssueMode{ORDER,REISSUE}` · `CredentialSource{ORG,LOAN,NONE}` · web-only `AusRunSelection{DU,LPA,ONE_CLICK}`.
- jsonb records (`domain/`): `CreditReference(UUID borrowerId, String reference)` · `AusVendorSettings(AusIssueMode issueMode, String creditProviderCode, String fhaCaseNumber, String branchNumber, List<CreditReference> creditReferences)` · `CreditScoreEntry(UUID borrowerId, CreditBureau bureau, Integer score, String model)`.
- Port records (`service/`): see Task 4 — `AusSubmission`, `AusLoanFile`, `ResolvedCredentials`, `CreditWiring`, `BorrowerCredit`, `AusVendorResult`, `VendorArtifact`, `CreditVendorRequest`, `CreditBorrower`, `CreditVendorResult`.

---

### Task 1: Module scaffold + enums + DocumentType.AUS_FINDINGS

**Files:**
- Modify: `settings.gradle.kts` (append `"aus"` to the include list)
- Create: `aus/build.gradle.kts`
- Modify: `app/build.gradle.kts` (add `implementation(project(":aus"))` alongside the other module deps)
- Modify: `documents/src/main/java/com/msfg/los/documents/domain/DocumentType.java` (add `AUS_FINDINGS,` on the line before `OTHER` — additive, never reorder existing constants)
- Create: all 11 enums listed in the header contract, one file each under `aus/src/main/java/com/msfg/los/aus/domain/` (web-only `AusRunSelection` goes in `aus/src/main/java/com/msfg/los/aus/web/dto/`)

- [ ] **Step 1:** `aus/build.gradle.kts` — copy `pricing/build.gradle.kts` verbatim, then add `implementation(project(":documents"))` and `implementation(project(":parties"))` to its dependencies (keep its `platform`/`loan-core` deps). Each enum is a plain public enum, e.g.:
```java
package com.msfg.los.aus.domain;
public enum AusVendor { DU, LPA }
```
(`AusRecommendation`, `CredentialVendor`, etc. — exactly the constant sets from the header contract.)
- [ ] **Step 2:** `./gradlew :aus:compileJava :app:compileJava :documents:compileJava --console=plain` → BUILD SUCCESSFUL.
- [ ] **Step 3:** Commit: `chore(aus): scaffold module + vendor/credit enums + DocumentType.AUS_FINDINGS`

### Task 2: Migration V15

**Files:**
- Create: `app/src/main/resources/db/migration/V15__aus_credit.sql`

- [ ] **Step 1:** Write V15 — FIRST open `V14__pricing.sql` and copy its exact RLS/grant block style. Content:
```sql
-- V15: AUS + credit vendor module — vendor_credential, aus_profile, credit_order, aus_run

CREATE TABLE vendor_credential (
    id                      uuid PRIMARY KEY,
    org_id                  uuid NOT NULL,
    loan_id                 uuid NULL,            -- NULL = org default; non-null = per-loan override
    vendor                  varchar(20) NOT NULL, -- DU | LPA | CREDIT
    institution_id          varchar(80),
    seller_servicer_number  varchar(80),
    tpo_number              varchar(80),
    branch_number           varchar(80),
    credit_provider_code    varchar(40),
    credit_affiliate_code   varchar(40),
    username                varchar(1024),        -- encrypted (AES-GCM ciphertext)
    password                varchar(1024),        -- encrypted
    credit_username         varchar(1024),        -- encrypted
    credit_password         varchar(1024),        -- encrypted
    version                 bigint NOT NULL DEFAULT 0,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_vendor_credential_org ON vendor_credential (org_id, vendor) WHERE loan_id IS NULL;
CREATE UNIQUE INDEX uq_vendor_credential_loan ON vendor_credential (org_id, vendor, loan_id) WHERE loan_id IS NOT NULL;
CREATE INDEX idx_vendor_credential_org_loan ON vendor_credential (org_id, loan_id);

CREATE TABLE aus_profile (
    id           uuid PRIMARY KEY,
    org_id       uuid NOT NULL,
    loan_id      uuid NOT NULL,
    du_settings  jsonb NOT NULL DEFAULT '{}',
    lpa_settings jsonb NOT NULL DEFAULT '{}',
    version      bigint NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_aus_profile UNIQUE (org_id, loan_id)
);

CREATE TABLE credit_order (
    id                        uuid PRIMARY KEY,
    org_id                    uuid NOT NULL,
    loan_id                   uuid NOT NULL,
    provider_code             varchar(40),
    action                    varchar(20) NOT NULL,  -- SUBMIT|FORCE_NEW|REISSUE|UPGRADE
    request_type              varchar(20) NOT NULL,  -- INDIVIDUAL|JOINT
    equifax                   boolean NOT NULL DEFAULT true,
    experian                  boolean NOT NULL DEFAULT true,
    trans_union               boolean NOT NULL DEFAULT true,
    borrower_ids              jsonb NOT NULL DEFAULT '[]',
    status                    varchar(20) NOT NULL,  -- PENDING|COMPLETE|ERROR
    credit_report_identifier  varchar(120),
    scores                    jsonb NOT NULL DEFAULT '[]',
    report_document_id        uuid,
    requested_by              varchar(120),
    requested_at              timestamptz NOT NULL DEFAULT now(),
    error_message             varchar(1000),
    version                   bigint NOT NULL DEFAULT 0,
    created_at                timestamptz NOT NULL DEFAULT now(),
    updated_at                timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_credit_order_org_loan ON credit_order (org_id, loan_id, requested_at);

CREATE TABLE aus_run (
    id                        uuid PRIMARY KEY,
    org_id                    uuid NOT NULL,
    loan_id                   uuid NOT NULL,
    vendor                    varchar(10) NOT NULL,  -- DU|LPA
    status                    varchar(20) NOT NULL,
    vendor_case_id            varchar(120),
    vendor_transaction_id     varchar(120),
    recommendation            varchar(40),
    raw_recommendation        varchar(120),
    raw_eligibility           varchar(120),
    credit_report_identifier  varchar(120),
    findings_html_document_id uuid,
    findings_xml_document_id  uuid,
    messages                  jsonb NOT NULL DEFAULT '[]',
    requested_by              varchar(120),
    requested_at              timestamptz NOT NULL DEFAULT now(),
    error_message             varchar(1000),
    version                   bigint NOT NULL DEFAULT 0,
    created_at                timestamptz NOT NULL DEFAULT now(),
    updated_at                timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_aus_run_org_loan ON aus_run (org_id, loan_id, requested_at);
```
then, for EACH of the four tables, append the V14-style RLS block (ALTER TABLE … ENABLE ROW LEVEL SECURITY; FORCE; CREATE POLICY tenant_isolation … USING (org_id = nullif(current_setting('app.current_org', true), '')::uuid) WITH CHECK (same); GRANT SELECT, INSERT, UPDATE, DELETE … TO app_user;) — copy the exact phrasing from V14.
- [ ] **Step 2:** `./gradlew :app:test --tests '*OpenApiDocsIT' --console=plain` (boots the context → Flyway applies V15 in a fresh Testcontainer) → BUILD SUCCESSFUL.
- [ ] **Step 3:** Commit: `feat(aus): V15 — vendor_credential, aus_profile, credit_order, aus_run (RLS + partial uniques)`

### Task 3: Entities + repositories

**Files:**
- Create: `aus/src/main/java/com/msfg/los/aus/domain/{VendorCredential,AusProfile,CreditOrder,AusRun}.java`, the 3 jsonb records (`CreditReference`, `AusVendorSettings`, `CreditScoreEntry`)
- Create: `aus/src/main/java/com/msfg/los/aus/repo/{VendorCredentialRepository,AusProfileRepository,CreditOrderRepository,AusRunRepository}.java`

- [ ] **Step 1:** Entities extend `TenantScopedEntity` (mirror `pricing/.../RateLock.java` for base usage). `VendorCredential`: `@Enumerated(STRING) CredentialVendor vendor`, nullable `UUID loanId`, plain identity columns, and the four secret fields each annotated `@Convert(converter = EncryptedStringConverter.class)` (import `com.msfg.los.platform.crypto.EncryptedStringConverter`; see `parties/.../BorrowerParty.java:38`). **Never add @ToString/@Data — secrets class.** `AusProfile`: unique loanId + two `@JdbcTypeCode(SqlTypes.JSON) AusVendorSettings` columns (mirror `coc/.../CocDraft.java:29`). `CreditOrder`: enums STRING, `@JdbcTypeCode(SqlTypes.JSON) List<UUID> borrowerIds`, `@JdbcTypeCode(SqlTypes.JSON) List<CreditScoreEntry> scores`. `AusRun`: enums STRING, `@JdbcTypeCode(SqlTypes.JSON) List<String> messages`.
- [ ] **Step 2:** Repositories (all extend `JpaRepository<X, UUID>`):
```java
// VendorCredentialRepository
Optional<VendorCredential> findByOrgIdAndVendorAndLoanIdIsNull(UUID orgId, CredentialVendor vendor);
Optional<VendorCredential> findByOrgIdAndVendorAndLoanId(UUID orgId, CredentialVendor vendor, UUID loanId);
List<VendorCredential> findByOrgIdAndLoanIdIsNull(UUID orgId);
Optional<VendorCredential> findByIdAndOrgId(UUID id, UUID orgId);
// AusProfileRepository
Optional<AusProfile> findByLoanId(UUID loanId);
Optional<AusProfile> findByIdAndOrgId(UUID id, UUID orgId);
// CreditOrderRepository / AusRunRepository
List<CreditOrder> findByLoanIdOrderByRequestedAtDescIdDesc(UUID loanId);
Optional<CreditOrder> findByIdAndOrgId(UUID id, UUID orgId);
List<AusRun> findByLoanIdOrderByRequestedAtDescIdDesc(UUID loanId);
Optional<AusRun> findByIdAndOrgId(UUID id, UUID orgId);
```
- [ ] **Step 3:** `./gradlew :aus:compileJava` → SUCCESS. Commit: `feat(aus): entities + repos (encrypted credential columns, jsonb settings/scores)`

### Task 4: Ports + deterministic stub adapters (TDD — unit tests first)

**Files:**
- Create (records+interfaces in `aus/src/main/java/com/msfg/los/aus/service/`): `AusVendorPort.java`, `CreditVendorPort.java`, `AusSubmission.java`, `AusLoanFile.java`, `ResolvedCredentials.java`, `CreditWiring.java`, `BorrowerCredit.java`, `AusVendorResult.java`, `VendorArtifact.java`, `CreditVendorRequest.java`, `CreditBorrower.java`, `CreditVendorResult.java`
- Create: `aus/src/main/java/com/msfg/los/aus/adapter/{StubAusVendorAdapter,StubCreditVendorAdapter}.java`
- Test: `aus/src/test/java/com/msfg/los/aus/adapter/{StubAusVendorAdapterTest,StubCreditVendorAdapterTest}.java`

- [ ] **Step 1:** The contracts (records exactly as below; javadoc on each port describing the REAL wire mapping — DU: MISMO 3.4 ULAD + DU Wrapper POST, casefile lifecycle, Technology Manager creds; LPA: S2S v6.1, LPA Key + transaction number, OAuth 4h tokens; credit: MISMO 2.x XML POST, operator auth, reissue via creditReportIdentifier):
```java
public interface AusVendorPort { AusVendorResult submit(AusSubmission submission); }
public interface CreditVendorPort { CreditVendorResult order(CreditVendorRequest request); }
public record AusSubmission(AusVendor vendor, UUID loanId, String existingCaseId,
        ResolvedCredentials credentials, CreditWiring creditWiring, AusLoanFile loanFile) {}
public record AusLoanFile(String loanNumber, BigDecimal noteAmount, BigDecimal propertyValue,
        BigDecimal interestRate, Integer termMonths, int borrowerCount, String fhaCaseNumber) {}
public record ResolvedCredentials(CredentialSource source, String institutionId, String sellerServicerNumber,
        String tpoNumber, String branchNumber, String username, String password,
        String creditProviderCode, String creditAffiliateCode, String creditUsername, String creditPassword) {}
public record CreditWiring(String creditProviderCode, String creditAffiliateCode, List<BorrowerCredit> perBorrower) {}
public record BorrowerCredit(UUID borrowerId, CreditOrderAction action, String creditReportIdentifier) {}
public record AusVendorResult(String vendorCaseId, String vendorTransactionId, AusRecommendation recommendation,
        String rawRecommendation, String rawEligibility, List<String> messages, List<VendorArtifact> artifacts) {}
public record VendorArtifact(String name, String contentType, byte[] bytes) {}
public record CreditVendorRequest(UUID loanId, String providerCode, CreditOrderAction action,
        CreditRequestType requestType, boolean equifax, boolean experian, boolean transUnion,
        List<CreditBorrower> borrowers, String creditReportIdentifier) {}
public record CreditBorrower(UUID borrowerId, String firstName, String lastName) {}
public record CreditVendorResult(String creditReportIdentifier, List<CreditScoreEntry> scores, VendorArtifact report) {}
```
- [ ] **Step 2 (RED):** unit tests asserting stub behavior BEFORE writing the stubs:
  - determinism: same loanId+vendor → same `vendorCaseId` twice; DU ids start `DU-`, LPA `LPA-`, credit refs `XS-`.
  - existingCaseId passthrough: submission with `existingCaseId` → result echoes it as `vendorCaseId`.
  - recommendation rules: complete file, LTV ≤ 97 → DU `APPROVE_ELIGIBLE` (raw "Approve/Eligible") / LPA `ACCEPT` (raw "Accept", rawEligibility "Freddie Mac Eligible"); LTV > 97 (note 400k, value 380k) → DU `APPROVE_INELIGIBLE`, LPA `CAUTION`; null noteAmount or null interestRate or borrowerCount 0 → `OUT_OF_SCOPE` (DU) / `CAUTION` w/ message (LPA: still CAUTION + "incomplete file" message; rawEligibility "Freddie Mac Ineligible").
  - artifacts: exactly 2 (name `findings.html` text/html containing the recommendation string + casefile id; `findings.xml` application/xml containing `<Recommendation>`).
  - credit stub: scores ∈ [660,790], one `CreditScoreEntry` per borrower×selected bureau (JOINT, 2 borrowers, 3 bureaus → 6), deterministic (call twice → equal lists); REISSUE with identifier → same identifier echoed; SUBMIT/FORCE_NEW → minted `XS-` id stable per loanId; report artifact text/html contains each borrower's name.
  Run `./gradlew :aus:test` → compilation failures/red as expected.
- [ ] **Step 3 (GREEN):** implement both stubs as `@Component`. Determinism via `new Random(loanId.getMostSignificantBits() ^ vendor.ordinal())` (or `^ providerCode.hashCode()` for credit). Case ids: `"%s-%010d".formatted(prefix, Math.abs(seedRandom.nextLong() % 10_000_000_000L))`. Keep rule logic in small private methods with the spec's rule comments.
- [ ] **Step 4:** `./gradlew :aus:test` → green. Commit: `feat(aus): AusVendorPort + CreditVendorPort + deterministic stub adapters (TDD)`

### Task 5: Org credentials — SecurityConfig gate + service + controller (TDD via IT)

**Files:**
- Modify: `app/src/main/java/com/msfg/los/config/SecurityConfig.java` (add `.requestMatchers("/api/org/**").hasRole("ADMIN")` immediately BEFORE the `.requestMatchers("/api/**").authenticated()` line)
- Create: `aus/src/main/java/com/msfg/los/aus/service/VendorCredentialService.java`, `aus/src/main/java/com/msfg/los/aus/web/OrgVendorCredentialController.java`, DTOs `aus/src/main/java/com/msfg/los/aus/web/dto/{UpsertVendorCredentialRequest,VendorCredentialResponse}.java`
- Test: `app/src/test/java/com/msfg/los/aus/web/VendorCredentialIT.java`

- [ ] **Step 1 (RED):** IT (mirror `RoleAccessIT` jwt helpers; `as(sub, "ROLE_ADMIN")`):
  - `adminUpsertsAndReadsMaskedOrgCredential`: PUT `/api/org/vendor-credentials/DU` `{institutionId:"INST123", username:"fannie-user", password:"s3cret-pw", creditProviderCode:"1", creditUsername:"cred-user", creditPassword:"cred-pw"}` → 200; GET `/api/org/vendor-credentials` → list contains DU entry with `institutionId=="INST123"`, `usernameSet==true`, `usernameMasked=="f•••r"`, `passwordSet==true` — and **the full response body string contains NEITHER "s3cret-pw" NOR "fannie-user" NOR "cred-pw"** (read content as string, assertThat(body).doesNotContain(...)).
  - `replaceOnlySemantics`: second PUT with `{institutionId:"INST999"}` (no secrets) → 200; GET → `institutionId=="INST999"` AND `passwordSet` still `true`; third PUT with `{password:""}` → `passwordSet==false`.
  - `encryptedAtRest`: JdbcTemplate `select password from vendor_credential where vendor='DU' and loan_id is null` → value `isNotEqualTo("s3cret-pw")` and does not contain it.
  - `nonAdminForbidden`: PUT as `ROLE_LO` → 403; as `ROLE_PROCESSOR` → 403. `noToken401`.
  Run → 404/403 reds (no controller yet).
- [ ] **Step 2 (GREEN):** `VendorCredentialService`: `upsertOrg(CredentialVendor v, UpsertVendorCredentialRequest req)` — find `findByOrgIdAndVendorAndLoanIdIsNull(org(), v)` or new; apply: identity fields = overwrite when non-null; secret fields = `null→keep, ""→clear, value→set`. `listOrg()` → masked responses. Masking helper:
```java
static String mask(String v) {
    if (v == null || v.isBlank()) return null;
    return v.length() <= 2 ? "••" : v.charAt(0) + "•••" + v.charAt(v.length() - 1);
}
```
`VendorCredentialResponse(CredentialVendor vendor, String institutionId, String sellerServicerNumber, String tpoNumber, String branchNumber, String creditProviderCode, String creditAffiliateCode, boolean usernameSet, String usernameMasked, boolean passwordSet, boolean creditUsernameSet, String creditUsernameMasked, boolean creditPasswordSet)` — **no secret-valued fields exist on the response type.** Controller: `@RestController @RequestMapping("/api/org/vendor-credentials")`, `GET ""` list, `PUT "/{vendor}"` upsert; org() from `TenantContext` (mirror `FeeService.org()`).
- [ ] **Step 3:** IT green; commit: `feat(aus): org vendor credentials — ROLE_ADMIN gate, encrypted at rest, masked write-only responses`

### Task 6: Loan credential overrides + resolution

**Files:**
- Modify: `VendorCredentialService` (+ loan-scope methods + resolution)
- Create: `aus/src/main/java/com/msfg/los/aus/web/LoanVendorCredentialController.java`
- Test: extend `VendorCredentialIT`

- [ ] **Step 1 (RED):** IT additions: `loanOverrideUpsertAndDelete` (PUT `/api/loans/{loanId}/aus/credentials/DU` as the owning LO → 200 masked; DELETE → 204; cross-org PUT → 404); `resolutionPrecedence` — covered end-to-end in Task 9 via `credentialSource`, here assert service-level: (a) org-only → resolve(...).source()==ORG; (b) loan override present → LOAN with the override's values; (c) neither → NONE. (Service-level asserts via `@Autowired VendorCredentialService` inside the IT class with `TenantContextHolder` set — mirror how service-level ITs set tenant in `parties` service tests.)
- [ ] **Step 2 (GREEN):** service methods `upsertLoan(UUID loanId, CredentialVendor, req)` / `deleteLoan(loanId, vendor)` / `listLoan(loanId)` (all `accessGuard.assertCanAccess(loanService.get(loanId))` first), and:
```java
public ResolvedCredentials resolve(UUID loanId, CredentialVendor vendor) {
    VendorCredential c = creds.findByOrgIdAndVendorAndLoanId(org(), vendor, loanId)
            .orElseGet(() -> creds.findByOrgIdAndVendorAndLoanIdIsNull(org(), vendor).orElse(null));
    if (c == null) return new ResolvedCredentials(CredentialSource.NONE, null, null, null, null, null, null, null, null, null, null);
    CredentialSource src = c.getLoanId() != null ? CredentialSource.LOAN : CredentialSource.ORG;
    return new ResolvedCredentials(src, c.getInstitutionId(), c.getSellerServicerNumber(), c.getTpoNumber(),
            c.getBranchNumber(), c.getUsername(), c.getPassword(), c.getCreditProviderCode(),
            c.getCreditAffiliateCode(), c.getCreditUsername(), c.getCreditPassword());
}
```
Whole-row precedence (no field merging) — comment that in code.
- [ ] **Step 3:** green; commit: `feat(aus): per-loan credential overrides + whole-row resolution (loan > org > none)`

### Task 7: AUS profile (settings + refs) GET/PUT

**Files:**
- Create: `aus/src/main/java/com/msfg/los/aus/service/AusProfileService.java`, `aus/src/main/java/com/msfg/los/aus/web/AusProfileController.java` (paths live under `/api/loans/{loanId}/aus`), DTOs `{AusProfileResponse,UpsertAusProfileRequest}.java`
- Test: `app/src/test/java/com/msfg/los/aus/web/AusProfileIT.java`

- [ ] **Step 1 (RED):** IT: `getBeforeAnySaveReturnsEmpty200` (du/lpa present with null fields + empty creditReferences + `credentialSource:"NONE"`); `putRoundTripsSettingsAndRefs` (PUT du settings `{issueMode:"REISSUE", creditProviderCode:"1", fhaCaseNumber:"011-1234567", creditReferences:[{borrowerId, reference:"ABC123"}]}` with a REAL borrower created via the parties API → GET echoes exactly; fresh GET, not the PUT echo); `unknownBorrowerRefRejected400` (random borrowerId in refs → 400, `$.message` contains "borrower"); `credentialSourceReflectsOrgCreds` (PUT org DU creds as ADMIN → profile GET shows du `credentialSource:"ORG"`); cross-org 404; no-token 401.
- [ ] **Step 2 (GREEN):** `AusProfileResponse(AusVendorSettingsView du, AusVendorSettingsView lpa)` where `AusVendorSettingsView` = `AusVendorSettings` fields + `CredentialSource credentialSource`. Service: GET = find-or-empty + `vendorCredentialService.resolve(loanId, DU/LPA).source()`; PUT = upsert 1:1 row, validating every `creditReferences[].borrowerId` against `BorrowerRepository.findByLoanIdOrderByOrdinalAsc(loanId)` membership (throw `ValidationException("creditReferences: unknown borrower " + id)`). Guard every method.
- [ ] **Step 3:** green; commit: `feat(aus): per-loan AUS profile (issue mode, provider, FHA case, per-borrower credit refs) + credentialSource`

### Task 8: Credit orders (TDD via IT — crown jewel scores)

**Files:**
- Create: `aus/src/main/java/com/msfg/los/aus/service/CreditOrderService.java`, `aus/src/main/java/com/msfg/los/aus/web/CreditOrderController.java`, DTOs `{CreditOrderRequest,CreditOrderResponse}.java` (web dto `CreditOrderRequest(CreditOrderAction action, CreditRequestType requestType, Boolean equifax, Boolean experian, Boolean transUnion, List<UUID> borrowerIds, String creditReportIdentifier)`; bureaus default true when null)
- Test: `app/src/test/java/com/msfg/los/aus/web/CreditOrderIT.java`

- [ ] **Step 1 (RED):** IT: `orderJointTriMergeProducesScoresAndReport` — create loan + 2 borrowers; POST `/api/loans/{loanId}/credit/order` `{action:"SUBMIT", requestType:"JOINT", borrowerIds:[b1,b2]}` → 201: `creditReportIdentifier` starts `XS-`, `scores` length 6, `status:"COMPLETE"`, `reportDocumentId` non-null; **JDBC crown jewel**: `select count(*) from document where id = ?::uuid` == 1 AND jsonb scores length via `select jsonb_array_length(scores) from credit_order` == 6; download `GET /api/loans/{loanId}/documents/{reportDocumentId}/content` → 200, body contains borrower 1's first name. `reissueReusesIdentifierNewRow`: POST REISSUE with the returned identifier → 201 new order id, same identifier, JDBC `count(*) from credit_order where loan_id` == 2. `unknownBorrower400`, `reissueWithoutIdentifier400` (`$.fields.creditReportIdentifier` present — bean validation can't express it; service throws `ValidationException("creditReportIdentifier is required for REISSUE")` and the test asserts `$.message` contains it), cross-org 404, history endpoint newest-first.
- [ ] **Step 2 (GREEN):** service: guard → validate borrowers (membership via parties repo; load names for `CreditBorrower`) → `CreditVendorPort.order(...)` with providerCode from the loan profile (du settings) or credential row (fallback chain: profile.du.creditProviderCode → resolve(loanId, CREDIT).creditProviderCode → null is allowed for stub) → persist row (status COMPLETE, scores, identifier) → store report via `documentService.storeGenerated(loanId, DocumentType.CREDIT_REPORT, "credit", "credit-report-" + identifier + ".html", "text/html", artifact.bytes())` → save doc id. `requested_by` = `currentUser.id().orElse(null)`. History: `GET /api/loans/{loanId}/credit/orders`.
- [ ] **Step 3:** green; commit: `feat(aus): credit orders — port-backed tri-merge with scores, stored report artifact, reissue semantics`

### Task 9: AUS runs (TDD via IT — crown jewel DU E2E + ONE_CLICK)

**Files:**
- Create: `aus/src/main/java/com/msfg/los/aus/service/AusRunService.java`, `aus/src/main/java/com/msfg/los/aus/web/AusRunController.java`, DTOs `{AusRunRequest,AusRunResponse}.java` (`AusRunRequest(AusRunSelection vendor)`; response mirrors `aus_run` row incl. document ids)
- Test: `app/src/test/java/com/msfg/los/aus/web/AusRunIT.java`

- [ ] **Step 1 (RED):** IT:
  - `duRunWithReissueRefsEndToEnd` (crown jewel): ADMIN PUTs org DU creds; LO creates loan (PATCH noteAmount 300000, estimatedValue/appraised 400000, interestRate 6.5, termMonths 360 — use the loan PATCH fields from `LoanControllerIT` idiom), adds borrower, PUTs profile (REISSUE + ref); POST `/api/loans/{loanId}/aus/run` `{vendor:"DU"}` → 201 list size 1: `vendorCaseId` starts `DU-`, `recommendation:"APPROVE_ELIGIBLE"`, `status:"COMPLETE"`, `creditReportIdentifier:"ABC123"` (the profile ref), both findings doc ids non-null; download the HTML findings → 200 + body contains "Approve/Eligible" and the casefile id; JDBC: `select count(*) from aus_run where loan_id` == 1.
  - `oneClickRunsBothVendors`: → 201 list size 2, vendors DU+LPA, history `GET /api/loans/{loanId}/aus/history` size 2 newest-first.
  - `missingCredentials409`: fresh org-cred-free loan (delete org creds first or use vendor LPA with no creds) → 409, `$.code=="MISSING_CREDENTIALS"`... (`DomainException` subclass with that code — create `MissingCredentialsException extends DomainException` status 409 code `MISSING_CREDENTIALS` in `aus/.../service`; mirror an existing DomainException subclass, e.g. pricing's `LOCK_STATE_CONFLICT` exception class, for constructor shape).
  - `reissueWithoutRefs400`: profile REISSUE with empty refs → 400 with `$.fields.creditReferences` OR `$.message` containing "creditReferences" (service throws `ValidationException("creditReferences required for REISSUE mode")`).
  - `orderModeMintsCreditOrder`: profile issueMode ORDER, no refs → run 201 + JDBC `count(*) from credit_order where loan_id` == 1 and run's `creditReportIdentifier` == that order's identifier.
  - `resubmitKeepsCaseId`: run DU twice → second run's `vendorCaseId` equals the first's (service passes prior run's case id as `existingCaseId`).
  - cross-org 404; no-token 401.
- [ ] **Step 2 (GREEN):** `AusRunService.run(UUID loanId, AusRunSelection sel)`: guard once; vendors = sel==ONE_CLICK ? [DU, LPA] : [sel-mapped]. Per vendor: resolve creds (NONE → `MissingCredentialsException(vendor)`); profile settings for the vendor (empty defaults OK); wiring: issueMode REISSUE → require non-empty refs (ValidationException) and map to `BorrowerCredit(b, REISSUE, ref)`; ORDER → call `creditOrderService.orderForAusRun(loanId, providerCode)` (internal variant ordering for all loan borrowers, JOINT if >1, all bureaus) and wire its identifier with action SUBMIT. Build `AusLoanFile` from `loanService.get(loanId)` (loanNumber, noteAmount, `estimatedValue`-or-`appraisedValue` whichever the Loan getters expose — check `Loan` getters and prefer appraised, falling back to estimated; interestRate, termMonths, borrower count via parties repo, fhaCaseNumber from settings). `existingCaseId` = latest prior run for (loan, vendor) via `findByLoanIdOrderByRequestedAtDescIdDesc` filtered by vendor. Submit; persist run (COMPLETE w/ result fields; on adapter exception → persist ERROR row + rethrow as DomainException? NO — let it bubble to 500 only if truly unexpected; stubs don't throw. Keep: any RuntimeException → save ERROR row with message, then rethrow). Store artifacts: html → `storeGenerated(loanId, AUS_FINDINGS, vendor.name(), vendor.name().toLowerCase()+"-findings-"+caseId+".html", "text/html", bytes)`; xml same with `.xml`/`application/xml`.
- [ ] **Step 3:** green; commit: `feat(aus): AUS runs — credential resolution, reissue/order wiring, ONE_CLICK, findings artifacts, history`

### Task 10: Negative/role coverage sweep

**Files:** extend `AusRunIT`/`CreditOrderIT`/`VendorCredentialIT`
- [ ] Add: PLATFORM_ADMIN → 403 on `GET /api/loans/{loanId}/aus/profile` (access model pin); PROCESSOR (non-owner, org-wide) → 200 on profile GET + 201 on a run (back-office can run AUS); LO on another LO's loan → 403; bad enum body on run (`{vendor:"BOTH"}`) → 400 `VALIDATION_ERROR` (handler); credential PUT to `/api/loans/{loanId}/aus/credentials/CREDIT` works for loan-scope (vendor CREDIT allowed at both scopes).
- [ ] Run all three IT classes green. Commit: `test(aus): role/negative coverage — org-wide ops roles, PLATFORM_ADMIN pin, enum 400s`

### Task 11: RLS IT

**Files:** Create `app/src/test/java/com/msfg/los/aus/AusRlsIT.java`
- [ ] Mirror `app/src/test/java/com/msfg/los/coc/CocRlsIT.java` (SET ROLE app_user, fresh orgs, fail-closed) covering all four tables: insert as org A, `set app.current_org` to org B → 0 rows; no GUC → 0 rows (NIL); WITH CHECK blocks cross-org insert. Green → commit: `test(aus): RLS coverage for vendor_credential/aus_profile/credit_order/aus_run`

### Task 12: Full build + OpenAPI guard + sweep

- [ ] `./gradlew build --console=plain` → BUILD SUCCESSFUL (expect ~345+: 311 + ~35 new). If a failure traces to files you did not author, STOP and report — do not "fix" foreign files.
- [ ] Duplicate-simple-name sweep:
```bash
for n in AusVendor CredentialVendor AusRecommendation AusRunStatus CreditOrderStatus CreditBureau CreditOrderAction CreditRequestType AusIssueMode CredentialSource AusRunSelection AusProfileResponse UpsertAusProfileRequest AusRunRequest AusRunResponse CreditOrderRequest CreditOrderResponse VendorCredentialResponse UpsertVendorCredentialRequest CreditReference AusVendorSettings CreditScoreEntry VendorArtifact; do c=$(grep -rl "\b\(class\|enum\|record\|interface\) $n\b" --include='*.java' . | wc -l | tr -d ' '); [ "$c" -gt 1 ] && echo "DUPLICATE: $n"; done; echo sweep-done
```
Expected: `sweep-done` only. (`CreditOrderRequest` exists twice by design? NO — the port-side record is `CreditVendorRequest`; web dto is `CreditOrderRequest` — distinct names, no dup.)
- [ ] Commit anything outstanding.

## Post-merge protocol (finish-branch time, NOT plan tasks)
Opus security pass (secrets/FCRA/resolution) → merge to `main` per worktree protocol (ff via `git fetch . feat/aus-credit:main` only when main unchanged AND not checked out elsewhere; else `--ff-only`/`--no-ff` in the parked shared checkout) → full build on merged tree → restart bootRun + verify `/v3/api-docs` markers (`AusRunResponse`, `vendor-credentials`, `credit/order`) → FE handoff (endpoints, masking contract, credentialSource, ONE_CLICK) → docs/frontend-integration.md + CLAUDE.md + memory + ROADMAP.

## Self-Review
Spec coverage: 4 tables (T2/T3) ✓ ports+stubs shaped to research (T4) ✓ org+loan creds, masked/write-only/replace-only/encrypted (T5/T6) ✓ profile+refs+credentialSource (T7) ✓ credit orders incl. reissue artifact rule (T8) ✓ runs incl. ONE_CLICK/MISSING_CREDENTIALS/ORDER-mode/resubmit-case-id (T9) ✓ role/negative (T10) ✓ RLS (T11) ✓ OpenAPI+sweep (T12) ✓ async-safe status fields (T2 cols, T8/T9 persist) ✓. Type consistency: port records defined once (T4), used in T6 (`ResolvedCredentials`), T8/T9; enums header-contracted. No placeholders; the one deliberate judgment call (Loan value getter: appraised-vs-estimated fallback) is delegated WITH the decision rule stated. ✓
