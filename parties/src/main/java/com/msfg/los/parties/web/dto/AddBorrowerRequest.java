package com.msfg.los.parties.web.dto;

import jakarta.validation.constraints.NotBlank;

public record AddBorrowerRequest(
        @NotBlank String firstName,
        @NotBlank String lastName,
        boolean primary) {
}
