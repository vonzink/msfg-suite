package com.msfg.los.pricing.service;

import com.msfg.los.loan.domain.Loan;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.pricing.domain.LockEvent;
import com.msfg.los.pricing.domain.PricingAdjustment;
import com.msfg.los.pricing.domain.RateLock;
import com.msfg.los.pricing.domain.RateLockStatus;
import com.msfg.los.pricing.repo.LockEventRepository;
import com.msfg.los.pricing.repo.PricingAdjustmentRepository;
import com.msfg.los.pricing.repo.RateLockRepository;
import com.msfg.los.pricing.web.dto.PricingResponse;
import com.msfg.los.qualification.service.LoanCalculationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
public class PricingService {

    private final RateLockRepository locks;
    private final PricingAdjustmentRepository adjustments;
    private final LockEventRepository events;
    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;
    private final LoanCalculationService calculations;

    public PricingService(RateLockRepository locks, PricingAdjustmentRepository adjustments,
                          LockEventRepository events, LoanService loanService,
                          LoanAccessGuard accessGuard, LoanCalculationService calculations) {
        this.locks = locks;
        this.adjustments = adjustments;
        this.events = events;
        this.loanService = loanService;
        this.accessGuard = accessGuard;
        this.calculations = calculations;
    }

    @Transactional(readOnly = true)
    public PricingResponse view(UUID loanId) {
        Loan loan = loanService.get(loanId);
        accessGuard.assertCanAccess(loan);
        RateLock lock = locks.findByLoanId(loanId).orElse(null);
        var calc = calculations.calculate(loanId);
        String exactRateType = loan.getAmortizationType() == null ? null : loan.getAmortizationType().name();

        if (lock == null) {
            return new PricingResponse(RateLockStatus.NOT_LOCKED, loan.getInterestRate(),
                    null, null, null, null, null, null, null,
                    calc.totalLoanAmount(), exactRateType);
        }
        RateLockStatus status = RateLockStatus.effective(lock.getExpirationDate(), today());
        return new PricingResponse(status, lock.getLockedRate(),
                lock.getCommitmentDays(), lock.getLockDate(), lock.getExpirationDate(),
                lock.getExtensionDaysTotal(), lock.getCompensationPayerType(),
                lock.getLockedBy(), lock.getInterviewerEmail(),
                calc.totalLoanAmount(), exactRateType);
    }

    @Transactional(readOnly = true)
    public List<PricingAdjustment> adjustments(UUID loanId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        return adjustments.findByLoanIdOrderByOrdinalAscIdAsc(loanId);
    }

    @Transactional(readOnly = true)
    public List<LockEvent> history(UUID loanId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        return events.findByLoanIdOrderByOccurredAtAscIdAsc(loanId);
    }

    static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }
}
