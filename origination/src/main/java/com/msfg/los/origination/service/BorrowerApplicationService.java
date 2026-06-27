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
import com.msfg.los.declarations.domain.ApplicationTakenMethod;
import com.msfg.los.declarations.service.DeclarationsService;
import com.msfg.los.declarations.service.DemographicsService;
import com.msfg.los.declarations.web.dto.DemographicsRequest;
import com.msfg.los.financials.service.AssetService;
import com.msfg.los.financials.service.LiabilityService;
import com.msfg.los.financials.web.dto.AddAssetRequest;
import com.msfg.los.financials.web.dto.AddLiabilityRequest;
import com.msfg.los.income.domain.Employment;
import com.msfg.los.income.domain.IncomeType;
import com.msfg.los.income.service.EmploymentService;
import com.msfg.los.income.service.IncomeService;
import com.msfg.los.income.web.dto.AddEmploymentRequest;
import com.msfg.los.income.web.dto.AddIncomeRequest;
import com.msfg.los.reo.service.ReoService;
import com.msfg.los.reo.web.dto.AddReoRequest;
import com.msfg.los.platform.error.ForbiddenException;
import com.msfg.los.platform.error.NotFoundException;
import com.msfg.los.platform.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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
    private final EmploymentService employmentService;
    private final IncomeService incomeService;
    private final AssetService assetService;
    private final LiabilityService liabilityService;
    private final ReoService reoService;
    private final DeclarationsService declarationsService;
    private final DemographicsService demographicsService;

    public BorrowerApplicationService(LoanService loanService, BorrowerService borrowerService,
                                      LoanAccessGuard accessGuard, CurrentUser currentUser,
                                      EmploymentService employmentService, IncomeService incomeService,
                                      AssetService assetService, LiabilityService liabilityService,
                                      ReoService reoService, DeclarationsService declarationsService,
                                      DemographicsService demographicsService) {
        this.loanService = loanService;
        this.borrowerService = borrowerService;
        this.accessGuard = accessGuard;
        this.currentUser = currentUser;
        this.employmentService = employmentService;
        this.incomeService = incomeService;
        this.assetService = assetService;
        this.liabilityService = liabilityService;
        this.reoService = reoService;
        this.declarationsService = declarationsService;
        this.demographicsService = demographicsService;
    }

    /** Hydrate the caller's application view. Borrower reads their own row; staff read the primary. */
    @Transactional(readOnly = true)
    public BorrowerApplicationResponse get(UUID loanId) {
        Loan loan = loanService.get(loanId);
        BorrowerParty target = resolveTarget(loan);
        accessGuard.assertBorrowerSelfReadable(loan, target.getId());
        return toResponse(loan, target);
    }

    /** Upsert the caller's application: §4 subset on the loan + the caller's own borrower row. */
    @Transactional
    public BorrowerApplicationResponse upsert(UUID loanId, BorrowerApplicationRequest req) {
        Loan loan = loanService.get(loanId);
        BorrowerParty target = resolveTarget(loan);
        accessGuard.assertBorrowerSelfWritable(loan, target.getId());

        // NOTE: the borrower ROW is self-scoped (target = the caller's own row). The loan §4 block is
        // loan-scoped (shared) — a co-borrower who passes the self-row guard may also set shared loan
        // fields (address/value/price/amounts) over the LO/primary's values (last-write-wins). This is
        // intentional for a JOINT LO+borrower application; staff-only/pricing/UW fields stay null-guarded.
        Loan updatedLoan = (req.loan() != null)
            ? loanService.update(loanId, toUpdateLoanRequest(req.loan()))
            : loan;
        BorrowerParty updatedBorrower = (req.borrower() != null)
            ? borrowerService.updateInternal(loanId, target.getId(), toUpdateBorrowerRequest(req.borrower()))
            : target;

        final UUID bid = target.getId();

        // Income + employment replace TOGETHER (null section = skip; present = full replace). FK ordering:
        // delete IncomeItems (children) → Employments (parents); re-add employments (capture new ids) →
        // income referencing those ids (employment income = BASE IncomeItem on the new id; other income = null id).
        if (req.income() != null) {
            incomeService.deleteAllForBorrowerInternal(loanId, bid);
            employmentService.deleteAllForBorrowerInternal(loanId, bid);
            for (BorrowerApplicationRequest.EmploymentInfo e : nullToEmpty(req.income().employments())) {
                Employment saved = employmentService.addInternal(loanId, bid, toAddEmploymentRequest(e));
                if (e.monthlyIncome() != null) {
                    incomeService.addInternal(loanId, bid,
                        new AddIncomeRequest(IncomeType.BASE, e.monthlyIncome(), saved.getId(), null));
                }
            }
            for (BorrowerApplicationRequest.OtherIncomeInfo oi : nullToEmpty(req.income().otherIncome())) {
                incomeService.addInternal(loanId, bid,
                    new AddIncomeRequest(oi.incomeType(), oi.monthlyAmount(), null, oi.description()));
            }
        }
        // Per-borrower list sections: null = skip, non-null = full replace (empty clears). REO is forced
        // to the caller's ownerBorrowerId by the seam.
        if (req.assets() != null) {
            assetService.replaceForBorrowerInternal(loanId, bid,
                nullToEmpty(req.assets()).stream().map(this::toAddAssetRequest).toList());
        }
        if (req.liabilities() != null) {
            liabilityService.replaceForBorrowerInternal(loanId, bid,
                nullToEmpty(req.liabilities()).stream().map(this::toAddLiabilityRequest).toList());
        }
        if (req.reo() != null) {
            reoService.replaceForBorrowerInternal(loanId, bid,
                nullToEmpty(req.reo()).stream().map(this::toAddReoRequest).toList());
        }
        // 1:1 sections: null = skip, present = full upsert.
        if (req.declarations() != null) {
            declarationsService.upsertInternal(loanId, bid, req.declarations());
        }
        if (req.demographics() != null) {
            demographicsService.upsertInternal(loanId, bid, toDemographicsRequest(req.demographics()));
        }

        return toResponse(updatedLoan, updatedBorrower);
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    /**
     * Resolve the borrower row the caller acts on: their OWN row (by linked sub) if present, else —
     * for STAFF/owning-LO ONLY — the loan's PRIMARY borrower. A party with no self row is denied HERE
     * (defense in depth) so {@code assertBorrowerSelf*} on the resolved id is a true SECOND layer, not
     * the sole gate: a future refactor that drops or reorders the guard can never silently hand a
     * borrower the primary's row. Both reads are no-guard seams.
     */
    private BorrowerParty resolveTarget(Loan loan) {
        UUID sub = currentSubject();
        Optional<BorrowerParty> self = (sub != null) ? borrowerService.findSelf(loan.getId(), sub) : Optional.empty();
        if (self.isPresent()) return self.get();
        if (!accessGuard.isStaffOrOwningLo(loan)) {
            throw new ForbiddenException("No access to loan " + loan.getLoanNumber());
        }
        return borrowerService.findPrimary(loan.getId())
            .orElseThrow(() -> new NotFoundException("Borrower for loan", loan.getId()));
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

    private AddEmploymentRequest toAddEmploymentRequest(BorrowerApplicationRequest.EmploymentInfo e) {
        return new AddEmploymentRequest(
            e.employerName(), e.employerPhone(), e.employerAddressLine1(), e.employerAddressLine2(),
            e.employerCity(), e.employerState(), e.employerPostalCode(), e.positionTitle(),
            e.employmentStatus(), e.classification(), e.selfEmployed(), e.ownershipShare(),
            e.employedByPartyToTransaction(), e.startDate(), e.endDate(), e.monthsInLineOfWork());
    }

    private AddAssetRequest toAddAssetRequest(BorrowerApplicationRequest.AssetInfo a) {
        // verified (VOA) is staff-only → null (default unverified).
        return new AddAssetRequest(a.assetType(), a.financialInstitution(), a.accountNumber(),
            a.cashOrMarketValue(), null);
    }

    private AddLiabilityRequest toAddLiabilityRequest(BorrowerApplicationRequest.LiabilityInfo l) {
        // includeInDti/exclusionReason are UW decisions → null (defaults include=true, reason cleared).
        return new AddLiabilityRequest(l.liabilityType(), l.creditorName(), l.accountNumber(),
            l.unpaidBalance(), l.monthlyPayment(), null, null, l.monthsRemaining());
    }

    private DemographicsRequest toDemographicsRequest(BorrowerApplicationRequest.DemographicsInfo in) {
        // HMDA self-report only. The lender-attestation fields are NOT borrower-settable: a self-service
        // online submission is, by definition, NOT collected by visual observation/surname and IS via INTERNET.
        return new DemographicsRequest(in.ethnicity(), in.race(), in.sex(), false, ApplicationTakenMethod.INTERNET);
    }

    private AddReoRequest toAddReoRequest(BorrowerApplicationRequest.ReoInfo r) {
        // ownerBorrowerId is forced to the caller by the seam → pass null here (body not trusted).
        return new AddReoRequest(
            null, r.isSubjectProperty(), r.addressLine1(), r.addressLine2(), r.city(), r.state(),
            r.postalCode(), r.propertyType(), r.intendedOccupancy(), r.propertyStatus(),
            r.marketValue(), r.grossMonthlyRentalIncome(), r.monthlyTaxes(), r.monthlyInsurance(),
            r.monthlyHoaDues(), r.monthlyMaintenance(), r.mortgageUnpaidBalance(), r.mortgageMonthlyPayment());
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
