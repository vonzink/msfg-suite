package com.msfg.los.declarations.web.dto;

import com.msfg.los.declarations.domain.BankruptcyType;
import com.msfg.los.declarations.domain.BorrowerDeclarations;
import com.msfg.los.declarations.domain.PriorPropertyTitleType;
import com.msfg.los.loan.domain.OccupancyType;

import java.util.LinkedHashSet;
import java.util.Set;

public record DeclarationsResponse(
        String id,
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
        Set<BankruptcyType> bankruptcyTypes
) {
    /** Maps a persisted entity to the response. */
    public static DeclarationsResponse from(BorrowerDeclarations e) {
        if (e == null) {
            return new DeclarationsResponse(null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, new LinkedHashSet<>());
        }
        return new DeclarationsResponse(
                e.getId() != null ? e.getId().toString() : null,
                e.getOccupyAsPrimaryResidence(),
                e.getHadOwnershipInterestLast3Years(),
                e.getFamilyOrBusinessAffiliationWithSeller(),
                e.getBorrowingUndisclosedMoney(),
                e.getApplyingForOtherMortgageOnProperty(),
                e.getApplyingForNewCreditBeforeClosing(),
                e.getSubjectToPriorityLienPace(),
                e.getCoSignerOrGuarantorOnUndisclosedDebt(),
                e.getOutstandingJudgments(),
                e.getDelinquentOrDefaultOnFederalDebt(),
                e.getPartyToLawsuit(),
                e.getConveyedTitleInLieuLast7Years(),
                e.getCompletedPreForeclosureShortSaleLast7Years(),
                e.getPropertyForeclosedLast7Years(),
                e.getDeclaredBankruptcyLast7Years(),
                e.getPriorPropertyUsage(),
                e.getPriorPropertyTitleType(),
                e.getBankruptcyTypes() != null ? e.getBankruptcyTypes() : new LinkedHashSet<>()
        );
    }
}
