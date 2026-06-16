package com.msfg.los.parties.service;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.parties.domain.*;
import com.msfg.los.parties.repo.BorrowerRepository;
import com.msfg.los.parties.web.dto.AddBorrowerRequest;
import com.msfg.los.parties.web.dto.UpdateBorrowerRequest;
import com.msfg.los.platform.error.NotFoundException;
import com.msfg.los.platform.pii.PiiAccessRecorder;
import com.msfg.los.platform.pii.SsnSupport;
import com.msfg.los.platform.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class BorrowerService {
    private final BorrowerRepository borrowers;
    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;
    private final TenantContext tenantContext;
    private final PiiAccessRecorder piiAccessRecorder;

    public BorrowerService(BorrowerRepository borrowers, LoanService loanService, LoanAccessGuard accessGuard,
                           TenantContext tenantContext, PiiAccessRecorder piiAccessRecorder) {
        this.borrowers = borrowers; this.loanService = loanService; this.accessGuard = accessGuard;
        this.tenantContext = tenantContext; this.piiAccessRecorder = piiAccessRecorder;
    }

    private UUID org() { return tenantContext.orgId().orElseThrow(() -> new NotFoundException("Tenant", "current")); }
    private BorrowerParty load(UUID loanId, UUID borrowerId) {
        return borrowers.findByIdAndOrgId(borrowerId, org())
            .filter(x -> x.getLoanId().equals(loanId))
            .orElseThrow(() -> new NotFoundException("Borrower", borrowerId));
    }

    @Transactional
    public BorrowerParty add(UUID loanId, AddBorrowerRequest req) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        long count = borrowers.countByLoanId(loanId);
        BorrowerParty b = new BorrowerParty();
        b.setLoanId(loanId); b.setFirstName(req.firstName()); b.setLastName(req.lastName());
        b.setOrdinal((int) count); b.setPrimary(req.primary() || count == 0);
        applyPii(b, req.middleName(), req.suffix(), req.ssn(), req.dateOfBirth(), req.maritalStatus(),
            req.dependentsCount(), req.dependentAges(), req.citizenshipType(), req.veteran(),
            req.unmarriedAddendumSpousalRights(), req.joinedToBorrowerId(), req.homePhone(), req.cellPhone(),
            req.workPhone(), req.workPhoneExt(), req.email(), req.noEmail());
        if (b.isPrimary()) clearOtherPrimaries(loanId, null);
        return borrowers.save(b);
    }

    @Transactional(readOnly = true)
    public List<BorrowerParty> list(UUID loanId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        return borrowers.findByLoanIdOrderByOrdinalAsc(loanId);
    }

    /**
     * Cross-module read seam: the loan's borrowers, tenant-scoped and ordinal-ordered, WITHOUT a
     * loan access decision. Callers in other modules guard loan access themselves (and have already
     * done so) before reading borrower membership — this mirrors the raw repository query they used
     * to make directly. Returns the {@code @TenantId}-filtered list ({@code findByLoanIdOrderByOrdinalAsc}).
     */
    @Transactional(readOnly = true)
    public List<BorrowerParty> listByLoan(UUID loanId) {
        return borrowers.findByLoanIdOrderByOrdinalAsc(loanId);
    }

    /**
     * Cross-module read seam: true iff a borrower with this id exists in the caller's tenant AND
     * belongs to the given loan. Mirrors {@code findByIdAndOrgId(borrowerId, org()).filter(loan match)}
     * exactly; returns a boolean so each caller keeps its own miss semantics (NotFound vs Validation).
     */
    @Transactional(readOnly = true)
    public boolean isBorrowerInLoan(UUID loanId, UUID borrowerId) {
        return borrowers.findByIdAndOrgId(borrowerId, org())
            .filter(b -> b.getLoanId().equals(loanId))
            .isPresent();
    }

    @Transactional
    public BorrowerParty update(UUID loanId, UUID borrowerId, UpdateBorrowerRequest req) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        BorrowerParty b = load(loanId, borrowerId);
        if (req.firstName() != null) b.setFirstName(req.firstName());
        if (req.lastName() != null) b.setLastName(req.lastName());
        applyPii(b, req.middleName(), req.suffix(), req.ssn(), req.dateOfBirth(), req.maritalStatus(),
            req.dependentsCount(), req.dependentAges(), req.citizenshipType(), req.veteran(),
            req.unmarriedAddendumSpousalRights(), req.joinedToBorrowerId(), req.homePhone(), req.cellPhone(),
            req.workPhone(), req.workPhoneExt(), req.email(), req.noEmail());
        if (Boolean.TRUE.equals(req.primary())) { clearOtherPrimaries(loanId, borrowerId); b.setPrimary(true); }
        return b;
    }

    @Transactional
    public void delete(UUID loanId, UUID borrowerId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        BorrowerParty b = load(loanId, borrowerId);
        boolean wasPrimary = b.isPrimary();
        borrowers.delete(b);
        if (wasPrimary) {
            borrowers.findByLoanIdOrderByOrdinalAsc(loanId).stream()
                .filter(x -> !x.getId().equals(borrowerId)).findFirst()
                .ifPresent(next -> next.setPrimary(true));
        }
    }

    /** Full SSN (123-45-6789) + writes an audited PiiAccessLog row. Loan-authorized. Read-write tx (the audit must flush). */
    @Transactional
    public String revealSsn(UUID loanId, UUID borrowerId, String reason) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        BorrowerParty b = load(loanId, borrowerId);
        if (b.getSsn() == null) throw new NotFoundException("SSN", borrowerId);
        piiAccessRecorder.record("BORROWER", borrowerId, "SSN", reason);
        return SsnSupport.formatDashed(b.getSsn());
    }

    private void clearOtherPrimaries(UUID loanId, UUID exceptId) {
        borrowers.findByLoanIdOrderByOrdinalAsc(loanId).forEach(other -> {
            if (exceptId == null || !other.getId().equals(exceptId)) other.setPrimary(false);
        });
    }

    private void applyPii(BorrowerParty b, String middleName, String suffix, String ssn, LocalDate dob,
            MaritalStatus maritalStatus, Integer dependentsCount, String dependentAges, CitizenshipType citizenship,
            Boolean veteran, Boolean unmarriedSpousal, UUID joinedTo, String home, String cell, String work,
            String workExt, String email, Boolean noEmail) {
        if (middleName != null) b.setMiddleName(middleName);
        if (suffix != null) b.setSuffix(suffix);
        if (ssn != null) b.setSsn(SsnSupport.normalize(ssn));   // throws 400 on bad SSN
        if (dob != null) b.setDateOfBirth(dob);
        if (maritalStatus != null) b.setMaritalStatus(maritalStatus);
        if (dependentsCount != null) b.setDependentsCount(dependentsCount);
        if (dependentAges != null) b.setDependentAges(dependentAges);
        if (citizenship != null) b.setCitizenshipType(citizenship);
        if (veteran != null) b.setVeteran(veteran);
        if (unmarriedSpousal != null) b.setUnmarriedAddendumSpousalRights(unmarriedSpousal);
        if (joinedTo != null) b.setJoinedToBorrowerId(joinedTo);
        if (home != null) b.setHomePhone(home);
        if (cell != null) b.setCellPhone(cell);
        if (work != null) b.setWorkPhone(work);
        if (workExt != null) b.setWorkPhoneExt(workExt);
        if (email != null) b.setEmail(email);
        if (noEmail != null) b.setNoEmail(noEmail);
    }
}
