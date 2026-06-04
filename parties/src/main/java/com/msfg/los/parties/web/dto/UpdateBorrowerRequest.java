package com.msfg.los.parties.web.dto;

public record UpdateBorrowerRequest(
        String firstName,
        String lastName,
        Boolean primary) {
}
