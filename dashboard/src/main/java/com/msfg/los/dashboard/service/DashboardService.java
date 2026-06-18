package com.msfg.los.dashboard.service;

import com.msfg.los.conditions.domain.LoanCondition;
import com.msfg.los.conditions.service.ConditionService;
import com.msfg.los.conditions.web.dto.ConditionResponse;
import com.msfg.los.contacts.domain.Contact;
import com.msfg.los.contacts.domain.ContactRole;
import com.msfg.los.contacts.service.ContactService;
import com.msfg.los.contacts.web.dto.ContactResponse;
import com.msfg.los.dashboard.web.dto.DashboardResponse;
import com.msfg.los.dashboard.web.dto.DashboardResponse.DashboardClosingInformation;
import com.msfg.los.dashboard.web.dto.DashboardResponse.DashboardConditions;
import com.msfg.los.dashboard.web.dto.DashboardResponse.DashboardHousingExpenses;
import com.msfg.los.dashboard.web.dto.DashboardResponse.DashboardIdentifiers;
import com.msfg.los.dashboard.web.dto.DashboardResponse.DashboardLoanTerms;
import com.msfg.los.dashboard.web.dto.DashboardResponse.DashboardPrimaryBorrower;
import com.msfg.los.dashboard.web.dto.DashboardResponse.DashboardProperty;
import com.msfg.los.dashboard.web.dto.DashboardResponse.DashboardPurchaseCredit;
import com.msfg.los.dashboard.web.dto.DashboardResponse.DashboardStatusHistoryEntry;
import com.msfg.los.dashboard.web.dto.DashboardTermsPatch;
import com.msfg.los.fees.domain.FeeLineItem;
import com.msfg.los.fees.domain.FeeSection;
import com.msfg.los.fees.service.FeeService;
import com.msfg.los.loan.domain.Loan;
import com.msfg.los.loan.domain.LoanStatusHistory;
import com.msfg.los.loan.domain.SubjectProperty;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.loan.web.dto.UpdateLoanRequest;
import com.msfg.los.notes.domain.LoanNote;
import com.msfg.los.notes.service.NoteService;
import com.msfg.los.notes.web.dto.NoteResponse;
import com.msfg.los.parties.domain.BorrowerParty;
import com.msfg.los.parties.service.BorrowerService;
import com.msfg.los.platform.error.ValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Read-only loan dashboard aggregator (Phase 2 T6). Composes the per-section payload by calling each
 * owning module's SERVICE — never another module's repository (ArchUnit {@code ModuleBoundaryTest}).
 * The dashboard module is a leaf consumer; nothing depends on it, so there is no bean cycle.
 *
 * <p>Mirrors the {@code qualification} aggregator: guard via {@link LoanAccessGuard#assertCanAccess}
 * on the loan resolved by {@link LoanService#get}, then assemble in-code.
 */
@Service
public class DashboardService {

    private static final BigDecimal RATE_CEILING = BigDecimal.valueOf(100);

    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;
    private final BorrowerService borrowerService;
    private final FeeService feeService;
    private final ContactService contactService;
    private final ConditionService conditionService;
    private final NoteService noteService;

    public DashboardService(
            LoanService loanService,
            LoanAccessGuard accessGuard,
            BorrowerService borrowerService,
            FeeService feeService,
            ContactService contactService,
            ConditionService conditionService,
            NoteService noteService) {
        this.loanService = loanService;
        this.accessGuard = accessGuard;
        this.borrowerService = borrowerService;
        this.feeService = feeService;
        this.contactService = contactService;
        this.conditionService = conditionService;
        this.noteService = noteService;
    }

    @Transactional(readOnly = true)
    public DashboardResponse dashboard(UUID loanId) {
        // Guard: 404 if cross-org (get filters by tenant + deleted), 403 if no access.
        Loan loan = loanService.get(loanId);
        accessGuard.assertCanAccess(loan);

        // Fetch contacts once — used by both loanAgents and closingInformation.
        List<Contact> contacts = contactService.list(loanId);

        return new DashboardResponse(
                loan.getId(),
                loan.getLoanNumber(),
                loan.getStatus(),
                loan.getCreatedAt(),
                loan.getUpdatedAt(),
                new DashboardIdentifiers(loan.getId(), loan.getLoanNumber()),
                primaryBorrower(loanId),
                property(loan.getSubjectProperty()),
                loanTerms(loan),
                housingExpenses(loan),
                purchaseCredits(loanId),
                conditions(loanId),
                statusHistory(loanId),
                contacts.stream().map(ContactResponse::from).toList(),
                closingInformation(loan, contacts),
                notes(loanId));
    }

    /**
     * Patch the loan §4 terms in place, reusing {@link LoanService#update} (no duplicated update
     * logic). Validates {@code interestRate} in the spec's range (&gt;=0, &lt;100) first; the loan
     * service further tightens it (0..25). Returns the updated terms section.
     */
    @Transactional
    public DashboardLoanTerms patchTerms(UUID loanId, DashboardTermsPatch patch) {
        Loan loan = loanService.get(loanId);
        accessGuard.assertCanAccess(loan);

        if (patch.interestRate() != null
                && (patch.interestRate().signum() < 0 || patch.interestRate().compareTo(RATE_CEILING) >= 0)) {
            throw new ValidationException("interestRate must be >= 0 and < 100");
        }

        UpdateLoanRequest req = new UpdateLoanRequest(
                null,                       // mortgageType
                patch.lienPriority(),
                patch.amortizationType(),
                patch.noteAmount(),
                null, null, null, null, null, null, // address + estimatedValue
                null,                       // documentationType
                patch.interestRate(),
                patch.loanTermMonths(),
                patch.baseLoanAmount(),
                null, null,                 // financedFeesAmount, secondLoanAmount
                patch.downPaymentAmount(),
                null,                       // qualifyingCreditScore
                null, null, null, null,     // proposed housing
                null, null, null, null, null, // subject property §4
                null);                      // consummationDate

        Loan updated = loanService.update(loanId, req);
        return loanTerms(updated);
    }

    // ── section assembly ──────────────────────────────────────────────────────

    private DashboardPrimaryBorrower primaryBorrower(UUID loanId) {
        // BorrowerService.list is itself access-gated and returns ordinal-ordered borrowers.
        List<BorrowerParty> borrowers = borrowerService.list(loanId);
        BorrowerParty b = borrowers.stream().filter(BorrowerParty::isPrimary).findFirst()
                .orElse(borrowers.isEmpty() ? null : borrowers.get(0));
        if (b == null) {
            return null;
        }
        return new DashboardPrimaryBorrower(
                b.getId(),
                b.getFirstName(),
                b.getLastName(),
                b.getEmail(),
                firstNonBlank(b.getCellPhone(), b.getHomePhone(), b.getWorkPhone()),
                b.getMaritalStatus());
        // SSN deliberately omitted — NPI is never carried on the dashboard payload.
    }

    private DashboardProperty property(SubjectProperty p) {
        if (p == null) {
            return null;
        }
        return new DashboardProperty(
                p.getAddressLine1(),
                p.getAddressLine2(),
                p.getCity(),
                p.getState(),
                p.getPostalCode(),
                p.getEstimatedValue(),
                p.getSalesPrice(),
                p.getAppraisedValue(),
                p.getNumberOfUnits());
    }

    private DashboardLoanTerms loanTerms(Loan loan) {
        return new DashboardLoanTerms(
                loan.getBaseLoanAmount(),
                loan.getNoteAmount(),
                loan.getInterestRate(),
                loan.getDownPaymentAmount(),
                loan.getAmortizationType(),
                loan.getLoanTermMonths(),
                loan.getLienPriority());
    }

    private DashboardHousingExpenses housingExpenses(Loan loan) {
        return new DashboardHousingExpenses(
                loan.getProposedTaxesMonthly(),
                loan.getProposedHazardInsuranceMonthly(),
                loan.getProposedHoaDuesMonthly(),
                loan.getProposedMortgageInsuranceMonthly());
    }

    private List<DashboardPurchaseCredit> purchaseCredits(UUID loanId) {
        return feeService.list(loanId).stream()
                .filter(f -> f.getSection() == FeeSection.L)
                .map(this::toPurchaseCredit)
                .toList();
    }

    private DashboardPurchaseCredit toPurchaseCredit(FeeLineItem f) {
        return new DashboardPurchaseCredit(
                f.getId(), f.getLabel(), f.getAmount(), f.getSellerConcession(), f.getPaidTo());
    }

    private DashboardConditions conditions(UUID loanId) {
        // ConditionService.list is access-gated; outstandingCount is the count seam.
        List<ConditionResponse> items = conditionService.list(loanId).stream()
                .map(ConditionResponse::from)
                .toList();
        return new DashboardConditions(conditionService.outstandingCount(loanId), items);
    }

    private List<DashboardStatusHistoryEntry> statusHistory(UUID loanId) {
        // LoanService.history is createdAt-ASC (oldest-first); reverse for newest-first.
        List<LoanStatusHistory> rows = loanService.history(loanId);
        return rows.stream()
                .sorted(Comparator.comparing(
                                LoanStatusHistory::getTransitionedAt,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .reversed())
                .map(h -> new DashboardStatusHistoryEntry(
                        h.getFromStatus(),
                        h.getToStatus(),
                        h.getTransitionedAt(),
                        h.getReason(),
                        h.getCreatedBy()))
                .toList();
    }

    private DashboardClosingInformation closingInformation(Loan loan, List<Contact> contacts) {
        return new DashboardClosingInformation(
                loan.getConsummationDate(),
                companyFor(contacts, ContactRole.TITLE_COMPANY),
                companyFor(contacts, ContactRole.ESCROW_OFFICER),
                companyFor(contacts, ContactRole.APPRAISER));
    }

    private List<NoteResponse> notes(UUID loanId) {
        // NoteService.list is access-gated and already newest-first.
        return noteService.list(loanId).stream().map(NoteResponse::from).toList();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** First contact's company (fallback to its name) for a role, or null when absent. */
    private static String companyFor(List<Contact> contacts, ContactRole role) {
        return contacts.stream()
                .filter(c -> c.getRole() == role)
                .map(c -> c.getCompany() != null && !c.getCompany().isBlank() ? c.getCompany() : c.getName())
                .findFirst()
                .orElse(null);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
