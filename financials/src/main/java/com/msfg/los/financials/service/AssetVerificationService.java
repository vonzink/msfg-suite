package com.msfg.los.financials.service;

import com.msfg.los.financials.domain.AssetVerification;
import com.msfg.los.financials.repo.AssetVerificationRepository;
import com.msfg.los.financials.verification.AssetVerificationPort;
import com.msfg.los.financials.verification.AssetVerificationResult;
import com.msfg.los.financials.verification.OrderAssetVerificationCommand;
import com.msfg.los.financials.web.dto.OrderAssetVerificationRequest;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.parties.service.BorrowerService;
import com.msfg.los.platform.error.ValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AssetVerificationService {

    private final AssetVerificationRepository verifications;
    private final AssetVerificationPort port;
    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;
    private final BorrowerService borrowerService;

    public AssetVerificationService(AssetVerificationRepository verifications,
                                    AssetVerificationPort port,
                                    LoanService loanService,
                                    LoanAccessGuard accessGuard,
                                    BorrowerService borrowerService) {
        this.verifications = verifications;
        this.port = port;
        this.loanService = loanService;
        this.accessGuard = accessGuard;
        this.borrowerService = borrowerService;
    }

    @Transactional
    public AssetVerification order(UUID loanId, OrderAssetVerificationRequest req) {
        accessGuard.assertCanAccess(loanService.get(loanId));

        if (req.borrowerId() != null && !borrowerService.isBorrowerInLoan(loanId, req.borrowerId()))
            throw new ValidationException("borrowerId must reference a borrower of this loan");

        AssetVerificationResult r = port.order(
                new OrderAssetVerificationCommand(loanId, req.borrowerId(), req.verificationType()));

        AssetVerification v = new AssetVerification();
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
    public List<AssetVerification> list(UUID loanId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        return verifications.findByLoanIdOrderByOrderedAtDesc(loanId);
    }
}
