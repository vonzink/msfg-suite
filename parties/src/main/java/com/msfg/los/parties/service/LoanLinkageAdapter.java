package com.msfg.los.parties.service;

import com.msfg.los.loan.service.LoanLinkageResolver;
import com.msfg.los.parties.repo.BorrowerRepository;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Parties-module adapter that implements the {@code loan-core} port {@link LoanLinkageResolver}.
 *
 * <p>Keeps {@code loan-core} free of any {@code parties} imports (ArchUnit boundary). All
 * repository queries delegate to {@link BorrowerRepository} derived / JPQL methods, so
 * Hibernate's {@code @TenantId} filter is applied automatically — the tenant context must be
 * bound by the caller before either method is invoked.
 */
@Component
public class LoanLinkageAdapter implements LoanLinkageResolver {

    private final BorrowerRepository borrowers;

    public LoanLinkageAdapter(BorrowerRepository borrowers) {
        this.borrowers = borrowers;
    }

    /**
     * {@inheritDoc}
     *
     * <p>A {@code null} {@code userId} returns {@code false} immediately — a co-borrower with no
     * linked account must never match an authenticated user.
     */
    @Override
    public boolean isBorrowerOnLoan(UUID loanId, UUID userId) {
        if (userId == null) return false;
        return borrowers.existsByLoanIdAndUserId(loanId, userId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>A {@code null} {@code userId} returns an empty list immediately.
     */
    @Override
    public List<UUID> loanIdsForBorrower(UUID userId) {
        if (userId == null) return Collections.emptyList();
        return borrowers.findLoanIdsByUserId(userId);
    }
}
