package com.msfg.los.income.service;

import com.msfg.los.income.domain.Employment;
import com.msfg.los.income.domain.EmploymentClassificationType;
import com.msfg.los.income.domain.EmploymentStatusType;
import com.msfg.los.income.domain.OwnershipInterestType;
import com.msfg.los.income.repo.EmploymentRepository;
import com.msfg.los.income.web.dto.AddEmploymentRequest;
import com.msfg.los.income.web.dto.UpdateEmploymentRequest;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.parties.repo.BorrowerRepository;
import com.msfg.los.platform.error.NotFoundException;
import com.msfg.los.platform.error.ValidationException;
import com.msfg.los.platform.reference.UsStateCode;
import com.msfg.los.platform.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class EmploymentService {

    private final EmploymentRepository employments;
    private final BorrowerRepository borrowers;
    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;
    private final TenantContext tenantContext;

    public EmploymentService(EmploymentRepository employments, BorrowerRepository borrowers,
                             LoanService loanService, LoanAccessGuard accessGuard,
                             TenantContext tenantContext) {
        this.employments = employments;
        this.borrowers = borrowers;
        this.loanService = loanService;
        this.accessGuard = accessGuard;
        this.tenantContext = tenantContext;
    }

    private UUID org() {
        return tenantContext.orgId().orElseThrow(() -> new NotFoundException("Tenant", "current"));
    }

    /** Verify the loan is in the caller's org + owned, and the borrower belongs to that loan. */
    private void assertBorrowerInLoan(UUID loanId, UUID borrowerId) {
        accessGuard.assertCanAccess(loanService.get(loanId));   // 404 cross-org loan, 403 not owner
        borrowers.findByIdAndOrgId(borrowerId, org())
                .filter(b -> b.getLoanId().equals(loanId))
                .orElseThrow(() -> new NotFoundException("Borrower", borrowerId));
    }

    // max+1, not count — count reuses ordinals after a delete and collides
    private int nextOrdinal(UUID borrowerId) {
        return employments.findTopByBorrowerIdOrderByOrdinalDesc(borrowerId)
                .map(x -> x.getOrdinal() + 1)
                .orElse(0);
    }

    @Transactional
    public Employment add(UUID loanId, UUID borrowerId, AddEmploymentRequest req) {
        assertBorrowerInLoan(loanId, borrowerId);
        Employment e = new Employment();
        e.setLoanId(loanId);
        e.setBorrowerId(borrowerId);
        e.setOrdinal(nextOrdinal(borrowerId));
        applyAndValidate(e,
                req.employerName(), req.employerPhone(),
                req.employerAddressLine1(), req.employerAddressLine2(),
                req.employerCity(), req.employerState(), req.employerPostalCode(),
                req.positionTitle(), req.employmentStatus(), req.classification(),
                req.selfEmployed(), req.ownershipShare(),
                req.employedByPartyToTransaction(),
                req.startDate(), req.endDate(), req.monthsInLineOfWork());
        return employments.save(e);
    }

    @Transactional(readOnly = true)
    public List<Employment> list(UUID loanId, UUID borrowerId) {
        assertBorrowerInLoan(loanId, borrowerId);
        return employments.findByBorrowerIdOrderByOrdinalAscIdAsc(borrowerId);
    }

    @Transactional
    public Employment update(UUID loanId, UUID borrowerId, UUID employmentId, UpdateEmploymentRequest req) {
        assertBorrowerInLoan(loanId, borrowerId);
        Employment e = employments.findByIdAndOrgId(employmentId, org())
                .filter(x -> x.getBorrowerId().equals(borrowerId))
                .orElseThrow(() -> new NotFoundException("Employment", employmentId));
        applyAndValidate(e,
                req.employerName(), req.employerPhone(),
                req.employerAddressLine1(), req.employerAddressLine2(),
                req.employerCity(), req.employerState(), req.employerPostalCode(),
                req.positionTitle(), req.employmentStatus(), req.classification(),
                req.selfEmployed(), req.ownershipShare(),
                req.employedByPartyToTransaction(),
                req.startDate(), req.endDate(), req.monthsInLineOfWork());
        return e;
    }

    @Transactional
    public void delete(UUID loanId, UUID borrowerId, UUID employmentId) {
        assertBorrowerInLoan(loanId, borrowerId);
        Employment e = employments.findByIdAndOrgId(employmentId, org())
                .filter(x -> x.getBorrowerId().equals(borrowerId))
                .orElseThrow(() -> new NotFoundException("Employment", employmentId));
        employments.delete(e);
    }

    private void applyAndValidate(Employment e,
                                  String employerName, String employerPhone,
                                  String employerAddressLine1, String employerAddressLine2,
                                  String employerCity, UsStateCode employerState, String employerPostalCode,
                                  String positionTitle, EmploymentStatusType employmentStatus,
                                  EmploymentClassificationType classification,
                                  Boolean selfEmployed, OwnershipInterestType ownershipShare,
                                  Boolean employedByPartyToTransaction,
                                  LocalDate startDate, LocalDate endDate, Integer monthsInLineOfWork) {
        if (employerName != null) e.setEmployerName(employerName);
        if (employerPhone != null) e.setEmployerPhone(employerPhone);
        if (employerAddressLine1 != null) e.setEmployerAddressLine1(employerAddressLine1);
        if (employerAddressLine2 != null) e.setEmployerAddressLine2(employerAddressLine2);
        if (employerCity != null) e.setEmployerCity(employerCity);
        if (employerState != null) e.setEmployerState(employerState);
        if (employerPostalCode != null) e.setEmployerPostalCode(employerPostalCode);
        if (positionTitle != null) e.setPositionTitle(positionTitle);
        if (employmentStatus != null) e.setEmploymentStatus(employmentStatus);
        if (classification != null) e.setClassification(classification);
        if (selfEmployed != null) {
            e.setSelfEmployed(selfEmployed);
            if (!selfEmployed) e.setOwnershipShare(null);   // clear paired field so SE→W2 switch is recoverable
        }
        if (ownershipShare != null) e.setOwnershipShare(ownershipShare);
        if (employedByPartyToTransaction != null) e.setEmployedByPartyToTransaction(employedByPartyToTransaction);
        if (startDate != null) e.setStartDate(startDate);
        if (endDate != null) e.setEndDate(endDate);
        if (monthsInLineOfWork != null) e.setMonthsInLineOfWork(monthsInLineOfWork);

        // self-employment + previous rules
        boolean se = Boolean.TRUE.equals(e.getSelfEmployed());
        if (!se && e.getOwnershipShare() != null)
            throw new ValidationException("ownershipShare requires selfEmployed=true");
        if (se && e.getOwnershipShare() == null)
            throw new ValidationException("ownershipShare is required when selfEmployed=true");
        if (!se && (e.getEmployerName() == null || e.getEmployerName().isBlank()))
            throw new ValidationException("employerName is required unless selfEmployed=true");
        if (e.getEmploymentStatus() == EmploymentStatusType.PREVIOUS) {
            if (e.getEndDate() == null)
                throw new ValidationException("endDate is required for PREVIOUS employment");
            if (e.getStartDate() != null && e.getEndDate().isBefore(e.getStartDate()))
                throw new ValidationException("endDate must be on or after startDate");
        }
    }
}
