package com.msfg.los.declarations.web.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.msfg.los.declarations.domain.BankruptcyType;
import com.msfg.los.declarations.domain.PriorPropertyTitleType;
import com.msfg.los.loan.domain.OccupancyType;

import java.util.LinkedHashSet;
import java.util.Set;

public record DeclarationsRequest(
        Boolean occupyAsPrimaryResidence,
        Boolean hadOwnershipInterestLast3Years,
        Boolean familyOrBusinessAffiliationWithSeller,
        Boolean borrowingUndisclosedMoney,
        Boolean applyingForOtherMortgageOnProperty,
        Boolean applyingForNewCreditBeforeClosing,
        Boolean subjectToPriorityLienPace,
        Boolean coSignerOrGuarantorOnUndisclosedDebt,
        Boolean outstandingJudgments,
        Boolean delinquentOrDefaultOnFederalDebt,
        Boolean partyToLawsuit,
        Boolean conveyedTitleInLieuLast7Years,
        Boolean completedPreForeclosureShortSaleLast7Years,
        Boolean propertyForeclosedLast7Years,
        Boolean declaredBankruptcyLast7Years,
        OccupancyType priorPropertyUsage,
        PriorPropertyTitleType priorPropertyTitleType,
        @JsonDeserialize(as = LinkedHashSet.class) Set<BankruptcyType> bankruptcyTypes
) {}
