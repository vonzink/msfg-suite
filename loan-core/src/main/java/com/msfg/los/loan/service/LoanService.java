package com.msfg.los.loan.service;

import com.msfg.los.loan.domain.*;
import com.msfg.los.loan.id.SequenceLoanNumberGenerator;
import com.msfg.los.loan.repo.*;
import com.msfg.los.loan.web.dto.*;
import com.msfg.los.platform.error.NotFoundException;
import com.msfg.los.platform.error.ValidationException;
import com.msfg.los.platform.security.CurrentUser;
import com.msfg.los.platform.tenancy.OrgTenantResolver;
import com.msfg.los.platform.tenancy.TenantContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class LoanService {

    private final LoanRepository loans;
    private final LoanStatusHistoryRepository histories;
    private final SequenceLoanNumberGenerator numberGen;
    private final LoanLifecycle lifecycle;
    private final CurrentUser currentUser;

    public LoanService(LoanRepository loans, LoanStatusHistoryRepository histories,
                       SequenceLoanNumberGenerator numberGen, LoanLifecycle lifecycle,
                       CurrentUser currentUser) {
        this.loans = loans;
        this.histories = histories;
        this.numberGen = numberGen;
        this.lifecycle = lifecycle;
        this.currentUser = currentUser;
    }

    @Transactional
    public Loan create(CreateLoanRequest req) {
        UUID officer = req.loanOfficerId();
        if (officer == null) {
            officer = currentUser.id().map(s -> {
                try { return UUID.fromString(s); } catch (RuntimeException e) { return null; }
            }).orElse(null);
        }
        if (officer == null) throw new ValidationException("loanOfficerId is required");
        Loan loan = new Loan();
        loan.setLoanNumber(numberGen.next());
        loan.setLoanOfficerId(officer);
        loan.setStatus(LoanStatus.STARTED);
        loan.setLoanPurpose(req.loanPurpose());
        loan.setMortgageType(req.mortgageType());
        loan.setLienPriority(req.lienPriority());
        loan.setAmortizationType(req.amortizationType());
        loan.setNoteAmount(req.noteAmount());
        return loans.save(loan);
    }

    @Transactional(readOnly = true)
    public Loan get(UUID id) {
        // Use findByIdAndOrgId (not findById) so Hibernate generates WHERE id=? AND org_id=?.
        // EntityManager.find() by PK does not apply the @TenantId filter in Hibernate 6 — it
        // only filters JPQL/Criteria. Without the org_id predicate, a cross-tenant caller could
        // load another org's loan and then hit 403 instead of 404 (leaking existence).
        UUID org = TenantContextHolder.get();
        UUID effectiveOrg = org != null ? org : OrgTenantResolver.NIL;
        return loans.findByIdAndOrgId(id, effectiveOrg)
                    .orElseThrow(() -> new NotFoundException("Loan", id));
    }

    @Transactional(readOnly = true)
    public Page<Loan> pipeline(UUID loanOfficerId, LoanStatus status, boolean admin, Pageable pageable) {
        if (admin) {
            return status == null ? loans.findAll(pageable) : loans.findByStatus(status, pageable);
        }
        return status == null
            ? loans.findByLoanOfficerId(loanOfficerId, pageable)
            : loans.findByLoanOfficerIdAndStatus(loanOfficerId, status, pageable);
    }

    @Transactional
    public Loan update(UUID id, UpdateLoanRequest req) {
        Loan loan = get(id);
        if (req.mortgageType() != null) loan.setMortgageType(req.mortgageType());
        if (req.lienPriority() != null) loan.setLienPriority(req.lienPriority());
        if (req.amortizationType() != null) loan.setAmortizationType(req.amortizationType());
        if (req.noteAmount() != null) loan.setNoteAmount(req.noteAmount());
        // Hibernate returns null for an all-null @Embedded, so a reloaded address-less loan
        // has a null SubjectProperty — instantiate before patching.
        SubjectProperty p = loan.getSubjectProperty();
        if (p == null) { p = new SubjectProperty(); loan.setSubjectProperty(p); }
        if (req.addressLine1() != null) p.setAddressLine1(req.addressLine1());
        if (req.addressLine2() != null) p.setAddressLine2(req.addressLine2());
        if (req.city() != null) p.setCity(req.city());
        if (req.state() != null) p.setState(req.state());
        if (req.postalCode() != null) p.setPostalCode(req.postalCode());
        if (req.estimatedValue() != null) p.setEstimatedValue(req.estimatedValue());

        // §4 Loan Information — apply then validate
        if (req.documentationType() != null) loan.setDocumentationType(req.documentationType());
        if (req.interestRate() != null) loan.setInterestRate(req.interestRate());
        if (req.loanTermMonths() != null) loan.setLoanTermMonths(req.loanTermMonths());
        if (req.baseLoanAmount() != null) loan.setBaseLoanAmount(req.baseLoanAmount());
        if (req.financedFeesAmount() != null) loan.setFinancedFeesAmount(req.financedFeesAmount());
        if (req.secondLoanAmount() != null) loan.setSecondLoanAmount(req.secondLoanAmount());
        if (req.downPaymentAmount() != null) loan.setDownPaymentAmount(req.downPaymentAmount());
        if (req.qualifyingCreditScore() != null) loan.setQualifyingCreditScore(req.qualifyingCreditScore());
        if (req.proposedTaxesMonthly() != null) loan.setProposedTaxesMonthly(req.proposedTaxesMonthly());
        if (req.proposedHazardInsuranceMonthly() != null) loan.setProposedHazardInsuranceMonthly(req.proposedHazardInsuranceMonthly());
        if (req.proposedHoaDuesMonthly() != null) loan.setProposedHoaDuesMonthly(req.proposedHoaDuesMonthly());
        if (req.proposedMortgageInsuranceMonthly() != null) loan.setProposedMortgageInsuranceMonthly(req.proposedMortgageInsuranceMonthly());

        // §4 Subject Property
        if (req.salesPrice() != null) p.setSalesPrice(req.salesPrice());
        if (req.appraisedValue() != null) p.setAppraisedValue(req.appraisedValue());
        if (req.propertyType() != null) p.setPropertyType(req.propertyType());
        if (req.occupancyType() != null) p.setOccupancyType(req.occupancyType());
        if (req.numberOfUnits() != null) p.setNumberOfUnits(req.numberOfUnits());

        // Validation — each rule its own if/throw
        if (loan.getInterestRate() != null && (loan.getInterestRate().signum() < 0 || loan.getInterestRate().compareTo(java.math.BigDecimal.valueOf(25)) > 0))
            throw new ValidationException("interestRate must be between 0 and 25");
        if (loan.getLoanTermMonths() != null && (loan.getLoanTermMonths() < 1 || loan.getLoanTermMonths() > 480))
            throw new ValidationException("loanTermMonths must be between 1 and 480");
        if (loan.getQualifyingCreditScore() != null && (loan.getQualifyingCreditScore() < 300 || loan.getQualifyingCreditScore() > 850))
            throw new ValidationException("qualifyingCreditScore must be between 300 and 850");
        if (p.getNumberOfUnits() != null && (p.getNumberOfUnits() < 1 || p.getNumberOfUnits() > 4))
            throw new ValidationException("numberOfUnits must be between 1 and 4");
        if (loan.getBaseLoanAmount() != null && loan.getBaseLoanAmount().signum() < 0)
            throw new ValidationException("baseLoanAmount must be >= 0");
        if (loan.getFinancedFeesAmount() != null && loan.getFinancedFeesAmount().signum() < 0)
            throw new ValidationException("financedFeesAmount must be >= 0");
        if (loan.getSecondLoanAmount() != null && loan.getSecondLoanAmount().signum() < 0)
            throw new ValidationException("secondLoanAmount must be >= 0");
        if (loan.getDownPaymentAmount() != null && loan.getDownPaymentAmount().signum() < 0)
            throw new ValidationException("downPaymentAmount must be >= 0");
        if (loan.getProposedTaxesMonthly() != null && loan.getProposedTaxesMonthly().signum() < 0)
            throw new ValidationException("proposedTaxesMonthly must be >= 0");
        if (loan.getProposedHazardInsuranceMonthly() != null && loan.getProposedHazardInsuranceMonthly().signum() < 0)
            throw new ValidationException("proposedHazardInsuranceMonthly must be >= 0");
        if (loan.getProposedHoaDuesMonthly() != null && loan.getProposedHoaDuesMonthly().signum() < 0)
            throw new ValidationException("proposedHoaDuesMonthly must be >= 0");
        if (loan.getProposedMortgageInsuranceMonthly() != null && loan.getProposedMortgageInsuranceMonthly().signum() < 0)
            throw new ValidationException("proposedMortgageInsuranceMonthly must be >= 0");
        if (p.getSalesPrice() != null && p.getSalesPrice().signum() < 0)
            throw new ValidationException("salesPrice must be >= 0");
        if (p.getAppraisedValue() != null && p.getAppraisedValue().signum() < 0)
            throw new ValidationException("appraisedValue must be >= 0");

        return loan;
    }

    @Transactional
    public Loan transition(UUID id, TransitionRequest req, Set<String> authorities) {
        Loan loan = get(id);
        LoanStatus from = loan.getStatus();
        lifecycle.assertTransition(from, req.targetStatus(), authorities);
        loan.setStatus(req.targetStatus());
        LoanStatusHistory h = new LoanStatusHistory();
        h.setLoanId(loan.getId());
        h.setFromStatus(from);
        h.setToStatus(req.targetStatus());
        h.setReason(req.reason());
        // Actor is captured automatically on the history row via AuditableEntity.createdBy (JPA auditing).
        histories.save(h);
        return loan;
    }

    @Transactional(readOnly = true)
    public List<LoanStatusHistory> history(UUID loanId) {
        return histories.findByLoanIdOrderByCreatedAtAsc(loanId);
    }
}
