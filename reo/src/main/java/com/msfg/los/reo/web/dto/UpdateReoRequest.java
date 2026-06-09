package com.msfg.los.reo.web.dto;

import com.msfg.los.loan.domain.OccupancyType;
import com.msfg.los.loan.domain.PropertyType;
import com.msfg.los.platform.reference.UsStateCode;
import com.msfg.los.reo.domain.ReoPropertyStatus;

import java.math.BigDecimal;
import java.util.UUID;

public record UpdateReoRequest(
        UUID ownerBorrowerId,
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
