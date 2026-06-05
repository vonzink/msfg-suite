package com.msfg.los.loan.service;

import com.msfg.los.loan.domain.*;
import com.msfg.los.loan.id.SequenceLoanNumberGenerator;
import com.msfg.los.loan.repo.*;
import com.msfg.los.loan.web.dto.*;
import com.msfg.los.platform.error.NotFoundException;
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

    public LoanService(LoanRepository loans, LoanStatusHistoryRepository histories,
                       SequenceLoanNumberGenerator numberGen, LoanLifecycle lifecycle) {
        this.loans = loans;
        this.histories = histories;
        this.numberGen = numberGen;
        this.lifecycle = lifecycle;
    }

    @Transactional
    public Loan create(CreateLoanRequest req) {
        Loan loan = new Loan();
        loan.setLoanNumber(numberGen.next());
        loan.setLoanOfficerId(req.loanOfficerId());
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
