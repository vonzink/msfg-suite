package com.msfg.los.identity.repo;

import com.msfg.los.identity.domain.VerificationRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link VerificationRequest}. All derived queries below are JPQL-backed, so Hibernate's
 * {@code @TenantId} auto-filters them to the bound {@code org_id} — every count/lookup is already
 * tenant-scoped (no explicit {@code org_id} predicate needed; the columns named here are the extra
 * filters). Postgres RLS is the fail-closed backstop when the app runs as the non-owner role.
 */
public interface VerificationRequestRepository extends JpaRepository<VerificationRequest, UUID> {

    /**
     * Recent sends for a borrower (send-throttle key #1: per {@code (org_id, borrower_id)}). Counts
     * rows created at or after {@code since} — tenant-scoped by {@code @TenantId}.
     */
    long countByBorrowerIdAndCreatedAtGreaterThanEqual(UUID borrowerId, Instant since);

    /**
     * Recent sends by the acting staff member (send-throttle key #2: per acting {@code sub}). Counts
     * rows whose {@code createdBy} (the principal sub) matches — tenant-scoped by {@code @TenantId}.
     */
    long countByCreatedByAndCreatedAtGreaterThanEqual(String createdBy, Instant since);

    /**
     * The most recent verification row for a borrower on a loan, regardless of state (consumed/expired/
     * locked). The verify path resolves this single row and then applies TTL/consumed/attempt checks —
     * tenant-scoped by {@code @TenantId}.
     */
    Optional<VerificationRequest> findFirstByLoanIdAndBorrowerIdOrderByCreatedAtDesc(UUID loanId, UUID borrowerId);

    /**
     * Tenant-scoped PK lookup for the attempt recorder's REQUIRES_NEW tx. Use this, NOT the inherited
     * {@code findById} — per the project invariant (CLAUDE.md) Hibernate's {@code @TenantId} does NOT
     * filter {@code find()}-by-PK, so a bare PK lookup is not tenant-scoped at the app layer. The
     * explicit {@code orgId} predicate (plus the {@code @TenantId} filter this derived query also gets)
     * keeps it fail-closed even if a caller ever passes an externally-sourced id.
     */
    Optional<VerificationRequest> findByIdAndOrgId(UUID id, UUID orgId);
}
