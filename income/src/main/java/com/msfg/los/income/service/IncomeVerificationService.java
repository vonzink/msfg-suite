package com.msfg.los.income.service;

import com.msfg.los.income.domain.IncomeVerification;
import com.msfg.los.income.repo.IncomeVerificationRepository;
import com.msfg.los.income.verification.IncomeVerificationPort;
import com.msfg.los.income.verification.IncomeVerificationResult;
import com.msfg.los.income.verification.OrderIncomeVerificationCommand;
import com.msfg.los.income.web.dto.OrderVerificationRequest;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.parties.repo.BorrowerRepository;
import com.msfg.los.platform.error.NotFoundException;
import com.msfg.los.platform.error.ValidationException;
import com.msfg.los.platform.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class IncomeVerificationService {

    private final IncomeVerificationRepository verifications;
    private final IncomeVerificationPort port;
    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;
    private final BorrowerRepository borrowers;
    private final TenantContext tenantContext;

    public IncomeVerificationService(IncomeVerificationRepository verifications,
                                     IncomeVerificationPort port,
                                     LoanService loanService,
                                     LoanAccessGuard accessGuard,
                                     BorrowerRepository borrowers,
                                     TenantContext tenantContext) {
        this.verifications = verifications;
        this.port = port;
        this.loanService = loanService;
        this.accessGuard = accessGuard;
        this.borrowers = borrowers;
        this.tenantContext = tenantContext;
    }

    private UUID org() {
        return tenantContext.orgId().orElseThrow(() -> new NotFoundException("Tenant", "current"));
    }

    @Transactional
    public IncomeVerification order(UUID loanId, OrderVerificationRequest req) {
        accessGuard.assertCanAccess(loanService.get(loanId));

        if (req.borrowerId() != null)
            borrowers.findByIdAndOrgId(req.borrowerId(), org())
                     .filter(b -> b.getLoanId().equals(loanId))
                     .orElseThrow(() -> new ValidationException("borrowerId must reference a borrower of this loan"));

        IncomeVerificationResult r = port.order(
                new OrderIncomeVerificationCommand(loanId, req.borrowerId(), req.verificationType()));

        IncomeVerification v = new IncomeVerification();
        v.setLoanId(loanId);
        v.setBorrowerId(req.borrowerId());
        v.setVerificationType(req.verificationType());
        v.setStatus(r.status());
        v.setProvider(r.provider());
        v.setReferenceNumber(r.referenceNumber());
        v.setOrderedAt(Instant.now());

        return verifications.save(v);
    }

    @Transactional(readOnly = true)
    public List<IncomeVerification> list(UUID loanId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        return verifications.findByLoanIdOrderByOrderedAtDesc(loanId);
    }
}
