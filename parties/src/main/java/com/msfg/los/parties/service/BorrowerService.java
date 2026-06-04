package com.msfg.los.parties.service;

import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.parties.domain.BorrowerParty;
import com.msfg.los.parties.repo.BorrowerRepository;
import com.msfg.los.parties.web.dto.AddBorrowerRequest;
import com.msfg.los.parties.web.dto.UpdateBorrowerRequest;
import com.msfg.los.platform.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class BorrowerService {

    private final BorrowerRepository borrowers;
    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;

    public BorrowerService(BorrowerRepository borrowers, LoanService loanService, LoanAccessGuard accessGuard) {
        this.borrowers = borrowers;
        this.loanService = loanService;
        this.accessGuard = accessGuard;
    }

    @Transactional
    public BorrowerParty add(UUID loanId, AddBorrowerRequest req) {
        accessGuard.assertCanAccess(loanService.get(loanId)); // 404 if loan missing, 403 if no access
        long count = borrowers.countByLoanId(loanId);
        BorrowerParty b = new BorrowerParty();
        b.setLoanId(loanId);
        b.setFirstName(req.firstName());
        b.setLastName(req.lastName());
        b.setOrdinal((int) count);
        b.setPrimary(req.primary() || count == 0); // first borrower is always primary
        if (b.isPrimary()) {
            clearOtherPrimaries(loanId, null);
        }
        return borrowers.save(b);
    }

    @Transactional(readOnly = true)
    public List<BorrowerParty> list(UUID loanId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        return borrowers.findByLoanIdOrderByOrdinalAsc(loanId);
    }

    @Transactional
    public BorrowerParty update(UUID loanId, UUID borrowerId, UpdateBorrowerRequest req) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        BorrowerParty b = borrowers.findById(borrowerId)
                .filter(x -> x.getLoanId().equals(loanId))
                .orElseThrow(() -> new NotFoundException("Borrower", borrowerId));
        if (req.firstName() != null) b.setFirstName(req.firstName());
        if (req.lastName() != null) b.setLastName(req.lastName());
        if (Boolean.TRUE.equals(req.primary())) {
            clearOtherPrimaries(loanId, borrowerId);
            b.setPrimary(true);
        }
        return b;
    }

    @Transactional
    public void delete(UUID loanId, UUID borrowerId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        BorrowerParty b = borrowers.findById(borrowerId)
                .filter(x -> x.getLoanId().equals(loanId))
                .orElseThrow(() -> new NotFoundException("Borrower", borrowerId));
        borrowers.delete(b);
    }

    private void clearOtherPrimaries(UUID loanId, UUID exceptId) {
        borrowers.findByLoanIdOrderByOrdinalAsc(loanId).forEach(other -> {
            if (exceptId == null || !other.getId().equals(exceptId)) {
                other.setPrimary(false);
            }
        });
    }
}
