package com.msfg.los.pricing.service;

import com.msfg.los.documents.domain.Document;
import com.msfg.los.documents.domain.DocumentType;
import com.msfg.los.documents.service.DocumentService;
import com.msfg.los.loan.domain.Loan;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.platform.security.CurrentUser;
import com.msfg.los.pricing.domain.LockAction;
import com.msfg.los.pricing.domain.LockEvent;
import com.msfg.los.pricing.domain.PricingAdjustment;
import com.msfg.los.pricing.domain.RateLock;
import com.msfg.los.pricing.domain.RateLockStatus;
import com.msfg.los.pricing.port.PricingEnginePort;
import com.msfg.los.pricing.port.PricingQuoteRequest;
import com.msfg.los.pricing.port.QuoteRow;
import com.msfg.los.pricing.repo.LockEventRepository;
import com.msfg.los.pricing.repo.PricingAdjustmentRepository;
import com.msfg.los.pricing.repo.RateLockRepository;
import com.msfg.los.pricing.web.dto.ExtendLockRequest;
import com.msfg.los.pricing.web.dto.LockTermsRequest;
import com.msfg.los.pricing.web.dto.PricingResponse;
import com.msfg.los.pricing.web.dto.RateChangeRequest;
import com.msfg.los.qualification.service.LoanCalculationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
    private final PricingEnginePort engine;
    private final CurrentUser currentUser;
    private final DocumentService documentService;
    private final LockConfirmationGenerator confirmationGenerator;

    public PricingService(RateLockRepository locks, PricingAdjustmentRepository adjustments,
                          LockEventRepository events, LoanService loanService,
                          LoanAccessGuard accessGuard, LoanCalculationService calculations,
                          PricingEnginePort engine, CurrentUser currentUser,
                          DocumentService documentService, LockConfirmationGenerator confirmationGenerator) {
        this.locks = locks;
        this.adjustments = adjustments;
        this.events = events;
        this.loanService = loanService;
        this.accessGuard = accessGuard;
        this.calculations = calculations;
        this.engine = engine;
        this.currentUser = currentUser;
        this.documentService = documentService;
        this.confirmationGenerator = confirmationGenerator;
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

    @Transactional
    public PricingResponse controlYourPrice(UUID loanId, LockTermsRequest req) {
        Loan loan = loadGuarded(loanId);
        assertNotTerminal(loan);
        RateLock lock = locks.findByLoanId(loanId).orElse(null);
        if (lock != null && RateLockStatus.effective(lock.getExpirationDate(), today()) == RateLockStatus.EXPIRED) {
            throw new LockStateConflictException("Lock is EXPIRED — use relock");
        }
        if (lock == null) {
            lock = new RateLock();
            lock.setLoanId(loanId);
        }
        applyTerms(lock, req);
        lock.setExtensionDaysTotal(0);
        requoteAndRecord(loan, lock, LockAction.CONTROL_YOUR_PRICE);
        return view(loanId);
    }

    @Transactional
    public PricingResponse extend(UUID loanId, ExtendLockRequest req) {
        Loan loan = loadGuarded(loanId);
        assertNotTerminal(loan);
        RateLock lock = requireLockInState(loanId, RateLockStatus.LOCKED, "extend");
        lock.setExpirationDate(lock.getExpirationDate().plusDays(req.additionalDays()));
        lock.setExtensionDaysTotal(lock.getExtensionDaysTotal() + req.additionalDays());
        requoteAndRecord(loan, lock, LockAction.EXTEND);
        return view(loanId);
    }

    @Transactional
    public PricingResponse rateChange(UUID loanId, RateChangeRequest req) {
        Loan loan = loadGuarded(loanId);
        assertNotTerminal(loan);
        RateLock lock = requireLockInState(loanId, RateLockStatus.LOCKED, "rate-change");
        lock.setLockedRate(req.rate());
        requoteAndRecord(loan, lock, LockAction.RATE_CHANGE);
        return view(loanId);
    }

    @Transactional
    public PricingResponse relock(UUID loanId, LockTermsRequest req) {
        Loan loan = loadGuarded(loanId);
        assertNotTerminal(loan);
        RateLock lock = requireLockInState(loanId, RateLockStatus.EXPIRED, "relock");
        applyTerms(lock, req);
        lock.setExtensionDaysTotal(0);
        requoteAndRecord(loan, lock, LockAction.RELOCK);
        return view(loanId);
    }

    @Transactional
    public Document generateLockConfirmation(UUID loanId) {
        Loan loan = loadGuarded(loanId);
        RateLock lock = requireLockInState(loanId, RateLockStatus.LOCKED, "generate a lock confirmation");
        var rows = adjustments.findByLoanIdOrderByOrdinalAscIdAsc(loanId);
        String html = confirmationGenerator.generate(loan, lock, rows);
        String fileName = "lock-confirmation-" + loan.getLoanNumber() + "-"
                + DateTimeFormatter.BASIC_ISO_DATE.format(today()) + ".html";
        return documentService.storeGenerated(loanId, DocumentType.LOCK_CONFIRMATION,
                "PRICING", fileName, "text/html", html.getBytes(StandardCharsets.UTF_8));
    }

    private RateLock requireLockInState(UUID loanId, RateLockStatus required, String action) {
        RateLock lock = locks.findByLoanId(loanId)
                .orElseThrow(() -> new LockStateConflictException(
                        "Loan is NOT_LOCKED — cannot " + action));
        RateLockStatus actual = RateLockStatus.effective(lock.getExpirationDate(), today());
        if (actual != required) {
            throw new LockStateConflictException(
                    "Lock is " + actual + " — cannot " + action);
        }
        return lock;
    }

    private Loan loadGuarded(UUID loanId) {
        Loan loan = loanService.get(loanId);
        accessGuard.assertCanAccess(loan);
        return loan;
    }

    private void assertNotTerminal(Loan loan) {
        if (loan.getStatus().isTerminal()) {
            throw new LockStateConflictException("Loan status " + loan.getStatus() + " does not allow lock actions");
        }
    }

    private void applyTerms(RateLock lock, LockTermsRequest req) {
        Instant now = Instant.now();
        lock.setLockedRate(req.rate());
        lock.setCommitmentDays(req.commitmentDays());
        lock.setCompensationPayerType(req.compensationPayerType());
        lock.setLockDate(now);
        lock.setExpirationDate(LocalDate.ofInstant(now, ZoneOffset.UTC).plusDays(req.commitmentDays()));
        lock.setLockedBy(currentUser.id().orElse(null));
        lock.setInterviewerEmail(currentUser.email().orElse(null));
    }

    /** Quote via the port, replace the adjustment snapshot, persist lock, append audit event. */
    private void requoteAndRecord(Loan loan, RateLock lock, LockAction action) {
        var calc = calculations.calculate(loan.getId());
        if (calc.totalLoanAmount() == null) {
            throw new LoanNotPriceableException("Loan has no base loan amount — cannot price");
        }
        var quote = engine.quote(new PricingQuoteRequest(
                lock.getLockedRate(), lock.getCommitmentDays(), lock.getCompensationPayerType(),
                lock.getExtensionDaysTotal(), loan.getQualifyingCreditScore(), calc.ltv(),
                loan.getLoanPurpose(), calc.totalLoanAmount()));

        adjustments.deleteByLoanId(loan.getId());
        adjustments.flush();   // Hibernate flushes INSERTs before DELETEs — force the deletes out first
        int ordinal = 1;
        for (QuoteRow row : quote.rows()) {
            PricingAdjustment a = new PricingAdjustment();
            a.setLoanId(loan.getId());
            a.setOrdinal(ordinal++);
            a.setName(row.name());
            a.setRowType(row.rowType());
            a.setAdjustmentPercent(row.percent());
            a.setDollarAmount(row.percent()
                    .divide(new BigDecimal("100"), 10, RoundingMode.HALF_UP)
                    .multiply(calc.totalLoanAmount())
                    .setScale(2, RoundingMode.HALF_UP));
            adjustments.save(a);
        }
        locks.save(lock);

        LockEvent e = new LockEvent();
        e.setLoanId(loan.getId());
        e.setAction(action);
        e.setActor(currentUser.id().orElse(null));
        e.setOccurredAt(Instant.now());
        e.setRate(lock.getLockedRate());
        e.setCommitmentDays(lock.getCommitmentDays());
        e.setExpirationDate(lock.getExpirationDate());
        events.save(e);
    }

    static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }
}
