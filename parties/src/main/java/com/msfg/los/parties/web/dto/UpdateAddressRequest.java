package com.msfg.los.parties.web.dto;

import com.msfg.los.parties.domain.OwnershipType;
import com.msfg.los.platform.reference.UsStateCode;

import java.math.BigDecimal;

public record UpdateAddressRequest(
        String addressLine1,
        String addressLine2,
        String city,
        UsStateCode state,
        String postalCode,
        String country,
        OwnershipType ownershipType,
        Integer residencyDurationYears,
        Integer residencyDurationMonths,
        BigDecimal rentAmount,
        Boolean rentVerified) {}
