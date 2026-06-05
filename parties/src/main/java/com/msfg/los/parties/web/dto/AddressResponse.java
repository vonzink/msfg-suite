package com.msfg.los.parties.web.dto;

import com.msfg.los.parties.domain.AddressType;
import com.msfg.los.parties.domain.BorrowerAddress;
import com.msfg.los.parties.domain.OwnershipType;
import com.msfg.los.platform.reference.UsStateCode;

import java.math.BigDecimal;
import java.util.UUID;

public record AddressResponse(
        UUID id,
        UUID borrowerId,
        AddressType addressType,
        int ordinal,
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
        Boolean rentVerified) {

    public static AddressResponse from(BorrowerAddress a) {
        return new AddressResponse(
                a.getId(),
                a.getBorrowerId(),
                a.getAddressType(),
                a.getOrdinal(),
                a.getAddressLine1(),
                a.getAddressLine2(),
                a.getCity(),
                a.getState(),
                a.getPostalCode(),
                a.getCountry(),
                a.getOwnershipType(),
                a.getResidencyDurationYears(),
                a.getResidencyDurationMonths(),
                a.getRentAmount(),
                a.getRentVerified());
    }
}
