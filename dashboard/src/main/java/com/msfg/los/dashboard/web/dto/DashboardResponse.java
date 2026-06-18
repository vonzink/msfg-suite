package com.msfg.los.dashboard.web.dto;

import com.msfg.los.conditions.web.dto.ConditionResponse;
import com.msfg.los.contacts.web.dto.ContactResponse;
import com.msfg.los.loan.domain.AmortizationType;
import com.msfg.los.loan.domain.LienPriorityType;
import com.msfg.los.loan.domain.LoanStatus;
import com.msfg.los.notes.web.dto.NoteResponse;
import com.msfg.los.parties.domain.MaritalStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Aggregated, read-only loan dashboard payload (Phase 2 T6). Assembled from the owning modules'
 * SERVICES only — no cross-module repository access (ArchUnit {@code ModuleBoundaryTest}). Every
 * nested record has a unique simple name (springdoc keys schemas by simple name; reuses the
 * already-registered {@link ConditionResponse}/{@link NoteResponse}/{@link ContactResponse}).
 *
 * <p>NPI safety: the primary borrower's SSN is never carried here — only non-NPI identity fields.
 */
public record DashboardResponse(
        UUID loanId,
        String applicationNumber,
        LoanStatus status,
        Instant createdAt,
        Instant updatedAt,
        DashboardIdentifiers identifiers,
        DashboardPrimaryBorrower primaryBorrower,
        DashboardProperty property,
        DashboardLoanTerms loanTerms,
        DashboardHousingExpenses housingExpenses,
        List<DashboardPurchaseCredit> purchaseCredits,
        DashboardConditions conditions,
        List<DashboardStatusHistoryEntry> statusHistory,
        List<ContactResponse> loanAgents,
        DashboardClosingInformation closingInformation,
        List<NoteResponse> notes) {

    /** Loan identifiers present on the loan (the loan number; extend as more identifiers are modeled). */
    public record DashboardIdentifiers(
            UUID loanId,
            String loanNumber) {}

    /** Primary borrower — non-NPI identity only (SSN deliberately omitted). */
    public record DashboardPrimaryBorrower(
            UUID id,
            String firstName,
            String lastName,
            String email,
            String phone,
            MaritalStatus maritalStatus) {}

    /** Subject property snapshot (whatever {@code SubjectProperty} exposes). */
    public record DashboardProperty(
            String addressLine1,
            String addressLine2,
            String city,
            String state,
            String postalCode,
            BigDecimal estimatedValue,
            BigDecimal salesPrice,
            BigDecimal appraisedValue,
            Integer numberOfUnits) {}

    /** Loan §4 terms (the editable set). */
    public record DashboardLoanTerms(
            BigDecimal baseLoanAmount,
            BigDecimal noteAmount,
            BigDecimal interestRate,
            BigDecimal downPaymentAmount,
            AmortizationType amortizationType,
            Integer loanTermMonths,
            LienPriorityType lienPriority) {}

    /** Proposed-housing PITI monthly inputs on the loan. */
    public record DashboardHousingExpenses(
            BigDecimal proposedTaxesMonthly,
            BigDecimal proposedHazardInsuranceMonthly,
            BigDecimal proposedHoaDuesMonthly,
            BigDecimal proposedMortgageInsuranceMonthly) {}

    /** A fees section-L credit line (purchase credits). */
    public record DashboardPurchaseCredit(
            UUID id,
            String label,
            BigDecimal amount,
            BigDecimal sellerConcession,
            String paidTo) {}

    /** Conditions list + the outstanding count (drives the pipeline {@code conditionsGt} facet too). */
    public record DashboardConditions(
            int outstandingCount,
            List<ConditionResponse> items) {}

    /** One status-history transition (newest-first in the list), with the note/by stamp. */
    public record DashboardStatusHistoryEntry(
            LoanStatus fromStatus,
            LoanStatus toStatus,
            Instant transitionedAt,
            String note,
            String transitionedBy) {}

    /** Closing info assembled from contacts (title/closing/appraiser companies) + consummation date. */
    public record DashboardClosingInformation(
            LocalDate consummationDate,
            String titleCompany,
            String escrowOfficer,
            String appraiser) {}
}
