package com.msfg.los.declarations.domain;

import com.msfg.los.loan.domain.OccupancyType;
import com.msfg.los.platform.domain.TenantScopedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "borrower_declarations")
@Getter
@Setter
public class BorrowerDeclarations extends TenantScopedEntity {

    @Column(name = "loan_id", nullable = false)
    private UUID loanId;

    @Column(name = "borrower_id", nullable = false)
    private UUID borrowerId;

    // §5 Declarations — nullable Boolean (yes/no/unanswered)
    @Column(name = "occupy_as_primary_residence")
    private Boolean occupyAsPrimaryResidence;

    @Column(name = "had_ownership_interest_last3_years")
    private Boolean hadOwnershipInterestLast3Years;

    @Column(name = "family_or_business_affiliation_with_seller")
    private Boolean familyOrBusinessAffiliationWithSeller;

    @Column(name = "borrowing_undisclosed_money")
    private Boolean borrowingUndisclosedMoney;

    @Column(name = "applying_for_other_mortgage_on_property")
    private Boolean applyingForOtherMortgageOnProperty;

    @Column(name = "applying_for_new_credit_before_closing")
    private Boolean applyingForNewCreditBeforeClosing;

    @Column(name = "subject_to_priority_lien_pace")
    private Boolean subjectToPriorityLienPace;

    @Column(name = "co_signer_or_guarantor_on_undisclosed_debt")
    private Boolean coSignerOrGuarantorOnUndisclosedDebt;

    @Column(name = "outstanding_judgments")
    private Boolean outstandingJudgments;

    @Column(name = "delinquent_or_default_on_federal_debt")
    private Boolean delinquentOrDefaultOnFederalDebt;

    @Column(name = "party_to_lawsuit")
    private Boolean partyToLawsuit;

    @Column(name = "conveyed_title_in_lieu_last7_years")
    private Boolean conveyedTitleInLieuLast7Years;

    @Column(name = "completed_pre_foreclosure_short_sale_last7_years")
    private Boolean completedPreForeclosureShortSaleLast7Years;

    @Column(name = "property_foreclosed_last7_years")
    private Boolean propertyForeclosedLast7Years;

    @Column(name = "declared_bankruptcy_last7_years")
    private Boolean declaredBankruptcyLast7Years;

    // Follow-up fields
    @Enumerated(EnumType.STRING)
    @Column(name = "prior_property_usage", length = 40)
    private OccupancyType priorPropertyUsage;

    @Enumerated(EnumType.STRING)
    @Column(name = "prior_property_title_type", length = 40)
    private PriorPropertyTitleType priorPropertyTitleType;

    @Convert(converter = BankruptcyTypeSetConverter.class)
    @Column(name = "bankruptcy_types", length = 60)
    private Set<BankruptcyType> bankruptcyTypes = new LinkedHashSet<>();
}
