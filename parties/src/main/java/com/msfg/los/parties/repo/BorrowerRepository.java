package com.msfg.los.parties.repo;

import com.msfg.los.parties.domain.BorrowerParty;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BorrowerRepository extends JpaRepository<BorrowerParty, UUID> {

    List<BorrowerParty> findByLoanIdOrderByOrdinalAsc(UUID loanId);

    long countByLoanId(UUID loanId);

    Optional<BorrowerParty> findByIdAndOrgId(UUID id, UUID orgId);

    List<BorrowerParty> findByLoanIdInAndPrimaryTrue(Collection<UUID> loanIds);

    /**
     * Loan ids whose PRIMARY borrower's first, last, or "first last" name contains {@code q}
     * (case-insensitive). Tenant-filtered by Hibernate {@code @TenantId} (JPQL). {@code q} is the
     * caller-supplied substring, already lower-cased and percent-wrapped (e.g. {@code %smith%}).
     */
    @Query("""
           select distinct b.loanId from BorrowerParty b
           where b.primary = true
             and (lower(b.firstName) like :q
                  or lower(b.lastName) like :q
                  or lower(concat(b.firstName, ' ', b.lastName)) like :q)
           """)
    List<UUID> findLoanIdsByPrimaryBorrowerNameLike(@Param("q") String q);
}
