package com.msfg.los.identity.web;

import com.msfg.los.platform.crypto.OtpHasher;
import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Staff-initiated borrower verification (security spec §6.2/§6.3), end-to-end through the filter +
 * service + DB. Send-path tests exercise authz/IDOR/rate-limit and assert (via JDBC) the code is stored
 * hashed; verify-path tests SEED a verification_request via JDBC with a known code's salted hash so the
 * TTL/lockout/single-use lifecycle is deterministic without ever exposing the generated code.
 */
class BorrowerVerificationControllerIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    // A fresh owning-LO sub PER TEST. The per-staff send throttle (≤3 / 15 min per sub) accumulates
    // across the whole JVM run (rows aren't cleaned between tests), so a single shared LO sub would trip
    // 429 in later tests. A fresh sub per test isolates the throttle to that test's sends.
    private String freshSub() {
        return UUID.randomUUID().toString();
    }

    private RequestPostProcessor loSub(String sub) {
        return jwt().jwt(j -> j.subject(sub).claim("org_id", DEFAULT_ORG))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));
    }

    private RequestPostProcessor processor() {
        return jwt().jwt(j -> j.subject(freshSub()).claim("org_id", DEFAULT_ORG))
                .authorities(new SimpleGrantedAuthority("ROLE_PROCESSOR"));
    }

    private RequestPostProcessor role(String roleAuthority) {
        return jwt().jwt(j -> j.subject(freshSub()).claim("org_id", DEFAULT_ORG))
                .authorities(new SimpleGrantedAuthority(roleAuthority));
    }

    /** Create a loan owned by {@code ownerSub} (so that sub is the owning LO). */
    private String createLoan(String ownerSub) throws Exception {
        var res = mvc.perform(post("/api/loans").with(loSub(ownerSub))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(ownerSub)))
                .andExpect(status().isCreated())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    private String addBorrowerWithEmail(String loanId, String ownerSub, String email) throws Exception {
        var res = mvc.perform(post("/api/loans/{id}/borrowers", loanId).with(loSub(ownerSub))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Jane\",\"lastName\":\"Doe\",\"primary\":false,\"email\":\"%s\"}"
                                .formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    private String sendBody(String loanId) {
        return "{\"channel\":\"EMAIL\",\"loanId\":\"%s\"}".formatted(loanId);
    }

    // ── send-verification authorization ───────────────────────────────────────

    @Test
    void loOnOwnedLoanCanSend204() throws Exception {
        String owner = freshSub();
        String loanId = createLoan(owner);
        String borrowerId = addBorrowerWithEmail(loanId, owner, "jane@example.com");

        mvc.perform(post("/api/identity/borrowers/{b}/send-verification", borrowerId).with(loSub(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(sendBody(loanId)))
                .andExpect(status().isNoContent());

        // Crown-jewel: a row was persisted and the code is stored HASHED, never plaintext.
        var row = jdbc.queryForMap(
                "select code_hash, code_salt, channel, org_id, attempts, consumed_at "
                        + "from verification_request where loan_id = ?::uuid and borrower_id = ?::uuid",
                loanId, borrowerId);
        assertThat((String) row.get("code_hash")).isNotBlank();
        assertThat((String) row.get("channel")).isEqualTo("EMAIL");
        assertThat(((Number) row.get("attempts")).intValue()).isZero();
        assertThat(row.get("consumed_at")).isNull();
        assertThat(row.get("org_id").toString()).isEqualTo(DEFAULT_ORG);
    }

    @Test
    void loOnNonOwnedLoanGets403() throws Exception {
        String owner = freshSub();
        String loanId = createLoan(owner);                       // owned by `owner`
        String borrowerId = addBorrowerWithEmail(loanId, owner, "jane@example.com");

        // a DIFFERENT LO (not the owner) — passes the filter (ROLE_LO) but fails the service access guard
        mvc.perform(post("/api/identity/borrowers/{b}/send-verification", borrowerId).with(loSub(freshSub()))
                        .contentType(MediaType.APPLICATION_JSON).content(sendBody(loanId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void borrowerRoleDeniedAtFilter403() throws Exception {
        String owner = freshSub();
        String loanId = createLoan(owner);
        String borrowerId = addBorrowerWithEmail(loanId, owner, "jane@example.com");

        mvc.perform(post("/api/identity/borrowers/{b}/send-verification", borrowerId)
                        .with(role("ROLE_BORROWER"))
                        .contentType(MediaType.APPLICATION_JSON).content(sendBody(loanId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void agentRoleDeniedAtFilter403() throws Exception {
        String owner = freshSub();
        String loanId = createLoan(owner);
        String borrowerId = addBorrowerWithEmail(loanId, owner, "jane@example.com");

        mvc.perform(post("/api/identity/borrowers/{b}/send-verification", borrowerId)
                        .with(role("ROLE_REAL_ESTATE_AGENT"))
                        .contentType(MediaType.APPLICATION_JSON).content(sendBody(loanId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void noToken401() throws Exception {
        mvc.perform(post("/api/identity/borrowers/{b}/send-verification", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(sendBody(UUID.randomUUID().toString())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void crossLoanBorrowerIdReturnsGeneric204NoLeak() throws Exception {
        String owner = freshSub();
        String loanId = createLoan(owner);                       // accessible loan, but borrower not on it
        String otherOwner = freshSub();
        String otherLoan = createLoan(otherOwner);
        String borrowerOnOtherLoan = addBorrowerWithEmail(otherLoan, otherOwner, "elsewhere@example.com");

        // Borrower exists but belongs to a DIFFERENT loan → must return the SAME generic 204, NOT 404.
        mvc.perform(post("/api/identity/borrowers/{b}/send-verification", borrowerOnOtherLoan).with(loSub(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(sendBody(loanId)))
                .andExpect(status().isNoContent());

        // A row IS created (audit + throttle) but with NO dispatch (resolveContact returned null for the
        // (accessible-loan, foreign-borrower) pair) — the response is indistinguishable from a real send.
        Long count = jdbc.queryForObject(
                "select count(*) from verification_request where loan_id = ?::uuid and borrower_id = ?::uuid",
                Long.class, loanId, borrowerOnOtherLoan);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void nonExistentBorrowerReturnsGeneric204() throws Exception {
        String owner = freshSub();
        String loanId = createLoan(owner);
        mvc.perform(post("/api/identity/borrowers/{b}/send-verification", UUID.randomUUID()).with(loSub(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(sendBody(loanId)))
                .andExpect(status().isNoContent());
    }

    // ── validation ────────────────────────────────────────────────────────────

    @Test
    void missingChannelIs400() throws Exception {
        String owner = freshSub();
        String loanId = createLoan(owner);
        mvc.perform(post("/api/identity/borrowers/{b}/send-verification", UUID.randomUUID()).with(loSub(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loanId\":\"%s\"}".formatted(loanId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fields.channel").exists());
    }

    @Test
    void missingCodeIs400() throws Exception {
        String owner = freshSub();
        String loanId = createLoan(owner);
        mvc.perform(post("/api/identity/borrowers/{b}/verify-code", UUID.randomUUID()).with(loSub(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loanId\":\"%s\"}".formatted(loanId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fields.code").exists());
    }

    // ── rate-limit ──────────────────────────────────────────────────────────

    @Test
    void fourthSendInWindowIs429() throws Exception {
        String owner = freshSub();
        String loanId = createLoan(owner);
        String borrowerId = addBorrowerWithEmail(loanId, owner, "rl@example.com");

        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/identity/borrowers/{b}/send-verification", borrowerId).with(loSub(owner))
                            .contentType(MediaType.APPLICATION_JSON).content(sendBody(loanId)))
                    .andExpect(status().isNoContent());
        }
        // 4th send for the same (org, borrower) AND same acting sub within the window → 429.
        mvc.perform(post("/api/identity/borrowers/{b}/send-verification", borrowerId).with(loSub(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(sendBody(loanId)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
    }

    // ── verify-code lifecycle (seeded rows for deterministic codes) ────────────

    /** Seed a verification_request row with a known code's salted hash so verify is deterministic. */
    private void seedRow(String loanId, String borrowerId, String createdBy, String code, Instant expiresAt,
                         int attempts, boolean consumed) {
        String salt = OtpHasher.newSalt();
        String hash = OtpHasher.hash(code, salt);
        jdbc.update(
                "insert into verification_request "
                        + "(id, version, org_id, loan_id, borrower_id, channel, code_hash, code_salt, "
                        + " expires_at, attempts, consumed_at, created_at, created_by) "
                        + "values (?::uuid, 0, ?::uuid, ?::uuid, ?::uuid, 'EMAIL', ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), DEFAULT_ORG, loanId, borrowerId, hash, salt,
                java.sql.Timestamp.from(expiresAt), attempts,
                consumed ? java.sql.Timestamp.from(Instant.now()) : null,
                java.sql.Timestamp.from(Instant.now()), createdBy);
    }

    @Test
    void correctCodeInTtlVerifies204AndConsumes() throws Exception {
        String owner = freshSub();
        String loanId = createLoan(owner);
        String borrowerId = addBorrowerWithEmail(loanId, owner, "v@example.com");
        seedRow(loanId, borrowerId, owner, "424242", Instant.now().plus(5, ChronoUnit.MINUTES), 0, false);

        mvc.perform(post("/api/identity/borrowers/{b}/verify-code", borrowerId).with(loSub(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"424242\",\"loanId\":\"%s\"}".formatted(loanId)))
                .andExpect(status().isNoContent());

        // single-use: consumed_at now set.
        var row = jdbc.queryForMap(
                "select consumed_at from verification_request where loan_id = ?::uuid and borrower_id = ?::uuid",
                loanId, borrowerId);
        assertThat(row.get("consumed_at")).isNotNull();

        // replay of the same correct code now fails (consumed).
        mvc.perform(post("/api/identity/borrowers/{b}/verify-code", borrowerId).with(loSub(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"424242\",\"loanId\":\"%s\"}".formatted(loanId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void expiredCodeFailsGenerically() throws Exception {
        String owner = freshSub();
        String loanId = createLoan(owner);
        String borrowerId = addBorrowerWithEmail(loanId, owner, "exp@example.com");
        seedRow(loanId, borrowerId, owner, "111111", Instant.now().minus(1, ChronoUnit.MINUTES), 0, false);

        mvc.perform(post("/api/identity/borrowers/{b}/verify-code", borrowerId).with(loSub(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"111111\",\"loanId\":\"%s\"}".formatted(loanId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void wrongCodeIncrementsAttemptsAndFails() throws Exception {
        String owner = freshSub();
        String loanId = createLoan(owner);
        String borrowerId = addBorrowerWithEmail(loanId, owner, "w@example.com");
        seedRow(loanId, borrowerId, owner, "222222", Instant.now().plus(5, ChronoUnit.MINUTES), 0, false);

        mvc.perform(post("/api/identity/borrowers/{b}/verify-code", borrowerId).with(loSub(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"000000\",\"loanId\":\"%s\"}".formatted(loanId)))
                .andExpect(status().isBadRequest());

        // The increment SURVIVES the failed-verify rollback (REQUIRES_NEW recorder).
        Integer attempts = jdbc.queryForObject(
                "select attempts from verification_request where loan_id = ?::uuid and borrower_id = ?::uuid",
                Integer.class, loanId, borrowerId);
        assertThat(attempts).isEqualTo(1);
    }

    @Test
    void sixthWrongAttemptIsLockedAndCorrectCodeThenFails() throws Exception {
        String owner = freshSub();
        String loanId = createLoan(owner);
        String borrowerId = addBorrowerWithEmail(loanId, owner, "lock@example.com");
        // seed already at MAX_ATTEMPTS (5) → locked.
        seedRow(loanId, borrowerId, owner, "333333", Instant.now().plus(5, ChronoUnit.MINUTES), 5, false);

        // even the CORRECT code now fails (locked out — fresh send required).
        mvc.perform(post("/api/identity/borrowers/{b}/verify-code", borrowerId).with(loSub(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"333333\",\"loanId\":\"%s\"}".formatted(loanId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void verifyOnNonOwnedLoanGets403() throws Exception {
        String owner = freshSub();
        String loanId = createLoan(owner);
        String borrowerId = addBorrowerWithEmail(loanId, owner, "z@example.com");
        seedRow(loanId, borrowerId, owner, "999999", Instant.now().plus(5, ChronoUnit.MINUTES), 0, false);

        mvc.perform(post("/api/identity/borrowers/{b}/verify-code", borrowerId).with(loSub(freshSub()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"999999\",\"loanId\":\"%s\"}".formatted(loanId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void processorOrgWideCanSend204() throws Exception {
        // org-wide back-office role sends on a loan it doesn't "own" — passes the guard (org-wide view).
        String owner = freshSub();
        String loanId = createLoan(owner);
        String borrowerId = addBorrowerWithEmail(loanId, owner, "p@example.com");

        mvc.perform(post("/api/identity/borrowers/{b}/send-verification", borrowerId).with(processor())
                        .contentType(MediaType.APPLICATION_JSON).content(sendBody(loanId)))
                .andExpect(status().isNoContent());
    }
}
