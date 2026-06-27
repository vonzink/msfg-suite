package com.msfg.los.origination.service;

import com.msfg.los.loan.domain.Loan;
import com.msfg.los.loan.domain.SubjectProperty;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.loan.web.dto.UpdateLoanRequest;
import com.msfg.los.origination.web.dto.BorrowerApplicationRequest;
import com.msfg.los.origination.web.dto.BorrowerApplicationResponse;
import com.msfg.los.parties.domain.BorrowerParty;
import com.msfg.los.parties.service.BorrowerService;
import com.msfg.los.parties.web.dto.UpdateBorrowerRequest;
import com.msfg.los.platform.error.NotFoundException;
import com.msfg.los.platform.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Stage-2 borrower-self application orchestrator: the ONE surface through which a borrower reads and
 * writes their own 1003 to the suite (the system of record), co-equal with the staff console path.
 *
 * <p>Authorization is done ONCE here on the resolved target borrower row — never delegated to the
 * per-module services, which stay staff-only. It then writes through no-guard internal seams
 * ({@code LoanService.update}, which is unguarded at the service layer, and
 * {@code BorrowerService.updateInternal}) exactly as {@code IntakeService} writes via
 * {@code addAtIntake}. The public per-module write endpoints are unchanged.
 *
 * <p>Target resolution: a BORROWER edits their OWN row ({@code findSelf} by linked {@code user_id});
 * staff (no self row) target the PRIMARY borrower. {@code assertBorrowerSelfWritable}/
 * {@code assertBorrowerSelfReadable} on the resolved id then admits staff/owning-LO or a borrower on
 * their own row, and denies everyone else (a borrower not on the loan, a co-borrower targeting another
 * row, an agent, PLATFORM_ADMIN). Concurrency: both entities extend {@code BaseEntity} ({@code @Version})
 * so a borrower/LO race surfaces as 409; and the merge (non-null only) means a borrower save never
 * touches the staff-only §4 fields or another borrower's row.
 */
@Service
public class BorrowerApplicationService {

    private final LoanService loanService;
    private final BorrowerService borrowerService;
    private final LoanAccessGuard accessGuard;
    private final CurrentUser currentUser;

    public BorrowerApplicationService(LoanService loanService, BorrowerService borrowerService,
                                      LoanAccessGuard accessGuard, CurrentUser currentUser) {
        this.loanService = loanService;
        this.borrowerService = borrowerService;
        this.accessGuard = accessGuard;
        this.currentUser = currentUser;
    }

    /** Hydrate the caller's application view. Borrower reads their own row; staff read the primary. */
    @Transactional(readOnly = true)
    public BorrowerApplicationResponse get(UUID loanId) {
        Loan loan = loanService.get(loanId);
        BorrowerParty target = resolveTarget(loanId);
        accessGuard.assertBorrowerSelfReadable(loan, target.getId());
        return toResponse(loan, target);
    }

    /** Upsert the caller's application: §4 subset on the loan + the caller's own borrower row. */
    @Transactional
    public BorrowerApplicationResponse upsert(UUID loanId, BorrowerApplicationRequest req) {
        Loan loan = loanService.get(loanId);
        BorrowerParty target = resolveTarget(loanId);
        accessGuard.assertBorrowerSelfWritable(loan, target.getId());

        Loan updatedLoan = (req.loan() != null)
            ? loanService.update(loanId, toUpdateLoanRequest(req.loan()))
            : loan;
        BorrowerParty updatedBorrower = (req.borrower() != null)
            ? borrowerService.updateInternal(loanId, target.getId(), toUpdateBorrowerRequest(req.borrower()))
            : target;

        return toResponse(updatedLoan, updatedBorrower);
    }

    /**
     * Resolve the borrower row the caller acts on: their OWN row (by linked sub) if present, else the
     * loan's PRIMARY borrower (the staff path). Both reads are no-guard; the caller-side
     * {@code assertBorrowerSelf*} on the returned id is what authorizes.
     */
    private BorrowerParty resolveTarget(UUID loanId) {
        UUID sub = currentSubject();
        Optional<BorrowerParty> self = (sub != null) ? borrowerService.findSelf(loanId, sub) : Optional.empty();
        return self.or(() -> borrowerService.findPrimary(loanId))
            .orElseThrow(() -> new NotFoundException("Borrower for loan", loanId));
    }

    private UUID currentSubject() {
        String me = currentUser.id().orElse(null);
        if (me == null) return null;
        try {
            return UUID.fromString(me);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /**
     * Map the borrower-writable §4 subset onto the full {@code UpdateLoanRequest}, hard-nulling every
     * staff-only / pricing / UW field. {@code LoanService.update} merges on non-null, so the nulls
     * leave any LO-set values untouched — a borrower can NEVER write rate/term/score/escrow/etc.
     */
    private UpdateLoanRequest toUpdateLoanRequest(BorrowerApplicationRequest.LoanInfo in) {
        return new UpdateLoanRequest(
            in.mortgageType(),       // mortgageType         (borrower-allowed)
            null,                    // lienPriority         (staff-only)
            null,                    // amortizationType     (staff-only)
            null,                    // noteAmount           (staff-only; borrower uses baseLoanAmount)
            in.addressLine1(),
            in.addressLine2(),
            in.city(),
            in.state(),
            in.postalCode(),
            in.estimatedValue(),
            null,                    // documentationType    (staff-only)
            null,                    // interestRate         (LO/pricing)
            null,                    // loanTermMonths       (LO/pricing)
            in.baseLoanAmount(),
            null,                    // financedFeesAmount   (staff-only)
            null,                    // secondLoanAmount     (staff-only)
            in.downPaymentAmount(),
            null,                    // qualifyingCreditScore (UW)
            null,                    // proposedTaxesMonthly  (staff-only)
            null,                    // proposedHazardInsuranceMonthly
            null,                    // proposedHoaDuesMonthly
            null,                    // proposedMortgageInsuranceMonthly
            in.salesPrice(),
            null,                    // appraisedValue       (staff/appraisal)
            in.propertyType(),
            in.occupancyType(),
            in.numberOfUnits(),
            null);                   // consummationDate     (TRID/staff)
    }

    private UpdateBorrowerRequest toUpdateBorrowerRequest(BorrowerApplicationRequest.BorrowerInfo in) {
        return new UpdateBorrowerRequest(
            in.firstName(), in.lastName(), null,   // primary stays as-is
            in.middleName(), in.suffix(), in.ssn(), in.dateOfBirth(), in.maritalStatus(),
            in.dependentsCount(), in.dependentAges(), in.citizenshipType(), null, // veteran unchanged
            null, null,                            // unmarriedAddendumSpousalRights, joinedToBorrowerId
            in.homePhone(), in.cellPhone(), in.workPhone(), in.workPhoneExt(),
            in.email(), null);                     // noEmail unchanged
    }

    private BorrowerApplicationResponse toResponse(Loan loan, BorrowerParty b) {
        SubjectProperty p = loan.getSubjectProperty();
        BorrowerApplicationResponse.LoanInfo loanView = new BorrowerApplicationResponse.LoanInfo(
            loan.getMortgageType(),
            loan.getBaseLoanAmount(),
            loan.getDownPaymentAmount(),
            p != null ? p.getEstimatedValue() : null,
            p != null ? p.getSalesPrice() : null,
            p != null ? p.getAddressLine1() : null,
            p != null ? p.getAddressLine2() : null,
            p != null ? p.getCity() : null,
            p != null ? p.getState() : null,
            p != null ? p.getPostalCode() : null,
            p != null ? p.getPropertyType() : null,
            p != null ? p.getOccupancyType() : null,
            p != null ? p.getNumberOfUnits() : null);
        BorrowerApplicationResponse.BorrowerInfo borrowerView = new BorrowerApplicationResponse.BorrowerInfo(
            b.getFirstName(), b.getLastName(), b.getMiddleName(), b.getSuffix(), b.getSsn() != null,
            b.getDateOfBirth(), b.getMaritalStatus(), b.getDependentsCount(), b.getDependentAges(),
            b.getCitizenshipType(), b.getHomePhone(), b.getCellPhone(), b.getWorkPhone(), b.getWorkPhoneExt(),
            b.getEmail());
        return new BorrowerApplicationResponse(loan.getId(), loan.getLoanNumber(), b.getId(), loanView, borrowerView);
    }
}
