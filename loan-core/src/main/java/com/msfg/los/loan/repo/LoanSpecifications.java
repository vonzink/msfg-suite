package com.msfg.los.loan.repo;

import com.msfg.los.loan.domain.Loan;
import com.msfg.los.loan.domain.LoanStatus;
import com.msfg.los.loan.domain.MortgageType;
import jakarta.persistence.criteria.Expression;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * JPA {@link Specification}s for the loan pipeline list (Phase 2 T4). Every optional filter is a SQL
 * predicate so filtering happens IN THE QUERY (never load-all-then-filter), mirroring
 * {@code DocumentSpecifications}.
 *
 * <p>Tenant scoping is NOT expressed here — Hibernate {@code @TenantId} already auto-filters every
 * query by the caller's {@code org_id}. These specs only add the not-deleted base, the caller's
 * access scope, and the business facets.
 */
public final class LoanSpecifications {

    private LoanSpecifications() {
    }

    /** Always-on base: soft-deleted loans must never appear in a pipeline read. */
    public static Specification<Loan> notDeleted() {
        return (root, q, cb) -> cb.isNull(root.get("deletedAt"));
    }

    /**
     * Caller access scope (preserves the existing pipeline branch): org-wide-view roles see every org
     * loan; an LO sees only loans they own ({@code loanOfficerId == caller}). A null owner with a
     * non-org-wide caller matches nothing (defensive — the controller always supplies the caller id).
     */
    public static Specification<Loan> callerScope(boolean orgWideView, UUID callerLoanOfficerId) {
        return (root, q, cb) -> {
            if (orgWideView) return cb.conjunction();
            if (callerLoanOfficerId == null) return cb.disjunction();
            return cb.equal(root.get("loanOfficerId"), callerLoanOfficerId);
        };
    }

    /** {@code status in (...)} — accepts the single-value legacy case (a one-element list). */
    public static Specification<Loan> statusIn(Collection<LoanStatus> statuses) {
        return (root, q, cb) -> root.get("status").in(statuses);
    }

    /** Assigned loan officer ({@code lo} param). */
    public static Specification<Loan> assignedTo(UUID loanOfficerId) {
        return (root, q, cb) -> cb.equal(root.get("loanOfficerId"), loanOfficerId);
    }

    /** {@code mortgageType in (...)} ({@code loanType} param). */
    public static Specification<Loan> mortgageTypeIn(Collection<MortgageType> types) {
        return (root, q, cb) -> root.get("mortgageType").in(types);
    }

    /** Restrict to a precomputed id set ({@code loan.id in (:ids)}); an empty set matches nothing. */
    public static Specification<Loan> idIn(Set<UUID> ids) {
        return (root, q, cb) -> (ids == null || ids.isEmpty())
                ? cb.disjunction()
                : root.get("id").in(ids);
    }

    /** consummationDate &gt;= {@code from} ({@code closingFrom} param). */
    public static Specification<Loan> consummationOnOrAfter(LocalDate from) {
        return (root, q, cb) -> cb.greaterThanOrEqualTo(root.get("consummationDate"), from);
    }

    /** consummationDate &lt;= {@code to} ({@code closingTo} param). */
    public static Specification<Loan> consummationOnOrBefore(LocalDate to) {
        return (root, q, cb) -> cb.lessThanOrEqualTo(root.get("consummationDate"), to);
    }

    /**
     * Stage age: {@code status_changed_at < now - days} ({@code stageAgeGt} param). Loans whose
     * status_changed_at is null are excluded (never-transitioned → no measurable stage age).
     */
    public static Specification<Loan> stageOlderThanDays(int days) {
        Instant cutoff = Instant.now().minusSeconds((long) days * 86_400L);
        return (root, q, cb) -> cb.lessThan(root.get("statusChangedAt"), cutoff);
    }

    /**
     * Primary loan amount &gt;= {@code min}, using {@code baseLoanAmount} with a {@code noteAmount}
     * fallback when base is null (the same field the loan summary treats as primary). Implemented as
     * {@code coalesce(baseLoanAmount, noteAmount)} so the comparison is a single SQL predicate.
     */
    public static Specification<Loan> amountAtLeast(BigDecimal min) {
        return (root, q, cb) -> cb.greaterThanOrEqualTo(primaryAmount(root, cb), min);
    }

    /** Primary loan amount &lt;= {@code max} (coalesce(baseLoanAmount, noteAmount)). */
    public static Specification<Loan> amountAtMost(BigDecimal max) {
        return (root, q, cb) -> cb.lessThanOrEqualTo(primaryAmount(root, cb), max);
    }

    @SuppressWarnings("unchecked")
    private static Expression<BigDecimal> primaryAmount(
            jakarta.persistence.criteria.Root<Loan> root,
            jakarta.persistence.criteria.CriteriaBuilder cb) {
        return cb.coalesce(root.get("baseLoanAmount"), root.get("noteAmount"));
    }
}
