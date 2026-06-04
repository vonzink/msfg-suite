package com.msfg.los.parties.web.dto;

import com.msfg.los.parties.domain.BorrowerParty;

import java.util.UUID;

public record BorrowerResponse(
        UUID id,
        UUID loanId,
        boolean primary,
        int ordinal,
        String firstName,
        String lastName) {

    public static BorrowerResponse from(BorrowerParty b) {
        return new BorrowerResponse(
                b.getId(),
                b.getLoanId(),
                b.isPrimary(),
                b.getOrdinal(),
                b.getFirstName(),
                b.getLastName());
    }
}
