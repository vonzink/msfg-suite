package com.msfg.los.origination.service;

import com.msfg.los.loan.domain.Loan;
import com.msfg.los.loan.service.BorrowerUserLinker;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.loan.web.dto.CreateLoanRequest;
import com.msfg.los.loan.web.dto.UpdateLoanRequest;
import com.msfg.los.origination.web.dto.IntakeRequest;
import com.msfg.los.origination.web.dto.IntakeResult;
import com.msfg.los.parties.domain.BorrowerParty;
import com.msfg.los.parties.service.BorrowerService;
import com.msfg.los.parties.web.dto.AddBorrowerRequest;
import com.msfg.los.platform.error.ValidationException;
import com.msfg.los.platform.security.CurrentUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Borrower funnel hand-off (Phase A4): {@code msfg.us → mortgage-app → suite}. Idempotently
 * materializes a loan + a primary borrower row in suite (the system of record) for a signed-in
 * borrower, then links that borrower row to the caller's Cognito {@code sub} so they can read it
 * back through {@code GET /api/me/loans}.
 *
 * <p>The {@code origination} module is a leaf consumer — nothing depends on it, so there is no bean
 * cycle. Like {@link CloneService}, every read and write goes through an owning module's
 * <strong>service</strong> (never another module's repository), so validation, ordinals, and
 * tenant-stamping all run and the ArchUnit {@code ModuleBoundaryTest} stays green. The whole
 * operation is one {@code @Transactional} unit so a mid-failure rolls back and never leaves a
 * half-created loan behind.
 *
 * <p><strong>Idempotency</strong>: the upstream {@code sourceLeadId} is the dedupe key. A re-POST
 * with the same id returns the existing loan's identifiers WITHOUT creating a duplicate loan or
 * re-linking — so a retried/replayed funnel hand-off is safe.
 */
@Service
public class IntakeService {

    private final LoanService loanService;
    private final BorrowerService borrowerService;
    private final BorrowerUserLinker borrowerUserLinker;
    private final CurrentUser currentUser;

    /**
     * The default loan officer stamped on funnel-created loans. {@code Loan.loanOfficerId} is NOT
     * NULL, and a null officer would make {@code LoanService.create} default the CALLER (the
     * borrower) as LO — wrong. So intake always passes an explicit default officer; an org reassigns
     * it later. Configurable via {@code los.intake.default-loan-officer-id}.
     */
    private final UUID defaultLoanOfficerId;

    public IntakeService(LoanService loanService,
                         BorrowerService borrowerService,
                         BorrowerUserLinker borrowerUserLinker,
                         CurrentUser currentUser,
                         @Value("${los.intake.default-loan-officer-id:00000000-0000-0000-0000-000000000001}")
                         String defaultLoanOfficerId) {
        this.loanService = loanService;
        this.borrowerService = borrowerService;
        this.borrowerUserLinker = borrowerUserLinker;
        this.currentUser = currentUser;
        this.defaultLoanOfficerId = UUID.fromString(defaultLoanOfficerId);
    }

    @Transactional
    public IntakeResult intake(IntakeRequest req) {
        // 1) Idempotency: an existing loan for this lead → return its identifiers, no duplicate / relink.
        Optional<Loan> existing = loanService.findBySourceLeadId(req.sourceLeadId());
        if (existing.isPresent()) {
            Loan loan = existing.get();
            return new IntakeResult(loan.getId(), loan.getLoanNumber());
        }

        // 2) Create the loan. Pass an EXPLICIT default officer (NOT the borrower); null would make
        //    LoanService.create default the caller as LO. Then tag the upstream lead id (dedupe key).
        Loan loan = loanService.create(new CreateLoanRequest(
                req.loanPurpose(), req.mortgageType(), null, null, null, defaultLoanOfficerId));
        loanService.tagSourceLead(loan.getId(), req.sourceLeadId());

        // 3) Carry the minimal property details via the validated loan-update path (mirror CloneService
        //    arity precisely — 29 fields; only mortgageType + the property address/value are populated).
        if (req.property() != null) {
            IntakeRequest.Property p = req.property();
            loanService.update(loan.getId(), new UpdateLoanRequest(
                    req.mortgageType(),       // mortgageType
                    null,                     // lienPriority
                    null,                     // amortizationType
                    null,                     // noteAmount
                    p.addressLine1(),         // addressLine1
                    null,                     // addressLine2
                    p.city(),                 // city
                    p.state(),                // state
                    p.postalCode(),           // postalCode
                    p.estimatedValue(),       // estimatedValue
                    null,                     // documentationType
                    null,                     // interestRate
                    null,                     // loanTermMonths
                    null,                     // baseLoanAmount
                    null,                     // financedFeesAmount
                    null,                     // secondLoanAmount
                    null,                     // downPaymentAmount
                    null,                     // qualifyingCreditScore
                    null,                     // proposedTaxesMonthly
                    null,                     // proposedHazardInsuranceMonthly
                    null,                     // proposedHoaDuesMonthly
                    null,                     // proposedMortgageInsuranceMonthly
                    null,                     // salesPrice
                    null,                     // appraisedValue
                    null,                     // propertyType
                    null,                     // occupancyType
                    null,                     // numberOfUnits
                    null));                   // consummationDate
        }

        // 4) Primary borrower. firstName + lastName are required for a usable 1003 row.
        IntakeRequest.Borrower b = req.borrower();
        if (b == null || isBlank(b.firstName()) || isBlank(b.lastName())) {
            throw new ValidationException("borrower firstName and lastName are required");
        }
        BorrowerParty party = borrowerService.addAtIntake(loan.getId(), new AddBorrowerRequest(
                b.firstName(), b.lastName(), true,          // firstName, lastName, primary
                null, null, null, null, null, null, null,   // middleName, suffix, ssn, dob, marital, dependentsCount, dependentAges
                null, null, null, null,                     // citizenshipType, veteran, unmarriedAddendum, joinedToBorrowerId
                null, b.phone(), null, null,                // homePhone, cellPhone, workPhone, workPhoneExt
                b.email(), null));                          // email, noEmail

        // 5) Link the new borrower row to the caller's Cognito sub so /me/loans resolves it. The
        //    linker is idempotent (only stamps while user_id IS NULL); an unparseable/absent sub no-ops.
        UUID callerSub = currentUser.id().map(IntakeService::toUuid).orElse(null);
        if (callerSub != null) {
            borrowerUserLinker.linkById(party.getId(), callerSub);
        }

        return new IntakeResult(loan.getId(), loan.getLoanNumber());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Parse a Cognito sub to UUID; {@code null} on a non-UUID sub (never link an unparseable sub). */
    private static UUID toUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
