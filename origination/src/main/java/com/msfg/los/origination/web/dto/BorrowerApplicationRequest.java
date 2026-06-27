package com.msfg.los.origination.web.dto;

import com.msfg.los.declarations.domain.Ethnicity;
import com.msfg.los.declarations.domain.Race;
import com.msfg.los.declarations.domain.Sex;
import com.msfg.los.declarations.web.dto.DeclarationsRequest;
import com.msfg.los.financials.domain.AssetType;
import com.msfg.los.financials.domain.LiabilityType;
import com.msfg.los.income.domain.EmploymentClassificationType;
import com.msfg.los.income.domain.EmploymentStatusType;
import com.msfg.los.income.domain.IncomeType;
import com.msfg.los.income.domain.OwnershipInterestType;
import com.msfg.los.loan.domain.MortgageType;
import com.msfg.los.loan.domain.OccupancyType;
import com.msfg.los.loan.domain.PropertyType;
import com.msfg.los.parties.domain.CitizenshipType;
import com.msfg.los.parties.domain.MaritalStatus;
import com.msfg.los.platform.reference.UsStateCode;
import com.msfg.los.reo.domain.ReoPropertyStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Borrower-self application upsert (Stage 2) — the full 1003 the client {@code /apply} form submits.
 *
 * <p>Section semantics: a {@code null} section is SKIPPED (left untouched — so the LO's concurrent
 * edits to a section the borrower didn't submit survive); a non-null section is a full REPLACE of the
 * caller's own rows for that section (an empty list clears them). The {@code income} block is
 * all-or-nothing (employment + income replace together, due to the IncomeItem→Employment FK).
 *
 * <p>Defence in depth: every sub-record contains ONLY borrower-writable fields. Staff/UW/pricing fields
 * are absent here AND hard-defaulted in the orchestrator mapping — a borrower can never set
 * {@code verified} (asset VOA), {@code includeInDti}/{@code exclusionReason} (DTI underwriting),
 * {@code ownerBorrowerId} (forced to the caller), or any §4 rate/term/pricing field.
 */
public record BorrowerApplicationRequest(
    LoanInfo loan,
    BorrowerInfo borrower,
    IncomeSection income,
    List<AssetInfo> assets,
    List<LiabilityInfo> liabilities,
    List<ReoInfo> reo,
    DeclarationsRequest declarations,
    DemographicsInfo demographics) {

    public record LoanInfo(
        MortgageType mortgageType,
        BigDecimal baseLoanAmount,
        BigDecimal downPaymentAmount,
        BigDecimal estimatedValue,
        BigDecimal salesPrice,
        String addressLine1,
        String addressLine2,
        String city,
        String state,
        String postalCode,
        PropertyType propertyType,
        OccupancyType occupancyType,
        Integer numberOfUnits) {}

    public record BorrowerInfo(
        String firstName,
        String lastName,
        String middleName,
        String suffix,
        String ssn,
        LocalDate dateOfBirth,
        MaritalStatus maritalStatus,
        Integer dependentsCount,
        String dependentAges,
        CitizenshipType citizenshipType,
        String homePhone,
        String cellPhone,
        String workPhone,
        String workPhoneExt,
        String email) {}

    /** Employment + income replace together (the IncomeItem→Employment FK forbids partial). */
    public record IncomeSection(
        List<EmploymentInfo> employments,
        List<OtherIncomeInfo> otherIncome) {}

    /** One employment; {@code monthlyIncome} becomes a BASE IncomeItem linked to the new employment id. */
    public record EmploymentInfo(
        String employerName,
        String employerPhone,
        String employerAddressLine1,
        String employerAddressLine2,
        String employerCity,
        UsStateCode employerState,
        String employerPostalCode,
        String positionTitle,
        EmploymentStatusType employmentStatus,
        EmploymentClassificationType classification,
        Boolean selfEmployed,
        OwnershipInterestType ownershipShare,
        Boolean employedByPartyToTransaction,
        LocalDate startDate,
        LocalDate endDate,
        Integer monthsInLineOfWork,
        BigDecimal monthlyIncome) {}

    /** Non-employment income (employmentId is forced null; type must be a non-employment IncomeType). */
    public record OtherIncomeInfo(
        IncomeType incomeType,
        BigDecimal monthlyAmount,
        String description) {}

    public record AssetInfo(
        AssetType assetType,
        String financialInstitution,
        String accountNumber,
        BigDecimal cashOrMarketValue) {}

    public record LiabilityInfo(
        LiabilityType liabilityType,
        String creditorName,
        String accountNumber,
        BigDecimal unpaidBalance,
        BigDecimal monthlyPayment,
        Integer monthsRemaining) {}

    public record ReoInfo(
        Boolean isSubjectProperty,
        String addressLine1,
        String addressLine2,
        String city,
        UsStateCode state,
        String postalCode,
        PropertyType propertyType,
        OccupancyType intendedOccupancy,
        ReoPropertyStatus propertyStatus,
        BigDecimal marketValue,
        BigDecimal grossMonthlyRentalIncome,
        BigDecimal monthlyTaxes,
        BigDecimal monthlyInsurance,
        BigDecimal monthlyHoaDues,
        BigDecimal monthlyMaintenance,
        BigDecimal mortgageUnpaidBalance,
        BigDecimal mortgageMonthlyPayment) {}

    /**
     * HMDA self-report ONLY (ethnicity/race/sex). The lender-attestation fields
     * {@code collectedByVisualObservationOrSurname} and {@code applicationTakenMethod} are NOT
     * borrower-settable — the orchestrator forces them (self-service = not-by-observation, INTERNET).
     */
    public record DemographicsInfo(
        Set<Ethnicity> ethnicity,
        Set<Race> race,
        Sex sex) {}
}
