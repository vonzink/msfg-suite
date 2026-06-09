package com.msfg.los.reo.web.dto;

import com.msfg.los.loan.domain.OccupancyType;
import com.msfg.los.loan.domain.PropertyType;
import com.msfg.los.platform.reference.UsStateCode;
import com.msfg.los.reo.domain.RealEstateOwned;
import com.msfg.los.reo.domain.ReoPropertyStatus;

import java.math.BigDecimal;
import java.util.UUID;

public record ReoResponse(
        UUID id,
        UUID loanId,
        UUID ownerBorrowerId,
        int ordinal,
        boolean isSubjectProperty,
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
        BigDecimal mortgageMonthlyPayment) {

    public static ReoResponse from(RealEstateOwned r) {
        return new ReoResponse(
                r.getId(),
                r.getLoanId(),
                r.getOwnerBorrowerId(),
                r.getOrdinal(),
                r.isSubjectProperty(),
                r.getAddressLine1(),
                r.getAddressLine2(),
                r.getCity(),
                r.getState(),
                r.getPostalCode(),
                r.getPropertyType(),
                r.getIntendedOccupancy(),
                r.getPropertyStatus(),
                r.getMarketValue(),
                r.getGrossMonthlyRentalIncome(),
                r.getMonthlyTaxes(),
                r.getMonthlyInsurance(),
                r.getMonthlyHoaDues(),
                r.getMonthlyMaintenance(),
                r.getMortgageUnpaidBalance(),
                r.getMortgageMonthlyPayment());
    }
}
